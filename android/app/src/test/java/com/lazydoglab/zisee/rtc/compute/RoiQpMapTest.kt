package com.lazydoglab.zisee.rtc.compute

import org.junit.Assert.*
import org.junit.Test

class RoiQpMapTest {
    @Test fun rectangleRasterizationMatchesBlockOverlapAcrossRotations() {
        val boxes = listOf(RoiBox(0.11f, 0.23f, 0.62f, 0.77f), RoiBox(0.91f, 0.89f, 1f, 1f))
        for (rotation in listOf(0, 90, 180, 270)) {
            val plan = requireNotNull(RoiBackgroundPlan.create(boxes, rotation))
            val map = requireNotNull(RoiQpMapPlanner.create(113, 79, plan))
            val bytes = map.bytes()
            for (y in 0 until map.blocksHigh) for (x in 0 until map.blocksWide) {
                val expected = if (plan.protectsEncodedBlock(x * 16f / 113, y * 16f / 79,
                        minOf(113, (x + 1) * 16) / 113f, minOf(79, (y + 1) * 16) / 79f)) -3 else 0
                assertEquals("rotation=$rotation block=$x,$y", expected.toByte(), bytes[y * map.blocksWide + x])
            }
        }
    }

    @Test fun createsOneSignedOffsetPerSixteenPixelBlockAndProtectsFaceBlocks() {
        val map = requireNotNull(RoiQpMapPlanner.create(32, 32, 0,
            listOf(RoiBox(0f, 0f, 0.5f, 0.5f))))
        assertEquals(2, map.blocksWide)
        assertEquals(2, map.blocksHigh)
        assertArrayEquals(byteArrayOf(-3, 0, 0, 0), map.bytes())
        val copy = map.bytes(); copy[0] = 99
        assertEquals((-3).toByte(), map.bytes()[0])
    }

    @Test fun respectsRotationAndDoesNotCreateAStickyMapWithoutFreshFaces() {
        val box = RoiBox(0f, 0f, 0.5f, 0.5f)
        assertArrayEquals(byteArrayOf(0, 0, -3, 0),
            requireNotNull(RoiQpMapPlanner.create(32, 32, 90, listOf(box))).bytes())
        assertNull(RoiQpMapPlanner.create(32, 32, 0, null))
        assertNull(RoiQpMapPlanner.create(32, 32, 0, emptyList()))
    }

    @Test fun protectsSmallFacesAndPartialEdgeBlocks() {
        val map = requireNotNull(RoiQpMapPlanner.create(17, 33, 0,
            listOf(RoiBox(0.95f, 0.98f, 1f, 1f))))
        assertEquals(2, map.blocksWide)
        assertEquals(3, map.blocksHigh)
        assertArrayEquals(byteArrayOf(0, 0, 0, 0, 0, -3), map.bytes())
    }
}
