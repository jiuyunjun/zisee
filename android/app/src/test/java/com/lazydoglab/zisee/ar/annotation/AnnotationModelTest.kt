package com.lazydoglab.zisee.ar.annotation

import com.lazydoglab.zisee.ar.spatial.SpatialRejection
import com.lazydoglab.zisee.ar.spatial.Vec3
import com.lazydoglab.zisee.ar.spatial.WorldPose
import com.lazydoglab.zisee.media.MediaTrack
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class AnnotationModelTest {
    @Test fun deletionAndClearNeverReuseDisplayNumbersOrIds() {
        val ledger = AnnotationLedger()
        val firstId = UUID.randomUUID()
        val first = ledger.create(firstId, AnnotationAuthor.GUIDE, AnnotationType.POINT, world(1), PlacementState.ANCHORED)

        assertTrue(ledger.remove(firstId, AnnotationAuthor.GUIDE))
        assertEquals(SpatialRejection.DUPLICATE_ID, ledger.rejectionFor(firstId))
        val second = ledger.create(UUID.randomUUID(), AnnotationAuthor.FIELD, AnnotationType.POINT, world(2), PlacementState.ANCHORED)
        ledger.clear(AnnotationAuthor.FIELD)
        val third = ledger.create(UUID.randomUUID(), AnnotationAuthor.GUIDE, AnnotationType.POINT, screen(3), PlacementState.SCREEN_LOCKED)

        assertEquals(listOf(1, 2, 3), listOf(first.displayNumber, second.displayNumber, third.displayNumber))
        assertTrue(first.revision < second.revision)
        assertTrue(second.revision < third.revision)
    }

    @Test fun guideMayOnlyRemoveItsOwnAnnotationsWhileFieldIsAuthoritative() {
        val ledger = AnnotationLedger()
        val fieldId = UUID.randomUUID()
        val guideId = UUID.randomUUID()
        ledger.create(fieldId, AnnotationAuthor.FIELD, AnnotationType.POINT, world(1), PlacementState.ANCHORED)
        ledger.create(guideId, AnnotationAuthor.GUIDE, AnnotationType.POINT, world(2), PlacementState.ANCHORED)

        assertFalse(ledger.remove(fieldId, AnnotationAuthor.GUIDE))
        assertEquals(listOf(guideId), ledger.clear(AnnotationAuthor.GUIDE))
        assertEquals(listOf(fieldId), ledger.snapshot().map { it.id })
        assertEquals(listOf(fieldId), ledger.clear(AnnotationAuthor.FIELD))
        assertTrue(ledger.snapshot().isEmpty())
    }

    @Test fun selectionIsOrthogonalToPlacementStateAndHasMonotonicRevisions() {
        val ledger = AnnotationLedger()
        val lostId = UUID.randomUUID()
        val screenId = UUID.randomUUID()
        ledger.create(lostId, AnnotationAuthor.FIELD, AnnotationType.POINT, world(1), PlacementState.LOST)
        ledger.create(screenId, AnnotationAuthor.GUIDE, AnnotationType.POINT, screen(2), PlacementState.SCREEN_LOCKED)

        assertTrue(ledger.select(lostId))
        val selectedLost = ledger.snapshot().associateBy { it.id }
        assertTrue(selectedLost.getValue(lostId).selected)
        assertEquals(PlacementState.LOST, selectedLost.getValue(lostId).state)
        assertEquals(PlacementState.SCREEN_LOCKED, selectedLost.getValue(screenId).state)
        val afterFirstSelection = ledger.revision

        assertTrue(ledger.select(screenId))
        val selectedScreen = ledger.snapshot().associateBy { it.id }
        assertFalse(selectedScreen.getValue(lostId).selected)
        assertTrue(selectedScreen.getValue(screenId).selected)
        assertEquals(PlacementState.LOST, selectedScreen.getValue(lostId).state)
        assertEquals(PlacementState.SCREEN_LOCKED, selectedScreen.getValue(screenId).state)
        assertTrue(ledger.revision > afterFirstSelection)
        assertFalse(ledger.select(UUID.randomUUID()))
    }

    @Test fun screenAndWorldPlacementCannotBeRepresentedByTheWrongState() {
        assertThrows(IllegalArgumentException::class.java) {
            record(world(1), PlacementState.SCREEN_LOCKED)
        }
        assertThrows(IllegalArgumentException::class.java) {
            record(screen(1), PlacementState.ANCHORED)
        }
        record(world(1), PlacementState.STABILIZING)
        record(screen(1), PlacementState.SCREEN_LOCKED)
    }

    @Test fun annotationAndTransportBudgetsAreExplicitAndLedgerRejectsOverflow() {
        assertEquals(32, AnnotationBudget.MAX_ANNOTATIONS)
        assertEquals(512, AnnotationBudget.MAX_IDS)
        assertEquals(512, AnnotationBudget.MAX_STROKE_POINTS)
        assertEquals(8192, AnnotationBudget.MAX_TOTAL_POINTS)
        assertEquals(16, AnnotationBudget.MAX_BATCH_POINTS)
        assertEquals(4096, AnnotationBudget.MAX_PACKET_BYTES)
        assertEquals(50_000_000L, AnnotationBudget.STROKE_BATCH_INTERVAL_NS)
        assertEquals(1_500_000_000L, AnnotationBudget.SCREEN_TTL_NS)
        assertEquals(100_000_000L, AnnotationBudget.MAX_PREDICTION_NS)
        assertEquals(0.05f, AnnotationBudget.MAX_PREDICTION_METRES)

        val ledger = AnnotationLedger(maxAnnotations = 1)
        ledger.create(UUID.randomUUID(), AnnotationAuthor.FIELD, AnnotationType.POINT, world(1), PlacementState.ANCHORED)
        assertEquals(SpatialRejection.LIMIT_REACHED, ledger.rejectionFor(UUID.randomUUID()))
        assertThrows(IllegalArgumentException::class.java) { AnnotationLedger(maxAnnotations = 0) }
        assertThrows(IllegalArgumentException::class.java) { AnnotationLedger(maxAnnotations = 129) }
    }

    @Test fun ledgerIsConfinedToItsCreatingThread() {
        val ledger = AnnotationLedger()
        val thrown = arrayOfNulls<Throwable>(1)
        val thread = Thread { runCatching { ledger.snapshot() }.onFailure { thrown[0] = it } }
        thread.start()
        thread.join()
        assertTrue(thrown[0] is IllegalStateException)
    }

    private fun record(placement: PlacementResult, state: PlacementState) = AnnotationRecord(
        UUID.randomUUID(), AnnotationAuthor.FIELD, 1, 1, AnnotationType.POINT, placement, state,
    )

    private fun world(timestampNs: Long) = PlacementResult.World(
        WorldPose(Vec3(0f, 0f, -1f)),
        SurfaceEvidence(PlacementMethod.DEPTH, 1f),
        frame(timestampNs),
    )

    private fun screen(timestampNs: Long) = PlacementResult.Screen(
        VideoPoint(0.5f, 0.5f), frame(timestampNs), timestampNs + AnnotationBudget.SCREEN_TTL_NS,
        SpatialRejection.TRACKING_UNAVAILABLE,
    )

    private fun frame(timestampNs: Long) = VideoFrameReference(MediaTrack.BACK_CAMERA, timestampNs)
}
