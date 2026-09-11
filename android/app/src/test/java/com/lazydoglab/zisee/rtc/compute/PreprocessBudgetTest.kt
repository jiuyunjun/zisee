package com.lazydoglab.zisee.rtc.compute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreprocessBudgetTest {
    @Test fun warmupFramesMayExceedFrameLimitButStayBounded() {
        val budget = PreprocessBudget()
        repeat(3) { assertNull(budget.record(40.0)) }
        assertEquals(0, budget.samples)
        assertNull(budget.p95Ms)
        assertEquals(BudgetBreach.WARMUP, PreprocessBudget().record(50.1))
    }

    @Test fun warmupOutliersDoNotPoisonSteadyStateP95() {
        val budget = PreprocessBudget()
        repeat(3) { budget.record(45.0) }
        repeat(30) { assertNull(budget.record(1.0)) }
        assertEquals(1.0, budget.p95Ms!!, 0.0)
    }

    @Test fun steadyStateLimitsAreUnchanged() {
        val frame = PreprocessBudget(warmupFrames = 0)
        assertEquals(BudgetBreach.FRAME, frame.record(20.1))

        val tail = PreprocessBudget(warmupFrames = 0)
        repeat(29) { assertNull(tail.record(5.1)) }
        assertEquals(BudgetBreach.P95, tail.record(5.1))

        val healthy = PreprocessBudget(warmupFrames = 0)
        repeat(60) { assertNull(healthy.record(5.0)) }
    }

    @Test fun windowKeepsOnlyRecentSamples() {
        val budget = PreprocessBudget(warmupFrames = 0)
        repeat(5) { budget.record(19.0) }
        repeat(60) { budget.record(1.0) }
        assertEquals(60, budget.samples)
        assertEquals(1.0, budget.p95Ms!!, 0.0)
    }
}
