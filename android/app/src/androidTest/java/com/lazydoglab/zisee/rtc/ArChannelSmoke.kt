package com.lazydoglab.zisee.rtc

import android.content.Context
import com.lazydoglab.zisee.ar.annotation.*
import com.lazydoglab.zisee.ar.collaboration.ArDataChannel
import com.lazydoglab.zisee.ar.collaboration.ArFieldEndpoint
import com.lazydoglab.zisee.ar.session.MarkerKind
import com.lazydoglab.zisee.media.MediaTrack
import kotlinx.coroutines.*
import org.webrtc.*
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Exercises actual SCTP/DTLS on two local PeerConnections; never opens a camera or ARCore. */
internal object ArChannelSmoke {
    fun run(context: Context) = runBlocking {
        val executor = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try { withContext(executor) {
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
            val factory = PeerConnectionFactory.builder().setOptions(PeerConnectionFactory.Options().apply {
                disableNetworkMonitor = true
            }).createPeerConnectionFactory()
            val candidates = List(2) { ConcurrentLinkedQueue<IceCandidate>() }
            val peers = mutableListOf<PeerConnection>()
            val controls = mutableListOf<DataChannel>()
            val channels = mutableListOf<ArDataChannel>()
            val rawArChannels = mutableListOf<DataChannel>()
            val rawStrokeChannels = mutableListOf<DataChannel>()
            val failures = AtomicInteger()
            val controlReceived = CompletableDeferred<Unit>()
            val field = object : ArFieldEndpoint {
                override val sessionId = UUID.randomUUID()
                override val depthSupported = false
                var creates = 0
                var clears = 0
                var strokeMessages = 0
                var closes = 0
                override suspend fun execute(message: ArMessage): ArMessage.Result? = when (message) {
                    is ArMessage.Create -> {
                        check(message.request.frame.timestampNs == 9001L)
                        creates++
                        ArMessage.Result(sessionId, message.id, null)
                    }
                    is ArMessage.Clear -> { clears++; null }
                    else -> null
                }
                override suspend fun executeStroke(message: ArStrokeMessage): ArStrokeMessage.Result? {
                    strokeMessages++
                    return if (message is ArStrokeMessage.End) ArStrokeMessage.Result(sessionId, message.id, null) else null
                }
                override suspend fun close() { closes++ }
            }
            try {
                repeat(2) { index ->
                    val peer = requireNotNull(factory.createPeerConnection(PeerConnection.RTCConfiguration(emptyList()),
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
                        }))
                    peers.add(peer)
                    controls.add(peer.createDataChannel("camera-state", DataChannel.Init().apply { negotiated = true; id = 0 }))
                    val rawAr = peer.createDataChannel(ArProtocol.CHANNEL_LABEL,
                        DataChannel.Init().apply { negotiated = true; id = 2; ordered = true })
                    val rawStroke = peer.createDataChannel(ArStrokeProtocol.CHANNEL_LABEL,
                        DataChannel.Init().apply { negotiated = true; id = 4; ordered = true })
                    rawArChannels.add(rawAr)
                    rawStrokeChannels.add(rawStroke)
                    channels.add(ArDataChannel(rawAr, executor, false, { failures.incrementAndGet() }, rawStroke))
                }
                controls[1].registerObserver(object : DataChannel.Observer {
                    override fun onBufferedAmountChange(previousAmount: Long) = Unit
                    override fun onStateChange() = Unit
                    override fun onMessage(buffer: DataChannel.Buffer) { controlReceived.complete(Unit) }
                })
                withTimeout(15_000) {
                    val offer = description(peers[0], true)
                    set(peers[0], offer, true); set(peers[1], offer, false)
                    val answer = description(peers[1], false)
                    set(peers[1], answer, true); set(peers[0], answer, false)
                    while (channels.any { !it.state.value.connected }) {
                        repeat(2) { index ->
                            while (true) {
                                val candidate = candidates[index].poll() ?: break
                                check(peers[1 - index].addIceCandidate(candidate))
                            }
                        }
                        delay(20)
                    }
                    val local = channels[0]; val guide = channels[1]
                    check(local.attach(field))
                    while (guide.state.value.remote == null) delay(10)
                    // Automatic join may already complete on the local SCTP loopback.
                    if (!guide.state.value.joined) check(guide.join(field.sessionId))
                    while (!guide.state.value.joined) delay(10)
                    while (!guide.state.value.remoteStrokeSupported) delay(10)
                    val id = UUID.randomUUID()
                    check(guide.create(id, MarkerKind.PIN, SpatialMarkerRequest(
                        VideoFrameReference(MediaTrack.BACK_CAMERA, 9001), VideoPoint(.2f, .7f))))
                    while (guide.state.value.lastResult?.id != id) delay(10)
                    check(field.creates == 1 && guide.state.value.lastResult?.rejection == null)
                    val strokeId = UUID.randomUUID()
                    val strokePoint = SpatialMarkerRequest(VideoFrameReference(MediaTrack.BACK_CAMERA, 9001), VideoPoint(.2f, .7f))
                    check(guide.beginStroke(strokeId, strokePoint))
                    check(guide.appendStroke(strokeId, listOf(strokePoint)))
                    check(guide.endStroke(strokeId, false))
                    while (guide.state.value.lastStrokeResult?.id != strokeId) delay(10)
                    check(field.strokeMessages == 3 && guide.state.value.lastStrokeResult?.rejection == null)
                    check(guide.clear())
                    while (field.clears != 1) delay(10)
                    guide.leave()
                    check(!guide.clear())
                    local.detach()
                    while (guide.state.value.remote != null) delay(10)
                    check(field.closes == 1)
                    var failureCloses = 0
                    check(local.attach(object : ArFieldEndpoint {
                        override val sessionId = UUID.randomUUID()
                        override val depthSupported = false
                        override suspend fun execute(message: ArMessage): ArMessage.Result? = null
                        override suspend fun executeStroke(message: ArStrokeMessage): ArStrokeMessage.Result? = null
                        override suspend fun close() { failureCloses++ }
                    }))
                    while (guide.state.value.remote == null) delay(10)
                    // Malformed peer traffic terminates AR and its field lease, not the call.
                    check(rawArChannels[1].send(DataChannel.Buffer(ByteBuffer.wrap("{}".toByteArray(Charsets.UTF_8)), false)))
                    while (local.state.value.connected) delay(10)
                    local.close(); guide.close()
                    check(failureCloses == 1)
                    check(controls[0].state() == DataChannel.State.OPEN)
                    check(controls[0].send(DataChannel.Buffer(ByteBuffer.wrap(byteArrayOf(1)), true)))
                    controlReceived.await()
                    check(failures.get() == 1)
                }
            } finally {
                channels.forEach { it.close() }
                controls.forEach { it.unregisterObserver(); it.close(); it.dispose() }
                peers.forEach { it.close(); it.dispose() }
                factory.dispose()
            }
        } } finally { executor.close() }
    }

    internal suspend fun description(peer: PeerConnection, offer: Boolean): SessionDescription = suspendCancellableCoroutine { cont ->
        val observer = object : Observer() {
            override fun onCreateSuccess(sdp: SessionDescription) { if (cont.isActive) cont.resume(sdp) }
            override fun onCreateFailure(error: String) { if (cont.isActive) cont.resumeWithException(IllegalStateException("create_description_failed")) }
        }
        if (offer) peer.createOffer(observer, MediaConstraints()) else peer.createAnswer(observer, MediaConstraints())
    }
    internal suspend fun set(peer: PeerConnection, sdp: SessionDescription, local: Boolean) = suspendCancellableCoroutine<Unit> { cont ->
        val observer = object : Observer() {
            override fun onSetSuccess() { if (cont.isActive) cont.resume(Unit) }
            override fun onSetFailure(error: String) { if (cont.isActive) cont.resumeWithException(IllegalStateException("set_description_failed")) }
        }
        if (local) peer.setLocalDescription(observer, sdp) else peer.setRemoteDescription(observer, sdp)
    }
    private open class Observer : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String) = Unit
        override fun onSetFailure(error: String) = Unit
    }
}
