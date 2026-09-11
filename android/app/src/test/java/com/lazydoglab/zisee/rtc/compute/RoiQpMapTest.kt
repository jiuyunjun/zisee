package com.lazydoglab.zisee.rtc.compute

import org.junit.Assert.*
import org.junit.Test

class RoiQpMapTest {
    @Test fun createsOneSignedOffsetPerSixteenPixelBlockAndProtectsFaceBlocks() {
        val map = requireNotNull(RoiQpMapPlanner.create(32, 32, 0,
            listOf(RoiBox(0f, 0f, 0.5f, 0.5f))))
        assertEquals(2, map.blocksWide)
        assertEquals(2, map.blocksHigh)
        assertArrayEquals(byteArrayOf(-3, 1, 1, 1), map.bytes())
        val copy = map.bytes(); copy[0] = 99
        assertEquals((-3).toByte(), map.bytes()[0])
    }

    @Test fun respectsRotationAndDoesNotCreateAStickyMapWithoutFreshFaces() {
        val box = RoiBox(0f, 0f, 0.5f, 0.5f)
        assertArrayEquals(byteArrayOf(1, 1, -3, 1),
            requireNotNull(RoiQpMapPlanner.create(32, 32, 90, listOf(box))).bytes())
        assertNull(RoiQpMapPlanner.create(32, 32, 0, null))
        assertNull(RoiQpMapPlanner.create(32, 32, 0, emptyList()))
    }

    @Test fun protectsSmallFacesAndPartialEdgeBlocks() {
        val map = requireNotNull(RoiQpMapPlanner.create(17, 33, 0,
            listOf(RoiBox(0.95f, 0.98f, 1f, 1f))))
        assertEquals(2, map.blocksWide)
        assertEquals(3, map.blocksHigh)
        assertArrayEquals(byteArrayOf(1, 1, 1, 1, 1, -3), map.bytes())
    }
}
