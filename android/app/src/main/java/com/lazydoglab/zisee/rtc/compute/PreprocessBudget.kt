package com.lazydoglab.zisee.rtc.compute

enum class BudgetBreach { WARMUP, FRAME, P95 }

/** Real-clock split of one processed frame in microseconds; [gpuUs] is -1 without timer queries. */
data class FramePhases(val queueUs: Long, val cpuUs: Long, val finishUs: Long, val gpuUs: Long)

/**
 * Per-processor latency guard. The first frames after each (re)start pay one-time driver/allocation
 * costs, so they get a bounded allowance and stay out of the P95 window; steady-state limits are
 * unchanged. A breach pauses processing with backoff rather than ending it for the call: the busy
 * call-setup window says little about the load minutes later.
 */
class PreprocessBudget(
    private val warmupFrames: Int = 3,
    private val backoffMs: LongArray = longArrayOf(30_000, 60_000, 120_000),
) {
    private val durations = ArrayDeque<Double>()
    private val phases = ArrayDeque<FramePhases>()
    private var warmupSeen = 0
    private var coolingUntilMs: Long? = null
    var p95Ms: Double? = null; private set
    val samples: Int get() = durations.size
    var retries = 0; private set
    var exhausted = false; private set
    val coolingActive: Boolean get() = coolingUntilMs != null
    /** Pause chosen by the latest breach; null when that breach was final. */
    var lastCooldownMs: Long? = null; private set

    /** True while paused; an expired pause starts a fresh warmup and window. */
    fun cooling(nowMs: Long): Boolean {
        val until = coolingUntilMs ?: return false
        if (nowMs < until) return true
        coolingUntilMs = null
        durations.clear(); phases.clear(); warmupSeen = 0; p95Ms = null
        return false
    }

    /** [oneTimeCost] marks a frame that reallocated textures for a new size: same bounded allowance as warmup. */
    fun record(elapsedMs: Double, nowMs: Long, phase: FramePhases? = null, oneTimeCost: Boolean = false): BudgetBreach? {
        val breach = if (oneTimeCost || warmupSeen < warmupFrames) {
            if (!oneTimeCost) warmupSeen++
            if (elapsedMs > WARMUP_FRAME_MS) BudgetBreach.WARMUP else null
        } else {
            durations.addLast(elapsedMs)
            if (durations.size > WINDOW) durations.removeFirst()
            phase?.let { phases.addLast(it); if (phases.size > WINDOW) phases.removeFirst() }
            val p95 = p95(durations.toList())
            p95Ms = p95
            when {
                elapsedMs > FRAME_MS -> BudgetBreach.FRAME
                durations.size >= MIN_P95_SAMPLES && p95 > P95_MS -> BudgetBreach.P95
                else -> null
            }
        }
        if (breach != null) {
            if (retries < backoffMs.size) {
                lastCooldownMs = backoffMs[retries]
                coolingUntilMs = nowMs + backoffMs[retries]
                retries++
            } else { lastCooldownMs = null; exhausted = true }
        }
        return breach
    }

    fun phaseP95(): FramePhases? = if (phases.isEmpty()) null else FramePhases(
        p95(phases.map { it.queueUs }), p95(phases.map { it.cpuUs }), p95(phases.map { it.finishUs }),
        phases.map { it.gpuUs }.filter { it >= 0 }.let { if (it.isEmpty()) -1 else p95(it) })

    private fun <T : Comparable<T>> p95(values: List<T>): T = values.sorted()[(values.size * 95 + 99) / 100 - 1]

    companion object {
        const val WARMUP_FRAME_MS = 50.0
        const val FRAME_MS = 20.0
        const val P95_MS = 5.0
        const val MIN_P95_SAMPLES = 30
        const val WINDOW = 60
    }
}
