package com.lazydoglab.zisee.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.lazydoglab.zisee.ZiseeApplication
import com.lazydoglab.zisee.core.logging.AppEvent
import kotlinx.coroutines.runBlocking

/**
 * FCM entry point. The process may have just been created for this callback
 * (docs/architecture/CALL_DELIVERY.md §30), so it does as little as possible:
 * parse, hand to [IncomingPushGate], done. No network, no profile fetch (§10).
 */
class ZiseeFirebaseMessagingService : FirebaseMessagingService() {

    private val container get() = (application as ZiseeApplication).container

    override fun onMessageReceived(message: RemoteMessage) {
        val invite = CallInvite.parse(message.data)
        if (invite == null) {
            container.logger.info(AppEvent.PUSH_IGNORED)
            return
        }
        // onMessageReceived must finish its work before returning: afterwards the
        // freshly started process may be killed before any detached work runs (§10).
        runBlocking { container.incomingPushGate.onInvite(invite) }
    }

    override fun onNewToken(token: String) {
        runBlocking { container.pushTokenManager.onNewToken() }
    }
}
