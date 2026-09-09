package com.lazydoglab.zisee.rtc

/**
 * Measures what a handover actually cost, from the samples the call already collects.
 *
 * Tuning a handover needs three numbers that no single stat carries: how long inbound media
 * stopped, how long the new pair took to be selected, and how long the picture stayed below the
 * quality the previous route was holding. Byte counters and durations only; addresses and SDP
 * never enter this class.
 */
class HandoverReport {
    data class Summary(
        val gapMs: Long, val selectMs: Long?, val restarted: Boolean,
        val bweKbps: Long?, val sendKbps: Long,
    ) {
        /** Bounded identifiers only, so the result can go straight into an allowlisted event. */
        fun encode() = "gapMs=$gapMs selectMs=${selectMs ?: -1} restart=$restarted " +
            "bweKbps=${bweKbps ?: -1} sendKbps=$sendKbps"
    }

    private var lastBytes: Long? = null
    private var lastFlowMs: Long? = null
    private var startedMs: Long? = null
    private var gapFromMs: Long? = null
    private var selectedMs: Long? = null
    private var restarted = false
    private var restoreFrom: VideoQuality? = null
    private var restoreStartMs: Long? = null

    /** The operating system replaced the default route. */
    fun routeChanged(nowMs: Long, quality: VideoQuality?) = begin(nowMs, quality)

    /** ICE selected a different candidate pair, with or without a preceding route change. */
    fun pairChanged(nowMs: Long, quality: VideoQuality?) {
        begin(nowMs, quality)
        if (selectedMs == null) selectedMs = nowMs
    }

    fun restartRequested() { restarted = true }

    private fun begin(nowMs: Long, quality: VideoQuality?) {
        if (restoreStartMs == null && quality != null) { restoreFrom = quality; restoreStartMs = nowMs }
        if (startedMs != null) return // One handover at a time; a pair change during it belongs to it.
        startedMs = nowMs
        // The freeze starts at the last frame that arrived, not at the moment anything noticed.
        gapFromMs = lastFlowMs ?: nowMs
        selectedMs = null
        restarted = false
    }

    /** Returns a summary on the first sample that shows inbound media flowing again. */
    fun sample(stats: MediaStats): Summary? {
        if (!stats.sampleAvailable) return null
        val previous = lastBytes
        val flowing = previous != null && stats.inboundBytes > previous
        lastBytes = stats.inboundBytes
        if (flowing) lastFlowMs = stats.sampledAtMs
        val start = startedMs ?: return null
        if (!flowing || stats.sampledAtMs <= start) return null
        val summary = Summary(
            gapMs = stats.sampledAtMs - (gapFromMs ?: start),
            selectMs = selectedMs?.let { it - start },
            restarted = restarted,
            bweKbps = stats.availableOutgoingKbps,
            sendKbps = stats.sendKbps,
        )
        startedMs = null; gapFromMs = null; selectedMs = null; restarted = false
        return summary
    }

    /**
     * Milliseconds from the handover until the picture is back at the quality the previous route
     * was holding, reported once. A restore that never happens simply never reports.
     */
    fun qualityRestored(quality: VideoQuality?, nowMs: Long): Long? {
        val since = restoreStartMs ?: return null
        val target = restoreFrom ?: return null
        if (quality == null || quality.ordinal < target.ordinal) return null
        restoreFrom = null; restoreStartMs = null
        return nowMs - since
    }
}
