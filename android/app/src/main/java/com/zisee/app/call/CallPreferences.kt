package com.zisee.app.call

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/** Small, non-critical call UI preferences. A read failure must never block a call. */
class CallPreferences(private val store: DataStore<Preferences>) {
    /** A hint that cannot be read is treated as already shown, so it never nags on every call. */
    val showMeHintSeen: Flow<Boolean> = store.data.map { it[Seen] == true }.catch { emit(true) }

    suspend fun markShowMeHintSeen() {
        try { store.edit { it[Seen] = true } } catch (error: Exception) { /* Reappearing once is harmless. */ }
    }

    private companion object { val Seen = booleanPreferencesKey("show_me_hint_seen") }
}
