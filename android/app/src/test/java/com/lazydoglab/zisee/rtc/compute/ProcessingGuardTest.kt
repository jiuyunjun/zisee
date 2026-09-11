package com.lazydoglab.zisee.rtc.compute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingGuardTest {
    private fun ok(addedMs: Double = 3.0) = GuardSample(addedMs, 1.0, waitMs = 1.5, submitMs = 0.3, queueMs = 0.1)

    private fun ProcessingGuard.shedOnce(nowMs: Long): GuardTransition? {
        record(ok(13.0), nowMs); record(ok(13.0), nowMs)
        return record(ok(13.0), nowMs)
    }

    @Test fun warmupIsExemptButBounded() {
        val guard = ProcessingGuard()
        repeat(3) { assertNull(guard.record(ok(40.0), 0)); assertFalse(guard.lastCounted) }
        assertEquals(ProcessingTier.FULL, guard.tier)
        val shed = ProcessingGuard().record(ok(50.1), 0)!!
        assertEquals("warmup", shed.cause)
        assertEquals(ProcessingTier.NO_AUX, shed.to)
    }

    @Test fun oneTimeCostFrameIsExemptButBounded() {
        val guard = ProcessingGuard(warmupFrames = 0)
        assertNull(guard.record(ok(45.0), 0, oneTimeCost = true))
        assertFalse(guard.lastCounted)
        assertEquals("warmup", guard.record(ok(51.0), 0, oneTimeCost = true)!!.cause)
    }

    @Test fun loneLongFrameIsToleratedLikeTheDeviceCall() {
        val guard = ProcessingGuard(warmupFrames = 0)
        var now = 0L
        repeat(100) { assertNull(guard.record(ok(), now)); now += 33 }
        assertNull(guard.record(ok(27.0), now))
        repeat(100) { now += 33; assertNull(guard.record(ok(), now)) }
        assertEquals(ProcessingTier.FULL, guard.tier)
        assertEquals(GuardState.NORMAL, guard.state)
    }

    @Test fun threeLongFramesWithinFiveSecondsShed() {
        val burst = ProcessingGuard(warmupFrames = 0)
        assertNull(burst.record(ok(25.0), 0)); burst.record(ok(), 33)
        assertNull(burst.record(ok(25.0), 1_000)); burst.record(ok(), 1_033)
        assertEquals("long_burst", burst.record(ok(25.0), 4_000)!!.cause)

        val spread = ProcessingGuard(warmupFrames = 0)
        spread.record(ok(25.0), 0); spread.record(ok(), 33)
        spread.record(ok(25.0), 3_000); spread.record(ok(), 3_033)
        assertNull(spread.record(ok(25.0), 6_000))
    }

    @Test fun threeConsecutiveLateFramesShed() {
        val guard = ProcessingGuard(warmupFrames = 0)
        assertNull(guard.record(ok(13.0), 0)); assertNull(guard.record(ok(13.0), 0))
        assertNull(guard.record(ok(), 0))
        val shed = guard.shedOnce(0)!!
        assertEquals("consecutive", shed.cause)
        assertEquals(ProcessingTier.NO_AUX, guard.tier)
        assertEquals(GuardState.DEGRADED, guard.state)
    }

    @Test fun missRateNeedsAFullMinimumWindow() {
        val guard = ProcessingGuard(warmupFrames = 0)
        for (i in 0 until 59) assertNull(guard.record(if (i % 15 == 0) ok(13.0) else ok(), i * 33L))
        assertEquals("miss_rate", guard.record(ok(), 59 * 33L)!!.cause)

        val tolerable = ProcessingGuard(warmupFrames = 0)
        for (i in 0 until 60) assertNull(tolerable.record(if (i % 20 == 0) ok(13.0) else ok(), i * 33L))
    }

    @Test fun ownGpuTailShedsAsGpuCompute() {
        val guard = ProcessingGuard(warmupFrames = 0)
        val heavy = GuardSample(4.0, 6.0, waitMs = 1.0)
        repeat(59) { assertNull(guard.record(heavy, it * 33L)) }
        val shed = guard.record(heavy, 59 * 33L)!!
        assertEquals("gpu", shed.cause)
        assertEquals(ComputePressure.GPU_COMPUTE, shed.pressure)
    }

    @Test fun pressureNamesTheDominantPhase() {
        val sync = ProcessingGuard(warmupFrames = 0)
        repeat(3) { sync.record(GuardSample(13.0, 1.0, waitMs = 10.0, submitMs = 1.0, queueMs = 1.0), 0) }
        assertEquals(ComputePressure.GPU_SYNC, sync.pressure)

        val queued = ProcessingGuard(warmupFrames = 0)
        repeat(3) { queued.record(GuardSample(13.0, null, waitMs = 1.0, submitMs = 1.0, queueMs = 9.0), 0) }
        assertEquals(ComputePressure.QUEUE, queued.pressure)
    }

    @Test fun climbsOneTierAfterTenHealthySeconds() {
        val guard = ProcessingGuard(warmupFrames = 0)
        guard.shedOnce(0)
        var now = 33L
        while (now < 9_900) { assertNull(guard.record(ok(), now)); now += 33 }
        assertEquals(ProcessingTier.NO_AUX, guard.tier)
        var climb: GuardTransition? = null
        while (climb == null) { now += 33; climb = guard.record(ok(), now) }
        assertTrue(now >= 10_000)
        assertEquals("stable", climb.cause)
        assertEquals(ProcessingTier.FULL, guard.tier)
        assertEquals(ComputePressure.NONE, guard.pressure)
    }

    private fun ProcessingGuard.healthyUntilClimb(from: Long): Long {
        var now = from
        while (true) { now += 33; if (record(ok(), now) != null) return now }
    }

    @Test fun repeatedFailuresOfATierDoubleItsClimbDelay() {
        val guard = ProcessingGuard(warmupFrames = 0)
        var shedAt = 0L
        for (expected in listOf(10_000L, 20_000L, 40_000L)) {
            guard.shedOnce(shedAt)
            assertEquals(expected, guard.climbDelayMs())
            val climbedAt = guard.healthyUntilClimb(shedAt)
            assertTrue(climbedAt - shedAt in expected..expected + 100)
            assertEquals(ProcessingTier.FULL, guard.tier)
            shedAt = climbedAt
        }
        guard.shedOnce(shedAt)
        assertEquals(60_000L, guard.climbDelayMs())
    }

    @Test fun sustainedTierForgetsEarlierFailures() {
        val guard = ProcessingGuard(warmupFrames = 0)
        guard.shedOnce(0)
        var now = guard.healthyUntilClimb(0)
        guard.shedOnce(now)
        assertEquals(20_000L, guard.climbDelayMs())
        now = guard.healthyUntilClimb(now)
        val end = now + 60_000
        while (now < end) { now += 33; assertNull(guard.record(ok(), now)) }
        guard.shedOnce(now)
        assertEquals(10_000L, guard.climbDelayMs())
    }

    @Test fun externalPressureShedsOnlyAuxiliaryWork() {
        val guard = ProcessingGuard(warmupFrames = 0)
        val external = GuardSample(13.0, 1.0, waitMs = 1.0, submitMs = 0.5, queueMs = 0.1, externalMs = 9.0)
        repeat(2) { guard.record(external, 0) }
        val dropAux = guard.record(external, 0)!!
        assertEquals(ProcessingTier.NO_AUX, dropAux.to)
        assertEquals(ComputePressure.EXTERNAL, dropAux.pressure)
        repeat(2) { guard.record(external, 100) }
        val held = guard.record(external, 100)!!
        assertEquals(ProcessingTier.NO_AUX, held.from)
        assertEquals(ProcessingTier.NO_AUX, held.to)
        assertEquals("consecutive_held", held.cause)
        assertEquals(ProcessingTier.NO_AUX, guard.tier)
    }

    @Test fun offProbesOnSparseShadowFramesAndRecovers() {
        val guard = ProcessingGuard(warmupFrames = 0)
        repeat(3) { guard.shedOnce(0) }
        assertEquals(ProcessingTier.OFF, guard.tier)
        assertEquals(GuardState.PROBE, guard.state)
        val actions = List(20) { guard.action() }
        assertEquals(2, actions.count { it == FrameAction.SHADOW })
        assertEquals(FrameAction.SHADOW, actions[0])
        assertEquals(FrameAction.SHADOW, actions[10])
        repeat(29) { assertNull(guard.record(ok(), it * 333L)) }
        val recovered = guard.record(ok(), 29 * 333L)!!
        assertEquals("probe_ok", recovered.cause)
        assertEquals(ProcessingTier.RESIZE_ONLY, guard.tier)
        assertEquals(FrameAction.PROCESS, guard.action())
    }

    @Test fun failedProbeKeepsProbingInsteadOfLocking() {
        val guard = ProcessingGuard(warmupFrames = 0)
        repeat(3) { guard.shedOnce(0) }
        assertNull(guard.shedOnce(1_000))
        assertEquals(ProcessingTier.OFF, guard.tier)
        assertFalse(guard.hardDisabled)
        repeat(29) { assertNull(guard.record(ok(), 2_000 + it * 333L)) }
        assertEquals(ProcessingTier.RESIZE_ONLY, guard.record(ok(), 12_000)!!.to)
    }

    @Test fun onlyGpuFaultsHardDisable() {
        val guard = ProcessingGuard(warmupFrames = 0)
        guard.hardDisable()
        assertEquals(GuardState.HARD_DISABLED, guard.state)
        assertEquals(FrameAction.BYPASS, guard.action())
        assertNull(guard.record(ok(60.0), 0))
    }
}
