package com.lazydoglab.zisee.rtc.compute

import org.junit.Assert.*
import org.junit.Test

class ScenePolicyTest {
    @Test fun darkStillRequiresSustainedEvidenceAndMotionRestoresFpsImmediately() {
        val p = ScenePolicy()
        val dark = SceneObservation(.15f, .005f, .1f, .03f)
        for (t in 0L..2_500 step 500) assertEquals(30, p.update(t, dark).fpsLimit)
        assertEquals(20, p.update(3_000, dark).fpsLimit)
        assertEquals(SceneMode.MOTION, p.update(3_500, dark.copy(motion = .1f)).mode)
        assertEquals(30, p.decision.fpsLimit)
    }
    @Test fun invalidOrMissingEvidenceCannotReduceFps() {
        val p = ScenePolicy()
        val still = SceneObservation(.5f, 0f, .1f, 0f)
        p.update(0, still)
        assertEquals(30, p.update(5_000, still).fpsLimit)
        assertEquals(30, p.update(5_500, still.copy(luma = Float.NaN)).fpsLimit)
    }
    @Test fun normalLightStillCapsAt24() {
        val p = ScenePolicy()
        for (t in 0L..3_000 step 500) p.update(t, SceneObservation(.5f, .005f, .2f, .01f))
        assertEquals(SceneDecision(SceneMode.STILL, 24), p.decision)
    }
}
