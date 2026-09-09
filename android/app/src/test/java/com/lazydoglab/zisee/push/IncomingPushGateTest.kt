package com.lazydoglab.zisee.push

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.lazydoglab.zisee.core.logging.AppEvent
import com.lazydoglab.zisee.core.logging.AppLogger
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class IncomingPushGateTest {
    @get:Rule val folder = TemporaryFolder()

    private val noopLogger = object : AppLogger {
        override fun error(event: AppEvent, reason: String?) {}
    }

    private fun store(scope: CoroutineScope): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope) { folder.root.resolve("push.preferences_pb") }

    private fun invite(id: String, expiresAt: Instant) =
        CallInvite(id, "zid_caller", "九云", "video", expiresAt)

    @Test fun showsAFreshInviteOnce() = runBlocking {
        val job = SupervisorJob()
        try {
            val shown = mutableListOf<CallInvite>()
            val gate = IncomingPushGate({ shown.add(it) }, noopLogger, store(CoroutineScope(Dispatchers.IO + job)),
                now = { Instant.parse("2026-09-09T15:30:00Z") })
            val expires = Instant.parse("2026-09-09T15:30:30Z")
            val outcomes = (1..3).map { gate.onInvite(invite("call-1", expires)) }
            assertEquals(listOf(PushOutcome.RINGING, PushOutcome.DUPLICATE, PushOutcome.DUPLICATE), outcomes)
            assertEquals(1, shown.size)
            assertEquals("call-1", shown.single().callId)
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test fun dropsAnExpiredInvite() = runBlocking {
        val job = SupervisorJob()
        try {
            val shown = mutableListOf<CallInvite>()
            val gate = IncomingPushGate({ shown.add(it) }, noopLogger, store(CoroutineScope(Dispatchers.IO + job)),
                now = { Instant.parse("2026-09-09T15:31:00Z") })
            assertEquals(PushOutcome.EXPIRED, gate.onInvite(invite("call-late", Instant.parse("2026-09-09T15:30:30Z"))))
            assertEquals(0, shown.size)
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test fun dedupesAcrossProcessRestart() = runBlocking {
        val now = { Instant.parse("2026-09-09T15:30:00Z") }
        val expires = Instant.parse("2026-09-09T15:30:30Z")
        val first = SupervisorJob()
        val shown = mutableListOf<CallInvite>()
        try {
            assertEquals(PushOutcome.RINGING,
                IncomingPushGate({ shown.add(it) }, noopLogger, store(CoroutineScope(Dispatchers.IO + first)), now)
                    .onInvite(invite("call-1", expires)))
        } finally {
            first.cancelAndJoin()
        }
        // A second process, its in-memory set empty: only the persisted record can catch this.
        val second = SupervisorJob()
        try {
            assertEquals(PushOutcome.DUPLICATE,
                IncomingPushGate({ shown.add(it) }, noopLogger, store(CoroutineScope(Dispatchers.IO + second)), now)
                    .onInvite(invite("call-1", expires)))
        } finally {
            second.cancelAndJoin()
        }
        assertEquals(1, shown.size)
    }
}
