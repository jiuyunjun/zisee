package com.zisee.app.rtc

import org.junit.Assert.*
import org.junit.Test

class DeviceOrientationTest {
    @Test fun `locked portrait stays unchanged across repeated display callbacks`() {
        val state = DisplayRotationState(0)
        repeat(20) { assertNull(state.update(0)) }
    }
    @Test fun `locked landscape starts with the actual display direction`() {
        for (rotation in 0..3) assertNull(DisplayRotationState(rotation).update(rotation))
    }
    @Test fun `unlock and actual display rotation update dual capture once`() {
        val state = DisplayRotationState(0)
        for (rotation in listOf(1, 2, 3, 0)) {
            assertEquals(rotation, state.update(rotation))
            assertNull(state.update(rotation))
        }
    }
    @Test fun `invalid callback does not replace the last display rotation`() {
        val state = DisplayRotationState(1)
        assertNull(state.update(-1))
        assertNull(state.update(4))
        assertNull(state.update(1))
        assertEquals(0, state.update(0))
    }
}
