package com.lazydoglab.zisee.signaling

import com.lazydoglab.zisee.auth.remote.AccessSession
import com.lazydoglab.zisee.auth.remote.AuthFailure
import com.lazydoglab.zisee.auth.remote.BackendApi
import com.lazydoglab.zisee.auth.remote.backendHttpClient
import java.net.InetAddress
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MediaSignalingTest {
    @Test fun `network change interrupts stalled response without waiting for timeout`() = runBlocking {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        val client = backendHttpClient()
        val requestSeen = kotlinx.coroutines.CompletableDeferred<Unit>()
        server.enqueue(MockResponse().setHeader("Sec-WebSocket-Protocol", "zisee.v1").withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"v":1,"type":"session.ready"}""")
            }
            override fun onMessage(webSocket: WebSocket, text: String) { requestSeen.complete(Unit) }
        }))
        val transport = MediaSignaling(BackendApi(server.url("/").newBuilder().host("127.0.0.1").build(), client), client,
            AccessSession("t".repeat(43), Instant.now().plusSeconds(900)))
        try {
            withTimeout(5_000) {
                transport.ready()
                val pending = async {
                    try { transport.exchange("media.sync"); null }
                    catch (error: java.io.IOException) { error.message }
                }
                requestSeen.await()
                transport.networkChanged()
                withTimeout(1_000) { assertEquals("signaling_network_changed", pending.await()) }
            }
        } finally {
            transport.close(); server.close(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }

    @Test fun `SDP larger than heartbeat limit is sent with stable id and authorization`() = runBlocking {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        val client = backendHttpClient()
        val token = "t".repeat(43)
        val sdp = "v=0\r\n" + "a=test\r\n".repeat(1000)
        var received: JSONObject? = null
        server.enqueue(MockResponse().setHeader("Sec-WebSocket-Protocol", "zisee.v1").withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"v":1,"type":"session.ready"}""")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                received = JSONObject(text)
                webSocket.send("""{"v":1,"type":"media.ack","id":"retry-stable","sequence":1}""")
            }
        }))
        val api = BackendApi(server.url("/").newBuilder().host("127.0.0.1").build(), client)
        val transport = MediaSignaling(api, client, AccessSession(token, Instant.now().plusSeconds(900)))
        try {
            withTimeout(5_000) {
                transport.ready()
                val ack = transport.exchange("media.send", JSONObject().put("callId", "test-call")
                    .put("description", JSONObject().put("type", "offer").put("sdp", sdp)), "retry-stable")
                assertEquals(1, ack.getInt("sequence"))
                assertEquals(sdp, received!!.getJSONObject("description").getString("sdp"))
                assertEquals("retry-stable", received!!.getString("id"))
                assertFalse(received!!.has("peerId"))
                val candidates = org.json.JSONArray().put(JSONObject().put("candidate", "candidate:test")
                    .put("sdpMid", "0").put("sdpMLineIndex", 0))
                transport.exchange("media.ice", JSONObject().put("callId", "test-call").put("candidates", candidates), "retry-stable")
                assertEquals("media.ice", received!!.getString("type"))
                assertEquals(candidates.toString(), received!!.getJSONArray("candidates").toString())
            }
            val request = server.takeRequest()
            assertEquals("Bearer $token", request.getHeader("Authorization"))
            assertEquals("/v1/signaling", request.path)
        } finally {
            transport.close(); server.close(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }

    @Test fun `mismatched response cannot acknowledge another request`() = runBlocking {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        val client = backendHttpClient()
        server.enqueue(MockResponse().setHeader("Sec-WebSocket-Protocol", "zisee.v1").withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("""{"v":1,"type":"session.ready"}""") }
            override fun onMessage(webSocket: WebSocket, text: String) { webSocket.send("""{"v":1,"type":"media.snapshot","id":"wrong"}""") }
        }))
        val api = BackendApi(server.url("/").newBuilder().host("127.0.0.1").build(), client)
        val transport = MediaSignaling(api, client, AccessSession("t".repeat(43), Instant.now().plusSeconds(900)))
        try {
            withTimeout(5_000) {
                transport.ready()
                try { transport.exchange("media.sync"); fail("mismatched response accepted") }
                catch (error: AuthFailure) { assertEquals(AuthFailure.Reason.PROTOCOL, error.reason) }
            }
        } finally {
            transport.close(); server.close(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }
}
