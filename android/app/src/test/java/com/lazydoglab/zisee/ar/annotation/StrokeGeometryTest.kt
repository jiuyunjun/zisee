package com.lazydoglab.zisee.ar.annotation

import com.lazydoglab.zisee.ar.spatial.*
import com.lazydoglab.zisee.media.MediaTrack
import org.junit.Assert.*
import org.junit.Test

class StrokeGeometryTest {
    private fun frame(time: Long) = VideoFrameReference(MediaTrack.BACK_CAMERA, time)
    private fun world(time: Long, x: Float, normal: Vec3 = Vec3(0f, 0f, 1f)) = PlacementResult.World(
        WorldPose(Vec3(x, 0f, -1f)), SurfaceEvidence(PlacementMethod.DEPTH, 0.9f, normal), frame(time))
    private fun request(time: Long, x: Float = 0.5f) = SpatialMarkerRequest(frame(time), VideoPoint(x, 0.5f))
    private fun snapshot(time: Long) = HistoricalFrame(frame(time), WorldPose(Vec3(0f, 0f, 0f)),
        CameraIntrinsics(100, 100, 100f, 100f, 50f, 50f), ArTracking.TRACKING)

    @Test fun resampledVerticesKeepExactFrameAndPreserveCorner() {
        val stroke = StrokeBuilder(world(1, 0f))
        assertEquals(StrokeAppendResult.ADDED, stroke.append(request(2), world(2, 0.024f), null))
        val corner = world(3, 0.024f).copy(pose = WorldPose(Vec3(0.024f, 0.024f, -1f)))
        assertEquals(StrokeAppendResult.ADDED, stroke.append(request(3), corner, null))
        val points = stroke.snapshot().vertices
        assertTrue(points.any { it.local == Vec3(0.024f, 0f, 0f) })
        assertTrue(points.drop(1).all { it.frame == frame(2) || it.frame == frame(3) })
        assertTrue(points.zipWithNext().all { (a, b) -> (b.local - a.local).length() <= 0.0061f })
    }

    @Test fun shortMissingSurfaceUsesLockedPlaneButNeverOutlivesPredictionBudget() {
        val stroke = StrokeBuilder(world(1, 0f))
        fun miss(time: Long, x: Float) = PlacementResult.Screen(VideoPoint(x, 0.5f), frame(time), 2_000_000_000, SpatialRejection.SURFACE_MISSING)
        val time = 50_000_001L
        assertEquals(StrokeAppendResult.ADDED, stroke.append(request(time, 0.52f), miss(time, 0.52f), snapshot(time)))
        assertTrue(stroke.snapshot().vertices.last().estimated)
        val tooLate = 100_000_002L
        assertEquals(StrokeAppendResult.STOPPED, stroke.append(request(tooLate, 0.53f), miss(tooLate, 0.53f), snapshot(tooLate)))
        assertEquals(StrokeAppendResult.STOPPED, stroke.append(request(tooLate + 1), world(tooLate + 1, 0.03f), snapshot(tooLate + 1)))
    }

    @Test fun differentNormalStopsInsteadOfJoiningSurfacesAndAnchorCorrectionPreservesLocalCoordinates() {
        val stroke = StrokeBuilder(world(1, 0f))
        stroke.followAnchor(WorldPose(Vec3(1f, 0f, -1f)))
        assertEquals(StrokeAppendResult.ADDED, stroke.append(request(2), world(2, 1.012f), null))
        assertEquals(0.012f, stroke.snapshot().vertices.last().local.x, 0.0001f)
        assertEquals(StrokeAppendResult.STOPPED, stroke.append(request(3), world(3, 1.02f, Vec3(0f, 1f, 0f)), null))
    }

    @Test fun ribbonSkipsDegeneratePointsAndProducesBoundedFiniteTriangles() {
        val geometry = StrokeGeometry(WorldPose(Vec3(0f, 0f, -1f)), listOf(
            StrokeVertex(Vec3(0f, 0f, 0f), frame(1), false), StrokeVertex(Vec3(0f, 0f, 0f), frame(2), false),
            StrokeVertex(Vec3(0.02f, 0f, 0f), frame(3), false)), null)
        val vertices = StrokeRibbon.build(geometry, Vec3(0f, 0f, 1f))
        assertEquals(18, vertices.size)
        assertTrue(vertices.all { it.isFinite() })
        assertTrue(vertices.filterIndexed { index, _ -> index % 3 == 1 }.all { kotlin.math.abs(it) <= 0.0031f })
    }
}
