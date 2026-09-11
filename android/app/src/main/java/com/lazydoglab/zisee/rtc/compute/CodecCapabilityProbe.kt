package com.lazydoglab.zisee.rtc.compute

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import com.lazydoglab.zisee.core.logging.AppEvent
import com.lazydoglab.zisee.core.logging.AppLogger

enum class CodecAcceleration { HARDWARE_DECLARED, SOFTWARE_DECLARED, UNKNOWN }
data class DeclaredVideoFormat(val width: Int, val height: Int, val fps: Int) {
    override fun toString() = "${width}x${height}@$fps"
}
data class CodecCapability(
    val component: String,
    val codec: String,
    val encoder: Boolean,
    val acceleration: CodecAcceleration,
    val surfaceInput: Boolean?,
    val complexityMin: Int?,
    val complexityMax: Int?,
    val roiEncoding: Boolean?,
    val bitrateModes: Set<String>,
    val formats: List<DeclaredVideoFormat>,
    val maxInstances: Int,
)
data class CodecCapabilitySnapshot(
    val sdkInt: Int,
    val components: List<CodecCapability>,
    val failedQueries: Int,
    /** Codec-level WebRTC factory advertisement, not proof that it selects each listed component. */
    val webRtcHardwareFormats: Set<String>? = null,
) {
    fun diagnosticLines(): List<String> = listOf(
        "Android declarations SDK=$sdkInt failed=$failedQueries; WebRTC HW=${webRtcHardwareFormats?.sorted()?.joinToString()?.ifEmpty { "none" } ?: "unknown"}",
        "complexity configuration through current WebRTC: unavailable; declared support is not a benchmark",
        "standard ROI QP-map transport through current WebRTC: unavailable; API 35 codec support is declaration only",
    ) + components.map {
        "${if (it.encoder) "enc" else "dec"} ${it.codec} ${it.component} ${it.acceleration} " +
            "surface=${it.surfaceInput ?: "n/a"} complexity=${it.complexityMin ?: "?"}..${it.complexityMax ?: "?"} roi=${it.roiEncoding ?: "n/a"} " +
            "modes=${it.bitrateModes.sorted().joinToString()} instances=${it.maxInstances} formats=${it.formats.joinToString()}"
    }
}

/** Read on a worker, once per call. Does not instantiate a codec, change negotiation or allocate
 * camera hardware. OEM declarations are hints, including isHardwareAccelerated; never a score.
 */
object CodecCapabilityProbe {
    private val types = mapOf("video/avc" to "H264", "video/hevc" to "H265", "video/x-vnd.on2.vp8" to "VP8",
        "video/x-vnd.on2.vp9" to "VP9", "video/av01" to "AV1")
    private val ladder = listOf(DeclaredVideoFormat(640, 360, 24), DeclaredVideoFormat(960, 540, 30),
        DeclaredVideoFormat(1280, 720, 30), DeclaredVideoFormat(1920, 1080, 30), DeclaredVideoFormat(1920, 1080, 60))

    fun read(logger: AppLogger, webRtcHardwareFormats: Set<String>? = null): CodecCapabilitySnapshot {
        val components = mutableListOf<CodecCapability>()
        var failures = 0
        val codecs = try { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos }
        catch (error: RuntimeException) {
            logger.error(AppEvent.RTC_COMPUTE_CAPABILITY_FAILED)
            return CodecCapabilitySnapshot(Build.VERSION.SDK_INT, emptyList(), 1, webRtcHardwareFormats)
        }
        for (info in codecs) {
            try {
                if (Build.VERSION.SDK_INT >= 29 && info.isAlias) continue
                for (mime in info.supportedTypes) {
                    val name = types[mime] ?: continue
                    try {
                        val caps = info.getCapabilitiesForType(mime)
                        val video = caps.videoCapabilities ?: continue
                        val encoder = caps.encoderCapabilities
                        val complexity = encoder?.complexityRange
                        val acceleration = if (Build.VERSION.SDK_INT < 29) CodecAcceleration.UNKNOWN else when {
                            info.isSoftwareOnly -> CodecAcceleration.SOFTWARE_DECLARED
                            info.isHardwareAccelerated -> CodecAcceleration.HARDWARE_DECLARED
                            else -> CodecAcceleration.UNKNOWN
                        }
                        val modes = if (encoder == null) emptySet() else mapOf(
                            "CQ" to MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ,
                            "VBR" to MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
                            "CBR" to MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
                        ).filterValues { encoder.isBitrateModeSupported(it) }.keys
                        components.add(CodecCapability(info.name.take(100), name, info.isEncoder, acceleration,
                            if (info.isEncoder) MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface in caps.colorFormats else null,
                            complexity?.lower, complexity?.upper,
                            if (info.isEncoder && Build.VERSION.SDK_INT >= 35)
                                caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_Roi)
                            else if (info.isEncoder) false else null,
                            modes,
                            ladder.filter { video.areSizeAndRateSupported(it.width, it.height, it.fps.toDouble()) },
                            caps.maxSupportedInstances))
                    } catch (error: RuntimeException) { failures++ }
                }
            } catch (error: RuntimeException) { failures++ }
        }
        if (failures > 0) logger.error(AppEvent.RTC_COMPUTE_CAPABILITY_FAILED)
        return CodecCapabilitySnapshot(Build.VERSION.SDK_INT, components, failures, webRtcHardwareFormats)
    }
}
