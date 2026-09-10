package com.lazydoglab.zisee.rtc

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import com.lazydoglab.zisee.core.logging.AppEvent
import com.lazydoglab.zisee.core.logging.AppLogger
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.webrtc.*

/** Explicit local media smoke, no account/server/TURN or extra test dependency required. */
class RtcSmokeInstrumentation : Instrumentation() {
    private var expectedQuality: String? = null
    private var preview = false
    private var thumbnailSwaps = false
    private var arTap = false
    private var computeQuality = false
    private var capabilities = false
    private var orientationPreview = false
    private var callUiPreview = false
    private var cameraTransform = false
    private var arChannel = false
    private var arCameraRender = false
    private var arFramePool = false
    private var arVideoIdentity = false
    private var arDisplayedIdentity = false
    private var waitForForeground = false
    private var arCameraTakeover = false
    private var audio = false
    private var repetitions = 1
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        expectedQuality = arguments?.getString("expectedQuality")
        preview = arguments?.getString("preview") == "true"
        thumbnailSwaps = arguments?.getString("thumbnailSwaps") == "true"
        arTap = arguments?.getString("arTap") == "true"
        computeQuality = arguments?.getString("computeQuality") == "true"
        capabilities = arguments?.getString("capabilities") == "true"
        orientationPreview = arguments?.getString("orientationPreview") == "true"
        callUiPreview = arguments?.getString("callUiPreview") == "true"
        cameraTransform = arguments?.getString("cameraTransform") == "true"
        arChannel = arguments?.getString("arChannel") == "true"
        arCameraRender = arguments?.getString("arCameraRender") == "true"
        arFramePool = arguments?.getString("arFramePool") == "true"
        arVideoIdentity = arguments?.getString("arVideoIdentity") == "true"
        arDisplayedIdentity = arguments?.getString("arDisplayedIdentity") == "true"
        waitForForeground = arguments?.getString("waitForForeground") == "true"
        arCameraTakeover = arguments?.getString("arCameraTakeover") == "true"
        audio = arguments?.getString("audio") == "true"
        repetitions = arguments?.getString("repeat")?.toIntOrNull()?.coerceIn(1, 3) ?: 1
        start()
    }

    override fun onStart() {
        val output = Bundle()
        try {
            if (computeQuality) {
                ComputeOesSmoke.run()
                ComputeQualitySmoke.run(targetContext)
                output.putString("stream", "PASS: synthetic GPU pixels/scaling/denoise, metadata, pool exhaustion, C0/AR bypass and retained cleanup; controlled clock verifies deadline fallback, not GPU performance\n")
                finish(Activity.RESULT_OK, output)
                return
            }
            if (arTap) {
                val result = CallArTapSmoke.run(this)
                output.putString("stream", "PASS: AR tap reached the marker layer\n$result\n")
                finish(Activity.RESULT_OK, output)
                return
            }
            if (thumbnailSwaps) {
                CallThumbnailSmoke.run(this)
                output.putString("stream", "PASS: all four thumbnail swaps, outlines and dragged position across auto-hide; synthetic frames\n")
                finish(Activity.RESULT_OK, output)
                return
            }
            if (arCameraTakeover) {
                runBlocking {
                    val activity = ArTestActivityLauncher.open(this@RtcSmokeInstrumentation, waitForForeground)
                    try {
                        val availability = CompletableDeferred<com.lazydoglab.zisee.ar.session.ArAvailability>()
                        runOnMainSync { com.lazydoglab.zisee.ar.session.ArCoreAvailability.check(activity) { availability.complete(it) } }
                        val supported = withTimeout(5_000) { availability.await() }
                        output.putString("arAvailability", supported.name)
                        check(supported == com.lazydoglab.zisee.ar.session.ArAvailability.READY)
                        var preparation = com.lazydoglab.zisee.ar.session.ArPreparation.RETRY
                        runOnMainSync { preparation = com.lazydoglab.zisee.ar.session.ArCoreAvailability.prepare(activity, false) }
                        output.putString("arPreparation", preparation.name)
                        check(preparation == com.lazydoglab.zisee.ar.session.ArPreparation.READY)
                        withTimeout(120_000) { smoke(output) }
                    } finally { runOnMainSync { activity.finish() } }
                }
                // am instrument prints only the stream, so a bare PASS hid which modes actually ran.
                val modes = output.keySet().filter { it.startsWith("arFrom") }.sorted()
                    .joinToString("\n") { "  $it: ${output.getString(it)}" }
                output.putString("stream",
                    "PASS: physical ARCore camera takeover and decoded AR identities\n$modes\n")
                finish(Activity.RESULT_OK, output)
                return
            }
            if (arVideoIdentity || arDisplayedIdentity) {
                ArVideoIdentitySmoke.run(targetContext, this, arDisplayedIdentity, waitForForeground)
                output.putString("stream", "PASS: synthetic AR RGB source through native H264 RTP and decoder with exact source identity; surface assertion=$arDisplayedIdentity\n")
                finish(Activity.RESULT_OK, output)
                return
            }
            if (arFramePool) {
                ArFramePoolSmoke.run(targetContext)
                output.putString("stream", "PASS: retained RGB pool, exhaustion, pixel stability and deferred GL cleanup\n")
                finish(Activity.RESULT_OK, output)
                return
            }
            if (arCameraRender) {
                ArCameraRenderSmoke.run()
                output.putString("stream", "PASS: synthetic OES camera GPU rendering, marker overlay and CPU-image corner orientation; no ARCore camera validation\n")
                finish(Activity.RESULT_OK, output)
                return
            }
            if (arChannel) {
                ArChannelSmoke.run(targetContext)
                output.putString("stream", "PASS: paired native AR data channels, join/create/result/clear/leave/ended and isolated cleanup; synthetic field only\n")
                finish(Activity.RESULT_OK, output)
                return
            }
            if (cameraTransform) {
                CameraTextureTransformSmoke.run()
                output.putString("stream", "PASS: camera texture corner mappings, sensor/display rotations and processed surface bypass; no physical camera validation\n")
                finish(Activity.RESULT_OK, output)
                return
            }
            if (audio) {
                AudioSmoke.run(targetContext, output)
                output.putString("stream", "PASS: native model, finite output, repeated lifecycle; synthetic input only\n")
                finish(Activity.RESULT_OK, output)
                return
            }
            if (callUiPreview) {
                val files = mutableListOf<String>()
                val scenarios = listOf("normal", "muted-camera-off", "long-name", "sharing-starting",
                    "sharing-active", "remote-sharing", "ar-notice", "more")
                try {
                    for ((rotation, orientation) in listOf(
                        android.app.UiAutomation.ROTATION_FREEZE_0 to android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                        android.app.UiAutomation.ROTATION_FREEZE_90 to android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)) {
                        check(uiAutomation.setRotation(rotation))
                        val suffix = if (orientation == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) "portrait" else "landscape"
                        for (scenario in scenarios) {
                            val activity = startActivitySync(android.content.Intent().setClassName(targetContext.packageName,
                                "com.lazydoglab.zisee.ui.CallPreviewActivity")
                                .putExtra("callUiScenario", scenario)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                            try {
                                waitForIdleSync()
                                runOnMainSync { activity.requestedOrientation = orientation }
                                waitForIdleSync()
                                android.os.SystemClock.sleep(700)
                                val screenshot = requireNotNull(uiAutomation.takeScreenshot())
                                val name = "call-ui-$scenario-$suffix.png"
                                try {
                                    check(if (orientation == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
                                        screenshot.height > screenshot.width else screenshot.width > screenshot.height)
                                    java.io.File(targetContext.getExternalFilesDir(null), name).outputStream().use {
                                        check(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                                    }
                                    files += name
                                } finally { screenshot.recycle() }
                            } finally { runOnMainSync { activity.finish() } }
                        }
                    }
                } finally { uiAutomation.setRotation(android.app.UiAutomation.ROTATION_UNFREEZE) }
                output.putString("stream", "PASS: synthetic call UI captures; no media or ARCore\n" +
                    files.joinToString("\n") { "  $it" } + "\n")
                finish(Activity.RESULT_OK, output)
                return
            }
            if (orientationPreview) {
                try {
                    for (mode in listOf("face", "mine", "both")) {
                        for (viewer in 0..1) for (remoteRotation in listOf(90, 0)) {
                            check(uiAutomation.setRotation(viewer))
                            val activity = startActivitySync(android.content.Intent().setClassName(targetContext.packageName,
                                "com.lazydoglab.zisee.ui.CallPreviewActivity")
                                .putExtra("scene", mode == "both").putExtra("mine", mode == "mine")
                                .putExtra("localRotation", if (viewer == 0) 90 else 0)
                                .putExtra("remoteRotation", remoteRotation)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                            try {
                                waitForIdleSync()
                                // ActiveCall requests FULL_USER on entry. Override only in this
                                // synthetic fixture after composition so the requested matrix is real.
                                runOnMainSync {
                                    activity.requestedOrientation = if (viewer == 0)
                                        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                    else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                                }
                                waitForIdleSync()
                                android.os.SystemClock.sleep(1_000)
                                val screenshot = requireNotNull(uiAutomation.takeScreenshot())
                                try {
                                    check(if (viewer == 0) screenshot.height > screenshot.width else screenshot.width > screenshot.height)
                                    java.io.File(targetContext.getExternalFilesDir(null), "orientation-$mode-$viewer-$remoteRotation.png")
                                        .outputStream().use { check(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
                                } finally { screenshot.recycle() }
                            } finally { runOnMainSync { activity.finish() } }
                        }
                    }
                } finally { uiAutomation.setRotation(android.app.UiAutomation.ROTATION_UNFREEZE) }
                output.putString("stream", "PASS: 12 synthetic orientation layout captures; not a camera orientation test\n")
                finish(Activity.RESULT_OK, output)
                return
            }
            if (preview) {
                for (scene in listOf(false, true)) {
                    val activity = startActivitySync(android.content.Intent().setClassName(targetContext.packageName,
                        "com.lazydoglab.zisee.ui.CallPreviewActivity").putExtra("scene", scene).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    waitForIdleSync()
                    android.os.SystemClock.sleep(1_000)
                    val screenshot = requireNotNull(uiAutomation.takeScreenshot())
                    java.io.File(targetContext.getExternalFilesDir(null), if (scene) "show-me.png" else "face-call.png").outputStream().use {
                        check(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                    }
                    screenshot.recycle()
                    runOnMainSync { activity.finish() }
                }
                output.putString("stream", "PASS: Face Call and Show Me layout captures\n")
                finish(Activity.RESULT_OK, output)
                return
            }
            if (capabilities) {
                // Show Me falls back to a single rear camera for three different reasons. This
                // reports the device half of that decision on its own, without a call.
                runBlocking {
                    withTimeout(30_000) {
                        val egl = EglBase.create()
                        try {
                            val capture = DualCameraCapture(targetContext, egl.eglBaseContext)
                            output.putString("concurrentFrontBack", capture.supported().toString())
                            capture.close()
                        } finally { egl.release() }
                    }
                }
                val enumerator: CameraEnumerator = if (Camera2Enumerator.isSupported(targetContext))
                    Camera2Enumerator(targetContext) else Camera1Enumerator(true)
                output.putString("frontCamera", enumerator.deviceNames.count { enumerator.isFrontFacing(it) }.toString())
                output.putString("rearCamera", enumerator.deviceNames.count { enumerator.isBackFacing(it) }.toString())
                output.putString("stream", "PASS: concurrent camera capability probe\n")
                finish(Activity.RESULT_OK, output)
                return
            }
            runBlocking { withTimeout(40_000L * repetitions) { repeat(repetitions) { smoke(output) } } }
            output.putInt("completedCalls", repetitions)
            output.putString("stream", "PASS: native camera, ICE, encode/decode, sender ceilings and release\n")
            finish(Activity.RESULT_OK, output)
        } catch (error: Exception) {
            // No SDP, addresses, media or device identity in reports. The class name alone could
            // not locate a failed check, so name the stage and our own topmost frame as well.
            val origin = error.stackTrace.firstOrNull { it.className.startsWith("com.lazydoglab.zisee") }
            val detail = output.keySet().filter { it.startsWith("ar") }.joinToString { "$it=${output.get(it)}" }
            output.putString("stream", "FAIL: ${error.javaClass.simpleName} at ${origin ?: "unknown"}" +
                // Only our own precondition text; a library message could carry SDP or addresses.
                (error.takeIf { it is IllegalStateException }?.message?.let { " ($it)" } ?: "") + "\n$detail\n")
            finish(Activity.RESULT_CANCELED, output)
        }
    }

    private suspend fun smoke(output: Bundle) {
        val events = ConcurrentLinkedQueue<AppEvent>()
        val logger = object : AppLogger {
            override fun error(event: AppEvent, reason: String?) { events.add(event) }
            override fun info(event: AppEvent, detail: String?) { events.add(event) }
        }
        val session = NativeRtcSession(targetContext, logger)
        var receiver: PeerConnection? = null
        var factory: PeerConnectionFactory? = null
        var egl: EglBase? = null
        val receivedFrames = AtomicInteger()
        val firstFrameNanos = AtomicLong()
        val arFrames = AtomicInteger()
        val ordinaryFrames = AtomicInteger()
        val peerControls = mutableListOf<DataChannel>()
        val candidates = ConcurrentLinkedQueue<IceCandidate>()
        try {
            session.start(emptyList()) // Loopback host candidates only; this does not validate TURN.
            egl = EglBase.create()
            factory = PeerConnectionFactory.builder().setVideoDecoderFactory(
                if (arCameraTakeover) com.lazydoglab.zisee.ar.render.ArDecoderFactory(egl.eglBaseContext)
                else DefaultVideoDecoderFactory(egl.eglBaseContext))
                .createPeerConnectionFactory()
            receiver = requireNotNull(factory.createPeerConnection(PeerConnection.RTCConfiguration(emptyList()).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            }, object : PeerConnection.Observer {
                override fun onTrack(transceiver: RtpTransceiver) {
                    (transceiver.receiver.track() as? VideoTrack)?.addSink {
                        firstFrameNanos.compareAndSet(0, System.nanoTime())
                        receivedFrames.incrementAndGet()
                        if ((it.buffer as? com.lazydoglab.zisee.ar.render.ArTextureBuffer)?.identity != null)
                            arFrames.incrementAndGet() else ordinaryFrames.incrementAndGet()
                    }
                }
                override fun onIceCandidate(candidate: IceCandidate) { candidates.add(candidate) }
                override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
                override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
                override fun onAddStream(stream: MediaStream) = Unit
                override fun onRemoveStream(stream: MediaStream) = Unit
                override fun onDataChannel(channel: DataChannel) { channel.dispose() }
                override fun onRenegotiationNeeded() = Unit
            }))
            if (arCameraTakeover) {
                peerControls.add(receiver.createDataChannel("camera-state", DataChannel.Init().apply { negotiated = true; id = 0 }))
                peerControls.add(receiver.createDataChannel("zisee-ar-v1", DataChannel.Init().apply { negotiated = true; id = 2 }))
            }
            val started = System.nanoTime()
            val offer = session.localDescription(true)
            output.putLong("offerMs", (System.nanoTime() - started) / 1_000_000)
            set(receiver, offer, false)
            val answer = suspendCancellableCoroutine<SessionDescription> { continuation ->
                receiver.createAnswer(object : DescriptionObserver() {
                    override fun onCreateSuccess(sdp: SessionDescription) { if (continuation.isActive) continuation.resume(sdp) }
                    override fun onCreateFailure(error: String) { if (continuation.isActive) continuation.resumeWithException(IllegalStateException()) }
                }, MediaConstraints())
            }
            set(receiver, answer, true)
            session.remoteDescription("answer", answer.description)
            var sent = 0
            while (receivedFrames.get() < 30 || session.mediaStats.value.sentWidth == 0) {
                val local = session.localCandidates()
                while (sent < local.size) check(receiver.addIceCandidate(local[sent++]))
                while (true) session.addRemoteCandidate(candidates.poll() ?: break)
                delay(100)
            }
            output.putString("arIceStateBeforeWait", session.iceState.value.name)
            // Frame delivery can beat the StateFlow assignment posted by the native ICE callback.
            withTimeout(2_000) { session.iceState.first { it == IceState.CONNECTED } }
            val qualityEvent = if (VideoAdaptationMode.parse(com.lazydoglab.zisee.BuildConfig.VIDEO_ADAPTATION) == VideoAdaptationMode.ACTIVE)
                AppEvent.RTC_ADAPTATION_PLAN else AppEvent.RTC_QUALITY_CHANGED
            check(qualityEvent in events && AppEvent.RTC_QUALITY_REJECTED !in events) {
                "Expected $qualityEvent and no rejection; events=${events.distinct()}"
            }
            expectedQuality?.let { check(session.mediaStats.value.quality.name == it) }
            if (com.lazydoglab.zisee.BuildConfig.VIDEO_COMPUTE_QUALITY) {
                val compute = requireNotNull(session.mediaStats.value.computeQuality["front"])
                check((Regex("frames=(\\d+)").find(compute)?.groupValues?.get(1)?.toLongOrNull() ?: 0) > 0)
                output.putString("computeQuality", compute)
            }
            check(session.mediaStats.value.audioProcessing.fallback != com.lazydoglab.zisee.rtc.audio.processing.AudioFallback.FORMAT)
            output.putString("audioProcessing", session.mediaStats.value.audioProcessing.toString())
            output.putString("audioCodec", session.mediaStats.value.audio.codec)
            for (mode in com.lazydoglab.zisee.rtc.audio.processing.NoiseSuppressionMode.entries) {
                session.setNoiseSuppression(mode)
                check(session.mediaStats.value.audioProcessing.mode == mode)
                check(session.mediaStats.value.audioProcessing.fallback != com.lazydoglab.zisee.rtc.audio.processing.AudioFallback.CONFIGURATION)
            }
            output.putString("audioModes", "PASS: OFF, STANDARD, AI, AUTO without renegotiation")
            output.putLong("firstDecodedFrameMs", (firstFrameNanos.get() - started) / 1_000_000)
            output.putLong("decoded30FramesMs", (System.nanoTime() - started) / 1_000_000)
            output.putString("qualityCeiling", session.mediaStats.value.quality.name)
            output.putInt("sentWidth", session.mediaStats.value.sentWidth)
            output.putInt("sentHeight", session.mediaStats.value.sentHeight)
            if (arCameraTakeover) {
                suspend fun takeover(mode: CameraMode) {
                    output.putString("arStage", "start_${mode.name}")
                    val before = arFrames.get()
                    check(session.startAr(com.lazydoglab.zisee.ar.session.ArPreparation.READY, 0, 320, 240))
                    output.putString("arStage", "frames_${mode.name}")
                    withTimeout(12_000) { while (arFrames.get() < before + 5) delay(50) }
                    check(session.showMe.value.mode == CameraMode.AR)
                    session.updateArGeometry(1, 240, 320)
                    val rotated = arFrames.get()
                    withTimeout(6_000) { while (arFrames.get() < rotated + 5) delay(50) }
                    session.stopAr()
                    output.putString("arStage", "restore_${mode.name}")
                    withTimeout(8_000) { while (session.showMe.value.mode != mode) delay(50) }
                    // A failed restore reports the previous mode too; only the notice separates them.
                    check(session.showMe.value.message.isEmpty()) { session.showMe.value.message }
                    delay(500) // Do not count frames already in the receiver queue before restoration.
                    val restored = ordinaryFrames.get()
                    withTimeout(6_000) { while (ordinaryFrames.get() < restored + 10) delay(50) }
                    check(session.iceState.value == IceState.CONNECTED)
                    output.putString("arFrom${mode.name}", "PASS: capture, rotation update, source identity and restored decoded video")
                }
                takeover(session.showMe.value.mode)
                if (session.hasFrontCamera) {
                    session.toggleShowMe(false)
                    check(session.showMe.value.mode == CameraMode.BACK_ONLY)
                    takeover(CameraMode.BACK_ONLY)
                    session.toggleShowMe(false)
                    session.toggleShowMe(true)
                    if (session.showMe.value.mode == CameraMode.DUAL) takeover(CameraMode.DUAL)
                    else output.putString("arFromDUAL", "SKIP: concurrent capture unavailable; single rear fallback")
                }
                return
            }
            val beforeRestart = receivedFrames.get()
            session.prepareIceGeneration(emptyList())
            session.restartIce()
            session.localDescription(true) // Simulate an offer whose answer was lost.
            session.prepareIceGeneration(emptyList()) // Must roll back the outstanding local offer.
            session.restartIce()
            val restarted = session.localDescription(true)
            fun ufrag(sdp: SessionDescription) = sdp.description.lineSequence().first { it.startsWith("a=ice-ufrag:") }
            check(ufrag(offer) != ufrag(restarted))
            candidates.clear()
            set(receiver, restarted, false)
            val restartAnswer = suspendCancellableCoroutine<SessionDescription> { continuation ->
                receiver.createAnswer(object : DescriptionObserver() {
                    override fun onCreateSuccess(sdp: SessionDescription) { if (continuation.isActive) continuation.resume(sdp) }
                    override fun onCreateFailure(error: String) { if (continuation.isActive) continuation.resumeWithException(IllegalStateException()) }
                }, MediaConstraints())
            }
            set(receiver, restartAnswer, true)
            session.remoteDescription("answer", restartAnswer.description)
            sent = 0
            while (receivedFrames.get() < beforeRestart + 30 || session.iceState.value != IceState.CONNECTED) {
                val local = session.localCandidates()
                while (sent < local.size) check(receiver.addIceCandidate(local[sent++]))
                while (true) session.addRemoteCandidate(candidates.poll() ?: break)
                delay(100)
            }
            output.putString("iceRestart", "PASS: new credentials and continued decoded frames")
            output.putString("cameraEvents", events.joinToString { it.name })
            // An emulator image without a front camera starts rear-only and can never leave it, so
            // there is no switch to exercise. Asserting one turned a device limit into a failure.
            if (!session.hasFrontCamera) {
                output.putString("cameraSwitch", "SKIP: device has no front camera")
            } else {
                val beforeSwitch = receivedFrames.get()
                session.toggleShowMe(preferDual = false)
                output.putString("rearMode", session.showMe.value.mode.name)
                check(session.showMe.value.mode == CameraMode.BACK_ONLY)
                withTimeout(5_000) { while (receivedFrames.get() < beforeSwitch + 10) delay(100) }
                session.toggleShowMe(preferDual = false)
                // A refusal carries its reason in the state; report it rather than only that it failed.
                output.putString("frontMode", session.showMe.value.mode.name)
                output.putString("frontMessage", session.showMe.value.message)
                check(session.showMe.value.mode == CameraMode.FACE)
                val afterSwitch = receivedFrames.get()
                withTimeout(5_000) { while (receivedFrames.get() < afterSwitch + 10) delay(100) }
                output.putString("cameraSwitch", "PASS: rear and front decoded without renegotiation")
            }

        } finally {
            withContext(NonCancellable) {
                peerControls.forEach { it.close(); it.dispose() }
                try { receiver?.dispose() }
                finally {
                    try { factory?.dispose() }
                    finally { try { egl?.release() } finally { session.release() } }
                }
            }
        }
    }

    private suspend fun set(pc: PeerConnection, sdp: SessionDescription, local: Boolean) =
        suspendCancellableCoroutine<Unit> { continuation ->
            val observer = object : DescriptionObserver() {
                override fun onSetSuccess() { if (continuation.isActive) continuation.resume(Unit) }
                override fun onSetFailure(error: String) { if (continuation.isActive) continuation.resumeWithException(IllegalStateException()) }
            }
            if (local) pc.setLocalDescription(observer, sdp) else pc.setRemoteDescription(observer, sdp)
        }

    private open class DescriptionObserver : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) = Unit
        override fun onCreateFailure(error: String) = Unit
        override fun onSetSuccess() = Unit
        override fun onSetFailure(error: String) = Unit
    }
}
