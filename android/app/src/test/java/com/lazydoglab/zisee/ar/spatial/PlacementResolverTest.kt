package com.lazydoglab.zisee.ar.spatial

import com.lazydoglab.zisee.ar.annotation.*
import com.lazydoglab.zisee.media.MediaTrack
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

class PlacementResolverTest {
    @Test fun planeEdgeCanBecomeConfirmedPolygonOnTheSameSurface() {
        val current = world(1, 0f, 42, 0.55f).let { it.copy(evidence = it.evidence.copy(
            method = PlacementMethod.LOCAL_SURFACE, strictPolygonHit = false)) }
        val refiner = PoseRefiner()
        var result = current
        for (time in 2L..5L) result = refiner.refine(result, world(time, 0f, 42, 0.85f), 33_000_000, true)
        assertEquals(PlacementMethod.PLANE, result.evidence.method)
        assertTrue(result.evidence.confidence >= 0.7f)
    }
    private val intrinsics = CameraIntrinsics(640, 480, 320f, 320f, 320f, 240f)

    @Test fun depthSurfaceRequiresContinuityToFillAHoleAndProducesAUnitNormal() {
        val flat = DepthSnapshot(3, 3, ShortArray(9) { 1000 })
        val surface = requireNotNull(flat.surfaceAt(VideoPoint(0.5f, 0.5f), intrinsics))
        assertEquals(1f, surface.metres, 0f)
        assertEquals(0.9f, surface.confidence, 0f)
        assertEquals(1f, requireNotNull(surface.cameraNormal).length(), 0.001f)

        val smallHole = ShortArray(9) { 1000 }.also { it[4] = 0 }
        assertEquals(0.5f, requireNotNull(DepthSnapshot(3, 3, smallHole)
            .surfaceAt(VideoPoint(0.5f, 0.5f), intrinsics)).confidence, 0f)
        val discontinuity = shortArrayOf(1000, 1000, 1000, 1000, 0, 3000, 3000, 3000, 3000)
        assertNull(DepthSnapshot(3, 3, discontinuity).surfaceAt(VideoPoint(0.5f, 0.5f), intrinsics))
    }

    @Test fun resolverKeepsDepthPlaneFeatureEstimateAndScreenResultsDistinct() {
        val depth = resolve(frame(1, depth = DepthSnapshot(1, 1, shortArrayOf(1000))))
        assertEquals(PlacementMethod.DEPTH, (depth as PlacementResult.World).evidence.method)

        val plane = resolve(frame(2, planes = listOf(wall(-2f, id = 7)))) as PlacementResult.World
        assertEquals(PlacementMethod.PLANE, plane.evidence.method)
        assertEquals(7L, plane.evidence.surfaceId)
        assertTrue(plane.evidence.strictPolygonHit)

        val feature = resolve(frame(3, features = listOf(
            FeatureSnapshot(8, Vec3(0.04f, 0f, -1f), 0.9f),
            FeatureSnapshot(9, Vec3(0.01f, 0f, -1f), 0.8f),
        ))) as PlacementResult.World
        assertEquals(PlacementMethod.FEATURE_POINT, feature.evidence.method)
        assertEquals(9L, feature.evidence.surfaceId)
        assertNull(feature.evidence.normal)

        val estimate = resolve(frame(4)) as PlacementResult.World
        assertEquals(PlacementMethod.HISTORICAL_RAY_ESTIMATE, estimate.evidence.method)
        assertEquals(-1.5f, estimate.pose.position.z, 0.001f)

        val history = PoseHistory().also { it.record(frame(5)) }
        val screen = PlacementResolver(history).resolve(request(5), 100, allowEstimate = false)
            as PlacementResult.Screen
        assertEquals(SpatialRejection.SURFACE_MISSING, screen.reason)
        assertEquals(100 + AnnotationBudget.SCREEN_TTL_NS, screen.expiresAtNs)
    }

    @Test fun resolverNeverSubstitutesAnotherFrameOrCurrentTrackingState() {
        val history = PoseHistory()
        history.record(frame(10, tracking = ArTracking.PAUSED))
        history.record(frame(20, depth = DepthSnapshot(1, 1, shortArrayOf(1000))))
        val resolver = PlacementResolver(history)

        assertEquals(SpatialRejection.TRACKING_UNAVAILABLE,
            (resolver.resolve(request(10), 0) as PlacementResult.Screen).reason)
        assertEquals(SpatialRejection.FRAME_MISSING,
            (resolver.resolve(request(11), 0) as PlacementResult.Screen).reason)
        val wrongTrack = SpatialMarkerRequest(VideoFrameReference(MediaTrack.SCREEN, 20), VideoPoint(0.5f, 0.5f))
        assertEquals(SpatialRejection.WRONG_TRACK,
            (resolver.resolve(wrongTrack, 0) as PlacementResult.Screen).reason)
    }

    @Test fun poseRefinerRequiresThreeCompatibleConfirmationsAndUsesElapsedTime() {
        val current = world(1, 0f, 42, 0.5f)
        val candidate = world(2, 0.04f, 42, 0.8f)
        val refiner = PoseRefiner(timeConstantSeconds = 0.2f)

        assertSame(current, refiner.refine(current, candidate, 100_000_000, worldContinuous = true))
        assertSame(current, refiner.refine(current, candidate.copy(frame = request(3).frame), 100_000_000, worldContinuous = true))
        val refined = refiner.refine(current, candidate.copy(frame = request(4).frame), 100_000_000, worldContinuous = true)
        assertTrue(refined.pose.position.x in 0.015f..0.016f)
        assertEquals(current.evidence.confidence, refined.evidence.confidence, 0f)
        assertEquals(current.pose.rotation, refined.pose.rotation)
        var result = refined
        for (time in 5L..20L) result = refiner.refine(result, candidate.copy(frame = request(time).frame), 100_000_000, true)
        assertEquals(0.04f, result.pose.position.x, 0.001f)
        assertEquals(candidate.evidence.confidence, result.evidence.confidence, 0f)
    }

    @Test fun repeatingOneFrameCannotSatisfyRefinementHysteresis() {
        val current = world(1, 0f, 42, 0.5f)
        val candidate = world(2, 0.04f, 42, 0.8f)
        val refiner = PoseRefiner()
        repeat(20) { assertSame(current, refiner.refine(current, candidate, 33_000_000, true)) }
    }

    @Test fun poseRefinerRejectsIdentityDiscontinuityWeakEvidenceAndLargeJumps() {
        val current = world(1, 0f, 42, 0.5f)
        val refiner = PoseRefiner()
        val incompatible = listOf(
            world(2, 0.01f, 43, 0.9f),
            world(2, 0.01f, 42, 0.54f),
            world(2, 0.06f, 42, 0.9f),
        )
        incompatible.forEach { assertSame(current, refiner.refine(current, it, 50_000_000, worldContinuous = true)) }
        assertSame(current, refiner.refine(current, world(2, 0.01f, 42, 0.9f), 50_000_000, worldContinuous = false))
        assertSame(current, refiner.refine(current, world(1, 0.01f, 42, 0.9f), 50_000_000, worldContinuous = true))
    }

    private fun resolve(frame: HistoricalFrame): PlacementResult {
        val history = PoseHistory().also { it.record(frame) }
        return PlacementResolver(history).resolve(request(frame.frame.timestampNs), 0)
    }

    private fun frame(time: Long, tracking: ArTracking = ArTracking.TRACKING,
        depth: DepthSnapshot? = null, planes: List<PlaneSnapshot> = emptyList(),
        features: List<FeatureSnapshot> = emptyList()) = HistoricalFrame(
        VideoFrameReference(MediaTrack.BACK_CAMERA, time), WorldPose(Vec3(0f, 0f, 0f)),
        intrinsics, tracking, depth, planes, features)

    private fun request(time: Long) = SpatialMarkerRequest(
        VideoFrameReference(MediaTrack.BACK_CAMERA, time), VideoPoint(0.5f, 0.5f))

    private fun wall(z: Float, id: Long): PlaneSnapshot {
        val q = sqrt(0.5f)
        return PlaneSnapshot(WorldPose(Vec3(0f, 0f, z), Rotation(q, 0f, 0f, q)), listOf(
            Vec3(-1f, 0f, -1f), Vec3(1f, 0f, -1f), Vec3(1f, 0f, 1f), Vec3(-1f, 0f, 1f)), id)
    }

    private fun world(time: Long, x: Float, surfaceId: Long, confidence: Float) = PlacementResult.World(
        WorldPose(Vec3(x, 0f, -1f)),
        SurfaceEvidence(PlacementMethod.PLANE, confidence, Vec3(0f, 0f, 1f), surfaceId, true),
        VideoFrameReference(MediaTrack.BACK_CAMERA, time),
    )
}
