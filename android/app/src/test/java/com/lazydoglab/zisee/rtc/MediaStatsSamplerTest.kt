package com.lazydoglab.zisee.rtc

import org.junit.Assert.*
import org.junit.Test

class MediaStatsSamplerTest {
    private fun reports(count: Int, id: String = "out") = listOf(
        StatsEntry(id, "outbound-rtp", mapOf("kind" to "video", "bytesSent" to count * 1000,
            "framesEncoded" to count * 10, "totalEncodeTime" to count * 0.1,
            "packetsSent" to count * 20, "totalPacketSendDelay" to count * 0.4, "remoteId" to "remote")),
        StatsEntry("in", "inbound-rtp", mapOf("kind" to "video", "bytesReceived" to count * 2000,
            "packetsLost" to 999, "jitterBufferDelay" to count * 0.5, "jitterBufferEmittedCount" to count * 10)),
        StatsEntry("remote", "remote-inbound-rtp", mapOf("fractionLost" to 0.01)),
        StatsEntry("transport", "transport", mapOf("selectedCandidatePairId" to "pair")),
        StatsEntry("pair", "candidate-pair", mapOf("currentRoundTripTime" to 0.05, "availableOutgoingBitrate" to 4_000_000)),
    )

    @Test fun `interval averages do not use lifetime counters or reverse link loss`() {
        val sampler = MediaStatsSampler()
        val first = sampler.sample(reports(100), 0)
        assertNull(first.encodeMs)
        val second = sampler.sample(reports(101), 1_000)
        assertEquals(8L, second.sendKbps)
        assertEquals(16L, second.receiveKbps)
        assertEquals(10.0, second.encodeMs!!, 0.001)
        assertEquals(20.0, second.sendDelayMs!!, 0.001)
        assertEquals(50.0, second.jitterBufferMs!!, 0.001)
        assertEquals(0.01, second.outboundLoss!!, 0.001)
        assertEquals(4_000L, second.availableOutgoingKbps)
        assertEquals(1_000L, second.sampledAtMs)
        assertEquals(202_000L, second.inboundBytes)
        assertEquals("pair", second.selectedPairId)
    }

    @Test fun `same candidate types still expose pair changes without network addresses`() {
        val sampler = MediaStatsSampler()
        fun path(id: String) = listOf(
            StatsEntry("transport", "transport", mapOf("selectedCandidatePairId" to id)),
            StatsEntry(id, "candidate-pair", mapOf("localCandidateId" to "local")),
            StatsEntry("local", "local-candidate", mapOf("candidateType" to "srflx", "networkType" to "cellular", "protocol" to "udp")),
        )
        val before = sampler.sample(path("first"), 0)
        val after = sampler.sample(path("second"), 200)
        assertEquals(before.candidateType, after.candidateType)
        assertNotEquals(before.selectedPairId, after.selectedPairId)
        assertEquals("cellular", after.networkType)
        assertEquals("udp", after.protocol)
    }

    @Test fun `counter resets and changed stream identities cannot produce negative rates`() {
        val sampler = MediaStatsSampler()
        sampler.sample(reports(100), 0)
        val reset = sampler.sample(reports(1), 1_000)
        assertEquals(0L, reset.sendKbps)
        assertNull(reset.encodeMs)
        val replaced = sampler.sample(reports(1000, "replacement"), 2_000)
        assertEquals(0L, replaced.sendKbps)
        assertNull(replaced.encodeMs)
    }

    @Test fun `missing and nonfinite statistics remain unknown`() {
        val sampler = MediaStatsSampler()
        val empty = sampler.sample(emptyList(), 0)
        assertNull(empty.measuredRttMs)
        assertNull(empty.availableOutgoingKbps)
        assertNull(empty.outboundLoss)
        assertNull(empty.freezes)
        val invalid = sampler.sample(listOf(StatsEntry("bad", "outbound-rtp", mapOf("framesEncoded" to Double.NaN))), 1_000)
        assertNull(invalid.encodeMs)
    }

    @Test fun `stalled counters and large sampling gaps do not invent averages`() {
        val sampler = MediaStatsSampler()
        sampler.sample(reports(1), 0)
        assertNull(sampler.sample(reports(1), 1_000).encodeMs)
        assertNull(sampler.sample(reports(2), 10_000).encodeMs)
    }
}

class InboundSelectionTest {
    /** The peer's idle camera decodes nothing; picking it reports a call that never gets a frame. */
    private val streams = listOf(
        StatsEntry("idle", "inbound-rtp", mapOf("kind" to "video",
            "trackIdentifier" to "d7f1", "framesDecoded" to 0, "frameWidth" to 0)),
        StatsEntry("live", "inbound-rtp", mapOf("kind" to "video",
            "trackIdentifier" to "9c2a", "framesDecoded" to 400, "frameWidth" to 1920)),
    )

    @Test fun `the named remote track is selected whatever the peer called it`() {
        val stats = MediaStatsSampler().sample(streams, 0, remoteTrackId = "9c2a")
        assertEquals(400, stats.videoFrames)
        assertEquals(1920, stats.videoWidth)
    }

    @Test fun `an unmatched identifier falls back to the stream that is decoding`() {
        // A peer whose msid did not survive: without the fallback this reported zero frames for
        // the whole call and the waiting indicator never cleared.
        for (id in listOf(null, "video_front", "nonsense")) {
            assertEquals(400, MediaStatsSampler().sample(streams, 0, remoteTrackId = id).videoFrames)
        }
    }
}
