package com.lazydoglab.zisee.rtc.compute

import org.junit.Assert.*
import org.junit.Test

class RoiBackgroundPlanTest {
    private val face = RoiBox(0.2f, 0.3f, 0.4f, 0.6f)

    @Test fun mapsRawGlCoordinatesIntoUprightDetectorCoordinatesForEveryRotation() {
        val points = mapOf(0 to (0.3f to 0.55f), 90 to (0.45f to 0.3f),
            180 to (0.7f to 0.45f), 270 to (0.55f to 0.7f))
        points.forEach { (rotation, point) ->
            val plan = requireNotNull(RoiBackgroundPlan.create(listOf(face), rotation))
            assertTrue("rotation=$rotation", plan.protects(point.first, point.second))
            assertFalse(plan.protects(0.05f, 0.05f))
        }
    }

    @Test fun unknownAndEmptyDetectionNeverEnableBackgroundProcessing() {
        assertNull(RoiBackgroundPlan.create(null, 0))
        assertNull(RoiBackgroundPlan.create(emptyList(), 0))
        assertThrows(IllegalArgumentException::class.java) {
            RoiBackgroundPlan.create(listOf(face), 45)
        }
    }
}
