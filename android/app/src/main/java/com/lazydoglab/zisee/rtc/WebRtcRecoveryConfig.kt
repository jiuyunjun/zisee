package com.lazydoglab.zisee.rtc

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
    // Detect an unusable selected path early enough for an already checked cellular/TURN pair.
    val receivingTimeoutMs: Int = 1_000,
    val backupPingIntervalMs: Int = 500,
    val stablePingIntervalMs: Int = 500,
    val unwritableTimeoutMs: Int = 1_500,
    val unwritableMinChecks: Int = 3,
)
