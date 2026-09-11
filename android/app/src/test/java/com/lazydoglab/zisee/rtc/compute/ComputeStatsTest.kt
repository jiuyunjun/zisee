package com.lazydoglab.zisee.rtc.compute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComputeStatsTest {
    @Test fun summaryReportsMedianAndTailPerPhase() {
        val window = ComputeStatsWindow()
        repeat(19) { window.add(FramePhases(10, 20, 30, 40, 100)) }
        window.add(FramePhases(90, 80, 70, 60, 900))
        val summary = window.summary()!!
        assertEquals(20, summary.samples)
        assertEquals(FramePhases(10, 20, 30, 40, 100), summary.p50)
        assertEquals(FramePhases(10, 20, 30, 40, 100), summary.p95)
        window.add(FramePhases(90, 80, 70, 60, 900))
        assertEquals(900L, window.summary()!!.p95.addedUs)
    }

    @Test fun unknownGpuTimesAreIgnoredAndCapacityIsBounded() {
        val window = ComputeStatsWindow(capacity = 3)
        repeat(5) { window.add(FramePhases(1, 1, 1, -1, 1)) }
        assertEquals(3, window.size)
        assertEquals(-1L, window.summary()!!.p95.gpuUs)
        window.add(FramePhases(1, 1, 1, 7, 1))
        assertEquals(7L, window.summary()!!.p95.gpuUs)
        window.clear()
        assertNull(window.summary())
    }

    @Test fun latencyWindowSummarizesCountAndTail() {
        val window = LatencyWindow(capacity = 4)
        assertEquals("0/-1", window.summary())
        listOf(5L, 1L, 3L, 2L, 4L).forEach(window::add)
        assertEquals("4/4", window.summary()) // Oldest value (5) was evicted.
    }
}
