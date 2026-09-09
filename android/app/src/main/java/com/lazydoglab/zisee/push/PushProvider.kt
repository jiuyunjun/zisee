package com.lazydoglab.zisee.push

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

    /**
     * Null whenever FCM cannot register at all — most often a device with no usable Google Play
     * Services, which is the normal state of a mainland-China ROM. The reason is logged in debug
     * builds only, because it is the difference between "push is broken" and "this device has no
     * FCM to begin with" and there is nothing else on the device that can tell them apart yet.
     */
    override suspend fun currentToken(): String? =
        runCatching { FirebaseMessaging.getInstance().token.await() }
            .onFailure { PushDebug.tokenUnavailable(it) }
            .getOrNull()
}
