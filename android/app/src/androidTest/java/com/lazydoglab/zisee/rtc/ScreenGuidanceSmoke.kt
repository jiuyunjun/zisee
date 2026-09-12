package com.lazydoglab.zisee.rtc

import android.content.Context
import android.provider.Settings
import com.lazydoglab.zisee.screen.*
import com.lazydoglab.zisee.ar.annotation.VideoPoint
import kotlinx.coroutines.*
import org.webrtc.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** Actual SCTP and overlay windows, synthetic capture geometry; no projection grant is fabricated. */
internal object ScreenGuidanceSmoke {
    fun run(context: Context) = runBlocking {
        check(Settings.canDrawOverlays(context)) { "Grant overlay permission to the test app first" }
        withContext(Dispatchers.Main) {
            val canvas = GuidanceCanvas(context).apply {
                layout(0, 0, 1000, 1000)
                state = GuidanceState("geometry", 3, 100, 200, overlay = true, connected = true)
                tool = GuidanceTool.POINTER
            }
            var input: GuidanceInput? = null; canvas.onPut = { input = it }
            fun touch(action: Int, x: Float, y: Float): Boolean {
                val event = android.view.MotionEvent.obtain(0, 1, action, x, y, 0)
                return try { canvas.onTouchEvent(event) } finally { event.recycle() }
            }
            check(!touch(android.view.MotionEvent.ACTION_DOWN, 20f, 250f)) // Letterbox.
            check(touch(android.view.MotionEvent.ACTION_DOWN, 500f, 250f))
            touch(android.view.MotionEvent.ACTION_UP, 500f, 250f)
            check(input?.points == listOf(VideoPoint(.5f, .25f)) && input?.geometry == 3)
        }
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        val factory = PeerConnectionFactory.builder().setOptions(PeerConnectionFactory.Options().apply { disableNetworkMonitor = true }).createPeerConnectionFactory()
        val candidates = List(2) { ConcurrentLinkedQueue<IceCandidate>() }
        val peers = mutableListOf<PeerConnection>()
        val sessions = mutableListOf<ScreenGuidance>()
        val failures = AtomicInteger()
        val pauses = AtomicInteger()
        try {
            repeat(2) { index ->
                val peer = requireNotNull(factory.createPeerConnection(PeerConnection.RTCConfiguration(emptyList()), object : PeerConnection.Observer {
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
                sessions.add(ScreenGuidance(context, { failures.incrementAndGet() }, { pauses.incrementAndGet() }, {}).also {
                    it.attach(peer.createDataChannel("screen-guidance-v1", DataChannel.Init().apply { negotiated = true; id = 6; ordered = true }))
                })
            }
            withTimeout(20_000) {
                val offer = ArChannelSmoke.description(peers[0], true)
                ArChannelSmoke.set(peers[0], offer, true); ArChannelSmoke.set(peers[1], offer, false)
                val answer = ArChannelSmoke.description(peers[1], false)
                ArChannelSmoke.set(peers[1], answer, true); ArChannelSmoke.set(peers[0], answer, false)
                while (sessions.any { !it.state.value.connected }) {
                    repeat(2) { index -> while (true) { val candidate = candidates[index].poll() ?: break
                        check(peers[1-index].addIceCandidate(candidate)) } }
                    delay(20)
                }
                val field = sessions[0]; val guide = sessions[1]
                guide.remoteSession("smoke"); field.configure("smoke", ScreenSize(1080, 2400))
                while (!guide.state.value.overlay) delay(10)
                val points = listOf(VideoPoint(.2f, .3f), VideoPoint(.6f, .7f))
                guide.put("guide", GuidanceTool.ARROW, points)
                while (guide.state.value.marks.size != 1) delay(10)
                field.put("field", GuidanceTool.CIRCLE, points)
                while (guide.state.value.marks.size != 2) delay(10)
                field.command(GuidanceOp.UNDO)
                while (guide.state.value.marks.size != 1) delay(10)
                check(guide.state.value.marks.single().id == "guide")
                field.setPaused(true); while (!guide.state.value.paused) delay(10)
                guide.put("blocked", GuidanceTool.NUMBER, points); delay(100)
                check(field.state.value.marks.size == 1)
                field.setPaused(false); while (guide.state.value.paused) delay(10)
                guide.put("pointer", GuidanceTool.POINTER, listOf(points.first()))
                while (guide.state.value.marks.size != 2) delay(10)
                while (guide.state.value.marks.size != 1) delay(10)
                field.configure("smoke", ScreenSize(2400, 1080))
                while (guide.state.value.width != 2400) delay(10)
                check(guide.state.value.marks.isEmpty())
                guide.submit(GuidanceInput("smoke", guide.state.value.geometry - 1, "stale", GuidanceTool.PEN, points))
                delay(100); check(field.state.value.marks.isEmpty())
                check(pauses.get() == 2 && failures.get() == 0)
            }
        } finally {
            withContext(Dispatchers.Main) { sessions.forEach { it.close() } }
            peers.forEach { it.close(); it.dispose() }; factory.dispose()
        }
    }
}
