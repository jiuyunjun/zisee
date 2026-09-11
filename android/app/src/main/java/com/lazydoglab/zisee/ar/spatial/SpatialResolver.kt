package com.lazydoglab.zisee.ar.spatial

import com.lazydoglab.zisee.ar.annotation.SpatialMarkerRequest
import com.lazydoglab.zisee.ar.annotation.PlacementMethod
import com.lazydoglab.zisee.ar.annotation.SurfaceEvidence
import com.lazydoglab.zisee.media.MediaTrack

enum class SpatialRejection {
    WRONG_SESSION, INACTIVE, WRONG_TRACK, FRAME_MISSING, TRACKING_UNAVAILABLE,
    SURFACE_MISSING, TOO_FAR, LIMIT_REACHED, DUPLICATE_ID, NATIVE_FAILURE,
}

enum class SurfaceSource { DEPTH, PLANE }
sealed interface SpatialResolution {
    data class Resolved(val pose: WorldPose, val source: SurfaceSource,
        val evidence: SurfaceEvidence = SurfaceEvidence(
            if (source == SurfaceSource.DEPTH) PlacementMethod.DEPTH else PlacementMethod.PLANE, 0.5f)) : SpatialResolution
    data class Rejected(val reason: SpatialRejection) : SpatialResolution
}

class SpatialResolver(private val history: PoseHistory, private val maxDistanceMetres: Float = 8f) {
    init { require(maxDistanceMetres.isFinite() && maxDistanceMetres > 0f) }
    fun resolve(request: SpatialMarkerRequest): SpatialResolution {
        fun reject(reason: SpatialRejection) = SpatialResolution.Rejected(reason)
        if (request.frame.track != MediaTrack.BACK_CAMERA) return reject(SpatialRejection.WRONG_TRACK)
        val frame = history.find(request.frame) ?: return reject(SpatialRejection.FRAME_MISSING)
        if (frame.tracking != ArTracking.TRACKING) return reject(SpatialRejection.TRACKING_UNAVAILABLE)
        val ray = frame.intrinsics.cameraRay(request.point)
        val depth = frame.depth?.metresAt(request.point)
        if (depth != null) {
            val point = ray * depth
            if (point.length() > maxDistanceMetres) return reject(SpatialRejection.TOO_FAR)
            return SpatialResolution.Resolved(WorldPose(frame.pose.transform(point)), SurfaceSource.DEPTH)
        }
        val origin = frame.pose.position
        val direction = frame.pose.rotation.rotate(ray).normalized()
        fun nearestPlaneHit(margin: Float) = frame.planes.mapNotNull { it.intersect(origin, direction, margin) }
            .minByOrNull { (it - origin).length() }
        // Inside a detected polygon first; only then the band just past a plane's edge.
        val point = nearestPlaneHit(0f) ?: nearestPlaneHit(PLANE_EDGE_MARGIN_METRES)
            ?: return reject(SpatialRejection.SURFACE_MISSING)
        if ((point - origin).length() > maxDistanceMetres) return reject(SpatialRejection.TOO_FAR)
        return SpatialResolution.Resolved(WorldPose(point), SurfaceSource.PLANE)
    }

    private companion object { const val PLANE_EDGE_MARGIN_METRES = 0.25f }
}
