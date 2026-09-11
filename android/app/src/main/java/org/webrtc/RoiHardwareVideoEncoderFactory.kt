/*
 * Factory selection follows WebRTC M144 HardwareVideoEncoderFactory (BSD-3-Clause).
 * Zisee adds a MediaCodecWrapper that enables and submits Android 15 ROI QP maps.
 */
package org.webrtc

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaCrypto
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.view.Surface

/** Hardware factory with a typed, non-reflective MediaCodec ROI insertion point. */
class RoiHardwareVideoEncoderFactory(
    shared: EglBase.Context?,
    private val enableIntelVp8Encoder: Boolean,
    private val enableH264HighProfile: Boolean,
    private val qpMaps: RoiQpMapProvider,
) : VideoEncoderFactory {
    private val sharedContext = shared as? EglBase14.Context
    private val advertised = HardwareVideoEncoderFactory(shared, enableIntelVp8Encoder, enableH264HighProfile)

    override fun getSupportedCodecs(): Array<VideoCodecInfo> = advertised.supportedCodecs

    override fun createEncoder(input: VideoCodecInfo): VideoEncoder? {
        val type = try { VideoCodecMimeType.valueOf(input.name) } catch (_: IllegalArgumentException) { return null }
        val info = findCodec(type) ?: return null
        val caps = try { info.getCapabilitiesForType(type.mimeType()) } catch (_: RuntimeException) { return null }
        val surface = MediaCodecUtils.selectColorFormat(MediaCodecUtils.TEXTURE_COLOR_FORMATS, caps)
        val yuv = MediaCodecUtils.selectColorFormat(MediaCodecUtils.ENCODER_COLOR_FORMATS, caps) ?: return null
        if (type == VideoCodecMimeType.H264) {
            val high = H264Utils.isSameH264Profile(input.params, MediaCodecUtils.getCodecProperties(type, true))
            val baseline = H264Utils.isSameH264Profile(input.params, MediaCodecUtils.getCodecProperties(type, false))
            if ((!high && !baseline) || (high && !isH264HighProfileSupported(info))) return null
        }
        val roiSupported = Build.VERSION.SDK_INT >= 35 &&
            caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_Roi)
        val controller = RoiCodecController(info.name, roiSupported, qpMaps)
        val encoder = HardwareVideoEncoder(RoiMediaCodecFactory(controller), info.name, type, surface, yuv,
            input.params, PERIODIC_KEY_FRAME_INTERVAL_S, forcedKeyFrameInterval(type, info.name),
            bitrateAdjuster(type, info.name), sharedContext)
        return RoiVideoEncoder(encoder, controller)
    }

    private fun findCodec(type: VideoCodecMimeType): MediaCodecInfo? =
        try { MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.firstOrNull { it.isEncoder && supported(it, type) } }
        catch (_: RuntimeException) { null }

    private fun supported(info: MediaCodecInfo, type: VideoCodecMimeType): Boolean {
        if (!MediaCodecUtils.codecSupportsType(info, type)) return false
        val caps = try { info.getCapabilitiesForType(type.mimeType()) } catch (_: RuntimeException) { return false }
        if (MediaCodecUtils.selectColorFormat(MediaCodecUtils.ENCODER_COLOR_FORMATS, caps) == null) return false
        return hardwareSupported(info, type)
    }

    private fun hardwareSupported(info: MediaCodecInfo, type: VideoCodecMimeType): Boolean {
        if (Build.VERSION.SDK_INT >= 29) return info.isHardwareAccelerated
        val name = info.name
        return when (type) {
            VideoCodecMimeType.VP8 -> name.startsWith(QCOM_PREFIX) ||
                (name.startsWith(EXYNOS_PREFIX) && Build.VERSION.SDK_INT >= 23) ||
                (name.startsWith(INTEL_PREFIX) && enableIntelVp8Encoder)
            VideoCodecMimeType.VP9 -> (name.startsWith(QCOM_PREFIX) || name.startsWith(EXYNOS_PREFIX)) &&
                Build.VERSION.SDK_INT >= 24
            VideoCodecMimeType.H264 -> Build.MODEL !in H264_HW_EXCEPTION_MODELS &&
                (name.startsWith(QCOM_PREFIX) || name.startsWith(EXYNOS_PREFIX))
            VideoCodecMimeType.H265 -> true
            VideoCodecMimeType.AV1 -> false
        }
    }

    private fun isH264HighProfileSupported(info: MediaCodecInfo) = enableH264HighProfile &&
        Build.VERSION.SDK_INT > 23 && info.name.startsWith(EXYNOS_PREFIX)

    private fun forcedKeyFrameInterval(type: VideoCodecMimeType, name: String): Int {
        if (type != VideoCodecMimeType.VP8 || !name.startsWith(QCOM_PREFIX)) return 0
        return when {
            Build.VERSION.SDK_INT < 23 -> 15_000
            Build.VERSION.SDK_INT == 23 -> 20_000
            else -> 15_000
        }
    }

    private fun bitrateAdjuster(type: VideoCodecMimeType, name: String): BitrateAdjuster =
        if (!name.startsWith(EXYNOS_PREFIX)) BaseBitrateAdjuster()
        else if (type == VideoCodecMimeType.VP8) DynamicBitrateAdjuster() else FramerateBitrateAdjuster()

    private companion object {
        const val PERIODIC_KEY_FRAME_INTERVAL_S = 3_600
        const val QCOM_PREFIX = "OMX.qcom."
        const val EXYNOS_PREFIX = "OMX.Exynos."
        const val INTEL_PREFIX = "OMX.Intel."
        val H264_HW_EXCEPTION_MODELS = setOf("SAMSUNG-SGH-I337", "Nexus 7", "Nexus 4")
    }
}

private class RoiMediaCodecFactory(private val controller: RoiCodecController) : MediaCodecWrapperFactory {
    private val delegate = MediaCodecWrapperFactoryImpl()
    override fun createByCodecName(name: String): MediaCodecWrapper =
        RoiMediaCodecWrapper(delegate.createByCodecName(name), controller).also { controller.codec = it }
}

private class RoiMediaCodecWrapper(
    private val delegate: MediaCodecWrapper,
    private val controller: RoiCodecController,
) : MediaCodecWrapper by delegate {
    override fun configure(format: MediaFormat, surface: Surface?, crypto: MediaCrypto?, flags: Int) {
        controller.configure(format)
        delegate.configure(format, surface, crypto, flags)
    }

    override fun setParameters(params: Bundle) = delegate.setParameters(params)
}

internal class RoiCodecController(
    private val codecName: String,
    private val declaredSupported: Boolean,
    private val qpMaps: RoiQpMapProvider,
) {
    @Volatile var codec: MediaCodecWrapper? = null
    private var runtimeDisabled = false
    private var neutral = ByteArray(0)

    fun configure(format: MediaFormat) {
        val enabled = Build.VERSION.SDK_INT >= 35 && declaredSupported && !runtimeDisabled
        if (enabled) format.setFeatureEnabled(MediaCodecInfo.CodecCapabilities.FEATURE_Roi, true)
        qpMaps.configured(codecName, enabled)
    }

    /** Must run immediately before HardwareVideoEncoder queues or swaps the corresponding input. */
    fun submit(frame: VideoFrame): Boolean {
        if (!declaredSupported || runtimeDisabled || Build.VERSION.SDK_INT < 35) return true
        val width = frame.buffer.width
        val height = frame.buffer.height
        val count = ((width + 15) / 16) * ((height + 15) / 16)
        val candidate = try { qpMaps.take(frame.timestampNs, width, height) }
        catch (_: RuntimeException) { disable("provider"); return false }
        val active = candidate?.size == count
        if (candidate != null && !active) { disable("size"); return false }
        if (neutral.size != count) neutral = ByteArray(count)
        val values = if (active) candidate!! else neutral
        return try {
            codec?.setParameters(Bundle().apply {
                putByteArray(MediaCodec.PARAMETER_KEY_QP_OFFSET_MAP, values)
            }) ?: throw IllegalStateException("codec unavailable")
            qpMaps.submitted(codecName, active)
            true
        } catch (_: RuntimeException) { disable("set_parameters"); false }
    }

    private fun disable(stage: String) {
        runtimeDisabled = true
        qpMaps.failed(codecName, stage)
    }
}

/** Resets to ordinary hardware encoding after the first runtime ROI failure; the failed frame drops. */
private class RoiVideoEncoder(
    private val delegate: VideoEncoder,
    private val controller: RoiCodecController,
) : VideoEncoder by delegate {
    private var settings: VideoEncoder.Settings? = null
    private var callback: VideoEncoder.Callback? = null
    private var inputShape: Triple<Int, Int, Boolean>? = null

    override fun initEncode(settings: VideoEncoder.Settings, callback: VideoEncoder.Callback): VideoCodecStatus {
        this.settings = settings
        this.callback = callback
        inputShape = null
        return delegate.initEncode(settings, callback)
    }

    override fun encode(frame: VideoFrame, info: VideoEncoder.EncodeInfo): VideoCodecStatus {
        val shape = Triple(frame.buffer.width, frame.buffer.height, frame.buffer is VideoFrame.TextureBuffer)
        // HardwareVideoEncoder may reset MediaCodec for a shape/mode change inside encode(). Let
        // that frame establish the new codec first; its initial ROI configuration is neutral.
        if (inputShape != shape) {
            inputShape = shape
            return delegate.encode(frame, info)
        }
        if (controller.submit(frame)) return delegate.encode(frame, info)
        val currentSettings = settings ?: return VideoCodecStatus.FALLBACK_SOFTWARE
        val currentCallback = callback ?: return VideoCodecStatus.FALLBACK_SOFTWARE
        val released = delegate.release()
        if (released != VideoCodecStatus.OK) return VideoCodecStatus.FALLBACK_SOFTWARE
        return if (delegate.initEncode(currentSettings, currentCallback) == VideoCodecStatus.OK)
            VideoCodecStatus.NO_OUTPUT else VideoCodecStatus.FALLBACK_SOFTWARE
    }

    override fun release(): VideoCodecStatus {
        settings = null
        callback = null
        inputShape = null
        return delegate.release()
    }
}
