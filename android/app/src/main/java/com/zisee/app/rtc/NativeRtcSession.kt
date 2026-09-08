package com.zisee.app.rtc

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import com.zisee.app.call.IceServerConfig
import com.zisee.app.core.logging.AppEvent
import com.zisee.app.core.logging.AppLogger
import com.zisee.app.media.MediaTrack
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule

data class MediaStats(
    val videoFrames: Long = 0, val audioReceived: Long = 0, val audioSent: Long = 0,
    val candidateType: String = "—", val rttMs: Long = 0,
    val remoteCandidateType: String = "—",
    val videoWidth: Int = 0, val videoHeight: Int = 0, val videoFps: Int = 0,
    val jitterMs: Long = 0, val packetsLost: Long = 0,
    val receiveKbps: Long = 0, val sendKbps: Long = 0,
)

/** One foreground call owns every native resource. All native operations use the RTC executor. */
class NativeRtcSession(private val context: Context, private val logger: AppLogger) : RtcSession {
    private val dispatcher = Executors.newSingleThreadExecutor { Thread(it, "ZiseeRtc") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    override val iceState = MutableStateFlow(IceState.NEW)
    private var egl: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var audioModule: JavaAudioDeviceModule? = null
    private var peer: PeerConnection? = null
    private var camera: CameraVideoCapturer? = null
    private var texture: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var videoTrack: VideoTrack? = null
    private var audioTrack: AudioTrack? = null
    private var remoteTrack: VideoTrack? = null
    var localFeed: VideoFeed? = null; private set
    var remoteFeed: VideoFeed? = null; private set
    private var gathered = CompletableDeferred<Unit>()
    private val networksReady = CompletableDeferred<Unit>()
    private val networkObserver = NetworkMonitor.NetworkObserver { type ->
        if (type != NetworkChangeDetector.ConnectionType.CONNECTION_NONE) {
            networksSeen = true; networksReady.complete(Unit)
        }
    }
    private var released = false
    private var lastCandidate: String? = null
    private var lastBytesReceived = 0L
    private var lastBytesSent = 0L
    private var lastSampleNanos = 0L
    private var startedNanos = 0L
    private var setupReported = false
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var previousMode = AudioManager.MODE_NORMAL
    private var previousSpeaker = false
    private var focus: AudioFocusRequest? = null

    suspend fun start(iceServers: List<IceServerConfig>) = withContext(dispatcher) {
        initialize(context, logger)
        egl = EglBase.create()
        val shared = requireNotNull(egl).eglBaseContext
        audioModule = JavaAudioDeviceModule.builder(context).setEnableVolumeLogger(false)
            .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                override fun onWebRtcAudioRecordInitError(error: String) = fail()
                override fun onWebRtcAudioRecordStartError(code: JavaAudioDeviceModule.AudioRecordStartErrorCode, error: String) = fail()
                override fun onWebRtcAudioRecordError(error: String) = fail()
            }).setAudioTrackErrorCallback(object : JavaAudioDeviceModule.AudioTrackErrorCallback {
                override fun onWebRtcAudioTrackInitError(error: String) = fail()
                override fun onWebRtcAudioTrackStartError(code: JavaAudioDeviceModule.AudioTrackStartErrorCode, error: String) = fail()
                override fun onWebRtcAudioTrackError(error: String) = fail()
            }).createAudioDeviceModule()
        factory = PeerConnectionFactory.builder().setAudioDeviceModule(audioModule)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(shared, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(shared)).createPeerConnectionFactory()
        val servers = iceServers.map { entry ->
            PeerConnection.IceServer.builder(entry.urls)
                .setUsername(entry.username).setPassword(entry.credential).createIceServer()
        }
        val config = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }
        // Creating the first peer connection starts the Android network monitor, which fills in
        // asynchronously. Registering before that guarantees the arrival callback is not missed.
        NetworkMonitor.addNetworkObserver(networkObserver)
        peer = requireNotNull(factory).createPeerConnection(config, observer) ?: throw IOException("peer_creation_failed")
        // A timeout here means ICE will gather with no interface known yet, which is the usual
        // cause of an empty candidate list, so record it rather than silently continuing.
        if (!networksSeen && withTimeoutOrNull(NETWORK_WAIT_MS) { networksReady.await() } == null) {
            logger.info(AppEvent.RTC_ICE_STATE, "networks_timeout")
        }
        val enumerator: CameraEnumerator = if (Camera2Enumerator.isSupported(context)) Camera2Enumerator(context) else Camera1Enumerator(true)
        val name = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?: enumerator.deviceNames.firstOrNull() ?: throw IOException("camera_unavailable")
        localFeed = VideoFeed(shared, enumerator.isFrontFacing(name))
        remoteFeed = VideoFeed(shared, false)
        camera = enumerator.createCapturer(name, object : CameraVideoCapturer.CameraEventsHandler {
            override fun onCameraError(error: String) = fail()
            override fun onCameraDisconnected() = fail()
            override fun onCameraFreezed(error: String) = fail()
            override fun onCameraOpening(name: String) = Unit
            override fun onFirstFrameAvailable() = Unit
            override fun onCameraClosed() = Unit
        }) ?: throw IOException("camera_unavailable")
        texture = SurfaceTextureHelper.create("ZiseeCapture", shared)
        videoSource = requireNotNull(factory).createVideoSource(false)
        requireNotNull(camera).initialize(texture, context, requireNotNull(videoSource).capturerObserver)
        val cameraId = if (enumerator.isFrontFacing(name)) MediaTrack.FRONT_CAMERA else MediaTrack.BACK_CAMERA
        videoTrack = requireNotNull(factory).createVideoTrack(cameraId.wireId, videoSource).also {
            it.addSink(localFeed); requireNotNull(peer).addTrack(it, listOf("zisee"))
        }
        audioSource = requireNotNull(factory).createAudioSource(MediaConstraints())
        audioTrack = requireNotNull(factory).createAudioTrack(MediaTrack.MICROPHONE.wireId, audioSource).also {
            requireNotNull(peer).addTrack(it, listOf("zisee"))
        }
        withContext(Dispatchers.Main.immediate) { acquireAudio() }
        // Capturer chooses a supported format closest to 720p/30. No CPU bitmap conversion.
        requireNotNull(camera).startCapture(1280, 720, 30)
        startedNanos = System.nanoTime()
        Unit
    }

    suspend fun localDescription(offer: Boolean): SessionDescription = withContext(dispatcher) {
        val pc = requireNotNull(peer)
        val attempts = if (offer) GATHER_ATTEMPTS else 1
        // One budget covers every attempt, so retrying cannot multiply the time a caller waits
        // before a broken network is reported.
        val deadline = System.nanoTime() + GATHER_BUDGET_MS * 1_000_000
        repeat(attempts) { attempt ->
            if (attempt > 0) {
                // The Android network monitor is populated asynchronously after the first peer
                // connection starts it, so the first generation can finish gathering before any
                // interface is known and yield zero candidates. Gathering only re-runs for a new
                // ICE generation, so restart ICE and describe again once interfaces have arrived.
                delay(GATHER_RETRY_MS)
                gathered = CompletableDeferred()
                pc.restartIce()
            }
            val description = withTimeout(10_000) { suspendCancellableCoroutine { continuation ->
                val observer = object : DescriptionObserver() {
                    override fun onCreateSuccess(sdp: SessionDescription) { if (continuation.isActive) continuation.resume(sdp) }
                    override fun onCreateFailure(error: String) { if (continuation.isActive) continuation.resumeWithException(IOException("sdp_create_failed")) }
                }
                if (offer) pc.createOffer(observer, MediaConstraints()) else pc.createAnswer(observer, MediaConstraints())
            } }
            setDescription(description, local = true)
            // Send the final local SDP including candidates. Completion is the normal signal, but
            // a stack that never reports it must not abort the call: time out into the candidate
            // check below so the remaining attempts still run and the failure names itself.
            val remaining = (deadline - System.nanoTime()) / 1_000_000
            if (remaining > 0) withTimeoutOrNull(remaining) { gathered.await() }
            val final = pc.localDescription ?: throw IOException("local_description_missing")
            if (final.description.contains("\r\na=candidate:")) return@withContext final
        }
        throw IOException("no_ice_candidates")
    }

    suspend fun remoteDescription(type: String, sdp: String) = withContext(dispatcher) {
        require(type == "offer" || type == "answer")
        setDescription(SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdp), local = false)
    }

    private suspend fun setDescription(sdp: SessionDescription, local: Boolean) = withTimeout(10_000) {
        suspendCancellableCoroutine<Unit> { continuation ->
            val observer = object : DescriptionObserver() {
                override fun onSetSuccess() { if (continuation.isActive) continuation.resume(Unit) }
                override fun onSetFailure(error: String) { if (continuation.isActive) continuation.resumeWithException(IOException("sdp_set_failed")) }
            }
            if (local) requireNotNull(peer).setLocalDescription(observer, sdp) else requireNotNull(peer).setRemoteDescription(observer, sdp)
        }
    }

    suspend fun stats(): MediaStats = withContext(dispatcher) { withTimeout(3_000) {
        suspendCancellableCoroutine { continuation ->
            requireNotNull(peer).getStats { report ->
                val values = report.statsMap.values
                fun kindOf(entry: RTCStats) = entry.members["kind"] ?: entry.members["mediaType"]
                fun sum(type: String, kind: String?, field: String) = values.filter {
                    it.type == type && (kind == null || kindOf(it) == kind)
                }.sumOf { (it.members[field] as? Number)?.toLong() ?: 0 }
                fun number(entry: RTCStats?, field: String) = entry?.members?.get(field) as? Number
                val video = values.firstOrNull { it.type == "inbound-rtp" && kindOf(it) == "video" }
                val audio = values.firstOrNull { it.type == "inbound-rtp" && kindOf(it) == "audio" }
                val transport = values.firstOrNull { it.type == "transport" && it.members["selectedCandidatePairId"] != null }
                val pair = report.statsMap[transport?.members?.get("selectedCandidatePairId")]
                fun candidateType(id: Any?) = (report.statsMap[id]?.members?.get("candidateType") as? String)
                    ?.takeIf { it in setOf("host", "srflx", "prflx", "relay") } ?: "—"
                // Throughput is a delta, so it needs the previous sample. The first poll of a call
                // has no predecessor and reports zero rather than a meaningless spike.
                val received = sum("inbound-rtp", null, "bytesReceived")
                val sent = sum("outbound-rtp", null, "bytesSent")
                val now = System.nanoTime()
                val elapsedMs = if (lastSampleNanos == 0L) 0L else (now - lastSampleNanos) / 1_000_000
                fun kbps(current: Long, previous: Long) =
                    if (elapsedMs <= 0) 0L else (current - previous) * 8 / elapsedMs
                val result = MediaStats(
                    videoFrames = number(video, "framesDecoded")?.toLong() ?: 0,
                    audioReceived = sum("inbound-rtp", "audio", "bytesReceived"),
                    audioSent = sum("outbound-rtp", "audio", "bytesSent"),
                    candidateType = candidateType(pair?.members?.get("localCandidateId")),
                    rttMs = number(pair, "currentRoundTripTime")?.toDouble()?.times(1000)?.toLong() ?: 0,
                    remoteCandidateType = candidateType(pair?.members?.get("remoteCandidateId")),
                    videoWidth = number(video, "frameWidth")?.toInt() ?: 0,
                    videoHeight = number(video, "frameHeight")?.toInt() ?: 0,
                    videoFps = number(video, "framesPerSecond")?.toInt() ?: 0,
                    jitterMs = number(audio ?: video, "jitter")?.toDouble()?.times(1000)?.toLong() ?: 0,
                    packetsLost = sum("inbound-rtp", null, "packetsLost"),
                    receiveKbps = kbps(received, lastBytesReceived),
                    sendKbps = kbps(sent, lastBytesSent),
                )
                lastBytesReceived = received; lastBytesSent = sent; lastSampleNanos = now
                // Stats are polled every second; only a change is worth a line. This is the one
                // signal that distinguishes a P2P pair from a TURN relay.
                val route = "${result.candidateType}/${result.remoteCandidateType}"
                if (route != lastCandidate) {
                    lastCandidate = route
                    logger.info(AppEvent.RTC_SELECTED_CANDIDATE, route)
                }
                if (continuation.isActive) continuation.resume(result)
            }
        }
    } }

    override suspend fun setTrackEnabled(track: MediaTrack, enabled: Boolean) = withContext(dispatcher) {
        if (!released) when (track) {
            MediaTrack.MICROPHONE -> audioTrack?.setEnabled(enabled)
            MediaTrack.FRONT_CAMERA, MediaTrack.BACK_CAMERA -> videoTrack?.setEnabled(enabled)
            MediaTrack.SCREEN -> throw UnsupportedOperationException("screen_not_available")
        }
        Unit
    }
    override suspend fun restartIce(): Unit = throw UnsupportedOperationException("initial_negotiation_only")

    @Suppress("DEPRECATION")
    private fun acquireAudio() {
        previousMode = audioManager.mode
        previousSpeaker = audioManager.isSpeakerphoneOn
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { if (it < 0) fail() }.build()
        focus = request
        if (audioManager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) throw IOException("audio_focus_denied")
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= 31) {
            val speaker = audioManager.availableCommunicationDevices.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (speaker != null && !audioManager.setCommunicationDevice(speaker)) throw IOException("audio_route_failed")
        } else audioManager.isSpeakerphoneOn = true
    }

    @Suppress("DEPRECATION")
    override suspend fun release() {
        withContext(dispatcher) {
            if (released) return@withContext
            released = true
            withContext(Dispatchers.Main.immediate) {
                localFeed?.close(); remoteFeed?.close()
            }
            // Attempt every release even if an OEM operation fails; log only an allowlisted event.
            fun cleanup(block: () -> Unit) { try { block() } catch (error: Exception) { logger.error(AppEvent.RTC_RELEASE_FAILED) } }
            cleanup { NetworkMonitor.removeNetworkObserver(networkObserver) }
            cleanup { camera?.stopCapture() }
            cleanup { remoteTrack?.removeSink(remoteFeed) }
            cleanup { videoTrack?.removeSink(localFeed) }
            cleanup { peer?.close() }
            cleanup { peer?.dispose() }; peer = null
            cleanup { camera?.dispose() }; camera = null
            cleanup { videoTrack?.dispose() }; videoTrack = null
            cleanup { audioTrack?.dispose() }; audioTrack = null
            cleanup { videoSource?.dispose() }; videoSource = null
            cleanup { audioSource?.dispose() }; audioSource = null
            cleanup { texture?.dispose() }; texture = null
            cleanup { factory?.dispose() }; factory = null
            cleanup { audioModule?.release() }; audioModule = null
            cleanup { egl?.release() }; egl = null
            withContext(Dispatchers.Main.immediate) {
                if (focus != null) {
                    cleanup { if (Build.VERSION.SDK_INT >= 31) audioManager.clearCommunicationDevice() else audioManager.isSpeakerphoneOn = previousSpeaker }
                    cleanup { audioManager.mode = previousMode }
                    cleanup { audioManager.abandonAudioFocusRequest(requireNotNull(focus)) }; focus = null
                }
            }
            iceState.value = IceState.CLOSED
        }
        scope.cancel(); dispatcher.close()
    }

    private fun fail() { scope.launch { if (!released) { logger.error(AppEvent.RTC_MEDIA_FAILED); iceState.value = IceState.FAILED } } }
    private val observer = object : PeerConnection.Observer {
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            logger.info(AppEvent.RTC_ICE_STATE, state.name)
            // Setup time is the headline M1 number, so report the first connect only; later
            // reconnects are visible as their own state transitions.
            val connected = state == PeerConnection.IceConnectionState.CONNECTED ||
                state == PeerConnection.IceConnectionState.COMPLETED
            if (connected && !setupReported && startedNanos != 0L) {
                setupReported = true
                logger.info(AppEvent.RTC_SETUP_MS, ((System.nanoTime() - startedNanos) / 1_000_000).toString())
            }
            scope.launch { if (!released) iceState.value = when (state) {
                PeerConnection.IceConnectionState.NEW -> IceState.NEW
                PeerConnection.IceConnectionState.CHECKING -> IceState.CHECKING
                PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> IceState.CONNECTED
                PeerConnection.IceConnectionState.DISCONNECTED -> IceState.DISCONNECTED
                PeerConnection.IceConnectionState.FAILED -> IceState.FAILED
                PeerConnection.IceConnectionState.CLOSED -> IceState.CLOSED
            } }
        }
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) { if (state == PeerConnection.IceGatheringState.COMPLETE) gathered.complete(Unit) }
        override fun onTrack(transceiver: RtpTransceiver) {
            val track = transceiver.receiver.track()
            if (track is VideoTrack) scope.launch { if (!released) { remoteTrack?.removeSink(remoteFeed); remoteTrack = track; track.addSink(remoteFeed) } }
        }
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceCandidate(candidate: IceCandidate) = Unit // Included in gathered local SDP.
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) { channel.close(); channel.dispose() }
        override fun onRenegotiationNeeded() = Unit // Initial negotiation is explicitly driven by caller role.
    }

    private open class DescriptionObserver : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String) = Unit
        override fun onSetFailure(error: String) = Unit
    }
    companion object {
        private const val GATHER_ATTEMPTS = 3
        private const val GATHER_BUDGET_MS = 20_000L
        private const val NETWORK_WAIT_MS = 2_000L
        @Volatile private var networksSeen = false
        private const val GATHER_RETRY_MS = 250L
        private var initialized = false
        @Synchronized private fun initialize(context: Context, logger: AppLogger) {
            if (initialized) return
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .setInjectableLogger({ message, severity, _ ->
                    if (severity == Logging.Severity.LS_ERROR) logger.error(when {
                        message.contains("bind", ignoreCase = true) -> AppEvent.RTC_BIND_FAILED
                        message.contains("socket", ignoreCase = true) -> AppEvent.RTC_SOCKET_FAILED
                        message.contains("codec", ignoreCase = true) -> AppEvent.RTC_CODEC_FAILED
                        else -> AppEvent.RTC_NATIVE_ERROR
                    })
                }, Logging.Severity.LS_ERROR)
                .createInitializationOptions())
            initialized = true
        }
    }
}
