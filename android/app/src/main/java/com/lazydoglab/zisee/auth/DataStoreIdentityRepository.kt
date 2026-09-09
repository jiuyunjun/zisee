package com.lazydoglab.zisee.auth

import androidx.datastore.core.DataStore
import androidx.datastore.core.CorruptionException
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.flow.map

class DataStoreIdentityRepository(
    private val store: DataStore<Preferences>,
    private val ids: IdentityIdGenerator = IdentityIdGenerator(),
    private val clock: Clock = Clock.systemUTC(),
) : IdentityRepository {
    override val identity = store.data.map(::decode)

    override suspend fun create(displayName: String) {
        val name = DisplayName.normalize(displayName)
        store.edit { values ->
            // DataStore serializes this transaction, including concurrent create requests.
            if (decode(values) == null) {
                val now = clock.millis()
                values[Id] = ids.newId()
                values[Name] = name
                values[Created] = now
                values[Updated] = now
            }
        }
    }

    override suspend fun rename(displayName: String) {
        val name = DisplayName.normalize(displayName)
        store.edit { values ->
            checkNotNull(decode(values)) { "Identity does not exist" }
            values[Name] = name
            values[Updated] = clock.millis()
        }
    }

    private fun decode(values: Preferences): LocalIdentity? {
        if (listOf(Id, Name, Created, Updated).none { values.contains(it) }) return null
        val id = values[Id] ?: throw CorruptionException("Missing identity ID")
        val name = values[Name] ?: throw CorruptionException("Missing display name")
        val created = values[Created] ?: throw CorruptionException("Missing creation time")
        val updated = values[Updated] ?: throw CorruptionException("Missing update time")
        return LocalIdentity(id, name, Instant.ofEpochMilli(created), Instant.ofEpochMilli(updated))
    }

    private companion object {
        val Id = stringPreferencesKey("identity_id")
        val Name = stringPreferencesKey("display_name")
        val Created = longPreferencesKey("created_at_ms")
        val Updated = longPreferencesKey("updated_at_ms")
    }
}
