package com.lazydoglab.zisee.rtc.compute

/** Workload tiers, ordered lightest to heaviest. */
enum class ProcessingTier { OFF, RESIZE_ONLY, NO_AUX, FULL }
enum class GuardState { NORMAL, DEGRADED, PROBE, HARD_DISABLED }
/**
 * Where late frames spent their time. QUEUE: waiting for the GL thread (incl. auxiliary readbacks).
 * EXTERNAL: scheduling outside our own work (before queueing, or waking after the GPU finished).
 */
enum class ComputePressure { NONE, GPU_COMPUTE, GPU_SYNC, CPU_SUBMIT, QUEUE, EXTERNAL }
enum class FrameAction { BYPASS, SHADOW, PROCESS }

/** One measured frame in milliseconds; [gpuMs] is null when timer queries are unavailable. */
data class GuardSample(val addedMs: Double, val gpuMs: Double?, val waitMs: Double = 0.0,
    val submitMs: Double = 0.0, val queueMs: Double = 0.0, val externalMs: Double = 0.0)

/** [from] == [to] means the violation was held rather than shed (external pressure). */
data class GuardTransition(val from: ProcessingTier, val to: ProcessingTier, val cause: String,
    val pressure: ComputePressure)

/**
 * The processor's single authority on its own health (COMPUTE_CONTROL_LOOP.md §4). Sheds one tier
 * per violation, climbs one tier after sustained health, and at OFF keeps measuring real work on
 * sparse shadow frames instead of recovering blind. A lone long frame is recorded, not punished.
 * Owner thread only.
 */
class ProcessingGuard(private val warmupFrames: Int = 3) {
    var tier = ProcessingTier.FULL; private set
    var pressure = ComputePressure.NONE; private set
    var hardDisabled = false; private set
    /** Whether the latest recorded frame entered the evidence window (not warmup/one-time cost). */
    var lastCounted = false; private set
    val state: GuardState get() = when {
        hardDisabled -> GuardState.HARD_DISABLED
        tier == ProcessingTier.OFF -> GuardState.PROBE
        tier == ProcessingTier.FULL -> GuardState.NORMAL
        else -> GuardState.DEGRADED
    }
    val addedP95Ms: Double? get() = if (window.isEmpty()) null else p95(window.map { it.addedMs })

    private val window = ArrayDeque<GuardSample>()
    private val longFrames = ArrayDeque<Long>()
    private val failures = IntArray(ProcessingTier.entries.size)
    private var consecutiveLate = 0
    private var warmupSeen = 0
    private var tierSinceMs: Long? = null
    private var tierEnteredMs: Long? = null
    private var probeCountdown = 0
    private var lastSampleMs: Long? = null

    /** Stop/ownership changes and external bypass invalidate evidence, not the current safety tier. */
    fun interruptEvidence() {
        window.clear(); longFrames.clear(); consecutiveLate = 0; lastCounted = false
        tierSinceMs = null; tierEnteredMs = null; lastSampleMs = null
    }

    /** Healthy time the next climb needs; null at FULL (nothing above) and OFF (probe decides). */
    fun climbDelayMs(): Long? =
        if (tier == ProcessingTier.OFF || tier == ProcessingTier.FULL) null
        else climbDelayFor(ProcessingTier.entries[tier.ordinal + 1])

    /** Call once per eligible frame. At OFF only every [PROBE_INTERVAL]th frame is shadow-processed. */
    fun action(): FrameAction = when {
        hardDisabled -> FrameAction.BYPASS
        tier != ProcessingTier.OFF -> FrameAction.PROCESS
        probeCountdown-- <= 0 -> { probeCountdown = PROBE_INTERVAL - 1; FrameAction.SHADOW }
        else -> FrameAction.BYPASS
    }

    /** GPU faults only; budget pressure never lands here. */
    fun hardDisable() { hardDisabled = true; window.clear() }

    /** [oneTimeCost] marks a frame that reallocated textures for a new size: bounded like warmup. */
    fun record(sample: GuardSample, nowMs: Long, oneTimeCost: Boolean = false): GuardTransition? {
        if (hardDisabled) return null
        if (oneTimeCost || lastSampleMs?.let { nowMs - it !in 0..MAX_SAMPLE_GAP_MS } == true) {
            interruptEvidence()
        }
        lastSampleMs = nowMs
        if (tierSinceMs == null) tierSinceMs = nowMs
        val entered = tierEnteredMs ?: nowMs.also { tierEnteredMs = it }
        // A tier held long enough has proven itself; forget its earlier failures.
        if (nowMs - entered >= SUSTAIN_MS) failures[tier.ordinal] = 0
        lastCounted = !oneTimeCost && warmupSeen >= warmupFrames
        if (!lastCounted) {
            if (!oneTimeCost) warmupSeen++
            return if (sample.addedMs > WARMUP_FRAME_MS) shed(nowMs, "warmup", classify(listOf(sample))) else null
        }
        window.addLast(sample)
        if (window.size > WINDOW) window.removeFirst()
        consecutiveLate = if (sample.addedMs > LATE_MS) consecutiveLate + 1 else 0
        if (sample.addedMs > LONG_MS) longFrames.addLast(nowMs)
        while (longFrames.isNotEmpty() && nowMs - longFrames.first() > LONG_SPAN_MS) longFrames.removeFirst()

        val minSamples = if (tier == ProcessingTier.OFF) PROBE_SAMPLES else MIN_WINDOW
        val gpuP95 = window.mapNotNull { it.gpuMs }.takeIf { it.isNotEmpty() }?.let(::p95)
        val cause = when {
            consecutiveLate >= 3 -> "consecutive"
            longFrames.size >= 3 -> "long_burst"
            window.size >= minSamples && window.count { it.addedMs > LATE_MS } > window.size * LATE_RATIO -> "miss_rate"
            window.size >= minSamples && (gpuP95 ?: 0.0) > GPU_P95_MS -> "gpu"
            else -> null
        }
        if (cause != null) return shed(nowMs, cause, classify(window.toList()))
        val since = tierSinceMs ?: nowMs
        return when {
            tier == ProcessingTier.OFF && window.size >= PROBE_SAMPLES -> climb(nowMs, "probe_ok")
            tier != ProcessingTier.OFF && tier != ProcessingTier.FULL && window.size >= MIN_WINDOW &&
                nowMs - since >= climbDelayFor(ProcessingTier.entries[tier.ordinal + 1]) -> climb(nowMs, "stable")
            else -> null
        }
    }

    private fun shed(nowMs: Long, cause: String, pressure: ComputePressure): GuardTransition? {
        val from = tier
        this.pressure = pressure
        resetEvidence(nowMs)
        // At OFF a failed probe only restarts the evidence: probing continues, nothing locks.
        if (from == ProcessingTier.OFF) return null
        // Late frames dominated by scheduling outside our work: dropping auxiliary tasks can relieve
        // CPU/GL contention, but shedding the base pipeline would not make those frames earlier.
        if (pressure == ComputePressure.EXTERNAL && from <= ProcessingTier.NO_AUX) {
            return GuardTransition(from, from, "${cause}_held", pressure)
        }
        failures[from.ordinal] = minOf(failures[from.ordinal] + 1, MAX_FAILURES)
        tier = ProcessingTier.entries[from.ordinal - 1]
        tierEnteredMs = nowMs
        return GuardTransition(from, tier, cause, pressure)
    }

    private fun climb(nowMs: Long, cause: String): GuardTransition {
        val from = tier
        tier = ProcessingTier.entries[from.ordinal + 1]
        tierEnteredMs = nowMs
        if (tier == ProcessingTier.FULL) pressure = ComputePressure.NONE
        resetEvidence(nowMs)
        return GuardTransition(from, tier, cause, pressure)
    }

    /** Each recent failure of the target tier doubles the wait before re-entering it. */
    private fun climbDelayFor(target: ProcessingTier): Long =
        minOf(CLIMB_AFTER_MS shl maxOf(failures[target.ordinal] - 1, 0), MAX_CLIMB_MS)

    private fun resetEvidence(nowMs: Long) {
        window.clear(); longFrames.clear(); consecutiveLate = 0; tierSinceMs = nowMs; probeCountdown = 0
    }

    private fun classify(samples: List<GuardSample>): ComputePressure {
        val gpu = samples.mapNotNull { it.gpuMs }
        if (gpu.isNotEmpty() && p95(gpu) > GPU_P95_MS) return ComputePressure.GPU_COMPUTE
        // Attribute the late frames' time to where it was actually spent.
        val late = samples.filter { it.addedMs > LATE_MS }.ifEmpty { samples }
        return linkedMapOf(
            ComputePressure.GPU_SYNC to late.sumOf { it.waitMs },
            ComputePressure.CPU_SUBMIT to late.sumOf { it.submitMs },
            ComputePressure.QUEUE to late.sumOf { it.queueMs },
            ComputePressure.EXTERNAL to late.sumOf { it.externalMs },
        ).maxBy { it.value }.key
    }

    private fun p95(values: List<Double>): Double = values.sorted()[(values.size * 95 + 99) / 100 - 1]

    companion object {
        const val WARMUP_FRAME_MS = 50.0
        const val LATE_MS = 12.0
        const val LONG_MS = 20.0
        const val LONG_SPAN_MS = 5_000L
        const val LATE_RATIO = 0.05
        const val GPU_P95_MS = 5.0
        const val WINDOW = 120
        const val MIN_WINDOW = 60
        const val PROBE_SAMPLES = 30
        const val PROBE_INTERVAL = 10
        const val CLIMB_AFTER_MS = 10_000L
        const val MAX_CLIMB_MS = 60_000L
        const val SUSTAIN_MS = 60_000L
        const val MAX_FAILURES = 4
        // Allows sparse OFF probes (one in ten camera frames), but never counts a stopped camera.
        const val MAX_SAMPLE_GAP_MS = 3_000L
    }
}
