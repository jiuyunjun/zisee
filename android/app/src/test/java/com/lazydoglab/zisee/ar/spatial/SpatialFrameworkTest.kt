package com.lazydoglab.zisee.ar.spatial

import com.lazydoglab.zisee.ar.annotation.SpatialMarkerRequest
import com.lazydoglab.zisee.ar.annotation.VideoFrameReference
import com.lazydoglab.zisee.ar.annotation.VideoPoint
import com.lazydoglab.zisee.media.MediaTrack
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

class SpatialFrameworkTest {
    private val intrinsics = CameraIntrinsics(640, 480, 320f, 320f, 320f, 240f)
    private fun frame(time: Long, pose: WorldPose = WorldPose(Vec3(0f, 0f, 0f)),
        tracking: ArTracking = ArTracking.TRACKING, depth: DepthSnapshot? = null,
        planes: List<PlaneSnapshot> = emptyList()) = HistoricalFrame(
        VideoFrameReference(MediaTrack.BACK_CAMERA, time), pose, intrinsics, tracking, depth, planes)
    private fun request(time: Long, x: Float = 0.5f, y: Float = 0.5f) =
        SpatialMarkerRequest(VideoFrameReference(MediaTrack.BACK_CAMERA, time), VideoPoint(x, y))

    @Test fun historyEvictsByBothAgeAndCount() {
        val history = PoseHistory(maxAgeNs = 100, maxFrames = 2)
        listOf(10L, 20L, 30L).forEach { assertTrue(history.record(frame(it))) }
        assertEquals(2, history.size)
        assertNull(history.find(request(10).frame))
        history.record(frame(130))
        assertNotNull(history.find(request(30).frame)) // Inclusive age boundary.
        history.record(frame(131))
        assertNull(history.find(request(30).frame))
        history.clear()
        assertEquals(0, history.size)
    }

    @Test fun duplicateAndOutOfOrderFramesCannotReplacePublishedMetadata() {
        val history = PoseHistory()
        val original = frame(100)
        assertTrue(history.record(original))
        assertFalse(history.record(frame(100, WorldPose(Vec3(99f, 0f, 0f)))))
        assertFalse(history.record(frame(99)))
        assertSame(original, history.find(request(100).frame))
        assertNull(history.find(request(101).frame))
        assertNull(history.find(VideoFrameReference(MediaTrack.FRONT_CAMERA, 100)))
    }

    @Test fun resolvesDepthAgainstHistoricalPoseEvenAfterCameraMoves() {
        val history = PoseHistory()
        history.record(frame(10, WorldPose(Vec3(1f, 2f, 3f)), depth = DepthSnapshot(1, 1, shortArrayOf(2000))))
        history.record(frame(20, WorldPose(Vec3(100f, 100f, 100f)), depth = DepthSnapshot(1, 1, shortArrayOf(1000))))
        val result = SpatialResolver(history).resolve(request(10)) as SpatialResolution.Resolved
        assertEquals(Vec3(1f, 2f, 1f), result.pose.position)
        assertEquals(SurfaceSource.DEPTH, result.source)
    }

    @Test fun projectionUsesAxialDepthAndCameraYUp() {
        val history = PoseHistory()
        history.record(frame(1, depth = DepthSnapshot(1, 1, shortArrayOf(2000))))
        val result = SpatialResolver(history).resolve(request(1, 1f, 0f)) as SpatialResolution.Resolved
        assertEquals(Vec3(2f, 1.5f, -2f), result.pose.position)
    }

    @Test fun rotationTransformsCameraRayToWorld() {
        val q = sqrt(0.5f)
        val history = PoseHistory()
        history.record(frame(1, WorldPose(Vec3(0f, 0f, 0f), Rotation(0f, q, 0f, q)),
            depth = DepthSnapshot(1, 1, shortArrayOf(2000))))
        val result = SpatialResolver(history).resolve(request(1)) as SpatialResolution.Resolved
        assertEquals(-2f, result.pose.position.x, 0.0001f)
        assertEquals(0f, result.pose.position.z, 0.0001f)
    }

    private fun wall(z: Float, extent: Float = 2f): PlaneSnapshot {
        val q = sqrt(0.5f)
        return PlaneSnapshot(WorldPose(Vec3(0f, 0f, z), Rotation(q, 0f, 0f, q)), listOf(
            Vec3(-extent, 0f, -extent), Vec3(extent, 0f, -extent),
            Vec3(extent, 0f, extent), Vec3(-extent, 0f, extent)))
    }

    @Test fun depthUnsupportedOrHoleFallsBackToNearestHistoricalPlane() {
        listOf(null, DepthSnapshot(1, 1, shortArrayOf(0))).forEach { depth ->
            val history = PoseHistory()
            history.record(frame(1, depth = depth, planes = listOf(wall(-4f), wall(-2f))))
            val result = SpatialResolver(history).resolve(request(1)) as SpatialResolution.Resolved
            assertEquals(SurfaceSource.PLANE, result.source)
            assertEquals(-2f, result.pose.position.z, 0.0001f)
        }
    }

    @Test fun cannotHitOutsidePolygonBehindCameraOrParallelPlane() {
        val history = PoseHistory()
        history.record(frame(1, planes = listOf(wall(-2f, 0.1f))))
        assertEquals(SpatialResolution.Rejected(SpatialRejection.SURFACE_MISSING), SpatialResolver(history).resolve(request(1, 1f)))
        assertNull(wall(2f).intersect(Vec3(0f, 0f, 0f), Vec3(0f, 0f, -1f)))
        assertNull(wall(-2f).intersect(Vec3(0f, 0f, 0f), Vec3(1f, 0f, 0f)))
    }

    @Test fun rejectsMissingFramesWrongTracksLostTrackingAndLongRange() {
        val history = PoseHistory()
        history.record(frame(1, tracking = ArTracking.PAUSED))
        history.record(frame(2, depth = DepthSnapshot(1, 1, shortArrayOf(9000))))
        history.record(frame(3, planes = listOf(wall(-9f))))
        val resolver = SpatialResolver(history)
        fun rejected(time: Long, reason: SpatialRejection) = assertEquals(SpatialResolution.Rejected(reason), resolver.resolve(request(time)))
        rejected(4, SpatialRejection.FRAME_MISSING)
        rejected(1, SpatialRejection.TRACKING_UNAVAILABLE)
        rejected(2, SpatialRejection.TOO_FAR)
        rejected(3, SpatialRejection.TOO_FAR)
        assertEquals(SpatialResolution.Rejected(SpatialRejection.WRONG_TRACK), resolver.resolve(
            SpatialMarkerRequest(VideoFrameReference(MediaTrack.SCREEN, 2), VideoPoint(0.5f, 0.5f))))
    }

    @Test fun snapshotsDefensivelyCopyMutableInputsAndUseUnsignedDepth() {
        val values = shortArrayOf(1000, 2000, 3000, 40000.toShort())
        val depth = DepthSnapshot(2, 2, values)
        values.fill(0)
        assertEquals(40f, depth.metresAt(VideoPoint(1f, 1f))!!, 0f)
        assertEquals(1f, depth.metresAt(VideoPoint(0f, 0f))!!, 0f)
        val polygon = mutableListOf(Vec3(0f, 0f, 0f), Vec3(1f, 0f, 0f), Vec3(0f, 0f, 1f))
        val plane = PlaneSnapshot(WorldPose(Vec3(0f, 0f, 0f)), polygon)
        polygon.clear()
        assertEquals(3, plane.polygon.size)
        assertThrows(UnsupportedOperationException::class.java) { (plane.polygon as MutableList).clear() }
    }

    @Test fun invalidOrUnboundedGeometryIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { Vec3(Float.NaN, 0f, 0f) }
        assertThrows(IllegalArgumentException::class.java) { Rotation(0f, 0f, 0f, 0f) }
        assertThrows(IllegalArgumentException::class.java) { CameraIntrinsics(1, 1, 0f, 1f, 0f, 0f) }
        assertThrows(IllegalArgumentException::class.java) { DepthSnapshot(161, 1, ShortArray(161)) }
        assertThrows(IllegalArgumentException::class.java) { PoseHistory(maxFrames = 301) }
        assertThrows(IllegalArgumentException::class.java) { frame(0) }
    }
}
