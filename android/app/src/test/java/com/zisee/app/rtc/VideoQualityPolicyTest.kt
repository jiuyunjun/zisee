package com.zisee.app.rtc

import org.junit.Assert.*
import org.junit.Test

class VideoQualityPolicyTest {
    private val good = MediaStats(availableOutgoingKbps = 4_000, outboundLoss = 0.0, measuredRttMs = 30, encodeMs = 10.0)

    @Test fun `full HD needs capability and sustained evidence`() {
        val supported = VideoQualityPolicy(true)
        val unsupported = VideoQualityPolicy(false)
        for (time in 0L..14_000L step 1_000) {
            assertEquals(VideoQuality.HD, supported.update(good, time).quality)
            assertEquals(VideoQuality.HD, unsupported.update(good, time).quality)
        }
        assertEquals(VideoQuality.FULL_HD, supported.update(good, 15_000).quality)
        assertEquals(VideoQuality.HD, unsupported.update(good, 15_000).quality)
    }

    @Test fun `unknown data and isolated spikes cannot force changes`() {
        val policy = VideoQualityPolicy(true)
        for (time in 0L..60_000L step 1_000) {
            val input = if (time % 3_000 == 0L) good.copy(qualityLimitation = "cpu") else MediaStats()
            assertEquals(VideoQuality.HD, policy.update(input, time).quality)
        }
    }

    @Test fun `thermal protection is immediate and recovery is slow`() {
        val policy = VideoQualityPolicy(true)
        assertEquals(QualityReason.THERMAL, policy.update(good.copy(thermalStatus = 3), 0).reason)
        for (time in 1_000L..5_000L step 1_000) {
            assertEquals(VideoQuality.ECONOMY, policy.update(good.copy(thermalStatus = 2), time).quality)
        }
        for (time in 6_000L..20_000L step 1_000) assertEquals(VideoQuality.ECONOMY, policy.update(good, time).quality)
        assertEquals(VideoQuality.HD, policy.update(good, 21_000).quality)
    }

    @Test fun `persistent bandwidth and encoding pressure lower ceilings`() {
        for (bad in listOf(good.copy(availableOutgoingKbps = 300), good.copy(encodeMs = 50.0))) {
            val policy = VideoQualityPolicy(true)
            for (time in 0L..2_000L step 1_000) assertEquals(VideoQuality.HD, policy.update(bad, time).quality)
            assertEquals(VideoQuality.ECONOMY, policy.update(bad, 3_000).quality)
        }
    }

    @Test fun `sampling gap breaks upgrade evidence and low ceiling can recover`() {
        val policy = VideoQualityPolicy(true)
        for (time in 0L..10_000L step 1_000) policy.update(good, time)
        assertEquals(VideoQuality.HD, policy.update(good, 20_000).quality)
        policy.update(good.copy(thermalStatus = 3), 21_000)
        // BWE can be application limited; clean feedback can probe back to HD.
        for (time in 22_000L..37_000L step 1_000) policy.update(good.copy(availableOutgoingKbps = 600), time)
        assertEquals(VideoQuality.HD, policy.current.quality)
    }
}
