package com.lazydoglab.zisee.signaling

import com.lazydoglab.zisee.auth.remote.AccessSession
import com.lazydoglab.zisee.auth.remote.AuthFailure
import com.lazydoglab.zisee.auth.remote.BackendApi
import java.time.Instant
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONException
import org.json.JSONObject

/** Authenticated transport only; does not implement the future call-message SignalingClient. */
class AuthenticatedSession(private val api: BackendApi, private val client: OkHttpClient) {
    suspend fun run(session: AccessSession, onReady: () -> Unit) {
        val incoming = Channel<String>(8)
        val request = Request.Builder().url(api.origin.resolve("v1/signaling")!!)
            .header("Authorization", "Bearer ${session.token}")
            .header("Sec-WebSocket-Protocol", "zisee.v1").build()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (response.header("Sec-WebSocket-Protocol") != "zisee.v1") {
                    incoming.close(AuthFailure(AuthFailure.Reason.PROTOCOL))
                    webSocket.cancel()
                }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.toByteArray(Charsets.UTF_8).size > 4096 || !incoming.trySend(text).isSuccess) {
                    incoming.close(AuthFailure(AuthFailure.Reason.PROTOCOL))
                    webSocket.cancel()
                }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                incoming.close(AuthFailure(AuthFailure.Reason.PROTOCOL))
                webSocket.cancel()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                incoming.close(AuthFailure(when (response?.code) {
                    401 -> AuthFailure.Reason.UNAUTHORIZED
                    429 -> AuthFailure.Reason.RATE_LIMITED
                    else -> AuthFailure.Reason.NETWORK
                }))
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                incoming.close(AuthFailure(AuthFailure.Reason.NETWORK))
                webSocket.close(code, "")
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                incoming.close(AuthFailure(AuthFailure.Reason.NETWORK))
            }
        })
        try {
            val ready = withTimeout(15_000) { parse(incoming.receive()) }
            if (ready.optString("type") != "session.ready") throw AuthFailure(AuthFailure.Reason.PROTOCOL)
            val expiry = try { Instant.parse(ready.optString("expiresAt")) }
            catch (error: java.time.format.DateTimeParseException) { throw AuthFailure(AuthFailure.Reason.PROTOCOL) }
            // Go JSON carries nanoseconds; PostgreSQL timestamptz retains microseconds.
            // Permit only storage precision loss, not a materially extended session.
            if (java.time.Duration.between(expiry, session.expiresAt).abs() > java.time.Duration.ofMillis(1)) {
                throw AuthFailure(AuthFailure.Reason.PROTOCOL)
            }
            onReady()
            var sequence = 0L
            while (Instant.now().isBefore(session.expiresAt.minusSeconds(30))) {
                val id = (++sequence).toString()
                if (!socket.send(JSONObject().put("v", 1).put("type", "ping").put("id", id).toString())) {
                    throw AuthFailure(AuthFailure.Reason.NETWORK)
                }
                val pong = withTimeout(10_000) { parse(incoming.receive()) }
                if (pong.optString("type") != "pong" || pong.optString("id") != id) {
                    throw AuthFailure(AuthFailure.Reason.PROTOCOL)
                }
                kotlinx.coroutines.delay(20_000)
            }
        } finally {
            socket.cancel()
            incoming.cancel()
        }
    }

    private fun parse(value: String): JSONObject {
        val json = try { JSONObject(value) }
        catch (error: JSONException) { throw AuthFailure(AuthFailure.Reason.PROTOCOL) }
        if (json.optInt("v") != 1) throw AuthFailure(AuthFailure.Reason.PROTOCOL)
        return json
    }
}
