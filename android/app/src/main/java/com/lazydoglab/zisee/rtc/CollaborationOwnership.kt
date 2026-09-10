package com.lazydoglab.zisee.rtc

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One call has one collaboration slot, and both ends must agree who holds it before either starts a
 * device. §5.2: a screen share and an AR field are mutually exclusive, and two screens never run at
 * once.
 *
 * Ownership is claimed and granted rather than announced, because announcing is exactly what fails
 * when both users tap at the same moment: each would see the other's announcement only after its
 * own consent dialog was already up. A claim asks first and starts nothing until the answer arrives.
 *
 * Messages ride the existing bounded control channel, so a peer too old to understand them simply
 * never answers and the claim times out. Refusing to share is the safe outcome of that, not a
 * reason to start anyway.
 */
class CollaborationOwnership(
    /** The caller side, resolving a simultaneous claim the same way an AR field conflict is. */
    private val localWinsConflict: Boolean,
    private val send: (String) -> Boolean,
) {
    enum class Phase {
        /** Nobody holds the slot here. */
        IDLE,
        /** This end asked and is waiting for the peer's answer. No device has been started. */
        CLAIMING,
        /** This end holds the slot and may start its consent and capture. */
        HELD,
        /** The peer holds it; this end must not start anything until it is released. */
        REMOTE_HELD,
    }

    /** Why the most recent claim did not become ownership, for the message shown to the user. */
    enum class Refusal { PEER_HOLDS, PEER_CLAIMED_FIRST, NO_ANSWER, CHANNEL_UNAVAILABLE }

    data class State(
        val phase: Phase = Phase.IDLE,
        val connected: Boolean = false,
        val refusal: Refusal? = null,
    )

    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    /** Identifies this end's outstanding claim so a late answer to an abandoned one is ignored. */
    private var request = 0L
    private var peerRequest: Long? = null

    fun connected() { mutable.value = mutable.value.copy(connected = true) }

    /**
     * The channel is gone, so no new collaboration may begin. An existing local share keeps its
     * media: the projection is this device's own and is not the peer's to invalidate. A share the
     * peer held can no longer be confirmed, so the slot stops being reported as taken.
     */
    fun disconnected() {
        peerRequest = null
        mutable.value = mutable.value.copy(
            connected = false,
            phase = when (mutable.value.phase) {
                Phase.HELD -> Phase.HELD
                else -> Phase.IDLE
            },
            refusal = if (mutable.value.phase == Phase.CLAIMING) Refusal.CHANNEL_UNAVAILABLE else null,
        )
    }

    /**
     * Asks for the slot. Returns false when this end must not even ask, so the caller can explain
     * why instead of opening a system consent dialog it would have to undo.
     *
     * The caller schedules the deadline and calls [claimExpired]; nothing here owns a timer.
     */
    fun claim(): Boolean {
        val current = mutable.value
        if (!current.connected) {
            mutable.value = current.copy(refusal = Refusal.CHANNEL_UNAVAILABLE)
            return false
        }
        if (current.phase != Phase.IDLE) {
            mutable.value = current.copy(
                refusal = if (current.phase == Phase.REMOTE_HELD) Refusal.PEER_HOLDS else current.refusal)
            return false
        }
        // The phase moves before the message leaves. A transport that answers synchronously would
        // otherwise deliver the grant into an IDLE state machine, which would discard it and leave
        // the peer believing this end owns a slot it never took.
        val id = request + 1
        request = id
        mutable.value = current.copy(phase = Phase.CLAIMING, refusal = null)
        if (!send(encode(CLAIM, id))) {
            // Roll back only what is still this claim; a synchronous answer may have moved it on.
            if (mutable.value.phase == Phase.CLAIMING && request == id) {
                mutable.value = mutable.value.copy(phase = Phase.IDLE, refusal = Refusal.CHANNEL_UNAVAILABLE)
            }
            return false
        }
        return true
    }

    /** A peer that never answers leaves the slot free rather than claimed forever. */
    fun claimExpired(id: Long) {
        if (mutable.value.phase != Phase.CLAIMING || id != request) return
        mutable.value = mutable.value.copy(phase = Phase.IDLE, refusal = Refusal.NO_ANSWER)
    }

    /** The id of the claim currently outstanding, for scheduling and matching its deadline. */
    val outstanding: Long? get() = request.takeIf { mutable.value.phase == Phase.CLAIMING }

    /** The owner stops on its own terms; it is never stopped remotely. Idempotent. */
    fun release() {
        val current = mutable.value
        if (current.phase != Phase.HELD && current.phase != Phase.CLAIMING) return
        mutable.value = current.copy(phase = Phase.IDLE, refusal = null)
        if (current.connected) send(encode(RELEASE, request))
    }

    fun receive(text: String): Boolean {
        val message = decode(text) ?: return false
        val (kind, id) = message
        val current = mutable.value
        if (!current.connected) return true
        when (kind) {
            CLAIM -> when (current.phase) {
                // Holding the slot, or having already been granted it, is a final refusal: the
                // owner is never stopped by someone else asking.
                Phase.HELD -> send(encode(DENY, id))
                Phase.REMOTE_HELD -> {
                    // The peer re-claiming what it already holds is a repeat, not a conflict.
                    peerRequest = id
                    send(encode(GRANT, id))
                }
                Phase.CLAIMING -> if (localWinsConflict) send(encode(DENY, id)) else {
                    // Both asked at once. The side that does not win abandons its own claim rather
                    // than letting two consent dialogs race two projections.
                    grant(id, current.copy(refusal = Refusal.PEER_CLAIMED_FIRST))
                }
                Phase.IDLE -> grant(id, current.copy(refusal = null))
            }
            GRANT -> if (current.phase == Phase.CLAIMING && id == request) {
                mutable.value = current.copy(phase = Phase.HELD, refusal = null)
            }
            DENY -> if (current.phase == Phase.CLAIMING && id == request) {
                mutable.value = current.copy(phase = Phase.IDLE, refusal = Refusal.PEER_HOLDS)
            }
            RELEASE -> if (current.phase == Phase.REMOTE_HELD && id == peerRequest) {
                peerRequest = null
                mutable.value = current.copy(phase = Phase.IDLE, refusal = null)
            }
        }
        return true
    }

    /** Hands the slot to the peer, taking it back only if the grant could not be sent at all. */
    private fun grant(id: Long, next: State) {
        peerRequest = id
        mutable.value = next.copy(phase = Phase.REMOTE_HELD)
        if (send(encode(GRANT, id))) return
        peerRequest = null
        if (mutable.value.phase == Phase.REMOTE_HELD) {
            mutable.value = mutable.value.copy(phase = Phase.IDLE)
        }
    }

    companion object {
        private const val PREFIX = "K1"
        private const val CLAIM = "C"
        private const val GRANT = "G"
        private const val DENY = "D"
        private const val RELEASE = "R"
        /** Keeps every message inside the control channel's 32-byte bound. */
        const val MAX_REQUEST = 999_999L
        /** Long enough for a round trip on a poor link, short enough that a peer which cannot
         * answer does not hold the share entry hostage. */
        const val CLAIM_TIMEOUT_MS = 5_000L

        private fun encode(kind: String, id: Long) = "$PREFIX|$kind|${id.coerceIn(0, MAX_REQUEST)}"

        /** Peer-supplied text: every field is bounded before it can reach the state machine. */
        private fun decode(text: String): Pair<String, Long>? {
            if (text.length > 32) return null
            val fields = text.split('|')
            if (fields.size != 3 || fields[0] != PREFIX) return null
            if (fields[1] !in setOf(CLAIM, GRANT, DENY, RELEASE)) return null
            val id = fields[2].toLongOrNull() ?: return null
            if (id !in 0..MAX_REQUEST) return null
            return fields[1] to id
        }
    }
}
