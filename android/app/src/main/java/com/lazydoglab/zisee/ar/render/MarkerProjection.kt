package com.lazydoglab.zisee.ar.render

import com.lazydoglab.zisee.ar.annotation.VideoPoint
import com.lazydoglab.zisee.ar.session.SpatialMarker
import com.lazydoglab.zisee.ar.spatial.ArTracking
import com.lazydoglab.zisee.ar.spatial.CameraIntrinsics
import com.lazydoglab.zisee.ar.spatial.Rotation
import com.lazydoglab.zisee.ar.spatial.WorldPose

data class ProjectedMarker(val marker: SpatialMarker, val point: VideoPoint)

/** Projects a session-local anchor into the current unrotated CPU image. */
object MarkerProjection {
    fun project(marker: SpatialMarker, camera: WorldPose, intrinsics: CameraIntrinsics): ProjectedMarker? {
        if (marker.tracking != ArTracking.TRACKING) return null
        val inverse = Rotation(-camera.rotation.x, -camera.rotation.y, -camera.rotation.z, camera.rotation.w)
        val local = inverse.rotate(marker.pose.position - camera.position)
        val depth = -local.z
        if (!depth.isFinite() || depth <= 0.01f) return null
        val x = (intrinsics.fx * local.x / depth + intrinsics.cx) / intrinsics.width
        val y = (intrinsics.cy - intrinsics.fy * local.y / depth) / intrinsics.height
        if (!x.isFinite() || !y.isFinite() || x !in 0f..1f || y !in 0f..1f) return null
        return ProjectedMarker(marker, VideoPoint(x, y))
    }
}
