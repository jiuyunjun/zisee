package com.lazydoglab.zisee.ar.spatial

import com.lazydoglab.zisee.ar.annotation.*
import com.lazydoglab.zisee.media.MediaTrack

/** Resolves only evidence belonging to the exact displayed source frame. No native Frame is
 * retained, and a historical ray estimate is deliberately not called Instant Placement.
 */
class PlacementResolver(private val history: PoseHistory, private val maxDistanceMetres: Float = 8f) {
    init { require(maxDistanceMetres.isFinite() && maxDistanceMetres > 0f) }

    fun resolve(request: SpatialMarkerRequest, nowNs: Long, allowEstimate: Boolean = true): PlacementResult {
        require(nowNs >= 0 && nowNs <= Long.MAX_VALUE - AnnotationBudget.SCREEN_TTL_NS)
        fun screen(reason: SpatialRejection) = PlacementResult.Screen(request.point, request.frame,
            nowNs + AnnotationBudget.SCREEN_TTL_NS, reason)
        if (request.frame.track != MediaTrack.BACK_CAMERA) return screen(SpatialRejection.WRONG_TRACK)
        val frame = history.find(request.frame) ?: return screen(SpatialRejection.FRAME_MISSING)
        if (frame.tracking != ArTracking.TRACKING) return screen(SpatialRejection.TRACKING_UNAVAILABLE)
        val ray = frame.intrinsics.cameraRay(request.point)
        val origin = frame.pose.position
        val direction = frame.pose.rotation.rotate(ray).normalized()
        frame.depth?.surfaceAt(request.point, frame.intrinsics)?.let { depth ->
            val local = ray * depth.metres
            if (local.length() > maxDistanceMetres) return screen(SpatialRejection.TOO_FAR)
            return PlacementResult.World(WorldPose(frame.pose.transform(local)), SurfaceEvidence(
                PlacementMethod.DEPTH, depth.confidence, depth.cameraNormal?.let { frame.pose.rotation.rotate(it) }), frame.frame)
        }
        fun hit(margin: Float) = frame.planes.mapNotNull { plane ->
            plane.intersect(origin, direction, margin)?.let { plane to it }
        }.minByOrNull { (_, point) -> (point - origin).length() }
        val strict = hit(0f)
        val planeHit = strict ?: hit(0.25f)
        planeHit?.let { (plane, point) ->
            if ((point - origin).length() > maxDistanceMetres) return screen(SpatialRejection.TOO_FAR)
            val normal = plane.normal.let { if (it.dot(direction) > 0f) it * -1f else it }
            return PlacementResult.World(WorldPose(point), SurfaceEvidence(
                if (strict != null) PlacementMethod.PLANE else PlacementMethod.LOCAL_SURFACE,
                if (strict != null) 0.85f else 0.55f, normal, plane.surfaceId, strict != null), frame.frame)
        }
        // A feature must lie inside a narrow ray cone and within five centimetres of the ray.
        val feature = frame.features.asSequence().filter { it.confidence >= 0.5f }.mapNotNull { feature ->
            val offset = feature.position - origin
            val distance = offset.dot(direction)
            val error = (offset - direction * distance).length()
            if (distance in 0.1f..maxDistanceMetres && error <= minOf(0.05f, distance * 0.015f))
                feature to error else null
        }.minByOrNull { it.second }?.first
        if (feature != null) return PlacementResult.World(WorldPose(feature.position),
            SurfaceEvidence(PlacementMethod.FEATURE_POINT, feature.confidence * 0.65f, surfaceId = feature.id), frame.frame)
        if (!allowEstimate) return screen(SpatialRejection.SURFACE_MISSING)
        val distance = minOf(1.5f, maxDistanceMetres)
        return PlacementResult.World(WorldPose(origin + direction * distance),
            SurfaceEvidence(PlacementMethod.HISTORICAL_RAY_ESTIMATE, 0.15f), frame.frame)
    }
}
