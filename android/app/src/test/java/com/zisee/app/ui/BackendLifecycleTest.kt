package com.zisee.app.ui

import com.zisee.app.auth.IdentityRepository
import com.zisee.app.auth.LocalIdentity
import com.zisee.app.auth.remote.BackendRunner
import com.zisee.app.auth.remote.ConnectionState
import com.zisee.app.core.logging.AppEvent
import com.zisee.app.core.logging.AppLogger
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BackendLifecycleTest {
    private val user = LocalIdentity("existing-id", "九云", Instant.EPOCH, Instant.EPOCH)
    private val repository = object : IdentityRepository {
        override val identity = flowOf(user)
        override suspend fun create(displayName: String) { error("unexpected create") }
        override suspend fun rename(displayName: String) { error("unexpected rename") }
    }
    private val logger = object : AppLogger { override fun error(event: AppEvent, reason: String?) = Unit }

    @Test fun foregroundOwnsConnectionAndIgnoresLateCallbacks() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val callbacks = mutableListOf<(ConnectionState) -> Unit>()
            var cancelled = 0
            val runner = BackendRunner { identity, callback ->
                assertEquals(user.identityId, identity.identityId)
                callbacks += callback
                callback(ConnectionState.CONNECTED)
                try { awaitCancellation() } finally { cancelled++ }
            }
            val model = ZiseeViewModel(repository, logger, runner)
            runCurrent()
            model.setForeground(true)
            model.connectBackend()
            model.connectBackend()
            runCurrent()
            assertEquals(1, callbacks.size)
            model.setForeground(false)
            runCurrent()
            assertEquals(1, cancelled)
            callbacks[0](ConnectionState.CONNECTED)
            assertEquals(ConnectionState.DISCONNECTED, model.connection.value)
            model.setForeground(true)
            runCurrent()
            assertEquals(2, callbacks.size)
            model.disconnectBackend()
            runCurrent()
            model.setForeground(false)
            model.setForeground(true)
            runCurrent()
            assertEquals(2, callbacks.size)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun terminalConflictRequiresExplicitRetry() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var attempts = 0
            val model = ZiseeViewModel(repository, logger, BackendRunner { _, callback ->
                attempts++
                callback(ConnectionState.CONFLICT)
            })
            runCurrent()
            model.setForeground(true)
            model.connectBackend()
            runCurrent()
            assertEquals(ConnectionState.CONFLICT, model.connection.value)
            model.setForeground(false)
            model.setForeground(true)
            runCurrent()
            assertEquals(1, attempts)
            model.connectBackend()
            runCurrent()
            assertEquals(2, attempts)
        } finally { Dispatchers.resetMain() }
    }
}
