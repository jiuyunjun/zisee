package com.zisee.app.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsControllerCompat
import com.zisee.app.BuildConfig
import com.zisee.app.call.CallUiState
import com.zisee.app.call.state.CallPhase
import com.zisee.app.rtc.CameraMode
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

// Foundations.dc.html tokens. Every call surface draws from this palette only.
private val CallInk = Color(0xFF0B0F12)
private val CallScrim = Color(0xFF070B0E)
private val CallAccent = Color(0xFF5FD4D6)
private val CallAccentInk = Color(0xFF071518)
private val CallText = Color(0xFFE8EDF0)
private val CallMuted = Color(0xFFA9B6BD)
private val CallCaption = Color(0xFF4E5C65)
private val PipInk = Color(0xFFC4D0D6)
private val CallDanger = Color(0xFFE5484D)
private val DockInk = Color(0xFF0D1317)
private val BadgeInk = Color(0xFF090E11)

// The four possible cameras in a call. Which of them exist depends on each end's Show Me mode.
private const val MeFace = CallVideoLayout.MeFace
private const val MeScene = CallVideoLayout.MeScene
private const val PeerFace = CallVideoLayout.PeerFace
private const val PeerScene = CallVideoLayout.PeerScene

private fun label(tile: String) = when (tile) {
    MeFace -> "我"
    MeScene -> "我的现场"
    // Four characters at most: a three-tile stack leaves the label 88dp to sit in.
    PeerScene -> "对方现场"
    else -> "对方"
}

private val DockShape = RoundedCornerShape(32.dp)
private val ButtonShape = RoundedCornerShape(28.dp)
private val PipShape = RoundedCornerShape(20.dp)
// A parked thumbnail keeps only the rounded edge that faces the picture.
private val HandleLeftShape = RoundedCornerShape(topEnd = 7.dp, bottomEnd = 7.dp)
private val HandleRightShape = RoundedCornerShape(topStart = 7.dp, bottomStart = 7.dp)

/** FaceCall.dc.html / ShowMe.dc.html: content first, translucent controls, lower-right PiP. */
@Composable
internal fun ActiveCall(state: CallUiState, onMute: () -> Unit, onCamera: () -> Unit,
    onShowMe: () -> Unit, onSwitch: () -> Unit, onSpeaker: () -> Unit, onEnd: () -> Unit,
    onHintSeen: () -> Unit = {}, onViewLayout: (Boolean, Boolean) -> Unit = { _, _ -> },
    onNoiseMode: (com.zisee.app.rtc.audio.processing.NoiseSuppressionMode) -> Unit = {}) {
    var controls by remember { mutableStateOf(true) }
    var interaction by remember { mutableLongStateOf(0L) }
    var more by remember { mutableStateOf(false) }
    var stableLocalMode by remember { mutableStateOf(CameraMode.FACE) }
    val localMode = if (state.showMe.mode == CameraMode.STARTING) stableLocalMode else state.showMe.mode
    SideEffect { stableLocalMode = localMode }
    var chosen by remember(localMode, state.remotePresentation.mode) { mutableStateOf<String?>(null) }
    // Where the viewer has dragged each thumbnail, as an offset from its stacked position.
    var moved by remember { mutableStateOf(mapOf<String, Offset>()) }
    // Thumbnails parked off an edge, and which edge each went to.
    var parked by remember { mutableStateOf(mapOf<String, Boolean>()) }
    var duration by remember { mutableLongStateOf(0L) }
    var tipInset by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    val remoteDual = state.remotePresentation.mode == CameraMode.DUAL
    val scene = state.remotePresentation.mode in setOf(CameraMode.DUAL, CameraMode.BACK_ONLY)
    val localScene = localMode in setOf(CameraMode.DUAL, CameraMode.BACK_ONLY)
    val starting = state.showMe.mode == CameraMode.STARTING
    val hint = state.showMeHint && remoteDual
    // Priority: what is happening now, then what failed, then the one-time teaching hint.
    val tip = when {
        state.stats.audioDevice.state == com.zisee.app.rtc.audio.AudioState.INTERRUPTED -> "音频被其他应用中断；可点击扬声器按钮恢复"
        state.stats.audioBandwidth == com.zisee.app.rtc.audio.AudioBandwidthMode.AUDIO_ONLY -> "网络较弱，已暂停发送画面以优先保持语音"
        starting -> "正在开启摄像头…"
        state.showMe.message.isNotEmpty() -> state.showMe.message
        hint -> "点击小窗即可切换主视角"
        else -> null
    }
    // The call surface is dark whatever the system theme is, so the system bars must stay light
    // for as long as it is on screen.
    val view = LocalView.current
    DisposableEffect(view) {
        val activity = view.context as? Activity
        val window = activity?.window
        val bars = window?.let { WindowInsetsControllerCompat(it, view) }
        val lightStatus = bars?.isAppearanceLightStatusBars
        val lightNavigation = bars?.isAppearanceLightNavigationBars
        bars?.isAppearanceLightStatusBars = false
        bars?.isAppearanceLightNavigationBars = false
        // Respect the system rotation lock; unlocked users can still use all four directions.
        val previousOrientation = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        onDispose {
            lightStatus?.let { bars?.isAppearanceLightStatusBars = it }
            lightNavigation?.let { bars?.isAppearanceLightNavigationBars = it }
            previousOrientation?.let { activity.requestedOrientation = it }
        }
    }
    LaunchedEffect(state.machine.session?.callId) {
        val start = SystemClock.elapsedRealtime()
        while (true) { duration = (SystemClock.elapsedRealtime() - start) / 1000; delay(1_000) }
    }
    LaunchedEffect(controls, interaction, more, state.machine.phase, state.showMe.mode) {
        if (controls && !more && state.machine.phase == CallPhase.CONNECTED && !starting) {
            delay(5_000); controls = false
        }
    }
    // The hint has taught its gesture once it has been read, whether or not it was used.
    LaunchedEffect(hint) { if (hint) { delay(8_000); onHintSeen() } }
    LaunchedEffect(tip) { if (tip == null) tipInset = 0.dp }
    Surface(color = CallInk, contentColor = CallText, modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().clickable { controls = !controls; interaction++ }) {
            val order = CallVideoLayout.sources(localMode, state.remotePresentation.mode)
            val main = CallVideoLayout.main(order, chosen)
            val thumbs = order.filter { it != main }
            // Report the resolved main view, including the automatic Show Me choice.
            LaunchedEffect(main, state.remotePresentation.mode) {
                val request = CallVideoLayout.remoteView(main, state.remotePresentation.mode)
                onViewLayout(request.front == com.zisee.app.rtc.ViewSize.LARGE,
                    request.back == com.zisee.app.rtc.ViewSize.LARGE)
            }
            val aspects = mapOf(
                MeFace to videoAspect(state.local),
                MeScene to videoAspect(if (localMode == CameraMode.DUAL) state.localBack else state.local),
                PeerFace to videoAspect(state.remote),
                PeerScene to videoAspect(if (remoteDual) state.remoteBack else state.remote),
            )
            val positions = CallVideoLayout.thumbnails(maxWidth.value.coerceAtLeast(1f),
                maxHeight.value.coerceAtLeast(1f), thumbs.map { aspects.getValue(it) }, 140f + tipInset.value)
            val tiles = thumbs.zip(positions).toMap()
            // Pixel drag offsets belong to one geometry. Rotation/stream changes reset them before
            // they can strand a thumbnail outside the resized window.
            LaunchedEffect(maxWidth, maxHeight, main, positions) {
                moved = emptyMap(); parked = emptyMap()
            }
            val edge = with(density) { 8.dp.toPx() }
            fun inside(tile: String, raw: Offset): Offset {
                val rect = tiles.getValue(tile)
                val x = with(density) { rect.x.dp.toPx() }
                val y = with(density) { rect.y.dp.toPx() }
                val w = with(density) { rect.width.dp.toPx() }
                val h = with(density) { rect.height.dp.toPx() }
                val width = with(density) { maxWidth.toPx() }
                val height = with(density) { maxHeight.toPx() }
                return Offset(raw.x.coerceIn(-x - w / 2, width - x - w / 2),
                    raw.y.coerceIn(-y, (height - y - h).coerceAtLeast(-y)))
            }
            fun settled(tile: String, raw: Offset): Offset {
                val held = inside(tile, raw)
                val rect = tiles.getValue(tile)
                val x = with(density) { rect.x.dp.toPx() } + held.x
                val w = with(density) { rect.width.dp.toPx() }
                val width = with(density) { maxWidth.toPx() }
                parked = when {
                    x < -w / 4 -> parked + (tile to true)
                    x + w > width + w / 4 -> parked + (tile to false)
                    else -> parked - tile
                }
                val snapped = if (x + w / 2 < width / 2) edge else (width - w - edge).coerceAtLeast(0f)
                return Offset(snapped - with(density) { rect.x.dp.toPx() }, held.y)
            }
            fun slot(tile: String): Modifier {
                if (tile == main) return Modifier.fillMaxSize()
                val rect = tiles.getValue(tile)
                // Clamp during composition as well: layout changes precede the reset effect.
                val drag = inside(tile, moved[tile] ?: Offset.Zero)
                val y = with(density) { rect.y.dp.toPx() } + drag.y
                parked[tile]?.let { left ->
                    val handleHeight = minOf(56.dp, maxHeight)
                    return Modifier.align(Alignment.TopStart)
                        .offset { IntOffset(if (left) 0 else with(density) { (maxWidth - 14.dp).toPx().roundToInt() },
                            y.coerceIn(0f, with(density) { (maxHeight - handleHeight).toPx() }).roundToInt()) }
                        .size(14.dp, handleHeight)
                        .clip(if (left) HandleLeftShape else HandleRightShape)
                        .background(DockInk.copy(alpha = 0.82f))
                        .border(1.dp, CallText.copy(alpha = 0.16f),
                            if (left) HandleLeftShape else HandleRightShape)
                }
                return Modifier.align(Alignment.TopStart)
                    .offset { IntOffset((with(density) { rect.x.dp.toPx() } + drag.x).roundToInt(), y.roundToInt()) }
                    .size(rect.width.dp, rect.height.dp).clip(PipShape)
                    .border(1.dp, CallText.copy(alpha = 0.16f), PipShape)
            }
            // Fixed call sites keep each renderer's node identity across a swap. Moving a renderer
            // between parents would recreate its surface and flash black.
            if (MeFace in order) {
                VideoTile(state.local, state.cameraEnabled && (MeFace == main || MeFace !in parked), slot(MeFace), MeFace != main)
            }
            if (MeScene in order) {
                VideoTile(if (localMode == CameraMode.DUAL) state.localBack else state.local,
                    state.cameraEnabled && (MeScene == main || MeScene !in parked), slot(MeScene), MeScene != main)
            }
            if (PeerFace in order) {
                VideoTile(state.remote, state.remotePresentation.enabled && (PeerFace == main || PeerFace !in parked), slot(PeerFace), PeerFace != main)
            }
            if (PeerScene in order) {
                VideoTile(if (state.remotePresentation.mode == CameraMode.DUAL) state.remoteBack else state.remote,
                    state.remotePresentation.enabled && (PeerScene == main || PeerScene !in parked), slot(PeerScene), PeerScene != main)
            }
            val mainIsPeer = main == PeerFace || main == PeerScene
            if (mainIsPeer && !state.remotePresentation.enabled) {
                Text("对方已关闭画面", Modifier.align(Alignment.Center), color = CallText.copy(alpha = 0.7f))
            } else if (mainIsPeer && state.stats.videoFrames == 0L) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(Modifier.size(26.dp), color = CallAccent, strokeWidth = 2.dp)
                    Text("正在等待对方画面…", Modifier.padding(top = 14.dp), color = CallText.copy(alpha = 0.7f))
                }
            }
            // Tapping a thumbnail promotes it; the previous main view takes its place.
            thumbs.forEach { tile ->
                Box(slot(tile)
                    .pointerInput(tile, main, positions, maxWidth, maxHeight) {
                        detectDragGestures(
                            onDrag = { change, delta ->
                                change.consume()
                                moved = moved + (tile to inside(tile, (moved[tile] ?: Offset.Zero) + delta))
                            },
                            onDragEnd = { moved = moved + (tile to settled(tile, moved[tile] ?: Offset.Zero)) },
                        )
                    }
                    .clickable {
                        interaction++; onHintSeen()
                        // A handle restores the thumbnail; a thumbnail becomes the main view.
                        if (tile in parked) { parked = parked - tile; moved = moved - tile }
                        else chosen = tile
                    }
                    .semantics {
                        contentDescription = if (tile in parked) "展开${label(tile)}"
                            else "将${label(tile)}切换为主画面"
                    }) {
                    // A parked handle is too narrow for a label or a badge; it is just the edge.
                    if (tile !in parked) {
                        val compact = tiles.getValue(tile).width < 72f
                        PipLabel(if (compact) when (tile) {
                            MeFace -> "我"; PeerFace -> "对"; else -> "景"
                        } else if (tile == MeFace && !state.cameraEnabled) "已关闭" else label(tile), compact)
                        if (!compact) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).clip(CircleShape)
                            .background(BadgeInk.copy(alpha = 0.66f)), contentAlignment = Alignment.Center) {
                            Canvas(Modifier.size(13.dp)) { scale(size.width / 24f, size.width / 24f, Offset.Zero) { callIcon("swap", CallText) } }
                        }
                    }
                }
            }
            if (tip != null) Box(Modifier.align(Alignment.BottomEnd).safeDrawingPadding()
                .padding(end = 16.dp, bottom = 132.dp).widthIn(max = 208.dp)
                .onSizeChanged { tipInset = with(density) { it.height.toDp() } + 8.dp }
                .clip(RoundedCornerShape(16.dp)).background(DockInk.copy(alpha = 0.82f))
                .border(1.dp, CallText.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                .padding(horizontal = 13.dp, vertical = 9.dp)) {
                Text(tip, fontSize = 12.sp, lineHeight = 18.sp, color = CallMuted)
            }
            if (controls) {
                Box(Modifier.fillMaxWidth().height(190.dp)
                    .background(Brush.verticalGradient(listOf(CallScrim.copy(alpha = 0.72f), Color.Transparent))))
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(260.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, CallScrim.copy(alpha = 0.78f)))))
                Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().safeDrawingPadding()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(verticalArrangement = Arrangement.spacedBy(if (scene) 8.dp else 5.dp)) {
                        Text(state.peerName, fontSize = 19.sp, fontWeight = FontWeight.Medium)
                        if (scene) Row(Modifier.height(28.dp).clip(RoundedCornerShape(14.dp))
                            .background(CallAccent.copy(alpha = 0.16f))
                            .border(1.dp, CallAccent.copy(alpha = 0.30f), RoundedCornerShape(14.dp))
                            .padding(start = 9.dp, end = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            Canvas(Modifier.size(14.dp)) {
                                scale(size.width / 24f, size.width / 24f, Offset.Zero) { callIcon("show", CallAccent, knockout = CallInk) }
                            }
                            Text("Show Me · 现场", fontSize = 12.sp, color = CallAccent, fontWeight = FontWeight.Medium)
                        } else Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            if (state.machine.phase == CallPhase.CONNECTED) {
                                Box(Modifier.size(6.dp).clip(CircleShape).background(CallAccent))
                            }
                            Text(if (state.machine.phase == CallPhase.CONNECTED)
                                "%02d:%02d".format(duration / 60, duration % 60) else state.status,
                                fontSize = 13.sp, color = CallMuted)
                        }
                    }
                    Box(Modifier.size(44.dp).clip(CircleShape).background(Color(0xFF0C1216).copy(alpha = 0.5f))
                        .border(1.dp, CallText.copy(alpha = 0.10f), CircleShape)
                        .clickable { interaction++; onSpeaker() }
                        .semantics { role = Role.Button; contentDescription = if (state.speakerOn) "免提已开启" else "免提已关闭" },
                        contentAlignment = Alignment.Center) {
                        Canvas(Modifier.size(20.dp)) {
                            scale(size.width / 24f, size.width / 24f, Offset.Zero) {
                                callIcon(if (state.speakerOn) "speaker" else "speaker-off", CallText)
                            }
                        }
                    }
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().safeDrawingPadding()
                    .padding(bottom = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.widthIn(max = 440.dp).fillMaxWidth().padding(horizontal = 12.dp).clip(DockShape)
                        .background(DockInk.copy(alpha = 0.66f))
                        .border(1.dp, CallText.copy(alpha = 0.08f), DockShape).padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        DockButton(if (state.muted) "取消静音" else "静音", "mic", off = state.muted) { interaction++; onMute() }
                        DockButton(if (state.cameraEnabled) "关闭画面" else "开启画面", "camera",
                            off = !state.cameraEnabled) { interaction++; onCamera() }
                        DockButton(if (localScene) "看我" else "给你看", "show", active = localScene,
                            available = !localScene, enabled = state.cameraEnabled && !starting) { interaction++; onShowMe() }
                        DockButton("更多", "more") { interaction++; more = true }
                        DockButton("挂断", "end", danger = true, width = 74.dp, action = onEnd)
                    }
                    Text("轻点画面可隐藏控制", Modifier.padding(top = 6.dp), fontSize = 11.sp, color = CallCaption)
                }
            }
        }
        if (more) CallOptions(state, onDismiss = { more = false; interaction++ }, onSwitch = onSwitch, onNoiseMode = onNoiseMode)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CallOptions(state: CallUiState, onDismiss: () -> Unit, onSwitch: () -> Unit,
    onNoiseMode: (com.zisee.app.rtc.audio.processing.NoiseSuppressionMode) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF12181D), contentColor = CallText) {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("通话选项", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { onDismiss(); onSwitch() },
                enabled = state.cameraEnabled && state.showMe.mode != CameraMode.STARTING,
                colors = ButtonDefaults.textButtonColors(contentColor = CallAccent)) { Text("切换前后摄像头") }
            Text("「给你看」在支持双摄的设备上同时展示人像与现场，否则改用单后摄。",
                style = MaterialTheme.typography.bodySmall, color = CallMuted)
            Text("麦克风降噪", style = MaterialTheme.typography.titleSmall)
            for (mode in com.zisee.app.rtc.audio.processing.NoiseSuppressionMode.entries) {
                Row(Modifier.fillMaxWidth().clickable { onNoiseMode(mode) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = state.stats.audioProcessing.mode == mode, onClick = { onNoiseMode(mode) })
                    Text(mode.label)
                }
            }
            Text(if (state.stats.audioProcessing.state == com.zisee.app.rtc.audio.processing.AiState.DEGRADED)
                "当前使用标准降噪，以保持语音连续。" else "AI 在本机处理语音。自动模式会根据设备状态降级。",
                style = MaterialTheme.typography.bodySmall, color = CallMuted)
            if (BuildConfig.DEBUG) {
                val stats = state.stats
                Text("连接详情", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall)
                Text("${stats.candidateType} → ${stats.remoteCandidateType} · RTT ${stats.measuredRttMs ?: "—"} ms\n" +
                    "接收 ${stats.videoWidth} × ${stats.videoHeight} · ${stats.videoFps} fps\n" +
                    "发送 ${stats.sentWidth} × ${stats.sentHeight} · ${stats.sentFps} fps\n" +
                    "收/发 ${stats.receiveKbps}/${stats.sendKbps} kbps · 上行估计 ${stats.availableOutgoingKbps ?: "—"} kbps\n" +
                    "${stats.codec} · ${stats.encoder}\n限制 ${stats.qualityLimitation} · 热状态 ${stats.thermalStatus ?: "—"}",
                    style = MaterialTheme.typography.bodySmall, color = CallMuted)
                val audio = stats.audio
                val processing = stats.audioProcessing
                Text("音频 ${stats.audioDevice.state} · 输入 ${stats.audioDevice.input} · 输出 ${stats.audioDevice.output}\n" +
                    "AEC 软件 · ${processing.engine} · ${processing.state}\n" +
                    "${audio.codec} · 发 ${audio.sendKbps?.toLong() ?: "—"} kbps · 抖动 ${audio.jitterMs?.toLong() ?: "—"} ms\n" +
                    "上/下行丢包 ${audio.outboundLoss ?: "—"}/${audio.inboundLoss ?: "—"}\n" +
                    "NetEq ${audio.jitterBufferMs?.toLong() ?: "—"} ms · PLC ${audio.concealmentRatio ?: "—"}\n" +
                    "AI avg/p95/p99/max ${processing.averageUs}/${processing.p95Us}/${processing.p99Us}/${processing.maxUs} µs\n" +
                    "超时 ${processing.deadlineMisses} · 回退 ${processing.fallbackCount} ${processing.fallback}",
                    style = MaterialTheme.typography.bodySmall, color = CallMuted)
            }
        }
    }
}

/** Read each track's upright frame dimensions, never infer the peer's direction from our window. */
@Composable
private fun videoAspect(feed: com.zisee.app.rtc.VideoFeed?): Float {
    if (feed == null) return 9f / 16f
    val geometry by feed.geometry.collectAsState()
    return geometry?.aspectRatio ?: (9f / 16f)
}

/** A camera's slot in the layout: its picture when it is live, its dark placeholder when not. */
@Composable
private fun VideoTile(feed: com.zisee.app.rtc.VideoFeed?, live: Boolean, modifier: Modifier, overlay: Boolean) {
    if (live && feed != null) VideoRenderer(feed, modifier, overlay)
    else Box(if (overlay) modifier.background(CallInk) else modifier)
}

@Composable
private fun BoxScope.PipLabel(text: String, compact: Boolean = false) {
    Box(Modifier.align(Alignment.BottomStart).padding(if (compact) 2.dp else 8.dp).height(20.dp).clip(RoundedCornerShape(10.dp))
        .background(BadgeInk.copy(alpha = 0.66f)).padding(horizontal = if (compact) 3.dp else 8.dp),
        contentAlignment = Alignment.Center) {
        Text(text, fontSize = 11.sp, color = PipInk)
    }
}

/**
 * A dock control. State is carried by shape and a slash rather than colour alone, so mute and
 * camera-off stay legible without colour vision and read correctly to TalkBack.
 */
@Composable
private fun DockButton(label: String, kind: String, off: Boolean = false, active: Boolean = false,
    available: Boolean = false, danger: Boolean = false, enabled: Boolean = true,
    width: Dp = 56.dp, action: () -> Unit) {
    val background = when {
        danger -> CallDanger
        active -> CallAccent
        off -> CallText.copy(alpha = 0.92f)
        else -> CallText.copy(alpha = 0.10f)
    }
    val ink = when {
        danger -> Color.White
        active -> CallAccentInk
        off -> CallInk
        available -> CallAccent
        else -> CallText
    }
    val outline = if (available) CallAccent.copy(alpha = 0.34f) else Color.Transparent
    // The knockout fills the overlap inside the Show Me glyph, so it tracks the button surface.
    val knockout = if (off) CallText else if (active) CallAccent else DockInk
    val dim = if (enabled) 1f else 0.4f
    Box(Modifier.size(width, 56.dp).clip(ButtonShape)
        .background(background.copy(alpha = background.alpha * dim))
        .border(1.dp, outline.copy(alpha = outline.alpha * dim), ButtonShape)
        .clickable(enabled = enabled, onClick = action)
        .semantics { role = Role.Button; contentDescription = label },
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(23.dp)) {
            scale(size.width / 24f, size.width / 24f, Offset.Zero) {
                callIcon(kind, ink.copy(alpha = ink.alpha * dim), knockout, off)
            }
        }
    }
}

/** Design glyphs drawn in the shared 24-unit viewBox used across the canvases. */
private fun DrawScope.callIcon(kind: String, ink: Color, knockout: Color = Color.Transparent, off: Boolean = false) {
    val stroke = Stroke(1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun path(data: String, color: Color = ink) = drawPath(PathParser().parsePathString(data).toPath(), color, style = stroke)
    when (kind) {
        "mic" -> {
            drawRoundRect(ink, Offset(9f, 2.5f), Size(6f, 11.5f), CornerRadius(3f), style = stroke)
            path("M5.5 11.5a6.5 6.5 0 0 0 13 0")
            path("M12 18v3.5")
        }
        "camera" -> {
            path("M15.5 10.2 21.5 6.8v10.4l-6-3.4z")
            drawRoundRect(ink, Offset(2.5f, 5.4f), Size(13f, 13.2f), CornerRadius(3.2f), style = stroke)
        }
        "show" -> {
            drawRoundRect(ink, Offset(2.4f, 4.2f), Size(14.6f, 15.4f), CornerRadius(3.2f), style = stroke)
            drawRoundRect(knockout, Offset(11.8f, 12f), Size(9.8f, 7.6f), CornerRadius(2.4f))
            drawRoundRect(ink, Offset(11.8f, 12f), Size(9.8f, 7.6f), CornerRadius(2.4f), style = stroke)
        }
        "more" -> for (x in listOf(5.5f, 12f, 18.5f)) drawCircle(ink, 1.4f, Offset(x, 12f))
        "speaker" -> {
            path("M11 5 6 9H3v6h3l5 4z")
            path("M15.5 8.5a5 5 0 0 1 0 7")
            path("M18.5 5.5a9 9 0 0 1 0 13")
        }
        "speaker-off" -> {
            path("M11 5 6 9H3v6h3l5 4z")
            path("M16 9.5 21 14.5")
            path("M21 9.5 16 14.5")
        }
        "swap" -> path("M4 8h13l-3.5-3.5M20 16H7l3.5 3.5")
        "end" -> path("M3.2 13.6c5-4.6 12.6-4.6 17.6 0l-2.5 2.6a2 2 0 0 1-2.5.3l-1.7-1.1a1.6 1.6 0 0 1-.7-1.3v-1.4a12 12 0 0 0-6.8 0v1.4c0 .5-.3 1-.7 1.3l-1.7 1.1a2 2 0 0 1-2.5-.3z")
    }
    if (off) path("M4 4 20 20")
}
