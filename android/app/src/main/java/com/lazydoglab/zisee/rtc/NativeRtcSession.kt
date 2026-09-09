package com.lazydoglab.zisee.rtc

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.lazydoglab.zisee.BuildConfig
import com.lazydoglab.zisee.call.IceServerConfig
import com.lazydoglab.zisee.core.logging.AppEvent
import com.lazydoglab.zisee.core.logging.AppLogger
import com.lazydoglab.zisee.media.MediaTrack
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
    private var audioSender: RtpSender? = null
    private var noiseMode = com.lazydoglab.zisee.rtc.audio.processing.NoiseSuppressionMode.AUTO
    private val audioProcessing = com.lazydoglab.zisee.rtc.audio.processing.AudioProcessingEngine(context) {
        scope.launch { if (!released) applyNoiseSuppression() }
    }
    private var ownsAudioProcessing = false
    private val audioMemory = object : android.content.ComponentCallbacks2 {
        override fun onConfigurationChanged(config: android.content.res.Configuration) = Unit
        override fun onLowMemory() { audioProcessing.onMemoryPressure() }
        override fun onTrimMemory(level: Int) {
            if (level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
                level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
                level >= android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE) audioProcessing.onMemoryPressure()
        }
    }
    private var remoteTrack: VideoTrack? = null
    private var remoteBackTrack: VideoTrack? = null
    private var backSource: VideoSource? = null
    private var backTrack: VideoTrack? = null
    private var backSender: RtpSender? = null
    var localBackFeed: VideoFeed? = null; private set
    var remoteBackFeed: VideoFeed? = null; private set
    val showMe = MutableStateFlow(ShowMeState())
    val remotePresentation = MutableStateFlow(CameraPresentation(CameraMode.FACE, true))
    private var presentationMode = CameraMode.FACE
    private var cameraEnabled = true
    @Volatile private var frontName: String? = null
    /** A device with no front camera can never leave the rear-only presentation. */
    val hasFrontCamera: Boolean get() = frontName != null
    private var backName: String? = null
    private var frontSupportsFullHd = false
    private var frontSupports60 = false
    private var dualCapture: DualCameraCapture? = null
    @Volatile private var cameraClosed: CompletableDeferred<Unit>? = null
    private val deviceOrientation = DeviceOrientation(context) { rotation ->
        // Single capture already uses display rotation; concurrent capture follows the same display.
        scope.launch { if (!released) dualCapture?.setTargetRotation(rotation) }
    }
    private var control: DataChannel? = null
    private var changingCamera = false
    var localFeed: VideoFeed? = null; private set
    var remoteFeed: VideoFeed? = null; private set
    private val candidates = mutableListOf<IceCandidate>()
    private var candidateOverflow = false
    private val cellularStandby = CellularStandby(context, logger)
    private var acceptingCandidates = true
    private var localUfrags = emptySet<String>()
    private var configuration: PeerConnection.RTCConfiguration? = null
    private val candidateRevision = MutableStateFlow(0L)
    val localCandidateRevision = candidateRevision.asStateFlow()
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
    private val recoveryConfig = WebRtcRecoveryConfig()
    private val statsWake = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private var handoverStatsUntilMs = 0L
    private var lastSelectedPairId: String? = null
    private var videoSender: RtpSender? = null
    private var qualityPolicy = VideoQualityPolicy(false)
    private var appliedQuality: VideoQuality? = null
    /** How the peer says it is showing this device's cameras, and what was last encoded for it. */
    private var remoteView = ViewRequest.Default
    private var appliedView: ViewRequest? = null
    private var sentView: ViewRequest? = null
    private var desiredView = ViewRequest.Default
    private var adaptationEnabled = true
    private val audioBandwidth = com.lazydoglab.zisee.rtc.audio.AudioBandwidthPolicy()
    private val videoSending = mutableMapOf<RtpSender, Boolean>()
    private val powerManager = context.getSystemService(PowerManager::class.java)
    @Volatile private var startedNanos = 0L
    private var setupReported = false
    private val callAudio = com.lazydoglab.zisee.rtc.audio.CallAudioManager(context, logger) { interrupted ->
        scope.launch {
            if (!released) {
                audioModule?.setMicrophoneMute(interrupted)
                audioModule?.setSpeakerMute(interrupted)
            }
        }
    }
    val audioDeviceState = callAudio.state

    suspend fun start(iceServers: List<IceServerConfig>) = withContext(dispatcher) {
        initialize(context, logger)
        audioProcessing.prepare()
        val processingFactory = com.lazydoglab.zisee.rtc.audio.processing.SharedAudioProcessing.acquire(audioProcessing)
        ownsAudioProcessing = true
        context.registerComponentCallbacks(audioMemory)
        cellularStandby.start()
        egl = EglBase.create()
        val shared = requireNotNull(egl).eglBaseContext
        audioModule = JavaAudioDeviceModule.builder(context).setEnableVolumeLogger(false)
            .setInputSampleRate(48_000).setUseStereoInput(false).setUseStereoOutput(false)
            .setUseHardwareAcousticEchoCanceler(false).setUseHardwareNoiseSuppressor(false)
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
        val options = PeerConnectionFactory.Options().apply {
            disableNetworkMonitor = !monitored
            // A loopback candidate can never reach the peer, but gathering one still creates ports
            // that send STUN and TURN to public addresses and time out, and it consumes slots in
            // the bounded candidate list. Left in, it delayed the first frame by twenty seconds.
            // ADAPTER_TYPE_LOOPBACK is package private in the SDK; the value tracks the native one.
            networkIgnoreMask = LOOPBACK_ADAPTER
        }
        factory = PeerConnectionFactory.builder().setAudioDeviceModule(audioModule)
            .setAudioProcessingFactory(processingFactory)
            .setOptions(options)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(shared, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(shared)).createPeerConnectionFactory()
        val servers = iceServers.map { entry ->
            PeerConnection.IceServer.builder(entry.urls)
                .setUsername(entry.username).setPassword(entry.credential).createIceServer()
        }
        val config = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            // One transport for every m-section. The default balanced policy gathers per transport
            // until the answer confirms bundling, so each network candidate is reported once per
            // m-section. Show Me took the call from two m-sections to four (front video, rear
            // video, control channel, audio), which doubled that fan-out into the bounded candidate
            // list and could exhaust it on a phone with several interfaces and a relay.
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            candidateNetworkPolicy = PeerConnection.CandidateNetworkPolicy.ALL
            iceBackupCandidatePairPingInterval = recoveryConfig.backupPingIntervalMs
            iceConnectionReceivingTimeout = recoveryConfig.receivingTimeoutMs
            stableWritableConnectionPingIntervalMs = recoveryConfig.stablePingIntervalMs
            iceUnwritableTimeMs = recoveryConfig.unwritableTimeoutMs
            iceUnwritableMinChecks = recoveryConfig.unwritableMinChecks
        }
        configuration = config
        peer = requireNotNull(factory).createPeerConnection(config, observer) ?: throw IOException("peer_creation_failed")
        val enumerator: CameraEnumerator = if (Camera2Enumerator.isSupported(context)) Camera2Enumerator(context) else Camera1Enumerator(true)
        val name = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?: enumerator.deviceNames.firstOrNull() ?: throw IOException("camera_unavailable")
        fun capturesFullHdAt(fps: Int) = try {
            enumerator.getSupportedFormats(name)?.any {
                it.width == 1920 && it.height == 1080 && it.framerate.max >= fps * 1_000
            } == true
        } catch (error: RuntimeException) {
            logger.error(AppEvent.RTC_CAPABILITY_UNAVAILABLE)
            false
        }
        val supportsFullHd = capturesFullHdAt(30)
        val supports60 = supportsFullHd && capturesFullHdAt(60)
        frontSupportsFullHd = supportsFullHd
        frontSupports60 = supports60
        frontName = name.takeIf { enumerator.isFrontFacing(it) }
        backName = enumerator.deviceNames.firstOrNull { enumerator.isBackFacing(it) }
        if (frontName == null) {
            presentationMode = CameraMode.BACK_ONLY
            showMe.value = ShowMeState(CameraMode.BACK_ONLY, "此设备仅有后摄")
        }
        qualityPolicy = VideoQualityPolicy(supportsFullHd, preferFullHd = true, supports60 = supports60)
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
            override fun onCameraClosed() { cameraClosed?.complete(Unit) }
        }) ?: throw IOException("camera_unavailable")
        texture = SurfaceTextureHelper.create("ZiseeCapture", shared)
        videoSource = requireNotNull(factory).createVideoSource(false)
        deviceOrientation.start()
        requireNotNull(camera).initialize(texture, context, requireNotNull(videoSource).capturerObserver)
        val cameraId = MediaTrack.FRONT_CAMERA // Primary single-camera slot, including back-only devices.
        videoTrack = requireNotNull(factory).createVideoTrack(cameraId.wireId, videoSource).also {
            it.addSink(localFeed); videoSender = requireNotNull(peer).addTrack(it, listOf("zisee"))
        }
        backSource = requireNotNull(factory).createVideoSource(false)
        localBackFeed = VideoFeed(shared, false)
        remoteBackFeed = VideoFeed(shared, false)
        backTrack = requireNotNull(factory).createVideoTrack(MediaTrack.BACK_CAMERA.wireId, backSource).also {
            it.setEnabled(false); it.addSink(localBackFeed)
            backSender = requireNotNull(peer).addTrack(it, listOf("zisee"))
        }
        control = requireNotNull(peer).createDataChannel("camera-state", DataChannel.Init().apply { negotiated = true; id = 0 })
        control?.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit
            override fun onStateChange() { scope.launch {
                if (!released) { sendPresentation(); sendViewLayout() }
            } }
            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.binary || buffer.data.remaining() > 32) return
                val bytes = ByteArray(buffer.data.remaining()); buffer.data.get(bytes)
                val text = bytes.toString(Charsets.UTF_8)
                CameraPresentation.decode(text)?.let { value ->
                    scope.launch { if (!released) remotePresentation.value = value }
                    return
                }
                ViewRequest.decode(text)?.let { value ->
                    scope.launch {
                        if (!released && remoteView != value) {
                            remoteView = value
                            applyQuality(qualityPolicy.current, changeCapture = false)
                        }
                    }
                }
            }
        })
        audioSource = requireNotNull(factory).createAudioSource(MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
        })
        audioTrack = requireNotNull(factory).createAudioTrack(MediaTrack.MICROPHONE.wireId, audioSource).also {
            audioSender = requireNotNull(peer).addTrack(it, listOf("zisee"))
        }
        applyNoiseSuppression()
        withContext(Dispatchers.Main.immediate) { callAudio.start() }
        // Open the supported quality ceiling immediately; native congestion control adapts output.
        val initialQuality = qualityPolicy.current.quality
        requireNotNull(camera).startCapture(initialQuality.width, initialQuality.height, initialQuality.fps)
        startedNanos = System.nanoTime()
        applyQuality(qualityPolicy.current, changeCapture = false)
        statsJob = scope.launch {
            var missing = false
            while (isActive && !released) {
                val interval = if (System.nanoTime() / 1_000_000 < handoverStatsUntilMs)
                    recoveryConfig.handoverStatsIntervalMs else 1_000L
                withTimeoutOrNull(interval) { statsWake.receive() }
                try {
                    val thermal = if (Build.VERSION.SDK_INT >= 29) powerManager.currentThermalStatus else null
                    // Thermal protection must keep running even when RTCStats is unavailable.
                    if ((thermal ?: 0) >= 3 && !changingCamera)
                        applyQuality(QualityDecision(VideoQuality.ECONOMY, QualityReason.THERMAL))
                    audioProcessing.setThermal(thermal ?: 0)
                    val result = stats()
                    val observed = result.copy(thermalStatus = thermal)
                    // Never use idle/muted encoder statistics to make quality decisions.
                    if (iceState.value == IceState.CONNECTED && videoTrack?.enabled() == true && adaptationEnabled && !changingCamera) {
                        applyQuality(qualityPolicy.update(observed, System.nanoTime() / 1_000_000))
                    }
                    if (iceState.value == IceState.CONNECTED && !changingCamera) applyAudioBandwidth(observed)
                    sampledStats.value = observed.copy(quality = appliedQuality ?: VideoQuality.HD,
                        audioProcessing = audioProcessing.stats(), audioDevice = callAudio.state.value,
                        audioBandwidth = audioBandwidth.mode)
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
        val created = withTimeout(10_000) { suspendCancellableCoroutine { continuation ->
            val observer = object : DescriptionObserver() {
                override fun onCreateSuccess(sdp: SessionDescription) { if (continuation.isActive) continuation.resume(sdp) }
                override fun onCreateFailure(error: String) { if (continuation.isActive) continuation.resumeWithException(IOException("sdp_create_failed")) }
            }
            if (offer) pc.createOffer(observer, MediaConstraints()) else pc.createAnswer(observer, MediaConstraints())
        } }
        val description = SessionDescription(created.type, com.lazydoglab.zisee.rtc.audio.OpusPolicy.apply(created.description))
        localUfrags = description.description.lineSequence().filter { it.startsWith("a=ice-ufrag:") }.map { it.substringAfter(":").trim() }.toSet()
        acceptingCandidates = true
        setDescription(description, local = true)
        configureAudioSender()
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
        configureAudioSender()
    }

    private fun configureAudioSender() {
        val sender = audioSender ?: return
        val parameters = sender.parameters
        if (parameters.encodings.isEmpty()) return
        parameters.encodings.forEach { it.maxBitrateBps = 32_000; it.minBitrateBps = null; it.bitratePriority = 4.0 }
        if (!sender.setParameters(parameters)) logger.error(AppEvent.RTC_QUALITY_REJECTED)
    }

    private fun applyAudioBandwidth(stats: MediaStats) {
        videoSending.keys.retainAll(listOfNotNull(videoSender, backSender).toSet())
        val mode = audioBandwidth.update(stats.availableOutgoingKbps, stats.audio.outboundLoss, stats.sampledAtMs)
        val frontMain = dualCapture == null || (remoteView.front == ViewSize.LARGE && remoteView.back == ViewSize.SMALL)
        for ((sender, primary) in listOf(videoSender to frontMain, backSender to !frontMain)) {
            if (sender == null) continue
            val enabled = mode == com.lazydoglab.zisee.rtc.audio.AudioBandwidthMode.ALL_VIDEO ||
                (mode == com.lazydoglab.zisee.rtc.audio.AudioBandwidthMode.PRIMARY_ONLY && primary)
            if (videoSending[sender] == enabled) continue
            val parameters = sender.parameters
            if (parameters.encodings.isEmpty()) continue
            parameters.encodings.forEach { it.active = enabled }
            if (sender.setParameters(parameters)) videoSending[sender] = enabled
            else logger.error(AppEvent.RTC_QUALITY_REJECTED)
        }
    }

    suspend fun setNoiseSuppression(mode: com.lazydoglab.zisee.rtc.audio.processing.NoiseSuppressionMode) = withContext(dispatcher) {
        if (!released) { noiseMode = mode; applyNoiseSuppression() }
    }

    private fun applyNoiseSuppression() {
        val track = audioTrack ?: return
        if (!audioProcessing.configure(track, noiseMode)) logger.error(AppEvent.RTC_MEDIA_FAILED)
        val info = audioProcessing.stats()
        sampledStats.value = sampledStats.value.copy(audioProcessing = info)
        logger.info(AppEvent.RTC_AUDIO_PROCESSING, "${info.mode} ${info.state} ${info.fallback}")
    }

    /**
     * "sdp_set_failed" alone cannot say which of the five set sites failed, which left a real
     * device failure undiagnosable. The call site and the signaling state going in are both
     * bounded identifiers, so they can be recorded without admitting SDP into the log.
     */
    private suspend fun setDescription(sdp: SessionDescription, local: Boolean) = withTimeout(10_000) {
        val code = when {
            sdp.type == SessionDescription.Type.ROLLBACK -> "sdp_rollback_failed"
            local && sdp.type == SessionDescription.Type.OFFER -> "sdp_set_local_offer_failed"
            local -> "sdp_set_local_answer_failed"
            sdp.type == SessionDescription.Type.OFFER -> "sdp_set_remote_offer_failed"
            else -> "sdp_set_remote_answer_failed"
        }
        // Read the state here, on the RTC executor, rather than from the native callback thread.
        val before = requireNotNull(peer).signalingState().name
        suspendCancellableCoroutine<Unit> { continuation ->
            val observer = object : DescriptionObserver() {
                override fun onSetSuccess() { if (continuation.isActive) continuation.resume(Unit) }
                override fun onSetFailure(error: String) {
                    logger.info(AppEvent.RTC_SDP_FAILED, "$code from=$before")
                    if (continuation.isActive) continuation.resumeWithException(IOException(code))
                }
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
            System.nanoTime() / 1_000_000, localBack = dualCapture != null,
            remoteTrackId = (if (remotePresentation.value.mode == CameraMode.DUAL) remoteBackTrack else remoteTrack)?.id())
        val route = "${result.candidateType}/${result.remoteCandidateType} ${result.networkType}/${result.protocol}"
        if (route != lastCandidate || result.selectedPairId != lastSelectedPairId) {
            lastCandidate = route; lastSelectedPairId = result.selectedPairId
            // Log pair transitions even when both paths have the same candidate types. Never log IPs or SDP.
            logger.info(AppEvent.RTC_SELECTED_CANDIDATE, "$route atMs=${result.sampledAtMs}")
        }
        return@withTimeout result
    }

    /**
     * The main view gets the policy ceiling; anything the viewer shows as a thumbnail is encoded at
     * this size instead. Sending a full-size stream into a 104dp window is wasted uplink.
     */
    private fun thumbnail(sender: RtpSender?, source: VideoSource?) {
        source?.adaptOutputFormat(640, 360, 15)
        val parameters = sender?.parameters ?: return
        parameters.encodings.forEach { it.maxBitrateBps = 450_000; it.maxFramerate = 15; it.minBitrateBps = null }
        if (!sender.setParameters(parameters)) logger.error(AppEvent.RTC_QUALITY_REJECTED)
    }

    private fun applyQuality(decision: QualityDecision, changeCapture: Boolean = true) {
        if (appliedQuality == decision.quality && appliedView == remoteView) return
        if (!adaptationEnabled) return
        // With both cameras live the viewer chooses which one is its main view, and may swap at any
        // time. Until it says otherwise the rear camera is the scene the mode exists to show.
        val frontIsMain = dualCapture != null &&
            remoteView.front == ViewSize.LARGE && remoteView.back == ViewSize.SMALL
        val sender = (if (dualCapture == null || frontIsMain) videoSender else backSender) ?: return
        val source = if (dualCapture == null || frontIsMain) videoSource else backSource
        try {
            val parameters = sender.parameters
            if (parameters.encodings.isEmpty()) return // Retry after negotiation produces encodings.
            parameters.degradationPreference = RtpParameters.DegradationPreference.BALANCED
            // A single camera the viewer keeps in a thumbnail is not worth a full-size encode either.
            val small = dualCapture == null && remoteView.front == ViewSize.SMALL
            parameters.encodings.forEach {
                it.maxBitrateBps = if (small) 450_000 else decision.quality.maxBitrateBps
                it.maxFramerate = if (small) 15 else decision.quality.fps
                it.minBitrateBps = null // Do not force a bitrate floor when audio needs the link.
            }
            if (!sender.setParameters(parameters)) {
                adaptationEnabled = false
                logger.error(AppEvent.RTC_QUALITY_REJECTED)
                return
            }
            if (dualCapture != null) {
                source?.adaptOutputFormat(decision.quality.width, decision.quality.height, decision.quality.fps)
                thumbnail(if (frontIsMain) backSender else videoSender, if (frontIsMain) backSource else videoSource)
            } else if (small) {
                // Keep the capture format so restoring the main view does not restart the camera.
                videoSource?.adaptOutputFormat(640, 360, 15)
            } else {
                videoSource?.adaptOutputFormat(decision.quality.width, decision.quality.height, decision.quality.fps)
                if (changeCapture) requireNotNull(camera).changeCaptureFormat(
                    decision.quality.width, decision.quality.height, decision.quality.fps)
            }
            appliedQuality = decision.quality
            appliedView = remoteView
            logger.info(AppEvent.RTC_QUALITY_CHANGED, "${decision.quality.name}/${decision.reason.name}/${remoteView.encode()}")
        } catch (error: Exception) {
            // Native defaults remain usable if an OEM rejects parameter changes.
            adaptationEnabled = false
            logger.error(AppEvent.RTC_QUALITY_REJECTED)
        }
    }

    override suspend fun setTrackEnabled(track: MediaTrack, enabled: Boolean) = withContext(dispatcher) {
        if (!released) when (track) {
            MediaTrack.MICROPHONE -> audioTrack?.setEnabled(enabled)
            MediaTrack.FRONT_CAMERA, MediaTrack.BACK_CAMERA -> {
                cameraEnabled = enabled
                videoTrack?.setEnabled(enabled)
                backTrack?.setEnabled(enabled && presentationMode == CameraMode.DUAL)
                sendPresentation()
            }
            MediaTrack.SCREEN -> throw UnsupportedOperationException("screen_not_available")
        }
        Unit
    }
    private fun sendPresentation() {
        val channel = control ?: return
        if (channel.state() != DataChannel.State.OPEN) return
        val data = CameraPresentation(presentationMode, cameraEnabled).encode().toByteArray(Charsets.UTF_8)
        if (!channel.send(DataChannel.Buffer(java.nio.ByteBuffer.wrap(data), false))) logger.error(AppEvent.RTC_MEDIA_FAILED)
    }

    /**
     * Tells the peer how large its cameras are on screen here, so it can stop paying full price for
     * a stream this device is showing in a thumbnail. Only changes are sent.
     */
    suspend fun reportViewLayout(front: ViewSize, back: ViewSize) = withContext(dispatcher) {
        desiredView = ViewRequest(front, back)
        sendViewLayout()
    }

    private fun sendViewLayout() {
        if (released || sentView == desiredView) return
        val channel = control ?: return
        if (channel.state() != DataChannel.State.OPEN) return
        val data = desiredView.encode().toByteArray(Charsets.UTF_8)
        if (channel.send(DataChannel.Buffer(java.nio.ByteBuffer.wrap(data), false))) sentView = desiredView
    }

    suspend fun toggleShowMe(preferDual: Boolean = true) = withContext(dispatcher) {
        if (released || changingCamera || !cameraEnabled) return@withContext
        changingCamera = true
        showMe.value = ShowMeState(CameraMode.STARTING)
        try {
            if (presentationMode != CameraMode.FACE) {
                if (frontName == null) { showMe.value = ShowMeState(presentationMode, "此设备没有可用前摄"); return@withContext }
                dualCapture?.close(); dualCapture = null
                backTrack?.setEnabled(false)
                videoSource?.adaptOutputFormat(1920, 1080, 30)
                if (presentationMode == CameraMode.BACK_ONLY) switchSingle(requireNotNull(frontName))
                else requireNotNull(camera).startCapture(1280, 720, 30)
                localFeed?.setMirrored(true)
                qualityPolicy = VideoQualityPolicy(frontSupportsFullHd, preferFullHd = true, supports60 = frontSupports60)
                presentationMode = CameraMode.FACE
                showMe.value = ShowMeState(CameraMode.FACE)
            } else {
                if (backName == null) { showMe.value = ShowMeState(CameraMode.FACE, "此设备没有可用后摄"); return@withContext }
                val dual = DualCameraCapture(context, requireNotNull(egl).eglBaseContext)
                var stopped = false
                var started = false
                // Falling back to one camera used to leave no trace of which of these it was.
                var fallback = "none"
                try {
                    if (!preferDual) fallback = "requested"
                    else if (control?.state() != DataChannel.State.OPEN) fallback = "control_channel_closed"
                    else if (withTimeoutOrNull(5_000) { dual.supported() } != true) fallback = "concurrent_unsupported"
                    else {
                        releaseCamera(); stopped = true
                        videoSource?.adaptOutputFormat(640, 360, 15)
                        dualCapture = dual
                        dual.setTargetRotation(deviceOrientation.rotation)
                        dual.start(requireNotNull(videoSource), requireNotNull(backSource))
                        backTrack?.setEnabled(true)
                        presentationMode = CameraMode.DUAL
                        qualityPolicy = VideoQualityPolicy(false)
                        showMe.value = ShowMeState(CameraMode.DUAL)
                        started = true
                    }
                } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                    fallback = "no_first_frame"
                    logger.error(AppEvent.RTC_CAPABILITY_UNAVAILABLE)
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    fallback = "bind_failed"
                    logger.error(AppEvent.RTC_CAPABILITY_UNAVAILABLE)
                }
                if (!started) {
                    logger.info(AppEvent.RTC_SHOW_ME_FALLBACK, fallback)
                    dual.close(); dualCapture = null
                    if (stopped) requireNotNull(camera).startCapture(1280, 720, 30)
                    videoSource?.adaptOutputFormat(1920, 1080, 30)
                    switchSingle(requireNotNull(backName))
                    localFeed?.setMirrored(false)
                    presentationMode = CameraMode.BACK_ONLY
                    qualityPolicy = VideoQualityPolicy(false)
                    showMe.value = ShowMeState(CameraMode.BACK_ONLY, "当前使用单摄现场，可点「翻转」切回前摄")
                }
            }
            appliedQuality = null
            applyQuality(qualityPolicy.current)
            sendPresentation()
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            logger.error(AppEvent.RTC_MEDIA_FAILED)
            showMe.value = ShowMeState(presentationMode, "摄像头切换失败，请重试")
        } finally {
            changingCamera = false
            if (showMe.value.mode == CameraMode.STARTING) showMe.value = ShowMeState(presentationMode)
        }
    }

    /**
     * stopCapture() only posts the device close to the camera thread, so it can return while the
     * front camera is still held. CameraX then cannot open the concurrent pair, that camera never
     * produces a frame, and Show Me falls back to a single rear camera. Wait for the device to
     * actually close before handing the camera over.
     */
    private suspend fun releaseCamera() {
        val closed = CompletableDeferred<Unit>()
        cameraClosed = closed
        try {
            requireNotNull(camera).stopCapture()
            // A capturer that was never started reports no close, so this must not be fatal.
            if (withTimeoutOrNull(3_000) { closed.await() } == null) {
                logger.info(AppEvent.RTC_SHOW_ME_FALLBACK, "camera_close_timeout")
            }
        } finally { cameraClosed = null }
    }

    private suspend fun switchSingle(name: String) = withTimeout(5_000) {
        suspendCancellableCoroutine<Unit> { continuation ->
            requireNotNull(camera).switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
                override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                    if (continuation.isActive) continuation.resume(Unit)
                }
                override fun onCameraSwitchError(error: String) { if (continuation.isActive) continuation.resumeWithException(IOException("camera_switch_failed")) }
            }, name)
        }
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
    suspend fun networkChanged() = withContext(dispatcher) {
        handoverStatsUntilMs = System.nanoTime() / 1_000_000 + recoveryConfig.handoverStatsDurationMs
        statsWake.trySend(Unit)
        Unit
    }

    override suspend fun restartIce() = withContext(dispatcher) {
        requireNotNull(peer).restartIce()
        logger.info(AppEvent.RTC_ICE_RESTART)
    }

    suspend fun setSpeaker(enabled: Boolean): Boolean = withContext(dispatcher) {
        if (released) return@withContext false
        withContext(Dispatchers.Main.immediate) { callAudio.setSpeaker(enabled) }
    }

    @Suppress("DEPRECATION")
    override suspend fun release() {
        withContext(dispatcher) {
            if (released) return@withContext
            released = true
            statsJob?.cancel(); statsJob = null
            withContext(Dispatchers.Main.immediate) {
                localFeed?.close(); remoteFeed?.close(); localBackFeed?.close(); remoteBackFeed?.close()
            }
            // Attempt every release even if an OEM operation fails; log only an allowlisted event.
            fun cleanup(block: () -> Unit) { try { block() } catch (error: Exception) { logger.error(AppEvent.RTC_RELEASE_FAILED) } }
            try { dualCapture?.close() } catch (error: Exception) { logger.error(AppEvent.RTC_RELEASE_FAILED) }
            dualCapture = null
            cleanup { control?.unregisterObserver(); control?.close(); control?.dispose() }; control = null
            cleanup { deviceOrientation.close() }
            cleanup { cellularStandby.close() }
            cleanup { if (monitoring) { NetworkMonitor.getInstance().stopMonitoring(); monitoring = false } }
            cleanup { NetworkMonitor.removeNetworkObserver(networkObserver) }
            cleanup { camera?.stopCapture() }
            cleanup { remoteTrack?.removeSink(remoteFeed) }
            cleanup { remoteBackTrack?.removeSink(remoteBackFeed) }
            cleanup { backTrack?.removeSink(localBackFeed) }
            cleanup { videoTrack?.removeSink(localFeed) }
            cleanup { peer?.close() }
            cleanup { peer?.dispose() }; peer = null; videoSender = null; audioSender = null
            cleanup { camera?.dispose() }; camera = null
            cleanup { videoTrack?.dispose() }; videoTrack = null
            cleanup { audioTrack?.dispose() }; audioTrack = null
            cleanup { backTrack?.dispose() }; backTrack = null
            cleanup { backSource?.dispose() }; backSource = null
            cleanup { videoSource?.dispose() }; videoSource = null
            cleanup { audioSource?.dispose() }; audioSource = null
            cleanup { texture?.dispose() }; texture = null
            cleanup { factory?.dispose() }; factory = null
            if (ownsAudioProcessing) {
                cleanup { com.lazydoglab.zisee.rtc.audio.processing.SharedAudioProcessing.release(audioProcessing) }
                ownsAudioProcessing = false
                cleanup { context.unregisterComponentCallbacks(audioMemory) }
            }
            cleanup { audioProcessing.close() }
            cleanup { audioModule?.release() }; audioModule = null
            cleanup { egl?.release() }; egl = null
            withContext(Dispatchers.Main.immediate) {
                cleanup { callAudio.stop() }
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
            if (track !is VideoTrack) return
            // Routing by the remote track id trusts the peer's msid to survive the round trip. When
            // it does not, both cameras land on the same feed: the other feed keeps no sink and
            // renders black, while the surviving one shows whichever camera arrived last. The
            // transceiver is bidirectional, so the local track sharing its m-section identifies it
            // without parsing anything the peer wrote.
            val local = transceiver.sender.track()?.id()
            val back = if (local != null) local == MediaTrack.BACK_CAMERA.wireId
                else track.id() == MediaTrack.BACK_CAMERA.wireId
            logger.info(AppEvent.RTC_TRACK_ROUTED, "${if (back) "back" else "front"} msid_matched=${local == track.id()}")
            scope.launch { if (!released) {
                if (back) {
                    remoteBackTrack?.removeSink(remoteBackFeed); remoteBackTrack = track; track.addSink(remoteBackFeed)
                } else { remoteTrack?.removeSink(remoteFeed); remoteTrack = track; track.addSink(remoteFeed) }
            } }
        }
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceCandidate(candidate: IceCandidate) { scope.launch {
            val ufrag = Regex("(?:^| )ufrag ([^ ]+)").find(candidate.sdp)?.groupValues?.get(1)
            if (!released && acceptingCandidates && (ufrag == null || ufrag in localUfrags) && candidates.none { it.sdp == candidate.sdp && it.sdpMid == candidate.sdpMid }) {
                // The current protocol is append-only and bounded. Rotate generation instead of
                // crashing or rewriting an acknowledged prefix when networks keep appearing.
                if (candidates.size >= 32) candidateOverflow = true else candidates.add(candidate)
                candidateRevision.value++
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
        private const val LOOPBACK_ADAPTER = 1 shl 4
        @Volatile private var networksSeen = false
        private var initialized = false
        @Synchronized private fun initialize(context: Context, logger: AppLogger) {
            if (initialized) return
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .setInjectableLogger({ message, severity, _ ->
                    if (severity == Logging.Severity.LS_ERROR) {
                        logger.error(when {
                            message.contains("bind", ignoreCase = true) -> AppEvent.RTC_BIND_FAILED
                            message.contains("socket", ignoreCase = true) -> AppEvent.RTC_SOCKET_FAILED
                            message.contains("codec", ignoreCase = true) -> AppEvent.RTC_CODEC_FAILED
                            else -> AppEvent.RTC_NATIVE_ERROR
                        })
                        // The event name alone cannot explain a negotiation failure. The text is
                        // free-form and may quote SDP, so it stays out of release builds.
                        if (BuildConfig.DEBUG) Log.e("ZiseeNative", message.take(400))
                    }
                }, Logging.Severity.LS_ERROR)
                .createInitializationOptions())
            initialized = true
        }
    }
}
