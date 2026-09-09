package com.lazydoglab.zisee.ar.session

import org.junit.Assert.*
import org.junit.Test

class ArActivationGateTest {
    @Test fun `only an explicit resumed request can start once`() {
        val gate = ArActivationGate()
        assertNull(gate.begin())
        gate.setResumed(true)
        assertFalse(gate.consume(123))
        val request = requireNotNull(gate.begin())
        assertTrue(gate.consume(request))
        assertFalse(gate.consume(request))
    }
    @Test fun `permission or installer result cannot reopen camera after pause and resume`() {
        val gate = ArActivationGate()
        gate.setResumed(true)
        val request = requireNotNull(gate.begin())
        gate.setResumed(false)
        assertFalse(gate.consume(request))
        gate.setResumed(true)
        assertFalse(gate.consume(request))
        assertTrue(gate.consume(requireNotNull(gate.begin())))
    }
    @Test fun `hangup exit and replacement invalidate pending intent`() {
        val gate = ArActivationGate()
        gate.setResumed(true)
        val first = requireNotNull(gate.begin())
        val second = requireNotNull(gate.begin())
        assertFalse(gate.consume(first))
        gate.invalidate()
        assertFalse(gate.consume(second))
    }
}
