package com.lazydoglab.zisee.rtc

import android.graphics.Color
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.lazydoglab.zisee.rtc.compute.CameraQualityShader
import org.webrtc.*
import java.nio.ByteBuffer

/** Tests the actual OES shader and camera transform, using synthetic asymmetric colored corners. */
internal object ComputeOesSmoke {
    fun run() {
        val thread = HandlerThread("QualityOesTest").apply { start() }
        val handler = Handler(thread.looper)
        try {
            ThreadUtils.invokeAtFrontUninterruptibly(handler) {
                val egl = EglBase.create()
                egl.createPbufferSurface(32, 32); egl.makeCurrent()
                val texture = GlUtil.generateTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
                val surfaceTexture = SurfaceTexture(texture)
                val converter = YuvConverter()
                try {
                    surfaceTexture.setDefaultBufferSize(64, 64)
                    val surface = Surface(surfaceTexture)
                    try {
                        val canvas = surface.lockCanvas(null)
                        try {
                            val paint = Paint()
                            listOf(Color.RED, Color.GREEN, Color.BLUE, Color.WHITE).forEachIndexed { i, color ->
                                paint.color = color
                                val x = (i % 2) * 32f; val y = (i / 2) * 32f
                                canvas.drawRect(x, y, x + 32, y + 32, paint)
                            }
                        } finally { surface.unlockCanvasAndPost(canvas) }
                        surfaceTexture.updateTexImage()
                        val transform = FloatArray(16).also { surfaceTexture.getTransformMatrix(it) }
                        val buffer = TextureBufferImpl(64, 64, VideoFrame.TextureBuffer.Type.OES, texture,
                            RendererCommon.convertMatrixToAndroidGraphicsMatrix(transform), handler, converter, null)
                        val adapted = buffer.cropAndScale(0, 0, 64, 64, 32, 32) as VideoFrame.TextureBuffer
                        try {
                            val reference = ByteBuffer.allocateDirect(32 * 32 * 4)
                            val drawer = GlRectDrawer()
                            try { drawer.drawOes(texture, RendererCommon.convertMatrixFromAndroidGraphicsMatrix(adapted.transformMatrix),
                                64, 64, 0, 0, 32, 32) }
                            finally { drawer.release() }
                            GLES20.glReadPixels(0, 0, 32, 32, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, reference)
                            CameraQualityShader().use { it.resize(adapted, 32, 32) }
                            val pixels = ByteBuffer.allocateDirect(32 * 32 * 4)
                            GLES20.glReadPixels(0, 0, 32, 32, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
                            // Match WebRTC's actual matrix convention, including SurfaceTexture's flip.
                            val corners = mutableSetOf<List<Int>>()
                            for (i in 0..3) {
                                val offset = (((i / 2) * 16 + 8) * 32 + (i % 2) * 16 + 8) * 4
                                val want = (0..2).map { reference.get(offset + it).toInt() and 255 }
                                corners.add(want)
                                check(want.indices.all { kotlin.math.abs((pixels.get(offset + it).toInt() and 255) - want[it]) < 8 }) {
                                    "OES resize changed corner $i"
                                }
                            }
                            check(corners.size == 4 && corners.all { it.max() > 240 })
                        } finally { adapted.release(); buffer.release() }
                        // 4:1 downsample of one white column in every four: ideal mean is 1/4.
                        // A single bilinear sample aliases this pattern to black at these centres.
                        val stripes = GlTextureFrameBuffer(GLES20.GL_RGBA)
                        stripes.setSize(64, 64)
                        try {
                            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, stripes.frameBufferId)
                            GLES20.glClearColor(0f, 0f, 0f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                            GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
                            GLES20.glClearColor(1f, 1f, 1f, 1f)
                            for (x in 0 until 64 step 4) { GLES20.glScissor(x, 0, 1, 64); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT) }
                            GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
                            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                            val raw = TextureBufferImpl(64, 64, VideoFrame.TextureBuffer.Type.RGB, stripes.textureId,
                                android.graphics.Matrix(), handler, converter, null)
                            val scaled = raw.cropAndScale(0, 0, 64, 64, 16, 16) as VideoFrame.TextureBuffer
                            try {
                                val pixel = ByteBuffer.allocateDirect(4)
                                val drawer = GlRectDrawer()
                                try { drawer.drawRgb(stripes.textureId, RendererCommon.convertMatrixFromAndroidGraphicsMatrix(scaled.transformMatrix),
                                    64, 64, 0, 0, 16, 16) } finally { drawer.release() }
                                GLES20.glReadPixels(8, 8, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixel)
                                val baseline = pixel.get(0).toInt() and 255
                                CameraQualityShader().use { it.resize(scaled, 16, 16) }
                                pixel.clear()
                                GLES20.glReadPixels(8, 8, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixel)
                                val filtered = pixel.get(0).toInt() and 255
                                check(filtered in 60..68 && kotlin.math.abs(filtered - 64) < kotlin.math.abs(baseline - 64)) {
                                    "Area scaling failed: filtered=$filtered baseline=$baseline"
                                }
                            } finally { scaled.release(); raw.release() }
                        } finally { stripes.release() }
                    } finally { surface.release() }
                } finally {
                    converter.release(); surfaceTexture.release()
                    GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
                    egl.release()
                }
            }
        } finally { thread.quitSafely(); thread.join(5_000); check(!thread.isAlive) }
    }
}
