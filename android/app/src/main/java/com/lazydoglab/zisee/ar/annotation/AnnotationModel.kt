package com.lazydoglab.zisee.ar.annotation

import com.lazydoglab.zisee.ar.spatial.SpatialRejection
import com.lazydoglab.zisee.ar.spatial.Vec3
import com.lazydoglab.zisee.ar.spatial.WorldPose
import java.util.UUID
import kotlin.math.abs

/** Derived from the authenticated call endpoint, never accepted from a payload's owner field. */
enum class AnnotationAuthor { FIELD, GUIDE }
enum class PlacementMethod { DEPTH, PLANE, LOCAL_SURFACE, FEATURE_POINT, HISTORICAL_RAY_ESTIMATE, INSTANT_PLACEMENT }
enum class PlacementState { PREVIEW, STABILIZING, ANCHORED, LOST, SCREEN_LOCKED }
enum class AnnotationType { POINT, STROKE, WARNING, ACTION, ARROW, REGION }

/** Normal is optional: a position-only depth/feature hit is not a surface orientation. */
data class SurfaceEvidence(
    val method: PlacementMethod,
    val confidence: Float,
    val normal: Vec3? = null,
    val surfaceId: Long? = null,
    val strictPolygonHit: Boolean = false,
) {
    init {
        require(confidence.isFinite() && confidence in 0f..1f)
        require(normal == null || abs(normal.length() - 1f) < 0.001f)
        require(surfaceId == null || surfaceId >= 0)
        require(!strictPolygonHit || method == PlacementMethod.PLANE)
    }
}

sealed interface PlacementResult {
    data class World(val pose: WorldPose, val evidence: SurfaceEvidence, val frame: VideoFrameReference) : PlacementResult
    /** Raw, unrotated image coordinates. TTL uses the receiver's monotonic clock, not AR time.
     * A missing frame has no implicit world candidate and cannot be refined automatically.
     */
    data class Screen(val point: VideoPoint, val frame: VideoFrameReference?,
        val expiresAtNs: Long, val reason: SpatialRejection) : PlacementResult {
        init { require(expiresAtNs >= 0) }
    }
}

data class AnnotationStyle(val argb: Int = 0xff5ed4d6.toInt(), val widthDp: Float = 4f) {
    init { require(widthDp.isFinite() && widthDp in 1f..24f) }
}

/** Immutable view of one authority-owned annotation. Native anchors belong to the GL controller. */
data class AnnotationRecord(
    val id: UUID,
    val author: AnnotationAuthor,
    val displayNumber: Int,
    val revision: Long,
    val type: AnnotationType,
    val placement: PlacementResult,
    val state: PlacementState,
    val selected: Boolean = false,
    val style: AnnotationStyle = AnnotationStyle(),
) {
    init {
        require(displayNumber > 0 && revision > 0)
        require((placement is PlacementResult.Screen) == (state == PlacementState.SCREEN_LOCKED))
    }
}

object AnnotationBudget {
    const val MAX_ANNOTATIONS = 32
    const val MAX_IDS = 512
    const val MAX_STROKE_POINTS = 512
    const val MAX_TOTAL_POINTS = 8192
    const val MAX_BATCH_POINTS = 16
    const val MAX_PACKET_BYTES = 4096
    const val STROKE_BATCH_INTERVAL_NS = 50_000_000L
    const val SCREEN_TTL_NS = 1_500_000_000L
    const val MAX_PREDICTION_NS = 100_000_000L
    const val MAX_PREDICTION_METRES = 0.05f
}

/** Thread-confined authority ledger. Deletion/clear retain bounded tombstones and numbering.
 * An instance lives for exactly one AR session, including pause/resume and guide re-entry.
 */
class AnnotationLedger(private val maxAnnotations: Int = AnnotationBudget.MAX_ANNOTATIONS) {
    private val owner = Thread.currentThread()
    private val records = LinkedHashMap<UUID, AnnotationRecord>()
    private val usedIds = HashSet<UUID>()
    private var nextNumber = 1
    var revision: Long = 0
        private set
    init { require(maxAnnotations in 1..128) }
    private fun checkOwner() = check(Thread.currentThread() === owner)

    fun rejectionFor(id: UUID): SpatialRejection? {
        checkOwner()
        return when {
            id in usedIds -> SpatialRejection.DUPLICATE_ID
            records.size >= maxAnnotations || usedIds.size >= AnnotationBudget.MAX_IDS -> SpatialRejection.LIMIT_REACHED
            else -> null
        }
    }

    fun create(id: UUID, author: AnnotationAuthor, type: AnnotationType, placement: PlacementResult,
        state: PlacementState): AnnotationRecord {
        checkOwner()
        check(rejectionFor(id) == null)
        val record = AnnotationRecord(id, author, nextNumber, revision + 1, type, placement, state)
        nextNumber++; revision++; usedIds.add(id); records[id] = record
        return record
    }

    fun snapshot(): List<AnnotationRecord> { checkOwner(); return records.values.toList() }

    fun update(id: UUID, placement: PlacementResult, state: PlacementState): AnnotationRecord? {
        checkOwner()
        val old = records[id] ?: return null
        if (old.placement == placement && old.state == state) return old
        val updated = old.copy(placement = placement, state = state, revision = revision + 1)
        revision++; records[id] = updated
        return updated
    }

    fun select(id: UUID?): Boolean {
        checkOwner()
        if (id != null && id !in records) return false
        records.replaceAll { key, old ->
            if (old.selected == (key == id)) old else old.copy(selected = key == id, revision = ++revision)
        }
        return true
    }

    fun canRemove(id: UUID, actor: AnnotationAuthor): Boolean {
        checkOwner()
        val record = records[id] ?: return false
        return actor == AnnotationAuthor.FIELD || record.author == actor
    }

    fun remove(id: UUID, actor: AnnotationAuthor): Boolean {
        checkOwner()
        if (!canRemove(id, actor)) return false
        records.remove(id); revision++
        return true
    }

    fun clear(actor: AnnotationAuthor): List<UUID> {
        checkOwner()
        val ids = records.values.filter { actor == AnnotationAuthor.FIELD || it.author == actor }.map { it.id }
        ids.forEach { remove(it, actor) }
        return ids
    }

    fun lastOwned(actor: AnnotationAuthor): UUID? {
        checkOwner()
        return records.values.lastOrNull { it.author == actor }?.id
    }
}
