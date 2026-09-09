package com.lazydoglab.zisee.rtc.audio

enum class AudioBandwidthMode { ALL_VIDEO, PRIMARY_ONLY, AUDIO_ONLY, VIDEO_PROBE }

/**
 * Bounded video suspension below the voice reserve. Uplink evidence only, with recovery hysteresis.
 *
 * Suspension must always be able to end. Missing evidence is never treated as bad evidence: a
 * handover leaves the send-side estimate collapsed and the remote RTCP report stale, so both a
 * single low sample and an absent sample are ignored, and every pause schedules another bounded
 * probe whose only failure conditions are measured ones.
 */
class AudioBandwidthPolicy {
    var mode = AudioBandwidthMode.ALL_VIDEO; private set
    private var healthySince: Long? = null
    private var previousMs: Long? = null
    private var starvedSince: Long? = null
    private var retryAtMs = 0L
    private var retryDelayMs = FIRST_RETRY_MS
    private var probeUntilMs = 0L
    private var ignoreEstimateUntilMs = 0L
    private var lastReport: Double? = null
    private var lossSeenMs: Long? = null
    private var recentLoss: Double? = null

    /** A new selected route invalidates loss evidence and can retry after a short settle time. */
    fun routeChanged(nowMs: Long) {
        healthySince = null; starvedSince = null
        recentLoss = null; lossSeenMs = null
        // The estimate restarts from a low probe rate on the new path; it is not evidence yet.
        // Only the estimate is discounted here, never measured audio loss.
        ignoreEstimateUntilMs = nowMs + SETTLE_MS
        retryDelayMs = FIRST_RETRY_MS
        retryAtMs = nowMs + 1_000
        if (mode == AudioBandwidthMode.VIDEO_PROBE) mode = AudioBandwidthMode.AUDIO_ONLY
    }

    fun update(availableKbps: Long?, outboundAudioLoss: Double?, nowMs: Long,
               reportTimestampUs: Double? = nowMs * 1_000.0): AudioBandwidthMode {
        if (previousMs?.let { nowMs <= it || nowMs - it > 3_000 } == true) { healthySince = null; starvedSince = null }
        previousMs = nowMs
        if (reportTimestampUs != null && reportTimestampUs != lastReport) {
            lastReport = reportTimestampUs; recentLoss = outboundAudioLoss; lossSeenMs = nowMs
        }
        val loss = recentLoss.takeIf { lossSeenMs?.let { nowMs - it in 0..3_000 } == true }
        val badAudio = loss != null && loss >= 0.15
        val estimate = availableKbps.takeIf { nowMs >= ignoreEstimateUntilMs }
        if (mode == AudioBandwidthMode.VIDEO_PROBE) {
            if (badAudio) return pause(nowMs, probeFailed = true)
            // BWE may still be pinned at the old audio-only rate. Give the bounded probe time to run.
            if (nowMs < probeUntilMs) return mode
            // Only measured failure ends a probe. A missing estimate or missing remote report is
            // exactly the state a handover leaves behind, and must not keep the camera off.
            if (estimate != null && estimate < PROBE_FLOOR_KBPS) return pause(nowMs, probeFailed = true)
            mode = AudioBandwidthMode.PRIMARY_ONLY
            healthySince = null
            retryDelayMs = FIRST_RETRY_MS
        }
        // A single collapsed sample is a handover, not a weak link; pausing needs it to persist.
        val starving = badAudio || (estimate != null && estimate < RESERVE_KBPS)
        val starvedFor = if (starving) nowMs - (starvedSince ?: nowMs.also { starvedSince = it })
            else { starvedSince = null; 0L }
        val target = when {
            starving -> if (starvedFor >= STARVE_MS) AudioBandwidthMode.AUDIO_ONLY else AudioBandwidthMode.PRIMARY_ONLY
            estimate != null && estimate < 250 -> AudioBandwidthMode.PRIMARY_ONLY
            else -> AudioBandwidthMode.ALL_VIDEO
        }
        if (target.ordinal > mode.ordinal) {
            if (target == AudioBandwidthMode.AUDIO_ONLY) return pause(nowMs)
            mode = target; healthySince = null
        }
        // Suspension only ever ends through a bounded probe: an estimate measured while no video
        // is being sent is not evidence that sending video would work.
        else if (mode == AudioBandwidthMode.PRIMARY_ONLY) {
            if (estimate != null && estimate >= 400 && (loss == null || loss < 0.05)) {
                val since = healthySince ?: nowMs.also { healthySince = it }
                if (nowMs - since >= 5_000) { mode = AudioBandwidthMode.ALL_VIDEO; healthySince = null }
            } else healthySince = null
        }
        if (mode == AudioBandwidthMode.AUDIO_ONLY && !starving && nowMs >= retryAtMs) {
            mode = AudioBandwidthMode.VIDEO_PROBE
            probeUntilMs = nowMs + PROBE_MS
        }
        return mode
    }

    /** Only a probe that measured a failure backs off; staying starved just defers the next one. */
    private fun pause(nowMs: Long, probeFailed: Boolean = false): AudioBandwidthMode {
        mode = AudioBandwidthMode.AUDIO_ONLY
        healthySince = null; starvedSince = null
        if (probeFailed) {
            retryAtMs = nowMs + retryDelayMs
            // Back off repeated failures, but never stop retrying: the link can recover silently.
            retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_MS)
        } else retryAtMs = maxOf(retryAtMs, nowMs + FIRST_RETRY_MS)
        return mode
    }

    private companion object {
        const val RESERVE_KBPS = 96L
        const val PROBE_FLOOR_KBPS = 80L
        const val STARVE_MS = 2_000L
        // A measured handover spent nine seconds audio-only while the new path's estimate was
        // simply cold: it climbed to full HD moments later. Measured audio loss still pauses video
        // inside this window; an estimate that has not warmed up does not.
        const val SETTLE_MS = 8_000L
        const val PROBE_MS = 4_000L
        const val FIRST_RETRY_MS = 3_000L
        const val MAX_RETRY_MS = 30_000L
    }
}
