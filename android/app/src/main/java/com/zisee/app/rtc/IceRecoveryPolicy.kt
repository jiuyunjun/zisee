package com.zisee.app.rtc

/** Per-outage budget; short disconnections may recover without renegotiation. */
class IceRecoveryPolicy(startedMs: Long) {
    private var generationMs = startedMs
    private var outageMs: Long? = null
    private var disconnectedMs: Long? = null
    private var healthyMs: Long? = null
    private var seenNetwork = 0L
    private var attempts = 0
    private var networkChangedMs: Long? = null

    fun initialNegotiationStarted(nowMs: Long, networkVersion: Long) {
        generationMs = nowMs; seenNetwork = networkVersion
        outageMs = null; disconnectedMs = null; healthyMs = null
        attempts = 0; networkChangedMs = null
    }

    fun generationStarted(nowMs: Long, networkVersion: Long) {
        generationMs = nowMs; seenNetwork = networkVersion; networkChangedMs = null
        if (outageMs == null) outageMs = nowMs
        attempts++; healthyMs = null
    }

    enum class Action { WAIT, RESTART, FAIL }
    fun evaluate(state: IceState, networkVersion: Long, negotiationComplete: Boolean, nowMs: Long): Action {
        if (networkVersion != seenNetwork) {
            seenNetwork = networkVersion; networkChangedMs = nowMs
            if (outageMs == null) outageMs = nowMs
            healthyMs = null
        }
        if (state == IceState.CLOSED) return Action.FAIL
        if (state == IceState.CONNECTED && negotiationComplete && networkChangedMs == null) {
            disconnectedMs = null
            if (healthyMs == null) healthyMs = nowMs
            if (nowMs - requireNotNull(healthyMs) >= 5_000) { outageMs = null; attempts = 0 }
            return Action.WAIT
        }
        healthyMs = null
        if (disconnectedMs == null) disconnectedMs = nowMs
        if (outageMs == null && (state == IceState.DISCONNECTED || state == IceState.FAILED)) outageMs = nowMs
        if (outageMs?.let { nowMs - it >= 45_000 } == true) return Action.FAIL
        val requested = networkChangedMs?.let { nowMs - it >= 500 } == true ||
            (state == IceState.DISCONNECTED && nowMs - requireNotNull(disconnectedMs) >= 1_500) ||
            state == IceState.FAILED || nowMs - generationMs >= 12_000
        if (!requested || nowMs - generationMs < 5_000) return Action.WAIT
        // Give the final attempt its full negotiation window.
        if (attempts >= 3) return if (nowMs - generationMs >= 12_000) Action.FAIL else Action.WAIT
        return Action.RESTART
    }
}
