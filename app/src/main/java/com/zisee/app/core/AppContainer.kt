package com.zisee.app.core

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import com.zisee.app.auth.DataStoreIdentityRepository
import com.zisee.app.auth.IdentityRepository
import com.zisee.app.core.logging.AndroidAppLogger
import com.zisee.app.core.logging.AppLogger

private val Context.identityStore by preferencesDataStore(name = "local_identity")

/** Application-scoped composition root. Media resources must later have a call-scoped owner. */
class AppContainer(context: Context) {
    val identityRepository: IdentityRepository =
        DataStoreIdentityRepository(context.applicationContext.identityStore)
    val logger: AppLogger = AndroidAppLogger
}
