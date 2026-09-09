package com.lazydoglab.zisee.auth.remote

import com.lazydoglab.zisee.auth.LocalIdentity
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TestSigner : DeviceSigner {
    val key = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    val proof = DeviceProof("zdev_abcdefghijklmnopqrstuvwx", AuthProtocol.base64(key.public.encoded))
    override suspend fun prepare() = proof
    override suspend fun sign(payload: ByteArray): String = AuthProtocol.base64(Signature.getInstance("SHA256withECDSA").run {
        initSign(key.private); update(payload); sign()
    })
    fun verify(payload: ByteArray, signature: String) = Signature.getInstance("SHA256withECDSA").run {
        initVerify(key.public); update(payload); verify(Base64.getUrlDecoder().decode(signature))
    }
}

class BackendApiTest {
    private val identity = LocalIdentity("zid_01991000-0000-7000-8000-000000000001", "九云", Instant.EPOCH, Instant.EPOCH)

    @Test fun signingPayloadMatchesGoVector() {
        assertEquals("zisee.bootstrap.v1\nzid_example\nzdev_example\nabc\n5Lmd5LqR",
            AuthProtocol.bootstrap("zid_example", DeviceProof("zdev_example", "abc"), "九云").toString(Charsets.UTF_8))
        assertEquals("zisee.auth.v1\nabc\nxyz", AuthProtocol.challenge("abc", "xyz").toString(Charsets.UTF_8))
    }

    @Test fun bootstrapChallengeAndLogoutFollowProtocol() = runBlocking {
        MockWebServer().apply { start(java.net.InetAddress.getByName("127.0.0.1"), 0) }.use { server ->
            val expiry = Instant.now().plusSeconds(900)
            val challenge = "c".repeat(43)
            val nonce = "n".repeat(43)
            val token = "t".repeat(43)
            server.enqueue(MockResponse().setBody(JSONObject().put("identityId", identity.identityId).toString()))
            server.enqueue(MockResponse().setBody(JSONObject().put("challengeId", challenge).put("nonce", nonce).toString()))
            server.enqueue(MockResponse().setBody(JSONObject().put("accessToken", token).put("tokenType", "Bearer").put("expiresAt", expiry).toString()))
            server.enqueue(MockResponse().setResponseCode(204))
            val signer = TestSigner()
            val api = BackendApi(server.url("/").newBuilder().host("127.0.0.1").build(), backendHttpClient())
            val session = api.login(identity, signer)
            assertEquals(token, session.token)
            val bootstrap = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/v1/identity/bootstrap", bootstrap.path)
            val body = JSONObject(bootstrap.body.readUtf8())
            assertEquals(identity.identityId, body.getString("identityId"))
            assertTrue(signer.verify(AuthProtocol.bootstrap(identity.identityId, signer.proof, identity.displayName), body.getString("signature")))
            val next = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/v1/auth/challenge", next.path)
            val auth = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/v1/auth/token", auth.path)
            assertTrue(signer.verify(AuthProtocol.challenge(challenge, nonce), JSONObject(auth.body.readUtf8()).getString("signature")))
            api.logout(session)
            val logout = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("DELETE", logout.method)
            assertEquals("Bearer $token", logout.getHeader("Authorization"))
            assertFalse(session.toString().contains(token))
        }
    }

    @Test fun conflictDoesNotRetryOrReplaceIdentity() = runBlocking {
        MockWebServer().apply { start(java.net.InetAddress.getByName("127.0.0.1"), 0) }.use { server ->
            server.enqueue(MockResponse().setResponseCode(409))
            try {
                BackendApi(server.url("/").newBuilder().host("127.0.0.1").build(), backendHttpClient()).login(identity, TestSigner())
                fail("conflict accepted")
            } catch (error: AuthFailure) { assertEquals(AuthFailure.Reason.CONFLICT, error.reason) }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun rejectsRedirectAndOversizeResponse() = runBlocking {
        for (response in listOf(MockResponse().setResponseCode(302).addHeader("Location", "https://example.com/"),
            MockResponse().setBody("a".repeat(9000)))) {
            MockWebServer().apply { start(java.net.InetAddress.getByName("127.0.0.1"), 0) }.use { server ->
                server.enqueue(response)
                try { BackendApi(server.url("/").newBuilder().host("127.0.0.1").build(), backendHttpClient()).login(identity, TestSigner()); fail("invalid response accepted") }
                catch (error: AuthFailure) { assertEquals(AuthFailure.Reason.PROTOCOL, error.reason) }
                assertEquals(1, server.requestCount)
            }
        }
    }
}
