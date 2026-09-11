package com.lazydoglab.zisee.ar.render

import org.webrtc.*

/** Wrap only Java H264 codecs. Other codecs and native software fallbacks keep ordinary video. */
class ArEncoderFactory(shared: EglBase.Context,
    private val decorateHardware: ((VideoCodecInfo, VideoEncoder) -> VideoEncoder)? = null,
    qpMaps: RoiQpMapProvider? = null) : VideoEncoderFactory {
    private val defaults = DefaultVideoEncoderFactory(shared, true, true)
    private val hardware: VideoEncoderFactory = qpMaps?.let {
        RoiHardwareVideoEncoderFactory(shared, true, true, it)
    } ?: HardwareVideoEncoderFactory(shared, true, true)
    fun hardwareFormats(): Set<String> = hardware.supportedCodecs.map { it.name }.toSet()
    override fun getSupportedCodecs(): Array<VideoCodecInfo> = defaults.supportedCodecs
    override fun createEncoder(info: VideoCodecInfo): VideoEncoder? {
        val h264 = info.name.equals("H264", ignoreCase = true)
        if (!h264 && decorateHardware == null) return defaults.createEncoder(info)
        val encoder = hardware.createEncoder(info) ?: return defaults.createEncoder(info)
        val software = SoftwareVideoEncoderFactory().createEncoder(info)
        val tagged = if (h264) ArVideoEncoder(encoder) else encoder
        val decorated = decorateHardware?.invoke(info, tagged) ?: tagged
        return if (software == null) decorated else VideoEncoderFallback(software, decorated)
    }
}

class ArVideoEncoder(private val delegate: VideoEncoder) : VideoEncoder by delegate {
    private val pending = FrameIdentityIndex<ArFrameIdentity>()
    override fun initEncode(settings: VideoEncoder.Settings, callback: VideoEncoder.Callback): VideoCodecStatus {
        pending.clear()
        return delegate.initEncode(settings) { encoded, info ->
            val identity = pending.take(encoded.captureTimeNs)
            val bytes = identity?.let { ArFrameSei.prepend(encoded.buffer, it) }
            if (bytes == null) callback.onEncodedFrame(encoded, info)
            else {
                val tagged = EncodedImage.builder().setBuffer(bytes, null)
                    .setEncodedWidth(encoded.encodedWidth).setEncodedHeight(encoded.encodedHeight)
                    .setCaptureTimeNs(encoded.captureTimeNs).setFrameType(encoded.frameType)
                    .setRotation(encoded.rotation).setQp(encoded.qp).createEncodedImage()
                try { callback.onEncodedFrame(tagged, info) } finally { tagged.release() }
            }
        }
    }
    override fun encode(frame: VideoFrame, info: VideoEncoder.EncodeInfo): VideoCodecStatus {
        pending.put(frame.timestampNs, (frame.buffer as? ArTextureBuffer)?.identity)
        return delegate.encode(frame, info).also {
            if (it != VideoCodecStatus.OK) pending.take(frame.timestampNs)
        }
    }
    override fun release(): VideoCodecStatus = try { delegate.release() } finally { pending.clear() }
}

class ArDecoderFactory(shared: EglBase.Context) : VideoDecoderFactory {
    private val defaults = DefaultVideoDecoderFactory(shared)
    private val hardware = HardwareVideoDecoderFactory(shared)
    private val platform = PlatformSoftwareVideoDecoderFactory(shared)
    override fun getSupportedCodecs(): Array<VideoCodecInfo> = defaults.supportedCodecs
    override fun createDecoder(info: VideoCodecInfo): VideoDecoder? {
        if (!info.name.equals("H264", ignoreCase = true)) return defaults.createDecoder(info)
        val primary = hardware.createDecoder(info)
        val fallback = platform.createDecoder(info)
        return when {
            primary != null && fallback != null -> VideoDecoderFallback(
                ArVideoDecoder(fallback), ArVideoDecoder(primary))
            primary != null -> ArVideoDecoder(primary)
            fallback != null -> ArVideoDecoder(fallback)
            else -> defaults.createDecoder(info)
        }
    }
}

class ArVideoDecoder(private val delegate: VideoDecoder) : VideoDecoder by delegate {
    private val pending = FrameIdentityIndex<ArFrameIdentity>()
    override fun initDecode(settings: VideoDecoder.Settings, callback: VideoDecoder.Callback): VideoCodecStatus {
        pending.clear()
        return delegate.initDecode(settings) { frame, time, qp ->
            val identity = pending.take(frame.timestampNs)
            val texture = frame.buffer as? VideoFrame.TextureBuffer
            if (identity == null || texture == null) callback.onDecodedFrame(frame, time, qp)
            else {
                texture.retain()
                val output = VideoFrame(ArTextureBuffer(texture, identity), frame.rotation, frame.timestampNs)
                try { callback.onDecodedFrame(output, time, qp) } finally { output.release() }
            }
        }
    }
    override fun decode(frame: EncodedImage, info: VideoDecoder.DecodeInfo?): VideoCodecStatus {
        // Android MediaCodec transports presentation time in microseconds. JNI passes null info.
        val timestamp = frame.captureTimeNs / 1000 * 1000
        pending.put(timestamp, ArFrameSei.read(frame.buffer))
        return delegate.decode(frame, info).also {
            if (it != VideoCodecStatus.OK) pending.take(timestamp)
        }
    }
    override fun release(): VideoCodecStatus = try { delegate.release() } finally { pending.clear() }
}
