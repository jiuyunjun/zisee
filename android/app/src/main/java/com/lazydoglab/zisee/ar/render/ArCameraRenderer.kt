package com.lazydoglab.zisee.ar.render

import android.opengl.EGL14
import android.opengl.GLES20
import android.os.Looper
import org.webrtc.GlRectDrawer

/** Draws an OES camera image into the current caller-owned framebuffer and viewport.
 * Owns only its shader. Construct/draw/close on the same non-main thread AND EGL context.
 * The caller sets framebuffer, blend/depth/scissor/color-mask state. Draw changes GL program,
 * texture/attribute bindings and viewport, like the existing WebRTC drawers.
 * Finish this draw before the next ARCore update; never hand the borrowed OES id to an encoder.
 */
class ArCameraRenderer : AutoCloseable {
    private val owner = Thread.currentThread()
    private val eglContext = EGL14.eglGetCurrentContext()
    private val drawer = GlRectDrawer()
    private var closed = false
    init { checkOwner() }

    private fun checkOwner() {
        check(Thread.currentThread() === owner && Looper.myLooper() != Looper.getMainLooper())
        check(eglContext != EGL14.EGL_NO_CONTEXT && EGL14.eglGetCurrentContext() == eglContext)
        check(!closed)
    }

    fun draw(textureId: Int, mapping: CameraTextureMapping, imageWidth: Int, imageHeight: Int,
        outputWidth: Int, outputHeight: Int) {
        checkOwner()
        require(textureId > 0 && imageWidth > 0 && imageHeight > 0 && outputWidth > 0 && outputHeight > 0)
        check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE)
        drawer.drawOes(textureId, mapping.glMatrix(), imageWidth, imageHeight, 0, 0, outputWidth, outputHeight)
    }

    override fun close() {
        if (closed) return
        checkOwner()
        drawer.release()
        closed = true
    }
}
