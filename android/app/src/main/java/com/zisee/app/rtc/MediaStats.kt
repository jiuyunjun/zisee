package com.zisee.app.rtc

data class MediaStats(
    val sampleAvailable: Boolean = false,
    val videoFrames: Long = 0, val audioReceived: Long = 0, val audioSent: Long = 0,
    val candidateType: String = "—", val rttMs: Long = 0,
    val remoteCandidateType: String = "—",
    val videoWidth: Int = 0, val videoHeight: Int = 0, val videoFps: Int = 0,
    val jitterMs: Long = 0, val packetsLost: Long = 0,
    val receiveKbps: Long = 0, val sendKbps: Long = 0,
    val measuredRttMs: Long? = null, val availableOutgoingKbps: Long? = null,
    val outboundLoss: Double? = null, val encodeMs: Double? = null, val sendDelayMs: Double? = null,
    val jitterBufferMs: Double? = null, val freezes: Long? = null, val freezeSeconds: Double? = null,
    val sentWidth: Int = 0, val sentHeight: Int = 0, val sentFps: Int = 0,
    val codec: String = "unknown", val encoder: String = "unknown", val powerEfficientEncoder: Boolean? = null,
    val qualityLimitation: String = "unknown", val thermalStatus: Int? = null,
    val quality: VideoQuality = VideoQuality.HD,
)

/** Framework-free input makes direction, missing values and counter resets testable on the JVM. */
data class StatsEntry(val id: String, val type: String, val members: Map<String, Any>) {
    fun number(key: String): Double? = (members[key] as? Number)?.toDouble()?.takeIf { it.isFinite() }
    val kind: Any? get() = members["kind"] ?: members["mediaType"]
}

class MediaStatsSampler {
    private var previous = emptyMap<String, StatsEntry>()
    private var previousMs: Long? = null

    fun sample(entries: List<StatsEntry>, nowMs: Long): MediaStats {
        val byId = entries.associateBy { it.id }
        val elapsed = previousMs?.let { nowMs - it }?.takeIf { it in 1..5_000 }
        fun delta(entry: StatsEntry?, key: String): Double? {
            if (entry == null || elapsed == null) return null
            val before = previous[entry.id]?.number(key) ?: return null
            val after = entry.number(key) ?: return null
            return (after - before).takeIf { it >= 0 }
        }
        fun average(entry: StatsEntry?, duration: String, count: String): Double? {
            val samples = delta(entry, count)?.takeIf { it > 0 } ?: return null
            return delta(entry, duration)?.let { it * 1000 / samples }
        }
        fun total(type: String, kind: String?, key: String) = entries.filter {
            it.type == type && (kind == null || it.kind == kind)
        }.sumOf { it.number(key)?.toLong() ?: 0 }
        fun rate(type: String): Long = if (elapsed == null) 0 else entries.filter { it.type == type }
            .sumOf { delta(it, if (type == "inbound-rtp") "bytesReceived" else "bytesSent") ?: 0.0 }
            .times(8).div(elapsed).toLong()
        val inbound = entries.firstOrNull { it.type == "inbound-rtp" && it.kind == "video" }
        val audio = entries.firstOrNull { it.type == "inbound-rtp" && it.kind == "audio" }
        val outbound = entries.firstOrNull { it.type == "outbound-rtp" && it.kind == "video" && it.number("framesEncoded") != null }
        val remote = byId[outbound?.members?.get("remoteId")]
        val transport = entries.firstOrNull { it.type == "transport" && it.members["selectedCandidatePairId"] != null }
        val pair = byId[transport?.members?.get("selectedCandidatePairId")]
        fun candidate(key: String) = (byId[pair?.members?.get(key)]?.members?.get("candidateType") as? String)
            ?.takeIf { it in setOf("host", "srflx", "prflx", "relay") } ?: "—"
        fun long(entry: StatsEntry?, key: String) = entry?.number(key)?.toLong() ?: 0L
        val codec = byId[outbound?.members?.get("codecId")]?.members?.get("mimeType") as? String
        val rtt = pair?.number("currentRoundTripTime")?.takeIf { it >= 0 }?.times(1000)?.toLong()
        val result = MediaStats(
            sampleAvailable = true,
            videoFrames = long(inbound, "framesDecoded"), audioReceived = total("inbound-rtp", "audio", "bytesReceived"),
            audioSent = total("outbound-rtp", "audio", "bytesSent"), candidateType = candidate("localCandidateId"),
            remoteCandidateType = candidate("remoteCandidateId"), rttMs = rtt ?: 0, measuredRttMs = rtt,
            videoWidth = long(inbound, "frameWidth").toInt(), videoHeight = long(inbound, "frameHeight").toInt(),
            videoFps = long(inbound, "framesPerSecond").toInt(),
            jitterMs = ((audio ?: inbound)?.number("jitter")?.times(1000))?.toLong() ?: 0,
            packetsLost = total("inbound-rtp", null, "packetsLost"), receiveKbps = rate("inbound-rtp"), sendKbps = rate("outbound-rtp"),
            availableOutgoingKbps = pair?.number("availableOutgoingBitrate")?.takeIf { it > 0 }?.div(1000)?.toLong(),
            outboundLoss = remote?.number("fractionLost")?.takeIf { it in 0.0..1.0 },
            encodeMs = average(outbound, "totalEncodeTime", "framesEncoded"),
            sendDelayMs = average(outbound, "totalPacketSendDelay", "packetsSent"),
            jitterBufferMs = average(inbound, "jitterBufferDelay", "jitterBufferEmittedCount"),
            freezes = inbound?.number("freezeCount")?.toLong(), freezeSeconds = inbound?.number("totalFreezesDuration"),
            sentWidth = long(outbound, "frameWidth").toInt(), sentHeight = long(outbound, "frameHeight").toInt(),
            sentFps = long(outbound, "framesPerSecond").toInt(),
            codec = codec?.takeIf { it in setOf("video/H264", "video/VP8", "video/VP9", "video/AV1") } ?: "unknown",
            encoder = (outbound?.members?.get("encoderImplementation") as? String)?.take(80) ?: "unknown",
            powerEfficientEncoder = outbound?.members?.get("powerEfficientEncoder") as? Boolean,
            qualityLimitation = (outbound?.members?.get("qualityLimitationReason") as? String)
                ?.takeIf { it in setOf("none", "cpu", "bandwidth", "other") } ?: "unknown",
        )
        previous = byId; previousMs = nowMs
        return result
    }
}
