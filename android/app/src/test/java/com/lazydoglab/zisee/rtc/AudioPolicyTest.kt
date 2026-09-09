package com.lazydoglab.zisee.rtc

import com.lazydoglab.zisee.rtc.audio.AudioBandwidthMode
import com.lazydoglab.zisee.rtc.audio.AudioBandwidthPolicy
import com.lazydoglab.zisee.rtc.audio.AudioRoute
import com.lazydoglab.zisee.rtc.audio.AudioRoutePolicy
import com.lazydoglab.zisee.rtc.audio.OpusPolicy
import org.junit.Assert.*
import org.junit.Test

class AudioPolicyTest {
    @Test fun audioReserveSuspendsVideoAndNeedsSustainedRecovery() {
        val policy = AudioBandwidthPolicy()
        assertEquals(AudioBandwidthMode.PRIMARY_ONLY, policy.update(200, null, 1000))
        // One collapsed sample is a handover, not a weak link.
        assertEquals(AudioBandwidthMode.PRIMARY_ONLY, policy.update(80, null, 2000))
        assertEquals(AudioBandwidthMode.PRIMARY_ONLY, policy.update(80, null, 3000))
        assertEquals(AudioBandwidthMode.AUDIO_ONLY, policy.update(80, null, 4000))
        for (time in 5000L..9000L step 1000) assertEquals(AudioBandwidthMode.AUDIO_ONLY, policy.update(80, null, time))
        // Recovering evidence restores the primary camera through a bounded probe, never directly.
        assertEquals(AudioBandwidthMode.VIDEO_PROBE, policy.update(500, 0.0, 10000))
        assertEquals(AudioBandwidthMode.VIDEO_PROBE, policy.update(500, 0.0, 12000))
        assertEquals(AudioBandwidthMode.PRIMARY_ONLY, policy.update(500, 0.0, 14000))
        for (time in 15000L..18000L step 1000) assertEquals(AudioBandwidthMode.PRIMARY_ONLY, policy.update(500, 0.0, time))
        assertEquals(AudioBandwidthMode.ALL_VIDEO, policy.update(500, 0.0, 19000))
        assertEquals(AudioBandwidthMode.PRIMARY_ONLY, policy.update(1000, 0.2, 20000))
        assertEquals(AudioBandwidthMode.AUDIO_ONLY, policy.update(1000, 0.2, 22000))
    }

    /** The handover case: no send estimate and no remote report must never hold video off. */
    @Test fun missingEvidenceEndsSuspensionInsteadOfExtendingIt() {
        val policy = AudioBandwidthPolicy()
        assertEquals(AudioBandwidthMode.PRIMARY_ONLY, policy.update(40, 0.3, 1000))
        assertEquals(AudioBandwidthMode.AUDIO_ONLY, policy.update(40, 0.3, 3000))
        assertEquals(AudioBandwidthMode.AUDIO_ONLY, policy.update(null, null, 4000))
        assertEquals(AudioBandwidthMode.VIDEO_PROBE, policy.update(null, null, 6000))
        assertEquals(AudioBandwidthMode.VIDEO_PROBE, policy.update(null, null, 8000))
        assertEquals(AudioBandwidthMode.PRIMARY_ONLY, policy.update(null, null, 10000))
    }

    /** Stale remote reports repeated by the sampler must not keep re-arming the pause. */
    @Test fun staleLossReportsDoNotSustainSuspension() {
        val policy = AudioBandwidthPolicy()
        assertEquals(AudioBandwidthMode.PRIMARY_ONLY, policy.update(40, 0.4, 1000, 1_000.0))
        assertEquals(AudioBandwidthMode.AUDIO_ONLY, policy.update(300, 0.4, 3000, 1_000.0))
        assertEquals(AudioBandwidthMode.AUDIO_ONLY, policy.update(300, 0.4, 5000, 1_000.0))
        assertEquals(AudioBandwidthMode.VIDEO_PROBE, policy.update(300, 0.4, 6000, 1_000.0))
        assertEquals(AudioBandwidthMode.PRIMARY_ONLY, policy.update(300, 0.4, 10000, 1_000.0))
    }

    @Test fun newRouteRetriesQuicklyAndIgnoresTheOldPathEstimate() {
        val policy = AudioBandwidthPolicy()
        assertEquals(AudioBandwidthMode.PRIMARY_ONLY, policy.update(40, 0.5, 1000))
        assertEquals(AudioBandwidthMode.AUDIO_ONLY, policy.update(40, 0.5, 3000))
        policy.routeChanged(4000)
        // A collapsed estimate on a freshly selected pair is the pair warming up, not a weak link.
        assertEquals(AudioBandwidthMode.AUDIO_ONLY, policy.update(10, null, 4500))
        assertEquals(AudioBandwidthMode.VIDEO_PROBE, policy.update(10, null, 5500))
        // A route change during a probe invalidates it and starts the retry window over.
        policy.routeChanged(6000)
        assertEquals(AudioBandwidthMode.AUDIO_ONLY, policy.update(600, null, 6500))
        assertEquals(AudioBandwidthMode.VIDEO_PROBE, policy.update(600, null, 7500))
        assertEquals(AudioBandwidthMode.PRIMARY_ONLY, policy.update(600, null, 12000))
    }

    @Test fun repeatedProbeFailuresBackOffButKeepRetrying() {
        val policy = AudioBandwidthPolicy()
        assertEquals(AudioBandwidthMode.PRIMARY_ONLY, policy.update(20, 0.5, 1000))
        assertEquals(AudioBandwidthMode.AUDIO_ONLY, policy.update(20, 0.5, 3000))
        var now = 3000L
        var probes = 0
        var lastProbeStart = 0L
        var gap = 0L
        var probing = false
        while (now < 300_000L) {
            now += 500
            // Audio-only looks fine, but every probe measures a link that cannot carry video.
            val mode = policy.update(if (probing) 20 else null, null, now)
            if (mode == AudioBandwidthMode.VIDEO_PROBE && !probing) { probes++; gap = now - lastProbeStart; lastProbeStart = now }
            probing = mode == AudioBandwidthMode.VIDEO_PROBE
        }
        assertTrue("probe stopped retrying: $probes", probes > 5)
        assertTrue("retry backoff unbounded: $gap", gap <= 35_000)
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
