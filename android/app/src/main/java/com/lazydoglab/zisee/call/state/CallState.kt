package com.lazydoglab.zisee.call.state

enum class CallPhase {
    IDLE, INCOMING, OUTGOING, CONNECTING, CONNECTED, RECONNECTING, ENDING, ENDED, FAILED,
}

data class CallSession(val callId: String, val peerIdentityId: String) {
    init {
        require(callId.isNotBlank())
        require(peerIdentityId.isNotBlank())
    }
}

data class CallState(val phase: CallPhase = CallPhase.IDLE, val session: CallSession? = null) {
    init { require((phase == CallPhase.IDLE) == (session == null)) }
}

enum class CallEvent { ACCEPT, MEDIA_CONNECTED, CONNECTION_LOST, HANG_UP, RELEASED, FAIL, RESET }

/** Pure reducer. The future controller serializes events and performs signaling/media effects.
 * Every callback carries a call ID so late callbacks cannot modify a subsequent call.
 */
object CallReducer {
    fun start(state: CallState, session: CallSession, incoming: Boolean): CallState =
        if (state.phase == CallPhase.IDLE) {
            CallState(if (incoming) CallPhase.INCOMING else CallPhase.OUTGOING, session)
        } else state

    fun reduce(state: CallState, callId: String, event: CallEvent): CallState {
        if (state.session?.callId != callId) return state
        val phase = state.phase
        val next = when (event) {
            CallEvent.ACCEPT -> if (phase in setOf(CallPhase.INCOMING, CallPhase.OUTGOING))
                CallPhase.CONNECTING else phase
            CallEvent.MEDIA_CONNECTED -> if (phase in setOf(CallPhase.CONNECTING, CallPhase.RECONNECTING))
                CallPhase.CONNECTED else phase
            CallEvent.CONNECTION_LOST -> if (phase == CallPhase.CONNECTED) CallPhase.RECONNECTING else phase
            CallEvent.HANG_UP -> if (phase in activePhases) CallPhase.ENDING else phase
            CallEvent.RELEASED -> if (phase == CallPhase.ENDING) CallPhase.ENDED else phase
            CallEvent.FAIL -> if (phase in activePhases) CallPhase.FAILED else phase
            CallEvent.RESET -> if (phase in setOf(CallPhase.ENDED, CallPhase.FAILED)) CallPhase.IDLE else phase
        }
        return if (next == CallPhase.IDLE) CallState() else state.copy(phase = next)
    }

    private val activePhases = setOf(
        CallPhase.INCOMING, CallPhase.OUTGOING, CallPhase.CONNECTING,
        CallPhase.CONNECTED, CallPhase.RECONNECTING,
    )
}
