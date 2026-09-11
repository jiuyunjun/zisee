package com.lazydoglab.zisee.rtc

import android.content.Context
import android.opengl.GLES20
import com.lazydoglab.zisee.ar.render.ArFramePool
import com.lazydoglab.zisee.core.logging.AppEvent
import com.lazydoglab.zisee.core.logging.AppLogger
import com.lazydoglab.zisee.rtc.compute.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.webrtc.*

/** Synthetic GPU evidence only; no claims about physical camera/encoder quality or thermal cost. */
internal object ComputeQualitySmoke {
    fun run(context: Context) = runBlocking {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        val root = EglBase.create()
        val input = requireNotNull(SurfaceTextureHelper.create("QualityTestInput", root.eglBaseContext))
        val drained = CompletableDeferred<Unit>()
        val errors = mutableListOf<String>()
        val logger = object : AppLogger {
            override fun error(event: AppEvent, reason: String?) { errors.add("$event:$reason") }
        }
        // Pixel/ownership assertions must not depend on the emulator's software GPU timing.
        // NativeRtcSession smoke uses the production clock and validates the actual fallback.
        val elapsedClock = java.util.concurrent.atomic.AtomicLong()
        val frameCost = java.util.concurrent.atomic.AtomicLong(1_000_000)
        val roiSeen = CompletableDeferred<Unit>()
        val roiClosed = CompletableDeferred<Unit>()
        val roiDetector = object : RoiDetector {
            override fun detect(input: RoiInput): List<RoiBox> {
                check(input is RgbaRoiInput && input.width == 32 && input.height == 32)
                val pixels = input.uprightArgb()
                check(pixels.size == 32 * 32)
                pixels.fill(0)
                roiSeen.complete(Unit)
                return emptyList()
            }
            override fun close() { roiClosed.complete(Unit) }
        }
        val processor = CameraQualityProcessor(root.eglBaseContext, logger, "test", roiDetector = roiDetector) {
            elapsedClock.addAndGet(frameCost.get())
        }
        val retained = mutableListOf<VideoFrame>()
        processor.setSink { it.retain(); retained.add(it) }
        processor.decision = ComputeDecision(ComputeLevel.C2, ComputeReason.RECOVERY)
        var timestamp = 1_000_000_000L
        var lastInputLuma = 0
        fun luma(frame: VideoFrame): Int {
            val yuv = requireNotNull(frame.buffer.toI420())
            return try { yuv.dataY.get(yuv.strideY * (yuv.height / 2) + yuv.width / 2).toInt() and 255 }
            finally { yuv.release() }
        }
        try {
            withContext(input.handler.asCoroutineDispatcher()) {
                val pool = ArFramePool(input.handler) { input.dispose(); drained.complete(Unit) }
                try {
                    fun send(gray: Float, rotation: Int = 90): VideoFrame {
                        val buffer = requireNotNull(pool.capture(64, 64) {
                            GLES20.glClearColor(gray, gray, gray, 1f)
                            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                            true
                        })
                        val frame = VideoFrame(buffer, rotation, timestamp)
                        timestamp += 33_333_333
                        lastInputLuma = luma(frame)
                        try { processor.onFrameCaptured(frame,
                            VideoProcessor.FrameAdaptationParameters(0, 0, 64, 64, 32, 32, frame.timestampNs, false)) }
                        finally { frame.release() }
                        return retained.last().also {
                            check(it.buffer.width == 32 && it.buffer.height == 32 && it.rotation == rotation)
                            check(it.timestampNs == timestamp - 33_333_333)
                        }
                    }
                    val first = send(0.2f)
                    val originalY = luma(first)
                    // Release source-pool slots between synchronous captures (their releases are posted).
                    kotlinx.coroutines.yield()
                    val second = send(0.22f)
                    check(luma(second) in originalY..(originalY + 5))
                    check(luma(second) < lastInputLuma) { "Temporal denoise did not attenuate the perturbation" }
                    kotlinx.coroutines.yield()
                    val third = send(0.9f)
                    check(luma(third) > 205) { "Scene change was smeared" }
                    val stableY = luma(first)
                    kotlinx.coroutines.yield()
                    // Three retained processed frames exhaust the output pool; original frame survives.
                    send(0.4f)
                    check(processor.stats.bypassed >= 1)
                    check(luma(first) == stableY) { "Retained texture overwritten" }
                    retained.drop(1).forEach { it.release() }; retained.subList(1, retained.size).clear()
                    kotlinx.coroutines.yield()
                    processor.setAllowed(false)
                    val bypass = send(0.5f)
                    check(luma(bypass) in 122..132)
                    bypass.release(); retained.removeAt(retained.lastIndex)
                    kotlinx.coroutines.yield()
                    processor.setAllowed(true)
                    processor.decision = ComputeDecision(ComputeLevel.C0, ComputeReason.THERMAL)
                    val processedCount = processor.stats.frames
                    val survival = send(0.7f)
                    check(processor.stats.frames == processedCount && luma(survival) in 164..175)
                    survival.release(); retained.removeAt(retained.lastIndex)
                    kotlinx.coroutines.yield()
                    processor.decision = ComputeDecision(ComputeLevel.C2, ComputeReason.RECOVERY)
                    val reset = send(0.2f, 270)
                    check(luma(reset) == originalY) { "History survived ownership/rotation change" }
                    reset.release(); retained.removeAt(retained.lastIndex)
                    kotlinx.coroutines.yield()
                    // Three consecutive late frames shed one tier; processing continues at the lighter tier.
                    frameCost.set(13_000_000)
                    repeat(3) {
                        val slow = send(0.3f)
                        slow.release(); retained.removeAt(retained.lastIndex)
                        kotlinx.coroutines.yield()
                    }
                    check(processor.stats.tier == ProcessingTier.NO_AUX && !processor.stats.failed) {
                        "Sustained deadline misses did not shed work: ${processor.stats}"
                    }
                    frameCost.set(1_000_000)
                    val beforeShed = processor.stats.frames
                    val afterShed = send(0.4f)
                    check(processor.stats.frames == beforeShed + 1) { "Degraded tier stopped processing" }
                    afterShed.release(); retained.removeAt(retained.lastIndex)
                    processor.setSink(null)
                    processor.close()
                    // Closing must leave the retained output's converter/context alive.
                    check(luma(first) == stableY)
                } finally { pool.close() }
            }
            check(errors.isEmpty()) { "GPU fallback: $errors" }
        } finally {
            processor.setSink(null); processor.close()
            retained.forEach { it.release() }
            root.release()
        }
        withTimeout(5_000) { drained.await() }
        withTimeout(5_000) { roiSeen.await(); roiClosed.await() }
    }
}
