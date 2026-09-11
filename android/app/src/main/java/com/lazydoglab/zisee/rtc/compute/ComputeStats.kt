package com.lazydoglab.zisee.rtc.compute

/** Real-clock split of one base-pipeline frame in microseconds; [gpuUs] is -1 without timer queries. */
data class FramePhases(val queueUs: Long, val submitUs: Long, val waitUs: Long, val gpuUs: Long, val addedUs: Long)

data class PhaseSummary(val samples: Int, val p50: FramePhases, val p95: FramePhases)

internal fun percentile(values: List<Long>, pct: Int): Long = values.sorted()[(values.size * pct + 99) / 100 - 1]

/** Base-pipeline frames only; warmup, resize and auxiliary work stay out. Owner thread only. */
class ComputeStatsWindow(private val capacity: Int = 120) {
    private val frames = ArrayDeque<FramePhases>()
    val size: Int get() = frames.size

    fun add(phases: FramePhases) {
        frames.addLast(phases)
        if (frames.size > capacity) frames.removeFirst()
    }

    fun clear() = frames.clear()

    fun summary(): PhaseSummary? {
        if (frames.isEmpty()) return null
        val gpu = frames.map { it.gpuUs }.filter { it >= 0 }
        fun at(pct: Int) = FramePhases(percentile(frames.map { it.queueUs }, pct),
            percentile(frames.map { it.submitUs }, pct), percentile(frames.map { it.waitUs }, pct),
            if (gpu.isEmpty()) -1 else percentile(gpu, pct), percentile(frames.map { it.addedUs }, pct))
        return PhaseSummary(frames.size, at(50), at(95))
    }
}

/** Auxiliary task cost. Written on the GL thread, read for logs on the capture thread. */
class LatencyWindow(private val capacity: Int = 30) {
    private val values = ArrayDeque<Long>()

    @Synchronized fun add(us: Long) {
        values.addLast(us)
        if (values.size > capacity) values.removeFirst()
    }

    /** "count/p95us"; "0/-1" when nothing ran. */
    @Synchronized fun summary(): String = if (values.isEmpty()) "0/-1" else "${values.size}/${percentile(values.toList(), 95)}"
}
