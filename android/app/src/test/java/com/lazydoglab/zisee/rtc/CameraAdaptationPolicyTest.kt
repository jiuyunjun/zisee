package com.lazydoglab.zisee.rtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraAdaptationPolicyTest {
    @Test fun `sender capped bandwidth can recover without a local route event`() {
        for (nativeLimit in listOf("none", "bandwidth")) {
        val policy = CameraAdaptationPolicy(supportsFullHd = true)
        for (t in 0L..10_000L step 1_000) policy.update(input(t, 100))
        assertEquals(CameraTier.C0, policy.lastPlan.main.tier)
        // Model a healthy route whose estimate cannot grow beyond the application's sender cap.
        for (t in 11_000L..120_000L step 1_000) {
            policy.update(input(t, policy.lastPlan.main.maxBitrateBps / 1_000L,
                main = healthyMain.copy(qualityLimitation = nativeLimit)))
        }
        assertEquals(CameraTier.C3, policy.lastPlan.main.tier)
        }
    }

    @Test fun `unsuccessful ceiling probe expires without forcing higher resolution`() {
        val policy = CameraAdaptationPolicy(supportsFullHd = true)
        for (t in 0L..10_000L step 1_000) policy.update(input(t, 100))
        var probed = false
        for (t in 11_000L..32_000L step 1_000) {
            val plan = policy.update(input(t, 350))
            probed = probed || plan.main.maxBitrateBps > CameraTier.C0.maxKbps * 1_000
            assertEquals(CameraTier.C0, plan.main.tier)
        }
        assertTrue(probed)
        assertEquals(CameraTier.C0.maxKbps * 1_000, policy.lastPlan.main.maxBitrateBps)
    }

    @Test fun `recovery probe never overrides loss thermal or thumbnail limits`() {
        for (blocked in listOf("loss", "thermal", "thumbnail", "stale")) {
            val policy = CameraAdaptationPolicy(supportsFullHd = true)
            for (t in 0L..10_000L step 1_000) policy.update(input(t, 100))
            for (t in 11_000L..60_000L step 1_000) {
                val observation = when (blocked) {
                    "loss" -> healthyMain.copy(outboundLoss = 0.1)
                    "stale" -> healthyMain.copy(outboundReportFresh = false)
                    else -> healthyMain
                }
                val plan = policy.update(input(t, 350, main = observation,
                    thermal = if (blocked == "thermal") 3 else 0, mainViewedSmall = blocked == "thumbnail"))
                assertEquals(plan.main.tier.maxKbps * 1_000, plan.main.maxBitrateBps)
            }
        }
    }
    private val healthyMain = CameraTrackObservation(encodeMs = 5.0, sendDelayMs = 10.0, outboundLoss = 0.0,
        outboundReportFresh = true, qualityLimitation = "none")

    private fun input(now: Long, bwe: Long?, main: CameraTrackObservation = healthyMain, aux: CameraTrackObservation? = null,
        thermal: Int? = 0, mainRepairKbps: Double? = null, auxRepairKbps: Double? = null, mainViewedSmall: Boolean = false) =
        CameraAdaptationInput(now, bwe, thermal, main.copy(retransmittedKbps = mainRepairKbps),
            aux?.copy(retransmittedKbps = auxRepairKbps), mainViewedSmall)

    @Test fun `switch parses known values and defaults unknown to OFF`() {
        assertEquals(VideoAdaptationMode.OFF, VideoAdaptationMode.parse(null))
        assertEquals(VideoAdaptationMode.OFF, VideoAdaptationMode.parse(""))
        assertEquals(VideoAdaptationMode.OFF, VideoAdaptationMode.parse("BOGUS"))
        assertEquals(VideoAdaptationMode.OFF, VideoAdaptationMode.parse("OFF"))
        assertEquals(VideoAdaptationMode.SHADOW, VideoAdaptationMode.parse("SHADOW"))
        assertEquals(VideoAdaptationMode.ACTIVE, VideoAdaptationMode.parse("ACTIVE"))
    }

    @Test fun `budget example B1000 h08 yields a 620kbps pool and lands on C1`() {
        val policy = CameraAdaptationPolicy(supportsFullHd = false)
        // B=1000, measured repair=100, audio 64, data 16. The very first sample already computes a
        // downgrade (h flips to the constrained 0.80 from that sample on), so two seconds of this
        // same evidence is enough for the tier itself to settle at C1 with a 620kbps pool.
        var plan = policy.lastPlan
        for (t in 0L..2_000L step 1_000) plan = policy.update(input(t, bwe = 1_000, mainRepairKbps = 100.0))
        assertEquals(CameraTier.C1, plan.main.tier)
        assertEquals(620L, plan.poolKbps)
    }

    @Test fun `zero pool still floors at C0`() {
        val policy = CameraAdaptationPolicy(supportsFullHd = false)
        var plan = policy.lastPlan
        for (t in 0L..8_000L step 1_000) plan = policy.update(input(t, bwe = 50))
        assertEquals(CameraTier.C0, plan.main.tier)
        assertTrue(plan.main.maxBitrateBps > 0)
    }

    @Test fun `dual camera caps aux at twenty percent or 450kbps and pauses it below the floor`() {
        val policy = CameraAdaptationPolicy(supportsFullHd = true)
        var plan = policy.lastPlan
        // Large pool: 20% would be well above 450, so the hard 450kbps cap binds.
        for (t in 0L..10_000L step 1_000) plan = policy.update(input(t, bwe = 6_000, aux = healthyMain))
        assertTrue(plan.aux!!.maxBitrateBps <= 450_000)
        // Small pool: aux's 20% share falls under C0's 150kbps floor, so it is pinned there and flagged.
        val tiny = CameraAdaptationPolicy(supportsFullHd = true)
        var tinyPlan = tiny.lastPlan
        for (t in 0L..3_000L step 1_000) tinyPlan = tiny.update(input(t, bwe = 200, aux = healthyMain))
        assertTrue(CameraAdaptationLimit.AUX_PAUSED in tinyPlan.limits)
        assertEquals(CameraTier.C0, tinyPlan.aux!!.tier)
    }

    @Test fun `main ceiling is at least tier max and never clamped to its share`() {
        // §5.2: a share-sized cap on main would suppress the very BWE signal the budget reacts to
        // (a low early estimate -> a low cap -> the estimate never gets a chance to climb). Native
        // congestion control owns the actual send rate below the tier ceiling.
        val policy = CameraAdaptationPolicy(supportsFullHd = true)
        var plan = policy.lastPlan
        for (t in 0L..10_000L step 1_000) plan = policy.update(input(t, bwe = 3_000, aux = healthyMain))
        assertTrue(plan.main.maxBitrateBps >= plan.main.tier.maxKbps * 1_000)

        val tight = CameraAdaptationPolicy(supportsFullHd = false)
        var tightPlan = tight.lastPlan
        for (t in 0L..8_000L step 1_000) tightPlan = tight.update(input(t, bwe = 500))
        assertEquals(tightPlan.main.tier.maxKbps * 1_000, tightPlan.main.maxBitrateBps)
    }

    @Test fun `aux ceiling is a hard allocation clamped to its own share`() {
        val policy = CameraAdaptationPolicy(supportsFullHd = true)
        var plan = policy.lastPlan
        for (t in 0L..10_000L step 1_000) plan = policy.update(input(t, bwe = 3_000, aux = healthyMain))
        assertTrue(plan.aux!!.maxBitrateBps <= 450_000)
        assertTrue(plan.aux!!.maxBitrateBps <= plan.poolKbps * 1_000)
    }

    @Test fun `a one second blip does not downgrade but two continuous seconds does`() {
        val policy = CameraAdaptationPolicy(supportsFullHd = false)
        val start = policy.lastPlan.main.tier
        policy.update(input(0, bwe = 6_000))
        val afterBlip = policy.update(input(1_000, bwe = 100))
        assertEquals(start, afterBlip.main.tier)
        policy.update(input(2_000, bwe = 100))
        val afterTwoSeconds = policy.update(input(3_000, bwe = 100))
        assertNotEquals(start, afterTwoSeconds.main.tier)
    }

    @Test fun `severe thermal drops immediately to C0`() {
        val policy = CameraAdaptationPolicy(supportsFullHd = true)
        val plan = policy.update(input(0, bwe = 6_000, thermal = 3))
        assertEquals(CameraTier.C0, plan.main.tier)
        assertEquals(CameraAdaptationReason.THERMAL, plan.reason)
    }

    @Test fun `cpu limitation also drops immediately`() {
        val policy = CameraAdaptationPolicy(supportsFullHd = true)
        val cpu = healthyMain.copy(qualityLimitation = "cpu")
        val plan = policy.update(input(0, bwe = 6_000, main = cpu))
        assertEquals(CameraTier.C0, plan.main.tier)
    }

    @Test fun `upgrade needs eight seconds of fresh healthy evidence`() {
        val policy = CameraAdaptationPolicy(supportsFullHd = true)
        // Settle low first (cascades one tier at a time with a 5s cooldown between steps).
        for (t in 0L..8_000L step 1_000) policy.update(input(t, bwe = 100))
        assertEquals(CameraTier.C0, policy.lastPlan.main.tier)
        // A few seconds of healthy high-bandwidth evidence is well short of the 8s window.
        var plan = policy.lastPlan
        for (t in 9_000L..12_000L step 1_000) plan = policy.update(input(t, bwe = 6_000))
        assertEquals(CameraTier.C0, plan.main.tier)
        // Sustained well past 8s (plus cooldown) does clear exactly one upgrade step.
        for (t in 13_000L..20_000L step 1_000) plan = policy.update(input(t, bwe = 6_000))
        assertTrue(plan.main.tier.ordinal > CameraTier.C0.ordinal)
    }

    @Test fun `thermal caused downgrade needs fifteen seconds to recover instead of eight`() {
        val policy = CameraAdaptationPolicy(supportsFullHd = true)
        policy.update(input(0, bwe = 6_000, thermal = 3))
        assertEquals(CameraTier.C0, policy.lastPlan.main.tier)
        var plan = policy.lastPlan
        for (t in 1_000L..8_000L step 1_000) plan = policy.update(input(t, bwe = 6_000, thermal = 0))
        // Eight seconds is not enough after a thermal drop.
        assertEquals(CameraTier.C0, plan.main.tier)
        for (t in 9_000L..18_000L step 1_000) plan = policy.update(input(t, bwe = 6_000, thermal = 0))
        assertTrue(plan.main.tier.ordinal > CameraTier.C0.ordinal)
    }

    @Test fun `missing bandwidth holds the current tier`() {
        val policy = CameraAdaptationPolicy(supportsFullHd = false)
        for (t in 0L..3_000L step 1_000) policy.update(input(t, bwe = 100))
        val held = policy.lastPlan.main.tier
        val plan = policy.update(input(4_000, bwe = null))
        assertEquals(held, plan.main.tier)
        assertEquals(CameraAdaptationReason.STALE_STATS, plan.reason)
    }

    @Test fun `missing bandwidth still forces C0 immediately under severe thermal`() {
        val policy = CameraAdaptationPolicy(supportsFullHd = true)
        val rev0 = policy.lastPlan.revision
        val plan = policy.update(input(0, bwe = null, thermal = 3))
        assertEquals(CameraTier.C0, plan.main.tier)
        assertEquals(CameraAdaptationReason.THERMAL, plan.reason)
        assertTrue(plan.revision > rev0)
    }

    @Test fun `missing bandwidth still forces C0 immediately under cpu limitation`() {
        val policy = CameraAdaptationPolicy(supportsFullHd = true)
        val cpu = healthyMain.copy(qualityLimitation = "cpu")
        val plan = policy.update(input(0, bwe = null, main = cpu))
        assertEquals(CameraTier.C0, plan.main.tier)
    }

    @Test fun `C4 needs a measured encode sample, not merely a missing one`() {
        val policy = CameraAdaptationPolicy(supportsFullHd = true, preferFullHd = true, supports60 = true)
        var plan = policy.lastPlan
        // Ample budget the whole time, but encodeMs is never reported.
        for (t in 0L..40_000L step 1_000) plan = policy.update(input(t, bwe = 12_000,
            main = healthyMain.copy(encodeMs = null, sendDelayMs = 8.0)))
        assertEquals(CameraTier.C3, plan.main.tier)
    }

    @Test fun `C3 and C4 need capability and ample budget, sixty drops before thirty on trouble`() {
        val capable = CameraAdaptationPolicy(supportsFullHd = true, preferFullHd = true, supports60 = true)
        var plan = capable.lastPlan
        for (t in 0L..40_000L step 1_000) plan = capable.update(input(t, bwe = 12_000,
            main = healthyMain.copy(encodeMs = 8.0, sendDelayMs = 8.0)))
        assertEquals(CameraTier.C4, plan.main.tier)
        // Encode headroom disappears: fps must give way before the plan falls out of full HD entirely.
        var trouble = plan
        for (t in 41_000L..48_000L step 1_000) trouble = capable.update(input(t, bwe = 12_000,
            main = healthyMain.copy(encodeMs = 20.0, sendDelayMs = 8.0)))
        assertEquals(CameraTier.C3, trouble.main.tier)

        val incapable = CameraAdaptationPolicy(supportsFullHd = false)
        var incapablePlan = incapable.lastPlan
        for (t in 0L..40_000L step 1_000) incapablePlan = incapable.update(input(t, bwe = 12_000))
        assertTrue(incapablePlan.main.tier.ordinal <= CameraTier.C2.ordinal)
    }

    @Test fun `route change drops to C1 then restores at two seconds per step`() {
        val policy = CameraAdaptationPolicy(supportsFullHd = true, preferFullHd = true)
        var plan = policy.lastPlan
        for (t in 0L..40_000L step 1_000) plan = policy.update(input(t, bwe = 12_000,
            main = healthyMain.copy(encodeMs = 8.0, sendDelayMs = 8.0)))
        val before = plan.main.tier
        assertTrue(before.ordinal >= CameraTier.C3.ordinal)
        policy.routeChanged(41_000)
        assertEquals(CameraTier.C1, policy.lastPlan.main.tier)
        var restored = policy.lastPlan
        for (t in 42_000L..60_000L step 1_000) restored = policy.update(input(t, bwe = 12_000,
            main = healthyMain.copy(encodeMs = 8.0, sendDelayMs = 8.0)))
        assertEquals(before, restored.main.tier)
    }

    @Test fun `revision increments only when the plan actually changes`() {
        val policy = CameraAdaptationPolicy(supportsFullHd = false)
        val rev0 = policy.lastPlan.revision
        val first = policy.update(input(0, bwe = 6_000))
        assertTrue(first.revision > rev0) // The placeholder startup plan is replaced by a real one.
        val second = policy.update(input(1_000, bwe = 6_000))
        assertEquals(first.revision, second.revision) // Same evidence again: no new plan.
        for (t in 2_000L..8_000L step 1_000) policy.update(input(t, bwe = 100))
        assertTrue(policy.lastPlan.revision > second.revision)
    }

    @Test fun `a ceiling change under fifteen percent in the same tier does not emit a new plan`() {
        val policy = CameraAdaptationPolicy(supportsFullHd = false)
        for (t in 0L..3_000L step 1_000) policy.update(input(t, bwe = 6_000))
        val settled = policy.lastPlan
        val nudged = policy.update(input(4_000, bwe = (6_000 * 1.05).toLong()))
        assertEquals(settled.revision, nudged.revision)
        assertEquals(settled.main.maxBitrateBps, nudged.main.maxBitrateBps)
    }
}
