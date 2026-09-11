package com.lazydoglab.zisee.rtc.compute

import android.opengl.GLES20
import com.lazydoglab.zisee.ar.render.ArFramePool
import com.lazydoglab.zisee.core.logging.AppEvent
import com.lazydoglab.zisee.core.logging.AppLogger
import org.webrtc.*

data class PreprocessStats(val frames: Long = 0, val bypassed: Long = 0, val p95Ms: Double? = null,
    val failed: Boolean = false, val tier: ProcessingTier = ProcessingTier.FULL,
    val state: GuardState = GuardState.NORMAL, val pressure: ComputePressure = ComputePressure.NONE,
    val roiAppliedFrames: Long = 0)

/** One processor per camera source. VideoSource serializes callbacks/setSink. Work runs on a
 * dedicated shared EGL context synchronously: no queued camera frames, no main-thread pixel work.
 * Output ownership uses the existing tested bounded texture pool; all consumers may retain frames.
 */
class CameraQualityProcessor(shared: EglBase.Context, private val logger: AppLogger, private val name: String,
    private val onSourceFrame: (Long) -> Unit = {},
    roiDetector: RoiDetector? = RoiDetectorProvider.create(),
    private val roiQpMaps: RoiQpMapRegistry? = null,
    private val elapsedClockNs: () -> Long = System::nanoTime) : VideoProcessor, AutoCloseable {
    private val helper = requireNotNull(SurfaceTextureHelper.create("Quality-$name", shared))
    private var sink: VideoSink? = null
    @Volatile var decision = ComputeDecision(ComputeLevel.C1, ComputeReason.STARTUP)
    @Volatile private var allowed = true
    private val epoch = java.util.concurrent.atomic.AtomicLong()
    private val evidenceEpoch = java.util.concurrent.atomic.AtomicLong()
    private var observedEvidenceEpoch = 0L // Capture-thread only, like the guard.
    private val roiLock = Any()
    private val roi = roiDetector?.let { detector ->
        RoiAnalyzer(detector, { stage -> logger.error(AppEvent.RTC_COMPUTE_BYPASS, "$name:roi:$stage") })
    }
    private var roiSampler: RoiTextureSampler? = null
    @Volatile private var roiSamplingFailed = false
    @Volatile private var roiFaces: Int? = null
    val roiDiagnostic: String get() = roi?.let {
        "${it.state}/faces=${roiFaces ?: -1}/applied=${stats.roiAppliedFrames}/readbackFailed=$roiSamplingFailed"
    } ?: "OFF"
    @Volatile private var closed = false
    @Volatile var stats = PreprocessStats(); private set
    private var shader: CameraQualityShader? = null
    private var pool: ArFramePool? = null
    private var history: List<GlTextureFrameBuffer> = emptyList()
    private var historyCount = 0
    private var index = 0
    private var historyKey = ""
    private var lastTimestamp = 0L
    private val guard = ProcessingGuard()
    /** Auxiliary readbacks (scene, ROI) run only while the guard allows the FULL tier. */
    @Volatile private var auxAllowed = false
    private var gpuTimer: GpuTimer? = null
    private var preparedWidth = 0
    private var preparedHeight = 0
    private var resizedThisFrame = false
    private val frameStats = ComputeStatsWindow()
    private val sceneCost = LatencyWindow()
    private val roiCost = LatencyWindow()
    private var lastStatsLogNs = 0L
    private var processedSerial = 0L
    private var sceneFailed = false
    @Volatile private var auxRunning = false
    @Volatile private var lastAuxEndNs = 0L
    @Volatile private var lastAuxUs = 0L
    private var lateLogWindowNs = 0L
    private var lateLogCount = 0
    /** GL-thread split of the submit phase, see [SPLIT_LABEL]; read on the capture thread after invoke. */
    private val split = LongArray(SPLIT_FIELDS)
    private var markNs = 0L
    private val submitSplit = SplitWindow(SPLIT_FIELDS)
    /** Output texture id → size it was last written at (GL thread), to spot first use after a resize. */
    private val slotSizes = HashMap<Int, Long>()
    @Volatile private var outFresh = false
    private var processedSize: String? = null
    private var lastResizeNs = 0L
    private var lastFrameTimestampNs = 0L
    /** GL-thread scheduler counters: splits submit into on-CPU, runqueue wait and blocked sleep. */
    private var schedStat: ThreadSchedStat? = null
    private val schedStart = LongArray(2)
    private val schedEnd = LongArray(2)
    @Volatile private var subCpuUs = -1L
    @Volatile private var subRunqUs = -1L
    @Volatile private var glThreadPriority = Int.MIN_VALUE
    private val schedSplit = SplitWindow(2)
    private val scenePolicy = ScenePolicy()
    private var analysisBuffer: GlTextureFrameBuffer? = null
    private val analysisPixels = java.nio.ByteBuffer.allocateDirect(16 * 9 * 4)
    private var lastAnalysisMs = 0L
    @Volatile private var sceneSampleMs = 0L
    @Volatile private var sceneDecision = SceneDecision()
    val scene: SceneDecision get() = if (allowed && decision.level != ComputeLevel.C0 && !stats.failed &&
        System.nanoTime() / 1_000_000 - sceneSampleMs in 0..1_500) sceneDecision else SceneDecision()

    init {
        ThreadUtils.invokeAtFrontUninterruptibly(helper.handler) {
            try {
                shader = CameraQualityShader()
                shader!!.prepare()
                gpuTimer = GpuTimer.createOrNull()
                schedStat = ThreadSchedStat.openOrNull()
                glThreadPriority = android.os.Process.getThreadPriority(android.os.Process.myTid())
            } catch (error: RuntimeException) {
                roi?.close()
                try { shader?.close() } finally { helper.dispose() }
                throw error
            }
        }
    }

    /** Call on RTC owner before changing camera/AR ownership, so an AR frame is never filtered. */
    fun setAllowed(value: Boolean) { if (allowed != value) { allowed = value; resetHistory() } }
    fun resetHistory() { evidenceEpoch.incrementAndGet(); invalidateHistory() }
    private fun invalidateHistory() { synchronized(roiLock) { epoch.incrementAndGet(); roi?.configure(null); roiFaces = null } }
    override fun setSink(sink: VideoSink?) { this.sink = sink }
    override fun onCapturerStarted(success: Boolean) { resetHistory() }
    override fun onCapturerStopped() { resetHistory() }

    override fun onFrameCaptured(frame: VideoFrame) {
        val target = sink ?: return
        if (closed) return
        onSourceFrame(frame.timestampNs)
        // Record neutral first. A successful ROI lookup below replaces it for this source; a
        // same-timestamp frame from another camera becomes ambiguous and stays neutral.
        roiQpMaps?.record(frame.timestampNs, name, null)
        val arrivalNs = System.nanoTime()
        // Camera timestamps share the monotonic clock: age shows how early the frame reaches us.
        val frameAgeMs = (arrivalNs - frame.timestampNs) / 1_000_000
        val frameDeltaMs = if (lastFrameTimestampNs == 0L) -1 else (frame.timestampNs - lastFrameTimestampNs) / 1_000_000
        lastFrameTimestampNs = frame.timestampNs
        // One injectable clock read per side of the frame, so a controlled test clock advances once per frame.
        val arrivalClockNs = elapsedClockNs()
        val buffer = frame.buffer as? VideoFrame.TextureBuffer
        val config = decision
        val frameEpoch = epoch.get()
        // Policy C0 is external pressure (thermal/encoder); the processor's own health is the guard's.
        val eligible = allowed && config.level != ComputeLevel.C0 &&
            frame.buffer.width.toLong() * frame.buffer.height <= 1920L * 1080
        val currentEvidenceEpoch = evidenceEpoch.get()
        if (currentEvidenceEpoch != observedEvidenceEpoch || !eligible || buffer == null) {
            observedEvidenceEpoch = currentEvidenceEpoch
            guard.interruptEvidence()
            frameStats.clear(); submitSplit.clear(); schedSplit.clear()
        }
        val action = if (eligible && buffer != null) guard.action() else FrameAction.BYPASS
        if (action == FrameAction.BYPASS || buffer == null) {
            auxAllowed = false
            // The nine normal bypass frames between OFF probes must preserve probe evidence.
            invalidateHistory()
            stats = stats.copy(bypassed = stats.bypassed + 1, p95Ms = guard.addedP95Ms)
            target.onFrame(frame)
            return
        }
        // A shadow frame measures the lightest real workload but the original frame is delivered.
        val tier = if (action == FrameAction.SHADOW) ProcessingTier.RESIZE_ONLY else guard.tier
        auxAllowed = tier == ProcessingTier.FULL
        // Context for late-frame diagnosis: was an auxiliary GL task or detector inference competing?
        val auxBusyAtArrival = auxRunning
        val auxAgoMs = if (lastAuxEndNs == 0L) -1 else (arrivalNs - lastAuxEndNs) / 1_000_000
        val detectorBusyAtArrival = roi?.state == RoiState.BUSY
        val queuedNs = System.nanoTime()
        var enteredNs = 0L; var submittedNs = 0L; var finishedNs = 0L; var gpuNs: Long? = null
        val output = try {
            ThreadUtils.invokeAtFrontUninterruptibly(helper.handler, java.util.concurrent.Callable {
                enteredNs = System.nanoTime()
                val schedStarted = schedStat?.read(schedStart) == true
                resizedThisFrame = false
                split.fill(0)
                outFresh = false
                gpuTimer?.begin()
                split[SPLIT_TIMER_BEGIN] = (System.nanoTime() - enteredNs) / 1000
                var owned: VideoFrame.TextureBuffer? = null
                try {
                    try { owned = process(frame, buffer, config, frameEpoch, tier) }
                    // Complete shared output writes and borrowed OES reads even on processing failure.
                    finally {
                        val endStarted = System.nanoTime()
                        gpuTimer?.end()
                        submittedNs = System.nanoTime()
                        split[SPLIT_TIMER_END] = (submittedNs - endStarted) / 1000
                        if (schedStarted && schedStat?.read(schedEnd) == true) {
                            subCpuUs = (schedEnd[0] - schedStart[0]) / 1000
                            subRunqUs = (schedEnd[1] - schedStart[1]) / 1000
                        } else { subCpuUs = -1; subRunqUs = -1 }
                        GLES20.glFinish()
                        finishedNs = System.nanoTime()
                        gpuNs = gpuTimer?.resultNs()
                    }
                    owned.also { owned = null } // Transfer only after GPU completion succeeds.
                } finally { owned?.release() }
            })
        } catch (error: RuntimeException) {
            guard.hardDisable()
            logger.error(AppEvent.RTC_COMPUTE_BYPASS, "gpu")
            roi?.close(); roiFaces = null
            null
        }
        // Base pipeline only (readbacks run as separate GL tasks); includes GL-thread waiting and
        // GPU completion, not just CPU submission time.
        val resumedNs = System.nanoTime()
        val splitRow = split.copyOf()
        val schedRow = longArrayOf(subCpuUs, subRunqUs)
        val frameFresh = outFresh
        if (resizedThisFrame) {
            val size = "${buffer.width}x${buffer.height}"
            logger.info(AppEvent.RTC_COMPUTE_RESIZE, "$name:${processedSize ?: "none"}>$size" +
                ":allocUs=${splitRow[SPLIT_ALLOC]}:$action:$tier")
            processedSize = size
            lastResizeNs = resumedNs
        }
        val addedMs = (elapsedClockNs() - arrivalClockNs) / 1_000_000.0
        val phases = if (finishedNs == 0L) null else FramePhases(preUs = (queuedNs - arrivalNs) / 1000,
            queueUs = (enteredNs - queuedNs) / 1000, submitUs = (submittedNs - enteredNs) / 1000,
            waitUs = (finishedNs - submittedNs) / 1000, resumeUs = (resumedNs - finishedNs) / 1000,
            gpuUs = gpuNs?.div(1000) ?: -1, addedUs = (resumedNs - arrivalNs) / 1000)
        val transition = if (phases == null) null else guard.record(GuardSample(addedMs, gpuNs?.let { it / 1_000_000.0 },
            waitMs = phases.waitUs / 1000.0, submitMs = phases.submitUs / 1000.0, queueMs = phases.queueUs / 1000.0,
            externalMs = (phases.preUs + phases.resumeUs) / 1000.0),
            arrivalClockNs / 1_000_000, oneTimeCost = resizedThisFrame)
        if (phases != null && addedMs > ProcessingGuard.LATE_MS) {
            logLate(phases, splitRow, action, tier, auxBusyAtArrival, auxAgoMs, detectorBusyAtArrival,
                LateContext(arrivalNs, frameAgeMs, frameDeltaMs, frameFresh, schedRow[0], schedRow[1]), buffer.width, buffer.height)
        }
        if (action == FrameAction.PROCESS && guard.lastCounted && phases != null) {
            frameStats.add(phases)
            submitSplit.add(splitRow)
            if (schedRow[0] >= 0) schedSplit.add(schedRow)
        }
        if (transition != null) logTransition(transition, buffer.width, buffer.height)
        val delivered = action == FrameAction.PROCESS && output != null
        stats = stats.copy(frames = stats.frames + if (delivered) 1 else 0,
            bypassed = stats.bypassed + if (delivered) 0 else 1, p95Ms = guard.addedP95Ms,
            failed = guard.hardDisabled, tier = guard.tier, state = guard.state, pressure = guard.pressure)
        maybeLogStats(buffer.width, buffer.height)
        if (action == FrameAction.SHADOW) { output?.release(); target.onFrame(frame); return }
        if (output == null) target.onFrame(frame)
        else {
            val processed = VideoFrame(output, frame.rotation, frame.timestampNs)
            try { target.onFrame(processed) } finally { processed.release() }
        }
    }

    private fun logTransition(transition: GuardTransition, width: Int, height: Int) {
        val p = frameStats.summary()?.p95
        val late = frameStats.countOver((ProcessingGuard.LATE_MS * 1000).toLong())
        val samples = frameStats.size
        val sub = submitSplit.p95()?.joinToString("/") ?: "-1"
        frameStats.clear()
        submitSplit.clear()
        schedSplit.clear()
        logger.info(AppEvent.RTC_COMPUTE_TIER, "$name:${transition.from}>${transition.to}:${transition.cause}" +
            ":${transition.pressure}:${guard.state}:climb=${guard.climbDelayMs() ?: -1}:late=$late/$samples" +
            ":added95=${p?.addedUs ?: -1}:pre95=${p?.preUs ?: -1}:queue95=${p?.queueUs ?: -1}" +
            ":submit95=${p?.submitUs ?: -1}:wait95=${p?.waitUs ?: -1}:resume95=${p?.resumeUs ?: -1}" +
            ":gpu95=${p?.gpuUs ?: -1}:sub95($SPLIT_LABEL)=$sub:${width}x$height")
    }

    /** One line per late frame (rate-limited) so a device log shows where each late frame's time went. */
    private class LateContext(val arrivalNs: Long, val ageMs: Long, val deltaMs: Long, val outFresh: Boolean,
                              val subCpuUs: Long, val subRunqUs: Long)

    private fun logLate(p: FramePhases, sub: LongArray, action: FrameAction, tier: ProcessingTier, auxBusy: Boolean,
                        auxAgoMs: Long, detectorBusy: Boolean, context: LateContext, width: Int, height: Int) {
        val now = System.nanoTime()
        if (now - lateLogWindowNs > LATE_LOG_WINDOW_NS) { lateLogWindowNs = now; lateLogCount = 0 }
        if (++lateLogCount > LATE_LOG_MAX) return
        val aux = if (auxBusy) "running" else "${auxAgoMs}ms_ago"
        // `at` is the frame's arrival in monotonic ms, comparable with RTC_COMPUTE_ENCODE_SLOW's `at`.
        logger.info(AppEvent.RTC_COMPUTE_LATE, "$name:$action:$tier:at=${context.arrivalNs / 1_000_000}" +
            ":added=${p.addedUs}:pre=${p.preUs}" +
            ":queue=${p.queueUs}:submit=${p.submitUs}:sub($SPLIT_LABEL)=${sub.joinToString("/")}" +
            ":wait=${p.waitUs}:resume=${p.resumeUs}:gpu=${p.gpuUs}:aux=$aux:auxUs=$lastAuxUs" +
            ":detector=${if (detectorBusy) "busy" else "idle"}:age=${context.ageMs}ms:fdelta=${context.deltaMs}ms" +
            ":sinceResize=${if (lastResizeNs == 0L) -1 else (context.arrivalNs - lastResizeNs) / 1_000_000}ms" +
            ":outFresh=${context.outFresh}" +
            // Submit wall time = on CPU + waiting for a CPU (preempted) + blocked (lock/fence sleep).
            (if (context.subCpuUs < 0) ":subSched=unavailable" else ":subCpu=${context.subCpuUs}" +
                ":subRunq=${context.subRunqUs}:subSleep=${p.submitUs - context.subCpuUs - context.subRunqUs}") +
            ":${width}x$height")
    }

    private fun lap(field: Int) {
        val now = System.nanoTime()
        split[field] = (now - markNs) / 1000
        markNs = now
    }

    /** Diagnostic: an explicit flush after each pass separates recording a draw from submitting it. */
    private fun flushLap(field: Int) {
        GLES20.glFlush()
        lap(field)
    }

    private fun markFresh(output: VideoFrame.TextureBuffer?, width: Int, height: Int) {
        val key = (width.toLong() shl 32) or height.toLong()
        outFresh = output != null && slotSizes.put(output.textureId, key) != key
    }

    /** One glGetError per frame (it can block until the driver thread drains queued commands). */
    private fun checkFrameErrors() {
        GlUtil.checkNoGLES2Error("quality frame")
        lap(SPLIT_ERR)
    }

    private fun process(frame: VideoFrame, buffer: VideoFrame.TextureBuffer, config: ComputeDecision, frameEpoch: Long,
                        tier: ProcessingTier): VideoFrame.TextureBuffer? {
        markNs = System.nanoTime()
        val width = buffer.width; val height = buffer.height
        if (shader == null) shader = CameraQualityShader()
        if (pool == null) pool = ArFramePool(helper.handler) { helper.dispose() }
        if (history.isEmpty()) history = List(3) { GlTextureFrameBuffer(GLES20.GL_RGBA) }
        if (width != preparedWidth || height != preparedHeight) {
            // Reallocate all textures on this one frame instead of whenever a slot is next used.
            history.forEach { it.setSize(width, height) }
            pool!!.prepare(width, height)
            preparedWidth = width; preparedHeight = height
            resizedThisFrame = true
        }
        lap(SPLIT_ALLOC)
        if (tier == ProcessingTier.RESIZE_ONLY) {
            // No history or auxiliary work; a later denoising tier restarts from a clean temporal state.
            historyCount = 0
            processedSerial++
            val out = pool!!.capture(width, height, finish = false) { shader!!.resize(buffer, width, height, check = false); true }
            try {
                lap(SPLIT_OUT)
                flushLap(SPLIT_OUT_FLUSH)
                markFresh(out, width, height)
                checkFrameErrors()
                return out
            } catch (error: Throwable) { out?.release(); throw error }
        }
        val matrix = FloatArray(9).also { buffer.transformMatrix.getValues(it) }
        val key = "${epoch.get()}:$width:$height:${frame.rotation}:${matrix.contentHashCode()}"
        if (historyKey != key || frame.timestampNs - lastTimestamp !in 1..250_000_000) {
            historyKey = key; historyCount = 0
            scenePolicy.reset(); sceneDecision = SceneDecision(); sceneSampleMs = 0
            synchronized(roiLock) { roi?.configure(null); roiFaces = null }
        }
        lastTimestamp = frame.timestampNs
        val current = history[index]
        current.setSize(width, height)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, current.frameBufferId)
        try { shader!!.resize(buffer, width, height, check = false) }
        finally { GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0) }
        lap(SPLIT_HIST)
        flushLap(SPLIT_HIST_FLUSH)
        val previous = if (historyCount >= 1) history[(index + 2) % 3] else current
        val older = if (historyCount >= 2) history[(index + 1) % 3] else previous
        val roiPlan = if (tier == ProcessingTier.FULL) currentRoiPlan(
            RoiGeometry(frameEpoch, width, height, frame.rotation), frame.timestampNs) else null
        if (roiPlan != null) roiQpMaps?.record(frame.timestampNs, name,
            RoiQpMapPlanner.create(width, height, roiPlan))
        val result = pool!!.capture(width, height, finish = false) {
            shader!!.denoise(current.textureId, previous.textureId, older.textureId, width, height,
                if (historyCount == 0) 0f else config.denoiseStrength, roiPlan, check = false)
            true
        }
        if (roiPlan != null && result != null)
            stats = stats.copy(roiAppliedFrames = stats.roiAppliedFrames + 1)
        try {
            lap(SPLIT_OUT)
            flushLap(SPLIT_OUT_FLUSH)
            markFresh(result, width, height)
            scheduleAux(frame, tier, frameEpoch, current.textureId, previous.textureId, width, height)
            lap(SPLIT_SCHED)
            checkFrameErrors()
            index = (index + 1) % 3
            historyCount = (historyCount + 1).coerceAtMost(2)
            return result
        } catch (error: Throwable) { result?.release(); throw error }
    }

    private fun currentRoiPlan(geometry: RoiGeometry, timestampNs: Long): RoiBackgroundPlan? =
        synchronized(roiLock) {
            val regions = roi?.regions(geometry, timestampNs) ?: return@synchronized null
            roiFaces = regions.size
            RoiBackgroundPlan.create(regions, geometry.rotation)
        }

    /** Readbacks stall the GL pipeline, so they run as a separate GL task after the frame's own work. */
    private fun scheduleAux(frame: VideoFrame, tier: ProcessingTier, frameEpoch: Long,
                            current: Int, previous: Int, width: Int, height: Int) {
        val serial = ++processedSerial
        val analyzer = roi
        val full = tier == ProcessingTier.FULL
        // Gated by the workload tier and its own measured cost, not by a compute level.
        val roiWanted = analyzer != null && full && !roiSamplingFailed
        if (analyzer != null && !roiWanted) synchronized(roiLock) { analyzer.configure(null); roiFaces = null }
        val sceneWanted = full && !sceneFailed && historyCount > 0 && System.nanoTime() / 1_000_000 - lastAnalysisMs >= 500
        if (!sceneWanted && !roiWanted) return
        val geometry = RoiGeometry(frameEpoch, width, height, frame.rotation)
        val timestampNs = frame.timestampNs
        helper.handler.post {
            // A newer frame may already be overwriting these history textures: skip, never read them.
            if (closed || shader == null || serial != processedSerial || frameEpoch != epoch.get()) return@post
            val auxStarted = System.nanoTime()
            auxRunning = true
            try {
                if (sceneWanted) analyzeScene(current, previous, width, height)
                if (roiWanted) {
                    val started = System.nanoTime()
                    if (analyzeRoi(current, geometry, timestampNs, frameEpoch)) roiCost.add((System.nanoTime() - started) / 1000)
                }
            } finally {
                val ended = System.nanoTime()
                lastAuxUs = (ended - auxStarted) / 1000
                lastAuxEndNs = ended
                auxRunning = false
            }
        }
    }

    private fun analyzeScene(current: Int, previous: Int, width: Int, height: Int) {
        val started = System.nanoTime()
        val nowMs = started / 1_000_000
        lastAnalysisMs = nowMs
        try {
            val analysis = analysisBuffer ?: GlTextureFrameBuffer(GLES20.GL_RGBA).also { analysisBuffer = it }
            analysis.setSize(16, 9)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, analysis.frameBufferId)
            shader!!.analyze(current, previous, width, height)
            // Only 576 bytes at 2Hz; never read back video-sized pixels.
            analysisPixels.clear()
            GLES20.glReadPixels(0, 0, 16, 9, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, analysisPixels)
            GlUtil.checkNoGLES2Error("quality analysis")
            fun mean(channel: Int) = (0 until 144).sumOf { analysisPixels.get(it * 4 + channel).toInt() and 255 } / (144f * 255)
            sceneDecision = scenePolicy.update(nowMs, SceneObservation(mean(0), mean(1), mean(2), mean(3)))
            sceneSampleMs = nowMs
        } catch (_: RuntimeException) {
            // Auxiliary only: stop scene analysis, keep the base pipeline.
            sceneFailed = true
            scenePolicy.reset(); sceneDecision = SceneDecision(); sceneSampleMs = 0
            logger.error(AppEvent.RTC_COMPUTE_BYPASS, "$name:aux:scene")
        } finally { GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0) }
        sceneCost.add((System.nanoTime() - started) / 1000)
    }

    /** @return whether a detector input was sampled, i.e. a readback happened. */
    private fun analyzeRoi(texture: Int, geometry: RoiGeometry, timestampNs: Long, frameEpoch: Long): Boolean {
        val analyzer = roi ?: return false
        synchronized(roiLock) {
            if (!allowed || closed || frameEpoch != epoch.get() || !auxAllowed || roiSamplingFailed) {
                analyzer.configure(null); roiFaces = null; return false
            }
            analyzer.configure(geometry)
            roiFaces = analyzer.regions(geometry, timestampNs)?.size
        }
        if (!analyzer.canSubmit()) return false
        return try {
            val sampler = roiSampler ?: RoiTextureSampler().also { roiSampler = it }
            val input = sampler.sample(texture, geometry, timestampNs)
            synchronized(roiLock) {
                if (allowed && !closed && frameEpoch == epoch.get() && auxAllowed) analyzer.submit(input)
                else input.close()
            }
            true
        } catch (_: RuntimeException) {
            roiSamplingFailed = true; roiFaces = null; analyzer.close()
            logger.error(AppEvent.RTC_COMPUTE_BYPASS, "$name:roi:readback")
            false
        }
    }

    private fun maybeLogStats(width: Int, height: Int) {
        val now = System.nanoTime()
        if (now - lastStatsLogNs < STATS_LOG_INTERVAL_NS) return
        val summary = frameStats.summary()?.takeIf { it.samples >= 30 } ?: return
        lastStatsLogNs = now
        fun pair(select: (FramePhases) -> Long) = "${select(summary.p50)}/${select(summary.p95)}"
        logger.info(AppEvent.RTC_COMPUTE_STATS, "$name:${decision.level}:${guard.tier}:n=${summary.samples}" +
            ":late=${frameStats.countOver((ProcessingGuard.LATE_MS * 1000).toLong())}" +
            ":added=${pair { it.addedUs }}:pre=${pair { it.preUs }}:queue=${pair { it.queueUs }}" +
            ":submit=${pair { it.submitUs }}:wait=${pair { it.waitUs }}:resume=${pair { it.resumeUs }}" +
            ":gpu=${pair { it.gpuUs }}:sub95($SPLIT_LABEL)=${submitSplit.p95()?.joinToString("/") ?: "-1"}" +
            ":scene=${sceneCost.summary()}:roi=${roiCost.summary()}" +
            ":det=${roi?.detectCost?.summary() ?: "0/-1"}:faces=${roiFaces ?: -1}" +
            ":climb=${guard.climbDelayMs() ?: -1}:sched95(cpu/runq)=${schedSplit.p95()?.joinToString("/") ?: "-1"}" +
            ":glPrio=$glThreadPriority:${width}x$height")
    }

    private companion object {
        const val STATS_LOG_INTERVAL_NS = 10_000_000_000L
        const val LATE_LOG_WINDOW_NS = 10_000_000_000L
        const val LATE_LOG_MAX = 10
        const val SPLIT_TIMER_BEGIN = 0
        const val SPLIT_ALLOC = 1
        const val SPLIT_HIST = 2
        const val SPLIT_HIST_FLUSH = 3
        const val SPLIT_OUT = 4
        const val SPLIT_OUT_FLUSH = 5
        const val SPLIT_SCHED = 6
        const val SPLIT_ERR = 7
        const val SPLIT_TIMER_END = 8
        const val SPLIT_FIELDS = 9
        const val SPLIT_LABEL = "tb/alloc/hist/hflush/out/oflush/sched/err/te"
    }

    /** Detach from VideoSource first. Pool defers EGL teardown until the last output is released. */
    override fun close() {
        if (closed) return
        closed = true
        roi?.close(); roiFaces = null
        ThreadUtils.invokeAtFrontUninterruptibly(helper.handler) {
            roiSampler?.close(); roiSampler = null
            gpuTimer?.close(); gpuTimer = null
            schedStat?.close(); schedStat = null
            shader?.close(); shader = null
            history.forEach { it.release() }; history = emptyList()
            analysisBuffer?.release(); analysisBuffer = null
            val activePool = pool
            if (activePool == null) helper.dispose() else activePool.close()
        }
    }
}
