package com.zisee.app.rtc.audio.processing

import android.content.Context
import android.os.Build
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.webrtc.AudioTrack
import org.webrtc.ExternalAudioProcessingFactory
import org.webrtc.audio.AudioProcessingComponentOptions
import org.webrtc.audio.AudioProcessingMode
import org.webrtc.audio.AudioProcessingOptions

/** Native audio callbacks process synchronously on WebRTC's audio thread, never the UI/RTC executor.
 * Control switches bypass AI first, then change WebRTC options outside the callback lock.
 */
class AudioProcessingEngine(private val context: Context, private val onFallback: () -> Unit) :
    ExternalAudioProcessingFactory.AudioProcessing, AutoCloseable {
    private var engine: NoiseSuppressionEngine? = null
    private var sampleRate = 0
    private var channels = 0
    private var active = false
    private var closed = false
    private val timing = AudioDeadlineMonitor()
    private var info = AudioProcessingStats()
    private var thermal = 0
    private var memoryPressure = false
    private var modelFailed = false
    private var performanceFailed = false

    /** Run before publishing the microphone track, off the main thread. */
    @Synchronized fun prepare() {
        check(!closed)
        info = info.copy(state = AiState.LOADING)
        try {
            val prefs = context.getSharedPreferences("audio_benchmark", Context.MODE_PRIVATE)
            val key = "df-mobile-v1-${Build.FINGERPRINT.hashCode()}"
            val age = System.currentTimeMillis() - prefs.getLong("$key-time", 0)
            val cached = if (age in 0..86_400_000L) prefs.getLong(key, -1) else -1
            if (cached >= 4_000) {
                info = info.copy(benchmarkP95Us = cached)
                performanceFailed = true; degrade(AudioFallback.BENCHMARK); return
            }
            val loaded = DeepFilterNetEngine(context)
            engine = loaded
            val buffer = ByteBuffer.allocateDirect(480 * 4).order(ByteOrder.nativeOrder())
            val benchmark = AudioDeadlineMonitor()
            // Non-silent deterministic input exercises inference, not just a silence fast path.
            var noise = 7
            repeat(if (cached < 0) 80 else 20) { frame ->
                for (sample in 0 until 480) {
                    noise = noise * 1664525 + 1013904223
                    buffer.putFloat(sample * 4, ((noise ushr 16) - 32768).toFloat() * 0.1f)
                }
                val started = System.nanoTime()
                check(loaded.process(buffer)) { "audio_model_error" }
                if (frame >= 10) benchmark.record((System.nanoTime() - started) / 1000)
            }
            val measured = benchmark.snapshot(info).p95Us
            info = info.copy(benchmarkP95Us = measured)
            if (cached < 0) prefs.edit().putLong(key, measured).putLong("$key-time", System.currentTimeMillis()).apply()
            if (measured >= 4_000) { performanceFailed = true; degrade(AudioFallback.BENCHMARK); return }
            // Flush synthetic warm-up audio from the overlap/state before the microphone starts.
            check(loaded.flush())
            info = info.copy(state = AiState.READY)
        } catch (error: LinkageError) { modelFailed = true; degrade(AudioFallback.MODEL_UNAVAILABLE) }
        catch (error: Exception) { modelFailed = true; degrade(AudioFallback.MODEL_ERROR) }
    }

    /** Called on RTC executor. Never hold our lock while entering WebRTC (lock-order inversion). */
    fun configure(track: AudioTrack, mode: NoiseSuppressionMode): Boolean {
        var ai = synchronized(this) {
            active = false
            info = info.copy(mode = mode)
            mode in listOf(NoiseSuppressionMode.AI, NoiseSuppressionMode.AUTO) &&
                engine != null && !closed && !modelFailed && !performanceFailed && !memoryPressure && thermal < 3
        }
        // AI is bypassed and the synchronized block above waited for any in-flight callback.
        // Flush outside that lock so standard-mode capture continues during a mode switch.
        if (ai) {
            val ready = try { engine?.flush() == true }
            catch (error: LinkageError) { false }
            catch (error: Exception) { false }
            if (!ready) {
                synchronized(this) { modelFailed = true; degrade(AudioFallback.MODEL_ERROR) }
                ai = false
            }
        }
        fun software(enabled: Boolean) = AudioProcessingComponentOptions(enabled, AudioProcessingMode.SOFTWARE)
        var result = track.setAudioProcessingOptions(AudioProcessingOptions(
            software(true), software(!ai && mode != NoiseSuppressionMode.OFF), software(!ai), software(true)))
        if (!result.isSuccess && ai) {
            synchronized(this) { modelFailed = true; degrade(AudioFallback.CONFIGURATION) }
            ai = false
            result = track.setAudioProcessingOptions(AudioProcessingOptions(
                software(true), software(true), software(true), software(true)))
        }
        synchronized(this) {
            if (result.isSuccess) {
                active = ai
                info = info.copy(state = if (ai) AiState.ACTIVE else if (mode == NoiseSuppressionMode.OFF) AiState.OFF
                    else if (info.fallback != AudioFallback.NONE) AiState.DEGRADED else AiState.OFF,
                    engine = if (ai) "DeepFilterNet mobile" else if (mode == NoiseSuppressionMode.OFF) "OFF" else "WebRTC NS")
            } else {
                degrade(AudioFallback.CONFIGURATION)
                info = info.copy(state = AiState.FAILED, engine = "configuration unknown")
            }
        }
        // A safety fallback is latched for this call; release its model working set as well.
        // The callback was bypassed above and control operations share the RTC executor.
        val retired = synchronized(this) {
            if (!active && (modelFailed || performanceFailed || memoryPressure)) engine.also { engine = null } else null
        }
        try { retired?.close() }
        catch (error: LinkageError) { synchronized(this) { degrade(AudioFallback.MODEL_ERROR) } }
        catch (error: Exception) { synchronized(this) { degrade(AudioFallback.MODEL_ERROR) } }
        return result.isSuccess
    }

    @Synchronized override fun initialize(sampleRateHz: Int, numChannels: Int) {
        sampleRate = sampleRateHz; channels = numChannels
    }
    @Synchronized override fun reset(newRate: Int) { sampleRate = newRate }
    @Synchronized override fun process(numBands: Int, numFrames: Int, buffer: ByteBuffer) {
        if (!active || closed) return
        if (sampleRate != 48_000 || channels != 1 || numFrames != 480 || !buffer.isDirect || buffer.capacity() != 1920) {
            modelFailed = true; degrade(AudioFallback.FORMAT); onFallback(); return
        }
        val start = System.nanoTime()
        val success = try { engine?.process(buffer) == true }
        catch (error: LinkageError) { false }
        catch (error: Exception) { false }
        val slow = timing.record((System.nanoTime() - start) / 1000)
        if (!success || slow) {
            if (!success) modelFailed = true else performanceFailed = true
            degrade(if (!success) AudioFallback.MODEL_ERROR else AudioFallback.DEADLINE)
            onFallback()
        }
    }

    @Synchronized fun setThermal(status: Int) {
        thermal = status
        if (active && status >= 3) { degrade(AudioFallback.THERMAL); onFallback() }
        // No automatic re-upgrade during the same call: avoid thermal oscillation.
        if (status >= 3) performanceFailed = true
    }
    @Synchronized fun onMemoryPressure() {
        if (memoryPressure || closed) return
        memoryPressure = true
        if (engine != null) { degrade(AudioFallback.MEMORY); onFallback() }
    }
    @Synchronized fun stats(): AudioProcessingStats = timing.snapshot(info)
    private fun degrade(reason: AudioFallback) {
        active = false
        info = info.copy(state = AiState.DEGRADED, fallback = reason, fallbackCount = info.fallbackCount + 1,
            engine = "WebRTC NS")
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true; active = false
        engine?.close(); engine = null
    }
}
