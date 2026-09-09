package com.lazydoglab.zisee.auth.remote

import com.lazydoglab.zisee.auth.LocalIdentity
import com.lazydoglab.zisee.core.logging.AppLogger
import com.lazydoglab.zisee.core.logging.AppEvent
import com.lazydoglab.zisee.signaling.AuthenticatedSession
import java.io.IOException
import kotlin.random.Random
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class ConnectionState {
    NOT_CONFIGURED, DISCONNECTED, AUTHENTICATING, CONNECTED, RECONNECTING,
    CONFLICT, KEY_UNAVAILABLE, FAILED,
}

/** Called by one foreground ViewModel job. Session tokens never leave this coroutine or hit disk. */
fun interface BackendRunner {
    suspend fun run(identity: LocalIdentity, onState: (ConnectionState) -> Unit)
}

class BackendConnection(
    private val api: BackendApi,
    private val transport: AuthenticatedSession,
    private val signer: (String) -> DeviceSigner,
    private val logger: AppLogger,
) : BackendRunner {
    override suspend fun run(identity: LocalIdentity, onState: (ConnectionState) -> Unit) {
        var failures = 0
        while (currentCoroutineContext().isActive) {
            var session: AccessSession? = null
            var reason: AuthFailure.Reason? = null
            try {
                onState(ConnectionState.AUTHENTICATING)
                session = api.login(identity, signer(identity.identityId))
                transport.run(session) { onState(ConnectionState.CONNECTED) }
                failures = 0 // Normal planned token renewal, not a transport failure.
            } catch (error: TimeoutCancellationException) {
                reason = AuthFailure.Reason.NETWORK
            } catch (error: AuthFailure) {
                reason = error.reason
            } catch (error: IOException) {
                reason = AuthFailure.Reason.NETWORK
            } finally {
                session?.let { token ->
                    withContext(NonCancellable) {
                        withTimeoutOrNull(3_000) {
                            try { api.logout(token) }
                            catch (error: IOException) {
                                // Local socket/token are discarded; server expiry bounds cleanup failure.
                                logger.error(AppEvent.SESSION_LOGOUT_FAILED)
                            }
                        }
                    }
                }
            }
            if (reason == null) continue
            when (reason) {
                AuthFailure.Reason.CONFLICT -> { onState(ConnectionState.CONFLICT); return }
                AuthFailure.Reason.KEY_UNAVAILABLE -> { onState(ConnectionState.KEY_UNAVAILABLE); return }
                AuthFailure.Reason.PROTOCOL, AuthFailure.Reason.UNAUTHORIZED -> { onState(ConnectionState.FAILED); return }
                else -> Unit
            }
            failures++
            if (failures >= 5) { onState(ConnectionState.FAILED); return }
            onState(ConnectionState.RECONNECTING)
            val wait = if (reason == AuthFailure.Reason.RATE_LIMITED) 120_000L else 1_000L shl (failures - 1)
            delay(wait + Random.nextLong(0, 1_000))
        }
    }
}
