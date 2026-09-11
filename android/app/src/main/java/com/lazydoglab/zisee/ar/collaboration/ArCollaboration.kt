package com.lazydoglab.zisee.ar.collaboration

import com.lazydoglab.zisee.ar.annotation.ArMessage
import com.lazydoglab.zisee.ar.annotation.ArStrokeMessage
import com.lazydoglab.zisee.ar.annotation.SpatialMarkerRequest
import com.lazydoglab.zisee.ar.session.MarkerKind
import com.lazydoglab.zisee.ar.spatial.SpatialRejection
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
    val remoteStrokeSupported: Boolean = false,
    val lastStrokeResult: ArStrokeMessage.Result? = null,
)

/** Serialized by the transport owner. A remote field is joined automatically without starting local AR. */
class ArCollaboration(private val localWinsFieldConflict: Boolean = false,
    private val sendStroke: (ArStrokeMessage) -> Boolean = { false },
    private val send: (ArMessage) -> Boolean) {
    private val mutable = MutableStateFlow(ArCollaborationState())
    val state = mutable.asStateFlow()
    private var local: ArFieldEndpoint? = null
    private var peerJoined = false
    private var joining: UUID? = null
    private var autoJoinSuppressed: UUID? = null
    private val pending = LinkedHashSet<UUID>()
    private val retired = HashSet<UUID>()
    private data class StrokeProgress(val nextSequence: Int, val points: Int)
    private val pendingStrokes = LinkedHashMap<UUID, StrokeProgress>()
    private val incomingStrokes = LinkedHashMap<UUID, StrokeProgress>()
    private var closed = false

    fun connected() {
        if (closed) return
        mutable.value = mutable.value.copy(connected = true)
        local?.let { check(send(ArMessage.Ready(it.sessionId, it.depthSupported))) }
    }

    suspend fun strokeConnected(connected: Boolean) {
        if (closed) return
        if (!connected) { pendingStrokes.clear(); cancelIncoming() }
        mutable.value = mutable.value.copy(remoteStrokeSupported = connected,
            lastStrokeResult = if (connected) mutable.value.lastStrokeResult else null)
    }

    private suspend fun cancelIncoming() {
        val target = local
        val ids = incomingStrokes.keys.toList()
        incomingStrokes.clear()
        if (target != null) ids.forEach { target.executeStroke(ArStrokeMessage.Cancel(target.sessionId, it)) }
    }

    /** Caller retains ownership on false. Attachment is the local user's explicit AR consent. */
    fun attach(endpoint: ArFieldEndpoint): Boolean {
        if (closed || local != null || endpoint.sessionId in retired) return false
        state.value.remote?.let { remote ->
            if (!localWinsFieldConflict) return false
            retire(remote.sessionId)
            mutable.value = mutable.value.copy(remote = null, joined = false, ownershipLost = false,
                lastStrokeResult = null)
        }
        if (state.value.connected && !send(ArMessage.Ready(endpoint.sessionId, endpoint.depthSupported))) return false
        local = endpoint
        peerJoined = false
        mutable.value = mutable.value.copy(localSession = endpoint.sessionId, fieldPeerJoined = false, ownershipLost = false)
        return true
    }

    suspend fun detach() {
        val old = local ?: return
        local = null; peerJoined = false; incomingStrokes.clear()
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
        joining = null; pending.clear(); pendingStrokes.clear(); incomingStrokes.clear()
        mutable.value = mutable.value.copy(joined = false, pendingMarkers = 0, lastResult = null,
            lastStrokeResult = null)
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

    fun beginStroke(id: UUID, request: SpatialMarkerRequest): Boolean {
        val remote = state.value.remote ?: return false
        if (!state.value.connected || !state.value.joined || !state.value.remoteStrokeSupported ||
            pendingStrokes.size >= 2 || id in pendingStrokes) return false
        if (!sendStroke(ArStrokeMessage.Begin(remote.sessionId, id, request))) return false
        pendingStrokes[id] = StrokeProgress(nextSequence = 0, points = 1)
        return true
    }

    fun appendStroke(id: UUID, requests: List<SpatialMarkerRequest>): Boolean {
        val remote = state.value.remote ?: return false
        val progress = pendingStrokes[id] ?: return false
        if (requests.isEmpty() ||
            requests.size > com.lazydoglab.zisee.ar.annotation.AnnotationBudget.MAX_BATCH_POINTS ||
            progress.points + requests.size > com.lazydoglab.zisee.ar.annotation.AnnotationBudget.MAX_STROKE_POINTS ||
            !sendStroke(ArStrokeMessage.Append(remote.sessionId, id, progress.nextSequence, requests))) return false
        pendingStrokes[id] = StrokeProgress(progress.nextSequence + 1, progress.points + requests.size)
        return true
    }

    fun endStroke(id: UUID, cancel: Boolean): Boolean {
        val remote = state.value.remote ?: return false
        val progress = pendingStrokes[id] ?: return false
        val message = if (cancel) ArStrokeMessage.Cancel(remote.sessionId, id)
            else ArStrokeMessage.End(remote.sessionId, id, progress.nextSequence)
        if (!sendStroke(message)) return false
        if (cancel) pendingStrokes.remove(id)
        return true
    }

    suspend fun receiveStroke(message: ArStrokeMessage) {
        if (closed || !state.value.connected || !state.value.remoteStrokeSupported) return
        if (message is ArStrokeMessage.Result) {
            if (state.value.joined && message.sessionId == state.value.remote?.sessionId &&
                pendingStrokes.remove(message.id) != null) mutable.value = mutable.value.copy(lastStrokeResult = message)
            return
        }
        val target = local ?: return
        if (!peerJoined || message.sessionId != target.sessionId) return
        when (message) {
            ArStrokeMessage.Hello -> return
            is ArStrokeMessage.Begin -> if (message.id in incomingStrokes || incomingStrokes.size >= 2) return
            is ArStrokeMessage.Append -> {
                val progress = incomingStrokes[message.id] ?: return
                if (progress.nextSequence != message.sequence) return
                if (progress.points + message.requests.size >
                    com.lazydoglab.zisee.ar.annotation.AnnotationBudget.MAX_STROKE_POINTS) {
                    incomingStrokes.remove(message.id)
                    target.executeStroke(ArStrokeMessage.Cancel(target.sessionId, message.id))
                    if (!closed && state.value.connected && local === target && peerJoined) {
                        check(sendStroke(ArStrokeMessage.Result(target.sessionId, message.id,
                            SpatialRejection.LIMIT_REACHED)))
                    }
                    return
                }
            }
            is ArStrokeMessage.End -> if (incomingStrokes[message.id]?.nextSequence != message.sequence) return
            is ArStrokeMessage.Cancel -> if (message.id !in incomingStrokes) return
            is ArStrokeMessage.Result -> return
        }
        val result = target.executeStroke(message)
        when (message) {
            ArStrokeMessage.Hello -> Unit
            is ArStrokeMessage.Begin -> if (result == null) incomingStrokes[message.id] = StrokeProgress(0, 1)
            is ArStrokeMessage.Append -> if (result == null) {
                val progress = requireNotNull(incomingStrokes[message.id])
                incomingStrokes[message.id] = StrokeProgress(message.sequence + 1,
                    progress.points + message.requests.size)
            }
                else incomingStrokes.remove(message.id)
            is ArStrokeMessage.End, is ArStrokeMessage.Cancel -> incomingStrokes.remove(message.id)
            is ArStrokeMessage.Result -> Unit
        }
        if (!closed && state.value.connected && local === target && peerJoined && result != null) check(sendStroke(result))
    }

    fun remove(id: UUID): Boolean = mutate { ArMessage.Remove(it, id) }
    fun clear(): Boolean = mutate { ArMessage.Clear(it) }
    /** The field already cleared its native anchors and announces that authoritative result. */
    fun announceFieldClear(): Boolean {
        val session = local?.sessionId ?: return false
        return state.value.connected && send(ArMessage.Clear(session))
    }
    suspend fun revokeGuide(): Boolean {
        val session = local?.sessionId ?: return false
        cancelIncoming()
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
                        pendingMarkers = 0, lastResult = null, ownershipLost = true, lastStrokeResult = null)
                    autoJoin(message.sessionId)
                    return
                }
                val previous = state.value.remote
                if (previous?.sessionId == message.sessionId) return
                previous?.let { retire(it.sessionId) }
                if (closed) return
                joining = null; pending.clear(); pendingStrokes.clear()
                mutable.value = mutable.value.copy(remote = message, joined = false, pendingMarkers = 0,
                    lastResult = null, ownershipLost = false, lastStrokeResult = null)
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
                local?.sessionId -> { peerJoined = false; cancelIncoming(); mutable.value = mutable.value.copy(fieldPeerJoined = false) }
                state.value.remote?.sessionId -> {
                    autoJoinSuppressed = message.sessionId
                    joining = null; pending.clear(); pendingStrokes.clear()
                    mutable.value = mutable.value.copy(joined = false, pendingMarkers = 0, lastResult = null,
                        lastStrokeResult = null)
                }
                else -> Unit
            }
            is ArMessage.Ended -> if (message.sessionId == state.value.remote?.sessionId) {
                retire(message.sessionId)
                if (autoJoinSuppressed == message.sessionId) autoJoinSuppressed = null
                joining = null; pending.clear(); pendingStrokes.clear()
                mutable.value = mutable.value.copy(remote = null, joined = false, pendingMarkers = 0,
                    lastResult = null, ownershipLost = false, lastStrokeResult = null)
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
        pending.clear(); pendingStrokes.clear(); incomingStrokes.clear(); retired.clear()
        mutable.value = ArCollaborationState()
        old?.close()
    }
}
