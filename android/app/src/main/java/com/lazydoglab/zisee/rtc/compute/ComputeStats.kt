package com.lazydoglab.zisee.rtc.compute

/**
 * Contiguous real-clock split of one base-pipeline frame, microseconds:
 * arrival →[pre] queued →[queue] GL start →[submit] submitted →[wait] GPU done →[resume] caller.
 * [gpuUs] is the timer-query execution time (-1 when unavailable); [addedUs] is the whole span.
 */
data class FramePhases(val preUs: Long, val queueUs: Long, val submitUs: Long, val waitUs: Long,
    val resumeUs: Long, val gpuUs: Long, val addedUs: Long)

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

    fun countOver(addedUs: Long): Int = frames.count { it.addedUs > addedUs }

    fun summary(): PhaseSummary? {
        if (frames.isEmpty()) return null
        val gpu = frames.map { it.gpuUs }.filter { it >= 0 }
        fun at(pct: Int) = FramePhases(
            preUs = percentile(frames.map { it.preUs }, pct),
            queueUs = percentile(frames.map { it.queueUs }, pct),
            submitUs = percentile(frames.map { it.submitUs }, pct),
            waitUs = percentile(frames.map { it.waitUs }, pct),
            resumeUs = percentile(frames.map { it.resumeUs }, pct),
            gpuUs = if (gpu.isEmpty()) -1 else percentile(gpu, pct),
            addedUs = percentile(frames.map { it.addedUs }, pct))
        return PhaseSummary(frames.size, at(50), at(95))
    }
}

/** Fixed-width per-frame breakdowns (e.g. the submit split), microseconds. Owner thread only. */
class SplitWindow(private val width: Int, private val capacity: Int = 120) {
    private val rows = ArrayDeque<LongArray>()

    fun add(row: LongArray) {
        require(row.size == width)
        rows.addLast(row.copyOf())
        if (rows.size > capacity) rows.removeFirst()
    }

    fun clear() = rows.clear()

    fun p95(): LongArray? = if (rows.isEmpty()) null else LongArray(width) { i -> percentile(rows.map { it[i] }, 95) }
}

/** Auxiliary/detector cost. Thread-safe: written on GL/worker threads, read for logs elsewhere. */
class LatencyWindow(private val capacity: Int = 30) {
    private val values = ArrayDeque<Long>()

    @Synchronized fun add(us: Long) {
        values.addLast(us)
        if (values.size > capacity) values.removeFirst()
    }

    /** "count/p95us"; "0/-1" when nothing ran. */
    @Synchronized fun summary(): String = if (values.isEmpty()) "0/-1" else "${values.size}/${percentile(values.toList(), 95)}"
}
