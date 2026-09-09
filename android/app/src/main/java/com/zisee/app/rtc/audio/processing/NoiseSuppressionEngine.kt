package com.zisee.app.rtc.audio.processing

import java.nio.ByteBuffer

enum class NoiseSuppressionMode(val label: String) { OFF("关闭降噪"), STANDARD("标准降噪"), AI("AI 降噪"), AUTO("自动降噪") }
enum class AiState { OFF, LOADING, READY, ACTIVE, DEGRADED, FAILED }
enum class AudioFallback { NONE, MODEL_UNAVAILABLE, MODEL_ERROR, FORMAT, DEADLINE, THERMAL, MEMORY, BENCHMARK, CONFIGURATION }

interface NoiseSuppressionEngine : AutoCloseable {
    /** 480 mono float samples, in WebRTC's FloatS16 scale; buffer owned by caller. */
    fun process(buffer: ByteBuffer): Boolean
    fun flush(): Boolean
}

data class AudioProcessingStats(
    val mode: NoiseSuppressionMode = NoiseSuppressionMode.AUTO,
    val state: AiState = AiState.OFF,
    val engine: String = "WebRTC NS",
    val fallback: AudioFallback = AudioFallback.NONE,
    val fallbackCount: Int = 0,
    val averageUs: Long = 0, val p95Us: Long = 0, val p99Us: Long = 0, val maxUs: Long = 0,
    val deadlineMisses: Long = 0,
    val benchmarkP95Us: Long? = null,
)

/** Independent of model/runtime: a 4ms budget reserves most of each 10ms frame for the RTC stack. */
class AudioDeadlineMonitor {
    private val timings = LongArray(256)
    private var cursor = 0
    private var count = 0
    private var slowFrames = 0
    var misses = 0L; private set
    var maxUs = 0L; private set
    fun record(us: Long): Boolean {
        timings[cursor] = us.coerceAtLeast(0); cursor = (cursor + 1) % timings.size
        count = (count + 1).coerceAtMost(timings.size)
        maxUs = maxOf(maxUs, us)
        if (us >= 10_000) misses++
        slowFrames = if (us >= 4_000) slowFrames + 1 else 0
        return us >= 10_000 || slowFrames >= 5
    }
    fun snapshot(base: AudioProcessingStats): AudioProcessingStats {
        if (count == 0) return base.copy(deadlineMisses = misses, maxUs = maxUs)
        val sorted = timings.copyOf(count).sortedArray()
        return base.copy(averageUs = sorted.sum() / count, p95Us = sorted[((count * 95 + 99) / 100 - 1)],
            p99Us = sorted[((count * 99 + 99) / 100 - 1)], maxUs = maxUs, deadlineMisses = misses)
    }
}
