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
data class PlacementDiagnostic(val author: AnnotationAuthor, val method: PlacementMethod? = null,
    val rejection: SpatialRejection? = null) {
    init { require((method == null) != (rejection == null)) }
    fun encode(): String = "${author.name}:${method?.name ?: rejection!!.name}"
}

interface LocalAnchor {
    val pose: WorldPose
    val tracking: ArTracking
    val placementConfidence: Float? get() = null
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
    /** Optional current-frame-only path. Historical remote requests must never replay screen XY. */
    fun createInstantAnchor(request: SpatialMarkerRequest): LocalAnchor? = null
}

data class SpatialMarker(val id: UUID, val kind: MarkerKind, val pose: WorldPose, val tracking: ArTracking,
    val displayNumber: Int = 0, val normal: Vec3? = null, val placementState: PlacementState = PlacementState.ANCHORED,
    val selected: Boolean = false)
data class SpatialStroke(val id: UUID, val geometry: StrokeGeometry, val pose: WorldPose, val tracking: ArTracking)
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
    private val onPlacement: (PlacementDiagnostic) -> Unit = {},
    private val history: PoseHistory = PoseHistory(),
    private val maxAnchors: Int = 32,
    private val monotonicNs: () -> Long = { System.nanoTime().coerceAtLeast(0) },
) : AutoCloseable {
    private val owner = Thread.currentThread()
    private val mutableState = MutableStateFlow(ArSessionState.IDLE)
    val state: StateFlow<ArSessionState> = mutableState.asStateFlow()
    private var backend: ArBackend? = null
    private val anchors = LinkedHashMap<UUID, Pair<MarkerKind, LocalAnchor>>()
    private val annotations = AnnotationLedger(maxAnchors)
    private var lastTimestampNs = 0L
    private val resolver = SpatialResolver(history)
    private val placementResolver = PlacementResolver(history)
    private val pointRefiners = HashMap<UUID, PoseRefiner>()
    private val pointAnchorPositions = HashMap<UUID, Vec3>()
    private val strokes = LinkedHashMap<UUID, StrokeBuilder>()
    private var lastRefineNs = 0L
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
            if (history.record(frame)) {
                expireScreens()
                refinePoints(frame)
                frame
            } else { onEvent(ArEvent.FRAME_REJECTED); null }
        } catch (_: Exception) { fail(); null }
    }

    /** New POINT entry point; v1 createMarker deliberately retains its strict rejection contract. */
    private sealed interface PointCreation {
        data class Created(val record: AnnotationRecord) : PointCreation
        data class Rejected(val reason: SpatialRejection) : PointCreation
    }

    fun createPoint(epoch: UUID, id: UUID, request: SpatialMarkerRequest,
        author: AnnotationAuthor = AnnotationAuthor.FIELD): AnnotationRecord? =
        (createPoint(epoch, id, MarkerKind.PIN, request, author, allowEstimate = true,
            keepScreenFallback = true) as? PointCreation.Created)?.record

    /** Remote taps use the same depth/plane/feature hierarchy as local points. A historical ray
     * without surface evidence is rejected because the guide cannot validate an estimated anchor.
     */
    fun createGuidePoint(epoch: UUID, id: UUID, kind: MarkerKind,
        request: SpatialMarkerRequest): SpatialRejection? =
        (createPoint(epoch, id, kind, request, AnnotationAuthor.GUIDE, allowEstimate = false,
            keepScreenFallback = false) as? PointCreation.Rejected)?.reason

    private fun createPoint(epoch: UUID, id: UUID, kind: MarkerKind, request: SpatialMarkerRequest,
        author: AnnotationAuthor, allowEstimate: Boolean, keepScreenFallback: Boolean): PointCreation {
        checkThread()
        fun reject(reason: SpatialRejection): PointCreation.Rejected {
            onEvent(ArEvent.MARKER_REJECTED)
            onPlacement(PlacementDiagnostic(author, rejection = reason))
            return PointCreation.Rejected(reason)
        }
        if (epoch != sessionId) return reject(SpatialRejection.WRONG_SESSION)
        if (!active()) return reject(SpatialRejection.INACTIVE)
        annotations.rejectionFor(id)?.let { return reject(it) }
        val result = resolvePlacement(request, allowEstimate)
        if (!keepScreenFallback && result is PlacementResult.Screen) return reject(result.reason)
        val placed = installPlacement(id, result, request, allowInstant = author == AnnotationAuthor.FIELD,
            kind = kind)
        if (!active()) return reject(SpatialRejection.INACTIVE)
        if (!keepScreenFallback && placed is PlacementResult.Screen) return reject(placed.reason)
        val record = annotations.create(id, author, AnnotationType.POINT, placed, placementState(placed))
        if (placed is PlacementResult.World) {
            pointRefiners[id] = PoseRefiner()
            pointAnchorPositions[id] = placed.pose.position
            onPlacement(PlacementDiagnostic(author, method = placed.evidence.method))
        } else {
            onPlacement(PlacementDiagnostic(author,
                rejection = (placed as PlacementResult.Screen).reason))
        }
        onEvent(ArEvent.MARKER_CREATED)
        return PointCreation.Created(record)
    }

    fun beginStroke(epoch: UUID, id: UUID, request: SpatialMarkerRequest,
        author: AnnotationAuthor = AnnotationAuthor.FIELD): Boolean {
        checkThread()
        if (epoch != sessionId || !active() || annotations.rejectionFor(id) != null ||
            strokes.values.any { !it.stopped } || strokes.values.sumOf { it.size } >= AnnotationBudget.MAX_TOTAL_POINTS) return false
        val placement = resolvePlacement(request, allowEstimate = false)
        // The UI always renders its touch preview. A stroke needs one real world starting point;
        // without it the user is explicitly asked to start again, never given a fake world path.
        if (placement !is PlacementResult.World) { onEvent(ArEvent.MARKER_REJECTED); return false }
        val installed = installPlacement(id, placement, request)
        if (installed !is PlacementResult.World) return false
        annotations.create(id, author, AnnotationType.STROKE, installed, PlacementState.ANCHORED)
        strokes[id] = StrokeBuilder(installed)
        return true
    }

    fun appendStroke(epoch: UUID, id: UUID, requests: List<SpatialMarkerRequest>,
        author: AnnotationAuthor = AnnotationAuthor.FIELD): Boolean {
        checkThread()
        if (epoch != sessionId || !active() || !owns(id, author) || requests.size !in 1..AnnotationBudget.MAX_BATCH_POINTS) return false
        val stroke = strokes[id] ?: return false
        try { anchors[id]?.second?.let { stroke.followAnchor(it.pose) } }
        catch (_: Exception) { fail(); return false }
        for (request in requests) {
            // Conservative reservation bounds the total even when one input expands into many samples.
            if (strokes.values.sumOf { it.size } + 42 > AnnotationBudget.MAX_TOTAL_POINTS) {
                stroke.finish(); onEvent(ArEvent.MARKER_REJECTED); return false
            }
            val result = stroke.append(request, resolvePlacement(request, allowEstimate = false), history.find(request.frame))
            if (result == StrokeAppendResult.STOPPED || result == StrokeAppendResult.LIMIT_REACHED) {
                onEvent(ArEvent.MARKER_REJECTED); return false
            }
        }
        return true
    }

    fun endStroke(epoch: UUID, id: UUID, author: AnnotationAuthor = AnnotationAuthor.FIELD): Boolean {
        checkThread()
        if (epoch != sessionId || !owns(id, author)) return false
        val stroke = strokes[id] ?: return false
        stroke.finish()
        if (stroke.size < 2) { removeMarker(epoch, id, author); return false }
        return true
    }

    fun cancelStroke(epoch: UUID, id: UUID, author: AnnotationAuthor = AnnotationAuthor.FIELD): Boolean {
        checkThread()
        return epoch == sessionId && owns(id, author) && id in strokes && removeMarker(epoch, id, author)
    }

    fun selectAnnotation(epoch: UUID, id: UUID?): Boolean {
        checkThread(); return epoch == sessionId && active() && annotations.select(id)
    }

    fun strokeSnapshot(): List<SpatialStroke> {
        checkThread()
        if (!active()) return emptyList()
        return try { strokes.mapNotNull { (id, stroke) -> anchors[id]?.second?.let {
            SpatialStroke(id, stroke.snapshot(), it.pose, it.tracking)
        } } } catch (_: Exception) { fail(); emptyList() }
    }

    private fun owns(id: UUID, author: AnnotationAuthor): Boolean = annotations.snapshot().any { it.id == id && it.author == author }

    private fun resolvePlacement(request: SpatialMarkerRequest, allowEstimate: Boolean = true): PlacementResult {
        val now = monotonicNs()
        if (state.value != ArSessionState.TRACKING) return PlacementResult.Screen(request.point, request.frame,
            now + AnnotationBudget.SCREEN_TTL_NS, SpatialRejection.TRACKING_UNAVAILABLE)
        return placementResolver.resolve(request, now, allowEstimate)
    }

    private fun placementState(result: PlacementResult): PlacementState = when (result) {
        is PlacementResult.Screen -> PlacementState.SCREEN_LOCKED
        is PlacementResult.World -> if (result.evidence.confidence < 0.7f) PlacementState.STABILIZING else PlacementState.ANCHORED
    }

    private fun installPlacement(id: UUID, placement: PlacementResult, request: SpatialMarkerRequest,
        allowInstant: Boolean = false, kind: MarkerKind = MarkerKind.PIN): PlacementResult {
        if (placement !is PlacementResult.World) return placement
        return try {
            val instant = if (allowInstant && placement.evidence.method == PlacementMethod.HISTORICAL_RAY_ESTIMATE)
                requireNotNull(backend).createInstantAnchor(request) else null
            val anchor = instant ?: requireNotNull(backend).createAnchor(placement.pose)
            anchors[id] = kind to anchor
            placement.copy(pose = anchor.pose, evidence = if (instant != null)
                SurfaceEvidence(PlacementMethod.INSTANT_PLACEMENT, anchor.placementConfidence ?: 0.2f) else placement.evidence)
        } catch (_: Exception) {
            anchors.remove(id)?.second?.let { if (!detach(it)) fail() }
            onEvent(ArEvent.NATIVE_FAILURE)
            // No synthetic position survives a failed native allocation.
            PlacementResult.Screen(request.point, placement.frame,
                monotonicNs() + AnnotationBudget.SCREEN_TTL_NS, SpatialRejection.NATIVE_FAILURE)
        }
    }

    private fun expireScreens() {
        val now = monotonicNs()
        annotations.snapshot().filter { (it.placement as? PlacementResult.Screen)?.expiresAtNs?.let { expiry -> now >= expiry } == true }
            .forEach { removeMarker(sessionId, it.id) }
    }

    private fun refinePoints(frame: HistoricalFrame) {
        val deltaNs = if (lastRefineNs > 0) frame.frame.timestampNs - lastRefineNs else 0L
        lastRefineNs = frame.frame.timestampNs
        if (frame.tracking != ArTracking.TRACKING) { pointRefiners.values.forEach { it.reset() }; return }
        for (record in annotations.snapshot()) {
            val refiner = pointRefiners[record.id] ?: continue
            val placement = record.placement as? PlacementResult.World ?: continue
            val anchor = anchors[record.id]?.second ?: continue
            if (anchor.tracking != ArTracking.TRACKING) { refiner.reset(); continue }
            val marker = SpatialMarker(record.id, MarkerKind.PIN, anchor.pose, anchor.tracking)
            val projected = com.lazydoglab.zisee.ar.render.MarkerProjection.project(marker, frame.pose, frame.intrinsics) ?: continue
            val candidate = placementResolver.resolve(SpatialMarkerRequest(frame.frame, projected.point), monotonicNs(), false) as? PlacementResult.World ?: continue
            val refined = refiner.refine(placement, candidate, deltaNs, true)
            if (refined != placement) annotations.update(record.id, refined, placementState(refined))
        }
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
            anchors.filterKeys { it !in strokes }.map { (id, entry) ->
                val pose = entry.second.pose
                val tracking = entry.second.tracking
                val record = requireNotNull(records[id])
                val placement = record.placement as PlacementResult.World
                val followed = if (id in pointRefiners) {
                    val previousAnchor = pointAnchorPositions.put(id, pose.position) ?: pose.position
                    placement.copy(pose = placement.pose.copy(position = placement.pose.position + (pose.position - previousAnchor)))
                } else placement.copy(pose = pose)
                val updated = if (followed.evidence.method == PlacementMethod.INSTANT_PLACEMENT)
                    followed.copy(evidence = followed.evidence.copy(confidence = entry.second.placementConfidence ?: 0.2f)) else followed
                val nextState = if (tracking != ArTracking.TRACKING || state.value != ArSessionState.TRACKING) PlacementState.LOST
                    else if (id in pointRefiners) placementState(updated) else PlacementState.ANCHORED
                annotations.update(id, updated, nextState)
                SpatialMarker(id, entry.first, updated.pose, tracking, record.displayNumber, updated.evidence.normal,
                    nextState, record.selected)
            }
        } catch (_: Exception) { fail(); emptyList() }
    }

    fun annotationSnapshot(): List<AnnotationRecord> { checkThread(); return annotations.snapshot() }

    fun removeMarker(epoch: UUID, id: UUID, actor: AnnotationAuthor = AnnotationAuthor.FIELD): Boolean {
        checkThread()
        if (epoch != sessionId || !annotations.canRemove(id, actor)) return false
        val anchor = anchors.remove(id)?.second
        annotations.remove(id, actor)
        pointRefiners.remove(id); pointAnchorPositions.remove(id); strokes.remove(id)
        if (anchor == null || detach(anchor)) return true
        // A failed detach must not leave an untracked native resource in a running session.
        fail()
        return false
    }

    fun clearMarkers(epoch: UUID, actor: AnnotationAuthor = AnnotationAuthor.FIELD): Boolean {
        checkThread()
        if (epoch != sessionId || state.value == ArSessionState.CLOSED) return false
        var success = true
        annotations.clear(actor).forEach { id ->
            pointRefiners.remove(id); pointAnchorPositions.remove(id); strokes.remove(id)
            anchors.remove(id)?.second?.let { if (!detach(it)) success = false }
        }
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
        strokes.clear(); pointRefiners.clear(); pointAnchorPositions.clear()
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
