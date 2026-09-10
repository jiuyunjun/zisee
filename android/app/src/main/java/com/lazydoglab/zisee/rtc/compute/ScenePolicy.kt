package com.lazydoglab.zisee.rtc.compute

enum class SceneMode { NORMAL, MOTION, LOW_LIGHT, STILL }
data class SceneObservation(val luma: Float, val motion: Float, val texture: Float, val noise: Float)
data class SceneDecision(val mode: SceneMode = SceneMode.NORMAL, val fpsLimit: Int = 30)

/** Sampled image heuristics, not face detection or optical flow. Motion always wins over darkness. */
class ScenePolicy {
    var decision = SceneDecision(); private set
    private var pending: SceneMode? = null
    private var since = 0L
    private var lastMs: Long? = null

    fun reset() { decision = SceneDecision(); pending = null; lastMs = null }

    fun update(nowMs: Long, observation: SceneObservation): SceneDecision {
        if (lastMs?.let { nowMs - it !in 0..1_500 } == true) reset()
        lastMs = nowMs
        if (listOf(observation.luma, observation.motion, observation.texture, observation.noise)
                .any { !it.isFinite() || it !in 0f..1f }) { reset(); return decision }
        val target = when {
            observation.motion >= 0.055f -> SceneMode.MOTION
            observation.luma < 0.22f && observation.motion < 0.025f -> SceneMode.LOW_LIGHT
            observation.motion < 0.012f -> SceneMode.STILL
            else -> SceneMode.NORMAL
        }
        if (target == SceneMode.MOTION) { decision = SceneDecision(target, 30); pending = null }
        else if (target == decision.mode) pending = null
        else {
            if (pending != target) { pending = target; since = nowMs }
            if (nowMs - since >= 3_000) {
                decision = SceneDecision(target, when (target) { SceneMode.LOW_LIGHT -> 20; SceneMode.STILL -> 24; else -> 30 })
                pending = null
            }
        }
        return decision
    }
}
