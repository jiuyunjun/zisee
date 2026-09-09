package com.zisee.app.rtc

import org.junit.Assert.*
import org.junit.Test

class VideoGeometryTest {
    @Test fun `upright dimensions follow each frame rather than the receiver window`() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val frame = VideoGeometry(1920, 1080, rotation)
            val sideways = rotation == 90 || rotation == 270
            assertEquals(if (sideways) 1080 else 1920, frame.displayWidth)
            assertEquals(if (sideways) 1920 else 1080, frame.displayHeight)
            assertEquals(if (sideways) 9f / 16 else 16f / 9, frame.aspectRatio, 0.0001f)
        }
    }

    @Test fun `sensor noise around the diagonal cannot alternate orientations`() {
        val sensor = OrientationQuantizer(0)
        for (degree in listOf(43, 47, 44, 46, 59)) assertEquals(0, sensor.update(degree))
        assertEquals(3, sensor.update(60))
        for (degree in listOf(48, 44, 46, 31)) assertEquals(3, sensor.update(degree))
        assertEquals(0, sensor.update(30))
    }

    @Test fun `flat unknown and invalid sensor values preserve initial display direction`() {
        for (initial in 0..3) {
            val sensor = OrientationQuantizer(initial)
            for (unknown in listOf(-1, -90, 360)) assertEquals(initial, sensor.update(unknown))
        }
    }

    @Test fun `clockwise sensor maps to counterclockwise display for all starting rotations`() {
        for (initial in 0..3) {
            for ((angle, expected) in listOf(0 to 0, 90 to 3, 180 to 2, 270 to 1)) {
                assertEquals(expected, OrientationQuantizer(initial).update(angle))
            }
        }
        val sensor = OrientationQuantizer(0)
        assertEquals(0, sensor.update(359))
        assertEquals(1, sensor.update(300))
        assertEquals(1, sensor.update(310))
        assertEquals(0, sensor.update(330))
    }

    @Test fun `both lenses produce the same upright orientation as the physical capturer formula`() {
        for (camera in listOf(0, 90, 180, 270)) {
            for (physical in listOf(0, 90, 180, 270)) {
                for (display in listOf(0, 90, 180, 270)) {
                    for (front in listOf(true, false)) {
                        val sign = if (front) 1 else -1
                        val captured = (camera + sign * display + 360) % 360
                        val expected = (camera + sign * physical + 360) % 360
                        assertEquals(expected, (captured + orientationCorrection(physical, display, front)) % 360)
                    }
                }
            }
        }
    }
}
