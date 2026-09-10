package com.lazydoglab.zisee.rtc.compute

import android.opengl.GLES20
import com.lazydoglab.zisee.ar.render.ArFramePool
import com.lazydoglab.zisee.core.logging.AppEvent
import com.lazydoglab.zisee.core.logging.AppLogger
import org.webrtc.*

data class PreprocessStats(val frames: Long = 0, val bypassed: Long = 0, val p95Ms: Double? = null,
    val failed: Boolean = false)

/** One processor per camera source. VideoSource serializes callbacks/setSink. Work runs on a
 * dedicated shared EGL context synchronously: no queued camera frames, no main-thread pixel work.
 * Output ownership uses the existing tested bounded texture pool; all consumers may retain frames.
 */
class CameraQualityProcessor(shared: EglBase.Context, private val logger: AppLogger, private val name: String,
    private val elapsedClockNs: () -> Long = System::nanoTime) : VideoProcessor, AutoCloseable {
    private val helper = requireNotNull(SurfaceTextureHelper.create("Quality-$name", shared))
    private var sink: VideoSink? = null
    @Volatile var decision = ComputeDecision(ComputeLevel.C1, ComputeReason.STARTUP)
    @Volatile private var allowed = true
    private val epoch = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var closed = false
    @Volatile var stats = PreprocessStats(); private set
    private var shader: CameraQualityShader? = null
    private var pool: ArFramePool? = null
    private var history: List<GlTextureFrameBuffer> = emptyList()
    private var historyCount = 0
    private var index = 0
    private var historyKey = ""
    private var lastTimestamp = 0L
    private val durations = ArrayDeque<Double>()
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
            } catch (error: RuntimeException) {
                try { shader?.close() } finally { helper.dispose() }
                throw error
            }
        }
    }

    /** Call on RTC owner before changing camera/AR ownership, so an AR frame is never filtered. */
    fun setAllowed(value: Boolean) { if (allowed != value) { allowed = value; epoch.incrementAndGet() } }
    fun resetHistory() { epoch.incrementAndGet() }
    override fun setSink(sink: VideoSink?) { this.sink = sink }
    override fun onCapturerStarted(success: Boolean) { resetHistory() }
    override fun onCapturerStopped() { resetHistory() }

    override fun onFrameCaptured(frame: VideoFrame) {
        val target = sink ?: return
        if (closed) return
        val buffer = frame.buffer as? VideoFrame.TextureBuffer
        val config = decision
        if (!allowed || config.level == ComputeLevel.C0 || stats.failed || buffer == null ||
            frame.buffer.width.toLong() * frame.buffer.height > 1920L * 1080) {
            epoch.incrementAndGet()
            stats = stats.copy(bypassed = stats.bypassed + 1)
            target.onFrame(frame)
            return
        }
        val started = elapsedClockNs()
        val output = try {
            ThreadUtils.invokeAtFrontUninterruptibly(helper.handler, java.util.concurrent.Callable {
                try { process(frame, buffer, config) }
                // Even pool exhaustion/errors may have queued input reads. Complete them before
                // the capturer can release/reuse the borrowed OES texture.
                finally { GLES20.glFinish() }
            })
        } catch (error: RuntimeException) {
            stats = stats.copy(failed = true)
            logger.error(AppEvent.RTC_COMPUTE_BYPASS, "gpu")
            null
        }
        // Includes GL-thread waiting, analysis and GPU completion; not just CPU submission time.
        val elapsedMs = (elapsedClockNs() - started) / 1_000_000.0
        durations.addLast(elapsedMs)
        if (durations.size > 60) durations.removeFirst()
        val p95 = durations.sorted()[(durations.size * 95 + 99) / 100 - 1]
        // A gross deadline miss must not wait for 30 samples on an unprofiled/slow GPU.
        val overload = elapsedMs > 20.0 || (durations.size >= 30 && p95 > 5.0)
        stats = stats.copy(frames = stats.frames + if (output != null) 1 else 0,
            bypassed = stats.bypassed + if (output == null) 1 else 0, p95Ms = p95, failed = stats.failed || overload)
        if (overload) logger.info(AppEvent.RTC_COMPUTE_BYPASS, "$name:budget")
        if (output == null) target.onFrame(frame)
        else {
            val processed = VideoFrame(output, frame.rotation, frame.timestampNs)
            try { target.onFrame(processed) } finally { processed.release() }
        }
    }

    private fun process(frame: VideoFrame, buffer: VideoFrame.TextureBuffer, config: ComputeDecision): VideoFrame.TextureBuffer? {
        val width = buffer.width; val height = buffer.height
        if (shader == null) shader = CameraQualityShader()
        if (pool == null) pool = ArFramePool(helper.handler) { helper.dispose() }
        if (history.isEmpty()) history = List(3) { GlTextureFrameBuffer(GLES20.GL_RGBA) }
        val matrix = FloatArray(9).also { buffer.transformMatrix.getValues(it) }
        val key = "${epoch.get()}:$width:$height:${frame.rotation}:${matrix.contentHashCode()}"
        if (historyKey != key || frame.timestampNs - lastTimestamp !in 1..250_000_000) {
            historyKey = key; historyCount = 0
            scenePolicy.reset(); sceneDecision = SceneDecision(); sceneSampleMs = 0
        }
        lastTimestamp = frame.timestampNs
        val current = history[index]
        current.setSize(width, height)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, current.frameBufferId)
        try { shader!!.resize(buffer, width, height) }
        finally { GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0) }
        val previous = if (historyCount >= 1) history[(index + 2) % 3] else current
        val older = if (historyCount >= 2) history[(index + 1) % 3] else previous
        val nowMs = System.nanoTime() / 1_000_000
        if (historyCount > 0 && nowMs - lastAnalysisMs >= 500) {
            lastAnalysisMs = nowMs
            val analysis = analysisBuffer ?: GlTextureFrameBuffer(GLES20.GL_RGBA).also { analysisBuffer = it }
            analysis.setSize(16, 9)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, analysis.frameBufferId)
            try {
                shader!!.analyze(current.textureId, previous.textureId, width, height)
                // Only 576 bytes at 2Hz; never read back video-sized pixels. Included in the budget.
                analysisPixels.clear()
                GLES20.glReadPixels(0, 0, 16, 9, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, analysisPixels)
                GlUtil.checkNoGLES2Error("quality analysis")
                fun mean(channel: Int) = (0 until 144).sumOf { analysisPixels.get(it * 4 + channel).toInt() and 255 } / (144f * 255)
                sceneDecision = scenePolicy.update(nowMs, SceneObservation(mean(0), mean(1), mean(2), mean(3)))
                sceneSampleMs = nowMs
            } finally { GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0) }
        }
        val result = pool!!.capture(width, height) {
            shader!!.denoise(current.textureId, previous.textureId, older.textureId, width, height,
                if (historyCount == 0) 0f else config.denoiseStrength)
            true
        }
        index = (index + 1) % 3
        historyCount = (historyCount + 1).coerceAtMost(2)
        return result
    }

    /** Detach from VideoSource first. Pool defers EGL teardown until the last output is released. */
    override fun close() {
        if (closed) return
        closed = true
        ThreadUtils.invokeAtFrontUninterruptibly(helper.handler) {
            shader?.close(); shader = null
            history.forEach { it.release() }; history = emptyList()
            analysisBuffer?.release(); analysisBuffer = null
            val activePool = pool
            if (activePool == null) helper.dispose() else activePool.close()
        }
    }
}
