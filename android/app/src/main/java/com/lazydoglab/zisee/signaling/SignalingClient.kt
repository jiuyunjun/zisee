package com.lazydoglab.zisee.signaling

import kotlinx.coroutines.flow.Flow

/** Domain messages, not a finalized or authenticated wire protocol. */
sealed interface SignalingMessage {
    val callId: String
    data class Invite(override val callId: String, val peerIdentityId: String) : SignalingMessage
    data class Accept(override val callId: String) : SignalingMessage
    data class Reject(override val callId: String) : SignalingMessage
    data class End(override val callId: String) : SignalingMessage
    // Intentionally not data classes: default toString must not print SDP/candidate contents.
    class Description(override val callId: String, val kind: Kind, val sdp: String) : SignalingMessage {
        enum class Kind { OFFER, ANSWER }
    }
    class Candidate(
        override val callId: String,
        val sdpMid: String?,
        val sdpMLineIndex: Int,
        val candidate: String,
    ) : SignalingMessage
}

/** Authentication/bootstrap and short-lived TURN credentials must be implemented before networking.
 * identityId alone never authorizes sending messages. No media travels through this interface.
 */
interface SignalingClient {
    val incoming: Flow<SignalingMessage>
    suspend fun send(message: SignalingMessage)
    suspend fun disconnect()
}
