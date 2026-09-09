package com.zisee.app.rtc.audio.processing

import java.nio.ByteBuffer
import org.webrtc.ExternalAudioProcessingFactory

/** The SDK's external APM is a process-global singleton. Keep its bridge alive for the process;
 * never call its unsafe destroy() across calls. Models and callbacks remain call-owned.
 * Reject overlapping users instead of allowing a second call to steal the microphone processor.
 */
object SharedAudioProcessing : ExternalAudioProcessingFactory.AudioProcessing {
    private val factory by lazy { ExternalAudioProcessingFactory().also { it.setCapturePostProcessing(this) } }
    @Volatile private var owner: AudioProcessingEngine? = null
    @Volatile private var rate = 48_000
    @Volatile private var channels = 1
    @Synchronized fun acquire(processor: AudioProcessingEngine): ExternalAudioProcessingFactory {
        check(owner == null) { "audio_processor_in_use" }
        val result = factory
        processor.initialize(rate, channels)
        owner = processor
        return result
    }
    @Synchronized fun release(processor: AudioProcessingEngine) { if (owner === processor) owner = null }
    override fun initialize(sampleRateHz: Int, numChannels: Int) {
        rate = sampleRateHz; channels = numChannels; owner?.initialize(sampleRateHz, numChannels)
    }
    override fun reset(newRate: Int) { rate = newRate; owner?.reset(newRate) }
    override fun process(numBands: Int, numFrames: Int, buffer: ByteBuffer) { owner?.process(numBands, numFrames, buffer) }
}
