package com.lazydoglab.zisee.push

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lazydoglab.zisee.auth.remote.AccessSession
import com.lazydoglab.zisee.call.CallApi
import com.lazydoglab.zisee.core.logging.AppEvent
import com.lazydoglab.zisee.core.logging.AppLogger
import com.lazydoglab.zisee.core.logging.FailureReason
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps the backend's Device Registry (docs/architecture/CALL_DELIVERY.md §23–§24)
 * in sync with this device's push token. The token can change on restore,
 * reinstall or clear-data, so the last value registered with the server is
 * persisted and re-checked, not assumed permanent.
 */
class PushTokenManager(
    private val store: DataStore<Preferences>,
    private val provider: PushProvider,
    private val logger: AppLogger,
) {
    private val mutex = Mutex()

    /** Push the current token to the backend if it differs from the last synced one. */
    suspend fun sync(api: CallApi, session: AccessSession, deviceId: String) {
        mutex.withLock {
            val token = provider.currentToken()?.takeIf { it.isNotBlank() } ?: return
            val synced = runCatching { store.data.first()[SYNCED_KEY] }.getOrNull()
            if (token == synced) return
            try {
                api.registerPushToken(session, deviceId, provider.name, token)
                runCatching { store.edit { it[SYNCED_KEY] = token } }
            } catch (error: Exception) {
                logger.error(AppEvent.PUSH_REGISTER_FAILED, FailureReason.of(error))
            }
        }
    }

    /** FCM rotated the token: forget what we synced so the next session re-registers. */
    suspend fun onNewToken() {
        runCatching { store.edit { it.remove(SYNCED_KEY) } }
    }

    private companion object {
        val SYNCED_KEY = stringPreferencesKey("synced_push_token")
    }
}
