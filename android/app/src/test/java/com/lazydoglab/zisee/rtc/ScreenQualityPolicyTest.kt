package com.lazydoglab.zisee.rtc

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenQualityPolicyTest {
    private fun stats(now: Long, bandwidth: Long? = 4_000, thermal: Int? = 0,
        encodeMs: Double? = 20.0, delayMs: Double? = 20.0, loss: Double? = 0.01,
        fresh: Boolean = true, limitation: String = "none") = MediaStats(
        sampleAvailable = true, sampledAtMs = now, availableOutgoingKbps = bandwidth,
        thermalStatus = thermal, outboundVideo = mapOf("video_screen" to VideoSendStats(
            "video_screen", "out", now, true, 1600, 896, 10.0, 2_000.0, 0.0,
            encodeMs, delayMs, loss, fresh, limitation)))

    @Test fun textModeDropsFrameRateBeforeResolution() {
        val policy = ScreenQualityPolicy()
        assertEquals(ScreenQuality.TEXT_NORMAL, policy.update(stats(0, bandwidth = 1_200), 0))
        assertEquals(ScreenQuality.TEXT_CONSTRAINED, policy.update(stats(3_000, bandwidth = 1_200), 3_000))
        assertEquals(1600, policy.current.longEdge)
        assertEquals(5, policy.current.fps)
    }

    @Test fun motionModeDropsSizeAlongWithFrameRate() {
        val policy = ScreenQualityPolicy()
        policy.setMode(ScreenContentMode.MOTION, -10_000)
        assertEquals(ScreenQuality.MOTION_NORMAL, policy.current)
        policy.update(stats(0, bandwidth = 1_200), 0)
        assertEquals(ScreenQuality.MOTION_CONSTRAINED, policy.update(stats(2_000, bandwidth = 1_200), 2_000))
        assertEquals(960, policy.current.longEdge)
        assertEquals(15, policy.current.fps)
    }

    @Test fun modeSwitchResetsLadderAndSurvivesOnlyForCurrentShare() {
        val policy = ScreenQualityPolicy()
        policy.update(stats(0, bandwidth = 1_200), 0)
        policy.update(stats(3_000, bandwidth = 1_200), 3_000)
        assertEquals(ScreenQuality.TEXT_CONSTRAINED, policy.current)
        policy.setMode(ScreenContentMode.MOTION, 3_500)
        assertEquals(ScreenQuality.MOTION_NORMAL, policy.current)
        assertEquals(ScreenQualityReason.MODE, policy.lastReason)
        // Severe conditions still bypass wait and cooldown right after a mode switch.
        assertEquals(ScreenQuality.MOTION_CONSTRAINED, policy.update(stats(3_600, thermal = 3), 3_600))
    }

    @Test fun severeThermalProtectionIsImmediateAndModerateHeatNeverUpgrades() {
        val policy = ScreenQualityPolicy()
        assertEquals(ScreenQuality.TEXT_LOW, policy.update(stats(0, thermal = 3), 0))
        for (time in 1_000L..20_000L step 1_000L)
            assertEquals(ScreenQuality.TEXT_LOW, policy.update(stats(time, thermal = 2), time))
    }

    @Test fun unknownOrStaleEvidenceCannotUpgrade() {
        val policy = ScreenQualityPolicy()
        policy.update(stats(0, thermal = 3), 0)
        for (time in 1_000L..30_000L step 1_000L) {
            val unknown = stats(time, bandwidth = null, encodeMs = null, delayMs = null,
                loss = null, fresh = false, limitation = "unknown")
            assertEquals(ScreenQuality.TEXT_LOW, policy.update(unknown, time))
        }
    }

    @Test fun recoveryRequiresContinuousFreshPerScreenEvidence() {
        val policy = ScreenQualityPolicy()
        policy.update(stats(0, thermal = 3), 0)
        for (time in 1_000L..15_000L step 1_000L) policy.update(stats(time), time)
        assertEquals(ScreenQuality.TEXT_CONSTRAINED, policy.current)
        policy.update(stats(16_000, fresh = false), 16_000)
        for (time in 17_000L..31_000L step 1_000L) policy.update(stats(time), time)
        assertEquals(ScreenQuality.TEXT_CONSTRAINED, policy.current)
        assertEquals(ScreenQuality.TEXT_NORMAL, policy.update(stats(32_000), 32_000))
        assertEquals(ScreenQualityReason.RECOVERY, policy.lastReason)
    }

    @Test fun twoSecondDowngradeVsOneSecondBlipDoesNotDowngrade() {
        val policy = ScreenQualityPolicy()
        policy.update(stats(0, bandwidth = 4_000), 0)
        policy.update(stats(1_000, bandwidth = 1_200), 1_000)
        // A single 1 s sample of moderate pressure must not have downgraded yet.
        assertEquals(ScreenQuality.TEXT_NORMAL,
            policy.update(stats(1_500, bandwidth = 4_000), 1_500).let { policy.current })
        val policy2 = ScreenQualityPolicy()
        policy2.update(stats(1_000, bandwidth = 1_200), 1_000)
        policy2.update(stats(2_000, bandwidth = 1_200), 2_000)
        assertEquals(ScreenQuality.TEXT_NORMAL, policy2.current)
        assertEquals(ScreenQuality.TEXT_CONSTRAINED, policy2.update(stats(3_000, bandwidth = 1_200), 3_000))
    }

    @Test fun formatChangesStayAtLeastFiveSecondsApartUnlessSevere() {
        val policy = ScreenQualityPolicy()
        policy.update(stats(0, bandwidth = 1_200), 0)
        assertEquals(ScreenQuality.TEXT_CONSTRAINED, policy.update(stats(2_000, bandwidth = 1_200), 2_000))
        // Weak (non-severe) evidence satisfies its own 2 s wait well inside the 5 s change cooldown.
        policy.update(stats(2_500, bandwidth = 800), 2_500)
        policy.update(stats(4_500, bandwidth = 800), 4_500)
        assertEquals(ScreenQuality.TEXT_CONSTRAINED, policy.update(stats(6_000, bandwidth = 800), 6_000))
        assertEquals(ScreenQuality.TEXT_LOW, policy.update(stats(7_000, bandwidth = 800), 7_000))
    }
}
