package com.zisee.app.rtc

import org.junit.Assert.assertEquals
import org.junit.Test

class IceRecoveryPolicyTest {
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
    @Test fun `route change triggers restart even while old pair remains connected`() {
        val policy = IceRecoveryPolicy(0)
        assertEquals(IceRecoveryPolicy.Action.WAIT, policy.evaluate(IceState.CONNECTED, 1, true, 6_000))
        assertEquals(IceRecoveryPolicy.Action.RESTART, policy.evaluate(IceState.CONNECTED, 1, true, 6_500))
        policy.generationStarted(6_500, 1)
        assertEquals(IceRecoveryPolicy.Action.WAIT, policy.evaluate(IceState.CHECKING, 1, false, 7_000))
    }
    @Test fun `failed recovery stops after three generations`() {
        val policy = IceRecoveryPolicy(0)
        for (time in listOf(12_000L, 24_000L, 36_000L)) {
            assertEquals(IceRecoveryPolicy.Action.RESTART, policy.evaluate(IceState.FAILED, 0, false, time))
            policy.generationStarted(time, 0)
        }
        assertEquals(IceRecoveryPolicy.Action.FAIL, policy.evaluate(IceState.FAILED, 0, false, 48_000))
    }
}
