package com.zisee.app.signaling

import com.zisee.app.auth.remote.AccessSession
import com.zisee.app.auth.remote.AuthFailure
import com.zisee.app.auth.remote.BackendApi
import com.zisee.app.auth.remote.backendHttpClient
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
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

class AuthenticatedSessionTest {
    @Test fun readyHeartbeatAndCancellationCloseSocket() = runBlocking {
        MockWebServer().apply { start(java.net.InetAddress.getByName("127.0.0.1"), 0) }.use { server ->
            val expiry = Instant.now().plusSeconds(900)
            val ping = CompletableDeferred<Unit>()
            val closed = CompletableDeferred<Unit>()
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(JSONObject().put("v", 1).put("type", "session.ready")
                        .put("expiresAt", expiry.truncatedTo(java.time.temporal.ChronoUnit.MICROS)).toString())
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val message = JSONObject(text)
                    if (message.optString("type") == "ping") {
                        webSocket.send(JSONObject().put("v", 1).put("type", "pong").put("id", message.getString("id")).toString())
                        ping.complete(Unit)
                    }
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { closed.complete(Unit) }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.complete(Unit) }
            }).addHeader("Sec-WebSocket-Protocol", "zisee.v1"))
            val client = backendHttpClient()
            val ready = CompletableDeferred<Unit>()
            val job = launch {
                AuthenticatedSession(BackendApi(server.url("/").newBuilder().host("127.0.0.1").build(), client), client)
                    .run(AccessSession("t".repeat(43), expiry)) { ready.complete(Unit) }
            }
            withTimeout(5_000) { ready.await(); ping.await() }
            job.cancelAndJoin()
            withTimeout(5_000) { closed.await() }
            val request = server.takeRequest()
            assertEquals("/v1/signaling", request.path)
            assertEquals("zisee.v1", request.getHeader("Sec-WebSocket-Protocol"))
            assertEquals("Bearer " + "t".repeat(43), request.getHeader("Authorization"))
        }
    }

    @Test fun unauthorizedHandshakeNeverReportsReady() = runBlocking {
        MockWebServer().apply { start(java.net.InetAddress.getByName("127.0.0.1"), 0) }.use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            val client = backendHttpClient()
            try {
                AuthenticatedSession(BackendApi(server.url("/").newBuilder().host("127.0.0.1").build(), client), client)
                    .run(AccessSession("t".repeat(43), Instant.now().plusSeconds(900))) { fail("unauthenticated ready") }
                fail("unauthenticated connection accepted")
            } catch (error: AuthFailure) { assertEquals(AuthFailure.Reason.UNAUTHORIZED, error.reason) }
        }
    }
}
