package com.lazydoglab.zisee.rtc

/**
 * V2 step 1 switch (§12.3). OFF is exactly today's behaviour and the new code never runs. SHADOW
 * lets [CameraAdaptationPolicy] run every sample alongside the old [VideoQualityPolicy] but only
 * logs its plan. ACTIVE lets the new applier write camera sender params instead of the old one.
 * Read once per call; unknown/missing values default to OFF so a bad BuildConfig value cannot turn
 * the experiment on by accident.
 */
enum class VideoAdaptationMode {
    OFF, SHADOW, ACTIVE;
    companion object {
        fun parse(raw: String?): VideoAdaptationMode = when (raw) {
            "SHADOW" -> SHADOW
            "ACTIVE" -> ACTIVE
            else -> OFF
        }
    }
}

/** §6.2 fixed camera operating points, 16:9 examples; real capture keeps the source aspect and
 * respects device capability. Bitrate bounds are an experimental application ceiling range, not a
 * floor guarantee — native congestion control still owns the instantaneous send rate. */
enum class CameraTier(val width: Int, val height: Int, val fps: Int, val minKbps: Int, val maxKbps: Int) {
    C0(320, 180, 15, 150, 350),
    C1(640, 360, 24, 400, 800),
    C2(1280, 720, 30, 900, 2_200),
    C3(1920, 1080, 30, 2_500, 4_000),
    C4(1920, 1080, 60, 4_000, 6_000),
}

/** §10 reason enum, the subset this policy actually produces. */
enum class CameraAdaptationReason { THERMAL, ENCODER, BANDWIDTH, QUEUE, VIEW, RECOVERY, STALE_STATS }

/** Flags describing why a track's plan is not simply "the tier its share allows". */
enum class CameraAdaptationLimit { AUX_PAUSED, VIEW_CAPPED, STALE_BWE }

data class TrackPlan(val tier: CameraTier, val width: Int, val height: Int, val fps: Int, val maxBitrateBps: Int)

data class CameraPlan(
    val revision: Int,
    val main: TrackPlan,
    val aux: TrackPlan?,
    val reason: CameraAdaptationReason,
    val limits: Set<CameraAdaptationLimit>,
    val poolKbps: Long,
    val bweKbps: Long?,
    val repairEstimated: Boolean,
)

/** Per-sender observation the policy needs, framework-free so it is testable from plain values
 * without [MediaStats]/WebRTC. [retransmittedKbps] is this sender's own repair subset of its send
 * rate — a measured value here means the budget does not also need to estimate it. */
data class CameraTrackObservation(
    val encodeMs: Double? = null,
    val sendDelayMs: Double? = null,
    val outboundLoss: Double? = null,
    val outboundReportFresh: Boolean = false,
    val qualityLimitation: String = "unknown",
    val retransmittedKbps: Double? = null,
)

data class CameraAdaptationInput(
    val nowMs: Long,
    val availableOutgoingKbps: Long?,
    val thermalStatus: Int?,
    val main: CameraTrackObservation,
    /** Null when only one camera is live. */
    val aux: CameraTrackObservation? = null,
    /** §5.3: single-camera main kept as a thumbnail by the viewer is capped like the old thumbnail
     * path; irrelevant once a second camera is live (that camera becomes the thumbnail instead). */
    val mainViewedSmall: Boolean = false,
)

/**
 * Pure Kotlin camera tier/budget policy (§5.2, §5.3, §6.2, §7). Does not touch any sender or
 * source; [NativeRtcSession] decides, based on [VideoAdaptationMode], whether to only log this
 * policy's plan (SHADOW) or apply it (ACTIVE).
 */
class CameraAdaptationPolicy(
    private val supportsFullHd: Boolean,
    preferFullHd: Boolean = false,
    private val supports60: Boolean = false,
) {
    // §6.2: V1 startup choice carried over unchanged so ACTIVE does not change what a call opens at.
    private val startTier = if (supportsFullHd && preferFullHd) CameraTier.C3 else CameraTier.C2
    private val mainState = TrackState(startTier)
    private var auxState: TrackState? = null
    private var lastAuxPresent = false
    private var lastMs: Long? = null
    /** §5.2: h stays lowered after a bandwidth/queue-caused downgrade until the link recovers. */
    private var h = STABLE_H
    private var probeHealthySince: Long? = null
    private var probeUntilMs = 0L
    private var nextProbeMs = 0L

    var lastPlan: CameraPlan = CameraPlan(0, TrackPlan(startTier, startTier.width, startTier.height, startTier.fps,
        startTier.minKbps * 1_000), null, CameraAdaptationReason.RECOVERY, emptySet(), 0, null, false); private set

    /** §7 routeGeneration handling, mirrored from [VideoQualityPolicy.routeChanged]: drop to C1,
     * remember the previous tier as a 30 s restoration ceiling climbed back at 2 s per step. */
    fun routeChanged(nowMs: Long) {
        probeHealthySince = null
        probeUntilMs = 0L
        nextProbeMs = nowMs + 8_000L
        mainState.armRestore(nowMs, RESTORE_WINDOW_MS)
        auxState?.armRestore(nowMs, RESTORE_WINDOW_MS)
        mainState.forceTo(CameraTier.C1, CameraAdaptationReason.RECOVERY, nowMs)
        auxState?.forceTo(CameraTier.C1, CameraAdaptationReason.RECOVERY, nowMs)
        lastMs = null
        // The first key frame on the new route is this decision, so — like VideoQualityPolicy — the
        // drop is visible in lastPlan immediately rather than waiting for the next sample.
        fun floor(tier: CameraTier) = TrackPlan(tier, tier.width, tier.height, tier.fps, tier.minKbps * 1_000)
        lastPlan = CameraPlan(lastPlan.revision + 1, floor(CameraTier.C1), auxState?.let { floor(CameraTier.C1) },
            CameraAdaptationReason.RECOVERY, emptySet(), lastPlan.poolKbps, lastPlan.bweKbps, lastPlan.repairEstimated)
    }

    /** Forces both tracks to C0 immediately, independent of the normal per-sample cadence: the fast
     * lane the caller uses when a stats sample never arrives (severe thermal with no RTCStats), and
     * what [update] itself falls back on when BWE is missing but thermal/CPU is still severe. */
    fun severe(nowMs: Long, reason: CameraAdaptationReason): CameraPlan {
        probeHealthySince = null
        probeUntilMs = 0L
        mainState.step(nowMs, CameraTier.C0, reason, immediate = true)
        auxState?.step(nowMs, CameraTier.C0, reason, immediate = true)
        lastMs = nowMs
        fun floor(tier: CameraTier) = TrackPlan(tier, tier.width, tier.height, tier.fps, tier.minKbps * 1_000)
        val newMain = floor(CameraTier.C0)
        val newAux = auxState?.let { floor(CameraTier.C0) }
        if (lastPlan.main == newMain && lastPlan.aux == newAux && lastPlan.reason == reason) return lastPlan
        lastPlan = CameraPlan(lastPlan.revision + 1, newMain, newAux, reason, emptySet(), lastPlan.poolKbps, lastPlan.bweKbps, lastPlan.repairEstimated)
        return lastPlan
    }

    fun update(input: CameraAdaptationInput): CameraPlan {
        val nowMs = input.nowMs
        val previousMs = lastMs
        lastMs = nowMs
        val gap = previousMs != null && (nowMs <= previousMs || nowMs - previousMs > 3_000)
        val hasAux = input.aux != null
        if (hasAux && auxState == null) auxState = TrackState(CameraTier.C0)
        if (!hasAux) auxState = null
        val aux = auxState
        if (gap) {
            mainState.resetWindows()
            aux?.resetWindows()
        }
        val bwe = input.availableOutgoingKbps
        if (bwe == null) {
            probeHealthySince = null
            probeUntilMs = 0L
            mainState.resetWindows()
            aux?.resetWindows()
            // Severe thermal/CPU protection is not budget logic: it must not wait on a BWE sample
            // that may never arrive. Only the budget and upgrade windows are skipped when BWE is
            // missing — a genuine safety condition still forces C0 immediately.
            val immediate = (input.thermalStatus ?: 0) >= 3 || input.main.qualityLimitation == "cpu" ||
                (hasAux && input.aux?.qualityLimitation == "cpu")
            if (immediate) {
                val reason = if ((input.thermalStatus ?: 0) >= 3) CameraAdaptationReason.THERMAL else CameraAdaptationReason.ENCODER
                return severe(nowMs, reason)
            }
            // §7: missing telemetry otherwise just holds the applied tier and breaks the upgrade
            // window; it never forces a downgrade on its own.
            if (lastPlan.reason == CameraAdaptationReason.STALE_STATS) return lastPlan
            lastPlan = lastPlan.copy(reason = CameraAdaptationReason.STALE_STATS, limits = lastPlan.limits + CameraAdaptationLimit.STALE_BWE)
            return lastPlan
        }

        // §5.2 budget.
        val measuredRepair = listOfNotNull(input.main.retransmittedKbps, input.aux?.retransmittedKbps)
            .takeIf { it.isNotEmpty() }?.sum()
        val repairEstimated = measuredRepair == null
        val repairKbps = (measuredRepair ?: (bwe * 0.10)).toLong()
        val safe = (bwe * h).toLong()
        val pool = (safe - AUDIO_RESERVE_KBPS - DATA_RESERVE_KBPS - repairKbps).coerceAtLeast(0)

        // §5.3 allocation.
        var limits = emptySet<CameraAdaptationLimit>()
        val auxShareRaw = if (hasAux) minOf(pool * 20 / 100, 450L) else 0L
        val auxShare = when {
            !hasAux -> 0L
            auxShareRaw < CameraTier.C0.minKbps -> { limits = limits + CameraAdaptationLimit.AUX_PAUSED; CameraTier.C0.minKbps.toLong() }
            else -> auxShareRaw
        }
        val mainShare = (pool - auxShare).coerceAtLeast(0)
        val mainCapView = input.mainViewedSmall && !hasAux
        if (mainCapView) limits = limits + CameraAdaptationLimit.VIEW_CAPPED

        // Main track step.
        val mainAchievable = tierForShare(mainShare, mainCapView, input.main.encodeMs, input.thermalStatus)
        val (mainTarget, mainReason, mainImmediate) = plannedStep(mainState.current, mainAchievable, input.main,
            input.thermalStatus, pool, mainCapView && mainAchievable.ordinal >= CameraTier.C1.ordinal)
        val mainTier = mainState.step(nowMs, mainTarget, mainReason, mainImmediate)
        if (mainReason == CameraAdaptationReason.BANDWIDTH || mainReason == CameraAdaptationReason.QUEUE) h = CONSTRAINED_H
        else if (mainTier.ordinal > mainState.enteredWithOrdinal) h = STABLE_H

        // Aux track step (never HD/60-gated: its numeric cap keeps it low anyway).
        var auxTier: CameraTier? = null
        var auxReasonUsed = CameraAdaptationReason.RECOVERY
        if (hasAux && aux != null) {
            val auxAchievable = tierForShare(auxShare, capView = false, encodeMsForC4 = null, thermalStatus = null)
            val (auxTarget, auxReason, auxImmediate) = plannedStep(aux.current, auxAchievable, input.aux!!, input.thermalStatus, pool, false)
            auxTier = aux.step(nowMs, auxTarget, auxReason, auxImmediate)
            auxReasonUsed = auxReason
        }

        // §5.2: the budget chooses the tier, but a share-clamped ceiling suppresses the very BWE
        // signal the budget is supposed to react to (a low early estimate produces a low cap, which
        // then keeps the estimate low). The main track's ceiling is therefore the tier's own
        // maxKbps — native congestion control still owns the actual send rate below it. The aux
        // track's share is a hard allocation carved out of the pool, so it keeps the share clamp.
        fun trackPlan(tier: CameraTier, ceilingKbps: Long) =
            TrackPlan(tier, tier.width, tier.height, tier.fps, (ceilingKbps * 1_000).toInt())

        // A bounded recovery probe may temporarily raise only the sender ceiling.
        // The tier ceiling can itself pin BWE below the next tier's entry threshold. Probe only
        // the ceiling (never force a resolution/minimum rate), with fresh healthy feedback,
        // bounded duration and cooldown. This also works when only the remote peer changed route.
        val probeHealthy = !gap && !mainCapView && (input.thermalStatus ?: 0) < 2 &&
            input.main.outboundReportFresh && input.main.outboundLoss?.let { it < 0.02 } == true &&
            input.main.sendDelayMs?.let { it <= 30.0 } == true &&
            input.main.encodeMs?.let { it <= 1_000.0 / mainTier.fps * 0.7 } == true &&
            input.main.qualityLimitation in setOf("none", "bandwidth") &&
            bwe >= mainTier.maxKbps * 3L / 4
        if (!probeHealthy) { probeHealthySince = null; probeUntilMs = 0L }
        else if (probeHealthySince == null) probeHealthySince = nowMs
        val probeTier = next(mainTier)
        val canProbe = probeTier != mainTier && probeTier != CameraTier.C4 &&
            (probeTier != CameraTier.C3 || supportsFullHd)
        if (canProbe && probeHealthy && nowMs >= nextProbeMs &&
            nowMs - requireNotNull(probeHealthySince) >= 8_000L) {
            probeUntilMs = nowMs + 12_000L
            nextProbeMs = nowMs + 30_000L
        }
        val probeCeiling = maxOf(probeTier.maxKbps.toLong(),
            ((probeTier.minKbps / 0.8 + AUDIO_RESERVE_KBPS + DATA_RESERVE_KBPS +
                if (hasAux) 450 else 0) / (CONSTRAINED_H - 0.10) * 1.10).toLong()).coerceAtMost(6_000L)
        val ceiling = if (canProbe && probeHealthy && nowMs < probeUntilMs)
            probeCeiling else mainTier.maxKbps.toLong()
        val newMain = trackPlan(mainTier, ceiling)
        val newAux = auxTier?.let {
            stableOrNew(lastPlan.aux, trackPlan(it, auxShare.coerceIn(it.minKbps.toLong(), it.maxKbps.toLong())))
        }
        val auxPresenceChanged = hasAux != lastAuxPresent
        lastAuxPresent = hasAux
        val changed = newMain != lastPlan.main || newAux != lastPlan.aux || auxPresenceChanged
        if (!changed) return lastPlan
        val reason = if (newMain != lastPlan.main) mainReason else if (newAux != lastPlan.aux) auxReasonUsed else lastPlan.reason
        lastPlan = CameraPlan(lastPlan.revision + 1, newMain, newAux, reason, limits, pool, bwe, repairEstimated)
        return lastPlan
    }

    /** Highest tier whose lower bound is at or below [shareKbps]; falls back to [CameraTier.C0] as a
     * floor even when the share is below C0's own minimum (§ "zero pool" case). */
    private fun tierForShare(shareKbps: Long, capView: Boolean, encodeMsForC4: Double?, thermalStatus: Int?): CameraTier {
        val allowed = CameraTier.entries.filter { tier ->
            when {
                capView && tier.ordinal > CameraTier.C1.ordinal -> false
                tier == CameraTier.C3 -> supportsFullHd
                tier == CameraTier.C4 -> supports60 && supportsFullHd &&
                    encodeMsForC4 != null && encodeMsForC4 <= 12.0 && (thermalStatus ?: 0) < 1
                else -> true
            }
        }
        return allowed.lastOrNull { it.minKbps <= shareKbps } ?: CameraTier.C0
    }

    private fun previous(tier: CameraTier) = CameraTier.entries.getOrElse(tier.ordinal - 1) { CameraTier.C0 }
    private fun next(tier: CameraTier) = CameraTier.entries.getOrElse(tier.ordinal + 1) { CameraTier.C4 }

    /** One hysteresis pass' target for a single track: the achievable tier further capped by an
     * immediate/sustained-condition step-down, or held back one tier if upgrade evidence is not
     * (yet) fresh and healthy. Never more than one step away from [current]. */
    private fun plannedStep(current: CameraTier, achievable: CameraTier, track: CameraTrackObservation,
        thermalStatus: Int?, pool: Long, viewBinding: Boolean): Triple<CameraTier, CameraAdaptationReason, Boolean> {
        val immediate = (thermalStatus ?: 0) >= 3 || track.qualityLimitation == "cpu"
        if (immediate) return Triple(CameraTier.C0, if ((thermalStatus ?: 0) >= 3) CameraAdaptationReason.THERMAL else CameraAdaptationReason.ENCODER, true)
        val frameIntervalMs = 1_000.0 / current.fps
        val overloadedEncode = track.encodeMs?.let { it > frameIntervalMs * 0.7 } == true
        val queueOrLoss = track.sendDelayMs?.let { it > 60.0 } == true ||
            (track.outboundLoss?.takeIf { track.outboundReportFresh }?.let { it >= 0.05 } == true)
        val forcedCap = if (overloadedEncode || queueOrLoss) previous(current) else CameraTier.C4
        var target = if (achievable.ordinal <= forcedCap.ordinal) achievable else forcedCap
        if (target.ordinal > current.ordinal) {
            // §7 normal upgrade: only one step, and only with fresh, healthy evidence.
            val upgradeTier = next(current)
            val healthy = upgradeTier.minKbps <= pool * 0.8 &&
                track.outboundLoss?.takeIf { track.outboundReportFresh }?.let { it < 0.02 } != false &&
                track.sendDelayMs?.let { it <= 30.0 } != false &&
                track.qualityLimitation in setOf("none", "bandwidth") && (thermalStatus ?: 0) < 2
            target = if (healthy) upgradeTier else current
        } else if (target.ordinal < current.ordinal) {
            target = previous(current)
        }
        val reason = when {
            target.ordinal > current.ordinal -> CameraAdaptationReason.RECOVERY
            target.ordinal < current.ordinal -> when {
                overloadedEncode -> CameraAdaptationReason.ENCODER
                queueOrLoss -> CameraAdaptationReason.QUEUE
                viewBinding -> CameraAdaptationReason.VIEW
                else -> CameraAdaptationReason.BANDWIDTH
            }
            else -> CameraAdaptationReason.RECOVERY
        }
        return Triple(target, reason, false)
    }

    /** Aux only: its ceiling is a share of the pool, so it can wobble by a few percent every sample
     * without the tier changing. Main's ceiling is now purely a function of its tier (see
     * [update]'s `newMain`), so it never needs this suppression. */
    private fun stableOrNew(old: TrackPlan?, new: TrackPlan): TrackPlan {
        if (old != null && old.tier == new.tier && old.maxBitrateBps > 0) {
            val delta = kotlin.math.abs(new.maxBitrateBps - old.maxBitrateBps).toDouble() / old.maxBitrateBps
            if (delta < 0.15) return old
        }
        return new
    }

    /** Per-track hysteresis state: which tier is currently in force, and how long the evidence for
     * moving away from it has been consistent. Mirrors [VideoQualityPolicy]'s pending/cooldown shape. */
    private class TrackState(start: CameraTier) {
        var current = start; private set
        var enteredWithOrdinal = start.ordinal; private set
        private var reason = CameraAdaptationReason.RECOVERY
        private var pending: CameraTier? = null
        private var sinceMs = 0L
        private var changedMs = Long.MIN_VALUE / 2
        private var restoreCeiling: CameraTier? = null
        private var restoreUntilMs = 0L

        fun armRestore(nowMs: Long, windowMs: Long) {
            val held = restoreCeiling?.takeIf { nowMs < restoreUntilMs && it.ordinal > current.ordinal }
            restoreCeiling = held ?: current
            restoreUntilMs = nowMs + windowMs
        }

        fun forceTo(tier: CameraTier, r: CameraAdaptationReason, nowMs: Long) {
            enteredWithOrdinal = current.ordinal
            current = tier; reason = r; pending = null; changedMs = nowMs
        }

        fun resetWindows() { pending = null }

        fun step(nowMs: Long, target: CameraTier, targetReason: CameraAdaptationReason, immediate: Boolean): CameraTier {
            if (immediate) {
                enteredWithOrdinal = current.ordinal
                current = target; reason = targetReason; pending = null; changedMs = nowMs
                if (restoreCeiling?.let { target.ordinal >= it.ordinal } == true) restoreCeiling = null
                return current
            }
            if (target == current) { pending = null; return current }
            val up = target.ordinal > current.ordinal
            if (pending != target) { pending = target; sinceMs = nowMs }
            val restoring = up && nowMs < restoreUntilMs && restoreCeiling?.let { target.ordinal <= it.ordinal } == true
            val wait = if (!up) 2_000L else if (restoring) RESTORE_WAIT_MS
                else if (reason == CameraAdaptationReason.THERMAL || reason == CameraAdaptationReason.ENCODER) 15_000L else 8_000L
            val cooled = changedMs == Long.MIN_VALUE / 2 || nowMs - changedMs >= if (restoring) RESTORE_WAIT_MS else 5_000L
            if (nowMs - sinceMs >= wait && cooled) {
                enteredWithOrdinal = current.ordinal
                current = target; reason = targetReason; pending = null; changedMs = nowMs
                if (restoreCeiling?.let { target.ordinal >= it.ordinal } == true) restoreCeiling = null
            }
            return current
        }
    }

    private companion object {
        const val AUDIO_RESERVE_KBPS = 64L
        const val DATA_RESERVE_KBPS = 16L
        const val STABLE_H = 0.90
        const val CONSTRAINED_H = 0.80
        const val RESTORE_WINDOW_MS = 30_000L
        const val RESTORE_WAIT_MS = 2_000L
    }
}
