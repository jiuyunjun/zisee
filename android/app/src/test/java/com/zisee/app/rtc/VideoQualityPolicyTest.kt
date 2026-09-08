package com.zisee.app.rtc

import org.junit.Assert.*
import org.junit.Test

class VideoQualityPolicyTest {
    @Test fun `quality first starts at device capability without an upgrade timer`() {
        val capable = VideoQualityPolicy(true, preferFullHd = true)
        assertEquals(VideoQuality.FULL_HD, capable.current.quality)
        assertEquals(VideoQuality.HD, VideoQualityPolicy(false, preferFullHd = true).current.quality)
        assertEquals(VideoQuality.ECONOMY, capable.update(MediaStats(thermalStatus = 3), 0).quality)
    }
    @Test fun `quality first still yields to persistent low bandwidth`() {
        val policy = VideoQualityPolicy(true, preferFullHd = true)
        for (time in 0L..3_000L step 1_000) policy.update(MediaStats(availableOutgoingKbps = 1_000), time)
        assertEquals(VideoQuality.HD, policy.current.quality)
    }
    private val good = MediaStats(availableOutgoingKbps = 4_000, outboundLoss = 0.0, measuredRttMs = 30, encodeMs = 10.0)

    private val fast = good.copy(sendDelayMs = 10.0, qualityLimitation = "none")

    @Test fun `strong sender evidence upgrades in five seconds`() {
        val policy = VideoQualityPolicy(true)
        for (time in 0L..4_000L step 1_000) assertEquals(VideoQuality.HD, policy.update(fast, time).quality)
        assertEquals(VideoQuality.FULL_HD, policy.update(fast, 5_000).quality)
    }

    @Test fun `fast evidence must be consecutive and cannot bypass capability`() {
        val policy = VideoQualityPolicy(true)
        val unsupported = VideoQualityPolicy(false)
        for (time in 0L..4_000L step 1_000) policy.update(fast, time)
        assertEquals(VideoQuality.HD, policy.update(fast.copy(sendDelayMs = 80.0), 5_000).quality)
        for (time in 6_000L..10_000L step 1_000) assertEquals(VideoQuality.HD, policy.update(fast, time).quality)
        assertEquals(VideoQuality.FULL_HD, policy.update(fast, 11_000).quality)
        for (time in 0L..20_000L step 1_000) assertEquals(VideoQuality.HD, unsupported.update(fast, time).quality)
    }

    @Test fun `heat recovery cannot use fast upgrade`() {
        val policy = VideoQualityPolicy(true)
        policy.update(fast.copy(thermalStatus = 3), 0)
        for (time in 1_000L..15_000L step 1_000) assertEquals(VideoQuality.ECONOMY, policy.update(fast, time).quality)
        assertEquals(VideoQuality.HD, policy.update(fast, 16_000).quality)
    }

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

class SixtyFrameTest {
    /** Enough uplink for 12Mbps and encode time well inside a 16ms frame budget. */
    private val ample = MediaStats(availableOutgoingKbps = 12_000, outboundLoss = 0.0, measuredRttMs = 20,
        encodeMs = 8.0, sendDelayMs = 8.0, qualityLimitation = "none", thermalStatus = 0)

    private fun settled(supports60: Boolean): VideoQualityPolicy {
        val policy = VideoQualityPolicy(true, preferFullHd = true, supports60 = supports60)
        for (time in 0L..30_000L step 1_000) policy.update(ample, time)
        return policy
    }

    @Test fun `sixty frames is reached only where the camera offers it`() {
        assertEquals(VideoQuality.FULL_HD_60, settled(supports60 = true).current.quality)
        assertEquals(VideoQuality.FULL_HD, settled(supports60 = false).current.quality)
    }

    @Test fun `a call never opens at sixty frames`() {
        // The ceiling is earned from this sender's own evidence, not assumed from the camera.
        assertEquals(VideoQuality.FULL_HD,
            VideoQualityPolicy(true, preferFullHd = true, supports60 = true).current.quality)
    }

    @Test fun `frame rate is given up before resolution`() {
        for (strain in listOf(ample.copy(encodeMs = 24.0), ample.copy(availableOutgoingKbps = 4_000),
            ample.copy(thermalStatus = 1))) {
            val policy = settled(supports60 = true)
            for (time in 31_000L..40_000L step 1_000) policy.update(strain, time)
            assertEquals(VideoQuality.FULL_HD, policy.current.quality)
        }
    }

    @Test fun `the step does not flap between thirty and sixty`() {
        // Upgrading needs 8Mbps and downgrading happens below 6Mbps, so a link sitting between the
        // two holds whichever step it already reached.
        val policy = settled(supports60 = true)
        for (time in 31_000L..60_000L step 1_000) policy.update(ample.copy(availableOutgoingKbps = 7_000), time)
        assertEquals(VideoQuality.FULL_HD_60, policy.current.quality)
    }
}
