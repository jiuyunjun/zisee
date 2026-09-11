package com.lazydoglab.zisee.rtc.compute

import android.opengl.GLES20
import com.lazydoglab.zisee.ar.render.ArFramePool
import com.lazydoglab.zisee.core.logging.AppEvent
import com.lazydoglab.zisee.core.logging.AppLogger
import org.webrtc.*

data class PreprocessStats(val frames: Long = 0, val bypassed: Long = 0, val p95Ms: Double? = null,
    val failed: Boolean = false, val tier: ProcessingTier = ProcessingTier.FULL,
    val state: GuardState = GuardState.NORMAL, val pressure: ComputePressure = ComputePressure.NONE)

/** One processor per camera source. VideoSource serializes callbacks/setSink. Work runs on a
 * dedicated shared EGL context synchronously: no queued camera frames, no main-thread pixel work.
 * Output ownership uses the existing tested bounded texture pool; all consumers may retain frames.
 */
class CameraQualityProcessor(shared: EglBase.Context, private val logger: AppLogger, private val name: String,
    private val onSourceFrame: (Long) -> Unit = {},
    roiDetector: RoiDetector? = RoiDetectorProvider.create(),
    private val elapsedClockNs: () -> Long = System::nanoTime) : VideoProcessor, AutoCloseable {
    private val helper = requireNotNull(SurfaceTextureHelper.create("Quality-$name", shared))
    private var sink: VideoSink? = null
    @Volatile var decision = ComputeDecision(ComputeLevel.C1, ComputeReason.STARTUP)
    @Volatile private var allowed = true
    private val epoch = java.util.concurrent.atomic.AtomicLong()
    private val roiLock = Any()
    private val roi = roiDetector?.let { detector ->
        RoiAnalyzer(detector, { stage -> logger.error(AppEvent.RTC_COMPUTE_BYPASS, "$name:roi:$stage") })
    }
    private var roiSampler: RoiTextureSampler? = null
    @Volatile private var roiSamplingFailed = false
    @Volatile private var roiFaces: Int? = null
    val roiDiagnostic: String get() = roi?.let { "${it.state}/faces=${roiFaces ?: -1}/readbackFailed=$roiSamplingFailed" } ?: "OFF"
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
            } catch (error: RuntimeException) {
                roi?.close()
                try { shader?.close() } finally { helper.dispose() }
                throw error
            }
        }
    }

    /** Call on RTC owner before changing camera/AR ownership, so an AR frame is never filtered. */
    fun setAllowed(value: Boolean) { if (allowed != value) { allowed = value; resetHistory() } }
    fun resetHistory() { synchronized(roiLock) { epoch.incrementAndGet(); roi?.configure(null); roiFaces = null } }
    override fun setSink(sink: VideoSink?) { this.sink = sink }
    override fun onCapturerStarted(success: Boolean) { resetHistory() }
    override fun onCapturerStopped() { resetHistory() }

    override fun onFrameCaptured(frame: VideoFrame) {
        val target = sink ?: return
        if (closed) return
        onSourceFrame(frame.timestampNs)
        val arrivalNs = System.nanoTime()
        // One injectable clock read per side of the frame, so a controlled test clock advances once per frame.
        val arrivalClockNs = elapsedClockNs()
        val buffer = frame.buffer as? VideoFrame.TextureBuffer
        val config = decision
        val frameEpoch = epoch.get()
        // Policy C0 is external pressure (thermal/encoder); the processor's own health is the guard's.
        val eligible = allowed && config.level != ComputeLevel.C0 &&
            frame.buffer.width.toLong() * frame.buffer.height <= 1920L * 1080
        val action = if (eligible && buffer != null) guard.action() else FrameAction.BYPASS
        if (action == FrameAction.BYPASS || buffer == null) {
            auxAllowed = false
            resetHistory()
            stats = stats.copy(bypassed = stats.bypassed + 1)
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
                resizedThisFrame = false
                split.fill(0)
                gpuTimer?.begin()
                split[SPLIT_TIMER_BEGIN] = (System.nanoTime() - enteredNs) / 1000
                try { process(frame, buffer, config, frameEpoch, tier) }
                // The frame's only GPU sync: completes the output before consumers on other contexts
                // sample it, and input reads (even on pool exhaustion/errors) before the capturer
                // can release/reuse the borrowed OES texture.
                finally {
                    val endStarted = System.nanoTime()
                    gpuTimer?.end()
                    submittedNs = System.nanoTime()
                    split[SPLIT_TIMER_END] = (submittedNs - endStarted) / 1000
                    GLES20.glFinish()
                    finishedNs = System.nanoTime()
                    gpuNs = gpuTimer?.resultNs()
                }
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
            logLate(phases, splitRow, action, tier, auxBusyAtArrival, auxAgoMs, detectorBusyAtArrival, buffer.width, buffer.height)
        }
        if (action == FrameAction.PROCESS && guard.lastCounted && phases != null) {
            frameStats.add(phases)
            submitSplit.add(splitRow)
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
        logger.info(AppEvent.RTC_COMPUTE_TIER, "$name:${transition.from}>${transition.to}:${transition.cause}" +
            ":${transition.pressure}:${guard.state}:climb=${guard.climbDelayMs() ?: -1}:late=$late/$samples" +
            ":added95=${p?.addedUs ?: -1}:pre95=${p?.preUs ?: -1}:queue95=${p?.queueUs ?: -1}" +
            ":submit95=${p?.submitUs ?: -1}:wait95=${p?.waitUs ?: -1}:resume95=${p?.resumeUs ?: -1}" +
            ":gpu95=${p?.gpuUs ?: -1}:sub95($SPLIT_LABEL)=$sub:${width}x$height")
    }

    /** One line per late frame (rate-limited) so a device log shows where each late frame's time went. */
    private fun logLate(p: FramePhases, sub: LongArray, action: FrameAction, tier: ProcessingTier, auxBusy: Boolean,
                        auxAgoMs: Long, detectorBusy: Boolean, width: Int, height: Int) {
        val now = System.nanoTime()
        if (now - lateLogWindowNs > LATE_LOG_WINDOW_NS) { lateLogWindowNs = now; lateLogCount = 0 }
        if (++lateLogCount > LATE_LOG_MAX) return
        val aux = if (auxBusy) "running" else "${auxAgoMs}ms_ago"
        logger.info(AppEvent.RTC_COMPUTE_LATE, "$name:$action:$tier:added=${p.addedUs}:pre=${p.preUs}" +
            ":queue=${p.queueUs}:submit=${p.submitUs}:sub($SPLIT_LABEL)=${sub.joinToString("/")}" +
            ":wait=${p.waitUs}:resume=${p.resumeUs}:gpu=${p.gpuUs}:aux=$aux:auxUs=$lastAuxUs:detector=${if (detectorBusy) "busy" else "idle"}:${width}x$height")
    }

    private fun lap(field: Int) {
        val now = System.nanoTime()
        split[field] = (now - markNs) / 1000
        markNs = now
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
            lap(SPLIT_OUT)
            checkFrameErrors()
            return out
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
        val previous = if (historyCount >= 1) history[(index + 2) % 3] else current
        val older = if (historyCount >= 2) history[(index + 1) % 3] else previous
        val result = pool!!.capture(width, height, finish = false) {
            shader!!.denoise(current.textureId, previous.textureId, older.textureId, width, height,
                if (historyCount == 0) 0f else config.denoiseStrength, check = false)
            true
        }
        lap(SPLIT_OUT)
        scheduleAux(frame, tier, frameEpoch, current.textureId, previous.textureId, width, height)
        lap(SPLIT_SCHED)
        checkFrameErrors()
        index = (index + 1) % 3
        historyCount = (historyCount + 1).coerceAtMost(2)
        return result
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
        val analysis = analysisBuffer ?: GlTextureFrameBuffer(GLES20.GL_RGBA).also { analysisBuffer = it }
        analysis.setSize(16, 9)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, analysis.frameBufferId)
        try {
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
            ":climb=${guard.climbDelayMs() ?: -1}:${width}x$height")
    }

    private companion object {
        const val STATS_LOG_INTERVAL_NS = 10_000_000_000L
        const val LATE_LOG_WINDOW_NS = 10_000_000_000L
        const val LATE_LOG_MAX = 10
        const val SPLIT_TIMER_BEGIN = 0
        const val SPLIT_ALLOC = 1
        const val SPLIT_HIST = 2
        const val SPLIT_OUT = 3
        const val SPLIT_SCHED = 4
        const val SPLIT_ERR = 5
        const val SPLIT_TIMER_END = 6
        const val SPLIT_FIELDS = 7
        const val SPLIT_LABEL = "tb/alloc/hist/out/sched/err/te"
    }

    /** Detach from VideoSource first. Pool defers EGL teardown until the last output is released. */
    override fun close() {
        if (closed) return
        closed = true
        roi?.close(); roiFaces = null
        ThreadUtils.invokeAtFrontUninterruptibly(helper.handler) {
            roiSampler?.close(); roiSampler = null
            gpuTimer?.close(); gpuTimer = null
            shader?.close(); shader = null
            history.forEach { it.release() }; history = emptyList()
            analysisBuffer?.release(); analysisBuffer = null
            val activePool = pool
            if (activePool == null) helper.dispose() else activePool.close()
        }
    }
}
