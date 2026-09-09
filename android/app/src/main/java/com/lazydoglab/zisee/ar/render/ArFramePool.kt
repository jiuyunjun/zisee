package com.lazydoglab.zisee.ar.render

import android.graphics.Matrix
import android.opengl.GLES20
import android.os.Handler
import org.webrtc.GlTextureFrameBuffer
import org.webrtc.TextureBufferImpl
import org.webrtc.VideoFrame
import org.webrtc.YuvConverter

/** GL-owner-only bounded storage. Closing retires slots; retained frames keep their GL owner alive. */
class ArFramePool(private val handler: Handler, private val onDrained: () -> Unit) {
    private class Slot(val framebuffer: GlTextureFrameBuffer, var busy: Boolean = false)
    private val slots = List(3) { Slot(GlTextureFrameBuffer(GLES20.GL_RGBA)) }
    private val converter = YuvConverter()
    private var closed = false
    private fun checkOwner() = check(Thread.currentThread() === handler.looper.thread)

    fun capture(width: Int, height: Int, draw: () -> Boolean): VideoFrame.TextureBuffer? {
        checkOwner()
        check(!closed)
        val slot = slots.firstOrNull { !it.busy } ?: return null
        slot.framebuffer.setSize(width, height)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, slot.framebuffer.frameBufferId)
        try {
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
            GLES20.glColorMask(true, true, true, true)
            if (!draw()) return null
            // Complete writes before consumers sample this shared texture on another EGL context.
            // No pixel readback. Replace with cross-context fences only after device measurements.
            GLES20.glFinish()
        } finally { GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0) }
        slot.busy = true
        return TextureBufferImpl(width, height, VideoFrame.TextureBuffer.Type.RGB,
            slot.framebuffer.textureId, Matrix(), handler, converter) {
            check(handler.post {
                slot.busy = false
                if (closed) { slot.framebuffer.release(); finishClose() }
            })
        }
    }

    fun close() {
        checkOwner()
        if (closed) return
        closed = true
        slots.filter { !it.busy }.forEach { it.framebuffer.release() }
        finishClose()
    }

    private fun finishClose() {
        if (slots.none { it.busy }) { converter.release(); onDrained() }
    }
}
