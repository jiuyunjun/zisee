package com.zisee.app.auth.remote

import com.zisee.app.auth.LocalIdentity
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject

/** Dedicated client: no redirects, logging interceptors, disk cache or ambient authenticators. */
fun backendHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
    .callTimeout(20, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
    .retryOnConnectionFailure(false).build()

class BackendApi(val origin: HttpUrl, private val client: OkHttpClient) {
    init {
        require(origin.username.isEmpty() && origin.password.isEmpty())
        require(origin.encodedPath == "/" && origin.query == null && origin.fragment == null)
        // Plain HTTP is allowed only for loopback JVM test servers, never production origins.
        require(origin.isHttps || origin.host in setOf("localhost", "127.0.0.1", "::1"))
    }

    suspend fun login(identity: LocalIdentity, signer: DeviceSigner): AccessSession {
        val device = signer.prepare()
        val registration = JSONObject().put("identityId", identity.identityId)
            .put("deviceId", device.deviceId).put("displayName", identity.displayName)
            .put("publicKey", device.publicKey)
            .put("signature", signer.sign(AuthProtocol.bootstrap(identity.identityId, device, identity.displayName)))
        val registered = request("POST", "v1/identity/bootstrap", registration)
        if (registered.optString("identityId") != identity.identityId) protocolError()
        val challenge = request("POST", "v1/auth/challenge", JSONObject().put("deviceId", device.deviceId))
        val id = challenge.optString("challengeId")
        val nonce = challenge.optString("nonce")
        if (!id.matches(Regex("[A-Za-z0-9_-]{43}")) || !nonce.matches(Regex("[A-Za-z0-9_-]{43}"))) protocolError()
        val result = request("POST", "v1/auth/token", JSONObject().put("challengeId", id)
            .put("signature", signer.sign(AuthProtocol.challenge(id, nonce))))
        val token = result.optString("accessToken")
        if (!token.matches(Regex("[A-Za-z0-9_-]{43}")) || result.optString("tokenType") != "Bearer") protocolError()
        val expires = try { Instant.parse(result.optString("expiresAt")) }
        catch (error: java.time.format.DateTimeParseException) { protocolError() }
        if (!expires.isAfter(Instant.now()) || expires.isAfter(Instant.now().plusSeconds(960))) protocolError()
        return AccessSession(token, expires)
    }

    suspend fun logout(session: AccessSession) { request("DELETE", "v1/auth/session", null, session.token) }

    internal suspend fun request(method: String, path: String, payload: JSONObject?, token: String? = null): JSONObject =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(origin.resolve(path)!!)
                .method(method, payload?.toString()?.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .apply { if (token != null) header("Authorization", "Bearer $token") }.build()
            client.newCall(request).jsonResponse()
        }
}

private fun protocolError(): Nothing = throw AuthFailure(AuthFailure.Reason.PROTOCOL)

private suspend fun Call.jsonResponse(): JSONObject = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) continuation.resumeWithException(AuthFailure(AuthFailure.Reason.NETWORK))
        }
        override fun onResponse(call: Call, response: Response) {
            val result = runCatching {
                response.use {
                    if (!it.isSuccessful) throw AuthFailure(when (it.code) {
                        401 -> AuthFailure.Reason.UNAUTHORIZED
                        409 -> AuthFailure.Reason.CONFLICT
                        429 -> AuthFailure.Reason.RATE_LIMITED
                        in 500..599 -> AuthFailure.Reason.NETWORK
                        else -> AuthFailure.Reason.PROTOCOL
                    })
                    if (it.code == 204) return@use JSONObject()
                    val source = it.body?.source() ?: protocolError()
                    if (source.request(8193)) protocolError()
                    try { JSONObject(source.readUtf8()) }
                    catch (error: JSONException) { protocolError() }
                }
            }
            if (!continuation.isCancelled) result.fold(
                { continuation.resume(it) },
                { continuation.resumeWithException(if (it is AuthFailure) it else AuthFailure(AuthFailure.Reason.NETWORK)) },
            )
        }
    })
}
