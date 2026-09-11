package com.lazydoglab.zisee.rtc

import android.opengl.GLES20
import com.lazydoglab.zisee.ar.render.ArFramePool
import com.lazydoglab.zisee.core.logging.AppEvent
import com.lazydoglab.zisee.core.logging.AppLogger
import com.lazydoglab.zisee.rtc.compute.*
import org.webrtc.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** Emulator fault injection; reflection is confined to tests so production has no fault hooks. */
internal object ComputeFailureSmoke {
    fun run() {
        sceneAllocationFailure()
        outputValidationFailure(ProcessingTier.FULL)
        outputValidationFailure(ProcessingTier.RESIZE_ONLY)
    }

    private fun field(name: String) = CameraQualityProcessor::class.java.getDeclaredField(name).apply { isAccessible = true }

    private class Fixture : AutoCloseable {
        val root = EglBase.create()
        val input = requireNotNull(SurfaceTextureHelper.create("QualityFailureInput", root.eglBaseContext))
        val errors = CopyOnWriteArrayList<String>()
        private val clock = AtomicLong()
        val processor = CameraQualityProcessor(root.eglBaseContext, object : AppLogger {
            override fun error(event: AppEvent, reason: String?) { errors.add("$event:$reason") }
        }, "failure-test", roiDetector = null) { clock.addAndGet(1_000_000) }
        val helper = field("helper").get(processor) as SurfaceTextureHelper
        private val pool = ThreadUtils.invokeAtFrontUninterruptibly(input.handler, java.util.concurrent.Callable {
            ArFramePool(input.handler) { input.dispose() }
        })
        var delivered = 0
        private var timestamp = 1_000_000_000L
        init { processor.setSink { delivered++ } }

        fun send() {
            ThreadUtils.invokeAtFrontUninterruptibly(input.handler) {
                val buffer = requireNotNull(pool.capture(32, 32) {
                    GLES20.glClearColor(0.3f, 0.3f, 0.3f, 1f)
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                    true
                })
                val frame = VideoFrame(buffer, 0, timestamp)
                timestamp += 33_333_333
                try { processor.onFrameCaptured(frame) } finally { frame.release() }
            }
            // Drain the auxiliary task and texture-release callbacks before the next assertion.
            ThreadUtils.invokeAtFrontUninterruptibly(helper.handler) {}
        }

        override fun close() {
            processor.setSink(null)
            processor.close()
            ThreadUtils.invokeAtFrontUninterruptibly(input.handler) { pool.close() }
            try {
                check(ThreadUtils.joinUninterruptibly(helper.handler.looper.thread, 5_000)) {
                    "Processor EGL thread leaked after fault (output pool did not drain)"
                }
                check(ThreadUtils.joinUninterruptibly(input.handler.looper.thread, 5_000)) { "Input pool did not drain" }
            } finally { root.release() }
        }
    }

    private fun sceneAllocationFailure() {
        Fixture().use { f ->
            var attempts = 0
            ThreadUtils.invokeAtFrontUninterruptibly(f.helper.handler) {
                field("analysisBuffer").set(f.processor, object : GlTextureFrameBuffer(GLES20.GL_RGBA) {
                    override fun setSize(width: Int, height: Int) {
                        attempts++
                        super.setSize(width, height)
                        throw IllegalStateException("Injected scene allocation failure")
                    }
                })
            }
            f.send(); f.send(); f.send()
            check(attempts == 1) { "Failed scene allocator was retried: $attempts" }
            check(f.errors.single().endsWith(":failure-test:aux:scene")) { "Missing scene fallback: ${f.errors}" }
            check(f.delivered == 3 && f.processor.stats.frames == 3L && !f.processor.stats.failed) {
                "Auxiliary allocation failure stopped base video: ${f.processor.stats}"
            }
        }
    }

    private fun outputValidationFailure(tier: ProcessingTier) {
        Fixture().use { f ->
            if (tier == ProcessingTier.RESIZE_ONLY) {
                val guard = field("guard").get(f.processor) as ProcessingGuard
                repeat(3) { guard.record(GuardSample(1.0, null), 0) } // Warmup.
                repeat(6) { guard.record(GuardSample(13.0, null, submitMs = 12.0), 0) }
                check(guard.tier == tier)
            }
            ThreadUtils.invokeAtFrontUninterruptibly(f.helper.handler) {
                field("slotSizes").set(f.processor, object : HashMap<Int, Long>() {
                    override fun put(key: Int, value: Long): Long? {
                        // markFresh runs after pool.capture has acquired the owned output reference.
                        GLES20.glEnable(-1) // GL_INVALID_ENUM; the normal frame validator must catch it.
                        return super.put(key, value)
                    }
                })
            }
            f.send(); f.send()
            check(f.errors.single().endsWith(":gpu")) { "Missing GPU fallback: ${f.errors}" }
            check(f.delivered == 2 && f.processor.stats.failed && f.processor.stats.bypassed == 2L) {
                "Validation failure did not preserve bypass delivery: ${f.processor.stats}"
            }
            // Fixture.close additionally asserts the owned output was released and the GL owner drains.
        }
    }
}
