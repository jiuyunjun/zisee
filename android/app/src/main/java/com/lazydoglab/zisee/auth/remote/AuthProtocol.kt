package com.lazydoglab.zisee.auth.remote

import java.io.IOException
import java.time.Instant
import java.util.Base64

/** Never expose credentials through data-class generated toString. */
class AccessSession(val token: String, val expiresAt: Instant)
class DeviceProof(val deviceId: String, val publicKey: String)
class AuthFailure(val reason: Reason) : IOException(reason.name) {
    enum class Reason { CONFLICT, KEY_UNAVAILABLE, UNAUTHORIZED, RATE_LIMITED, PROTOCOL, NETWORK }
}

object AuthProtocol {
    fun base64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun bootstrap(identityId: String, device: DeviceProof, name: String): ByteArray =
        "zisee.bootstrap.v1\n$identityId\n${device.deviceId}\n${device.publicKey}\n${base64(name.toByteArray(Charsets.UTF_8))}"
            .toByteArray(Charsets.UTF_8)

    fun challenge(id: String, nonce: String): ByteArray =
        "zisee.auth.v1\n$id\n$nonce".toByteArray(Charsets.UTF_8)
}

interface DeviceSigner {
    suspend fun prepare(): DeviceProof
    suspend fun sign(payload: ByteArray): String
}
