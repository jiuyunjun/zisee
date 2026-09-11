package com.lazydoglab.zisee.ar

import com.lazydoglab.zisee.ar.render.uprightOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class AnnotationHudTest {
    @Test fun hudCounterRotatesForAllVideoOrientations() {
        assertEquals(2f to -5f, uprightOffset(2f, -5f, 0))
        assertEquals(-5f to -2f, uprightOffset(2f, -5f, 90))
        assertEquals(-2f to 5f, uprightOffset(2f, -5f, 180))
        assertEquals(5f to 2f, uprightOffset(2f, -5f, 270))
    }
}
