package com.lazydoglab.zisee.rtc

import com.lazydoglab.zisee.rtc.audio.processing.AudioDeadlineMonitor
import com.lazydoglab.zisee.rtc.audio.processing.AudioProcessingStats
import org.junit.Assert.*
import org.junit.Test

class AudioDeadlineMonitorTest {
    @Test fun consecutivePressureAndSingleDeadlineMiss() {
        val monitor = AudioDeadlineMonitor()
        repeat(4) { assertFalse(monitor.record(4_000)) }
        assertFalse(monitor.record(500))
        repeat(4) { assertFalse(monitor.record(4_000)) }
        assertTrue(monitor.record(4_000))
        assertTrue(monitor.record(10_000))
        assertEquals(1L, monitor.misses)
    }
    @Test fun rollingPercentilesAndLifetimeMax() {
        val monitor = AudioDeadlineMonitor()
        monitor.record(20_000)
        repeat(256) { monitor.record(1_000) }
        val stats = monitor.snapshot(AudioProcessingStats())
        assertEquals(1_000L, stats.averageUs)
        assertEquals(1_000L, stats.p95Us)
        assertEquals(1_000L, stats.p99Us)
        assertEquals(20_000L, stats.maxUs)
        assertEquals(1L, stats.deadlineMisses)
    }
}
