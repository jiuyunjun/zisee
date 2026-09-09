package com.lazydoglab.zisee.ar

import com.lazydoglab.zisee.ar.annotation.VideoFrameReference
import com.lazydoglab.zisee.ar.render.CameraTextureMapping
import com.lazydoglab.zisee.ar.render.CurrentCameraTexture
import com.lazydoglab.zisee.media.MediaTrack
import org.junit.Assert.*
import org.junit.Test

class CameraTextureMappingTest {
    @Test fun mapsCropRotationAndReflectionWithoutChangingCpuImageCoordinates() {
        for (rotation in 0..3) for (mirror in listOf(false, true)) {
            fun expected(x: Float, y: Float): Pair<Float, Float> {
                var u = if (mirror) 1f - x else x
                var v = y
                repeat(rotation) { val old = u; u = 1f - v; v = old }
                return (0.1f + 0.8f * u) to (0.2f + 0.6f * v)
            }
            val corners = listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f)
                .flatMap { (x, y) -> expected(x, y).let { listOf(it.first, it.second) } }.toFloatArray()
            val mapping = CameraTextureMapping(corners)
            corners.fill(Float.NaN) // Snapshot must not alias the ARCore output array.
            val matrix = mapping.glMatrix()
            for ((x, y) in listOf(0f to 0f, 1f to 1f, 0.2f to 0.7f)) {
                val want = expected(x, y)
                assertEquals(want.first, matrix[0] * x + matrix[4] * (1f - y) + matrix[12], 0.00001f)
                assertEquals(want.second, matrix[1] * x + matrix[5] * (1f - y) + matrix[13], 0.00001f)
            }
            matrix.fill(0f)
            assertEquals(1f, mapping.glMatrix()[15], 0f)
        }
    }

    @Test fun rejectsMissingDegenerateAndNonAffineMapping() {
        for (corners in listOf(FloatArray(8) { Float.NaN }, FloatArray(8), FloatArray(6),
            floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0.8f, 1f))) {
            assertThrows(IllegalArgumentException::class.java) { CameraTextureMapping(corners) }
        }
    }

    @Test fun historicalReferencesCannotSampleAnUpdatedOrInvalidatedTexture() {
        val gate = CurrentCameraTexture()
        val first = VideoFrameReference(MediaTrack.BACK_CAMERA, 100)
        val second = first.copy(timestampNs = 101)
        val mapping = CameraTextureMapping(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
        assertNull(gate.find(first))
        gate.publish(CurrentCameraTexture.Frame(first, 640, 480, mapping))
        assertNotNull(gate.find(first))
        assertNull(gate.find(first.copy(track = MediaTrack.FRONT_CAMERA)))
        gate.invalidate() // Before update: repeated frame, exception and pause all leave no texture.
        assertNull(gate.find(first))
        gate.publish(CurrentCameraTexture.Frame(second, 640, 480, mapping))
        assertNull(gate.find(first))
        assertEquals(second, gate.find(second)?.reference)
        gate.invalidate()
        assertNull(gate.find(second))
    }
}
