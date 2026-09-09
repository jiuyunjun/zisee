package com.lazydoglab.zisee.core

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import com.lazydoglab.zisee.auth.DataStoreIdentityRepository
import com.lazydoglab.zisee.auth.IdentityRepository
import com.lazydoglab.zisee.core.logging.AndroidAppLogger
import com.lazydoglab.zisee.core.logging.AppLogger
import com.lazydoglab.zisee.BuildConfig
import com.lazydoglab.zisee.auth.remote.BackendApi
import com.lazydoglab.zisee.auth.remote.BackendConnection
import com.lazydoglab.zisee.auth.remote.KeystoreDeviceSigner
import com.lazydoglab.zisee.call.CallPreferences
import com.lazydoglab.zisee.auth.remote.backendHttpClient
import com.lazydoglab.zisee.push.CallNotifications
import com.lazydoglab.zisee.push.FcmPushProvider
import com.lazydoglab.zisee.push.IncomingPushGate
import com.lazydoglab.zisee.push.PushTokenManager
import com.lazydoglab.zisee.signaling.AuthenticatedSession
import okhttp3.HttpUrl.Companion.toHttpUrl

private val Context.identityStore by preferencesDataStore(name = "local_identity")
private val Context.deviceStore by preferencesDataStore(name = "device_public_keys")
private val Context.callStore by preferencesDataStore(name = "call_preferences")
private val Context.pushStore by preferencesDataStore(name = "push_state")

/** Application-scoped composition root. Media resources must later have a call-scoped owner. */
class AppContainer(context: Context) {
    val identityRepository: IdentityRepository =
        DataStoreIdentityRepository(context.applicationContext.identityStore)
    val callPreferences = CallPreferences(context.applicationContext.callStore)
    val logger: AppLogger = AndroidAppLogger
    val callNotifications = CallNotifications(context.applicationContext)
    val pushProvider = FcmPushProvider()
    val pushTokenManager = PushTokenManager(context.applicationContext.pushStore, pushProvider, logger)
    val incomingPushGate = IncomingPushGate(
        callNotifications::showIncoming, logger, context.applicationContext.pushStore,
    )
    private val signers = mutableMapOf<String, KeystoreDeviceSigner>()
    val httpClient = backendHttpClient()
    val backendApi = BuildConfig.BACKEND_URL.takeIf { it.isNotBlank() }?.let { BackendApi(it.toHttpUrl(), httpClient) }
    val deviceSigner: (String) -> KeystoreDeviceSigner = { identityId ->
            synchronized(signers) {
                signers.getOrPut(identityId) {
                    KeystoreDeviceSigner(identityId, requireNotNull(backendApi).origin.toString(), context.applicationContext.deviceStore)
                }
            }
    }
    val backend: BackendConnection? = backendApi?.let { api ->
        BackendConnection(api, AuthenticatedSession(api, httpClient), deviceSigner, logger)
    }
}
