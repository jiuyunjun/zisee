package com.zisee.app.signaling

import com.zisee.app.auth.remote.AccessSession
import com.zisee.app.auth.remote.AuthFailure
import com.zisee.app.auth.remote.BackendApi
import java.io.Closeable
import java.io.IOException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject

/** One request at a time; caller owns reconnect and retains acknowledged SDP/cursor in memory. */
class MediaSignaling(api: BackendApi, client: OkHttpClient, session: AccessSession) : Closeable {
    private val incoming = Channel<String>(8)
    private var sequence = 0L
    private val socket = client.newWebSocket(Request.Builder().url(api.origin.resolve("v1/signaling")!!)
        .header("Authorization", "Bearer ${session.token}")
        .header("Sec-WebSocket-Protocol", "zisee.v1").build(), object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (response.header("Sec-WebSocket-Protocol") != "zisee.v1") fail(webSocket)
        }
        override fun onMessage(webSocket: WebSocket, text: String) {
            if (text.toByteArray(Charsets.UTF_8).size > 65536 || !incoming.trySend(text).isSuccess) fail(webSocket)
        }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) { fail(webSocket) }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            incoming.close(AuthFailure(if (response?.code == 401) AuthFailure.Reason.UNAUTHORIZED else AuthFailure.Reason.NETWORK))
        }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            incoming.close(IOException("signaling_closed")); webSocket.close(code, "")
        }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { incoming.close(IOException("signaling_closed")) }
        private fun fail(webSocket: WebSocket) {
            incoming.close(AuthFailure(AuthFailure.Reason.PROTOCOL)); webSocket.cancel()
        }
    })

    suspend fun ready() {
        val json = receive()
        if (json.optString("type") != "session.ready") throw AuthFailure(AuthFailure.Reason.PROTOCOL)
    }

    suspend fun exchange(type: String, payload: JSONObject = JSONObject(), stableId: String? = null): JSONObject {
        val id = stableId ?: "q${++sequence}"
        if (!socket.send(payload.put("v", 1).put("type", type).put("id", id).toString())) throw IOException("signaling_send")
        val result = receive()
        if (result.optString("id") != id || result.optString("type") == "error") throw AuthFailure(AuthFailure.Reason.PROTOCOL)
        val expected = when (type) { "call.sync" -> "call.snapshot"; "media.send", "media.ice" -> "media.ack"; "media.sync" -> "media.snapshot"; else -> "pong" }
        if (result.optString("type") != expected) throw AuthFailure(AuthFailure.Reason.PROTOCOL)
        return result
    }

    private suspend fun receive(): JSONObject = withTimeoutOrNull(10_000) {
        val json = JSONObject(incoming.receive())
        if (json.optInt("v") != 1) throw AuthFailure(AuthFailure.Reason.PROTOCOL)
        json
    } ?: throw IOException("signaling_timeout")
    override fun close() { socket.cancel(); incoming.cancel() }
}
