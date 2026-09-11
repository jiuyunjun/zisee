package com.lazydoglab.zisee.rtc.compute

import com.lazydoglab.zisee.core.logging.AppEvent
import com.lazydoglab.zisee.core.logging.AppLogger
import org.webrtc.VideoCodecStatus
import org.webrtc.VideoEncoder
import org.webrtc.VideoFrame
import org.webrtc.WrappedNativeVideoEncoder

/** Call-scoped snapshots; never retain native encoders or video buffers in the diagnostics owner. */
class EncoderTelemetry(private val logger: AppLogger? = null) {
    private var nextId = 0
    private val active = linkedMapOf<Int, EncoderTimingWindow>()
    /** Encoder-thread wall time of one synchronous encode call, microseconds. */
    val encodeCallCost = LatencyWindow(120)
    private var slowWindowStartNs = 0L
    private var slowLogged = 0

    /** For HW encoders the call includes drawing the input texture and swapping into the codec surface. */
    fun encodeCall(codec: String, startNs: Long, durationNs: Long) {
        encodeCallCost.add(durationNs / 1000)
        if (durationNs < SLOW_ENCODE_NS || logger == null) return
        val allowed = synchronized(this) {
            if (startNs - slowWindowStartNs > SLOW_LOG_WINDOW_NS) { slowWindowStartNs = startNs; slowLogged = 0 }
            ++slowLogged <= SLOW_LOG_MAX
        }
        // `at` is monotonic ms, comparable with RTC_COMPUTE_LATE's `at`.
        if (allowed) logger.info(AppEvent.RTC_COMPUTE_ENCODE_SLOW, "$codec:us=${durationNs / 1000}:at=${startNs / 1_000_000}")
    }

    private companion object {
        const val SLOW_ENCODE_NS = 8_000_000L
        const val SLOW_LOG_WINDOW_NS = 10_000_000_000L
        const val SLOW_LOG_MAX = 20
    }
    @Synchronized fun register(codec: String): Pair<Int, EncoderTimingWindow> {
        val id = ++nextId
        val window = EncoderTimingWindow(id, codec)
        active[id] = window
        return id to window
    }
    @Synchronized fun unregister(id: Int) { active.remove(id) }
    fun snapshot(nowNs: Long): List<EncoderTimingStats> {
        val windows = synchronized(this) { active.values.toList() }
        return windows.flatMap { it.snapshot(nowNs) }
    }
}

/** Decorate Java hardware encoders *inside* VideoEncoderFallback. WrappedNativeVideoEncoder's
 * Java encode methods deliberately throw and cannot be intercepted using this wrapper.
 */
class MeasuredVideoEncoder(
    private val delegate: VideoEncoder,
    private val codec: String,
    private val telemetry: EncoderTelemetry,
    private val sources: FrameSourceRegistry,
    private val clockNs: () -> Long = System::nanoTime,
) : VideoEncoder by delegate {
    init { require(delegate !is WrappedNativeVideoEncoder) }
    @Volatile private var registration: Pair<Int, EncoderTimingWindow>? = null

    override fun initEncode(settings: VideoEncoder.Settings, callback: VideoEncoder.Callback): VideoCodecStatus {
        registration?.let { telemetry.unregister(it.first) }
        val current = telemetry.register(codec)
        registration = current
        return try {
            delegate.initEncode(settings) { encoded, info ->
                current.second.completed(encoded.captureTimeNs, clockNs(), encoded.qp)
                // Borrowed encoded image, unchanged: preserves AR SEI, metadata and ownership.
                callback.onEncodedFrame(encoded, info)
            }.also { if (it != VideoCodecStatus.OK) discard(current.first) }
        } catch (error: RuntimeException) { discard(current.first); throw error }
    }

    override fun encode(frame: VideoFrame, info: VideoEncoder.EncodeInfo): VideoCodecStatus {
        val now = clockNs()
        val window = registration?.second
        window?.started(frame.timestampNs, now, sources.source(frame.timestampNs, now))
        return try {
            delegate.encode(frame, info).also { if (it != VideoCodecStatus.OK) window?.rejected(frame.timestampNs) }
        } catch (error: RuntimeException) { window?.rejected(frame.timestampNs); throw error }
        finally { telemetry.encodeCall(codec, now, clockNs() - now) }
    }

    override fun release(): VideoCodecStatus = try { delegate.release() }
        finally { registration?.let { discard(it.first) } }

    private fun discard(id: Int) {
        telemetry.unregister(id)
        if (registration?.first == id) registration = null
    }
}
