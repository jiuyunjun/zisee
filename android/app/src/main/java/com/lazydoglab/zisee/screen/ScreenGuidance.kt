package com.lazydoglab.zisee.screen

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.lazydoglab.zisee.ar.annotation.AnnotationAuthor
import com.lazydoglab.zisee.ar.annotation.VideoPoint
import kotlinx.coroutines.flow.MutableStateFlow
import org.webrtc.DataChannel
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Call-owned; all state and windows live on main, independently of Activity visibility. */
class ScreenGuidance(private val context: Context, private val failure: () -> Unit,
    private val pauseCapture: (Boolean) -> Unit, private val stopCapture: () -> Unit) : AutoCloseable {
    val state = MutableStateFlow(GuidanceState())
    private val main = Handler(Looper.getMainLooper())
    private val engine = GuidanceEngine()
    private var channel: DataChannel? = null
    private var local = false
    private var closed = false
    private var expectedRemote = ""
    private var window: GuidanceOverlay? = null
    private var pending = linkedMapOf<String, Long>()
    private val queued = AtomicInteger()
    private var lastSyncRequest = 0L
    private var needsSync = false
    private var awaitingSync = false
    private var transportNotice = ""
    private val displays = context.getSystemService(android.hardware.display.DisplayManager::class.java)
    private val displayListener = object : android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) = Unit
        override fun onDisplayRemoved(id: Int) = Unit
        override fun onDisplayChanged(id: Int) {
            if (local && id == android.view.Display.DEFAULT_DISPLAY) configure(engine.state.session,
                ScreenSize(engine.state.width.coerceAtLeast(1), engine.state.height.coerceAtLeast(1)))
        }
    }
    private fun onMain(block: () -> Unit) { main.post { if (!closed) block() } }
    fun attach(value: DataChannel) = onMain {
        channel = value
        displays.registerDisplayListener(displayListener, main)
        value.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit
            override fun onStateChange() = onMain {
                publish()
                if (open()) { if (local) sync() else { awaitingSync = true; send(GuidancePacket(GuidancePacket.REQUEST)) } }
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                if (!buffer.binary || buffer.data.remaining() > GuidanceWire.MAX_BYTES) return
                if (queued.incrementAndGet() > 64) { queued.decrementAndGet(); return }
                val bytes = ByteArray(buffer.data.remaining()); buffer.data.get(bytes)
                main.post { queued.decrementAndGet(); if (!closed) GuidanceWire.decode(bytes)?.let(::receive) }
            }
        })
        tick()
    }
    fun remoteSession(session: String) = onMain {
        expectedRemote = session
        awaitingSync = session.isNotEmpty()
        if (!local && engine.state.session != session) {
            engine.restore(GuidanceState()); pending.clear(); publish()
            if (session.isNotEmpty()) send(GuidancePacket(GuidancePacket.REQUEST))
        }
    }
    fun configure(session: String, size: ScreenSize?) = onMain {
        local = session.isNotEmpty()
        if (!local) { window?.close(); window = null }
        val allowed = local && Settings.canDrawOverlays(context)
        if (allowed && window == null) {
            try { window = GuidanceOverlay(context, ::submit, { op, mark -> command(op, mark) },
                { setPaused(!engine.state.paused) }, stopCapture, failure) }
            catch (_: RuntimeException) { failure() }
        }
        if (engine.configure(session, size?.width ?: 0, size?.height ?: 0,
                if (local && session == engine.state.session) engine.state.paused else false, window != null,
                displays.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.rotation ?: 0)) { pending.clear(); sync() }
        publish()
    }
    fun setPaused(value: Boolean) = onMain {
        if (!local) return@onMain
        pauseCapture(value)
        engine.configure(engine.state.session, engine.state.width, engine.state.height, value, window != null)
        sync(); publish()
    }
    fun put(id: String, tool: GuidanceTool, points: List<VideoPoint>) {
        if (points.isEmpty() || points.size > 128) return
        command(GuidanceOp.PUT, GuidanceMark(id, AnnotationAuthor.GUIDE, tool, points.toList()))
    }
    fun submit(input: GuidanceInput) = command(GuidanceOp.PUT,
        GuidanceMark(input.id, AnnotationAuthor.GUIDE, input.tool, input.points.toList()), input.session, input.geometry)
    fun command(op: GuidanceOp, mark: GuidanceMark? = null, expectedSession: String? = null, expectedGeometry: Int? = null) = onMain {
        val current = engine.state
        if (expectedSession != null && (current.session != expectedSession || current.geometry != expectedGeometry)) return@onMain
        if (current.session.isEmpty() || current.paused || !current.overlay || (!local && !open())) return@onMain
        val packet = GuidancePacket(GuidancePacket.COMMAND, current.session, current.geometry,
            UUID.randomUUID().toString(), op, marks = listOfNotNull(mark))
        if (local) apply(packet, AnnotationAuthor.FIELD)
        else if (pending.size < 128 && send(packet)) pending[packet.id] = android.os.SystemClock.elapsedRealtime()
        else transportNotice = "标注发送失败，请稍后重试"
        publish()
    }
    private fun apply(packet: GuidancePacket, actor: AnnotationAuthor) {
        if (!engine.apply(packet, actor)) {
            if (actor == AnnotationAuthor.GUIDE) send(GuidancePacket(GuidancePacket.REJECT, engine.state.session,
                engine.state.geometry, packet.id))
            else { transportNotice = "标注未接受：已过期或数量达到上限"; publish() }
            return
        }
        val canonical = packet.copy(kind = GuidancePacket.ACK, author = actor, state = engine.state,
            marks = if (packet.op == GuidanceOp.PUT) engine.state.marks.filter { it.id == packet.marks.single().id } else emptyList())
        if (!send(canonical)) needsSync = true
        publish()
        canonical.marks.singleOrNull()?.takeIf { it.tool == GuidanceTool.POINTER }?.let { mark ->
            val session = engine.state.session; val geometry = engine.state.geometry
            main.postDelayed({ if (!closed && local && engine.state.session == session && engine.state.geometry == geometry)
                apply(GuidancePacket(GuidancePacket.COMMAND, session, geometry, UUID.randomUUID().toString(),
                    GuidanceOp.REMOVE, mark.id), mark.author) }, 1_500)
        }
    }
    private fun receive(p: GuidancePacket) {
        if (local) {
            if (p.kind == GuidancePacket.REQUEST) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastSyncRequest > 500) { lastSyncRequest = now; sync() }
            } else if (p.kind == GuidancePacket.COMMAND && engine.state.overlay) apply(p, AnnotationAuthor.GUIDE)
            return
        }
        if (expectedRemote.isEmpty() || p.session != expectedRemote) return
        if (p.kind == GuidancePacket.REJECT) {
            pending.remove(p.id); transportNotice = "标注未接受：已过期或数量达到上限"; publish(); return
        }
        if (p.kind == GuidancePacket.SYNC) {
            val value = p.state ?: return
            if (value.session == engine.state.session && value.revision < engine.state.revision) return
            engine.restore(value); pending.clear(); awaitingSync = false; transportNotice = ""; publish()
        } else if (p.kind == GuidancePacket.ACK) {
            pending.remove(p.id)
            transportNotice = ""
            if (p.state?.revision != engine.state.revision + 1 || p.geometry != engine.state.geometry) {
                awaitingSync = true
                send(GuidancePacket(GuidancePacket.REQUEST)); return
            }
            engine.apply(p, p.author); publish()
        }
    }
    private fun sync() { if (local) needsSync = !send(GuidancePacket(GuidancePacket.SYNC, engine.state.session,
        engine.state.geometry, marks = engine.state.marks, state = engine.state)) }
    private fun open() = channel?.state() == DataChannel.State.OPEN
    private fun send(packet: GuidancePacket): Boolean {
        val value = channel ?: return false
        if (!open() || value.bufferedAmount() > 96_000) return false
        return try { value.send(DataChannel.Buffer(ByteBuffer.wrap(GuidanceWire.encode(packet)), true)) }
        catch (_: RuntimeException) { failure(); false }
    }
    private fun publish() {
        val timeout = pending.values.any { android.os.SystemClock.elapsedRealtime() - it > 3_000 }
        state.value = engine.state.copy(connected = open(), notice = if (timeout) "标注未确认，请检查连接" else transportNotice)
        window?.update(state.value)
    }
    private fun tick() {
        if (closed) return
        if (local && !Settings.canDrawOverlays(context)) {
            window?.close(); window = null
            if (engine.configure(engine.state.session, engine.state.width, engine.state.height, engine.state.paused, false)) sync()
        }
        publish()
        if (local && needsSync && open()) sync()
        if (!local && expectedRemote.isNotEmpty() && open() && (awaitingSync || engine.state.session.isEmpty() ||
                pending.values.any { android.os.SystemClock.elapsedRealtime() - it > 3_000 })) send(GuidancePacket(GuidancePacket.REQUEST))
        main.postDelayed(::tick, 1_000)
    }
    override fun close() {
        val release = {
            closed = true; main.removeCallbacksAndMessages(null); window?.close(); window = null
            displays.unregisterDisplayListener(displayListener)
            val oldChannel = channel; channel = null
            for (cleanup in listOf<() -> Unit>({ oldChannel?.unregisterObserver() }, { oldChannel?.close() }, { oldChannel?.dispose() })) {
                try { cleanup() } catch (_: RuntimeException) { failure() }
            }
            state.value = GuidanceState()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) release() else main.post { release() }
    }
}
