package com.lazydoglab.zisee.media

enum class MediaTrack(val wireId: String) {
    MICROPHONE("audio_microphone"), FRONT_CAMERA("video_front"),
    BACK_CAMERA("video_back"), SCREEN("video_screen"),
}

enum class Capability { UNKNOWN, SUPPORTED, UNSUPPORTED }

/** UNKNOWN is deliberately not equivalent to supported. Query actual camera combinations. */
data class DeviceCapabilities(
    val concurrentFrontBack: Capability = Capability.UNKNOWN,
    val arCore: Capability = Capability.UNKNOWN,
    val depth: Capability = Capability.UNKNOWN,
    val hardwareAv1Encoder: Capability = Capability.UNKNOWN,
)
