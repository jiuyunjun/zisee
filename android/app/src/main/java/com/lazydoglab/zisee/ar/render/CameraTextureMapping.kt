package com.lazydoglab.zisee.ar.render

import com.lazydoglab.zisee.ar.annotation.VideoFrameReference
import com.lazydoglab.zisee.media.MediaTrack
import kotlin.math.abs

/** ARCore texture UV at CPU-image corners: top-left, top-right, bottom-left, bottom-right.
 * No display crop/rotation is baked in. The renderer outputs the full unrotated CPU image.
 */
class CameraTextureMapping(corners: FloatArray) {
    private val uv = corners.copyOf()
    init {
        require(uv.size == 8 && uv.all { it.isFinite() })
        // A 2D affine mapping is required by GlRectDrawer. Fail closed if this changes (e.g. EIS).
        require(abs(uv[6] - (uv[2] + uv[4] - uv[0])) < 0.0001f)
        require(abs(uv[7] - (uv[3] + uv[5] - uv[1])) < 0.0001f)
        val determinant = (uv[2] - uv[0]) * (uv[5] - uv[1]) - (uv[3] - uv[1]) * (uv[4] - uv[0])
        require(abs(determinant) > 0.000001f)
    }

    /** Column-major GL matrix; GL bottom-left maps to CPU bottom-left (image Y points down). */
    fun glMatrix(): FloatArray = floatArrayOf(
        uv[2] - uv[0], uv[3] - uv[1], 0f, 0f,
        uv[0] - uv[4], uv[1] - uv[5], 0f, 0f,
        0f, 0f, 1f, 0f,
        uv[4], uv[5], 0f, 1f,
    )
}

/** A borrowed camera texture is invalidated BEFORE every native update, including failed/repeated
 * updates. History may keep a frame for seconds; the camera texture only holds the current image.
 * Owner-thread only. This gate holds no native Frame/Image or pixel data.
 */
internal class CurrentCameraTexture {
    data class Frame(val reference: VideoFrameReference, val width: Int, val height: Int,
        val mapping: CameraTextureMapping)
    private var current: Frame? = null
    fun invalidate() { current = null }
    fun publish(frame: Frame) {
        require(frame.reference.track == MediaTrack.BACK_CAMERA && frame.reference.timestampNs > 0)
        require(frame.width > 0 && frame.height > 0)
        current = frame
    }
    fun find(reference: VideoFrameReference): Frame? = current?.takeIf { it.reference == reference }
}
