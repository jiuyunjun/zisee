package com.lazydoglab.zisee.ar

import com.lazydoglab.zisee.ar.render.MarkerProjection
import com.lazydoglab.zisee.ar.session.MarkerKind
import com.lazydoglab.zisee.ar.session.SpatialMarker
import com.lazydoglab.zisee.ar.spatial.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class MarkerProjectionTest {
    private val intrinsics = CameraIntrinsics(640, 480, 320f, 320f, 320f, 240f)
    private fun marker(position: Vec3, tracking: ArTracking = ArTracking.TRACKING) =
        SpatialMarker(UUID.randomUUID(), MarkerKind.PIN, WorldPose(position), tracking)

    @Test fun `projects visible world anchors through the current camera pose`() {
        val centre = MarkerProjection.project(marker(Vec3(0f, 0f, -2f)), WorldPose(Vec3(0f, 0f, 0f)), intrinsics)
        assertEquals(.5f, centre!!.point.x, .0001f)
        assertEquals(.5f, centre.point.y, .0001f)
        val shifted = MarkerProjection.project(marker(Vec3(1f, 0f, -2f)), WorldPose(Vec3(1f, 0f, 0f)), intrinsics)
        assertEquals(.5f, shifted!!.point.x, .0001f)
    }

    @Test fun `rejects anchors behind outside or not tracking`() {
        val camera = WorldPose(Vec3(0f, 0f, 0f))
        assertNull(MarkerProjection.project(marker(Vec3(0f, 0f, 1f)), camera, intrinsics))
        assertNull(MarkerProjection.project(marker(Vec3(20f, 0f, -1f)), camera, intrinsics))
        assertNull(MarkerProjection.project(marker(Vec3(0f, 0f, -1f), ArTracking.PAUSED), camera, intrinsics))
    }
}
