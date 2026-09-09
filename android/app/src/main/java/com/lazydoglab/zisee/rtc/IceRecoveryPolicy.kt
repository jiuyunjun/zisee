package com.lazydoglab.zisee.rtc

/** Per-outage budget; short disconnections may recover without renegotiation. */
class IceRecoveryPolicy(startedMs: Long, private val config: WebRtcRecoveryConfig = WebRtcRecoveryConfig()) {
    private var generationMs = startedMs
    private var outageMs: Long? = null
    private var disconnectedMs: Long? = null
    private var healthyMs: Long? = null
    private var seenNetwork = 0L
    private var attempts = 0
    private var networkChangedMs: Long? = null
    private var routeSample: MediaStats? = null
    val checkingRoute: Boolean get() = networkChangedMs != null
    var lastNaturalRecoveryMs: Long? = null; private set

    fun initialNegotiationStarted(nowMs: Long, networkVersion: Long) {
        generationMs = nowMs; seenNetwork = networkVersion
        outageMs = null; disconnectedMs = null; healthyMs = null
        attempts = 0; networkChangedMs = null
        routeSample = null
        lastNaturalRecoveryMs = null
    }

    fun generationStarted(nowMs: Long, networkVersion: Long) {
        generationMs = nowMs; seenNetwork = networkVersion; networkChangedMs = null
        routeSample = null
        if (outageMs == null) outageMs = nowMs
        attempts++; healthyMs = null
    }

    enum class Action { WAIT, RESTART, FAIL }
    /** Why the last evaluation asked for a restart, so a self-inflicted outage can be traced. */
    enum class Reason { NONE, ROUTE, DISCONNECTED, FAILED, NEGOTIATION }
    var lastReason = Reason.NONE; private set
    /** Called when the route changes, before any signaling IO can delay evaluation. */
    fun networkChanged(networkVersion: Long, nowMs: Long) {
        if (networkVersion != seenNetwork) {
            seenNetwork = networkVersion; networkChangedMs = nowMs
            if (outageMs == null) outageMs = nowMs
            healthyMs = null
            routeSample = null
            lastNaturalRecoveryMs = null
        }
    }

    /**
     * Credits a route as recovered on the evidence of media arriving on it, and returns whether it
     * just did. Kept separate from [evaluate] because the loop that evaluates also performs
     * signaling IO: a measured handover had media back after 1.5 s and was restarted anyway,
     * because the next evaluation was almost five seconds later. Samples arrive every 200 ms during
     * a handover, so crediting recovery where they arrive cannot be starved that way.
     *
     * Two samples are required after detection. A stale CONNECTED state or bytes accumulated on the
     * old route must not suppress recovery of a dead path.
     */
    fun observeMedia(state: IceState, stats: MediaStats, nowMs: Long): Boolean {
        val changedAt = networkChangedMs ?: return false
        if (!stats.sampleAvailable || stats.sampledAtMs < changedAt) return false
        val baseline = routeSample
        // Media arriving on the new route is stronger evidence than the negotiation bookkeeping:
        // the peer can only be reaching us here.
        if (baseline != null && stats.sampledAtMs > baseline.sampledAtMs &&
            stats.inboundBytes > baseline.inboundBytes && state == IceState.CONNECTED) {
            networkChangedMs = null
            routeSample = null
            lastNaturalRecoveryMs = nowMs - changedAt
            return true
        }
        if (baseline == null || stats.inboundBytes < baseline.inboundBytes) routeSample = stats
        return false
    }

    fun evaluate(state: IceState, networkVersion: Long, negotiationComplete: Boolean, nowMs: Long,
                 stats: MediaStats = MediaStats()): Action {
        networkChanged(networkVersion, nowMs)
        if (state == IceState.CLOSED) return Action.FAIL
        observeMedia(state, stats, nowMs)
        if (state == IceState.CONNECTED && negotiationComplete && networkChangedMs == null) {
            disconnectedMs = null
            if (healthyMs == null) healthyMs = nowMs
            if (nowMs - requireNotNull(healthyMs) >= config.healthyResetMs) { outageMs = null; attempts = 0 }
            return Action.WAIT
        }
        healthyMs = null
        if (disconnectedMs == null) disconnectedMs = nowMs
        if (outageMs == null && (state == IceState.DISCONNECTED || state == IceState.FAILED)) outageMs = nowMs
        if (outageMs?.let { nowMs - it >= config.outageTimeoutMs } == true) return Action.FAIL
        val routeWait = if (state == IceState.CONNECTED && negotiationComplete) config.naturalRecoveryMs else config.routeDebounceMs
        val routeReady = networkChangedMs?.let { nowMs - it >= routeWait } == true
        val disconnectedTooLong = state == IceState.DISCONNECTED &&
            nowMs - requireNotNull(disconnectedMs) >= config.disconnectedGraceMs
        val negotiationStalled = networkChangedMs == null && nowMs - generationMs >= config.negotiationTimeoutMs
        val requested = routeReady || disconnectedTooLong || state == IceState.FAILED || negotiationStalled
        if (requested) lastReason = when {
            routeReady -> Reason.ROUTE
            state == IceState.FAILED -> Reason.FAILED
            disconnectedTooLong -> Reason.DISCONNECTED
            else -> Reason.NEGOTIATION
        }
        // A new route invalidates the previous attempt's cooldown, but not the outage budget.
        if (!requested || (!routeReady && nowMs - generationMs < config.restartCooldownMs)) return Action.WAIT
        // Give the final attempt its full negotiation window.
        if (attempts >= config.maxRestarts) return if (nowMs - generationMs >= config.negotiationTimeoutMs) Action.FAIL else Action.WAIT
        return Action.RESTART
    }
}
