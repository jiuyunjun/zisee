package com.lazydoglab.zisee.rtc.compute

import org.junit.Assert.*
import org.junit.Test

class ComputeQualityPolicyTest {
    private fun good(t: Long) = ComputeInput(t, 0, 0.4f, false, 80, 4.0, 30)
    private fun warmUp(p: ComputeQualityPolicy) { for (t in 0L..52_000L step 1_000) p.update(good(t)) }

    @Test fun slowUpAndImmediateThermalDown() {
        val p = ComputeQualityPolicy()
        warmUp(p)
        assertEquals(ComputeLevel.C3, p.decision.level)
        assertEquals(ComputeLevel.C0, p.update(good(53_000).copy(thermalStatus = 3)).level)
        assertEquals(ComputeLevel.C0, p.update(good(54_000)).level)
        for (t in 55_000L..79_000 step 1_000) p.update(good(t))
        assertEquals(ComputeLevel.C1, p.decision.level)
    }

    @Test fun unknownAndNanNeverAuthorizeExtraCompute() {
        val p = ComputeQualityPolicy()
        for (t in 0L..120_000 step 1_000) p.update(good(t).copy(forecastHeadroom = Float.NaN))
        assertEquals(ComputeLevel.C1, p.decision.level)
        warmUp(p)
        assertEquals(ComputeLevel.C1, p.update(good(53_000).copy(sampleFresh = false)).level)
    }

    @Test fun budgetAndPowerCaps() {
        for (input in listOf(good(0).copy(encodeMs = 25.0), good(0).copy(cpuLimited = true),
            good(0).copy(preprocessP95Ms = 5.1), good(0).copy(forecastHeadroom = 0.91f))) {
            assertEquals(ComputeLevel.C0, ComputeQualityPolicy().update(input).level)
        }
        val p = ComputeQualityPolicy(); warmUp(p)
        assertEquals(ComputeLevel.C1, p.update(good(53_000).copy(powerSave = true)).level)
    }

    @Test fun gapCannotCountAsHealthyTime() {
        val p = ComputeQualityPolicy()
        p.update(good(0)); p.update(good(60_000))
        assertEquals(ComputeLevel.C1, p.decision.level)
    }

    @Test fun callbackP95ProtectsAgainstAHealthyMeanHidingTailLatency() {
        assertEquals(ComputeLevel.C0, ComputeQualityPolicy().update(good(0).copy(encodeCallbackP95Ms = 25.0)).level)
        assertEquals(ComputeLevel.C1, ComputeQualityPolicy().update(good(0).copy(encodeCallbackP95Ms = Double.NaN)).level)
    }

    @Test fun burstExpiresAndCannotBeExtended() {
        val p = ComputeQualityPolicy(); warmUp(p)
        assertTrue(p.requestBurst(52_000))
        assertEquals(ComputeLevel.C4, p.update(good(53_000)).level)
        assertFalse(p.requestBurst(53_000))
        for (t in 54_000L..60_000 step 1_000) p.update(good(t))
        assertEquals(ComputeLevel.C3, p.decision.level)
        assertFalse(p.requestBurst(60_000))
    }
}
