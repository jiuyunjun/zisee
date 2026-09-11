package com.lazydoglab.zisee.rtc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.opengl.GLES20
import java.nio.ByteBuffer
import com.lazydoglab.zisee.rtc.compute.*
import org.webrtc.*

/** Real bundled SDK invocation on synthetic pixels; no evidence of face recall or device speed. */
internal object RoiDetectorSmoke {
    fun run(context: Context, testContext: Context) {
        val detector = checkNotNull(RoiDetectorProvider.create()) { "Build with -Pzisee.faceRoi=true" }
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        val egl = EglBase.create()
        try {
            egl.createDummyPbufferSurface(); egl.makeCurrent()
            val texture = GlTextureFrameBuffer(GLES20.GL_RGBA)
            val sampler = RoiTextureSampler()
            try {
                texture.setSize(640, 360)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, texture.frameBufferId)
                GLES20.glClearColor(0.2f, 0.4f, 0.6f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                for (rotation in listOf(0, 90, 180, 270)) {
                    sampler.sample(texture.textureId, RoiGeometry(1, 640, 360, rotation), 123).use { input ->
                        val pixels = input.uprightArgb()
                        check(pixels.all { pixel ->
                            ((pixel shr 16) and 255) in 50..52 && ((pixel shr 8) and 255) in 101..103 &&
                                (pixel and 255) in 152..154
                        }) { "GPU RGBA channels changed" }
                        pixels.fill(0)
                        check(detector.detect(input).isEmpty()) { "Unexpected face on uniform image" }
                    }
                }
                val portrait = testContext.assets.open("roi/astronaut.png").use { BitmapFactory.decodeStream(it) }
                try {
                    for (rotation in listOf(0, 90, 180, 270)) {
                        val matrix = Matrix().apply { postRotate(-rotation.toFloat()) }
                        val source = Bitmap.createBitmap(portrait, 0, 0, portrait.width, portrait.height, matrix, false)
                        try {
                            val colors = IntArray(source.width * source.height)
                            source.getPixels(colors, 0, source.width, 0, 0, source.width, source.height)
                            val bytes = ByteBuffer.allocateDirect(colors.size * 4)
                            for (y in source.height - 1 downTo 0) for (x in 0 until source.width) {
                                val color = colors[y * source.width + x]
                                bytes.put((color shr 16).toByte()).put((color shr 8).toByte()).put(color.toByte()).put(255.toByte())
                            }
                            bytes.flip()
                            texture.setSize(source.width, source.height)
                            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture.textureId)
                            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, source.width, source.height,
                                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bytes)
                            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
                            sampler.sample(texture.textureId, RoiGeometry(2, source.width, source.height, rotation), 456).use { input ->
                                val faces = detector.detect(input)
                                check(faces.any { it.left < 0.43f && it.right > 0.46f && it.top < 0.20f && it.bottom > 0.27f &&
                                    it.right - it.left < 0.45f && it.bottom < 0.5f }) { "Portrait ROI missing or rotated incorrectly: $rotation" }
                            }
                        } finally { if (source !== portrait) source.recycle() }
                    }
                } finally { portrait.recycle() }
            } finally { sampler.close(); texture.release() }
        } finally { detector.close(); egl.release() }
    }
}
