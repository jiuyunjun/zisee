package com.lazydoglab.zisee.rtc

data class MediaStats(
    val sampleAvailable: Boolean = false,
    val sampledAtMs: Long = 0,
    val inboundBytes: Long = 0,
    val selectedPairId: String? = null,
    val networkType: String = "unknown", val protocol: String = "unknown",
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
    val audio: com.lazydoglab.zisee.rtc.audio.quality.AudioStats = com.lazydoglab.zisee.rtc.audio.quality.AudioStats(),
    val audioProcessing: com.lazydoglab.zisee.rtc.audio.processing.AudioProcessingStats = com.lazydoglab.zisee.rtc.audio.processing.AudioProcessingStats(),
    val audioDevice: com.lazydoglab.zisee.rtc.audio.AudioDeviceState = com.lazydoglab.zisee.rtc.audio.AudioDeviceState(),
    val audioBandwidth: com.lazydoglab.zisee.rtc.audio.AudioBandwidthMode = com.lazydoglab.zisee.rtc.audio.AudioBandwidthMode.ALL_VIDEO,
)

/** Framework-free input makes direction, missing values and counter resets testable on the JVM. */
data class StatsEntry(val id: String, val type: String, val members: Map<String, Any>, val timestampUs: Double? = null) {
    fun number(key: String): Double? = (members[key] as? Number)?.toDouble()?.takeIf { it.isFinite() }
    val kind: Any? get() = members["kind"] ?: members["mediaType"]
}

class MediaStatsSampler {
    private var previous = emptyMap<String, StatsEntry>()
    private var previousMs: Long? = null

    /**
     * [remoteTrackId] is the id the peer's track actually arrived with. It cannot be assumed to be
     * the wire id this app would have chosen: the peer's msid does not survive every stack, and
     * matching on the expected name instead selected an arbitrary inbound stream. Picking the
     * peer's idle camera that way left framesDecoded at zero for the whole call.
     */
    fun sample(entries: List<StatsEntry>, nowMs: Long, localBack: Boolean = false, remoteTrackId: String? = null): MediaStats {
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
        val inboundVideos = entries.filter { it.type == "inbound-rtp" && it.kind == "video" }
        val inbound = inboundVideos.firstOrNull { remoteTrackId != null && it.members["trackIdentifier"] == remoteTrackId }
            // Falling back to whichever stream is actually decoding keeps the call readable even if
            // the identifier is missing entirely.
            ?: inboundVideos.maxByOrNull { it.number("framesDecoded")?.toLong() ?: 0L }
        val audio = entries.firstOrNull { it.type == "inbound-rtp" && it.kind == "audio" }
        val sentAudio = entries.firstOrNull { it.type == "outbound-rtp" && it.kind == "audio" }
        val remoteAudio = byId[sentAudio?.members?.get("remoteId")]
        val audioSource = byId[sentAudio?.members?.get("mediaSourceId")]
        fun ratio(numerator: Double?, denominator: Double?): Double? =
            if (numerator != null && denominator != null && denominator > 0) (numerator / denominator).takeIf { it in 0.0..1.0 } else null
        val lost = delta(audio, "packetsLost")
        val received = delta(audio, "packetsReceived")
        val outboundVideos = entries.filter { it.type == "outbound-rtp" && it.kind == "video" && it.number("framesEncoded") != null }
        val outbound = outboundVideos.firstOrNull { byId[it.members["mediaSourceId"]]?.members?.get("trackIdentifier") == if (localBack) "video_back" else "video_front" }
            ?: outboundVideos.firstOrNull()
        val remote = byId[outbound?.members?.get("remoteId")]
        val transport = entries.firstOrNull { it.type == "transport" && it.members["selectedCandidatePairId"] != null }
        val pair = byId[transport?.members?.get("selectedCandidatePairId")]
        val localCandidate = byId[pair?.members?.get("localCandidateId")]
        fun candidate(key: String) = (byId[pair?.members?.get(key)]?.members?.get("candidateType") as? String)
            ?.takeIf { it in setOf("host", "srflx", "prflx", "relay") } ?: "—"
        fun long(entry: StatsEntry?, key: String) = entry?.number(key)?.toLong() ?: 0L
        val codec = byId[outbound?.members?.get("codecId")]?.members?.get("mimeType") as? String
        val rtt = pair?.number("currentRoundTripTime")?.takeIf { it >= 0 }?.times(1000)?.toLong()
        val result = MediaStats(
            audio = com.lazydoglab.zisee.rtc.audio.quality.AudioStats(
                sentPackets = sentAudio?.number("packetsSent")?.toLong(), receivedPackets = audio?.number("packetsReceived")?.toLong(),
                lostPackets = audio?.number("packetsLost")?.toLong(),
                sendKbps = delta(sentAudio, "bytesSent")?.let { it * 8 / requireNotNull(elapsed) },
                receiveKbps = delta(audio, "bytesReceived")?.let { it * 8 / requireNotNull(elapsed) },
                outboundLoss = remoteAudio?.number("fractionLost")?.takeIf { it in 0.0..1.0 },
                outboundReportTimestampUs = remoteAudio?.timestampUs,
                inboundLoss = ratio(lost, if (lost != null && received != null) lost + received else null),
                jitterMs = audio?.number("jitter")?.takeIf { it >= 0 }?.times(1000),
                rttMs = remoteAudio?.number("roundTripTime")?.takeIf { it >= 0 }?.times(1000),
                localLevel = audioSource?.number("audioLevel"), remoteLevel = audio?.number("audioLevel"),
                totalEnergy = audio?.number("totalAudioEnergy"),
                concealedSamples = audio?.number("concealedSamples")?.toLong(), concealmentEvents = audio?.number("concealmentEvents")?.toLong(),
                concealmentRatio = ratio(delta(audio, "concealedSamples"), delta(audio, "totalSamplesReceived")),
                jitterBufferMs = average(audio, "jitterBufferDelay", "jitterBufferEmittedCount"),
                insertedSamples = audio?.number("insertedSamplesForDeceleration")?.toLong(),
                removedSamples = audio?.number("removedSamplesForAcceleration")?.toLong(),
                codec = (byId[sentAudio?.members?.get("codecId")]?.members?.get("mimeType") as? String)
                    ?.takeIf { it in setOf("audio/opus", "audio/PCMU", "audio/PCMA", "audio/G722") } ?: "unknown",
            ),
            sampleAvailable = true,
            sampledAtMs = nowMs, inboundBytes = total("inbound-rtp", null, "bytesReceived"),
            selectedPairId = pair?.id,
            networkType = (localCandidate?.members?.get("networkType") as? String)
                ?.takeIf { it in setOf("wifi", "cellular", "ethernet", "vpn", "unknown") } ?: "unknown",
            protocol = (localCandidate?.members?.get("protocol") as? String)
                ?.takeIf { it in setOf("udp", "tcp") } ?: "unknown",
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
