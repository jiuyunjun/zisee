package com.zisee.app.signaling

import com.zisee.app.call.IceServerConfig
import com.zisee.app.rtc.NativeRtcSession
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.IceCandidate

/** One serialized SDP owner; only the original caller offers, even if the callee changes networks. */
class MediaNegotiator(
    private val media: NativeRtcSession,
    private val caller: Boolean,
    private val refreshIce: suspend () -> List<IceServerConfig>,
) {
    var generation = 0; private set
    private var local: JSONObject? = null
    private var messageId = UUID.randomUUID().toString()
    private var sent = false
    private var cursor = 0
    private var sentCandidates = 0
    private var receivedCandidates = 0
    val complete: Boolean get() = sent && cursor > 0

    /** Returns true when adopting a generation, so the recovery owner consumes the triggering route. */
    suspend fun exchange(socket: MediaSignaling, callId: String, restart: Boolean): Boolean {
        fun payload() = JSONObject().put("callId", callId).put("generation", generation)
        try {
            val snapshot = socket.exchange("media.sync", payload().put("after", cursor)).getJSONObject("snapshot")
            val remoteGeneration = snapshot.optInt("generation", 0)
            require(remoteGeneration >= generation)
            if (remoteGeneration != generation) {
                val ice = refreshIce()
                media.prepareIceGeneration(ice)
                if (caller) media.restartIce()
                generation = remoteGeneration
                local = null; sent = false; cursor = 0; sentCandidates = 0; receivedCandidates = 0
                messageId = UUID.randomUUID().toString()
                return true // Poll with a fresh cursor before reading the new offer/answer.
            }
            if (restart) {
                socket.exchange("media.restart", payload())
                return false // Adoption happens from an authoritative snapshot, including after a lost ACK.
            }
            if (caller && local == null) {
                val offer = media.localDescription(true)
                local = JSONObject().put("type", "offer").put("sdp", offer.description)
            }
            suspend fun sendDescription() {
                if (local != null && !sent) {
                    socket.exchange("media.send", payload().put("description", local), messageId)
                    sent = true
                }
            }
            sendDescription()
            val descriptions = snapshot.getJSONArray("descriptions")
            for (index in 0 until descriptions.length()) {
                val description = descriptions.getJSONObject(index)
                val sequence = description.getInt("sequence")
                if (sequence <= cursor) continue
                val type = description.getString("type")
                require(type == if (caller) "answer" else "offer")
                media.remoteDescription(type, description.getString("sdp"))
                cursor = sequence
                if (!caller) {
                    val answer = media.localDescription(false)
                    local = JSONObject().put("type", "answer").put("sdp", answer.description)
                }
            }
            sendDescription()
            val candidates = media.localCandidates()
            if (sent && candidates.size > sentCandidates) {
                val batch = JSONArray()
                candidates.forEach { batch.put(JSONObject().put("candidate", it.sdp)
                    .put("sdpMid", it.sdpMid).put("sdpMLineIndex", it.sdpMLineIndex)) }
                socket.exchange("media.ice", payload().put("candidates", batch))
                sentCandidates = candidates.size
            }
            val incoming = snapshot.optJSONArray("candidates")
            if (cursor > 0 && incoming != null) while (receivedCandidates < incoming.length()) {
                val candidate = incoming.getJSONObject(receivedCandidates)
                media.addRemoteCandidate(IceCandidate(candidate.getString("sdpMid"),
                    candidate.getInt("sdpMLineIndex"), candidate.getString("candidate")))
                receivedCandidates++
            }
            return false
        } catch (error: StaleMediaGeneration) {
            // Another endpoint restarted between sync and send. Discard nothing until the next snapshot.
            return false
        }
    }
}
