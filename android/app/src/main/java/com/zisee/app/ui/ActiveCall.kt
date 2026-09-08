package com.zisee.app.ui

import android.app.Activity
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsControllerCompat
import com.zisee.app.BuildConfig
import com.zisee.app.call.CallUiState
import com.zisee.app.call.state.CallPhase
import com.zisee.app.rtc.CameraMode
import kotlinx.coroutines.delay

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

private val DockShape = RoundedCornerShape(32.dp)
private val ButtonShape = RoundedCornerShape(28.dp)
private val PipShape = RoundedCornerShape(20.dp)

/** FaceCall.dc.html / ShowMe.dc.html: content first, translucent controls, lower-right PiP. */
@Composable
internal fun ActiveCall(state: CallUiState, onMute: () -> Unit, onCamera: () -> Unit,
    onShowMe: () -> Unit, onSwitch: () -> Unit, onSpeaker: () -> Unit, onEnd: () -> Unit,
    onHintSeen: () -> Unit = {}) {
    var controls by remember { mutableStateOf(true) }
    var interaction by remember { mutableLongStateOf(0L) }
    var more by remember { mutableStateOf(false) }
    var swap by remember { mutableStateOf(false) }
    var duration by remember { mutableLongStateOf(0L) }
    var tipInset by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    val remoteDual = state.remotePresentation.mode == CameraMode.DUAL
    val scene = state.remotePresentation.mode in setOf(CameraMode.DUAL, CameraMode.BACK_ONLY)
    val localScene = state.showMe.mode in setOf(CameraMode.DUAL, CameraMode.BACK_ONLY)
    val starting = state.showMe.mode == CameraMode.STARTING
    val hint = state.showMeHint && remoteDual
    // Priority: what is happening now, then what failed, then the one-time teaching hint.
    val tip = when {
        starting -> "正在开启摄像头…"
        state.showMe.message.isNotEmpty() -> state.showMe.message
        hint -> "点击小窗即可切换主视角"
        else -> null
    }
    // The call surface is dark whatever the system theme is, so the system bars must stay light
    // for as long as it is on screen.
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        val bars = window?.let { WindowInsetsControllerCompat(it, view) }
        val lightStatus = bars?.isAppearanceLightStatusBars
        val lightNavigation = bars?.isAppearanceLightNavigationBars
        bars?.isAppearanceLightStatusBars = false
        bars?.isAppearanceLightNavigationBars = false
        onDispose {
            lightStatus?.let { bars?.isAppearanceLightStatusBars = it }
            lightNavigation?.let { bars?.isAppearanceLightNavigationBars = it }
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
    LaunchedEffect(remoteDual) { if (!remoteDual) swap = false }
    LaunchedEffect(tip) { if (tip == null) tipInset = 0.dp }
    Surface(color = CallInk, contentColor = CallText, modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().clickable { controls = !controls; interaction++ }) {
            val pipWidth = minOf(104.dp, maxWidth * 0.28f)
            val pipHeight = minOf(148.dp, maxHeight * 0.25f)
            // A visible tip lifts the PiP so both keep the designed 132dp footing above the dock.
            val pip = Modifier.align(Alignment.BottomEnd).safeDrawingPadding()
                .padding(end = 16.dp, bottom = 132.dp + tipInset)
                .size(pipWidth, pipHeight).clip(PipShape)
                .border(1.dp, CallText.copy(alpha = 0.16f), PipShape)
            if (state.remotePresentation.enabled) {
                // Keep both renderer nodes keyed in place when swapping, rather than detaching feeds.
                state.remoteBack?.let { feed ->
                    if (remoteDual) VideoRenderer(feed, if (swap) pip else Modifier.fillMaxSize(), overlay = swap)
                }
                state.remote?.let { feed ->
                    VideoRenderer(feed, if (remoteDual && !swap) pip else Modifier.fillMaxSize(), overlay = remoteDual && !swap)
                }
            } else Text("对方已关闭画面", Modifier.align(Alignment.Center), color = CallText.copy(alpha = 0.7f))
            if (state.stats.videoFrames == 0L && state.remotePresentation.enabled) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(Modifier.size(26.dp), color = CallAccent, strokeWidth = 2.dp)
                    Text("正在等待对方画面…", Modifier.padding(top = 14.dp), color = CallText.copy(alpha = 0.7f))
                }
            }
            if (remoteDual) {
                Box(pip.clickable { swap = !swap; interaction++; onHintSeen() }
                    .semantics { contentDescription = if (swap) "切换回现场主画面" else "切换到对方人像主画面" }) {
                    PipLabel(if (swap) "现场" else "对方")
                    Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(26.dp).clip(CircleShape)
                        .background(BadgeInk.copy(alpha = 0.66f)), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.size(14.dp)) { scale(size.width / 24f, size.width / 24f, Offset.Zero) { callIcon("swap", CallText) } }
                    }
                }
            } else Box(pip.background(CallInk)) {
                if (state.cameraEnabled) {
                    val preview = if (state.showMe.mode == CameraMode.DUAL) state.localBack else state.local
                    preview?.let { VideoRenderer(it, Modifier.fillMaxSize(), overlay = true) }
                }
                PipLabel(if (!state.cameraEnabled) "画面已关闭" else if (localScene) "我的现场" else "我")
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
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(DockShape)
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
        if (more) CallOptions(state, onDismiss = { more = false; interaction++ }, onSwitch = onSwitch)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CallOptions(state: CallUiState, onDismiss: () -> Unit, onSwitch: () -> Unit) {
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
            if (BuildConfig.DEBUG) {
                val stats = state.stats
                Text("连接详情", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall)
                Text("${stats.candidateType} → ${stats.remoteCandidateType} · RTT ${stats.measuredRttMs ?: "—"} ms\n" +
                    "接收 ${stats.videoWidth} × ${stats.videoHeight} · ${stats.videoFps} fps\n" +
                    "发送 ${stats.sentWidth} × ${stats.sentHeight} · ${stats.sentFps} fps\n" +
                    "收/发 ${stats.receiveKbps}/${stats.sendKbps} kbps · 上行估计 ${stats.availableOutgoingKbps ?: "—"} kbps\n" +
                    "${stats.codec} · ${stats.encoder}\n限制 ${stats.qualityLimitation} · 热状态 ${stats.thermalStatus ?: "—"}",
                    style = MaterialTheme.typography.bodySmall, color = CallMuted)
            }
        }
    }
}

@Composable
private fun BoxScope.PipLabel(text: String) {
    Box(Modifier.align(Alignment.BottomStart).padding(8.dp).height(20.dp).clip(RoundedCornerShape(10.dp))
        .background(BadgeInk.copy(alpha = 0.66f)).padding(horizontal = 8.dp),
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
