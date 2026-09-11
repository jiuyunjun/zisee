package com.lazydoglab.zisee.rtc.compute

import android.opengl.GLES20
import java.nio.ByteBuffer
import org.webrtc.GlRectDrawer
import org.webrtc.GlTextureFrameBuffer
import org.webrtc.GlUtil

/** GL-owner-only. Samples the already corrected RGB texture, preserving its aspect ratio. */
internal class RoiTextureSampler : AutoCloseable {
    private val framebuffer = GlTextureFrameBuffer(GLES20.GL_RGBA)
    private val drawer = GlRectDrawer()
    private val pixels = ByteBuffer.allocateDirect(640 * 360 * 4)
    private val identity = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }

    fun sample(texture: Int, geometry: RoiGeometry, timestampNs: Long): RgbaRoiInput {
        val scale = minOf(1.0, 640.0 / geometry.width, 360.0 / geometry.height)
        val width = (geometry.width * scale).toInt().coerceAtLeast(1)
        val height = (geometry.height * scale).toInt().coerceAtLeast(1)
        framebuffer.setSize(width, height)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer.frameBufferId)
        try {
            drawer.drawRgb(texture, identity, geometry.width, geometry.height,
                0, 0, width, height)
            pixels.clear()
            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
            GlUtil.checkNoGLES2Error("ROI readback")
            val owned = ByteArray(width * height * 4)
            pixels.position(0); pixels.get(owned)
            return RgbaRoiInput(geometry, timestampNs, width, height, owned)
        } finally { GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0) }
    }

    override fun close() { drawer.release(); framebuffer.release() }
}
