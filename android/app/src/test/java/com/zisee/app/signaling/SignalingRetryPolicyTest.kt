package com.zisee.app.signaling

import org.junit.Assert.*
import org.junit.Test

class SignalingRetryPolicyTest {
    @Test fun `established media survives three transport failures within bounded grace`() {
        val policy = SignalingRetryPolicy()
        assertEquals(1_000L, policy.nextDelayMs(0, true))
        assertEquals(2_000L, policy.nextDelayMs(1_000, true))
        assertEquals(4_000L, policy.nextDelayMs(3_000, true))
        assertEquals(1L, policy.nextDelayMs(29_999, true))
        assertNull(policy.nextDelayMs(30_000, true))
    }

    @Test fun `setup still fails promptly and a successful sync resets outage budget`() {
        val policy = SignalingRetryPolicy()
        policy.nextDelayMs(0, false)
        policy.nextDelayMs(1_000, false)
        assertNull(policy.nextDelayMs(3_000, false))
        policy.recovered()
        assertEquals(1_000L, policy.nextDelayMs(60_000, false))
    }
}
