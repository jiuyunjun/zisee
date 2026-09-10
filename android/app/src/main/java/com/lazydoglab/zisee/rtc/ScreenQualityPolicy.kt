package com.lazydoglab.zisee.rtc

/** §6.1: manual choice between a text-first ladder and a motion-first ladder. Switching resets to
 * the chosen mode's top tier; the policy may immediately downgrade again on the next sample. */
enum class ScreenContentMode { TEXT, MOTION }

/** §10 reason enum, screen subset: which signal drove the last tier or mode change. */
enum class ScreenQualityReason { THERMAL, ENCODER, BANDWIDTH, QUEUE, LOSS, RECOVERY, MODE }

/** Conservative per-mode ceilings for the single screen sender. Native congestion control still owns
 * fast bitrate changes; this policy only moves between slow, sustainable operating points. Each
 * mode's tiers are declared low-to-high so the ladder for a mode is `values().filter { it.mode == mode }`.
 */
enum class ScreenQuality(val mode: ScreenContentMode, val longEdge: Int, val fps: Int, val maxBitrateBps: Int) {
    TEXT_LOW(ScreenContentMode.TEXT, 1280, 3, 400_000),
    TEXT_CONSTRAINED(ScreenContentMode.TEXT, 1600, 5, 1_000_000),
    TEXT_NORMAL(ScreenContentMode.TEXT, 1600, 10, 2_000_000),
    MOTION_CONSTRAINED(ScreenContentMode.MOTION, 960, 15, 1_000_000),
    MOTION_NORMAL(ScreenContentMode.MOTION, 1280, 30, 2_500_000),
}

class ScreenQualityPolicy {
    private fun ladder(m: ScreenContentMode) = ScreenQuality.values().filter { it.mode == m }
    private fun top(m: ScreenContentMode) = ladder(m).last()

    var mode = ScreenContentMode.TEXT; private set
    var current = top(mode); private set
    var lastReason = ScreenQualityReason.MODE; private set
    private var pending: ScreenQuality? = null
    private var sinceMs = 0L
    private var lastMs: Long? = null
    /** When [current] was last entered; the anchor an upgrade window counts continuous headroom
     * from, as long as evidence has not been interrupted since (see [recoveryBroken]). */
    private var tierEnteredMs = 0L
    /** True once continuity since [tierEnteredMs] has a gap or a non-healthy, non-degrading sample;
     * a fresh upgrade window then has to count 15 s from the sample that restarts it, not from
     * [tierEnteredMs]. Reset whenever the tier actually changes. */
    private var recoveryBroken = false
    private var lastChangeMs = Long.MIN_VALUE / 2

    /** §6.1: mode switch never restarts projection or re-consents; it only resets the ladder.
     * New shares always start in [ScreenContentMode.TEXT] — callers reconstruct the policy for that. */
    fun setMode(newMode: ScreenContentMode, nowMs: Long) {
        if (mode == newMode) return
        mode = newMode
        current = top(mode)
        pending = null
        tierEnteredMs = nowMs
        recoveryBroken = false
        lastChangeMs = nowMs
        lastReason = ScreenQualityReason.MODE
    }

    fun update(stats: MediaStats, nowMs: Long): ScreenQuality {
        val previous = lastMs
        lastMs = nowMs
        // A gap this large is missing telemetry, not a healthy sample: §7 keeps the applied tier but
        // interrupts the upgrade window rather than crediting the silence as headroom.
        if (previous != null && (nowMs <= previous || nowMs - previous > 3_000)) { pending = null; recoveryBroken = true }
        val screen = stats.outboundVideo["video_screen"]
        val thermal = stats.thermalStatus ?: 0
        val frameMs = 1_000.0 / current.fps
        val severeThermal = thermal >= 3
        val overloaded = screen?.qualityLimitation == "cpu" || screen?.encodeMs?.let { it > frameMs * 0.7 } == true
        val weakBandwidth = stats.availableOutgoingKbps?.let { it < 900 } == true
        val weakLoss = screen?.outboundLoss?.takeIf { screen.outboundReportFresh }?.let { it >= 0.08 } == true
        val weakDelay = screen?.sendDelayMs?.let { it > 120 } == true
        val weak = weakBandwidth || weakLoss || weakDelay
        val moderateThermal = thermal >= 2
        val moderateBandwidth = stats.availableOutgoingKbps?.let { it < 1_700 } == true
        val moderateEncode = screen?.encodeMs?.let { it > frameMs * 0.45 } == true
        val moderate = moderateThermal || moderateBandwidth || moderateEncode
        val bottom = ladder(mode).first()
        val topTier = top(mode)
        val healthyNow = healthy(stats, screen)
        val target = when {
            severeThermal || overloaded || weak -> bottom
            moderate && current == topTier -> ladder(mode)[ladder(mode).indexOf(topTier) - 1]
            current != topTier && healthyNow -> ladder(mode)[ladder(mode).indexOf(current) + 1]
            else -> current
        }
        if (target == current) {
            pending = null
            if (current != topTier && !healthyNow) recoveryBroken = true
            return current
        }
        val upgrading = ladder(mode).indexOf(target) > ladder(mode).indexOf(current)
        if (pending != target) {
            pending = target
            sinceMs = if (upgrading && !recoveryBroken) tierEnteredMs else nowMs
        }
        val severe = severeThermal || overloaded
        val wait = if (upgrading) 15_000L else if (severe) 0L else 2_000L
        if (nowMs - sinceMs < wait) return current
        // §7 format cooldown: ordinary transitions stay at least 5 s apart; severe conditions bypass it.
        if (!severe && nowMs - lastChangeMs < 5_000L) return current
        current = target
        pending = null
        tierEnteredMs = nowMs
        recoveryBroken = false
        lastChangeMs = nowMs
        lastReason = reason(upgrading, severeThermal, overloaded, weakBandwidth, weakLoss, weakDelay,
            moderateThermal, moderateBandwidth, moderateEncode)
        return current
    }

    private fun reason(upgrading: Boolean, severeThermal: Boolean, overloaded: Boolean, weakBandwidth: Boolean,
        weakLoss: Boolean, weakDelay: Boolean, moderateThermal: Boolean, moderateBandwidth: Boolean,
        moderateEncode: Boolean): ScreenQualityReason = when {
        upgrading -> ScreenQualityReason.RECOVERY
        severeThermal || moderateThermal -> ScreenQualityReason.THERMAL
        overloaded || moderateEncode -> ScreenQualityReason.ENCODER
        weakBandwidth || moderateBandwidth -> ScreenQualityReason.BANDWIDTH
        weakLoss -> ScreenQualityReason.LOSS
        weakDelay -> ScreenQualityReason.QUEUE
        else -> ScreenQualityReason.RECOVERY
    }

    private fun healthy(stats: MediaStats, screen: VideoSendStats?): Boolean =
        stats.sampleAvailable && stats.availableOutgoingKbps?.let { it >= 3_000 } == true &&
            (stats.thermalStatus ?: 0) < 2 && screen != null && screen.active != false &&
            screen.encodeMs?.let { it <= 35 } == true && screen.sendDelayMs?.let { it <= 60 } == true &&
            screen.outboundReportFresh && screen.outboundLoss?.let { it < 0.03 } == true &&
            screen.qualityLimitation == "none"
}
