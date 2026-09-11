package com.lazydoglab.zisee.rtc.compute

import org.junit.Assert.*
import org.junit.Test

class RgbaRoiInputTest {
    @Test fun bottomUpRgbaBecomesUprightArgbForEveryRotation() {
        // Raw GL bottom row is C,D; top row A,B. Use separate channels to catch RGBA/BGRA confusion.
        val bytes = byteArrayOf(0, 0, -1, -1, -1, -1, -1, -1, -1, 0, 0, -1, 0, -1, 0, -1)
        val a = 0xffff0000.toInt(); val b = 0xff00ff00.toInt()
        val c = 0xff0000ff.toInt(); val d = 0xffffffff.toInt()
        val expected = mapOf(0 to intArrayOf(a,b,c,d), 90 to intArrayOf(c,a,d,b),
            180 to intArrayOf(d,c,b,a), 270 to intArrayOf(b,d,a,c))
        for ((rotation, pixels) in expected) {
            RgbaRoiInput(RoiGeometry(1, 2, 2, rotation), 123, 2, 2, bytes.copyOf()).use { input ->
                assertArrayEquals(pixels, input.uprightArgb())
                assertEquals(123L, input.timestampNs)
            }
        }
    }

    @Test fun nonSquareRotationSwapsDimensionsAndPreservesAllPixels() {
        val bytes = ByteArray(2 * 3 * 4) { index -> if (index % 4 == 0) (index / 4 + 1).toByte() else 0 }
        RgbaRoiInput(RoiGeometry(7, 20, 30, 90), 123, 2, 3, bytes).use { input ->
            assertEquals(3, input.uprightWidth); assertEquals(2, input.uprightHeight)
            assertArrayEquals(intArrayOf(1,3,5,2,4,6), input.uprightArgb().map { (it shr 16) and 255 }.toIntArray())
        }
    }

    @Test fun releaseErasesInputAndRejectsFurtherAccess() {
        val bytes = ByteArray(16) { 42 }
        val input = RgbaRoiInput(RoiGeometry(1, 2, 2, 0), 0, 2, 2, bytes)
        input.close(); input.close()
        assertTrue(bytes.all { it == 0.toByte() })
        assertThrows(IllegalStateException::class.java) { input.uprightArgb() }
    }

    @Test fun oversizedOrMalformedInputIsRejectedBeforeConversion() {
        val geometry = RoiGeometry(1, 640, 360, 0)
        assertThrows(IllegalArgumentException::class.java) { RgbaRoiInput(geometry, 0, 641, 1, ByteArray(2564)) }
        assertThrows(IllegalArgumentException::class.java) { RgbaRoiInput(geometry, 0, 640, 640, ByteArray(0)) }
        assertThrows(IllegalArgumentException::class.java) { RgbaRoiInput(geometry, 0, 2, 2, ByteArray(15)) }
        assertThrows(IllegalArgumentException::class.java) { RgbaRoiInput(geometry.copy(rotation = 45), 0, 2, 2, ByteArray(16)) }
    }
}
