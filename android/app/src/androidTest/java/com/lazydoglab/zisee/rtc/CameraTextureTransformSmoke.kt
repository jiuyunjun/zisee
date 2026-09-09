package com.lazydoglab.zisee.rtc

import android.graphics.Matrix
import kotlin.math.abs

/** Uses the real Android Matrix implementation; asymmetric points detect mirror/order mistakes. */
internal object CameraTextureTransformSmoke {
    fun run() {
        val original = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f, 0.2f, 0.7f)
        for (sensor in listOf(0, 90, 180, 270)) for (front in listOf(false, true)) {
            for (display in listOf(0, 90, 180, 270)) {
                val rotation = (sensor + if (front) display else 360 - display) % 360
                val transform = CameraTextureTransform(rotation, true, sensor, front)
                check(transform.rotationDegrees == rotation)
                val points = original.copyOf()
                transform.textureCorrection().mapPoints(points)
                // Independently simulate the camera's sensor rotation and front mirror. The
                // correction must cancel those, leaving raw coordinates for frame.rotation.
                for (index in points.indices step 2) {
                    var x = if (front) 1f - points[index] else points[index]
                    var y = points[index + 1]
                    repeat(sensor / 90) {
                        val previousX = x
                        x = 1f - y
                        y = previousX
                    }
                    check(abs(x - original[index]) < 0.00001f)
                    check(abs(y - original[index + 1]) < 0.00001f)
                }
                // Existing crop and GL vertical inversion must survive matrix concatenation.
                val base = Matrix().apply { setValues(floatArrayOf(.8f, 0f, .1f, 0f, -.6f, .9f, 0f, 0f, 1f)) }
                val expected = original.copyOf()
                transform.textureCorrection().mapPoints(expected)
                base.mapPoints(expected)
                val actual = original.copyOf()
                Matrix(base).apply { preConcat(transform.textureCorrection()) }.mapPoints(actual)
                actual.indices.forEach { check(abs(actual[it] - expected[it]) < 0.00001f) }

                val processed = CameraTextureTransform(rotation, false, sensor, front)
                val unchanged = original.copyOf()
                processed.textureCorrection().mapPoints(unchanged)
                check(unchanged.contentEquals(original))
                check(processed.rotationDegrees == rotation)
            }
        }
    }
}
