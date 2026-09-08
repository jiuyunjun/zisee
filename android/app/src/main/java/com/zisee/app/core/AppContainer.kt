package com.zisee.app.core

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import com.zisee.app.auth.DataStoreIdentityRepository
import com.zisee.app.auth.IdentityRepository
import com.zisee.app.core.logging.AndroidAppLogger
import com.zisee.app.core.logging.AppLogger
import com.zisee.app.BuildConfig
import com.zisee.app.auth.remote.BackendApi
import com.zisee.app.auth.remote.BackendConnection
import com.zisee.app.auth.remote.KeystoreDeviceSigner
import com.zisee.app.auth.remote.backendHttpClient
import com.zisee.app.signaling.AuthenticatedSession
import okhttp3.HttpUrl.Companion.toHttpUrl

private val Context.identityStore by preferencesDataStore(name = "local_identity")
private val Context.deviceStore by preferencesDataStore(name = "device_public_keys")

/** Application-scoped composition root. Media resources must later have a call-scoped owner. */
class AppContainer(context: Context) {
    val identityRepository: IdentityRepository =
        DataStoreIdentityRepository(context.applicationContext.identityStore)
    val logger: AppLogger = AndroidAppLogger
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
