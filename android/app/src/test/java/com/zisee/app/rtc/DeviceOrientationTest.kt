package com.zisee.app.rtc

import org.junit.Assert.*
import org.junit.Test

class DeviceOrientationTest {
    @Test fun `a window that already follows the device needs no correction`() {
        for (degrees in listOf(0, 90, 180, 270)) {
            assertEquals(0, orientationCorrection(degrees, degrees, frontFacing = true))
            assertEquals(0, orientationCorrection(degrees, degrees, frontFacing = false))
        }
    }
    @Test fun `a locked window is corrected by the difference the sensor reports`() {
        // Portrait-locked window, phone turned onto its side.
        assertEquals(90, orientationCorrection(90, 0, frontFacing = true))
        assertEquals(180, orientationCorrection(180, 0, frontFacing = true))
        assertEquals(270, orientationCorrection(270, 0, frontFacing = true))
    }
    @Test fun `a rear camera turns the opposite way from a front one`() {
        // The capturer subtracts the device orientation for a rear camera, so the correction that
        // undoes it has to be mirrored, and the two must always cancel.
        for (physical in listOf(0, 90, 180, 270)) {
            for (display in listOf(0, 90, 180, 270)) {
                val front = orientationCorrection(physical, display, frontFacing = true)
                val back = orientationCorrection(physical, display, frontFacing = false)
                assertEquals(0, (front + back) % 360)
            }
        }
    }
    @Test fun `every correction is a legal frame rotation`() {
        for (physical in listOf(0, 90, 180, 270)) {
            for (display in listOf(0, 90, 180, 270)) {
                for (front in listOf(true, false)) {
                    assertTrue(orientationCorrection(physical, display, front) in setOf(0, 90, 180, 270))
                }
            }
        }
    }
}
