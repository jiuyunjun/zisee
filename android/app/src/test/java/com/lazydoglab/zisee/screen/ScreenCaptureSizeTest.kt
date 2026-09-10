package com.lazydoglab.zisee.screen

import org.junit.Assert.*
import org.junit.Test

class ScreenCaptureSizeTest {
    /** Panels from a 720p phone to a folded tablet, in both directions. */
    private val panels = listOf(
        720 to 1280, 1080 to 2400, 1440 to 3120, 1080 to 1920, 2208 to 1840, 800 to 1280,
    ).flatMap { (width, height) -> listOf(width to height, height to width) }

    @Test fun `every panel encodes at an aligned size within the ceiling`() {
        for ((width, height) in panels) {
            val size = ScreenCaptureSize.of(width, height)
            assertEquals("width of $width x $height", 0, size.width % ScreenCaptureSize.ALIGNMENT)
            assertEquals("height of $width x $height", 0, size.height % ScreenCaptureSize.ALIGNMENT)
            assertTrue(size.width > 0 && size.height > 0)
            // Alignment can round one edge up, but never past a whole alignment step.
            assertTrue("$width x $height became ${size.width} x ${size.height}",
                maxOf(size.width, size.height) <= ScreenCaptureSize.MAX_LONG_EDGE + ScreenCaptureSize.ALIGNMENT)
        }
    }

    @Test fun `aspect ratio survives the downscale closely enough to keep text unsheared`() {
        for ((width, height) in panels) {
            val size = ScreenCaptureSize.of(width, height)
            val source = width.toDouble() / height
            val encoded = size.width.toDouble() / size.height
            assertEquals("$width x $height", source, encoded, source * 0.02)
            assertEquals("orientation of $width x $height", width > height, size.width > size.height)
        }
    }

    @Test fun `a panel already within the ceiling is only aligned, never upscaled`() {
        val size = ScreenCaptureSize.of(640, 480)
        assertEquals(640, size.width)
        assertEquals(480, size.height)
    }

    /** A display smaller than one alignment step still has to produce a legal capture size. */
    @Test fun `tiny and degenerate displays stay legal`() {
        for ((width, height) in listOf(1 to 1, 1 to 4000, 4000 to 1, 8 to 9)) {
            val size = ScreenCaptureSize.of(width, height)
            assertTrue(size.width in 1..ScreenSize.MAX_DIMENSION)
            assertTrue(size.height in 1..ScreenSize.MAX_DIMENSION)
        }
    }
}
