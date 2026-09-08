package com.zisee.app.signaling

import org.junit.Assert.*
import org.junit.Test

class CandidateExchangePolicyTest {
    @Test fun `late network rotates before server candidate window expires`() {
        assertFalse(CandidateExchangePolicy.needsFreshGeneration(0, 89_999, 2, 3, false))
        assertTrue(CandidateExchangePolicy.needsFreshGeneration(0, 90_000, 2, 3, false))
        assertFalse(CandidateExchangePolicy.needsFreshGeneration(0, 900_000, 3, 3, false))
    }
    @Test fun `bounded history overflow rotates even during initial gathering`() {
        assertTrue(CandidateExchangePolicy.needsFreshGeneration(null, 100, 0, 32, true))
        assertFalse(CandidateExchangePolicy.needsFreshGeneration(null, 100, 0, 3, false))
    }
}
