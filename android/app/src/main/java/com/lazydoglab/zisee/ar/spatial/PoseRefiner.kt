package com.lazydoglab.zisee.ar.spatial

import com.lazydoglab.zisee.ar.annotation.PlacementResult
import kotlin.math.exp

/** Evidence gate plus time-based visual correction. Does not allocate/detach native anchors.
 * Caller invalidates the refiner when session world continuity is lost.
 */
class PoseRefiner(private val timeConstantSeconds: Float = 0.2f) {
    init { require(timeConstantSeconds.isFinite() && timeConstantSeconds > 0f) }
    private var accepted: PlacementResult.World? = null
    private var confirmations = 0

    fun reset() { accepted = null; confirmations = 0 }

    fun refine(current: PlacementResult.World, candidate: PlacementResult.World,
        elapsedNs: Long, worldContinuous: Boolean): PlacementResult.World {
        if (!worldContinuous || elapsedNs <= 0 || candidate.frame.timestampNs <= current.frame.timestampNs) {
            reset(); return current
        }
        val previous = current.evidence
        val next = candidate.evidence
        if (accepted?.frame?.timestampNs?.let { candidate.frame.timestampNs <= it } == true) return current
        // Missing identity is not positive correspondence. Depth can correct on a compatible
        // local surface only with normal and bounded displacement, never upgrade a free ray guess.
        val sameIdentity = previous.method == next.method && previous.surfaceId != null && previous.surfaceId == next.surfaceId
        val normalsAgree = previous.normal != null && next.normal != null && previous.normal.dot(next.normal) >= 0.94f
        if ((!sameIdentity && !normalsAgree) || (previous.surfaceId != null && next.surfaceId != null && !sameIdentity) ||
            (candidate.pose.position - current.pose.position).length() > 0.05f ||
            next.confidence < previous.confidence + 0.05f) {
            reset(); return current
        }
        val last = accepted
        confirmations = if (last != null && (last.pose.position - candidate.pose.position).length() <= 0.02f) confirmations + 1 else 1
        accepted = candidate
        if (confirmations < 3) return current
        val weight = (1.0 - exp(-elapsedNs.coerceAtMost(100_000_000L) / 1e9 / timeConstantSeconds)).toFloat()
        val position = current.pose.position + (candidate.pose.position - current.pose.position) * weight
        val normal = if (previous.normal != null && next.normal != null)
            (previous.normal * (1f - weight) + next.normal * weight).normalized() else next.normal
        // Keep the anchor's orientation; rendering uses the explicit surface normal.
        val converged = (candidate.pose.position - position).length() < 0.001f
        // Retain the lower confidence while approaching the target. Promoting confidence on the
        // first lerp would fail the quality gate on the next frame and freeze a partial correction.
        return current.copy(pose = current.pose.copy(position = position),
            evidence = if (converged) next.copy(normal = normal) else previous.copy(normal = normal))
    }
}
