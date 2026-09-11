package com.lazydoglab.zisee.ar.session

import com.lazydoglab.zisee.ar.annotation.*
import com.lazydoglab.zisee.ar.spatial.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class ArSessionState { IDLE, STARTING, SCANNING, TRACKING, TRACKING_LOST, PAUSED, FAILED, CLOSED }
enum class MarkerKind { PIN, ARROW, CIRCLE }
enum class ArEvent { STARTED, TRACKING_CHANGED, FRAME_REJECTED, MARKER_CREATED, MARKER_REJECTED, NATIVE_FAILURE, CLEANUP_FAILED, CLOSED }

interface LocalAnchor {
    val pose: WorldPose
    val tracking: ArTracking
    fun detach()
}

/** Owned by one controller. All calls execute on the same non-main GL thread.
 * The camera lease is acquired before resume and released only after pause/close complete.
 */
interface ArBackend : AutoCloseable {
    val depthSupported: Boolean
    fun resume()
    fun pause()
    fun capture(): HistoricalFrame?
    fun createAnchor(pose: WorldPose): LocalAnchor
}

data class SpatialMarker(val id: UUID, val kind: MarkerKind, val pose: WorldPose, val tracking: ArTracking,
    val displayNumber: Int = 0)
sealed interface MarkerResult {
    data class Created(val marker: SpatialMarker, val source: SurfaceSource) : MarkerResult
    data class Rejected(val reason: SpatialRejection) : MarkerResult
}

/** One instance per field-side AR session. Construct and invoke on its GL worker thread.
 * UI observes immutable StateFlow only; DataChannel callbacks must dispatch to the owner thread.
 * No coordinates, peer IDs, images or exception messages are passed to the diagnostic callback.
 */
class ArSessionController(
    val sessionId: UUID,
    private val backendFactory: () -> ArBackend,
    private val onEvent: (ArEvent) -> Unit = {},
    private val history: PoseHistory = PoseHistory(),
    private val maxAnchors: Int = 32,
) : AutoCloseable {
    private val owner = Thread.currentThread()
    private val mutableState = MutableStateFlow(ArSessionState.IDLE)
    val state: StateFlow<ArSessionState> = mutableState.asStateFlow()
    private var backend: ArBackend? = null
    private val anchors = LinkedHashMap<UUID, Pair<MarkerKind, LocalAnchor>>()
    private val annotations = AnnotationLedger(maxAnchors)
    private var lastTimestampNs = 0L
    private val resolver = SpatialResolver(history)
    init { require(maxAnchors in 1..128) }
    private fun checkThread() = check(Thread.currentThread() === owner) { "AR session accessed outside owner thread" }
    private fun transition(next: ArSessionState) { mutableState.value = next }

    fun start(): Boolean {
        checkThread()
        check(state.value == ArSessionState.IDLE)
        transition(ArSessionState.STARTING)
        return try {
            backend = backendFactory()
            requireNotNull(backend).resume()
            transition(ArSessionState.SCANNING)
            onEvent(ArEvent.STARTED)
            true
        } catch (_: Exception) {
            fail()
            false
        }
    }

    /** Called once per GL render tick. Returned reference may accompany the exact encoded frame.
     * Do not publish ARCore timestamps against an independently captured CameraX/WebRTC frame.
     */
    fun capture(): HistoricalFrame? {
        checkThread()
        if (!active()) return null
        return try {
            val frame = requireNotNull(backend).capture() ?: return null
            if (frame.frame.timestampNs <= lastTimestampNs) {
                onEvent(ArEvent.FRAME_REJECTED)
                return null
            }
            lastTimestampNs = frame.frame.timestampNs
            val next = when (frame.tracking) {
                ArTracking.TRACKING -> ArSessionState.TRACKING
                else -> ArSessionState.TRACKING_LOST
            }
            if (next != state.value) { transition(next); onEvent(ArEvent.TRACKING_CHANGED) }
            if (history.record(frame)) frame else { onEvent(ArEvent.FRAME_REJECTED); null }
        } catch (_: Exception) { fail(); null }
    }

    fun createMarker(epoch: UUID, id: UUID, kind: MarkerKind, request: SpatialMarkerRequest,
        author: AnnotationAuthor = AnnotationAuthor.FIELD): MarkerResult {
        checkThread()
        fun reject(reason: SpatialRejection): MarkerResult {
            onEvent(ArEvent.MARKER_REJECTED)
            return MarkerResult.Rejected(reason)
        }
        if (epoch != sessionId) return reject(SpatialRejection.WRONG_SESSION)
        if (!active()) return reject(SpatialRejection.INACTIVE)
        if (state.value != ArSessionState.TRACKING) return reject(SpatialRejection.TRACKING_UNAVAILABLE)
        annotations.rejectionFor(id)?.let { return reject(it) }
        return when (val result = resolver.resolve(request)) {
            is SpatialResolution.Rejected -> reject(result.reason)
            is SpatialResolution.Resolved -> try {
                val anchor = requireNotNull(backend).createAnchor(result.pose)
                anchors[id] = kind to anchor
                val pose = anchor.pose
                val tracking = anchor.tracking
                val record = annotations.create(id, author, AnnotationType.POINT,
                    PlacementResult.World(pose, result.evidence, request.frame),
                    if (tracking == ArTracking.TRACKING) PlacementState.ANCHORED else PlacementState.LOST)
                val marker = SpatialMarker(id, kind, pose, tracking, record.displayNumber)
                onEvent(ArEvent.MARKER_CREATED)
                MarkerResult.Created(marker, result.source)
            } catch (_: Exception) {
                // The handle may have been created before a native pose read failed.
                anchors.remove(id)?.second?.let { if (!detach(it)) fail() }
                onEvent(ArEvent.NATIVE_FAILURE)
                reject(SpatialRejection.NATIVE_FAILURE)
            }
        }
    }

    fun markers(): List<SpatialMarker> {
        checkThread()
        if (!active()) return emptyList()
        return try {
            val records = annotations.snapshot().associateBy { it.id }
            anchors.map { (id, entry) ->
                val pose = entry.second.pose
                val tracking = entry.second.tracking
                val record = requireNotNull(records[id])
                val placement = record.placement as PlacementResult.World
                annotations.update(id, placement.copy(pose = pose),
                    if (tracking == ArTracking.TRACKING) PlacementState.ANCHORED else PlacementState.LOST)
                SpatialMarker(id, entry.first, pose, tracking, record.displayNumber)
            }
        } catch (_: Exception) { fail(); emptyList() }
    }

    fun annotationSnapshot(): List<AnnotationRecord> { checkThread(); return annotations.snapshot() }

    fun removeMarker(epoch: UUID, id: UUID, actor: AnnotationAuthor = AnnotationAuthor.FIELD): Boolean {
        checkThread()
        if (epoch != sessionId || !annotations.canRemove(id, actor)) return false
        val anchor = anchors.remove(id)?.second ?: return false
        annotations.remove(id, actor)
        if (detach(anchor)) return true
        // A failed detach must not leave an untracked native resource in a running session.
        fail()
        return false
    }

    fun clearMarkers(epoch: UUID, actor: AnnotationAuthor = AnnotationAuthor.FIELD): Boolean {
        checkThread()
        if (epoch != sessionId || state.value == ArSessionState.CLOSED) return false
        var success = true
        annotations.clear(actor).forEach { id -> anchors.remove(id)?.second?.let { if (!detach(it)) success = false } }
        if (!success) fail()
        return success
    }

    fun pause() {
        checkThread()
        if (!active()) return
        // Resume must not resolve clicks against pre-pause world coordinates.
        history.clear()
        if (!clearMarkers(sessionId)) return
        try { requireNotNull(backend).pause(); transition(ArSessionState.PAUSED) }
        catch (_: Exception) { fail() }
    }

    fun resume(): Boolean {
        checkThread()
        if (state.value != ArSessionState.PAUSED) return false
        return try { requireNotNull(backend).resume(); transition(ArSessionState.SCANNING); true }
        catch (_: Exception) { fail(); false }
    }

    override fun close() {
        checkThread()
        if (state.value == ArSessionState.CLOSED) return
        cleanup()
        transition(ArSessionState.CLOSED)
        onEvent(ArEvent.CLOSED)
    }

    private fun active() = state.value in setOf(ArSessionState.SCANNING, ArSessionState.TRACKING, ArSessionState.TRACKING_LOST)
    private fun detach(anchor: LocalAnchor): Boolean = try { anchor.detach(); true }
        catch (_: Exception) { onEvent(ArEvent.CLEANUP_FAILED); false }
    private fun fail() { onEvent(ArEvent.NATIVE_FAILURE); cleanup(); transition(ArSessionState.FAILED) }
    private fun cleanup() {
        anchors.values.forEach { detach(it.second) }
        anchors.clear()
        annotations.clear(AnnotationAuthor.FIELD)
        history.clear()
        val closing = backend
        backend = null
        if (closing != null) {
            try { closing.pause() } catch (_: Exception) { onEvent(ArEvent.CLEANUP_FAILED) }
            try { closing.close() } catch (_: Exception) { onEvent(ArEvent.CLEANUP_FAILED) }
        }
    }
}
