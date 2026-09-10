package com.lazydoglab.zisee.screen

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class ScreenSharePhase { IDLE, AWAITING_CONSENT, STARTING, ACTIVE, STOPPING, STOPPED, FAILED, CLOSED }
enum class ScreenShareReason { USER, CONSENT_DENIED, SYSTEM_STOPPED, CALL_ENDED, LOCKED, START_FAILED, CAPTURE_FAILED, FIRST_FRAME_TIMEOUT }
enum class ScreenShareEvent { REQUESTED, STARTING, FIRST_FRAME, RESIZED, VISIBILITY_CHANGED, STOPPED, FAILED, CLEANUP_FAILED }

data class ScreenSize(val width: Int, val height: Int) {
    init { require(width in 1..MAX_DIMENSION && height in 1..MAX_DIMENSION) }
    companion object { const val MAX_DIMENSION = 16_384 }
}

/** Identifies one consent attempt, never contains Android consent data or projection tokens. */
data class ScreenShareRequest(val callId: String, val id: UUID)

data class ScreenShareState(
    val phase: ScreenSharePhase = ScreenSharePhase.IDLE,
    val request: ScreenShareRequest? = null,
    val size: ScreenSize? = null,
    val contentVisible: Boolean? = null,
    val reason: ScreenShareReason? = null,
)

interface ScreenProjectionEvents {
    fun stopped()
    fun resized(size: ScreenSize)
    fun visibilityChanged(visible: Boolean)
    fun failed()
}

/** One single-use projection. Methods and callbacks run on the controller's non-main owner thread.
 * close must attempt every release, even when start only partially succeeded.
 */
interface ScreenProjection : AutoCloseable {
    fun start(size: ScreenSize, events: ScreenProjectionEvents)
    fun resize(size: ScreenSize)
}

/** One controller per capture attempt, independent of Activity/PeerConnection lifetime.
 * The call owner must obtain collaboration ownership before request(), validate the call again on
 * consent return, and establish the mediaProjection foreground service before start().
 * No Android consent data is stored here. Diagnostics contain enums only, never exception messages.
 */
class ScreenShareController(
    private val callId: String,
    private val onEvent: (ScreenShareEvent) -> Unit = {},
) : AutoCloseable {
    private val owner = Thread.currentThread()
    private val mutableState = MutableStateFlow(ScreenShareState())
    val state = mutableState.asStateFlow()
    private var projection: ScreenProjection? = null

    init { require(callId.isNotBlank()) }
    private fun checkThread() = check(Thread.currentThread() === owner) { "Screen share accessed outside owner thread" }

    fun request(): ScreenShareRequest {
        checkThread()
        check(state.value.phase == ScreenSharePhase.IDLE)
        val request = ScreenShareRequest(callId, UUID.randomUUID())
        mutableState.value = ScreenShareState(ScreenSharePhase.AWAITING_CONSENT, request)
        onEvent(ScreenShareEvent.REQUESTED)
        return request
    }

    fun deny(request: ScreenShareRequest): Boolean {
        checkThread()
        if (!awaiting(request)) return false
        stop(ScreenShareReason.CONSENT_DENIED)
        return true
    }

    /** Factory is called only for the current pending request, at most once. It must release its
     * own partial allocations if it throws before returning a backend. False never means retry the
     * same token: a new controller and fresh system consent are required for a new capture attempt.
     */
    fun start(request: ScreenShareRequest, size: ScreenSize, factory: () -> ScreenProjection): Boolean {
        checkThread()
        if (!awaiting(request)) return false
        mutableState.value = state.value.copy(phase = ScreenSharePhase.STARTING, size = size)
        onEvent(ScreenShareEvent.STARTING)
        try {
            projection = factory()
            requireNotNull(projection).start(size, object : ScreenProjectionEvents {
                override fun stopped() = stop(ScreenShareReason.SYSTEM_STOPPED)
                override fun resized(size: ScreenSize) = resize(size)
                override fun visibilityChanged(visible: Boolean) {
                    checkThread()
                    if (!capturing()) return
                    mutableState.value = state.value.copy(contentVisible = visible)
                    onEvent(ScreenShareEvent.VISIBILITY_CHANGED)
                }
                override fun failed() = stop(ScreenShareReason.CAPTURE_FAILED)
            })
            return capturing()
        } catch (_: Exception) {
            stop(ScreenShareReason.START_FAILED)
            return false
        }
    }

    /** Invoked by the screen-only frame sink, not by consent success or VirtualDisplay creation. */
    fun onFrame(request: ScreenShareRequest) {
        checkThread()
        if (state.value.request != request || state.value.phase != ScreenSharePhase.STARTING) return
        mutableState.value = state.value.copy(phase = ScreenSharePhase.ACTIVE)
        onEvent(ScreenShareEvent.FIRST_FRAME)
    }

    /** Call owner schedules a bounded startup deadline; stale timers cannot stop another request. */
    fun firstFrameTimeout(request: ScreenShareRequest) {
        checkThread()
        if (state.value.request == request && state.value.phase == ScreenSharePhase.STARTING)
            stop(ScreenShareReason.FIRST_FRAME_TIMEOUT)
    }

    /** Also used by the display/orientation owner on versions without captured-content callbacks. */
    fun resize(size: ScreenSize) {
        checkThread()
        if (!capturing() || state.value.size == size) return
        try {
            requireNotNull(projection).resize(size)
            if (!capturing()) return
            mutableState.value = state.value.copy(size = size)
            onEvent(ScreenShareEvent.RESIZED)
        } catch (_: Exception) { stop(ScreenShareReason.CAPTURE_FAILED) }
    }

    fun stop(reason: ScreenShareReason = ScreenShareReason.USER) {
        checkThread()
        if (state.value.phase in setOf(ScreenSharePhase.STOPPING, ScreenSharePhase.STOPPED,
                ScreenSharePhase.FAILED, ScreenSharePhase.CLOSED)) return
        mutableState.value = state.value.copy(phase = ScreenSharePhase.STOPPING, reason = reason)
        val resource = projection
        projection = null // Reentrant system-stop callbacks cannot release twice.
        var cleanupFailed = false
        try { resource?.close() } catch (_: Exception) { cleanupFailed = true }
        val failed = cleanupFailed || reason in setOf(ScreenShareReason.START_FAILED,
            ScreenShareReason.CAPTURE_FAILED, ScreenShareReason.FIRST_FRAME_TIMEOUT)
        mutableState.value = state.value.copy(
            phase = if (failed) ScreenSharePhase.FAILED else ScreenSharePhase.STOPPED,
            contentVisible = false,
        )
        if (cleanupFailed) onEvent(ScreenShareEvent.CLEANUP_FAILED)
        onEvent(if (failed) ScreenShareEvent.FAILED else ScreenShareEvent.STOPPED)
    }

    override fun close() {
        checkThread()
        if (state.value.phase == ScreenSharePhase.CLOSED) return
        stop(ScreenShareReason.CALL_ENDED)
        mutableState.value = state.value.copy(phase = ScreenSharePhase.CLOSED)
    }

    private fun awaiting(request: ScreenShareRequest) =
        state.value.phase == ScreenSharePhase.AWAITING_CONSENT && state.value.request == request
    private fun capturing() = state.value.phase in setOf(ScreenSharePhase.STARTING, ScreenSharePhase.ACTIVE)
}
