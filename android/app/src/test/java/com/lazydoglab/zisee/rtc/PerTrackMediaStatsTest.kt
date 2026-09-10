package com.lazydoglab.zisee.rtc

import org.junit.Assert.*
import org.junit.Test

class PerTrackMediaStatsTest {
    private fun stream(track: String, count: Int, loss: Double = 0.01, stamp: Double? = 1_000.0,
                       ssrc: Int = 1, id: String = track) = listOf(
        StatsEntry(id, "outbound-rtp", mapOf("kind" to "video", "mediaSourceId" to "source-$id",
            "ssrc" to ssrc, "framesEncoded" to count * 10, "totalEncodeTime" to count * 0.1,
            "bytesSent" to count * 1_000, "retransmittedBytesSent" to count * 100,
            "remoteId" to "remote-$id", "frameWidth" to 1600, "active" to true)),
        StatsEntry("source-$id", "media-source", mapOf("trackIdentifier" to track)),
        StatsEntry("remote-$id", "remote-inbound-rtp", mapOf("fractionLost" to loss), stamp),
    )
    private fun route(id: String) = listOf(
        StatsEntry("transport", "transport", mapOf("selectedCandidatePairId" to id)),
        StatsEntry(id, "candidate-pair", emptyMap()),
    )

    @Test fun `three sources retain their direction and screen is selected explicitly`() {
        val sampler = MediaStatsSampler()
        fun reports(count: Int) = stream("video_back", count, 0.2) +
            stream("video_front", count, 0.1) + stream("video_screen", count, 0.03)
        sampler.sample(reports(1), 0)
        val stats = sampler.sample(reports(2).reversed(), 1_000, localTrackId = "video_screen")
        assertEquals(3, stats.outboundVideo.size)
        assertEquals(0.03, stats.outboundLoss!!, 0.0001)
        assertEquals(1600, stats.sentWidth)
        assertEquals(0.2, stats.outboundVideo.getValue("video_back").outboundLoss!!, 0.0001)
        val screen = stats.outboundVideo.getValue("video_screen")
        assertEquals(10.0, screen.encodeMs!!, 0.0001)
        assertEquals(8.0, screen.sendKbps!!, 0.0001)
        assertEquals(0.8, screen.retransmittedKbps!!, 0.0001)
        assertEquals(24, stats.sendKbps) // RTX is already part of bytesSent.
    }

    @Test fun `missing requested source and duplicate identity never select another camera`() {
        val reports = stream("video_front", 1) + stream("video_back", 1)
        val missing = MediaStatsSampler().sample(reports, 0, localTrackId = "video_screen")
        assertEquals(0, missing.sentWidth)
        assertNull(missing.outboundLoss)
        val duplicate = MediaStatsSampler().sample(reports + stream("video_front", 1, id = "duplicate"), 0)
        assertFalse(duplicate.outboundVideo.containsKey("video_front"))
        assertEquals(0, duplicate.sentWidth)
        val conflict = stream("video_front", 1).map {
            if (it.type == "outbound-rtp") it.copy(members = it.members + ("trackIdentifier" to "video_screen")) else it
        }
        assertTrue(MediaStatsSampler().sample(conflict, 0).outboundVideo.isEmpty())
    }

    @Test fun `repeated feedback expires and a route switch requires a new report`() {
        val sampler = MediaStatsSampler()
        fun sample(time: Long, pair: String = "a", stamp: Double? = 1_000.0) = sampler.sample(
            route(pair) + stream("video_screen", 1, stamp = stamp), time,
            localTrackId = "video_screen").outboundVideo.getValue("video_screen")
        assertTrue(sample(0).outboundReportFresh)
        assertTrue(sample(3_000).outboundReportFresh)
        assertNull(sample(3_001).outboundLoss)
        assertTrue(sample(4_000, stamp = 2_000.0).outboundReportFresh)
        assertFalse(sample(4_500, pair = "b", stamp = 2_000.0).outboundReportFresh)
        assertTrue(sample(5_000, pair = "b", stamp = 3_000.0).outboundReportFresh)
        assertFalse(sample(6_000, pair = "b", stamp = null).outboundReportFresh)
    }

    @Test fun `reused stats id with new SSRC or source and sampling gaps invalidate deltas`() {
        val sampler = MediaStatsSampler()
        sampler.sample(stream("video_front", 1), 0)
        val changed = sampler.sample(stream("video_front", 10, ssrc = 2), 1_000)
        assertNull(changed.outboundVideo.getValue("video_front").encodeMs)
        val rebound = sampler.sample(stream("video_screen", 20, ssrc = 2, id = "video_front"), 2_000)
        assertNull(rebound.outboundVideo.getValue("video_screen").sendKbps)
        val gap = sampler.sample(stream("video_screen", 30, ssrc = 2, id = "video_front"), 5_001)
        assertNull(gap.outboundVideo.getValue("video_screen").encodeMs)
        val reset = sampler.sample(stream("video_screen", 1, ssrc = 2, id = "video_front"), 6_000)
        assertNull(reset.outboundVideo.getValue("video_screen").sendKbps)
        assertNull(reset.outboundVideo.getValue("video_screen").encodeMs)
    }
}
