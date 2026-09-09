package com.lazydoglab.zisee.rtc

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

}
