package com.lazydoglab.zisee.rtc

enum class VideoQuality(val width: Int, val height: Int, val fps: Int, val maxBitrateBps: Int) {
    ECONOMY(640, 360, 15, 450_000), HD(1280, 720, 30, 4_000_000), FULL_HD(1920, 1080, 30, 10_000_000),
    // Sixty frames costs roughly double the encode time per second of video and is the first thing
    // a warm phone cannot hold, so it is a step above full HD rather than a faster version of it.
    FULL_HD_60(1920, 1080, 60, 12_000_000),
}

enum class QualityReason { STARTUP, THERMAL, ENCODER, BANDWIDTH, RECOVERY, CAPACITY }
data class QualityDecision(val quality: VideoQuality, val reason: QualityReason)

/** Slow application ceilings. libwebrtc still owns instantaneous congestion control. */
class VideoQualityPolicy(
    private val supportsFullHd: Boolean,
    preferFullHd: Boolean = false,
    private val supports60: Boolean = false,
) {
    // Sixty frames is earned, never assumed: a call opens at 1080p30 and steps up only once this
    // sender's own link and encoder have both shown they can hold it.
    var current = QualityDecision(if (supportsFullHd && preferFullHd) VideoQuality.FULL_HD else VideoQuality.HD,
        QualityReason.STARTUP); private set
    private var pending: QualityDecision? = null
    private var sinceMs = 0L
    private var fastSinceMs: Long? = null
    private var changedMs = Long.MIN_VALUE
    private var lastMs: Long? = null
    private var restoreCeiling: VideoQuality? = null
    private var restoreUntilMs = 0L

    /**
     * A route change is not evidence that the link lost capacity a moment ago it had. The estimate
     * restarts low on the new path, and treating that like a newly discovered ceiling made the
     * picture crawl back over tens of seconds. Climbing back to what the previous route was already
     * holding is a restoration, so it is paced like one; going past it still earns its way.
     */
    fun routeChanged(nowMs: Long) {
        pending = null; fastSinceMs = null
        changedMs = Long.MIN_VALUE
        // A second route change while still recovering from the first must not lower the target to
        // whatever the recovery had reached by then.
        val held = restoreCeiling?.takeIf { nowMs < restoreUntilMs && it.ordinal > current.quality.ordinal }
        restoreCeiling = held ?: current.quality
        restoreUntilMs = nowMs + RESTORE_WINDOW_MS
    }

    fun update(stats: MediaStats, nowMs: Long): QualityDecision {
        val previousMs = lastMs
        lastMs = nowMs
        // A missing sample or clock reset breaks consecutive evidence.
        if (previousMs != null && (nowMs <= previousMs || nowMs - previousMs > 3_000)) { pending = null; fastSinceMs = null }
        if ((stats.thermalStatus ?: 0) >= 3) return change(VideoQuality.ECONOMY, QualityReason.THERMAL, nowMs)
        val overloaded = stats.qualityLimitation == "cpu" || (stats.encodeMs ?: 0.0) > 40.0
        val constrained = stats.availableOutgoingKbps?.let { it < 450 } == true
        // A report that has not arrived yet is not a bad report. Right after a handover the remote
        // RTCP report is stale and the round trip is unmeasured, and requiring them left the
        // picture at its lowest step for as long as they stayed missing.
        val healthy = stats.outboundLoss?.let { it < 0.02 } != false &&
            stats.measuredRttMs?.let { it < 200 } != false && stats.encodeMs?.let { it <= 25 } != false &&
            !overloaded && (stats.thermalStatus ?: 0) < 2
        // Sixty frames leaves 16ms per frame, so it needs encode headroom the lower steps do not,
        // and it is given up before resolution is: a smooth 1080p30 beats a stuttering 1080p60.
        val sixtyReady = healthy && supports60 && (stats.thermalStatus ?: 0) < 1 &&
            stats.encodeMs?.let { it <= 12.0 } == true &&
            stats.availableOutgoingKbps?.let { it >= 8_000 } == true
        val sixtyFailing = stats.availableOutgoingKbps?.let { it < 6_000 } == true ||
            stats.encodeMs?.let { it > 18.0 } == true || (stats.thermalStatus ?: 0) >= 1
        val target = when {
            overloaded -> QualityDecision(VideoQuality.ECONOMY, QualityReason.ENCODER)
            constrained -> QualityDecision(VideoQuality.ECONOMY, QualityReason.BANDWIDTH)
            (stats.thermalStatus ?: 0) >= 2 -> QualityDecision(VideoQuality.HD, QualityReason.THERMAL)
            current.quality == VideoQuality.FULL_HD_60 && sixtyFailing -> QualityDecision(VideoQuality.FULL_HD,
                if ((stats.thermalStatus ?: 0) >= 1) QualityReason.THERMAL else QualityReason.BANDWIDTH)
            current.quality == VideoQuality.FULL_HD && stats.availableOutgoingKbps?.let { it < 2_500 } == true ->
                QualityDecision(VideoQuality.HD, QualityReason.BANDWIDTH)
            current.quality == VideoQuality.ECONOMY && healthy -> QualityDecision(VideoQuality.HD, QualityReason.RECOVERY)
            current.quality == VideoQuality.HD && healthy && supportsFullHd &&
                stats.availableOutgoingKbps?.let { it >= 3_500 } == true -> QualityDecision(VideoQuality.FULL_HD, QualityReason.CAPACITY)
            current.quality == VideoQuality.FULL_HD && sixtyReady -> QualityDecision(VideoQuality.FULL_HD_60, QualityReason.CAPACITY)
            else -> null
        }
        if (target == null || target.quality == current.quality) { pending = null; fastSinceMs = null; return current }
        // Moderate heat must not raise ECONOMY to HD.
        if (target.reason == QualityReason.THERMAL && target.quality.ordinal > current.quality.ordinal) { pending = null; fastSinceMs = null; return current }
        if (pending != target) { pending = target; sinceMs = nowMs; fastSinceMs = null }
        val up = target.quality.ordinal > current.quality.ordinal
        // Fast recovery needs evidence in this sender's direction, not host/host or peer size.
        // Keep thermal/encoder recovery conservative even on an otherwise excellent link.
        val fastUpgrade = up && healthy &&
            current.reason !in setOf(QualityReason.THERMAL, QualityReason.ENCODER) &&
            stats.availableOutgoingKbps?.let { it >= 3_500 } == true &&
            stats.sendDelayMs?.let { it <= 30.0 } == true && stats.qualityLimitation == "none"
        if (!fastUpgrade) fastSinceMs = null
        else if (fastSinceMs == null) fastSinceMs = nowMs
        val fastReady = fastSinceMs?.let { nowMs - it >= 5_000 } == true
        // Only the climb back to the pre-handover ceiling is accelerated, and only while the link
        // looks healthy: anything above it is a new claim about the new route.
        val restoring = up && healthy && nowMs < restoreUntilMs &&
            restoreCeiling?.let { target.quality.ordinal <= it.ordinal } == true
        val wait = if (!up) 3_000L else if (restoring) RESTORE_WAIT_MS else if (fastReady) 5_000L else 15_000L
        val cooled = changedMs == Long.MIN_VALUE ||
            nowMs - changedMs >= if (restoring) RESTORE_WAIT_MS else if (fastReady) 5_000L else 10_000L
        if (nowMs - sinceMs >= wait && (!up || cooled)) return change(target.quality, target.reason, nowMs)
        return current
    }

    private fun change(quality: VideoQuality, reason: QualityReason, nowMs: Long): QualityDecision {
        if (current.quality != quality) { current = QualityDecision(quality, reason); changedMs = nowMs }
        pending = null; fastSinceMs = null
        if (restoreCeiling?.let { quality.ordinal >= it.ordinal } == true) restoreCeiling = null
        return current
    }

    private companion object {
        const val RESTORE_WINDOW_MS = 30_000L
        const val RESTORE_WAIT_MS = 2_000L
    }
}
