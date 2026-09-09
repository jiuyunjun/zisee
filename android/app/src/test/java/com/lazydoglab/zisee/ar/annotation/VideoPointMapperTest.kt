package com.lazydoglab.zisee.ar.annotation

import com.lazydoglab.zisee.rtc.VideoGeometry
import org.junit.Assert.*
import org.junit.Test

class VideoPointMapperTest {
    @Test fun fitRejectsLetterboxAndMapsContentBounds() {
        val geometry = VideoGeometry(200, 100, 0)
        assertNull(VideoPointMapper.fromViewport(50f, 10f, 100f, 100f, geometry))
        assertEquals(VideoPoint(0f, 0f), VideoPointMapper.fromViewport(0f, 25f, 100f, 100f, geometry))
        assertEquals(VideoPoint(1f, 1f), VideoPointMapper.fromViewport(100f, 75f, 100f, 100f, geometry))
    }

    @Test fun fillInvertsCentreCrop() {
        assertEquals(VideoPoint(0.25f, 0.5f), VideoPointMapper.fromViewport(0f, 50f, 100f, 100f,
            VideoGeometry(200, 100, 0), scale = VideoPointMapper.Scale.FILL))
    }

    @Test fun invertsEveryRotationAndDisplayMirror() {
        val expected = listOf(VideoPoint(0.25f, 0.75f), VideoPoint(0.75f, 0.75f),
            VideoPoint(0.75f, 0.25f), VideoPoint(0.25f, 0.25f))
        listOf(0, 90, 180, 270).forEachIndexed { index, rotation ->
            assertEquals(expected[index], VideoPointMapper.fromViewport(25f, 75f, 100f, 100f, VideoGeometry(100, 100, rotation)))
            assertEquals(expected[index], VideoPointMapper.fromViewport(75f, 75f, 100f, 100f, VideoGeometry(100, 100, rotation), mirrored = true))
        }
    }

    @Test fun rejectsInvalidViewportAndTouches() {
        val geometry = VideoGeometry(100, 100, 0)
        assertNull(VideoPointMapper.fromViewport(Float.NaN, 0f, 100f, 100f, geometry))
        assertNull(VideoPointMapper.fromViewport(0f, 0f, 0f, 100f, geometry))
        assertNull(VideoPointMapper.fromViewport(-1f, 0f, 100f, 100f, geometry))
        assertNull(VideoPointMapper.fromViewport(0f, 101f, 100f, 100f, geometry))
    }
}
