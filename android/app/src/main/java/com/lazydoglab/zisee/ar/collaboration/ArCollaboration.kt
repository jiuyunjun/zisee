package com.lazydoglab.zisee.ar.collaboration

import com.lazydoglab.zisee.ar.annotation.ArMessage
import com.lazydoglab.zisee.ar.annotation.SpatialMarkerRequest
import com.lazydoglab.zisee.ar.session.MarkerKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class ArCollaborationState(
    val connected: Boolean = false,
    val localSession: UUID? = null,
    val remote: ArMessage.Ready? = null,
    val joined: Boolean = false,
    val pendingMarkers: Int = 0,
    val lastResult: ArMessage.Result? = null,
    val fieldPeerJoined: Boolean = false,
    val fieldClearRevision: Long = 0,
    /** The call coordinator kept the peer's simultaneous field before two scenes became active. */
    val ownershipLost: Boolean = false,
)

/** Serialized by the transport owner. A remote field is joined automatically without starting local AR. */
class ArCollaboration(private val localWinsFieldConflict: Boolean = false,
    private val send: (ArMessage) -> Boolean) {
    private val mutable = MutableStateFlow(ArCollaborationState())
    val state = mutable.asStateFlow()
    private var local: ArFieldEndpoint? = null
    private var peerJoined = false
    private var joining: UUID? = null
    private var autoJoinSuppressed: UUID? = null
    private val pending = LinkedHashSet<UUID>()
    private val retired = HashSet<UUID>()
    private var closed = false

    fun connected() {
        if (closed) return
        mutable.value = mutable.value.copy(connected = true)
        local?.let { check(send(ArMessage.Ready(it.sessionId, it.depthSupported))) }
    }

    /** Caller retains ownership on false. Attachment is the local user's explicit AR consent. */
    fun attach(endpoint: ArFieldEndpoint): Boolean {
        if (closed || local != null || endpoint.sessionId in retired) return false
        state.value.remote?.let { remote ->
            if (!localWinsFieldConflict) return false
            retire(remote.sessionId)
            mutable.value = mutable.value.copy(remote = null, joined = false, ownershipLost = false)
        }
        if (state.value.connected && !send(ArMessage.Ready(endpoint.sessionId, endpoint.depthSupported))) return false
        local = endpoint
        peerJoined = false
        mutable.value = mutable.value.copy(localSession = endpoint.sessionId, fieldPeerJoined = false, ownershipLost = false)
        return true
    }

    suspend fun detach() {
        val old = local ?: return
        local = null; peerJoined = false
        mutable.value = mutable.value.copy(localSession = null, fieldPeerJoined = false, ownershipLost = false)
        try {
            retire(old.sessionId)
            if (state.value.connected) send(ArMessage.Ended(old.sessionId))
        } finally { old.close() }
    }

    /** Manual retry after a failed automatic join; matching Joined must arrive before mutations. */
    fun join(sessionId: UUID): Boolean {
        if (!state.value.connected || state.value.remote?.sessionId != sessionId) return false
        if (!send(ArMessage.Join(sessionId))) return false
        joining = sessionId
        autoJoinSuppressed = null
        return true
    }

    fun leave() {
        state.value.remote?.let {
            autoJoinSuppressed = it.sessionId
            if (state.value.connected) send(ArMessage.Leave(it.sessionId))
        }
        joining = null; pending.clear()
        mutable.value = mutable.value.copy(joined = false, pendingMarkers = 0, lastResult = null)
    }

    /** request.frame MUST identify the actual displayed source frame, never a latest-time hint. */
    fun create(id: UUID, kind: MarkerKind, request: SpatialMarkerRequest): Boolean {
        val remote = state.value.remote ?: return false
        if (!state.value.connected || !state.value.joined || pending.size >= 16 || id in pending) return false
        if (!send(ArMessage.Create(remote.sessionId, id, kind, request))) return false
        pending.add(id)
        mutable.value = mutable.value.copy(pendingMarkers = pending.size)
        return true
    }

    fun remove(id: UUID): Boolean = mutate { ArMessage.Remove(it, id) }
    fun clear(): Boolean = mutate { ArMessage.Clear(it) }
    /** The field already cleared its native anchors and announces that authoritative result. */
    fun announceFieldClear(): Boolean {
        val session = local?.sessionId ?: return false
        return state.value.connected && send(ArMessage.Clear(session))
    }
    fun revokeGuide(): Boolean {
        val session = local?.sessionId ?: return false
        peerJoined = false
        mutable.value = mutable.value.copy(fieldPeerJoined = false)
        return !state.value.connected || send(ArMessage.Leave(session))
    }
    private fun mutate(message: (UUID) -> ArMessage): Boolean {
        val remote = state.value.remote ?: return false
        return state.value.connected && state.value.joined && send(message(remote.sessionId))
    }

    suspend fun receive(message: ArMessage) {
        if (closed || !state.value.connected) return
        when (message) {
            is ArMessage.Ready -> {
                if (message.sessionId in retired || message.sessionId == local?.sessionId) return
                local?.let { field ->
                    if (localWinsFieldConflict) return
                    mutable.value = mutable.value.copy(remote = message, joined = false,
                        pendingMarkers = 0, lastResult = null, ownershipLost = true)
                    autoJoin(message.sessionId)
                    return
                }
                val previous = state.value.remote
                if (previous?.sessionId == message.sessionId) return
                previous?.let { retire(it.sessionId) }
                if (closed) return
                joining = null; pending.clear()
                mutable.value = mutable.value.copy(remote = message, joined = false, pendingMarkers = 0,
                    lastResult = null, ownershipLost = false)
                autoJoin(message.sessionId)
            }
            is ArMessage.Join -> if (message.sessionId == local?.sessionId) {
                peerJoined = send(ArMessage.Joined(message.sessionId))
                mutable.value = mutable.value.copy(fieldPeerJoined = peerJoined)
            }
            is ArMessage.Joined -> if (message.sessionId == joining && message.sessionId == state.value.remote?.sessionId) {
                joining = null
                mutable.value = mutable.value.copy(joined = true)
            }
            is ArMessage.Leave -> when (message.sessionId) {
                local?.sessionId -> { peerJoined = false; mutable.value = mutable.value.copy(fieldPeerJoined = false) }
                state.value.remote?.sessionId -> {
                    autoJoinSuppressed = message.sessionId
                    joining = null; pending.clear()
                    mutable.value = mutable.value.copy(joined = false, pendingMarkers = 0, lastResult = null)
                }
                else -> Unit
            }
            is ArMessage.Ended -> if (message.sessionId == state.value.remote?.sessionId) {
                retire(message.sessionId)
                if (autoJoinSuppressed == message.sessionId) autoJoinSuppressed = null
                joining = null; pending.clear()
                mutable.value = mutable.value.copy(remote = null, joined = false, pendingMarkers = 0,
                    lastResult = null, ownershipLost = false)
            }
            is ArMessage.Result -> if (state.value.joined && message.sessionId == state.value.remote?.sessionId && pending.remove(message.id)) {
                mutable.value = mutable.value.copy(pendingMarkers = pending.size, lastResult = message)
            }
            is ArMessage.Create, is ArMessage.Remove, is ArMessage.Clear -> {
                if (message is ArMessage.Clear && message.sessionId == state.value.remote?.sessionId) {
                    pending.clear()
                    mutable.value = mutable.value.copy(pendingMarkers = 0, lastResult = null,
                        fieldClearRevision = mutable.value.fieldClearRevision + 1)
                    return
                }
                val target = local ?: return
                if (!peerJoined || message.sessionId != target.sessionId) return
                val result = target.execute(message)
                // Detach/hangup may have run while dispatching to the GL thread.
                if (!closed && state.value.connected && local === target && peerJoined && result != null) {
                    // A marker whose result cannot be acknowledged must not leave guidance in
                    // an apparently healthy but unknowable state. The transport closes AR only.
                    check(send(result))
                }
            }
        }
    }

    private fun autoJoin(sessionId: UUID) {
        if (autoJoinSuppressed == sessionId) return
        if (send(ArMessage.Join(sessionId))) joining = sessionId
    }

    private fun retire(id: UUID) {
        // Bound replay tombstones over a call; reaching the limit requires a new call.
        check(retired.size < 64) { "AR session budget exhausted" }
        retired.add(id)
    }

    suspend fun close() {
        if (closed) return
        closed = true
        val old = local
        local = null; peerJoined = false; joining = null; autoJoinSuppressed = null
        pending.clear(); retired.clear()
        mutable.value = ArCollaborationState()
        old?.close()
    }
}
