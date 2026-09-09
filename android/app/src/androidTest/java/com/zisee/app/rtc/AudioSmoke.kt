package com.zisee.app.rtc

import android.content.Context
import android.os.Bundle
import com.zisee.app.rtc.audio.processing.AudioDeadlineMonitor
import com.zisee.app.rtc.audio.processing.AudioProcessingStats
import com.zisee.app.rtc.audio.processing.DeepFilterNetEngine
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

/** Synthetic PCM only: never records a microphone or writes speech to disk. */
object AudioSmoke {
    fun run(context: Context, output: Bundle) {
        repeat(2) { call ->
            DeepFilterNetEngine(context).use { engine ->
                val pcm = ByteBuffer.allocateDirect(1920).order(ByteOrder.nativeOrder())
                val timing = AudioDeadlineMonitor()
                var changed = false
                for (frame in 0 until 500) {
                    for (sample in 0 until 480) {
                        val phase = (frame * 480 + sample) * 2 * Math.PI / 48_000
                        pcm.putFloat(sample * 4, (sin(phase * 220) * 2500 + sin(phase * 3700) * 500).toFloat())
                    }
                    val before = pcm.getFloat(0)
                    val start = System.nanoTime()
                    check(engine.process(pcm)) { "native_process_failed" }
                    if (frame >= 20) timing.record((System.nanoTime() - start) / 1000)
                    changed = changed || pcm.getFloat(0) != before
                    for (sample in 0 until 480) check(pcm.getFloat(sample * 4).isFinite() && kotlin.math.abs(pcm.getFloat(sample * 4)) <= 32768)
                }
                check(changed)
                val stats = timing.snapshot(AudioProcessingStats())
                output.putString("audioNative$call", "PASS avg=${stats.averageUs} p95=${stats.p95Us} p99=${stats.p99Us} max=${stats.maxUs} us misses=${stats.deadlineMisses}")
                check(engine.flush())
                val malformed = ByteBuffer.allocateDirect(1916)
                check(!engine.process(malformed))
                check(!engine.process(pcm)) // Native failure latches; the caller must fall back.
            }
        }
        processingFallback(context, output)
    }

    private fun processingFallback(context: Context, output: Bundle) {
        org.webrtc.PeerConnectionFactory.initialize(org.webrtc.PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        val factory = org.webrtc.PeerConnectionFactory.builder().createPeerConnectionFactory()
        val source = factory.createAudioSource(org.webrtc.MediaConstraints())
        val track = factory.createAudioTrack("audio_test", source)
        try {
            for (reason in listOf("thermal", "memory", "format")) {
                var fallbacks = 0
                val processor = com.zisee.app.rtc.audio.processing.AudioProcessingEngine(context) { fallbacks++ }
                try {
                    processor.prepare()
                    check(processor.configure(track, com.zisee.app.rtc.audio.processing.NoiseSuppressionMode.AUTO))
                    if (processor.stats().state != com.zisee.app.rtc.audio.processing.AiState.ACTIVE) {
                        output.putString("audioPolicy", "SKIP: ${processor.stats().fallback}")
                        return
                    }
                    when (reason) {
                        "thermal" -> processor.setThermal(3)
                        "memory" -> processor.onMemoryPressure()
                        else -> {
                            processor.initialize(16_000, 1)
                            processor.process(1, 160, ByteBuffer.allocateDirect(640))
                        }
                    }
                    check(fallbacks == 1)
                    check(processor.configure(track, com.zisee.app.rtc.audio.processing.NoiseSuppressionMode.AUTO))
                    check(processor.stats().engine == "WebRTC NS")
                    check(processor.stats().state == com.zisee.app.rtc.audio.processing.AiState.DEGRADED)
                    processor.setThermal(0)
                    check(processor.configure(track, com.zisee.app.rtc.audio.processing.NoiseSuppressionMode.AUTO))
                    check(processor.stats().engine == "WebRTC NS")
                    processor.close()
                    processor.process(3, 480, ByteBuffer.allocateDirect(1920)) // Late callback is harmless.
                } finally { processor.close() }
            }
            output.putString("audioPolicy", "PASS: thermal, memory, format fallback; no automatic re-upgrade; late callback ignored")
        } finally { track.dispose(); source.dispose(); factory.dispose() }
    }
}
