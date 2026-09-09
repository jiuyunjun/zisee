package com.zisee.app.push

import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await

/**
 * The wake channel the device is registered on. Abstracted from day one so a
 * later HMS / OEM push provider (docs/architecture/CALL_DELIVERY.md §33) does
 * not have to reach into the call code.
 */
interface PushProvider {
    /** Provider name sent to the backend, e.g. "fcm". */
    val name: String

    /** Current registration token, or null when the provider is unavailable. */
    suspend fun currentToken(): String?
}

class FcmPushProvider : PushProvider {
    override val name = "fcm"

    override suspend fun currentToken(): String? =
        runCatching { FirebaseMessaging.getInstance().token.await() }.getOrNull()
}
