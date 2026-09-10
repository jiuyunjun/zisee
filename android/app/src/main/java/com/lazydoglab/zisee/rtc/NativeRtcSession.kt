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
class NativeRtcSession(private val context: Context, private val logger: AppLogger,
    private val arFieldCoordinator: Boolean = false) : RtcSession {
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
    private var remoteScreenTrack: VideoTrack? = null
    private var screenSource: VideoSource? = null
    private var screenTrack: VideoTrack? = null
    private var screenSender: RtpSender? = null
    private var screenQualityPolicy = ScreenQualityPolicy()
    private var appliedScreenQuality: ScreenQuality? = null
    private var appliedScreenContentSize: com.lazydoglab.zisee.screen.ScreenSize? = null
    private val mutableScreenContentMode = MutableStateFlow(ScreenContentMode.TEXT)
    /** UI-facing "文字清晰 / 动态流畅" choice. Survives only for the current share; a new share
     * always restarts in [ScreenContentMode.TEXT]. */
    val screenContentMode = mutableScreenContentMode.asStateFlow()
    private var screenAdaptationEnabled = true
    private var stoppingScreenShare: com.lazydoglab.zisee.screen.ScreenShareSession? = null
    var localScreenFeed: VideoFeed? = null; private set
    var remoteScreenFeed: VideoFeed? = null; private set
    private var screenShare: com.lazydoglab.zisee.screen.ScreenShareSession? = null
    /** One session is one call, so a per-instance id is what a late consent result must match. */
    private val mediaCallId = java.util.UUID.randomUUID().toString()
    private var screenStateJob: Job? = null
    /** What this end is actually publishing, and what the peer says it is publishing. */
    val screenShareState = MutableStateFlow(com.lazydoglab.zisee.screen.ScreenShareState())
    val remoteShare = MutableStateFlow(SharePresentation.None)
    private var localShare = SharePresentation.None
    /** Suppresses every camera while the screen is the one thing being sent, in the same way
     * [arLeaseId] suppresses them while ARCore holds the device. */
    private var screenSharing = false
    /** Agrees who holds the call's single collaboration slot before any device is started. */
    private val ownership = CollaborationOwnership(arFieldCoordinator) { text -> sendControl(text) }
    val collaborationOwnership = ownership.state
    private var claimTimeout: Job? = null
    /** The camera arrangement to put back when the share ends, if the user has not changed it. */
    private var shareSuspendedMode: CameraMode? = null
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
    var arCollaboration: com.lazydoglab.zisee.ar.collaboration.ArDataChannel? = null; private set
    private var changingCamera = false
    private var arCapture: com.lazydoglab.zisee.ar.session.ArVideoCapture? = null
    private var arLeaseId: java.util.UUID? = null
    private var arStopRequested = false
    private var arStarting: CompletableDeferred<Unit>? = null
    private val mutableArState = MutableStateFlow(com.lazydoglab.zisee.ar.session.ArSessionState.IDLE)
    val arState = mutableArState.asStateFlow()
    val arCollaborationState get() = requireNotNull(arCollaboration).state
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
        // ICE twice promoted a Wi-Fi pair moments after the interface was gone. libwebrtc removes
        // the ports on a network it is told has disconnected, so when it learns of the loss decides
        // whether that is a stale pair it should never have had, or one it kept on purpose.
        logger.info(AppEvent.RTC_NETWORK_CHANGED, "native/${type.name}")
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
    // VIDEO_ADAPTATION.md §12.3: read once per call. OFF runs none of the code below; SHADOW runs
    // cameraPolicy alongside the old path and only logs; ACTIVE lets cameraPolicy write senders and
    // makes applyQuality/thumbnail early-return so only one path ever touches a camera sender.
    private val adaptationMode = VideoAdaptationMode.parse(BuildConfig.VIDEO_ADAPTATION)
    private var cameraPolicy = CameraAdaptationPolicy(false)
    private var appliedCameraRevision = -1
    /** Set once an ACTIVE setParameters call is rejected; from then on this call falls back to the
     * old path for good, exactly like [adaptationEnabled] does for it. */
    private var cameraActiveFallback = false
    private var appliedMainCamera: TrackPlan? = null
    private var appliedAuxCamera: TrackPlan? = null
    /** The (front-is-main, dual-is-active) pair the last camera write assumed. Any change means
     * "main"/"aux" now name different senders (or capture is single vs. dual), so the cached
     * [appliedMainCamera]/[appliedAuxCamera] must not be trusted to decide whether a write is a
     * no-op. */
    private var cameraMappingFrontIsMain: Boolean? = null
    private var cameraMappingDual: Boolean? = null
    /** Width/height/fps last actually passed to the single camera's startCapture/changeCaptureFormat
     * — the real HAL state, unlike [appliedMainCamera] which is only this session's bookkeeping and
     * gets cleared on every force=true reapply. A forced event-path reapply (swap, restart,
     * handover, audio-mode transition, startup) must still skip changeCaptureFormat when the plan's
     * dimensions already match what the capturer is running, or it reconfigures the HAL every time
     * one of those events fires even though nothing about the picture actually changed. */
    private var captureFormat: Triple<Int, Int, Int>? = null
    /** How the peer says it is showing this device's cameras, and what was last encoded for it. */
    private var remoteView = ViewRequest.Default
    private var appliedView: ViewRequest? = null
    private var sentView: ViewRequest? = null
    private var desiredView = ViewRequest.Default
    private var adaptationEnabled = true
    private val audioBandwidth = com.lazydoglab.zisee.rtc.audio.AudioBandwidthPolicy()
    private val handover = HandoverReport()
    private val gatheredOrigins = mutableSetOf<String>()
    private var connectedSinceMs = Long.MAX_VALUE
    private var lastSeedMs: Long? = null
    private var lastRouteChangeMs: Long? = null
    private var previousInboundBytes: Long? = null
    private var backupReady: Boolean? = null
    private var lastPairChangeMs: Long? = null
    private var relayReported = false
    private var sustainedSendKbps = 0L
    private val videoSending = mutableMapOf<RtpSender, Pair<Boolean, Boolean>>()
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
            .setVideoEncoderFactory(com.lazydoglab.zisee.ar.render.ArEncoderFactory(shared))
            .setVideoDecoderFactory(com.lazydoglab.zisee.ar.render.ArDecoderFactory(shared)).createPeerConnectionFactory()
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
            iceCheckIntervalStrongConnectivityMs = recoveryConfig.strongCheckIntervalMs
            stableWritableConnectionPingIntervalMs = recoveryConfig.stablePingIntervalMs
            iceUnwritableTimeMs = recoveryConfig.unwritableTimeoutMs
            iceUnwritableMinChecks = recoveryConfig.unwritableMinChecks
            // A cellular pair reached through a relay is usually the one a handover lands on.
            // Sending as soon as both ends are relayed removes a round trip from that switch.
            presumeWritableWhenFullyRelayed = true
            // The jitter buffer grows across the gap; without this it drains at real time and the
            // added delay outlives the handover that caused it.
            audioJitterBufferFastAccelerate = true
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
        cameraPolicy = CameraAdaptationPolicy(supportsFullHd, preferFullHd = true, supports60 = supports60)
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
        // The screen m-section is negotiated up front and left disabled, exactly like the rear
        // camera. Creating it when the user starts sharing would need a mid-call renegotiation,
        // and the queue that drives one only exists for the initial caller-role exchange.
        // isScreencast keeps libwebrtc from trading resolution away on a still page of text.
        screenSource = requireNotNull(factory).createVideoSource(true)
        localScreenFeed = VideoFeed(shared, false)
        remoteScreenFeed = VideoFeed(shared, false)
        screenTrack = requireNotNull(factory).createVideoTrack(MediaTrack.SCREEN.wireId, screenSource).also {
            it.setEnabled(false); it.addSink(localScreenFeed)
            screenSender = requireNotNull(peer).addTrack(it, listOf("zisee"))
        }
        // Rear video carries AR identities through H264 SEI; retain every fallback codec.
        val rearCodecs = requireNotNull(factory).getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs
        if (rearCodecs.any { it.name.equals("H264", true) }) {
            try {
                requireNotNull(peer).transceivers.firstOrNull { it.sender.id() == backSender?.id() }
                    ?.setCodecPreferences(rearCodecs.sortedBy { if (it.name.equals("H264", true)) 0 else 1 })
            } catch (_: Exception) { logger.error(AppEvent.RTC_CAPABILITY_UNAVAILABLE) }
        }
        control = requireNotNull(peer).createDataChannel("camera-state", DataChannel.Init().apply { negotiated = true; id = 0 })
        arCollaboration = com.lazydoglab.zisee.ar.collaboration.ArDataChannel(
            requireNotNull(requireNotNull(peer).createDataChannel(
                com.lazydoglab.zisee.ar.annotation.ArProtocol.CHANNEL_LABEL,
                DataChannel.Init().apply { negotiated = true; id = 2; ordered = true },
            )), dispatcher, arFieldCoordinator,
        ) { logger.error(AppEvent.AR_CHANNEL_FAILED) }
        scope.launch {
            requireNotNull(arCollaboration).state.collect { collaboration ->
                if (!released && collaboration.ownershipLost && arLeaseId != null) stopAr()
            }
        }
        control?.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit
            override fun onStateChange() { scope.launch {
                if (released) return@launch
                if (control?.state() == DataChannel.State.OPEN) ownership.connected()
                else ownership.disconnected()
                sendPresentation(); sendViewLayout(); sendSharePresentation()
            } }
            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.binary || buffer.data.remaining() > 32) return
                val bytes = ByteArray(buffer.data.remaining()); buffer.data.get(bytes)
                val text = bytes.toString(Charsets.UTF_8)
                CameraPresentation.decode(text)?.let { value ->
                    scope.launch { if (!released) remotePresentation.value = value }
                    return
                }
                SharePresentation.decode(text)?.let { value ->
                    scope.launch { if (!released) remoteShare.value = value }
                    return
                }
                ViewRequest.decode(text)?.let { value ->
                    scope.launch {
                        if (!released && remoteView != value) {
                            remoteView = value
                            applyQuality(qualityPolicy.current, changeCapture = false)
                        }
                    }
                    return
                }
                // Ownership is serialized onto the same dispatcher as every other decision about
                // starting or stopping capture, so a claim cannot interleave with one.
                scope.launch { if (!released) ownership.receive(text) }
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
        captureFormat = Triple(initialQuality.width, initialQuality.height, initialQuality.fps)
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
                    if ((thermal ?: 0) >= 3 && !changingCamera) {
                        // §12.3: force cameraPolicy's own plan to C0 first (SHADOW logs it, ACTIVE's
                        // applyQuality branch below then applies exactly that plan) — this fast lane
                        // cannot wait for a stats sample that may never arrive.
                        val severePlan = if (adaptationMode != VideoAdaptationMode.OFF)
                            cameraPolicy.severe(System.nanoTime() / 1_000_000, CameraAdaptationReason.THERMAL)
                                .also { logCameraPlanIfChanged(it) } else null
                        // Runs every tick while hot: ACTIVE must not force-rewrite (and restart capture)
                        // each second, so it applies the unchanged C0 plan through the normal diff.
                        if (severePlan != null && adaptationMode == VideoAdaptationMode.ACTIVE && !cameraActiveFallback)
                            applyCameraPlan(severePlan)
                        else applyQuality(QualityDecision(VideoQuality.ECONOMY, QualityReason.THERMAL))
                    }
                    audioProcessing.setThermal(thermal ?: 0)
                    val result = stats()
                    val observed = result.copy(thermalStatus = thermal)
                    // Never use idle/muted encoder statistics to make quality decisions.
                    if (iceState.value == IceState.CONNECTED && videoTrack?.enabled() == true && adaptationEnabled && !changingCamera &&
                        audioBandwidth.mode != com.lazydoglab.zisee.rtc.audio.AudioBandwidthMode.AUDIO_ONLY &&
                        audioBandwidth.mode != com.lazydoglab.zisee.rtc.audio.AudioBandwidthMode.VIDEO_PROBE) {
                        // qualityPolicy keeps running every tick regardless of mode — its state is
                        // the ACTIVE fallback target and the SHADOW/ACTIVE log comparison — but in
                        // ACTIVE (and not yet fallen back) applying its decision every second is
                        // exactly the old writer this switch exists to replace; cameraPolicy's own
                        // per-sample plan (below) is what actually reaches the sender.
                        val decision = qualityPolicy.update(observed, System.nanoTime() / 1_000_000)
                        if (!(adaptationMode == VideoAdaptationMode.ACTIVE && !cameraActiveFallback)) applyQuality(decision)
                    }
                    // VIDEO_ADAPTATION.md §12.3: OFF runs none of this. SHADOW/ACTIVE mirror the old
                    // path's guards exactly (probe/audio-only/AR/screen keep camera senders alone).
                    if (adaptationMode != VideoAdaptationMode.OFF && iceState.value == IceState.CONNECTED &&
                        videoTrack?.enabled() == true && !changingCamera && !screenSharing && arLeaseId == null &&
                        audioBandwidth.mode != com.lazydoglab.zisee.rtc.audio.AudioBandwidthMode.AUDIO_ONLY &&
                        audioBandwidth.mode != com.lazydoglab.zisee.rtc.audio.AudioBandwidthMode.VIDEO_PROBE) {
                        runCameraAdaptation(cameraPolicy.update(cameraAdaptationInput(observed, System.nanoTime() / 1_000_000)))
                    }
                    if (iceState.value == IceState.CONNECTED && !changingCamera) applyAudioBandwidth(observed)
                    if (iceState.value == IceState.CONNECTED && screenSharing && screenAdaptationEnabled) {
                        applyScreenQuality(screenQualityPolicy.update(observed, observed.sampledAtMs))
                    }
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

    private suspend fun applyAudioBandwidth(stats: MediaStats) {
        videoSending.keys.retainAll(listOfNotNull(videoSender, backSender, screenSender).toSet())
        val previous = audioBandwidth.mode
        val mode = audioBandwidth.update(stats.availableOutgoingKbps, stats.audio.outboundLoss, stats.sampledAtMs,
            stats.audio.outboundReportTimestampUs)
        if (mode != previous) {
            logger.info(AppEvent.RTC_VIDEO_BANDWIDTH_MODE, "${previous.name}->${mode.name}")
            appliedQuality = null
            applyQuality(qualityPolicy.current, changeCapture = false)
        }
        if (mode == com.lazydoglab.zisee.rtc.audio.AudioBandwidthMode.AUDIO_ONLY && screenShare != null) {
            stopScreenShare(com.lazydoglab.zisee.screen.ScreenShareReason.AUDIO_ONLY)
        }
        val frontMain = dualCapture == null || (remoteView.front == ViewSize.LARGE && remoteView.back == ViewSize.SMALL)
        for ((sender, primary) in listOf(videoSender to frontMain, backSender to !frontMain)) {
            if (sender == null) continue
            val enabled = mode == com.lazydoglab.zisee.rtc.audio.AudioBandwidthMode.ALL_VIDEO ||
                (mode in setOf(com.lazydoglab.zisee.rtc.audio.AudioBandwidthMode.PRIMARY_ONLY,
                    com.lazydoglab.zisee.rtc.audio.AudioBandwidthMode.VIDEO_PROBE) && primary)
            // Only cap the probe when quality changes can still be undone; an OEM that rejected
            // setParameters would otherwise leave the camera stuck at the probe format.
            val probing = mode == com.lazydoglab.zisee.rtc.audio.AudioBandwidthMode.VIDEO_PROBE && primary && adaptationEnabled
            val plan = enabled to probing
            if (videoSending[sender] == plan) continue
            val parameters = sender.parameters
            if (parameters.encodings.isEmpty()) continue
            parameters.encodings.forEach {
                it.active = enabled
                if (probing) { it.maxBitrateBps = 150_000; it.maxFramerate = 10; it.minBitrateBps = null }
            }
            if (sender.setParameters(parameters)) {
                videoSending[sender] = plan
                if (probing) (if (sender === videoSender) videoSource else backSource)?.adaptOutputFormat(320, 180, 10)
            }
            else logger.error(AppEvent.RTC_QUALITY_REJECTED)
        }
    }

    /** §6.3: output size is always a fresh scale of the raw [ScreenShareState.contentSize], never a
     * re-scale of the already-capped [ScreenShareState.size]. §7/item4: re-applies only when the
     * tier or the content size actually changed, not on every share-state emission (visibility,
     * resize echoes of the same size, ...). */
    private fun applyScreenQuality(quality: ScreenQuality) {
        if (!screenAdaptationEnabled) return
        val sender = screenSender ?: return
        val contentSize = screenShareState.value.contentSize ?: return
        if (appliedScreenQuality == quality && appliedScreenContentSize == contentSize) return
        val qualityChanged = appliedScreenQuality != quality
        val parameters = sender.parameters
        if (parameters.encodings.isEmpty()) return
        parameters.degradationPreference = if (quality.mode == ScreenContentMode.MOTION)
            RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
        else RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
        parameters.encodings.forEach {
            it.maxBitrateBps = quality.maxBitrateBps
            it.maxFramerate = quality.fps
            it.minBitrateBps = null
        }
        if (!sender.setParameters(parameters)) {
            screenAdaptationEnabled = false
            logger.error(AppEvent.RTC_QUALITY_REJECTED)
            return
        }
        val output = com.lazydoglab.zisee.screen.ScreenCaptureSize.of(contentSize.width, contentSize.height, quality.longEdge)
        screenSource?.adaptOutputFormat(output.width, output.height, quality.fps)
        appliedScreenQuality = quality
        appliedScreenContentSize = contentSize
        // No screen content, SDP or addresses: only the enum tier and reason.
        if (qualityChanged) logger.info(AppEvent.RTC_QUALITY_CHANGED, "screen:${quality.name}:${screenQualityPolicy.lastReason.name}")
    }

    /** §6.1: switching text/motion never restarts projection or re-consents. */
    suspend fun setScreenContentMode(mode: ScreenContentMode) = withContext(dispatcher) {
        if (released) return@withContext
        mutableScreenContentMode.value = mode
        screenQualityPolicy.setMode(mode, System.nanoTime() / 1_000_000)
        if (screenSharing) applyScreenQuality(screenQualityPolicy.current)
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
        val result = sampler.sample(report.statsMap.values.map { StatsEntry(it.id, it.type, it.members, it.timestampUs) },
            System.nanoTime() / 1_000_000, localBack = dualCapture != null,
            remoteTrackId = (if (remoteShare.value.sharing) remoteScreenTrack
                else if (remotePresentation.value.mode == CameraMode.DUAL) remoteBackTrack else remoteTrack)?.id(),
            localTrackId = if (screenSharing) MediaTrack.SCREEN.wireId
                else if (dualCapture != null && !(remoteView.front == ViewSize.LARGE && remoteView.back == ViewSize.SMALL))
                    MediaTrack.BACK_CAMERA.wireId else MediaTrack.FRONT_CAMERA.wireId)
        // Which interfaces this call has actually gathered on, and when. A handover can only be
        // fast if the other interface was already gathered and checked before it happened; the
        // trickled candidate's adapter type is reported as UNKNOWN, so read it from the stats.
        for (entry in report.statsMap.values) {
            if (entry.type != "local-candidate") continue
            val origin = "${entry.members["networkType"] ?: "unknown"}/${entry.members["candidateType"] ?: "unknown"}"
            if (gatheredOrigins.add(origin)) logger.info(AppEvent.RTC_LOCAL_CANDIDATE, origin)
        }
        // A handover is only ever fast when the other interface already has a candidate that can
        // reach the peer. A measured switch with one took a second; the same switch with nothing but
        // that interface's host candidate took ten, because everything had to be gathered after the
        // break. Report which of the two this call is set up for, and when that changes.
        if (result.sampledAtMs - connectedSinceMs >= BACKUP_CHECK_MS) {
            val backup = gatheredOrigins.filter { !it.startsWith("${result.networkType}/") }
            val ready = backup.any { it.endsWith("/srflx") || it.endsWith("/relay") }
            if (ready != backupReady) {
                backupReady = ready
                val reachable = backup.firstOrNull { it.endsWith("/srflx") || it.endsWith("/relay") }
                logger.info(AppEvent.RTC_BACKUP_PATH, if (ready) "ready=$reachable"
                    else "missing on=${result.networkType} origins=${gatheredOrigins.size}")
            }
        }
        val stalled = previousInboundBytes?.let { result.inboundBytes <= it } == true
        previousInboundBytes = result.inboundBytes
        val route = "${result.candidateType}/${result.remoteCandidateType} ${result.networkType}/${result.protocol}"
        if (route != lastCandidate || result.selectedPairId != lastSelectedPairId) {
            // The first pair a call selects is not a handover away from anything, and neither is
            // the peer-reflexive to host promotion that normally follows it a second later.
            if (lastSelectedPairId != null && result.sampledAtMs - connectedSinceMs >= SETTLED_MS) {
                // ICE keeps improving the route for seconds after a switch, and each promotion
                // costs media of its own. Measure and react to those at handover resolution too.
                handoverStatsUntilMs = result.sampledAtMs + recoveryConfig.handoverStatsDurationMs
                audioBandwidth.routeChanged(result.sampledAtMs)
                handover.pairChanged(result.sampledAtMs, appliedQuality)
                // Coming back small only pays for itself when the picture actually stopped. ICE
                // promotes the relay pair to a direct one seconds after a handover, on a route the
                // network never changed and while media is still flowing: giving up the picture
                // there costs four seconds of soft image to save a key frame nobody is waiting on.
                val handingOver = stalled ||
                    lastRouteChangeMs?.let { result.sampledAtMs - it <= HANDOVER_WINDOW_MS } == true
                if (handingOver) {
                    qualityPolicy.routeChanged(result.sampledAtMs)
                    if (adaptationMode != VideoAdaptationMode.OFF) {
                        cameraPolicy.routeChanged(result.sampledAtMs)
                        if (adaptationMode == VideoAdaptationMode.ACTIVE) applyCameraPlan(cameraPolicy.lastPlan)
                    }
                    seedBitrate(result.sampledAtMs)
                    // Push the smaller frame out now rather than a tick later, and without
                    // restarting the camera: this key frame is the one the viewer is waiting on.
                    applyQuality(qualityPolicy.current, changeCapture = false)
                }
            }
            lastCandidate = route; lastSelectedPairId = result.selectedPairId
            lastPairChangeMs = result.sampledAtMs; relayReported = false
            // Log pair transitions even when both paths have the same candidate types. Never log IPs or SDP.
            logger.info(AppEvent.RTC_SELECTED_CANDIDATE, "$route atMs=${result.sampledAtMs}")
        } else if (result.sendKbps > 0 && audioBandwidth.mode == com.lazydoglab.zisee.rtc.audio.AudioBandwidthMode.ALL_VIDEO) {
            // A recent peak, not an all-call one: what the next route has to reach is what this one
            // was carrying lately, so an old high water mark decays out of it.
            sustainedSendKbps = maxOf(result.sendKbps, sustainedSendKbps * 9 / 10)
        }
        // Staying on a relay is the right answer when nothing else reaches the peer, and a waste of
        // latency and relay traffic when something does. Only the pairs that actually succeeded can
        // tell those apart, so count the direct ones once the route has settled.
        if (!relayReported && (result.candidateType == "relay" || result.remoteCandidateType == "relay") &&
            lastPairChangeMs?.let { result.sampledAtMs - it >= RELAY_CHECK_MS } == true) {
            val kinds = report.statsMap.values
                .filter { it.type == "local-candidate" || it.type == "remote-candidate" }
                .associate { it.id to (it.members["candidateType"] as? String ?: "unknown") }
            val direct = report.statsMap.values.count { entry ->
                entry.type == "candidate-pair" && entry.members["state"] == "succeeded" &&
                    kinds[entry.members["localCandidateId"]] != "relay" &&
                    kinds[entry.members["remoteCandidateId"]] != "relay"
            }
            relayReported = true
            logger.info(AppEvent.RTC_RELAY_PINNED, "directSucceeded=$direct")
        }
        handover.sample(result)?.let { logger.info(AppEvent.RTC_HANDOVER, it.encode()) }
        handover.videoResumed()?.let { logger.info(AppEvent.RTC_HANDOVER_VIDEO, "ms=$it") }
        handover.qualityRestored(appliedQuality, result.sampledAtMs)
            ?.let { logger.info(AppEvent.RTC_QUALITY_RESTORED, "ms=$it") }
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

    /**
     * Congestion control starts over on a new route, and climbing back from its opening estimate
     * takes far longer than the link needs. Re-seed it, but only just past the slowest part of that
     * ramp: seeding at half of what Wi-Fi was carrying overshot a fresh cellular uplink, and the
     * loss that followed cost the audio reserve, the picture and thirty seconds of recovery. A
     * quarter, capped at one megabit, keeps video alive while libwebrtc measures the new path.
     *
     * Once per handover. The pair can change several times while a route settles, and re-seeding on
     * each of those restarts the estimator underneath a link it was already measuring.
     */
    private fun seedBitrate(nowMs: Long) {
        // A handover that seeds nothing leaves the new path to climb from the opening estimate, and
        // a measured one did exactly that with no trace of why. Say which precondition refused.
        if (sustainedSendKbps <= 0) {
            logger.info(AppEvent.RTC_BITRATE_SEEDED, "skipped=no_measured_rate")
            return
        }
        if (audioBandwidth.mode == com.lazydoglab.zisee.rtc.audio.AudioBandwidthMode.AUDIO_ONLY) {
            logger.info(AppEvent.RTC_BITRATE_SEEDED, "skipped=video_paused")
            return
        }
        // Never subtract from a sentinel: nowMs - Long.MIN_VALUE overflows negative and this
        // guard then swallowed every seed the feature exists to apply.
        if (lastSeedMs?.let { nowMs - it < SEED_COOLDOWN_MS } == true) {
            logger.info(AppEvent.RTC_BITRATE_SEEDED, "skipped=cooldown")
            return
        }
        val seed = (sustainedSendKbps * 1_000 / 4).coerceIn(MIN_SEED_BPS, MAX_SEED_BPS).toInt()
        lastSeedMs = nowMs
        if (peer?.setBitrate(null, seed, null) == false) logger.error(AppEvent.RTC_QUALITY_REJECTED)
        else logger.info(AppEvent.RTC_BITRATE_SEEDED, "kbps=${seed / 1_000}")
    }

    private fun applyQuality(decision: QualityDecision, changeCapture: Boolean = true) {
        // VIDEO_ADAPTATION.md §12.3: ACTIVE means the new applier owns camera senders. Every old
        // trigger that calls applyQuality — startup, a remoteView main/aux swap, the AUDIO_ONLY
        // transition, camera restart/share end/AR end, a handover — still lands here, so route all
        // of them to the new applier's current plan instead of writing nothing. force=true because
        // any of those triggers may have just changed which sender is main/aux, or restarted
        // capture, making the applier's own "nothing changed" cache stale.
        if (adaptationMode == VideoAdaptationMode.ACTIVE && !cameraActiveFallback) {
            applyCameraPlan(cameraPolicy.lastPlan, force = true)
            return
        }
        if (arLeaseId != null) return // AR preserves the full image; sender congestion control remains active.
        // With every camera paused this would adapt a dead sender; the screen has its own encoding.
        if (screenSharing) return
        if (audioBandwidth.mode == com.lazydoglab.zisee.rtc.audio.AudioBandwidthMode.VIDEO_PROBE) return
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
                if (changeCapture) {
                    requireNotNull(camera).changeCaptureFormat(
                        decision.quality.width, decision.quality.height, decision.quality.fps)
                    captureFormat = Triple(decision.quality.width, decision.quality.height, decision.quality.fps)
                }
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

    /** Which physical camera is "main" mirrors [applyQuality]'s own frontIsMain choice, so SHADOW's
     * plan and ACTIVE's writes always describe the same sender the old path would have touched. */
    private fun cameraAdaptationInput(stats: MediaStats, nowMs: Long): CameraAdaptationInput {
        val frontIsMain = dualCapture != null && remoteView.front == ViewSize.LARGE && remoteView.back == ViewSize.SMALL
        val mainId = if (dualCapture == null || frontIsMain) "video_front" else "video_back"
        val auxId = if (dualCapture != null) (if (frontIsMain) "video_back" else "video_front") else null
        fun observation(id: String?) = stats.outboundVideo[id]?.let {
            CameraTrackObservation(it.encodeMs, it.sendDelayMs, it.outboundLoss, it.outboundReportFresh,
                it.qualityLimitation, it.retransmittedKbps)
        } ?: CameraTrackObservation()
        return CameraAdaptationInput(nowMs, stats.availableOutgoingKbps, stats.thermalStatus, observation(mainId),
            auxId?.let { observation(it) }, mainViewedSmall = dualCapture == null && remoteView.front == ViewSize.SMALL)
    }

    /** Logs a changed plan (SHADOW and ACTIVE both want this, including the severe-thermal fast
     * lane), keyed on [CameraPlan.revision] so an unchanged plan never repeats the line. */
    private fun logCameraPlanIfChanged(plan: CameraPlan) {
        if (plan.revision == appliedCameraRevision) return
        appliedCameraRevision = plan.revision
        val aux = plan.aux?.let { "${it.tier}/${it.maxBitrateBps / 1_000}k" } ?: "-"
        logger.info(AppEvent.RTC_ADAPTATION_PLAN,
            "mode=${adaptationMode.name} main=${plan.main.tier}/${plan.main.maxBitrateBps / 1_000}k aux=$aux " +
                // appliedQuality is the old writer's applied-cache and stays null for the whole
                // call in ACTIVE (it never writes); qualityPolicy.current is kept running every
                // tick specifically so this comparison field is meaningful in every mode.
                "reason=${plan.reason.name} pool=${plan.poolKbps} bwe=${plan.bweKbps ?: -1} old=${qualityPolicy.current.quality.name}")
    }

    /** SHADOW: log a changed plan only, never touch a sender. ACTIVE: write the plan; on rejection
     * fall back to the old path for the rest of this call, exactly as [adaptationEnabled] does. */
    private fun runCameraAdaptation(plan: CameraPlan) {
        logCameraPlanIfChanged(plan)
        if (adaptationMode == VideoAdaptationMode.ACTIVE) applyCameraPlan(plan)
    }

    /** [force] means "do not trust what was last applied" — the sender mapping or capture device
     * may have just changed (startup, a remoteView swap, camera restart/share end/AR end, a
     * handover), so both tracks are rewritten and, for the single-camera path, [CameraVideoCapturer]
     * is told to reformat even if the tier's dimensions happen to match the stale cache. */
    private fun applyCameraPlan(plan: CameraPlan, force: Boolean = false) {
        if (cameraActiveFallback) return
        // Same skip conditions as applyQuality/thumbnail: a dead or foreign sender must not be adapted.
        if (arLeaseId != null) return
        if (screenSharing) return
        if (audioBandwidth.mode == com.lazydoglab.zisee.rtc.audio.AudioBandwidthMode.VIDEO_PROBE ||
            audioBandwidth.mode == com.lazydoglab.zisee.rtc.audio.AudioBandwidthMode.AUDIO_ONLY) return
        val frontIsMain = dualCapture != null && remoteView.front == ViewSize.LARGE && remoteView.back == ViewSize.SMALL
        val dualActive = dualCapture != null
        // "main"/"aux" now name different senders (or single vs. dual capture changed): the cached
        // applied plans describe a sender this call is no longer about to write.
        if (force || frontIsMain != cameraMappingFrontIsMain || dualActive != cameraMappingDual) {
            appliedMainCamera = null; appliedAuxCamera = null
            cameraMappingFrontIsMain = frontIsMain; cameraMappingDual = dualActive
        }
        val mainSender = (if (dualCapture == null || frontIsMain) videoSender else backSender) ?: return
        val mainSource = if (dualCapture == null || frontIsMain) videoSource else backSource
        val auxSender = if (dualCapture != null) (if (frontIsMain) backSender else videoSender) else null
        val auxSource = if (dualCapture != null) (if (frontIsMain) backSource else videoSource) else null

        fun write(sender: RtpSender, track: TrackPlan): Boolean {
            val parameters = sender.parameters
            if (parameters.encodings.isEmpty()) return true // Retry once negotiation produces encodings.
            parameters.degradationPreference = RtpParameters.DegradationPreference.BALANCED
            parameters.encodings.forEach {
                it.maxBitrateBps = track.maxBitrateBps; it.maxFramerate = track.fps; it.minBitrateBps = null
            }
            return sender.setParameters(parameters)
        }
        fun fallback() {
            cameraActiveFallback = true
            logger.error(AppEvent.RTC_QUALITY_REJECTED)
            appliedQuality = null
            applyQuality(qualityPolicy.current, changeCapture = false)
        }
        try {
            // §8: lower whichever sender is giving up budget first, then raise the other — never
            // both drawing their old ceilings for a transition instant.
            val loweringMain = plan.main.maxBitrateBps < (appliedMainCamera?.maxBitrateBps ?: Int.MAX_VALUE)
            for (isMain in if (loweringMain || auxSender == null) listOf(true, false) else listOf(false, true)) {
                val sender = if (isMain) mainSender else auxSender ?: continue
                val track = if (isMain) plan.main else plan.aux ?: continue
                if (!write(sender, track)) { fallback(); return }
                if (isMain) {
                    mainSource?.adaptOutputFormat(track.width, track.height, track.fps)
                    // Compare against the format actually last given to the capturer, not the
                    // applied-plan cache: force=true clears that cache on every event-path reapply
                    // (swap, restart, handover, audio-mode transition, startup), and a per-second
                    // "nothing changed" reapply must not reconfigure the HAL every tick either.
                    // Some OEM HALs (seen on Xiaomi) close and reopen the camera for every format
                    // change, so a tier change would visibly restart it and the frame gap then reads
                    // as QUEUE pressure. Only reformat when the capturer cannot already supply the
                    // tier; adaptOutputFormat above scales down without touching the HAL.
                    val target = Triple(track.width, track.height, track.fps)
                    val covers = captureFormat?.let { (w, h, fps) ->
                        w >= target.first && h >= target.second && fps >= target.third } == true
                    if (dualCapture == null && !covers) {
                        requireNotNull(camera).changeCaptureFormat(track.width, track.height, track.fps)
                        captureFormat = target
                    }
                } else auxSource?.adaptOutputFormat(track.width, track.height, track.fps)
            }
            appliedMainCamera = plan.main
            appliedAuxCamera = plan.aux
        } catch (error: Exception) {
            fallback()
        }
    }

    override suspend fun setTrackEnabled(track: MediaTrack, enabled: Boolean) = withContext(dispatcher) {
        if (!released) when (track) {
            MediaTrack.MICROPHONE -> audioTrack?.setEnabled(enabled)
            MediaTrack.FRONT_CAMERA, MediaTrack.BACK_CAMERA -> {
                cameraEnabled = enabled
                // A share owns every camera until it ends. Foreground changes and the camera
                // button both land here, and neither may quietly resume a paused camera.
                videoTrack?.setEnabled(enabled && arLeaseId == null && !screenSharing)
                backTrack?.setEnabled(enabled && !screenSharing &&
                    (presentationMode == CameraMode.DUAL || arLeaseId != null))
                // Turning the camera off during a share is a real change of intent, so nothing is
                // restored for the user afterwards.
                if (!enabled) shareSuspendedMode = null
                sendPresentation()
            }
            // Screen sending follows the capture lifecycle, not a UI toggle: stopScreenShare is the
            // only way to stop, because leaving a live projection with a disabled track keeps
            // capturing the user's screen while telling them nothing is going out.
            MediaTrack.SCREEN -> throw UnsupportedOperationException("screen_not_available")
        }
        Unit
    }

    /**
     * Asks the peer for the call's one collaboration slot. Nothing is started here: the system
     * consent dialog is only worth showing once [collaborationOwnership] reports HELD, so two users
     * tapping at the same moment cannot end up with two consent dialogs and two projections.
     */
    suspend fun claimCollaboration(): Boolean = withContext(dispatcher) {
        if (released || screenShare != null) return@withContext false
        if (!ownership.claim()) return@withContext false
        val id = ownership.outstanding
        claimTimeout?.cancel()
        claimTimeout = scope.launch {
            delay(CollaborationOwnership.CLAIM_TIMEOUT_MS)
            if (!released && id != null) ownership.claimExpired(id)
        }
        true
    }

    /** Gives the slot back. Idempotent, and safe whether or not a claim ever became ownership. */
    suspend fun releaseCollaboration() = withContext(dispatcher) {
        claimTimeout?.cancel(); claimTimeout = null
        if (!released) ownership.release()
    }

    /**
     * Claims this call's one share attempt. A request is what the system consent dialog is asked
     * for; it carries no consent data and grants nothing on its own.
     */
    suspend fun requestScreenShare(): com.lazydoglab.zisee.screen.ScreenShareRequest? = withContext(dispatcher) {
        if (released || screenShare != null) return@withContext null
        // The slot must already be agreed: a device is never started on an unconfirmed claim.
        if (ownership.state.value.phase != CollaborationOwnership.Phase.HELD) return@withContext null
        val source = screenSource ?: return@withContext null
        val shared = egl?.eglBaseContext ?: return@withContext null
        lateinit var session: com.lazydoglab.zisee.screen.ScreenShareSession
        screenQualityPolicy = ScreenQualityPolicy()
        mutableScreenContentMode.value = ScreenContentMode.TEXT
        screenAdaptationEnabled = true
        appliedScreenQuality = null
        appliedScreenContentSize = null
        session = com.lazydoglab.zisee.screen.ScreenShareSession(
            context, mediaCallId, shared, source, logger,
        ) { request, active -> scope.launch {
            if (!released && screenShare === session && session.state.value.request == request) {
                if (active && stoppingScreenShare !== session &&
                    session.state.value.phase == com.lazydoglab.zisee.screen.ScreenSharePhase.ACTIVE) {
                    applyScreenSending(true)
                } else if (!active && session.state.value.phase in TERMINAL_SCREEN_PHASES) {
                    closeScreenShare(session, restoreCameras = audioBandwidth.mode !=
                        com.lazydoglab.zisee.rtc.audio.AudioBandwidthMode.AUDIO_ONLY &&
                        session.state.value.reason != com.lazydoglab.zisee.screen.ScreenShareReason.AUDIO_ONLY)
                    ownership.release()
                }
            }
        } }
        screenShare = session
        screenStateJob?.cancel()
        screenStateJob = scope.launch { session.state.collect {
            if (!released && screenShare === session) {
                screenShareState.value = it
                // applyScreenQuality re-applies on its own only when the tier or the content size
                // actually changed, so a visibility-only or duplicate-size emission is a no-op here.
                if (it.phase in setOf(com.lazydoglab.zisee.screen.ScreenSharePhase.STARTING,
                        com.lazydoglab.zisee.screen.ScreenSharePhase.ACTIVE)) {
                    applyScreenQuality(screenQualityPolicy.current)
                }
            }
        } }
        val request = session.request()
        if (request == null) { session.close(); screenShare = null }
        request
    }

    /** [data] is the untouched system consent result; it is never stored or logged. */
    suspend fun startScreenShare(request: com.lazydoglab.zisee.screen.ScreenShareRequest,
        resultCode: Int, data: android.content.Intent): Boolean = withContext(dispatcher) {
        val session = screenShare ?: return@withContext false
        if (released) return@withContext false
        val started = session.start(request, resultCode, data)
        if (!started) closeScreenShare(session)
        started
    }

    suspend fun stopScreenShare(reason: com.lazydoglab.zisee.screen.ScreenShareReason =
        com.lazydoglab.zisee.screen.ScreenShareReason.USER) = withContext(dispatcher) {
        claimTimeout?.cancel(); claimTimeout = null
        val session = screenShare
        if (session != null) {
            // Privacy stop wins before projection cleanup or any callback can run.
            stoppingScreenShare = session
            screenTrack?.setEnabled(false)
            if (reason == com.lazydoglab.zisee.screen.ScreenShareReason.AUDIO_ONLY) cameraEnabled = false
            session.stop(reason)
            closeScreenShare(session, restoreCameras = reason !=
                com.lazydoglab.zisee.screen.ScreenShareReason.AUDIO_ONLY)
        }
        // The slot is given back whether or not capture ever started: a denied or cancelled consent
        // must not leave the peer believing this end still owns it.
        if (!released) ownership.release()
    }

    /** One controller per attempt: a stopped share can never be resumed on its consumed token. */
    private suspend fun closeScreenShare(expected: com.lazydoglab.zisee.screen.ScreenShareSession? = screenShare,
        restoreCameras: Boolean = true) {
        if (expected == null || screenShare !== expected) return
        applyScreenSending(false, restoreCameras)
        screenShareState.value = expected.state.value
        screenStateJob?.cancel(); screenStateJob = null
        try { expected.close() } catch (_: Exception) { logger.error(AppEvent.SCREEN_SHARE_FAILED) }
        screenShare = null
        if (stoppingScreenShare === expected) stoppingScreenShare = null
        appliedScreenQuality = null
        appliedScreenContentSize = null
    }

    /**
     * The track is enabled only while frames are genuinely flowing, so the peer's "sharing" state
     * and the picture it waits for cannot disagree. The session id changes per share, letting the
     * viewer drop any selection or annotation state left over from the previous one.
     *
     * A share is the one thing this device sends: every camera pauses for its duration and the
     * hardware is released, so nothing competes with the screen for the uplink or the thermal
     * budget, and the peer is never left choosing between a screen and a stale face.
     */
    private suspend fun applyScreenSending(active: Boolean, restoreCameras: Boolean = true) {
        if (active == screenSharing) {
            screenTrack?.setEnabled(active)
            return
        }
        screenSharing = active
        screenTrack?.setEnabled(active)
        if (active) {
            applyScreenQuality(screenQualityPolicy.current)
            pauseCamerasForShare()
        } else if (restoreCameras) restoreCamerasAfterShare() else {
            shareSuspendedMode = null
            showMe.value = ShowMeState(presentationMode)
        }
        localShare = if (!active) SharePresentation.None
            else SharePresentation(true, java.util.UUID.randomUUID().toString()
                .replace("-", "").take(SharePresentation.MAX_SESSION))
        sendSharePresentation()
        sendPresentation()
    }

    /** Releases the camera devices, not just their tracks: a projection is exactly when the
     * thermal and power budget matters most. Failing to close one still pauses what it sends. */
    private suspend fun pauseCamerasForShare() {
        shareSuspendedMode = if (cameraEnabled) presentationMode else null
        changingCamera = true
        try {
            videoTrack?.setEnabled(false)
            backTrack?.setEnabled(false)
            try { dualCapture?.close() } catch (_: Exception) { logger.error(AppEvent.RTC_MEDIA_FAILED) }
            dualCapture = null
            try { releaseCamera() } catch (_: Exception) { logger.error(AppEvent.RTC_MEDIA_FAILED) }
            showMe.value = ShowMeState(presentationMode, "共享屏幕时摄像头已暂停")
        } finally { changingCamera = false }
    }

    /**
     * §4.1: only what this share paused comes back. A camera the user switched off during the
     * share, or a device that can no longer open the pair, stays off rather than surprising them
     * with a live camera when the share ends.
     */
    private suspend fun restoreCamerasAfterShare() {
        val previous = shareSuspendedMode
        shareSuspendedMode = null
        if (previous == null || released || !cameraEnabled) {
            showMe.value = ShowMeState(presentationMode)
            return
        }
        changingCamera = true
        try {
            if (previous == CameraMode.DUAL) {
                val dual = DualCameraCapture(context, requireNotNull(egl).eglBaseContext)
                dualCapture = dual
                check(dual.supported())
                dual.setTargetRotation(deviceOrientation.rotation)
                dual.start(requireNotNull(videoSource), requireNotNull(backSource))
                dual.setTargetRotation(deviceOrientation.rotation)
                backTrack?.setEnabled(true)
            } else { requireNotNull(camera).startCapture(1280, 720, 30); captureFormat = Triple(1280, 720, 30) }
            presentationMode = previous
            videoTrack?.setEnabled(true)
            showMe.value = ShowMeState(previous)
            appliedQuality = null
            applyQuality(qualityPolicy.current)
        } catch (_: Exception) {
            logger.error(AppEvent.RTC_MEDIA_FAILED)
            // Leaving the mode on the arrangement that failed to reopen would strand the user with
            // a disabled track and no button that brings an ordinary camera back.
            try { dualCapture?.close() } catch (_: Exception) { logger.error(AppEvent.RTC_MEDIA_FAILED) }
            dualCapture = null
            presentationMode = CameraMode.FACE
            videoTrack?.setEnabled(cameraEnabled)
            showMe.value = ShowMeState(CameraMode.FACE, "摄像头恢复失败，请重试")
        } finally { changingCamera = false }
    }

    private fun sendSharePresentation() {
        if (!sendControl(localShare.encode())) logger.error(AppEvent.RTC_MEDIA_FAILED)
    }

    /** Bounded text on the shared control channel. A peer that cannot parse a line ignores it. */
    private fun sendControl(text: String): Boolean {
        val channel = control ?: return false
        if (channel.state() != DataChannel.State.OPEN) return false
        val data = text.toByteArray(Charsets.UTF_8)
        if (data.size > 32) return false
        return channel.send(DataChannel.Buffer(java.nio.ByteBuffer.wrap(data), false))
    }

    /**
     * What this end is actually sending, not what it intends to send later. During a share every
     * camera is paused, so the peer is told a single paused camera: keeping a Show Me pair here
     * would leave two slots waiting for pictures that are not coming.
     */
    private fun sendPresentation() {
        val channel = control ?: return
        if (channel.state() != DataChannel.State.OPEN) return
        val mode = if (screenSharing) CameraMode.FACE else presentationMode
        val data = CameraPresentation(mode, cameraEnabled && !screenSharing).encode().toByteArray(Charsets.UTF_8)
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

    /** Called only after user-triggered ARCore preparation. UI must stop AR when leaving foreground. */
    suspend fun startAr(preparation: com.lazydoglab.zisee.ar.session.ArPreparation,
        rotation: Int, width: Int, height: Int): Boolean = withContext(dispatcher + kotlinx.coroutines.NonCancellable) {
        // §5.2: an AR field and a screen share are mutually exclusive, and the share owns the
        // cameras an AR field would need.
        if (released || changingCamera || arLeaseId != null || !cameraEnabled || screenSharing ||
            preparation != com.lazydoglab.zisee.ar.session.ArPreparation.READY ||
            rotation !in 0..3 || width <= 0 || height <= 0) return@withContext false
        val collaboration = arCollaboration ?: return@withContext false
        val previous = presentationMode
        val id = java.util.UUID.randomUUID()
        changingCamera = true
        arLeaseId = id
        arStopRequested = false
        mutableArState.value = com.lazydoglab.zisee.ar.session.ArSessionState.STARTING
        val starting = CompletableDeferred<Unit>()
        arStarting = starting
        val lease = com.lazydoglab.zisee.ar.session.ArCameraLease {
            // Never block the GL owner waiting for RTC; channel close can itself be awaiting GL.
            scope.launch { restoreAfterAr(id, previous) }
        }
        try {
            if (dualCapture != null) {
                dualCapture?.close(awaitCameraClosed = true)
                dualCapture = null
            } else releaseCamera(strict = true)
            if (released) { lease.close(); return@withContext false }
            videoTrack?.setEnabled(false)
            val capture = com.lazydoglab.zisee.ar.session.ArVideoCapture.start(context,
                requireNotNull(egl).eglBaseContext, requireNotNull(backSource), lease,
                rotation, width, height, onState = { state -> scope.launch {
                    if (arLeaseId == id && !released &&
                        mutableArState.value != com.lazydoglab.zisee.ar.session.ArSessionState.FAILED) mutableArState.value = state
                } }) { scope.launch {
                    mutableArState.value = com.lazydoglab.zisee.ar.session.ArSessionState.FAILED
                    stopAr()
                } }
            arCapture = capture
            if (released || arStopRequested || !collaboration.attach(capture)) {
                capture.close()
                return@withContext false
            }
            backTrack?.setEnabled(cameraEnabled)
            presentationMode = CameraMode.AR
            showMe.value = ShowMeState(CameraMode.AR)
            sendPresentation()
            true
        } catch (_: Exception) {
            logger.error(AppEvent.AR_CHANNEL_FAILED)
            mutableArState.value = com.lazydoglab.zisee.ar.session.ArSessionState.FAILED
            try { arCapture?.close() } catch (_: Exception) { logger.error(AppEvent.AR_CHANNEL_FAILED) }
            lease.close()
            false
        } finally {
            changingCamera = false
            arStarting = null
            starting.complete(Unit)
        }
    }

    suspend fun updateArGeometry(rotation: Int, width: Int, height: Int) = withContext(dispatcher) {
        arCapture?.setGeometry(rotation, width, height)
    }

    suspend fun joinRemoteAr(sessionId: java.util.UUID): Boolean =
        arCollaboration?.join(sessionId) ?: false

    suspend fun leaveRemoteAr() { arCollaboration?.leave() }

    suspend fun createArMarker(identity: com.lazydoglab.zisee.ar.render.ArFrameIdentity,
        id: java.util.UUID, kind: com.lazydoglab.zisee.ar.session.MarkerKind,
        point: com.lazydoglab.zisee.ar.annotation.VideoPoint): Boolean {
        val request = com.lazydoglab.zisee.ar.annotation.SpatialMarkerRequest(identity.reference, point)
        val local = arCapture
        return when {
            local != null && identity.sessionId == local.sessionId -> local.createLocalMarker(id, kind, request)
            arCollaboration?.state?.value?.remote?.sessionId == identity.sessionId ->
                arCollaboration?.create(id, kind, request) ?: false
            else -> false
        }
    }

    suspend fun removeArMarker(sessionId: java.util.UUID, id: java.util.UUID): Boolean {
        val local = arCapture
        return if (local != null && local.sessionId == sessionId) local.removeLocalMarker(id)
        else arCollaboration?.remove(id) ?: false
    }

    suspend fun clearFieldArMarkers(): Boolean {
        val local = arCapture ?: return false
        if (!local.clearLocalMarkers()) return false
        return arCollaboration?.announceFieldClear() ?: false
    }

    suspend fun revokeArGuide(): Boolean = arCollaboration?.revokeGuide() ?: false

    suspend fun stopAr() = withContext(dispatcher + kotlinx.coroutines.NonCancellable) {
        arStopRequested = true
        arStarting?.await()
        try { arCollaboration?.detach() }
        finally {
            arCapture?.close(); arCapture = null
            if (mutableArState.value != com.lazydoglab.zisee.ar.session.ArSessionState.FAILED)
                mutableArState.value = com.lazydoglab.zisee.ar.session.ArSessionState.CLOSED
        }
    }

    private suspend fun restoreAfterAr(id: java.util.UUID, previous: CameraMode) {
        arStarting?.await()
        if (arLeaseId != id) return
        arCapture = null
        arLeaseId = null
        if (released) return
        changingCamera = true
        val restoring = CompletableDeferred<Unit>()
        arStarting = restoring
        try {
            backTrack?.setEnabled(false)
            if (previous == CameraMode.DUAL) {
                val dual = DualCameraCapture(context, requireNotNull(egl).eglBaseContext)
                dualCapture = dual
                check(dual.supported())
                dual.setTargetRotation(deviceOrientation.rotation)
                dual.start(requireNotNull(videoSource), requireNotNull(backSource))
                dual.setTargetRotation(deviceOrientation.rotation)
                backTrack?.setEnabled(cameraEnabled)
            } else { requireNotNull(camera).startCapture(1280, 720, 30); captureFormat = Triple(1280, 720, 30) }
            presentationMode = previous
            videoTrack?.setEnabled(cameraEnabled)
            showMe.value = ShowMeState(previous)
            appliedQuality = null
            applyQuality(qualityPolicy.current)
            sendPresentation()
        } catch (_: Exception) {
            logger.error(AppEvent.RTC_MEDIA_FAILED)
            // A failed restore used to leave the mode on AR with the local track disabled, so
            // neither the retry notice nor the flip button could bring an ordinary camera back.
            presentationMode = previous
            videoTrack?.setEnabled(cameraEnabled)
            showMe.value = ShowMeState(previous, "摄像头恢复失败，请重试")
        } finally {
            changingCamera = false
            arStarting = null
            restoring.complete(Unit)
        }
    }

    suspend fun toggleShowMe(preferDual: Boolean = true) = withContext(dispatcher) {
        // A share owns every camera; the arrangement to come back to is chosen after it ends.
        if (released || changingCamera || !cameraEnabled || arLeaseId != null || screenSharing) return@withContext
        changingCamera = true
        showMe.value = ShowMeState(CameraMode.STARTING)
        try {
            if (presentationMode != CameraMode.FACE) {
                if (frontName == null) { showMe.value = ShowMeState(presentationMode, "此设备没有可用前摄"); return@withContext }
                dualCapture?.close(); dualCapture = null
                backTrack?.setEnabled(false)
                videoSource?.adaptOutputFormat(1920, 1080, 30)
                if (presentationMode == CameraMode.BACK_ONLY) switchSingle(requireNotNull(frontName))
                else { requireNotNull(camera).startCapture(1280, 720, 30); captureFormat = Triple(1280, 720, 30) }
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
                    if (stopped) { requireNotNull(camera).startCapture(1280, 720, 30); captureFormat = Triple(1280, 720, 30) }
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
    private suspend fun releaseCamera(strict: Boolean = false) {
        val closed = CompletableDeferred<Unit>()
        cameraClosed = closed
        try {
            requireNotNull(camera).stopCapture()
            // A capturer that was never started reports no close, so this must not be fatal.
            if (withTimeoutOrNull(3_000) { closed.await() } == null) {
                logger.info(AppEvent.RTC_SHOW_ME_FALLBACK, "camera_close_timeout")
                check(!strict) { "camera_close_timeout" }
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
    /**
     * The current route is about to go. Nothing can be migrated yet, but the picture can be made
     * cheap to resume before the break rather than after it: the first frame on whatever comes next
     * has to be a key frame, and this is the last moment it can be shrunk while the old path is
     * still carrying media.
     */
    suspend fun routeLosing() = withContext(dispatcher) {
        if (released || iceState.value != IceState.CONNECTED) return@withContext
        val nowMs = System.nanoTime() / 1_000_000
        handoverStatsUntilMs = nowMs + recoveryConfig.handoverStatsDurationMs
        qualityPolicy.routeChanged(nowMs)
        if (adaptationMode != VideoAdaptationMode.OFF) {
            cameraPolicy.routeChanged(nowMs)
            if (adaptationMode == VideoAdaptationMode.ACTIVE) applyCameraPlan(cameraPolicy.lastPlan)
        }
        applyQuality(qualityPolicy.current, changeCapture = false)
        statsWake.trySend(Unit)
        Unit
    }

    suspend fun networkChanged() = withContext(dispatcher) {
        val nowMs = System.nanoTime() / 1_000_000
        handoverStatsUntilMs = nowMs + recoveryConfig.handoverStatsDurationMs
        lastRouteChangeMs = nowMs
        // The old path's estimate and loss say nothing about the new one, and a suspension entered
        // on the old path must not outlive it.
        audioBandwidth.routeChanged(nowMs)
        qualityPolicy.routeChanged(nowMs)
        if (adaptationMode != VideoAdaptationMode.OFF) cameraPolicy.routeChanged(nowMs)
        handover.routeChanged(nowMs, appliedQuality)
        statsWake.trySend(Unit)
        Unit
    }

    override suspend fun restartIce() = withContext(dispatcher) {
        handover.restartRequested()
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
            arStopRequested = true
            arStarting?.await() // Keep sources/factory/EGL alive while GL startup is in flight.
            statsJob?.cancel(); statsJob = null
            // Before anything else: hanging up must not leave the user's screen being captured.
            screenStateJob?.cancel(); screenStateJob = null
            claimTimeout?.cancel(); claimTimeout = null
            ownership.disconnected()
            try { screenShare?.close() } catch (_: Exception) { logger.error(AppEvent.SCREEN_SHARE_FAILED) }
            screenShare = null
            withContext(Dispatchers.Main.immediate) {
                localFeed?.close(); remoteFeed?.close(); localBackFeed?.close(); remoteBackFeed?.close()
                localScreenFeed?.close(); remoteScreenFeed?.close()
            }
            // Attempt every release even if an OEM operation fails; log only an allowlisted event.
            fun cleanup(block: () -> Unit) { try { block() } catch (error: Exception) { logger.error(AppEvent.RTC_RELEASE_FAILED) } }
            try { dualCapture?.close() } catch (error: Exception) { logger.error(AppEvent.RTC_RELEASE_FAILED) }
            dualCapture = null
            try { arCollaboration?.close() } catch (error: Exception) { logger.error(AppEvent.AR_CHANNEL_FAILED) }
            arCollaboration = null
            try { arCapture?.close() } catch (_: Exception) { logger.error(AppEvent.AR_CHANNEL_FAILED) }
            arCapture = null
            cleanup { control?.unregisterObserver(); control?.close(); control?.dispose() }; control = null
            cleanup { deviceOrientation.close() }
            cleanup { cellularStandby.close() }
            cleanup { if (monitoring) { NetworkMonitor.getInstance().stopMonitoring(); monitoring = false } }
            cleanup { NetworkMonitor.removeNetworkObserver(networkObserver) }
            cleanup { camera?.stopCapture() }
            cleanup { remoteTrack?.removeSink(remoteFeed) }
            cleanup { remoteBackTrack?.removeSink(remoteBackFeed) }
            cleanup { remoteScreenTrack?.removeSink(remoteScreenFeed) }
            cleanup { backTrack?.removeSink(localBackFeed) }
            cleanup { screenTrack?.removeSink(localScreenFeed) }
            cleanup { videoTrack?.removeSink(localFeed) }
            cleanup { peer?.close() }
            cleanup { peer?.dispose() }; peer = null; videoSender = null; audioSender = null; screenSender = null
            cleanup { camera?.dispose() }; camera = null
            cleanup { videoTrack?.dispose() }; videoTrack = null
            cleanup { audioTrack?.dispose() }; audioTrack = null
            cleanup { backTrack?.dispose() }; backTrack = null
            cleanup { screenTrack?.dispose() }; screenTrack = null
            cleanup { backSource?.dispose() }; backSource = null
            // The share session borrows this source, so it is only safe to dispose after its close.
            cleanup { screenSource?.dispose() }; screenSource = null
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
            if (connected && connectedSinceMs == Long.MAX_VALUE) connectedSinceMs = System.nanoTime() / 1_000_000
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
            val slot = when (local ?: track.id()) {
                MediaTrack.BACK_CAMERA.wireId -> MediaTrack.BACK_CAMERA
                MediaTrack.SCREEN.wireId -> MediaTrack.SCREEN
                else -> MediaTrack.FRONT_CAMERA
            }
            logger.info(AppEvent.RTC_TRACK_ROUTED, "${slot.name} msid_matched=${local == track.id()}")
            scope.launch { if (!released) when (slot) {
                MediaTrack.BACK_CAMERA -> {
                    remoteBackTrack?.removeSink(remoteBackFeed); remoteBackTrack = track; track.addSink(remoteBackFeed)
                }
                MediaTrack.SCREEN -> {
                    remoteScreenTrack?.removeSink(remoteScreenFeed); remoteScreenTrack = track; track.addSink(remoteScreenFeed)
                }
                else -> { remoteTrack?.removeSink(remoteFeed); remoteTrack = track; track.addSink(remoteFeed) }
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
        private val TERMINAL_SCREEN_PHASES = setOf(
            com.lazydoglab.zisee.screen.ScreenSharePhase.STOPPED,
            com.lazydoglab.zisee.screen.ScreenSharePhase.FAILED,
            com.lazydoglab.zisee.screen.ScreenSharePhase.CLOSED,
        )
        private const val MONITOR_TAG = "Zisee"
        private const val NETWORK_WAIT_MS = 2_000L
        private const val LOOPBACK_ADAPTER = 1 shl 4
        // Low enough that a genuinely slower route sheds it in a second, high enough to skip the
        // slow opening ramp; the per-sender ceilings still bound what the encoder does with it.
        // ICE promotes its way to the best pair over the first seconds of a call.
        private const val SETTLED_MS = 3_000L
        // How long after the network changed a new pair still belongs to that handover.
        private const val HANDOVER_WINDOW_MS = 5_000L
        // Long enough for reflexive and relay gathering to have finished on every interface.
        private const val BACKUP_CHECK_MS = 8_000L
        // Long enough for the direct pairs to have finished their checks after a switch.
        private const val RELAY_CHECK_MS = 5_000L
        private const val MIN_SEED_BPS = 300_000L
        private const val MAX_SEED_BPS = 1_000_000L
        private const val SEED_COOLDOWN_MS = 10_000L
        @Volatile private var networksSeen = false
        private var initialized = false
        private val throttledAt = java.util.concurrent.ConcurrentHashMap<AppEvent, Long>()
        /** One line per event per five seconds: a burst of these says nothing a single line does not. */
        private fun throttled(event: AppEvent, logger: AppLogger) {
            val now = System.nanoTime() / 1_000_000
            val previous = throttledAt[event]
            if (previous != null && now - previous < 5_000) return
            throttledAt[event] = now
            logger.info(event)
        }
        @Synchronized private fun initialize(context: Context, logger: AppLogger) {
            if (initialized) return
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .setInjectableLogger({ message, severity, _ ->
                    if (severity == Logging.Severity.LS_ERROR) {
                        // Three of these are normal operation reported at error level, and left
                        // alone they bury the ones that are not. A relay refuses to open a
                        // permission toward a peer's private address, which is what it should do
                        // and what every same-LAN peer produces; sending on an interface that has
                        // just gone away is the handover itself; and the data channel back
                        // compatibility notice is not a failure at all. They stay visible as
                        // bounded, throttled facts rather than as errors.
                        val expected = message.contains("CreatePermission") ||
                            message.contains("failed with error 101") ||
                            message.contains("for backwards compatibility")
                        if (expected) {
                            when {
                                message.contains("CreatePermission") ->
                                    throttled(AppEvent.RTC_TURN_PERMISSION_PRUNED, logger)
                                message.contains("failed with error 101") ->
                                    throttled(AppEvent.RTC_ROUTE_UNREACHABLE, logger)
                            }
                            if (BuildConfig.DEBUG) Log.e("ZiseeNative", message.take(400))
                            return@setInjectableLogger
                        }
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
