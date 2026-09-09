package com.zisee.app.push

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.zisee.app.core.logging.AppEvent
import com.zisee.app.core.logging.AppLogger
import java.time.Instant
import kotlinx.coroutines.flow.first

/** Where a validated incoming call is shown. Phase 1: a notification. Phase 2: Telecom + CallStyle. */
fun interface IncomingCallSurface {
    fun showIncoming(invite: CallInvite)
}

/**
 * The single entry point every incoming-call push funnels through
 * (docs/architecture/CALL_DELIVERY.md §11). Phase 1 keeps it deliberately thin:
 * drop expired invites (§29), collapse duplicate `call_id`s to one ring (§12),
 * then show the notification. No network, no avatar fetch (§10).
 *
 * Phase 2: this becomes the Telecom + CallStyle registration site.
 */
class IncomingPushGate(
    private val surface: IncomingCallSurface,
    private val logger: AppLogger,
    private val store: DataStore<Preferences>,
    private val now: () -> Instant = Instant::now,
) {
    private val seen = LinkedHashSet<String>()

    suspend fun onInvite(invite: CallInvite) {
        if (!now().isBefore(invite.expiresAt)) {
            logger.info(AppEvent.PUSH_EXPIRED)
            return
        }
        if (!firstSeen(invite.callId)) return
        logger.info(AppEvent.PUSH_RECEIVED)
        surface.showIncoming(invite)
    }

    /** True the first time a call id is seen; survives process death via DataStore. */
    private suspend fun firstSeen(callId: String): Boolean {
        synchronized(seen) {
            if (!seen.add(callId)) return false
            while (seen.size > MEMORY_LIMIT) seen.iterator().let { it.next(); it.remove() }
        }
        val persisted = runCatching { store.data.first()[SEEN_KEY].orEmpty() }.getOrDefault(emptySet())
        if (callId in persisted) return false
        runCatching {
            store.edit { prefs ->
                val next = ArrayDeque(prefs[SEEN_KEY].orEmpty())
                next.addLast(callId)
                while (next.size > DISK_LIMIT) next.removeFirst()
                prefs[SEEN_KEY] = next.toSet()
            }
        }
        return true
    }

    private companion object {
        val SEEN_KEY = stringSetPreferencesKey("seen_call_ids")
        const val MEMORY_LIMIT = 32
        const val DISK_LIMIT = 24
    }
}
