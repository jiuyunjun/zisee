package com.zisee.app.rtc.audio.quality

data class AudioStats(
    val sentPackets: Long? = null, val receivedPackets: Long? = null, val lostPackets: Long? = null,
    val sendKbps: Double? = null, val receiveKbps: Double? = null,
    val outboundLoss: Double? = null, val inboundLoss: Double? = null,
    val jitterMs: Double? = null, val rttMs: Double? = null,
    val localLevel: Double? = null, val remoteLevel: Double? = null, val totalEnergy: Double? = null,
    val concealedSamples: Long? = null, val concealmentEvents: Long? = null,
    val concealmentRatio: Double? = null, val jitterBufferMs: Double? = null,
    val insertedSamples: Long? = null, val removedSamples: Long? = null,
    val codec: String = "unknown",
)
