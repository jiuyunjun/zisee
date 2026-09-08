package com.zisee.app.rtc

import org.webrtc.EglBase
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink

/** Serializes frame delivery with detach; renderers are explicitly released before parent EGL. */
class VideoFeed(val eglContext: EglBase.Context, val mirrored: Boolean) : VideoSink {
    private val renderers = mutableSetOf<SurfaceViewRenderer>()
    private var closed = false
    @Synchronized fun attach(renderer: SurfaceViewRenderer) {
        if (closed) return
        renderer.init(eglContext, null)
        renderer.setMirror(mirrored)
        renderer.setEnableHardwareScaler(true)
        renderers.add(renderer)
    }
    @Synchronized fun detach(renderer: SurfaceViewRenderer) {
        if (renderers.remove(renderer)) renderer.release()
    }
    @Synchronized override fun onFrame(frame: VideoFrame) { renderers.forEach { it.onFrame(frame) } }
    @Synchronized fun close() {
        closed = true
        renderers.forEach { it.release() }
        renderers.clear()
    }
}
