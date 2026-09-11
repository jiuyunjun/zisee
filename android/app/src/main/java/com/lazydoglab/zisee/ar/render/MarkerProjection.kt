package com.lazydoglab.zisee.ar.render

import com.lazydoglab.zisee.ar.annotation.VideoPoint
import com.lazydoglab.zisee.ar.session.SpatialMarker
import com.lazydoglab.zisee.ar.spatial.ArTracking
import com.lazydoglab.zisee.ar.spatial.CameraIntrinsics
import com.lazydoglab.zisee.ar.spatial.Rotation
import com.lazydoglab.zisee.ar.spatial.WorldPose
import com.lazydoglab.zisee.ar.spatial.Vec3
import kotlin.math.cos
import kotlin.math.sin

data class ProjectedMarker(val marker: SpatialMarker, val point: VideoPoint,
    val surfaceRing: List<VideoPoint> = emptyList())

/** Projects a session-local anchor into the current unrotated CPU image. */
object MarkerProjection {
    fun project(marker: SpatialMarker, camera: WorldPose, intrinsics: CameraIntrinsics): ProjectedMarker? {
        if (marker.tracking != ArTracking.TRACKING ||
            marker.placementState == com.lazydoglab.zisee.ar.annotation.PlacementState.LOST) return null
        val centre = projectPoint(marker.pose.position, camera, intrinsics) ?: return null
        val normal = marker.normal ?: return ProjectedMarker(marker, centre)
        val axis = if (kotlin.math.abs(normal.y) < 0.9f) Vec3(0f, 1f, 0f) else Vec3(1f, 0f, 0f)
        val u = normal.cross(axis).normalized()
        val v = normal.cross(u).normalized()
        val radius = ((marker.pose.position - camera.position).length() *
            minOf(intrinsics.width, intrinsics.height) * 0.035f / intrinsics.fx).coerceIn(0.008f, 0.15f)
        val ring = (0 until 48).mapNotNull { index ->
            val angle = index * (Math.PI * 2.0 / 48)
            projectPoint(marker.pose.position + normal * 0.002f +
                u * (cos(angle).toFloat() * radius) + v * (sin(angle).toFloat() * radius), camera, intrinsics)
        }
        return ProjectedMarker(marker, centre, if (ring.size == 48) ring else emptyList())
    }

    fun projectPoint(point: Vec3, camera: WorldPose, intrinsics: CameraIntrinsics): VideoPoint? {
        val inverse = Rotation(-camera.rotation.x, -camera.rotation.y, -camera.rotation.z, camera.rotation.w)
        val local = inverse.rotate(point - camera.position)
        val depth = -local.z
        if (!depth.isFinite() || depth <= 0.01f) return null
        val x = (intrinsics.fx * local.x / depth + intrinsics.cx) / intrinsics.width
        val y = (intrinsics.cy - intrinsics.fy * local.y / depth) / intrinsics.height
        if (!x.isFinite() || !y.isFinite() || x !in 0f..1f || y !in 0f..1f) return null
        return VideoPoint(x, y)
    }
}
