package com.lazydoglab.zisee.auth.remote

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.security.GeneralSecurityException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One instance per identity and backend origin. Marker is persisted before any network request.
 * Missing/replaced keys after initialization are errors; never silently create a new identity.
 */
class KeystoreDeviceSigner(
    identityId: String,
    origin: String,
    private val store: DataStore<Preferences>,
) : DeviceSigner {
    private val alias = "zisee.device." + AuthProtocol.base64(
        MessageDigest.getInstance("SHA-256").digest("$origin\n$identityId".toByteArray(Charsets.UTF_8)),
    )
    private val marker = stringPreferencesKey(alias)
    private val mutex = Mutex()

    override suspend fun prepare(): DeviceProof = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val saved = store.data.first()[marker]
                val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                if (!keys.containsAlias(alias)) {
                    if (saved != null) throw AuthFailure(AuthFailure.Reason.KEY_UNAVAILABLE)
                    KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                        initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                            .setDigests(KeyProperties.DIGEST_SHA256)
                            .build())
                    }.generateKeyPair()
                }
                val entry = keys.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
                    ?: throw AuthFailure(AuthFailure.Reason.KEY_UNAVAILABLE)
                val encoded = entry.certificate.publicKey.encoded
                val publicKey = AuthProtocol.base64(encoded)
                if (saved != null && saved != publicKey) throw AuthFailure(AuthFailure.Reason.KEY_UNAVAILABLE)
                store.edit { it[marker] = publicKey }
                // Randomized installation key determines a stable, non-hardware device ID.
                DeviceProof("zdev_" + AuthProtocol.base64(MessageDigest.getInstance("SHA-256").digest(encoded)), publicKey)
            } catch (error: GeneralSecurityException) {
                throw AuthFailure(AuthFailure.Reason.KEY_UNAVAILABLE)
            } catch (error: java.security.ProviderException) {
                throw AuthFailure(AuthFailure.Reason.KEY_UNAVAILABLE)
            }
        }
    }

    override suspend fun sign(payload: ByteArray): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                val entry = keys.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
                    ?: throw AuthFailure(AuthFailure.Reason.KEY_UNAVAILABLE)
                val saved = store.data.first()[marker]
                if (saved == null || saved != AuthProtocol.base64(entry.certificate.publicKey.encoded)) {
                    throw AuthFailure(AuthFailure.Reason.KEY_UNAVAILABLE)
                }
                AuthProtocol.base64(Signature.getInstance("SHA256withECDSA").run {
                    initSign(entry.privateKey)
                    update(payload)
                    sign()
                })
            } catch (error: GeneralSecurityException) {
                throw AuthFailure(AuthFailure.Reason.KEY_UNAVAILABLE)
            } catch (error: java.security.ProviderException) {
                throw AuthFailure(AuthFailure.Reason.KEY_UNAVAILABLE)
            }
        }
    }
}
