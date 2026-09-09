package com.zisee.app.rtc.audio

enum class AudioBandwidthMode { ALL_VIDEO, PRIMARY_ONLY, AUDIO_ONLY }

/** Bounded video suspension below the voice reserve. Uplink evidence only, with recovery hysteresis. */
class AudioBandwidthPolicy {
    var mode = AudioBandwidthMode.ALL_VIDEO; private set
    private var healthySince: Long? = null
    private var previousMs: Long? = null
    fun update(availableKbps: Long?, outboundAudioLoss: Double?, nowMs: Long): AudioBandwidthMode {
        if (previousMs?.let { nowMs <= it || nowMs - it > 3_000 } == true) healthySince = null
        previousMs = nowMs
        val target = when {
            availableKbps != null && availableKbps < 96 -> AudioBandwidthMode.AUDIO_ONLY
            outboundAudioLoss != null && outboundAudioLoss >= 0.15 -> AudioBandwidthMode.AUDIO_ONLY
            availableKbps != null && availableKbps < 250 -> AudioBandwidthMode.PRIMARY_ONLY
            else -> AudioBandwidthMode.ALL_VIDEO
        }
        if (target.ordinal > mode.ordinal) { mode = target; healthySince = null }
        else if (mode != AudioBandwidthMode.ALL_VIDEO) {
            if (availableKbps != null && availableKbps >= 400 && (outboundAudioLoss == null || outboundAudioLoss < 0.05)) {
                val since = healthySince ?: nowMs.also { healthySince = it }
                if (nowMs - since >= 5_000) { mode = AudioBandwidthMode.ALL_VIDEO; healthySince = null }
            } else healthySince = null
        }
        return mode
    }
}
