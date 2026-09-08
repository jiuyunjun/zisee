package com.zisee.app.call.state

import org.junit.Assert.assertEquals
import org.junit.Test

class CallReducerTest {
    private val session = CallSession("call-1", "zid_peer")

    @Test fun outgoingCallReconnectsAndReleasesBeforeReset() {
        var state = CallReducer.start(CallState(), session, incoming = false)
        assertEquals(CallPhase.OUTGOING, state.phase)
        listOf(
            CallEvent.ACCEPT to CallPhase.CONNECTING,
            CallEvent.MEDIA_CONNECTED to CallPhase.CONNECTED,
            CallEvent.CONNECTION_LOST to CallPhase.RECONNECTING,
            CallEvent.MEDIA_CONNECTED to CallPhase.CONNECTED,
            CallEvent.HANG_UP to CallPhase.ENDING,
            CallEvent.RELEASED to CallPhase.ENDED,
            CallEvent.RESET to CallPhase.IDLE,
        ).forEach { (event, expected) ->
            state = CallReducer.reduce(state, session.callId, event)
            assertEquals(expected, state.phase)
        }
        assertEquals(CallState(), state)
    }

    @Test fun incomingCanBeRejectedAndLateCallbacksAreIgnored() {
        val incoming = CallReducer.start(CallState(), session, incoming = true)
        val ending = CallReducer.reduce(incoming, session.callId, CallEvent.HANG_UP)
        assertEquals(ending, CallReducer.reduce(ending, session.callId, CallEvent.MEDIA_CONNECTED))
        assertEquals(ending, CallReducer.reduce(ending, session.callId, CallEvent.RESET))
        assertEquals(incoming, CallReducer.reduce(incoming, "old-call", CallEvent.HANG_UP))
        assertEquals(incoming, CallReducer.start(incoming, CallSession("other", "peer"), false))
    }

    @Test fun failureIsTerminalUntilExplicitReset() {
        val connecting = CallState(CallPhase.CONNECTING, session)
        val failed = CallReducer.reduce(connecting, session.callId, CallEvent.FAIL)
        assertEquals(CallPhase.FAILED, failed.phase)
        assertEquals(failed, CallReducer.reduce(failed, session.callId, CallEvent.MEDIA_CONNECTED))
        assertEquals(CallState(), CallReducer.reduce(failed, session.callId, CallEvent.RESET))
    }

    @Test fun idleIgnoresCallbacks() {
        CallEvent.entries.forEach {
            assertEquals(CallState(), CallReducer.reduce(CallState(), "old-call", it))
        }
    }
}
