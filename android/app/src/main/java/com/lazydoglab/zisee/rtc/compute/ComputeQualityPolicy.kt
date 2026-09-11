package com.lazydoglab.zisee.rtc.compute

/** Compute budget, deliberately independent of CameraTier (network resolution/bitrate). */
enum class ComputeLevel { C0, C1, C2, C3, C4 }
enum class ComputeReason { STARTUP, THERMAL, LOAD, POWER, UNAVAILABLE, RECOVERY, BURST }
data class ComputeDecision(val level: ComputeLevel, val reason: ComputeReason) {
    val denoiseStrength: Float get() = when (level) {
        ComputeLevel.C0 -> 0f
        ComputeLevel.C1 -> 0.15f
        ComputeLevel.C2 -> 0.3f
        ComputeLevel.C3, ComputeLevel.C4 -> 0.4f
    }
}

data class ComputeInput(
    val nowMs: Long,
    val thermalStatus: Int?,
    val forecastHeadroom: Float?,
    val powerSave: Boolean,
    val batteryPercent: Int?,
    val encodeMs: Double?, // RTC interval mean, NOT a frame P95.
    val fps: Int,
    val cpuLimited: Boolean = false,
    val preprocessP95Ms: Double? = null,
    val sampleFresh: Boolean = true,
    /** Encode entry through callback P95 includes codec scheduling, unlike the RTC interval mean. */
    val encodeCallbackP95Ms: Double? = null,
)

/** Conservative starting heuristics; fast down, 25 seconds of continuous evidence per upshift.
 * Unknown telemetry cannot authorize C2+. C4 is explicit, bounded, with a 60 second cooldown.
 */
class ComputeQualityPolicy {
    var decision = ComputeDecision(ComputeLevel.C1, ComputeReason.STARTUP); private set
    private var pending: ComputeLevel? = null
    private var pendingSince = 0L
    private var lastMs: Long? = null
    private var burstUntil = 0L
    private var nextBurst = 0L

    fun requestBurst(nowMs: Long): Boolean {
        if (decision.level != ComputeLevel.C3 || nowMs < nextBurst || nowMs != lastMs) return false
        burstUntil = nowMs + 8_000
        nextBurst = nowMs + 60_000
        return true
    }

    fun update(input: ComputeInput): ComputeDecision {
        val gap = lastMs?.let { input.nowMs - it !in 0..3_000 } == true
        if (gap) { pending = null; burstUntil = 0 }
        lastMs = input.nowMs
        val headroom = input.forecastHeadroom?.takeIf { it.isFinite() && it >= 0f }
        val encode = input.encodeCallbackP95Ms?.takeIf { it.isFinite() && it >= 0 }
            ?: input.encodeMs?.takeIf { it.isFinite() && it >= 0 }
        val preprocess = input.preprocessP95Ms?.takeIf { it.isFinite() && it >= 0 }
        val frameBudget = 1_000.0 / input.fps.coerceIn(1, 60)
        val (ceiling, reason) = when {
            (input.thermalStatus ?: 0) >= 3 || (headroom ?: 0f) >= 0.9f -> ComputeLevel.C0 to ComputeReason.THERMAL
            input.cpuLimited || (encode ?: 0.0) >= frameBudget * 0.7 || (preprocess ?: 0.0) > 5.0 -> ComputeLevel.C0 to ComputeReason.LOAD
            input.powerSave || (input.batteryPercent ?: 100) <= 15 -> ComputeLevel.C1 to ComputeReason.POWER
            (input.thermalStatus ?: 0) >= 2 || (headroom ?: 0f) >= 0.8f -> ComputeLevel.C1 to ComputeReason.THERMAL
            !input.sampleFresh || gap || headroom == null || encode == null || input.thermalStatus == null ||
                input.batteryPercent == null -> ComputeLevel.C1 to ComputeReason.UNAVAILABLE
            input.thermalStatus >= 1 || headroom >= 0.65f || encode >= frameBudget * 0.45 || input.batteryPercent < 40 ->
                ComputeLevel.C2 to ComputeReason.THERMAL
            else -> ComputeLevel.C3 to ComputeReason.RECOVERY
        }
        if (ceiling < ComputeLevel.C3) burstUntil = 0
        val target = if (input.nowMs < burstUntil && ceiling == ComputeLevel.C3) ComputeLevel.C4 else ceiling
        when {
            target < decision.level -> { decision = ComputeDecision(target, reason); pending = null }
            target == decision.level -> pending = null
            target == ComputeLevel.C4 -> { decision = ComputeDecision(target, ComputeReason.BURST); pending = null }
            else -> {
                val next = ComputeLevel.entries[decision.level.ordinal + 1]
                if (pending != next) { pending = next; pendingSince = input.nowMs }
                if (input.nowMs - pendingSince >= 25_000) {
                    decision = ComputeDecision(next, ComputeReason.RECOVERY)
                    pending = null
                }
            }
        }
        return decision
    }
}
