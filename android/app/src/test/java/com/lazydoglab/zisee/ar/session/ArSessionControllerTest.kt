package com.lazydoglab.zisee.ar.session

import com.lazydoglab.zisee.ar.annotation.*
import com.lazydoglab.zisee.ar.spatial.*
import com.lazydoglab.zisee.media.MediaTrack
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

class ArSessionControllerTest {
    private val epoch = UUID.randomUUID()
    private val events = mutableListOf<ArEvent>()
    private val backend = FakeBackend()
    private fun controller(max: Int = 32) = ArSessionController(epoch, { backend }, events::add, maxAnchors = max)
    private fun request(time: Long = 1) = SpatialMarkerRequest(VideoFrameReference(MediaTrack.BACK_CAMERA, time), VideoPoint(0.5f, 0.5f))
    private fun create(controller: ArSessionController, id: UUID = UUID.randomUUID(), time: Long = 1) =
        controller.createMarker(epoch, id, MarkerKind.PIN, request(time))

    @Test fun tracksLifecycleAndDetachesAnchorsBeforeNativeClose() {
        val controller = controller()
        assertEquals(ArSessionState.IDLE, controller.state.value)
        assertTrue(controller.start())
        assertEquals(ArSessionState.SCANNING, controller.state.value)
        assertNotNull(controller.capture())
        assertEquals(ArSessionState.TRACKING, controller.state.value)
        val result = create(controller) as MarkerResult.Created
        assertEquals(Vec3(0f, 0f, -1f), result.marker.pose.position)
        backend.created.single().position = WorldPose(Vec3(1f, 0f, -1f))
        assertEquals(Vec3(1f, 0f, -1f), controller.markers().single().pose.position)
        controller.close()
        controller.close()
        assertEquals(listOf("resume", "detach", "pause", "close"), backend.actions)
        assertEquals(ArSessionState.CLOSED, controller.state.value)
        assertNull(controller.capture())
        assertFalse(controller.resume())
        assertEquals(MarkerResult.Rejected(SpatialRejection.INACTIVE), create(controller))
    }

    @Test fun wrongEpochAndMissingFramesNeverReachNativeAnchorCreation() {
        val controller = controller()
        controller.start(); controller.capture()
        assertEquals(MarkerResult.Rejected(SpatialRejection.WRONG_SESSION),
            controller.createMarker(UUID.randomUUID(), UUID.randomUUID(), MarkerKind.PIN, request()))
        assertEquals(MarkerResult.Rejected(SpatialRejection.FRAME_MISSING), create(controller, time = 2))
        assertTrue(backend.created.isEmpty())
        assertFalse(controller.clearMarkers(UUID.randomUUID()))
        controller.close()
    }

    @Test fun trackingLossRejectsHistoricalClickUntilTrackingReturns() {
        val controller = controller()
        controller.start(); controller.capture()
        backend.next = snapshot(2, ArTracking.PAUSED)
        controller.capture()
        assertEquals(ArSessionState.TRACKING_LOST, controller.state.value)
        assertEquals(MarkerResult.Rejected(SpatialRejection.TRACKING_UNAVAILABLE), create(controller))
        backend.next = snapshot(3)
        controller.capture()
        assertTrue(create(controller) is MarkerResult.Created)
        controller.close()
    }

    @Test fun pauseClearsHistoryAndAnchorsAndRejectsPrePauseDuplicateFrame() {
        val controller = controller()
        controller.start(); controller.capture(); create(controller)
        controller.pause()
        assertEquals(ArSessionState.PAUSED, controller.state.value)
        assertEquals(1, backend.created.single().detaches)
        assertTrue(controller.resume())
        assertNull(controller.capture()) // Native repeat of frame 1 after resume.
        backend.next = snapshot(2)
        controller.capture()
        assertEquals(MarkerResult.Rejected(SpatialRejection.FRAME_MISSING), create(controller))
        assertTrue(create(controller, time = 2) is MarkerResult.Created)
        controller.close()
    }

    @Test fun boundsAnchorsAndRemembersRemovedIdsAgainstReplay() {
        val controller = controller(max = 1)
        val id = UUID.randomUUID()
        controller.start(); controller.capture()
        assertTrue(create(controller, id) is MarkerResult.Created)
        assertEquals(MarkerResult.Rejected(SpatialRejection.DUPLICATE_ID), create(controller, id))
        assertEquals(MarkerResult.Rejected(SpatialRejection.LIMIT_REACHED), create(controller))
        assertFalse(controller.removeMarker(UUID.randomUUID(), id))
        assertTrue(controller.removeMarker(epoch, id))
        assertFalse(controller.removeMarker(epoch, id))
        assertEquals(MarkerResult.Rejected(SpatialRejection.DUPLICATE_ID), create(controller, id))
        assertTrue(create(controller) is MarkerResult.Created)
        assertTrue(controller.clearMarkers(epoch))
        assertTrue(controller.markers().isEmpty())
        controller.close()
    }

    @Test fun legacyMarkerApiUsesStableNonReusedDisplayNumbers() {
        val controller = controller()
        val firstId = UUID.randomUUID()
        controller.start(); controller.capture()
        val first = create(controller, firstId) as MarkerResult.Created
        assertEquals(1, first.marker.displayNumber)
        assertTrue(controller.removeMarker(epoch, firstId))
        val second = create(controller) as MarkerResult.Created
        assertEquals(2, second.marker.displayNumber)
        assertEquals(2, controller.markers().single().displayNumber)
        assertEquals(2, controller.annotationSnapshot().single().displayNumber)
        controller.close()
    }

    @Test fun guideCannotDeleteFieldMarkerButFieldMayDeleteGuideMarker() {
        val controller = controller()
        controller.start(); controller.capture()
        val fieldId = UUID.randomUUID()
        val guideId = UUID.randomUUID()
        assertTrue(controller.createMarker(epoch, fieldId, MarkerKind.PIN, request(), AnnotationAuthor.FIELD) is MarkerResult.Created)
        assertTrue(controller.createMarker(epoch, guideId, MarkerKind.PIN, request(), AnnotationAuthor.GUIDE) is MarkerResult.Created)

        assertFalse(controller.removeMarker(epoch, fieldId, AnnotationAuthor.GUIDE))
        assertTrue(controller.removeMarker(epoch, guideId, AnnotationAuthor.GUIDE))
        assertEquals(listOf(fieldId), controller.markers().map { it.id })
        assertTrue(controller.clearMarkers(epoch, AnnotationAuthor.FIELD))
        assertTrue(controller.annotationSnapshot().isEmpty())
        assertEquals(2, backend.created.count { it.detaches == 1 })
        controller.close()
    }

    @Test fun pauseAndCloseClearLedgerAlongsideNativeAnchors() {
        val controller = controller()
        controller.start(); controller.capture()
        create(controller)
        assertEquals(1, controller.annotationSnapshot().size)

        controller.pause()
        assertTrue(controller.annotationSnapshot().isEmpty())
        assertTrue(controller.resume())
        backend.next = snapshot(2)
        controller.capture()
        val afterResume = create(controller, time = 2) as MarkerResult.Created
        assertEquals(2, afterResume.marker.displayNumber)
        controller.close()
        assertTrue(controller.annotationSnapshot().isEmpty())
        assertEquals(ArSessionState.CLOSED, controller.state.value)
    }

    @Test fun cameraResumeFailureClosesAcquiredBackend() {
        backend.failResume = true
        val controller = controller()
        assertFalse(controller.start())
        assertEquals(ArSessionState.FAILED, controller.state.value)
        assertEquals(listOf("resume", "pause", "close"), backend.actions)
        assertTrue(ArEvent.NATIVE_FAILURE in events)
        controller.close()
        assertEquals(1, backend.actions.count { it == "close" })
    }

    @Test fun factoryFailureIsExplicitWithoutCallingAnUnownedBackend() {
        val controller = ArSessionController(epoch, { error("unavailable") }, events::add)
        assertFalse(controller.start())
        assertEquals(ArSessionState.FAILED, controller.state.value)
        assertTrue(ArEvent.NATIVE_FAILURE in events)
        controller.close()
    }

    @Test fun nativeCaptureFailureDetachesAndCloses() {
        val controller = controller()
        controller.start(); controller.capture(); create(controller)
        backend.failCapture = true
        assertNull(controller.capture())
        assertEquals(ArSessionState.FAILED, controller.state.value)
        assertEquals(1, backend.created.single().detaches)
        assertEquals("close", backend.actions.last())
        controller.close()
    }

    @Test fun nativeCreateFailureDoesNotFailBasicSession() {
        val controller = controller()
        controller.start(); controller.capture()
        backend.failCreate = true
        assertEquals(MarkerResult.Rejected(SpatialRejection.NATIVE_FAILURE), create(controller))
        assertEquals(ArSessionState.TRACKING, controller.state.value)
        controller.close()
    }

    @Test fun cleanupAttemptsAllResourcesDespiteDetachAndPauseFailures() {
        val controller = controller()
        controller.start(); controller.capture(); create(controller); create(controller)
        backend.created.first().failDetach = true
        backend.failPause = true
        controller.close()
        assertEquals(2, backend.actions.count { it == "detach" })
        assertEquals("close", backend.actions.last())
        assertEquals(ArSessionState.CLOSED, controller.state.value)
        assertTrue(ArEvent.CLEANUP_FAILED in events)
    }

    @Test fun crossThreadUseFailsBeforeTouchingNativeState() {
        val controller = controller()
        val error = AtomicReference<Throwable>()
        val thread = Thread { try { controller.start() } catch (e: Throwable) { error.set(e) } }
        thread.start(); thread.join()
        assertTrue(error.get() is IllegalStateException)
        assertTrue(backend.actions.isEmpty())
        controller.close()
    }

    @Test fun detachFailureDuringRemoveOrPauseClosesSessionInsteadOfLosingNativeHandle() {
        for (remove in listOf(true, false)) {
            val native = FakeBackend()
            val controller = ArSessionController(epoch, { native }, events::add)
            controller.start(); controller.capture()
            val id = UUID.randomUUID()
            create(controller, id)
            native.created.single().failDetach = true
            if (remove) assertFalse(controller.removeMarker(epoch, id)) else controller.pause()
            assertEquals(ArSessionState.FAILED, controller.state.value)
            assertEquals("close", native.actions.last())
            assertFalse(controller.resume())
            controller.close()
        }
    }

    private class FakeBackend : ArBackend {
        override val depthSupported = true
        val actions = mutableListOf<String>()
        val created = mutableListOf<FakeAnchor>()
        var next = snapshot(1)
        var failResume = false
        var failPause = false
        var failCapture = false
        var failCreate = false
        override fun resume() { actions.add("resume"); check(!failResume) }
        override fun pause() { actions.add("pause"); check(!failPause) }
        override fun capture(): HistoricalFrame { check(!failCapture); return next }
        override fun createAnchor(pose: WorldPose): LocalAnchor {
            check(!failCreate)
            return FakeAnchor(pose, actions).also { created.add(it) }
        }
        override fun close() { actions.add("close") }
    }
    private class FakeAnchor(var position: WorldPose, private val actions: MutableList<String>) : LocalAnchor {
        var detaches = 0
        var failDetach = false
        override val pose: WorldPose get() = position
        override val tracking = ArTracking.TRACKING
        override fun detach() { actions.add("detach"); detaches++; check(!failDetach) }
    }
    companion object {
        private fun snapshot(time: Long, tracking: ArTracking = ArTracking.TRACKING) = HistoricalFrame(
            VideoFrameReference(MediaTrack.BACK_CAMERA, time), WorldPose(Vec3(0f, 0f, 0f)),
            CameraIntrinsics(640, 480, 320f, 320f, 320f, 240f), tracking,
            DepthSnapshot(1, 1, shortArrayOf(1000)))
    }
}
