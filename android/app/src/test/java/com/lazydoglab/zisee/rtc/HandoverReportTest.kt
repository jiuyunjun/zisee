package com.lazydoglab.zisee.rtc

import org.junit.Assert.*
import org.junit.Test

class HandoverReportTest {
    private fun sample(bytes: Long, atMs: Long, kbps: Long = 0) =
        MediaStats(sampleAvailable = true, sampledAtMs = atMs, inboundBytes = bytes, sendKbps = kbps)

    @Test fun `the gap is measured from the last frame that arrived, not from detection`() {
        val report = HandoverReport()
        assertNull(report.sample(sample(1_000, 1_000)))
        assertNull(report.sample(sample(2_000, 2_000)))
        // Media stops at 2000; nothing notices until the route is replaced at 2600.
        assertNull(report.sample(sample(2_000, 2_400)))
        report.routeChanged(2_600, VideoQuality.FULL_HD)
        report.restartRequested()
        assertNull(report.sample(sample(2_000, 2_800)))
        report.pairChanged(3_000, VideoQuality.FULL_HD)
        val summary = report.sample(sample(2_400, 3_200, kbps = 700))
        assertNotNull(summary)
        assertEquals(1_200L, requireNotNull(summary).gapMs)
        assertEquals(400L, summary.selectMs)
        assertTrue(summary.restarted)
        assertEquals(700L, summary.sendKbps)
        // One summary per handover.
        assertNull(report.sample(sample(3_000, 3_400)))
    }

    @Test fun `a switch that never interrupts media reports the sampling interval, not a freeze`() {
        val report = HandoverReport()
        report.sample(sample(1_000, 1_000))
        report.sample(sample(2_000, 1_200))
        report.pairChanged(1_300, VideoQuality.HD)
        val summary = report.sample(sample(3_000, 1_400))
        assertEquals(200L, requireNotNull(summary).gapMs)
        assertEquals(0L, summary.selectMs)
        assertFalse(summary.restarted)
    }

    @Test fun `quality restoration is reported once, and only back at the previous step`() {
        val report = HandoverReport()
        report.routeChanged(1_000, VideoQuality.FULL_HD)
        assertNull(report.qualityRestored(VideoQuality.ECONOMY, 2_000))
        assertNull(report.qualityRestored(VideoQuality.HD, 3_000))
        assertEquals(5_000L, report.qualityRestored(VideoQuality.FULL_HD, 6_000))
        assertNull(report.qualityRestored(VideoQuality.FULL_HD, 7_000))
    }

    @Test fun `a handover that never cost any quality reports no restoration`() {
        val report = HandoverReport()
        report.pairChanged(1_000, VideoQuality.HD)
        assertNull(report.qualityRestored(VideoQuality.HD, 1_200))
        assertNull(report.qualityRestored(VideoQuality.HD, 5_000))
        // Only a real drop arms it.
        assertNull(report.qualityRestored(VideoQuality.ECONOMY, 6_000))
        assertEquals(6_000L, report.qualityRestored(VideoQuality.HD, 7_000))
    }

    @Test fun `an unavailable sample neither ends a handover nor moves the gap`() {
        val report = HandoverReport()
        report.sample(sample(800, 900))
        report.sample(sample(1_000, 1_000))
        report.pairChanged(1_500, null)
        assertNull(report.sample(MediaStats(sampleAvailable = false, sampledAtMs = 1_700, inboundBytes = 9_000)))
        assertEquals(900L, requireNotNull(report.sample(sample(1_400, 1_900))).gapMs)
    }
}
