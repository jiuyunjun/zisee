package com.zisee.app.rtc

enum class VideoQuality(val width: Int, val height: Int, val fps: Int, val maxBitrateBps: Int) {
    ECONOMY(640, 360, 15, 450_000), HD(1280, 720, 30, 2_500_000), FULL_HD(1920, 1080, 30, 4_000_000),
}

enum class QualityReason { STARTUP, THERMAL, ENCODER, BANDWIDTH, RECOVERY, CAPACITY }
data class QualityDecision(val quality: VideoQuality, val reason: QualityReason)

/** Slow application ceilings. libwebrtc still owns instantaneous congestion control. */
class VideoQualityPolicy(private val supportsFullHd: Boolean) {
    var current = QualityDecision(VideoQuality.HD, QualityReason.STARTUP); private set
    private var pending: QualityDecision? = null
    private var sinceMs = 0L
    private var changedMs = Long.MIN_VALUE
    private var lastMs: Long? = null

    fun update(stats: MediaStats, nowMs: Long): QualityDecision {
        val previousMs = lastMs
        lastMs = nowMs
        // A missing sample or clock reset breaks consecutive evidence.
        if (previousMs != null && (nowMs <= previousMs || nowMs - previousMs > 3_000)) pending = null
        if ((stats.thermalStatus ?: 0) >= 3) return change(VideoQuality.ECONOMY, QualityReason.THERMAL, nowMs)
        val overloaded = stats.qualityLimitation == "cpu" || (stats.encodeMs ?: 0.0) > 40.0
        val constrained = stats.availableOutgoingKbps?.let { it < 450 } == true
        val healthy = stats.outboundLoss?.let { it < 0.02 } == true &&
            stats.measuredRttMs?.let { it < 200 } == true && stats.encodeMs?.let { it <= 25 } == true && !overloaded && (stats.thermalStatus ?: 0) < 2
        val target = when {
            overloaded -> QualityDecision(VideoQuality.ECONOMY, QualityReason.ENCODER)
            constrained -> QualityDecision(VideoQuality.ECONOMY, QualityReason.BANDWIDTH)
            (stats.thermalStatus ?: 0) >= 2 -> QualityDecision(VideoQuality.HD, QualityReason.THERMAL)
            current.quality == VideoQuality.FULL_HD && stats.availableOutgoingKbps?.let { it < 2_500 } == true ->
                QualityDecision(VideoQuality.HD, QualityReason.BANDWIDTH)
            current.quality == VideoQuality.ECONOMY && healthy -> QualityDecision(VideoQuality.HD, QualityReason.RECOVERY)
            current.quality == VideoQuality.HD && healthy && supportsFullHd &&
                stats.availableOutgoingKbps?.let { it >= 3_500 } == true -> QualityDecision(VideoQuality.FULL_HD, QualityReason.CAPACITY)
            else -> null
        }
        if (target == null || target.quality == current.quality) { pending = null; return current }
        // Moderate heat must not raise ECONOMY to HD.
        if (target.reason == QualityReason.THERMAL && target.quality.ordinal > current.quality.ordinal) { pending = null; return current }
        if (pending != target) { pending = target; sinceMs = nowMs }
        val up = target.quality.ordinal > current.quality.ordinal
        val wait = if (up) 15_000 else 3_000
        val cooled = changedMs == Long.MIN_VALUE || nowMs - changedMs >= 10_000
        if (nowMs - sinceMs >= wait && (!up || cooled)) return change(target.quality, target.reason, nowMs)
        return current
    }

    private fun change(quality: VideoQuality, reason: QualityReason, nowMs: Long): QualityDecision {
        if (current.quality != quality) { current = QualityDecision(quality, reason); changedMs = nowMs }
        pending = null
        return current
    }
}
