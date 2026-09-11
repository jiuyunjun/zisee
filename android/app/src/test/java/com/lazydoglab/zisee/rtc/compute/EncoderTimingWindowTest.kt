package com.lazydoglab.zisee.rtc.compute

import org.junit.Assert.*
import org.junit.Test

class EncoderTimingWindowTest {
    @Test fun recordsRealCallbackDistributionAndOptionalQp() {
        val window = EncoderTimingWindow(1, "H264")
        for (i in 1..100) {
            window.started(i * 1_000L, 0, "video_front")
            window.completed(i * 1_000L, i * 1_000_000L, if (i <= 50) 20 else null)
        }
        val stats = window.snapshot(100_000_000).single()
        assertEquals(50.0, stats.callbackP50Ms!!, 0.0)
        assertEquals(95.0, stats.callbackP95Ms!!, 0.0)
        assertEquals(99.0, stats.callbackP99Ms!!, 0.0)
        assertEquals(20.0, stats.qpMean!!, 0.0)
        assertEquals(50, stats.qpSamples)
    }

    @Test fun outOfOrderCallbacksKeepCameraAttribution() {
        val window = EncoderTimingWindow(1, "VP8")
        window.started(1_999, 0, "video_front")
        window.started(2_999, 1_000_000, "video_back")
        window.completed(2_000, 5_000_000, 30)
        window.completed(1_000, 9_000_000, 31)
        val stats = window.snapshot(10_000_000).associateBy { it.trackId }
        assertEquals(4.0, stats.getValue("video_back").callbackP95Ms!!, 0.0)
        assertEquals(9.0, stats.getValue("video_front").callbackP95Ms!!, 0.0)
    }

    @Test fun rejectedDuplicatesAndExpiredFramesDoNotFabricateLatency() {
        val window = EncoderTimingWindow(1, "H264")
        window.started(1_000, 0, "video_front"); window.rejected(1_000)
        window.completed(1_000, 1_000_000, 20)
        window.started(2_000, 0, "video_front"); window.started(2_001, 1, "video_back")
        window.completed(2_000, 1_000_000, 20)
        window.started(3_000, 0, "video_front"); window.completed(3_000, 3_000_000_000, 20)
        assertTrue(window.snapshot(3_000_000_000).isEmpty())
    }

    @Test fun staleWindowsReturnNoUsableTelemetry() {
        val window = EncoderTimingWindow(1, "H264")
        window.started(1_000, 0, null); window.completed(1_000, 1_000_000, null)
        assertNull(window.snapshot(2_000_000).single().qpMean)
        assertTrue(window.snapshot(3_000_000_000).isEmpty())
    }

    @Test fun pendingAndSamplesAreBounded() {
        val window = EncoderTimingWindow(1, "H264")
        for (i in 1..300) window.started(i * 1_000L, 0, "video_front")
        for (i in 1..300) window.completed(i * 1_000L, 10_000_000, 20)
        val stats = window.snapshot(10_000_000).single()
        assertEquals(120, stats.samples)
        assertEquals(172, stats.expired)
        assertEquals(172, stats.unmatched)
    }

    @Test fun sourceRegistryRejectsAmbiguousAndStaleTimestamps() {
        val sources = FrameSourceRegistry()
        sources.record(1_999, "video_front", 0)
        assertEquals("video_front", sources.source(1_000, 10))
        sources.record(1_001, "video_back", 10)
        assertNull(sources.source(1_000, 20))
        sources.record(2_000, "video_front", 0)
        assertNull(sources.source(2_000, 3_000_000_000))
        sources.record(3_000, "video_front", 3_000_000_000)
        sources.clear()
        assertNull(sources.source(3_000, 3_000_000_001))
    }

    @Test fun olderClockReadDoesNotEraseNewerCameraEntries() {
        val sources = FrameSourceRegistry()
        sources.record(1_000, "video_front", 100)
        // Simulate an encoder clock read preceding a camera write, but acquiring the lock later.
        assertNull(sources.source(1_000, 90))
        sources.record(2_000, "video_back", 90)
        assertEquals("video_front", sources.source(1_000, 110))
        assertEquals("video_back", sources.source(2_000, 110))
    }

    @Test fun reversedClockOrderKeepsTimestampCollisionAmbiguousUntilExpiry() {
        val sources = FrameSourceRegistry()
        sources.record(1_999, "video_front", 100)
        sources.record(1_001, "video_back", 90)
        assertNull(sources.source(1_000, 110))
        // Do not regress the expiry time to 90 or assign the collision to a third write.
        sources.record(1_002, "video_back", 2_000_000_095)
        assertNull(sources.source(1_000, 2_000_000_100))
        sources.record(1_003, "video_front", 4_000_000_096)
        assertEquals("video_front", sources.source(1_000, 4_000_000_100))
    }
}
