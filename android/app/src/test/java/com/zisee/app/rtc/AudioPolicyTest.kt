package com.zisee.app.rtc

import com.zisee.app.rtc.audio.AudioRoute
import com.zisee.app.rtc.audio.AudioRoutePolicy
import com.zisee.app.rtc.audio.OpusPolicy
import org.junit.Assert.*
import org.junit.Test

class AudioPolicyTest {
    @Test fun audioReserveSuspendsVideoAndNeedsSustainedRecovery() {
        val policy = com.zisee.app.rtc.audio.AudioBandwidthPolicy()
        assertEquals(com.zisee.app.rtc.audio.AudioBandwidthMode.PRIMARY_ONLY, policy.update(200, null, 1000))
        assertEquals(com.zisee.app.rtc.audio.AudioBandwidthMode.AUDIO_ONLY, policy.update(80, null, 2000))
        assertEquals(com.zisee.app.rtc.audio.AudioBandwidthMode.AUDIO_ONLY, policy.update(null, null, 3000))
        for (time in 4000L..8000L step 1000) assertEquals(com.zisee.app.rtc.audio.AudioBandwidthMode.AUDIO_ONLY, policy.update(500, 0.0, time))
        assertEquals(com.zisee.app.rtc.audio.AudioBandwidthMode.ALL_VIDEO, policy.update(500, 0.0, 9000))
        assertEquals(com.zisee.app.rtc.audio.AudioBandwidthMode.AUDIO_ONLY, policy.update(1000, 0.2, 10000))
    }
    @Test fun externalRouteAndDisconnectFallback() {
        val devices = setOf(AudioRoute.SPEAKER, AudioRoute.EARPIECE, AudioRoute.BLUETOOTH)
        assertEquals(AudioRoute.BLUETOOTH, AudioRoutePolicy.select(devices, false))
        assertEquals(AudioRoute.SPEAKER, AudioRoutePolicy.select(devices, true))
        assertEquals(AudioRoute.EARPIECE, AudioRoutePolicy.select(devices - AudioRoute.BLUETOOTH, false))
        assertEquals(AudioRoute.SPEAKER, AudioRoutePolicy.select(setOf(AudioRoute.SPEAKER), false))
        assertNull(AudioRoutePolicy.select(emptySet(), false))
    }
    @Test fun opusUsesNegotiatedPayloadAndPreservesOtherSections() {
        val sdp = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 0 109\r\na=rtpmap:109 opus/48000/2\r\n" +
            "a=fmtp:109 minptime=10;useinbandfec=0\r\na=ptime:60\r\n" +
            "m=video 9 UDP/TLS/RTP/SAVPF 96\r\na=rtpmap:96 VP8/90000\r\n" +
            "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n"
        val configured = OpusPolicy.apply(sdp)
        assertTrue(configured.contains("SAVPF 109 0\r\n"))
        assertTrue(configured.contains("minptime=10;useinbandfec=1;usedtx=1;stereo=0;sprop-stereo=0;maxaveragebitrate=32000"))
        assertTrue(configured.contains("a=ptime:20\r\n"))
        assertEquals(sdp.substringAfter("m=video"), configured.substringAfter("m=video"))
        assertEquals(configured, OpusPolicy.apply(configured))
        val rejected = sdp.replace("m=audio 9", "m=audio 0")
        assertEquals(rejected, OpusPolicy.apply(rejected))
    }
    @Test fun audioIntervalsDoNotMixVideoAndHandleResets() {
        fun entries(n: Int) = listOf(
            StatsEntry("audio", "inbound-rtp", mapOf("kind" to "audio", "packetsReceived" to n * 90,
                "packetsLost" to n * 10, "jitterBufferDelay" to n * 2.0, "jitterBufferEmittedCount" to n * 100,
                "concealedSamples" to n * 100, "totalSamplesReceived" to n * 1000)),
            StatsEntry("video", "inbound-rtp", mapOf("kind" to "video", "packetsLost" to n * 900)),
        )
        val sampler = MediaStatsSampler()
        assertNull(sampler.sample(entries(1), 1000).audio.inboundLoss)
        val result = sampler.sample(entries(2), 2000).audio
        assertEquals(0.1, result.inboundLoss!!, 0.0001)
        assertEquals(20.0, result.jitterBufferMs!!, 0.0001)
        assertEquals(0.1, result.concealmentRatio!!, 0.0001)
        assertNull(result.outboundLoss)
        assertNull(sampler.sample(entries(0), 3000).audio.concealmentRatio)
    }
}
