package com.lazydoglab.zisee.rtc

import android.graphics.Color
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.view.Surface
import com.lazydoglab.zisee.ar.render.ArCameraRenderer
import com.lazydoglab.zisee.ar.render.CameraTextureMapping
import org.webrtc.EglBase
import org.webrtc.GlUtil
import java.nio.ByteBuffer

/** Real OES sampling/shader/EGL, synthetic colored input. Does not claim ARCore device support. */
internal object ArCameraRenderSmoke {
    fun run() {
        val egl = EglBase.create()
        try {
            egl.createPbufferSurface(32, 32)
            egl.makeCurrent()
            val texture = GlUtil.generateTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
            val source = SurfaceTexture(texture)
            try {
                source.setDefaultBufferSize(32, 32)
                val surface = Surface(source)
                try {
                    val canvas = surface.lockCanvas(null)
                    try {
                        val paint = Paint()
                        listOf(Color.RED, Color.GREEN, Color.BLUE, Color.WHITE).forEachIndexed { i, color ->
                            paint.color = color
                            val x = (i % 2) * 16f
                            val y = (i / 2) * 16f
                            canvas.drawRect(x, y, x + 16f, y + 16f, paint)
                        }
                    } finally { surface.unlockCanvasAndPost(canvas) }
                    source.updateTexImage()
                    check(source.timestamp > 0)
                    val transform = FloatArray(16)
                    source.getTransformMatrix(transform)
                    // SurfaceTexture's input is bottom-left GL, while our contract uses CPU top-left.
                    val uv = listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f).flatMap { (x, y) ->
                        listOf(transform[0] * x + transform[4] * (1f - y) + transform[12],
                            transform[1] * x + transform[5] * (1f - y) + transform[13])
                    }.toFloatArray()
                    ArCameraRenderer().use { renderer ->
                        renderer.draw(texture, CameraTextureMapping(uv), 32, 32, 32, 32)
                        GlUtil.checkNoGLES2Error("AR camera draw")
                        // Readback is test-only. Assert asymmetric corners to detect Y flips.
                        val pixels = ByteBuffer.allocateDirect(32 * 32 * 4)
                        GLES20.glReadPixels(0, 0, 32, 32, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
                        listOf(Color.BLUE, Color.WHITE, Color.RED, Color.GREEN).forEachIndexed { i, expected ->
                            val offset = (((i / 2) * 16 + 8) * 32 + (i % 2) * 16 + 8) * 4
                            val actual = (0..2).map { pixels.get(offset + it).toInt() and 255 }
                            val want = listOf(Color.red(expected), Color.green(expected), Color.blue(expected))
                            check(actual.zip(want).all { (a, b) -> kotlin.math.abs(a - b) < 8 })
                        }
                    }
                } finally { surface.release() }
            } finally {
                source.release()
                GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
            }
        } finally { egl.release() }
    }
}
