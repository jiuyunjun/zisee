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
    // Two failed checks cannot land inside a 750 ms timeout while the selected pair is only pinged
    // every 500 ms, so the cadence, not the timeout, was setting how fast a dead path is noticed.
    // A hundred-byte check four times a second costs a few kbps and buys back most of that.
    val stablePingIntervalMs: Int = 250,
    // A measured Wi-Fi to cellular switch took 1502 ms to select the already checked cellular pair,
    // which is this timeout: ICE will not leave a selected connection until it is declared
    // unwritable. Halving it halves that gap; the backup pair is pinged throughout, and a Wi-Fi
    // hiccup that outlives it is switched back the same way.
    val unwritableTimeoutMs: Int = 750,
    val unwritableMinChecks: Int = 2,
)
