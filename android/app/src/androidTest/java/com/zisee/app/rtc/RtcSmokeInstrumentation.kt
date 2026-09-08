package com.zisee.app.rtc

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import com.zisee.app.core.logging.AppEvent
import com.zisee.app.core.logging.AppLogger
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.webrtc.*

/** Explicit local media smoke, no account/server/TURN or extra test dependency required. */
class RtcSmokeInstrumentation : Instrumentation() {
    private var expectedQuality: String? = null
    private var preview = false
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        expectedQuality = arguments?.getString("expectedQuality")
        preview = arguments?.getString("preview") == "true"
        start()
    }

    override fun onStart() {
        val output = Bundle()
        try {
            if (preview) {
                for (scene in listOf(false, true)) {
                    val activity = startActivitySync(android.content.Intent().setClassName(targetContext.packageName,
                        "com.zisee.app.ui.CallPreviewActivity").putExtra("scene", scene).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
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
            runBlocking { withTimeout(40_000) { smoke(output) } }
            output.putString("stream", "PASS: native camera, ICE, encode/decode, sender ceilings and release\n")
            finish(Activity.RESULT_OK, output)
        } catch (error: Exception) {
            // No SDP, addresses, media or device identity in reports.
            output.putString("stream", "FAIL: ${error.javaClass.simpleName}\n")
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
        val candidates = ConcurrentLinkedQueue<IceCandidate>()
        try {
            session.start(emptyList()) // Loopback host candidates only; this does not validate TURN.
            egl = EglBase.create()
            factory = PeerConnectionFactory.builder().setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
                .createPeerConnectionFactory()
            receiver = requireNotNull(factory.createPeerConnection(PeerConnection.RTCConfiguration(emptyList()).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            }, object : PeerConnection.Observer {
                override fun onTrack(transceiver: RtpTransceiver) {
                    (transceiver.receiver.track() as? VideoTrack)?.addSink {
                        firstFrameNanos.compareAndSet(0, System.nanoTime())
                        receivedFrames.incrementAndGet()
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
            check(session.iceState.value == IceState.CONNECTED)
            check(AppEvent.RTC_QUALITY_CHANGED in events && AppEvent.RTC_QUALITY_REJECTED !in events)
            expectedQuality?.let { check(session.mediaStats.value.quality.name == it) }
            output.putLong("firstDecodedFrameMs", (firstFrameNanos.get() - started) / 1_000_000)
            output.putLong("decoded30FramesMs", (System.nanoTime() - started) / 1_000_000)
            output.putString("qualityCeiling", session.mediaStats.value.quality.name)
            output.putInt("sentWidth", session.mediaStats.value.sentWidth)
            output.putInt("sentHeight", session.mediaStats.value.sentHeight)
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
            val beforeSwitch = receivedFrames.get()
            session.toggleShowMe(preferDual = false)
            output.putString("rearMode", session.showMe.value.mode.name)
            output.putString("cameraEvents", events.joinToString { it.name })
            check(session.showMe.value.mode == CameraMode.BACK_ONLY)
            withTimeout(5_000) { while (receivedFrames.get() < beforeSwitch + 10) delay(100) }
            session.toggleShowMe(preferDual = false)
            check(session.showMe.value.mode == CameraMode.FACE)
            val afterSwitch = receivedFrames.get()
            withTimeout(5_000) { while (receivedFrames.get() < afterSwitch + 10) delay(100) }
            output.putString("cameraSwitch", "PASS: rear and front decoded without renegotiation")

        } finally {
            withContext(NonCancellable) {
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
