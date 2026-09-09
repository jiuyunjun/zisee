package com.zisee.app.rtc

import org.junit.Assert.assertEquals
import org.junit.Test

class IceRecoveryPolicyTest {
    @Test fun `new route bypasses cooldown after debounce`() {
        val policy = IceRecoveryPolicy(0)
        policy.generationStarted(1_000, 0)
        assertEquals(IceRecoveryPolicy.Action.WAIT, policy.evaluate(IceState.CHECKING, 1, false, 1_100))
        assertEquals(IceRecoveryPolicy.Action.WAIT, policy.evaluate(IceState.CHECKING, 1, false, 1_349))
        assertEquals(IceRecoveryPolicy.Action.RESTART, policy.evaluate(IceState.CHECKING, 1, false, 1_350))
        policy.generationStarted(1_350, 1)
        assertEquals(IceRecoveryPolicy.Action.WAIT, policy.evaluate(IceState.FAILED, 1, false, 1_600))
    }
    @Test fun `route flapping cannot exceed restart budget`() {
        val policy = IceRecoveryPolicy(0)
        for (version in 1L..3L) {
            val now = version * 1_000
            policy.evaluate(IceState.CHECKING, version, false, now)
            assertEquals(IceRecoveryPolicy.Action.RESTART, policy.evaluate(IceState.CHECKING, version, false, now + 250))
            policy.generationStarted(now + 250, version)
        }
        policy.evaluate(IceState.CHECKING, 4, false, 4_000)
        assertEquals(IceRecoveryPolicy.Action.WAIT, policy.evaluate(IceState.CHECKING, 4, false, 4_250))
        assertEquals(IceRecoveryPolicy.Action.FAIL, policy.evaluate(IceState.CHECKING, 4, false, 15_250))
    }

    @Test fun `ringing time does not consume initial negotiation budget`() {
        val policy = IceRecoveryPolicy(0)
        policy.initialNegotiationStarted(60_000, 4)
        assertEquals(IceRecoveryPolicy.Action.WAIT, policy.evaluate(IceState.NEW, 4, false, 60_100))
    }
    @Test fun `short disconnect recovers without restart`() {
        val policy = IceRecoveryPolicy(0)
        assertEquals(IceRecoveryPolicy.Action.WAIT, policy.evaluate(IceState.DISCONNECTED, 0, true, 6_000))
        assertEquals(IceRecoveryPolicy.Action.WAIT, policy.evaluate(IceState.CONNECTED, 0, true, 7_000))
    }
    @Test fun `stale connected state without incoming media still restarts`() {
        val policy = IceRecoveryPolicy(0)
        assertEquals(IceRecoveryPolicy.Action.WAIT, policy.evaluate(IceState.CONNECTED, 1, true, 6_000))
        assertEquals(IceRecoveryPolicy.Action.WAIT, policy.evaluate(IceState.CONNECTED, 1, true, 6_500))
        assertEquals(IceRecoveryPolicy.Action.RESTART, policy.evaluate(IceState.CONNECTED, 1, true, 6_750))
        policy.generationStarted(6_750, 1)
        assertEquals(IceRecoveryPolicy.Action.WAIT, policy.evaluate(IceState.CHECKING, 1, false, 7_000))
    }
    @Test fun `incoming media on recovered path avoids unnecessary restart`() {
        val policy = IceRecoveryPolicy(0)
        policy.evaluate(IceState.CONNECTED, 1, true, 6_000, sample(5_900, 100))
        policy.evaluate(IceState.CONNECTED, 1, true, 6_200, sample(6_200, 120))
        assertEquals(IceRecoveryPolicy.Action.WAIT,
            policy.evaluate(IceState.CONNECTED, 1, true, 6_400, sample(6_400, 140)))
        assertEquals(false, policy.checkingRoute)
        assertEquals(IceRecoveryPolicy.Action.WAIT,
            policy.evaluate(IceState.CONNECTED, 1, true, 20_000, sample(20_000, 200)))
    }
    @Test fun `old sample and counter reset cannot prove route recovery`() {
        val policy = IceRecoveryPolicy(0)
        policy.evaluate(IceState.CONNECTED, 1, true, 6_000, sample(5_900, 100))
        policy.evaluate(IceState.CONNECTED, 1, true, 6_200, sample(6_200, 120))
        policy.evaluate(IceState.CONNECTED, 1, true, 6_400, sample(6_400, 10))
        assertEquals(IceRecoveryPolicy.Action.RESTART,
            policy.evaluate(IceState.CONNECTED, 1, true, 6_750, sample(6_400, 10)))
    }
    @Test fun `second route change discards first route progress`() {
        val policy = IceRecoveryPolicy(0)
        policy.evaluate(IceState.CONNECTED, 1, true, 6_000, sample(6_000, 100))
        policy.evaluate(IceState.CONNECTED, 2, true, 6_200, sample(6_100, 120))
        assertEquals(IceRecoveryPolicy.Action.RESTART,
            policy.evaluate(IceState.CONNECTED, 2, true, 6_950, sample(6_100, 120)))
    }
    @Test fun `receiving packets cannot override disconnected ICE state`() {
        val policy = IceRecoveryPolicy(0)
        policy.evaluate(IceState.DISCONNECTED, 1, true, 6_000, sample(6_000, 100))
        assertEquals(IceRecoveryPolicy.Action.RESTART,
            policy.evaluate(IceState.DISCONNECTED, 1, true, 6_250, sample(6_250, 120)))
    }
    private fun sample(time: Long, bytes: Long) = MediaStats(
        sampleAvailable = true, sampledAtMs = time, inboundBytes = bytes)
    @Test fun `failed recovery stops after three generations`() {
        val policy = IceRecoveryPolicy(0)
        for (time in listOf(12_000L, 24_000L, 36_000L)) {
            assertEquals(IceRecoveryPolicy.Action.RESTART, policy.evaluate(IceState.FAILED, 0, false, time))
            policy.generationStarted(time, 0)
        }
        assertEquals(IceRecoveryPolicy.Action.FAIL, policy.evaluate(IceState.FAILED, 0, false, 48_000))
    }
}
