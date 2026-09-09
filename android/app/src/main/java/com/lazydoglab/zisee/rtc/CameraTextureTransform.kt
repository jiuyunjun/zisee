package com.lazydoglab.zisee.rtc

import android.graphics.Matrix

/** SurfaceTexture includes camera orientation; WebRTC carries that orientation in frame metadata. */
internal class CameraTextureTransform(
    val rotationDegrees: Int,
    private val hasCameraTransform: Boolean,
    private val sensorRotationDegrees: Int,
    private val frontFacing: Boolean,
) {
    // Match WebRTC Camera2Session: restore sensor coordinates and unmirrored outbound media.
    // This is concatenated with the existing texture matrix, preserving its crop/vertical flip.
    private val correction = Matrix().apply {
        if (hasCameraTransform) {
            preTranslate(0.5f, 0.5f)
            if (frontFacing) preScale(-1f, 1f)
            preRotate(-sensorRotationDegrees.toFloat())
            preTranslate(-0.5f, -0.5f)
        }
    }

    fun textureCorrection(): Matrix = correction
}
