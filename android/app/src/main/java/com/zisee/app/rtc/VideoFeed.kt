package com.zisee.app.rtc

import org.webrtc.EglBase
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Serializes frame delivery with detach; renderers are explicitly released before parent EGL. */
class VideoFeed(val eglContext: EglBase.Context, mirrored: Boolean, private val onFirstFrame: () -> Unit = {}) : VideoSink {
    private val renderers = mutableSetOf<TextureViewRenderer>()
    private var closed = false
    private var receivedFrame = false
    private var mirror = mirrored
    private val frameGeometry = MutableStateFlow<VideoGeometry?>(null)
    val geometry = frameGeometry.asStateFlow()
    @Synchronized fun setMirrored(value: Boolean) {
        mirror = value
        renderers.forEach { it.setMirror(value) }
    }
    @Synchronized fun attach(renderer: TextureViewRenderer) {
        if (closed) return
        renderer.init(eglContext)
        renderer.setMirror(mirror)
        renderers.add(renderer)
    }
    @Synchronized fun detach(renderer: TextureViewRenderer) {
        if (renderers.remove(renderer)) renderer.release()
    }
    @Synchronized override fun onFrame(frame: VideoFrame) {
        if (closed) return
        frameGeometry.value = VideoGeometry(frame.buffer.width, frame.buffer.height, frame.rotation)
        if (!receivedFrame) { receivedFrame = true; onFirstFrame() }
        renderers.forEach { it.onFrame(frame) }
    }
    @Synchronized fun close() {
        closed = true
        renderers.forEach { it.release() }
        renderers.clear()
    }
}
