package com.lazydoglab.zisee.rtc.compute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreprocessBudgetTest {
    @Test fun warmupFramesMayExceedFrameLimitButStayBounded() {
        val budget = PreprocessBudget()
        repeat(3) { assertNull(budget.record(40.0, 0)) }
        assertEquals(0, budget.samples)
        assertNull(budget.p95Ms)
        assertEquals(BudgetBreach.WARMUP, PreprocessBudget().record(50.1, 0))
    }

    @Test fun warmupOutliersDoNotPoisonSteadyStateP95() {
        val budget = PreprocessBudget()
        repeat(3) { budget.record(45.0, 0) }
        repeat(30) { assertNull(budget.record(1.0, 0)) }
        assertEquals(1.0, budget.p95Ms!!, 0.0)
    }

    @Test fun steadyStateLimitsAreUnchanged() {
        assertEquals(BudgetBreach.FRAME, PreprocessBudget(warmupFrames = 0).record(20.1, 0))

        val tail = PreprocessBudget(warmupFrames = 0)
        repeat(29) { assertNull(tail.record(5.1, 0)) }
        assertEquals(BudgetBreach.P95, tail.record(5.1, 0))

        val healthy = PreprocessBudget(warmupFrames = 0)
        repeat(60) { assertNull(healthy.record(5.0, 0)) }
    }

    @Test fun windowKeepsOnlyRecentSamples() {
        val budget = PreprocessBudget(warmupFrames = 0)
        repeat(5) { budget.record(19.0, 0) }
        repeat(60) { budget.record(1.0, 0) }
        assertEquals(60, budget.samples)
        assertEquals(1.0, budget.p95Ms!!, 0.0)
    }

    @Test fun breachPausesWithGrowingBackoffThenGivesUp() {
        val budget = PreprocessBudget(warmupFrames = 0)
        var now = 0L
        for ((attempt, pause) in listOf(30_000L, 60_000L, 120_000L).withIndex()) {
            assertEquals(BudgetBreach.FRAME, budget.record(25.0, now))
            assertEquals(attempt + 1, budget.retries)
            assertEquals(pause, budget.lastCooldownMs)
            assertTrue(budget.cooling(now + pause - 1))
            now += pause
            assertFalse(budget.cooling(now))
            assertFalse(budget.exhausted)
        }
        assertEquals(BudgetBreach.FRAME, budget.record(25.0, now))
        assertTrue(budget.exhausted)
        assertNull(budget.lastCooldownMs)
        assertFalse(budget.coolingActive)
    }

    @Test fun retryStartsWithFreshWarmupAndWindow() {
        val budget = PreprocessBudget()
        repeat(3) { budget.record(1.0, 0) }
        repeat(30) { budget.record(6.0, 0) }
        assertTrue(budget.cooling(29_999))
        assertFalse(budget.cooling(30_000))
        assertEquals(0, budget.samples)
        assertNull(budget.p95Ms)
        assertNull(budget.phaseP95())
        assertNull(budget.record(45.0, 30_000))
    }

    @Test fun sizeChangeFrameGetsOneBoundedAllowance() {
        val budget = PreprocessBudget(warmupFrames = 0)
        assertNull(budget.record(45.0, 0, oneTimeCost = true))
        assertEquals(0, budget.samples)
        assertEquals(BudgetBreach.FRAME, budget.record(21.0, 0))
        assertEquals(BudgetBreach.WARMUP, PreprocessBudget(warmupFrames = 0).record(50.1, 0, oneTimeCost = true))
    }

    @Test fun phaseP95IgnoresUnknownGpuTimes() {
        val budget = PreprocessBudget(warmupFrames = 0)
        repeat(20) { budget.record(1.0, 0, FramePhases(100, 200, 300, -1)) }
        assertEquals(FramePhases(100, 200, 300, -1), budget.phaseP95())
        budget.record(1.0, 0, FramePhases(900, 200, 300, 50))
        assertEquals(50L, budget.phaseP95()!!.gpuUs)
        assertEquals(100L, budget.phaseP95()!!.queueUs)
    }
}
