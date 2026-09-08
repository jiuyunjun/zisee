package com.zisee.app.rtc

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import com.zisee.app.call.IceServerConfig
import com.zisee.app.core.logging.AppEvent
import com.zisee.app.core.logging.AppLogger
import com.zisee.app.media.MediaTrack
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule

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
    private val candidates = mutableListOf<IceCandidate>()
    private var candidateOverflow = false
    private val cellularStandby = CellularStandby(context, logger)
    private var acceptingCandidates = true
    private var localUfrags = emptySet<String>()
    private var configuration: PeerConnection.RTCConfiguration? = null
    private val networksReady = CompletableDeferred<Unit>()
    private val networkObserver = NetworkMonitor.NetworkObserver { type ->
        if (type != NetworkChangeDetector.ConnectionType.CONNECTION_NONE) {
            networksSeen = true; networksReady.complete(Unit)
        }
    }
    private var released = false
    private var lastCandidate: String? = null
    private var monitoring = false
    private val sampledStats = MutableStateFlow(MediaStats())
    val mediaStats = sampledStats.asStateFlow()
    private val sampler = MediaStatsSampler()
    private var statsJob: Job? = null
    private var videoSender: RtpSender? = null
    private var qualityPolicy = VideoQualityPolicy(false)
    private var appliedQuality: VideoQuality? = null
    private var adaptationEnabled = true
    private val powerManager = context.getSystemService(PowerManager::class.java)
    @Volatile private var startedNanos = 0L
    private var setupReported = false
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var previousMode = AudioManager.MODE_NORMAL
    private var previousSpeaker = false
    private var focus: AudioFocusRequest? = null

    suspend fun start(iceServers: List<IceServerConfig>) = withContext(dispatcher) {
        initialize(context, logger)
        cellularStandby.start()
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
        // The Android network monitor is the fast path for noticing a network change, but on some
        // platforms it reports no interface at all and ICE then gathers zero candidates. Start it
        // and probe it before the factory exists: a monitor that never reports falls back to
        // libwebrtc enumerating interfaces itself, which is slower to see a change but always
        // finds the interfaces that are already there.
        NetworkMonitor.addNetworkObserver(networkObserver)
        NetworkMonitor.getInstance().startMonitoring(context, MONITOR_TAG)
        monitoring = true
        // isOnline() reports the detector's current view synchronously. The observer only fires on
        // a later change, so waiting for it alone mistakes an already stable connection for a
        // monitor that does not work.
        val monitored = networksSeen || NetworkMonitor.isOnline() ||
            withTimeoutOrNull(NETWORK_WAIT_MS) { networksReady.await() } != null
        if (!monitored) logger.info(AppEvent.RTC_ICE_STATE, "network_monitor_unavailable")
        val options = PeerConnectionFactory.Options().apply { disableNetworkMonitor = !monitored }
        factory = PeerConnectionFactory.builder().setAudioDeviceModule(audioModule)
            .setOptions(options)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(shared, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(shared)).createPeerConnectionFactory()
        val servers = iceServers.map { entry ->
            PeerConnection.IceServer.builder(entry.urls)
                .setUsername(entry.username).setPassword(entry.credential).createIceServer()
        }
        val config = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            candidateNetworkPolicy = PeerConnection.CandidateNetworkPolicy.ALL
            iceBackupCandidatePairPingInterval = 1_000
        }
        configuration = config
        peer = requireNotNull(factory).createPeerConnection(config, observer) ?: throw IOException("peer_creation_failed")
        val enumerator: CameraEnumerator = if (Camera2Enumerator.isSupported(context)) Camera2Enumerator(context) else Camera1Enumerator(true)
        val name = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?: enumerator.deviceNames.firstOrNull() ?: throw IOException("camera_unavailable")
        val supportsFullHd = try {
            enumerator.getSupportedFormats(name)?.any {
                it.width == 1920 && it.height == 1080 && it.framerate.max >= 30_000
            } == true
        } catch (error: RuntimeException) {
            logger.error(AppEvent.RTC_CAPABILITY_UNAVAILABLE)
            false
        }
        qualityPolicy = VideoQualityPolicy(supportsFullHd, preferFullHd = true)
        localFeed = VideoFeed(shared, enumerator.isFrontFacing(name))
        remoteFeed = VideoFeed(shared, false) {
            logger.info(AppEvent.RTC_FIRST_FRAME_MS, ((System.nanoTime() - startedNanos) / 1_000_000).toString())
        }
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
            it.addSink(localFeed); videoSender = requireNotNull(peer).addTrack(it, listOf("zisee"))
        }
        audioSource = requireNotNull(factory).createAudioSource(MediaConstraints())
        audioTrack = requireNotNull(factory).createAudioTrack(MediaTrack.MICROPHONE.wireId, audioSource).also {
            requireNotNull(peer).addTrack(it, listOf("zisee"))
        }
        withContext(Dispatchers.Main.immediate) { acquireAudio() }
        // Open the supported quality ceiling immediately; native congestion control adapts output.
        val initialQuality = qualityPolicy.current.quality
        requireNotNull(camera).startCapture(initialQuality.width, initialQuality.height, initialQuality.fps)
        startedNanos = System.nanoTime()
        applyQuality(qualityPolicy.current, changeCapture = false)
        statsJob = scope.launch {
            var missing = false
            while (isActive && !released) {
                delay(1_000)
                try {
                    val result = stats()
                    val thermal = if (Build.VERSION.SDK_INT >= 29) powerManager.currentThermalStatus else null
                    val observed = result.copy(thermalStatus = thermal)
                    // Never use idle/muted encoder statistics to make quality decisions.
                    if (iceState.value == IceState.CONNECTED && videoTrack?.enabled() == true && adaptationEnabled) {
                        applyQuality(qualityPolicy.update(observed, System.nanoTime() / 1_000_000))
                    }
                    sampledStats.value = observed.copy(quality = appliedQuality ?: VideoQuality.HD)
                    missing = false
                } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                    sampledStats.value = sampledStats.value.copy(sampleAvailable = false)
                    if (!missing) logger.error(AppEvent.RTC_STATS_UNAVAILABLE)
                    missing = true
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    sampledStats.value = sampledStats.value.copy(sampleAvailable = false)
                    if (!missing) logger.error(AppEvent.RTC_STATS_UNAVAILABLE)
                    missing = true
                }
            }
        }
        Unit
    }

    suspend fun localDescription(offer: Boolean): SessionDescription = withContext(dispatcher) {
        val pc = requireNotNull(peer)
        val description = withTimeout(10_000) { suspendCancellableCoroutine { continuation ->
            val observer = object : DescriptionObserver() {
                override fun onCreateSuccess(sdp: SessionDescription) { if (continuation.isActive) continuation.resume(sdp) }
                override fun onCreateFailure(error: String) { if (continuation.isActive) continuation.resumeWithException(IOException("sdp_create_failed")) }
            }
            if (offer) pc.createOffer(observer, MediaConstraints()) else pc.createAnswer(observer, MediaConstraints())
        } }
        localUfrags = description.description.lineSequence().filter { it.startsWith("a=ice-ufrag:") }.map { it.substringAfter(":").trim() }.toSet()
        acceptingCandidates = true
        setDescription(description, local = true)
        // Send immediately. Candidates are delivered independently, including slow TURN results.
        description
    }

    suspend fun localCandidates(): List<IceCandidate> = withContext(dispatcher) { candidates.toList() }
    suspend fun candidateCapacityExceeded(): Boolean = withContext(dispatcher) { candidateOverflow }

    suspend fun addRemoteCandidate(candidate: IceCandidate) = withContext(dispatcher) {
        if (!requireNotNull(peer).addIceCandidate(candidate)) throw IOException("ice_candidate_rejected")
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

    private suspend fun stats(): MediaStats = withTimeout(1_500) {
        val report = suspendCancellableCoroutine<RTCStatsReport> { continuation ->
            requireNotNull(peer).getStats { value -> if (continuation.isActive) continuation.resume(value) }
        }
        // Native callback only delivers a report. Mutable sampling state stays on the RTC executor.
        val result = sampler.sample(report.statsMap.values.map { StatsEntry(it.id, it.type, it.members) },
            System.nanoTime() / 1_000_000)
        val route = "${result.candidateType}/${result.remoteCandidateType}"
        if (route != lastCandidate) { lastCandidate = route; logger.info(AppEvent.RTC_SELECTED_CANDIDATE, route) }
        return@withTimeout result
    }

    private fun applyQuality(decision: QualityDecision, changeCapture: Boolean = true) {
        if (appliedQuality == decision.quality || !adaptationEnabled) return
        val sender = videoSender ?: return
        try {
            val parameters = sender.parameters
            if (parameters.encodings.isEmpty()) return // Retry after negotiation produces encodings.
            parameters.degradationPreference = RtpParameters.DegradationPreference.BALANCED
            parameters.encodings.forEach {
                it.maxBitrateBps = decision.quality.maxBitrateBps
                it.maxFramerate = decision.quality.fps
                it.minBitrateBps = null // Do not force a bitrate floor when audio needs the link.
            }
            if (!sender.setParameters(parameters)) {
                adaptationEnabled = false
                logger.error(AppEvent.RTC_QUALITY_REJECTED)
                return
            }
            if (changeCapture) requireNotNull(camera).changeCaptureFormat(
                decision.quality.width, decision.quality.height, decision.quality.fps)
            appliedQuality = decision.quality
            logger.info(AppEvent.RTC_QUALITY_CHANGED, "${decision.quality.name}/${decision.reason.name}")
        } catch (error: Exception) {
            // Native defaults remain usable if an OEM rejects parameter changes.
            adaptationEnabled = false
            logger.error(AppEvent.RTC_QUALITY_REJECTED)
        }
    }

    override suspend fun setTrackEnabled(track: MediaTrack, enabled: Boolean) = withContext(dispatcher) {
        if (!released) when (track) {
            MediaTrack.MICROPHONE -> audioTrack?.setEnabled(enabled)
            MediaTrack.FRONT_CAMERA, MediaTrack.BACK_CAMERA -> videoTrack?.setEnabled(enabled)
            MediaTrack.SCREEN -> throw UnsupportedOperationException("screen_not_available")
        }
        Unit
    }
    suspend fun prepareIceGeneration(iceServers: List<IceServerConfig>) = withContext(dispatcher) {
        acceptingCandidates = false
        candidates.clear()
        candidateOverflow = false
        val config = requireNotNull(configuration)
        config.iceServers = iceServers.map { PeerConnection.IceServer.builder(it.urls)
            .setUsername(it.username).setPassword(it.credential).createIceServer() }
        check(requireNotNull(peer).setConfiguration(config))
        // Roll back an unanswered old offer before the caller creates a new generation.
        if (requireNotNull(peer).signalingState() == PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
            setDescription(SessionDescription(SessionDescription.Type.ROLLBACK, ""), local = true)
        }
    }
    override suspend fun restartIce() = withContext(dispatcher) {
        requireNotNull(peer).restartIce()
        logger.info(AppEvent.RTC_ICE_RESTART)
    }

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
            statsJob?.cancel(); statsJob = null
            withContext(Dispatchers.Main.immediate) {
                localFeed?.close(); remoteFeed?.close()
            }
            // Attempt every release even if an OEM operation fails; log only an allowlisted event.
            fun cleanup(block: () -> Unit) { try { block() } catch (error: Exception) { logger.error(AppEvent.RTC_RELEASE_FAILED) } }
            cleanup { cellularStandby.close() }
            cleanup { if (monitoring) { NetworkMonitor.getInstance().stopMonitoring(); monitoring = false } }
            cleanup { NetworkMonitor.removeNetworkObserver(networkObserver) }
            cleanup { camera?.stopCapture() }
            cleanup { remoteTrack?.removeSink(remoteFeed) }
            cleanup { videoTrack?.removeSink(localFeed) }
            cleanup { peer?.close() }
            cleanup { peer?.dispose() }; peer = null; videoSender = null
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
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onTrack(transceiver: RtpTransceiver) {
            val track = transceiver.receiver.track()
            if (track is VideoTrack) scope.launch { if (!released) { remoteTrack?.removeSink(remoteFeed); remoteTrack = track; track.addSink(remoteFeed) } }
        }
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceCandidate(candidate: IceCandidate) { scope.launch {
            val ufrag = Regex("(?:^| )ufrag ([^ ]+)").find(candidate.sdp)?.groupValues?.get(1)
            if (!released && acceptingCandidates && (ufrag == null || ufrag in localUfrags) && candidates.none { it.sdp == candidate.sdp && it.sdpMid == candidate.sdpMid }) {
                // The current protocol is append-only and bounded. Rotate generation instead of
                // crashing or rewriting an acknowledged prefix when networks keep appearing.
                if (candidates.size >= 32) candidateOverflow = true else candidates.add(candidate)
            }
        } }
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
        private const val MONITOR_TAG = "Zisee"
        private const val NETWORK_WAIT_MS = 2_000L
        @Volatile private var networksSeen = false
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
