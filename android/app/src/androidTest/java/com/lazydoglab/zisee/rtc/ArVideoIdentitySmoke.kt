package com.lazydoglab.zisee.rtc

import android.content.Context
import android.opengl.GLES20
import com.lazydoglab.zisee.ar.annotation.VideoFrameReference
import com.lazydoglab.zisee.ar.render.*
import com.lazydoglab.zisee.media.MediaTrack
import kotlinx.coroutines.*
import kotlinx.coroutines.android.asCoroutineDispatcher
import org.webrtc.*
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/** Real PeerConnection, H264 packetization and MediaCodec; synthetic RGB camera pixels. */
internal object ArVideoIdentitySmoke {
    fun run(context: Context, instrumentation: android.app.Instrumentation, display: Boolean, waitForForeground: Boolean) = runBlocking {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        val activity = if (display) ArTestActivityLauncher.open(instrumentation, waitForForeground) else null
        val egl = EglBase.create()
        val renderer = activity?.renderer
        instrumentation.runOnMainSync { renderer?.init(egl.eglBaseContext) }
        val factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(ArEncoderFactory(egl.eglBaseContext))
            .setVideoDecoderFactory(ArDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
        val helper = requireNotNull(SurfaceTextureHelper.create("ArIdentityTest", egl.eglBaseContext))
        val gl = helper.handler.asCoroutineDispatcher()
        val drained = CompletableDeferred<Unit>()
        val pool = withContext(gl) { ArFramePool(helper.handler) { helper.dispose(); drained.complete(Unit) } }
        val source = factory.createVideoSource(false)
        val track = factory.createVideoTrack(MediaTrack.BACK_CAMERA.wireId, source)
        val session = UUID.randomUUID()
        val expected = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
        val received = CompletableDeferred<ArFrameIdentity>()
        val candidates = List(2) { ConcurrentLinkedQueue<IceCandidate>() }
        val peers = mutableListOf<PeerConnection>()
        var remote: VideoTrack? = null
        val sink = VideoSink { frame ->
            (frame.buffer as? ArTextureBuffer)?.identity?.let {
                if (it.sessionId == session && it.reference.timestampNs in expected) received.complete(it)
            }
            renderer?.onIdentifiedFrame(frame, (frame.buffer as? ArTextureBuffer)?.identity)
        }
        try {
            repeat(2) { index ->
                peers.add(requireNotNull(factory.createPeerConnection(PeerConnection.RTCConfiguration(emptyList()),
                    object : PeerConnection.Observer {
                        override fun onIceCandidate(candidate: IceCandidate) { candidates[index].add(candidate) }
                        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
                        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
                        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
                        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
                        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
                        override fun onAddStream(stream: MediaStream) = Unit
                        override fun onRemoveStream(stream: MediaStream) = Unit
                        override fun onDataChannel(channel: DataChannel) { channel.close(); channel.dispose() }
                        override fun onRenegotiationNeeded() = Unit
                        override fun onTrack(transceiver: RtpTransceiver) {
                            if (index == 1) (transceiver.receiver.track() as? VideoTrack)?.let {
                                remote = it; it.addSink(sink)
                            }
                        }
                    })))
            }
            val transceiver = peers[0].addTransceiver(track)
            val codecs = factory.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs
                .filter { it.name.equals("H264", true) }
            check(codecs.isNotEmpty()) { "H264 unavailable" }
            transceiver.setCodecPreferences(codecs)
            val offer = ArChannelSmoke.description(peers[0], true)
            ArChannelSmoke.set(peers[0], offer, true); ArChannelSmoke.set(peers[1], offer, false)
            val answer = ArChannelSmoke.description(peers[1], false)
            ArChannelSmoke.set(peers[1], answer, true); ArChannelSmoke.set(peers[0], answer, false)
            source.capturerObserver.onCapturerStarted(true)
            withTimeout(15_000) {
                while (!received.isCompleted) {
                    repeat(2) { index ->
                        while (true) {
                            val candidate = candidates[index].poll() ?: break
                            check(peers[1 - index].addIceCandidate(candidate))
                        }
                    }
                    withContext(gl) {
                        val ns = System.nanoTime()
                        expected.add(ns)
                        val buffer = pool.capture(320, 240) {
                            GLES20.glClearColor(.8f, .2f, .1f, 1f)
                            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT); true
                        }
                        if (buffer != null) {
                            val tagged = ArTextureBuffer(buffer, ArFrameIdentity(session,
                                VideoFrameReference(MediaTrack.BACK_CAMERA, ns)))
                            val frame = VideoFrame(tagged, 90, ns)
                            try { source.capturerObserver.onFrameCaptured(frame) } finally { frame.release() }
                        }
                    }
                    delay(40)
                }
                received.await()
                if (renderer != null) {
                    while (renderer.displayedArFrame.value == null) delay(20)
                    val displayed = requireNotNull(renderer.displayedArFrame.value)
                    check(displayed.identity.sessionId == session)
                    check(displayed.identity.reference.timestampNs in expected)
                    check(displayed.geometry.rotation == 90)
                }
            }
        } finally {
            remote?.removeSink(sink)
            instrumentation.runOnMainSync { renderer?.release(); activity?.finish() }
            source.capturerObserver.onCapturerStopped()
            peers.forEach { it.close(); it.dispose() }
            track.dispose(); source.dispose(); factory.dispose()
            withContext(gl) { pool.close() }
            withTimeout(5_000) { drained.await() }
            egl.release()
        }
    }
}
