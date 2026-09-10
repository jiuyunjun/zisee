package com.lazydoglab.zisee.call

import java.util.concurrent.atomic.AtomicReference

/** Process-local bridge from foreground-service notification actions to the current call owner.
 * Registration uses an opaque owner token so a stale ViewModel cannot clear a replacement owner.
 */
class ActiveCallActions {
    private data class Registration(
        val owner: Any,
        val hangUp: () -> Unit,
        val toggleMute: () -> Unit,
        val stopSharing: () -> Unit,
    )

    private val current = AtomicReference<Registration?>()

    fun register(owner: Any, hangUp: () -> Unit, toggleMute: () -> Unit, stopSharing: () -> Unit) {
        current.set(Registration(owner, hangUp, toggleMute, stopSharing))
    }

    fun clear(owner: Any) {
        while (true) {
            val existing = current.get() ?: return
            if (existing.owner !== owner) return
            if (current.compareAndSet(existing, null)) return
        }
    }

    fun hangUp(): Boolean = current.get()?.let { it.hangUp(); true } ?: false
    fun toggleMute(): Boolean = current.get()?.let { it.toggleMute(); true } ?: false
    fun stopSharing(): Boolean = current.get()?.let { it.stopSharing(); true } ?: false
}
