package com.zisee.app.rtc

/** Monotonic-clock durations, kept together for device handover tuning. */
data class WebRtcRecoveryConfig(
    val networkDebounceMs: Long = 200,
    val routeDebounceMs: Long = 250,
    val naturalRecoveryMs: Long = 750,
    val disconnectedGraceMs: Long = 1_500,
    val restartCooldownMs: Long = 5_000,
    val negotiationTimeoutMs: Long = 12_000,
    val outageTimeoutMs: Long = 45_000,
    val healthyResetMs: Long = 5_000,
    val maxRestarts: Int = 3,
    val handoverStatsIntervalMs: Long = 200,
    val handoverStatsDurationMs: Long = 5_000,
)
