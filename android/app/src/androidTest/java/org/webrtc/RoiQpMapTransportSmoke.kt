package org.webrtc

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCrypto
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.view.Surface
import java.nio.ByteBuffer

/** Android framework smoke for feature configuration, per-frame Bundle transport and neutral clear. */
object RoiQpMapTransportSmoke {
    fun run() {
        check(Build.VERSION.SDK_INT >= 35) { "QP map smoke requires API 35" }
        val provider = Provider()
        val controller = RoiCodecController("fake.encoder", true, provider)
        val format = MediaFormat.createVideoFormat("video/avc", 32, 32)
        controller.configure(format)
        check(format.getFeatureEnabled(MediaCodecInfo.CodecCapabilities.FEATURE_Roi))
        val codec = Codec()
        controller.codec = codec
        val buffer = Buffer(32, 32)
        val frame = VideoFrame(buffer, 0, 123_000)
        try {
            provider.next = byteArrayOf(-3, 1, 1, 1)
            check(controller.submit(frame))
            check(codec.values!!.contentEquals(provider.next))
            provider.next = null
        } finally { frame.release() }
        val neutralBuffer = Buffer(32, 32)
        val neutralFrame = VideoFrame(neutralBuffer, 0, 124_000)
        try {
            check(controller.submit(neutralFrame))
            check(codec.values!!.contentEquals(byteArrayOf(0, 0, 0, 0)))
        } finally { neutralFrame.release() }
        check(provider.configured && provider.active == 1 && provider.neutral == 1 && provider.failures == 0)

        val invalid = Provider().apply { next = byteArrayOf(1) }
        val rejected = RoiCodecController("fake.encoder", true, invalid).also { it.codec = Codec() }
        val invalidBuffer = Buffer(32, 32)
        val invalidFrame = VideoFrame(invalidBuffer, 0, 125_000)
        try { check(!rejected.submit(invalidFrame) && invalid.failures == 1) }
        finally { invalidFrame.release() }
    }

    private class Provider : RoiQpMapProvider {
        var next: ByteArray? = null
        var configured = false
        var active = 0
        var neutral = 0
        var failures = 0
        override fun take(timestampNs: Long, width: Int, height: Int) = next
        override fun configured(codecName: String, supported: Boolean) { configured = supported }
        override fun submitted(codecName: String, active: Boolean) { if (active) this.active++ else neutral++ }
        override fun failed(codecName: String, stage: String) { failures++ }
    }

    private class Buffer(private val bufferWidth: Int, private val bufferHeight: Int) : VideoFrame.Buffer {
        override fun getWidth() = bufferWidth
        override fun getHeight() = bufferHeight
        override fun toI420(): VideoFrame.I420Buffer? = null
        override fun retain() = Unit
        override fun release() = Unit
        override fun cropAndScale(cropX: Int, cropY: Int, cropWidth: Int, cropHeight: Int,
            scaleWidth: Int, scaleHeight: Int): VideoFrame.Buffer = Buffer(scaleWidth, scaleHeight)
    }

    private class Codec : MediaCodecWrapper {
        var values: ByteArray? = null
        override fun setParameters(params: Bundle) {
            values = params.getByteArray(MediaCodec.PARAMETER_KEY_QP_OFFSET_MAP)?.copyOf()
        }
        override fun configure(format: MediaFormat, surface: Surface?, crypto: MediaCrypto?, flags: Int) = Unit
        override fun start() = Unit
        override fun flush() = Unit
        override fun stop() = Unit
        override fun release() = Unit
        override fun dequeueInputBuffer(timeoutUs: Long) = -1
        override fun queueInputBuffer(index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int) = Unit
        override fun dequeueOutputBuffer(info: MediaCodec.BufferInfo, timeoutUs: Long) = -1
        override fun releaseOutputBuffer(index: Int, render: Boolean) = Unit
        override fun getInputFormat(): MediaFormat = throw UnsupportedOperationException()
        override fun getOutputFormat(): MediaFormat = throw UnsupportedOperationException()
        override fun getOutputFormat(index: Int): MediaFormat = throw UnsupportedOperationException()
        override fun getInputBuffer(index: Int): ByteBuffer? = null
        override fun getOutputBuffer(index: Int): ByteBuffer? = null
        override fun createInputSurface(): Surface = throw UnsupportedOperationException()
        override fun getCodecInfo(): MediaCodecInfo = throw UnsupportedOperationException()
    }
}
