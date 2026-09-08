package com.zisee.app.rtc

import org.webrtc.EglBase
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink

/** Serializes frame delivery with detach; renderers are explicitly released before parent EGL. */
class VideoFeed(val eglContext: EglBase.Context, val mirrored: Boolean, private val onFirstFrame: () -> Unit = {}) : VideoSink {
    private val renderers = mutableSetOf<SurfaceViewRenderer>()
    private var closed = false
    private var receivedFrame = false
    @Synchronized fun attach(renderer: SurfaceViewRenderer) {
        if (closed) return
        renderer.init(eglContext, null)
        renderer.setMirror(mirrored)
        // Keep the display surface stable across capture/decoder size changes. Resizing the
        // SurfaceHolder can discard its displayed buffer and briefly flash on some devices.
        // The existing EGL buffer holds the last image during capture reconfiguration;
        // the next frame replaces it without retaining a camera texture or adding latency.
        renderer.setEnableHardwareScaler(false)
        renderers.add(renderer)
    }
    @Synchronized fun detach(renderer: SurfaceViewRenderer) {
        if (renderers.remove(renderer)) renderer.release()
    }
    @Synchronized override fun onFrame(frame: VideoFrame) {
        if (closed) return
        if (!receivedFrame) { receivedFrame = true; onFirstFrame() }
        renderers.forEach { it.onFrame(frame) }
    }
    @Synchronized fun close() {
        closed = true
        renderers.forEach { it.release() }
        renderers.clear()
    }
}
