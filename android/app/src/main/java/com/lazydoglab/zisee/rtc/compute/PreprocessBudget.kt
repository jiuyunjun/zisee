package com.lazydoglab.zisee.rtc.compute

enum class BudgetBreach { WARMUP, FRAME, P95 }

/**
 * Per-processor latency guard. The first frames pay one-time driver/allocation costs, so they get
 * a bounded allowance and stay out of the P95 window; steady-state limits are unchanged.
 */
class PreprocessBudget(private val warmupFrames: Int = 3) {
    private val durations = ArrayDeque<Double>()
    private var warmupSeen = 0
    var p95Ms: Double? = null; private set
    val samples: Int get() = durations.size

    fun record(elapsedMs: Double): BudgetBreach? {
        if (warmupSeen < warmupFrames) {
            warmupSeen++
            return if (elapsedMs > WARMUP_FRAME_MS) BudgetBreach.WARMUP else null
        }
        durations.addLast(elapsedMs)
        if (durations.size > WINDOW) durations.removeFirst()
        val p95 = durations.sorted()[(durations.size * 95 + 99) / 100 - 1]
        p95Ms = p95
        return when {
            elapsedMs > FRAME_MS -> BudgetBreach.FRAME
            durations.size >= MIN_P95_SAMPLES && p95 > P95_MS -> BudgetBreach.P95
            else -> null
        }
    }

    companion object {
        const val WARMUP_FRAME_MS = 50.0
        const val FRAME_MS = 20.0
        const val P95_MS = 5.0
        const val MIN_P95_SAMPLES = 30
        const val WINDOW = 60
    }
}
