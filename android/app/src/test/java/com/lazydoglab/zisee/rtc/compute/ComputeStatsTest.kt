package com.lazydoglab.zisee.rtc.compute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComputeStatsTest {
    private fun phases(us: Long, gpu: Long = us, added: Long = us) = FramePhases(us, us, us, us, us, gpu, added)

    @Test fun summaryReportsMedianAndTailPerPhase() {
        val window = ComputeStatsWindow()
        repeat(19) { window.add(phases(10, added = 100)) }
        window.add(phases(90, added = 900))
        val summary = window.summary()!!
        assertEquals(20, summary.samples)
        assertEquals(phases(10, added = 100), summary.p50)
        assertEquals(phases(10, added = 100), summary.p95)
        window.add(phases(90, added = 900))
        assertEquals(900L, window.summary()!!.p95.addedUs)
        assertEquals(90L, window.summary()!!.p95.resumeUs)
        assertEquals(2, window.countOver(500))
    }

    @Test fun unknownGpuTimesAreIgnoredAndCapacityIsBounded() {
        val window = ComputeStatsWindow(capacity = 3)
        repeat(5) { window.add(phases(1, gpu = -1)) }
        assertEquals(3, window.size)
        assertEquals(-1L, window.summary()!!.p95.gpuUs)
        window.add(phases(1, gpu = 7))
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
