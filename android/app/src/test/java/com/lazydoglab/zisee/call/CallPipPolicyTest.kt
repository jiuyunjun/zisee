package com.lazydoglab.zisee.call

import org.junit.Assert.assertEquals
import org.junit.Test

class CallPipPolicyTest {
    @Test fun preservesAndReducesOrdinaryFrameRatios() {
        assertEquals(PipAspect(16, 9), CallPipPolicy.aspect(1920, 1080))
        assertEquals(PipAspect(9, 16), CallPipPolicy.aspect(1080, 1920))
        assertEquals(PipAspect(1, 1), CallPipPolicy.aspect(720, 720))
    }

    @Test fun clampsUnsupportedRatiosAndDefaultsMissingFrames() {
        assertEquals(PipAspect(239, 100), CallPipPolicy.aspect(4000, 1000))
        assertEquals(PipAspect(100, 239), CallPipPolicy.aspect(1000, 4000))
        assertEquals(PipAspect(16, 9), CallPipPolicy.aspect(0, 0))
    }
}
