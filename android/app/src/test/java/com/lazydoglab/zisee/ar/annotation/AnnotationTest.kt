package com.lazydoglab.zisee.ar.annotation

import com.lazydoglab.zisee.media.MediaTrack
import org.junit.Assert.assertThrows
import org.junit.Test

class AnnotationTest {
    @Test fun rejectsInvalidCoordinatesAndNonVideoFrames() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, -0.1f, 1.1f).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { VideoPoint(invalid, 0.5f) }
            assertThrows(IllegalArgumentException::class.java) { VideoPoint(0.5f, invalid) }
        }
        assertThrows(IllegalArgumentException::class.java) { VideoFrameReference(MediaTrack.MICROPHONE, 1) }
        assertThrows(IllegalArgumentException::class.java) { VideoFrameReference(MediaTrack.BACK_CAMERA, -1) }
        VideoPoint(0f, 1f)
        VideoFrameReference(MediaTrack.BACK_CAMERA, 0)
    }
}
