package com.lazydoglab.zisee.rtc.compute

import org.webrtc.VideoCodecStatus
import org.webrtc.VideoEncoder
import org.webrtc.VideoFrame
import org.webrtc.WrappedNativeVideoEncoder

/** Call-scoped snapshots; never retain native encoders or video buffers in the diagnostics owner. */
class EncoderTelemetry {
    private var nextId = 0
    private val active = linkedMapOf<Int, EncoderTimingWindow>()
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
    }

    override fun release(): VideoCodecStatus = try { delegate.release() }
        finally { registration?.let { discard(it.first) } }

    private fun discard(id: Int) {
        telemetry.unregister(id)
        if (registration?.first == id) registration = null
    }
}
