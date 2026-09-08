package com.zisee.app.signaling

/** A bounded control-plane outage must not immediately tear down established P2P media. */
class SignalingRetryPolicy {
    private var startedMs: Long? = null
    private var failures = 0
    fun recovered() { startedMs = null; failures = 0 }
    fun nextDelayMs(nowMs: Long, mediaEstablished: Boolean): Long? {
        val start = startedMs ?: nowMs.also { startedMs = it }
        failures++
        val budget = if (mediaEstablished) 30_000L else 10_000L
        val elapsed = (nowMs - start).coerceAtLeast(0)
        if (elapsed >= budget || (!mediaEstablished && failures >= 3)) return null
        return minOf(1_000L shl (failures - 1).coerceAtMost(2), budget - elapsed)
    }
}
