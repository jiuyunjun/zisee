package com.lazydoglab.zisee.ar.annotation

import com.lazydoglab.zisee.ar.spatial.*
import kotlin.math.abs
import kotlin.math.ceil

enum class StrokeAppendResult { ADDED, SKIPPED, STOPPED, LIMIT_REACHED }
data class StrokeVertex(val local: Vec3, val frame: VideoFrameReference, val estimated: Boolean)
data class StrokeGeometry(val origin: WorldPose, val vertices: List<StrokeVertex>, val normal: Vec3?)

/** One local coordinate system and at most one native anchor per stroke. Every resampled
 * vertex retains the input's actual displayed source frame; timestamps are never interpolated.
 * A short missing surface can be projected onto the locked plane, bounded by time AND travel.
 */
class StrokeBuilder(first: PlacementResult.World, private val spacingMetres: Float = 0.006f) {
    var origin = first.pose
        private set
    private var evidence = first.evidence
    private val vertices = mutableListOf(StrokeVertex(Vec3(0f, 0f, 0f), first.frame, false))
    private var lastInput = first.pose.position
    private var lastFrame = first.frame
    private var lastEvidenceNs = first.frame.timestampNs
    private var predictedDistance = 0f
    var stopped = false
        private set
    val size: Int get() = vertices.size
    init { require(spacingMetres.isFinite() && spacingMetres in 0.001f..0.05f) }

    /** Keep incoming points in the same anchor-local coordinates after ARCore adjusts the anchor. */
    fun followAnchor(pose: WorldPose) {
        if (pose == origin) return
        lastInput = pose.transform(origin.inverseTransform(lastInput))
        val inverse = Rotation(-origin.rotation.x, -origin.rotation.y, -origin.rotation.z, origin.rotation.w)
        evidence = evidence.copy(normal = evidence.normal?.let { pose.rotation.rotate(inverse.rotate(it)) })
        origin = pose
    }

    fun append(request: SpatialMarkerRequest, placement: PlacementResult, frame: HistoricalFrame?): StrokeAppendResult {
        if (stopped) return StrokeAppendResult.STOPPED
        if (request.frame.track != lastFrame.track || request.frame.timestampNs < lastFrame.timestampNs)
            return stop()
        var estimated = false
        val world = (placement as? PlacementResult.World)?.takeIf {
            it.evidence.method != PlacementMethod.HISTORICAL_RAY_ESTIMATE &&
                compatible(it.evidence) && absDistanceToSurface(it.pose.position) <= 0.03f
        }?.pose?.position ?: run {
            // Explicit evidence of a different surface must end the segment, not be papered over.
            if (placement is PlacementResult.World) return stop()
            if (frame == null || frame.frame != request.frame || frame.tracking != ArTracking.TRACKING) return stop()
            if (request.frame.timestampNs - lastEvidenceNs > AnnotationBudget.MAX_PREDICTION_NS) return stop()
            val normal = evidence.normal ?: return stop()
            val direction = frame.pose.rotation.rotate(frame.intrinsics.cameraRay(request.point)).normalized()
            val denominator = normal.dot(direction)
            if (abs(denominator) < 0.05f) return stop()
            val distance = (origin.position - frame.pose.position).dot(normal) / denominator
            if (distance !in 0.05f..8f) return stop()
            estimated = true
            frame.pose.position + direction * distance
        }
        val delta = world - lastInput
        val length = delta.length()
        if (length > 0.25f) return stop() // A discontinuity is not a long connecting line.
        if (estimated && predictedDistance + length > AnnotationBudget.MAX_PREDICTION_METRES) return stop()
        if (estimated) predictedDistance += length else { predictedDistance = 0f; lastEvidenceNs = request.frame.timestampNs }
        lastFrame = request.frame
        if (length < spacingMetres) return StrokeAppendResult.SKIPPED
        val count = ceil(length / spacingMetres).toInt()
        if (vertices.size + count > AnnotationBudget.MAX_STROKE_POINTS) {
            stopped = true
            return StrokeAppendResult.LIMIT_REACHED
        }
        val inverse = Rotation(-origin.rotation.x, -origin.rotation.y, -origin.rotation.z, origin.rotation.w)
        for (index in 1..count) {
            val point = lastInput + delta * (index.toFloat() / count)
            vertices.add(StrokeVertex(inverse.rotate(point - origin.position), request.frame, estimated))
        }
        lastInput = world
        return StrokeAppendResult.ADDED
    }

    fun finish() { stopped = true }
    fun snapshot(): StrokeGeometry = StrokeGeometry(origin, vertices.toList(), evidence.normal)

    private fun compatible(other: SurfaceEvidence): Boolean {
        val planeMethods = setOf(PlacementMethod.PLANE, PlacementMethod.LOCAL_SURFACE)
        val sameKind = evidence.method == other.method || evidence.method in planeMethods && other.method in planeMethods
        if (evidence.surfaceId != null && other.surfaceId != null &&
            (!sameKind || evidence.surfaceId != other.surfaceId)) return false
        val normal = evidence.normal
        return normal == null || other.normal != null && normal.dot(other.normal) >= 0.94f
    }
    private fun absDistanceToSurface(point: Vec3): Float = evidence.normal?.let { abs((point - origin.position).dot(it)) } ?: 0f
    private fun stop(): StrokeAppendResult { stopped = true; return StrokeAppendResult.STOPPED }
}

/** Ribbon vertices are triangles in the stroke's local coordinates. Degenerate points are
 * removed; independent segment quads avoid unbounded miter spikes at sharp corners.
 */
object StrokeRibbon {
    fun build(geometry: StrokeGeometry, cameraLocal: Vec3, widthMetres: Float = 0.006f): FloatArray {
        require(widthMetres.isFinite() && widthMetres in 0.001f..0.05f)
        val points = geometry.vertices.map { it.local }
        val output = ArrayList<Float>((points.size - 1).coerceAtLeast(0) * 18)
        for (index in 1 until points.size) {
            val a = points[index - 1]; val b = points[index]
            val tangent = b - a
            if (tangent.length() < 1e-6f) continue
            val view = cameraLocal - (a + b) * 0.5f
            val side = tangent.cross(view).takeIf { it.length() > 1e-7f }?.normalized()?.times(widthMetres * 0.5f) ?: continue
            for (point in listOf(a - side, a + side, b - side, b - side, a + side, b + side)) {
                output.add(point.x); output.add(point.y); output.add(point.z)
            }
        }
        return output.toFloatArray()
    }
}
