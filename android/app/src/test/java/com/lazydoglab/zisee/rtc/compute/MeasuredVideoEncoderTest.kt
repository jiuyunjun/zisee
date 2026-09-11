package com.lazydoglab.zisee.rtc.compute

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test
import org.webrtc.*

class MeasuredVideoEncoderTest {
    private class FakeEncoder : VideoEncoder {
        lateinit var callback: VideoEncoder.Callback
        var result = VideoCodecStatus.OK
        var received: VideoFrame? = null
        var releaseCalls = 0
        override fun initEncode(settings: VideoEncoder.Settings, callback: VideoEncoder.Callback): VideoCodecStatus {
            this.callback = callback; return result
        }
        override fun encode(frame: VideoFrame, info: VideoEncoder.EncodeInfo): VideoCodecStatus { received = frame; return result }
        override fun release(): VideoCodecStatus { releaseCalls++; return VideoCodecStatus.OK }
        override fun setRateAllocation(allocation: VideoEncoder.BitrateAllocation, frameRate: Int) = VideoCodecStatus.OK
        override fun getScalingSettings() = VideoEncoder.ScalingSettings.OFF
        override fun getImplementationName() = "test-java"
    }
    private open class BorrowedBuffer : VideoFrame.Buffer {
        var references = 1
        override fun getWidth() = 16
        override fun getHeight() = 16
        override fun retain() { references++ }
        override fun release() { references-- }
        override fun toI420(): VideoFrame.I420Buffer = error("Must not read pixels")
        override fun cropAndScale(x: Int, y: Int, width: Int, height: Int, outWidth: Int, outHeight: Int): VideoFrame.Buffer =
            error("Must not transform frame")
    }
    private val settings = VideoEncoder.Settings(1, 16, 16, 100, 30, 1, false)
    private val info = VideoEncoder.EncodeInfo(arrayOf(EncodedImage.FrameType.VideoFrameDelta))

    @Test fun wrapperPreservesFrameImageAndOwnershipAndReportsTiming() {
        val delegate = FakeEncoder()
        val telemetry = EncoderTelemetry()
        val sources = FrameSourceRegistry()
        var now = 0L
        val encoder = MeasuredVideoEncoder(delegate, "H264", telemetry, sources) { now }
        var returned: EncodedImage? = null
        encoder.initEncode(settings) { encoded, _ -> returned = encoded }
        val buffer = BorrowedBuffer()
        val frame = VideoFrame(buffer, 90, 5_000)
        sources.record(frame.timestampNs, "video_back", now)
        encoder.encode(frame, info)
        assertSame(frame, delegate.received)
        var freed = false
        val image = EncodedImage.builder().setBuffer(ByteBuffer.allocateDirect(1), { freed = true })
            .setEncodedWidth(16).setEncodedHeight(16).setRotation(90).setCaptureTimeNs(5_000)
            .setFrameType(EncodedImage.FrameType.VideoFrameDelta).setQp(27).createEncodedImage()
        now = 12_000_000
        delegate.callback.onEncodedFrame(image, null)
        assertSame(image, returned)
        assertFalse(freed)
        assertEquals(1, buffer.references)
        val stats = telemetry.snapshot(now).single()
        assertEquals("video_back", stats.trackId)
        assertEquals(12.0, stats.callbackP95Ms!!, 0.0)
        assertEquals(27.0, stats.qpMean!!, 0.0)
        image.release(); frame.release()
        assertTrue(freed); assertEquals(0, buffer.references)
        encoder.release()
        assertEquals(1, delegate.releaseCalls)
        assertTrue(telemetry.snapshot(now).isEmpty())
    }

    @Test fun reinitializationAndFailedInitializationDoNotKeepStaleWindows() {
        val delegate = FakeEncoder()
        val telemetry = EncoderTelemetry()
        val encoder = MeasuredVideoEncoder(delegate, "H264", telemetry, FrameSourceRegistry()) { 0L }
        encoder.initEncode(settings) { _, _ -> }
        val oldCallback = delegate.callback
        encoder.initEncode(settings) { _, _ -> }
        val image = EncodedImage.builder().setBuffer(ByteBuffer.allocateDirect(1), null)
            .setCaptureTimeNs(1).setFrameType(EncodedImage.FrameType.VideoFrameDelta).createEncodedImage()
        try { oldCallback.onEncodedFrame(image, null) } finally { image.release() }
        assertTrue(telemetry.snapshot(0).isEmpty())
        delegate.result = VideoCodecStatus.ERROR
        assertEquals(VideoCodecStatus.ERROR, encoder.initEncode(settings) { _, _ -> })
        assertTrue(telemetry.snapshot(0).isEmpty())
        encoder.release()
    }

    @Test fun screenTimestampObserverIsPixelAndReferenceNeutral() {
        val sources = FrameSourceRegistry()
        val observer = SourceTimestampProcessor(sources, "video_screen") { 0 }
        val buffer = BorrowedBuffer()
        val frame = VideoFrame(buffer, 0, 10_000)
        var received: VideoFrame? = null
        observer.setSink { received = it }
        observer.onFrameCaptured(frame)
        assertSame(frame, received)
        assertEquals("video_screen", sources.source(10_000, 0))
        assertEquals(1, buffer.references)
        frame.release()
    }

    @Test fun measuredArEncoderKeepsSeiAndQpAndDoesNotRetainMedia() {
        val raw = FakeEncoder()
        val telemetry = EncoderTelemetry()
        val sources = FrameSourceRegistry()
        var now = 0L
        val encoder = MeasuredVideoEncoder(com.lazydoglab.zisee.ar.render.ArVideoEncoder(raw),
            "H264", telemetry, sources) { now }
        val texture = object : BorrowedBuffer(), VideoFrame.TextureBuffer {
            override fun getType() = VideoFrame.TextureBuffer.Type.RGB
            override fun getTextureId() = 1
            override fun getTransformMatrix(): android.graphics.Matrix = error("Must not touch pixels")
        }
        val identity = com.lazydoglab.zisee.ar.render.ArFrameIdentity(java.util.UUID(1, 2),
            com.lazydoglab.zisee.ar.annotation.VideoFrameReference(com.lazydoglab.zisee.media.MediaTrack.BACK_CAMERA, 5_000))
        var delivered = false
        encoder.initEncode(settings) { image, _ ->
            assertEquals(identity, com.lazydoglab.zisee.ar.render.ArFrameSei.read(image.buffer))
            assertEquals(27, image.qp)
            delivered = true
        }
        val frame = VideoFrame(com.lazydoglab.zisee.ar.render.ArTextureBuffer(texture, identity), 90, 5_000)
        sources.record(frame.timestampNs, "video_back", now)
        encoder.encode(frame, info)
        val image = EncodedImage.builder().setBuffer(ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1, 0x65, 1, 2)), null)
            .setEncodedWidth(16).setEncodedHeight(16).setCaptureTimeNs(5_000).setRotation(90)
            .setFrameType(EncodedImage.FrameType.VideoFrameKey).setQp(27).createEncodedImage()
        try {
            now = 10_000_000
            raw.callback.onEncodedFrame(image, null)
            assertTrue(delivered)
            assertEquals(1, texture.references)
            assertEquals(10.0, telemetry.snapshot(now).single().callbackP95Ms!!, 0.0)
        } finally { image.release(); frame.release(); encoder.release() }
        assertEquals(0, texture.references)
    }
}
