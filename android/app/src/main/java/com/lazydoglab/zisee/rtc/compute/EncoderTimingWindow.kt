package com.lazydoglab.zisee.rtc.compute

/** Numeric observations only. No frames, encoded payloads, SDP or private device identifiers. */
data class EncoderTimingStats(
    val encoderId: Int,
    val codec: String,
    val trackId: String?,
    val samples: Int,
    val sampledAtNs: Long,
    val callbackP50Ms: Double?,
    val callbackP95Ms: Double?,
    val callbackP99Ms: Double?,
    val qpMean: Double?,
    val qpSamples: Int,
    /** Counters describe this encoder instance, not each individual track. */
    val rejected: Long,
    val expired: Long,
    val unmatched: Long,
)

/** Bridges source and encoder timestamp domains (MediaCodec truncates ns to us). Collisions are
 * explicitly ambiguous, never guessed to be the last camera. One call owns one bounded registry.
 */
class FrameSourceRegistry {
    private data class Entry(val source: String?, val observedNs: Long)
    private val entries = linkedMapOf<Long, Entry>()
    @Synchronized fun record(timestampNs: Long, source: String, nowNs: Long) {
        prune(nowNs)
        val key = timestampNs / 1_000
        val old = entries[key]
        entries[key] = Entry(if (old == null || old.source == source) source else null,
            maxOf(old?.observedNs ?: nowNs, nowNs))
        while (entries.size > 256) entries.remove(entries.keys.first())
    }
    @Synchronized fun source(timestampNs: Long, nowNs: Long): String? {
        prune(nowNs)
        return entries[timestampNs / 1_000]?.takeIf { nowNs >= it.observedNs }?.source
    }
    @Synchronized fun clear() { entries.clear() }
    private fun prune(nowNs: Long) {
        // Camera and encoder threads read the clock before acquiring this monitor. An older
        // clock observation must not erase a newer entry (especially an ambiguous source).
        entries.entries.removeAll { nowNs - it.value.observedNs > 2_000_000_000L }
    }
}

/** Thread safe: encode and encoded callbacks run on different threads. Measures encode entry to
 * callback, including codec scheduling/queueing, NOT just MediaCodec execution or end-to-end latency.
 */
class EncoderTimingWindow(private val encoderId: Int, private val codec: String) {
    private data class Pending(val startedNs: Long, val trackId: String?, val ambiguous: Boolean = false)
    private data class Sample(val finishedNs: Long, val elapsedMs: Double, val qp: Int?, val trackId: String?)
    private val pending = linkedMapOf<Long, Pending>()
    private val samples = ArrayDeque<Sample>()
    private var rejected = 0L
    private var expired = 0L
    private var unmatched = 0L

    @Synchronized fun started(timestampNs: Long, nowNs: Long, trackId: String?) {
        prune(nowNs)
        // Duplicate timestamps cannot safely identify a latency sample. Mark attribution unknown.
        val key = timestampNs / 1_000
        if (pending.containsKey(key)) {
            pending[key] = Pending(nowNs, null, ambiguous = true)
        } else pending[key] = Pending(nowNs, trackId)
        while (pending.size > 128) { pending.remove(pending.keys.first()); expired++ }
    }

    @Synchronized fun completed(timestampNs: Long, nowNs: Long, qp: Int?) {
        val start = pending.remove(timestampNs / 1_000)
        prune(nowNs)
        if (start == null || start.ambiguous || nowNs - start.startedNs !in 0..2_000_000_000L) {
            unmatched++
            return
        }
        samples.addLast(Sample(nowNs, (nowNs - start.startedNs) / 1_000_000.0, qp?.takeIf { it >= 0 }, start.trackId))
        while (samples.size > 120) samples.removeFirst()
    }

    @Synchronized fun rejected(timestampNs: Long) { pending.remove(timestampNs / 1_000); rejected++ }

    @Synchronized fun snapshot(nowNs: Long): List<EncoderTimingStats> {
        prune(nowNs)
        return samples.groupBy { it.trackId }.map { (track, values) ->
            val sorted = values.map { it.elapsedMs }.sorted()
            fun percentile(p: Int) = sorted[(sorted.size * p + 99) / 100 - 1]
            val qp = values.mapNotNull { it.qp }
            EncoderTimingStats(encoderId, codec, track, values.size, values.maxOf { it.finishedNs },
                percentile(50), percentile(95), percentile(99), qp.takeIf { it.isNotEmpty() }?.average(),
                qp.size, rejected, expired, unmatched)
        }
    }

    private fun prune(nowNs: Long) {
        val stale = pending.filterValues { nowNs - it.startedNs > 2_000_000_000L }.keys
        stale.forEach { pending.remove(it); expired++ }
        // Callback/encode threads can acquire the monitor in the opposite order to clock reads.
        samples.removeAll { nowNs - it.finishedNs > 2_000_000_000L }
    }
}
