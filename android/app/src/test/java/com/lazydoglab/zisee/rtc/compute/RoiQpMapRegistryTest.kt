package com.lazydoglab.zisee.rtc.compute

import com.lazydoglab.zisee.core.logging.AppEvent
import com.lazydoglab.zisee.core.logging.AppLogger
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class RoiQpMapRegistryTest {
    private class Logger : AppLogger {
        val info = mutableListOf<Pair<AppEvent, String?>>()
        val errors = mutableListOf<Pair<AppEvent, String?>>()
        override fun info(event: AppEvent, detail: String?) { info += event to detail }
        override fun error(event: AppEvent, reason: String?) { errors += event to reason }
    }

    @Test fun sameSourceReplacesNeutralAndTakeConsumesTheMap() {
        val clock = AtomicLong(100)
        val registry = RoiQpMapRegistry(Logger(), clock::get)
        val map = requireNotNull(RoiQpMapPlanner.create(32, 32, 0,
            listOf(RoiBox(0f, 0f, 0.5f, 0.5f))))
        registry.record(1_234_567, "front", null)
        registry.record(1_234_567, "front", map)
        assertArrayEquals(map.bytes(), registry.take(1_234_999, 32, 32))
        assertNull(registry.take(1_234_999, 32, 32))
    }

    @Test fun cameraTimestampCollisionAndDimensionMismatchStayNeutral() {
        val registry = RoiQpMapRegistry(Logger())
        val map = requireNotNull(RoiQpMapPlanner.create(32, 32, 0,
            listOf(RoiBox(0f, 0f, 0.5f, 0.5f))))
        registry.record(2_000, "front", map)
        registry.record(2_999, "back", null)
        assertNull(registry.take(2_500, 32, 32))
        registry.record(3_000, "front", map)
        assertNull(registry.take(3_000, 64, 64))
    }

    @Test fun staleEntriesAndTransportTelemetryAreBoundedAndObservable() {
        val clock = AtomicLong(0)
        val logger = Logger()
        val registry = RoiQpMapRegistry(logger, clock::get)
        val map = requireNotNull(RoiQpMapPlanner.create(16, 16, 0,
            listOf(RoiBox(0f, 0f, 1f, 1f))))
        registry.record(1_000, "front", map)
        clock.set(2_000_000_001)
        assertNull(registry.take(1_000, 16, 16))
        registry.configured("codec", true)
        registry.configured("codec", true)
        registry.submitted("codec", true)
        registry.submitted("codec", false)
        registry.failed("codec", "set_parameters")
        assertEquals(RoiQpMapStats(setOf("codec"), emptySet(), 1, 1, 1, "set_parameters"), registry.stats)
        assertEquals(1, logger.info.size)
        assertEquals(1, logger.errors.size)
        assertTrue(logger.info.single().second!!.length < 120)
        assertTrue(logger.errors.single().second!!.length < 120)
    }
}
