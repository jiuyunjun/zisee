package com.lazydoglab.zisee.rtc.compute

import org.webrtc.VideoFrame
import org.webrtc.VideoProcessor
import org.webrtc.VideoSink

/** Identity-only observation for screens or GPU-unavailable cameras. No image processing/copies. */
class SourceTimestampProcessor(private val sources: FrameSourceRegistry, private val trackId: String,
    private val clockNs: () -> Long = System::nanoTime) : VideoProcessor {
    private var sink: VideoSink? = null
    override fun setSink(sink: VideoSink?) { this.sink = sink }
    override fun onCapturerStarted(success: Boolean) = Unit
    override fun onCapturerStopped() = Unit
    override fun onFrameCaptured(frame: VideoFrame) {
        sources.record(frame.timestampNs, trackId, clockNs())
        sink?.onFrame(frame)
    }
}
