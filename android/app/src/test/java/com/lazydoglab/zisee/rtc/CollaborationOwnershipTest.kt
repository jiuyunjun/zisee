package com.lazydoglab.zisee.rtc

import com.lazydoglab.zisee.rtc.CollaborationOwnership.Phase
import com.lazydoglab.zisee.rtc.CollaborationOwnership.Refusal
import org.junit.Assert.*
import org.junit.Test

class CollaborationOwnershipTest {
    /**
     * Two ends wired to each other, so a test states what each user did, not what was encoded.
     *
     * Delivery here is synchronous and re-entrant, which is stricter than a real data channel: an
     * answer lands inside the send that provoked it. A state machine that survives that cannot be
     * broken by transport timing.
     *
     * [deliver] holds messages in flight instead of failing the send, which is what "both users
     * tapped before either message arrived" actually means.
     */
    private class Pair(var deliver: Boolean = true) {
        val sent = mutableMapOf<String, MutableList<String>>()
        val caller = CollaborationOwnership(localWinsConflict = true) { text -> post("caller", text) }
        val callee = CollaborationOwnership(localWinsConflict = false) { text -> post("callee", text) }

        init { caller.connected(); callee.connected() }

        private fun post(from: String, text: String): Boolean {
            sent.getOrPut(from) { mutableListOf() }.add(text)
            if (deliver) {
                if (from == "caller") callee.receive(text) else caller.receive(text)
            }
            return true
        }
    }

    @Test fun `one end claiming is granted the slot and the other sees it taken`() {
        val call = Pair()
        assertTrue(call.caller.claim())
        assertEquals(Phase.HELD, call.caller.state.value.phase)
        assertEquals(Phase.REMOTE_HELD, call.callee.state.value.phase)
    }

    /** The defect this exists to prevent: both users tap share at the same instant. */
    @Test fun `simultaneous claims resolve to exactly one owner`() {
        val call = Pair(deliver = false)
        // Both claims leave their end before either arrives, which is what "at once" means here.
        assertTrue(call.caller.claim())
        assertTrue(call.callee.claim())
        assertEquals(Phase.CLAIMING, call.caller.state.value.phase)
        assertEquals(Phase.CLAIMING, call.callee.state.value.phase)
        call.deliver = true
        call.callee.receive(call.sent.getValue("caller").first())
        call.caller.receive(call.sent.getValue("callee").first())

        val phases = listOf(call.caller.state.value.phase, call.callee.state.value.phase)
        assertEquals("exactly one owner, got $phases", 1, phases.count { it == Phase.HELD })
        assertEquals(1, phases.count { it == Phase.REMOTE_HELD })
        // The caller side wins, the same rule an AR field conflict uses.
        assertEquals(Phase.HELD, call.caller.state.value.phase)
        assertEquals(Refusal.PEER_CLAIMED_FIRST, call.callee.state.value.refusal)
    }

    @Test fun `the owner is never stopped by the other end asking`() {
        val call = Pair()
        assertTrue(call.callee.claim())
        assertEquals(Phase.HELD, call.callee.state.value.phase)
        assertFalse("claimed a slot the peer holds", call.caller.claim())
        assertEquals(Phase.HELD, call.callee.state.value.phase)
        assertEquals(Phase.REMOTE_HELD, call.caller.state.value.phase)
        assertEquals(Refusal.PEER_HOLDS, call.caller.state.value.refusal)
    }

    @Test fun `releasing frees the slot for the other end`() {
        val call = Pair()
        assertTrue(call.caller.claim())
        call.caller.release()
        assertEquals(Phase.IDLE, call.caller.state.value.phase)
        assertEquals(Phase.IDLE, call.callee.state.value.phase)
        assertTrue(call.callee.claim())
        assertEquals(Phase.HELD, call.callee.state.value.phase)
        assertEquals(Phase.REMOTE_HELD, call.caller.state.value.phase)
    }

    @Test fun `release is idempotent and harmless when nothing is held`() {
        val call = Pair()
        call.caller.release()
        call.caller.release()
        assertEquals(Phase.IDLE, call.caller.state.value.phase)
        assertTrue(call.caller.claim())
        call.caller.release()
        call.caller.release()
        assertEquals(Phase.IDLE, call.caller.state.value.phase)
        assertEquals(Phase.IDLE, call.callee.state.value.phase)
    }

    /** A peer too old to understand the claim never answers; refusing to share is the safe end. */
    @Test fun `a claim nobody answers expires instead of starting anything`() {
        val sent = mutableListOf<String>()
        val ownership = CollaborationOwnership(localWinsConflict = true) { sent.add(it); true }
        ownership.connected()
        assertTrue(ownership.claim())
        val outstanding = requireNotNull(ownership.outstanding)
        ownership.claimExpired(outstanding)
        assertEquals(Phase.IDLE, ownership.state.value.phase)
        assertEquals(Refusal.NO_ANSWER, ownership.state.value.refusal)
    }

    @Test fun `a deadline from an abandoned claim cannot cancel a later one`() {
        val ownership = CollaborationOwnership(localWinsConflict = true) { true }
        ownership.connected()
        assertTrue(ownership.claim())
        val stale = requireNotNull(ownership.outstanding)
        ownership.claimExpired(stale)
        assertTrue(ownership.claim())
        val current = requireNotNull(ownership.outstanding)
        assertNotEquals(stale, current)
        ownership.claimExpired(stale)
        assertEquals(Phase.CLAIMING, ownership.state.value.phase)
        ownership.claimExpired(current)
        assertEquals(Phase.IDLE, ownership.state.value.phase)
    }

    @Test fun `a late answer to an abandoned claim is ignored`() {
        val sent = mutableListOf<String>()
        val ownership = CollaborationOwnership(localWinsConflict = true) { sent.add(it); true }
        ownership.connected()
        assertTrue(ownership.claim())
        val stale = sent.last()
        ownership.claimExpired(requireNotNull(ownership.outstanding))
        assertTrue(ownership.claim())
        // The peer finally answers the first claim after a second one is already outstanding.
        ownership.receive(stale.replace("|C|", "|G|"))
        assertEquals(Phase.CLAIMING, ownership.state.value.phase)
    }

    @Test fun `no collaboration begins while the channel is unavailable`() {
        val ownership = CollaborationOwnership(localWinsConflict = true) { true }
        assertFalse("claimed before the channel opened", ownership.claim())
        assertEquals(Refusal.CHANNEL_UNAVAILABLE, ownership.state.value.refusal)
        ownership.connected()
        assertTrue(ownership.claim())
    }

    @Test fun `a send that fails leaves the slot free rather than half claimed`() {
        val ownership = CollaborationOwnership(localWinsConflict = true) { false }
        ownership.connected()
        assertFalse(ownership.claim())
        assertEquals(Phase.IDLE, ownership.state.value.phase)
        assertEquals(Refusal.CHANNEL_UNAVAILABLE, ownership.state.value.refusal)
    }

    /** §5.2: losing the channel must not invalidate media that is already running. */
    @Test fun `losing the channel keeps a local share but drops an unconfirmable remote one`() {
        val held = CollaborationOwnership(localWinsConflict = true) { true }
        held.connected()
        held.claim()
        held.receive("K1|G|1")
        assertEquals(Phase.HELD, held.state.value.phase)
        held.disconnected()
        assertEquals("a local projection is this device's own", Phase.HELD, held.state.value.phase)

        val watching = CollaborationOwnership(localWinsConflict = true) { true }
        watching.connected()
        watching.receive("K1|C|7")
        assertEquals(Phase.REMOTE_HELD, watching.state.value.phase)
        watching.disconnected()
        assertEquals(Phase.IDLE, watching.state.value.phase)
    }

    @Test fun `a release for a different claim does not free the slot`() {
        val ownership = CollaborationOwnership(localWinsConflict = true) { true }
        ownership.connected()
        ownership.receive("K1|C|4")
        assertEquals(Phase.REMOTE_HELD, ownership.state.value.phase)
        ownership.receive("K1|R|9")
        assertEquals(Phase.REMOTE_HELD, ownership.state.value.phase)
        ownership.receive("K1|R|4")
        assertEquals(Phase.IDLE, ownership.state.value.phase)
    }

    /** Every field is peer-supplied text, so malformed input must change nothing. */
    @Test fun `malformed and out of range messages are rejected`() {
        val ownership = CollaborationOwnership(localWinsConflict = true) { true }
        ownership.connected()
        for (text in listOf("", "K1", "K1|C", "K1|C|1|2", "K2|C|1", "K1|X|1", "K1|C|-1",
            "K1|C|abc", "K1|C|" + (CollaborationOwnership.MAX_REQUEST + 1),
            "K1|C|" + "9".repeat(40), "1|DUAL|1", "S1|1|abc", "V1|LARGE|LARGE")) {
            assertFalse("accepted $text", ownership.receive(text))
            assertEquals("state moved on $text", Phase.IDLE, ownership.state.value.phase)
        }
    }

    @Test fun `ownership messages never collide with the other control channel messages`() {
        val ownership = CollaborationOwnership(localWinsConflict = true) { text ->
            assertTrue("over the control channel bound: $text", text.toByteArray().size <= 32)
            assertNull(CameraPresentation.decode(text))
            assertNull(SharePresentation.decode(text))
            assertNull(ViewRequest.decode(text))
            true
        }
        ownership.connected()
        ownership.claim()
        ownership.release()
        ownership.receive("K1|C|3")
    }
}
