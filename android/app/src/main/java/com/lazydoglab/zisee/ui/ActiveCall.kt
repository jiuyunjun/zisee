package com.lazydoglab.zisee.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.SystemClock
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowInsetsControllerCompat
import com.lazydoglab.zisee.BuildConfig
import com.lazydoglab.zisee.call.CallUiState
import com.lazydoglab.zisee.call.state.CallPhase
import com.lazydoglab.zisee.rtc.CameraMode
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

// Foundations.dc.html tokens. Every call surface draws from this palette only, in-call and out.
internal val CallInk = Color(0xFF0B0F12)
internal val CallPanel = Color(0xFF12181D)
private val CallScrim = Color(0xFF070B0E)
internal val CallAccent = Color(0xFF5FD4D6)
internal val CallAccentSoft = Color(0x295FD4D6)
internal val CallAccentInk = Color(0xFF071518)
internal val CallText = Color(0xFFE8EDF0)
internal val CallMuted = Color(0xFFA9B6BD)
internal val CallFaint = Color(0xFF67757E)
internal val CallCaption = Color(0xFF4E5C65)
internal val PipInk = Color(0xFFC4D0D6)
internal val CallDanger = Color(0xFFE5484D)
internal val DockInk = Color(0xFF0D1317)
internal val BadgeInk = Color(0xFF090E11)
internal val Elevated = Color(0xFF1E2A31)

// The four possible cameras in a call. Which of them exist depends on each end's Show Me mode.
private const val MeFace = CallVideoLayout.MeFace
private const val MeScene = CallVideoLayout.MeScene
private const val PeerFace = CallVideoLayout.PeerFace
private const val PeerScene = CallVideoLayout.PeerScene
private const val PeerScreen = CallVideoLayout.PeerScreen

private fun label(tile: String) = when (tile) {
    MeFace -> "我"
    MeScene -> "我的现场"
    // Four characters at most: a three-tile stack leaves the label 88dp to sit in.
    PeerScene -> "对方现场"
    PeerScreen -> "对方屏幕"
    else -> "对方"
}

private val DockShape = RoundedCornerShape(32.dp)
internal val ButtonShape = RoundedCornerShape(28.dp)
// Foundations.dc.html: primary/secondary actions round to 26, cards/panels to 20-28.
internal val PillShape = RoundedCornerShape(26.dp)
internal val CardShape = RoundedCornerShape(28.dp)
// Foundations.dc.html "圆角": ordinary cards and settings groups round to 24, not 28.
internal val StandardCardShape = RoundedCornerShape(24.dp)
private val PipCorner = 20.dp
private val PipShape = RoundedCornerShape(PipCorner)
// A parked thumbnail keeps only the rounded edge that faces the picture.
private val HandleLeftShape = RoundedCornerShape(topEnd = 7.dp, bottomEnd = 7.dp)
private val HandleRightShape = RoundedCornerShape(topStart = 7.dp, bottomStart = 7.dp)

/** FaceCall.dc.html / ShowMe.dc.html: content first, translucent controls, lower-right PiP. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ActiveCall(state: CallUiState, onMute: () -> Unit, onCamera: () -> Unit,
    onShowMe: () -> Unit, onSwitch: () -> Unit, onSpeaker: () -> Unit, onEnd: () -> Unit,
    onMinimize: () -> Unit = {},
    onHintSeen: () -> Unit = {}, onViewLayout: (Boolean, Boolean) -> Unit = { _, _ -> },
    onNoiseMode: (com.lazydoglab.zisee.rtc.audio.processing.NoiseSuppressionMode) -> Unit = {},
    onArMarker: (com.lazydoglab.zisee.rtc.TextureViewRenderer.DisplayedArFrame,
        com.lazydoglab.zisee.ar.annotation.VideoPoint,
        com.lazydoglab.zisee.ar.session.MarkerKind) -> Unit = { _, _, _ -> },
    onArUndo: () -> Unit = {},
    onArClearOwn: () -> Unit = {},
    onArStroke: (com.lazydoglab.zisee.ar.annotation.ArStrokeInput) -> Unit = {},
    onStopAr: () -> Unit = {},
    onSelectVideo: (String) -> Unit = {},
    onStartShare: () -> Unit = {}, onStopShare: () -> Unit = {},
    onSetScreenContentMode: (com.lazydoglab.zisee.rtc.ScreenContentMode) -> Unit = {},
    initialMore: Boolean = false,
    arControls: @Composable () -> Unit = {},
    debugControls: @Composable () -> Unit = {}) {
    var controls by remember { mutableStateOf(true) }
    var interaction by remember { mutableLongStateOf(0L) }
    var more by remember(initialMore) { mutableStateOf(initialMore) }
    var stableLocalMode by remember { mutableStateOf(CameraMode.FACE) }
    var arKind by remember { mutableStateOf(com.lazydoglab.zisee.ar.session.MarkerKind.PIN) }
    var arDrawing by remember { mutableStateOf(false) }
    var confirmArClear by remember { mutableStateOf(false) }
    val localMode = if (state.showMe.mode == CameraMode.STARTING) stableLocalMode else state.showMe.mode
    SideEffect { stableLocalMode = localMode }
    // Where the viewer has dragged each thumbnail, as an offset from its stacked position.
    var moved by remember { mutableStateOf(mapOf<String, Offset>()) }
    // Thumbnails parked off an edge, and which edge each went to.
    var parked by remember { mutableStateOf(mapOf<String, Boolean>()) }
    var duration by remember { mutableLongStateOf(0L) }
    var tipInset by remember { mutableStateOf(0.dp) }
    var bottomControlsHeight by remember { mutableStateOf(0.dp) }
    var arToolsHeight by remember { mutableStateOf(0.dp) }
    var topControlsHeight by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    val accessibilityManager = LocalContext.current.getSystemService(AccessibilityManager::class.java)
    var touchExploration by remember { mutableStateOf(accessibilityManager?.isTouchExplorationEnabled == true) }
    DisposableEffect(accessibilityManager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { touchExploration = it }
        accessibilityManager?.addTouchExplorationStateChangeListener(listener)
        onDispose { accessibilityManager?.removeTouchExplorationStateChangeListener(listener) }
    }
    val remoteDual = state.remotePresentation.mode == CameraMode.DUAL
    val scene = !state.remoteShare.sharing && state.remotePresentation.mode in setOf(CameraMode.DUAL, CameraMode.BACK_ONLY, CameraMode.AR)
    val localScene = localMode in setOf(CameraMode.DUAL, CameraMode.BACK_ONLY, CameraMode.AR)
    val starting = state.showMe.mode == CameraMode.STARTING
    // Anything past consent is a live projection as far as the user is concerned, including the
    // window before the first frame: they must be able to stop it throughout.
    val sharing = state.screenShare.phase in setOf(
        com.lazydoglab.zisee.screen.ScreenSharePhase.STARTING,
        com.lazydoglab.zisee.screen.ScreenSharePhase.ACTIVE)
    val hint = state.showMeHint && remoteDual
    // Priority: what is happening now, then what failed, then the one-time teaching hint.
    val tip = when {
        state.stats.audioDevice.state == com.lazydoglab.zisee.rtc.audio.AudioState.INTERRUPTED -> "音频被其他应用中断；可点击扬声器按钮恢复"
        state.stats.audioBandwidth == com.lazydoglab.zisee.rtc.audio.AudioBandwidthMode.AUDIO_ONLY -> "网络较弱，已暂停发送画面以优先保持语音"
        state.arNotice.isNotBlank() -> state.arNotice
        state.arState == com.lazydoglab.zisee.ar.session.ArSessionState.STARTING -> "正在开启 AR…"
        state.arState == com.lazydoglab.zisee.ar.session.ArSessionState.TRACKING_LOST -> "AR 暂时失去跟踪，请缓慢移动手机"
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
    LaunchedEffect(controls, interaction, more, confirmArClear, state.machine.phase, state.showMe.mode,
        state.stats.audioDevice.state, touchExploration) {
        if (controls && !more && !confirmArClear && !touchExploration &&
            state.stats.audioDevice.state != com.lazydoglab.zisee.rtc.audio.AudioState.INTERRUPTED &&
            state.machine.phase == CallPhase.CONNECTED && !starting) {
            delay(5_000); controls = false
        }
    }
    // The hint has taught its gesture once it has been read, whether or not it was used.
    LaunchedEffect(hint) { if (hint) { delay(8_000); onHintSeen() } }
    LaunchedEffect(tip) { if (tip == null) tipInset = 0.dp }
    // Retain measured safe areas while controls are hidden, so default thumbnails stay put.
    Surface(color = CallInk, contentColor = CallText, modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().clickable { controls = !controls; interaction++ }) {
            val order = CallVideoLayout.sources(localMode, state.remotePresentation.mode,
                state.remoteShare.sharing, sharing)
            val main = CallVideoLayout.main(order, state.selectedVideoSource)
            val localArMarking = main == MeScene && localMode == CameraMode.AR &&
                state.arState in setOf(com.lazydoglab.zisee.ar.session.ArSessionState.TRACKING,
                    com.lazydoglab.zisee.ar.session.ArSessionState.TRACKING_LOST,
                    com.lazydoglab.zisee.ar.session.ArSessionState.SCANNING)
            val remoteArMarking = main == PeerScene && state.remotePresentation.mode == CameraMode.AR &&
                state.arCollaboration.joined
            val compactHeight = maxHeight < 420.dp
            LaunchedEffect(localArMarking, remoteArMarking) {
                if (!localArMarking && !remoteArMarking) arToolsHeight = 0.dp
            }
            val thumbs = order.filter { it != main }
            // Report the resolved main view, including the automatic Show Me choice.
            LaunchedEffect(main, state.remotePresentation.mode) {
                val request = CallVideoLayout.remoteView(main, state.remotePresentation.mode)
                onViewLayout(request.front == com.lazydoglab.zisee.rtc.ViewSize.LARGE,
                    request.back == com.lazydoglab.zisee.rtc.ViewSize.LARGE)
            }
            val aspects = mapOf(
                MeFace to videoAspect(state.local),
                MeScene to videoAspect(if (localMode in setOf(CameraMode.DUAL, CameraMode.AR)) state.localBack else state.local),
                PeerFace to videoAspect(state.remote),
                PeerScene to videoAspect(if (remoteDual || state.remotePresentation.mode == CameraMode.AR) state.remoteBack else state.remote),
                PeerScreen to videoAspect(state.remoteScreen),
            )
            val positions = CallVideoLayout.thumbnails(maxWidth.value.coerceAtLeast(1f),
                maxHeight.value.coerceAtLeast(1f), thumbs.map { aspects.getValue(it) },
                (bottomControlsHeight + arToolsHeight + tipInset + 12.dp).value)
            val tiles = thumbs.zip(positions).toMap()
            // Store user placements as window-relative coordinates, independent of default slots
            // and transient controls. Clamp for the current tile size without losing the memory.
            fun rememberedOffset(tile: String): Offset {
                val saved = moved[tile] ?: return Offset.Zero
                val rect = tiles.getValue(tile)
                return with(density) { Offset((saved.x * maxWidth.value - rect.x).dp.toPx(),
                    (saved.y * maxHeight.value - rect.y).dp.toPx()) }
            }
            fun saveOffset(tile: String, offset: Offset) {
                val rect = tiles.getValue(tile)
                moved = moved + (tile to with(density) { Offset(
                    (rect.x + offset.x.toDp().value) / maxWidth.value,
                    (rect.y + offset.y.toDp().value) / maxHeight.value) })
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
            // [video] omits the rounding and border: the picture rounds itself through the
            // renderer's outline (an ancestor clip makes a TextureView composite as nothing), and
            // the gesture overlay drawn on top of it already carries the border.
            fun slot(tile: String, video: Boolean = false): Modifier {
                // Every tile stacks through CallVideoLayout.stack; see it for why fixed renderer
                // call sites make an explicit z index the only thing keeping thumbnails visible.
                if (tile == main) return Modifier.fillMaxSize().zIndex(CallVideoLayout.stack(tile, main))
                val rect = tiles.getValue(tile)
                // Clamp during composition as well: layout changes precede the reset effect.
                val drag = inside(tile, rememberedOffset(tile))
                val y = with(density) { rect.y.dp.toPx() } + drag.y
                parked[tile]?.let { left ->
                    val handleHeight = minOf(56.dp, maxHeight)
                    return Modifier.align(Alignment.TopStart).zIndex(CallVideoLayout.stack(tile, main))
                        .offset { IntOffset(if (left) 0 else with(density) { (maxWidth - 14.dp).toPx().roundToInt() },
                            y.coerceIn(0f, with(density) { (maxHeight - handleHeight).toPx() }).roundToInt()) }
                        .size(14.dp, handleHeight)
                        .clip(if (left) HandleLeftShape else HandleRightShape)
                        .background(DockInk.copy(alpha = 0.82f))
                        .border(1.dp, CallText.copy(alpha = 0.16f),
                            if (left) HandleLeftShape else HandleRightShape)
                }
                val placed = Modifier.align(Alignment.TopStart).zIndex(CallVideoLayout.stack(tile, main))
                    .offset { IntOffset((with(density) { rect.x.dp.toPx() } + drag.x).roundToInt(), y.roundToInt()) }
                    .size(rect.width.dp, rect.height.dp)
                return if (video) placed
                else placed.clip(PipShape).border(1.dp, CallText.copy(alpha = 0.16f), PipShape)
            }
            // Fixed call sites keep each renderer's node identity across a swap. Moving a renderer
            // between parents would recreate its surface and flash black.
            // A parked tile is a bare edge handle, and the main view fills the screen; only a
            // real thumbnail rounds its picture.
            fun corner(tile: String) = if (tile == main || tile in parked) 0.dp else PipCorner
            if (MeFace in order) {
                VideoTile(state.local, state.cameraEnabled && (MeFace == main || MeFace !in parked),
                    slot(MeFace, video = true), MeFace != main, corner(MeFace))
            }
            if (MeScene in order) {
                VideoTile(if (localMode in setOf(CameraMode.DUAL, CameraMode.AR)) state.localBack else state.local,
                    state.cameraEnabled && (MeScene == main || MeScene !in parked),
                    slot(MeScene, video = true), MeScene != main, corner(MeScene),
                    if (localArMarking && !arDrawing) { frame, point -> onArMarker(frame, point, arKind) } else null,
                    if (localArMarking && arDrawing) onArStroke else null)
            }
            if (PeerFace in order) {
                VideoTile(state.remote, state.remotePresentation.enabled && (PeerFace == main || PeerFace !in parked),
                    slot(PeerFace, video = true), PeerFace != main, corner(PeerFace))
            }
            if (PeerScene in order) {
                VideoTile(if (state.remotePresentation.mode in setOf(CameraMode.DUAL, CameraMode.AR)) state.remoteBack else state.remote,
                    state.remotePresentation.enabled && (PeerScene == main || PeerScene !in parked),
                    slot(PeerScene, video = true), PeerScene != main, corner(PeerScene),
                    if (remoteArMarking) { frame, point -> onArMarker(frame, point, arKind) } else null)
            }
            if (PeerScreen in order) {
                VideoTile(state.remoteScreen, state.remoteShare.sharing && (PeerScreen == main || PeerScreen !in parked),
                    slot(PeerScreen, video = true), PeerScreen != main, corner(PeerScreen))
            }
            // §5.1: the peer is only "sharing" once its frames arrive here. Until then say so
            // rather than showing an empty picture that looks like a failure.
            if (main == PeerScreen && !videoReady(state.remoteScreen)) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(Modifier.size(26.dp), color = CallAccent, strokeWidth = 2.dp)
                    Text("正在等待共享画面…", Modifier.padding(top = 14.dp), color = CallText.copy(alpha = 0.7f))
                }
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
                                interaction++
                                saveOffset(tile, inside(tile, rememberedOffset(tile) + delta))
                            },
                            onDragEnd = { saveOffset(tile, settled(tile, rememberedOffset(tile))) },
                        )
                    }
                    .clickable {
                        interaction++; onHintSeen()
                        // A handle restores the thumbnail; a thumbnail becomes the main view.
                        if (tile in parked) { parked = parked - tile; moved = moved - tile }
                        else onSelectVideo(tile)
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
            if (tip != null && !(compactHeight && (localArMarking || remoteArMarking))) Box(Modifier.align(Alignment.BottomEnd).safeDrawingPadding()
                .zIndex(2f)
                .padding(end = 16.dp, bottom = bottomControlsHeight + arToolsHeight + 12.dp).widthIn(max = 240.dp)
                .onSizeChanged { tipInset = with(density) { it.height.toDp() } + 8.dp }
                .clip(RoundedCornerShape(16.dp)).background(DockInk.copy(alpha = 0.82f))
                .border(1.dp, CallText.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                .padding(horizontal = 13.dp, vertical = 9.dp)) {
                Text(tip, fontSize = 12.sp, lineHeight = 18.sp, color = CallMuted)
            }
            // §3.2: stopping a collaboration is a permanent control, never hidden behind "更多",
            // and it stays on screen while the auto-hiding call controls are away.
            if (sharing) Column(Modifier.align(if (compactHeight) Alignment.BottomCenter else Alignment.TopCenter)
                .safeDrawingPadding().zIndex(2f).padding(horizontal = 16.dp)
                .then(if (compactHeight) Modifier.padding(bottom = bottomControlsHeight + 8.dp)
                    else Modifier.padding(top = topControlsHeight + 8.dp))
                .widthIn(max = 440.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(DockInk.copy(alpha = 0.88f))
                .border(1.dp, CallAccent.copy(alpha = .25f), RoundedCornerShape(20.dp))
                .padding(horizontal = 14.dp, vertical = if (compactHeight) 0.dp else 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val shareActive = state.screenShare.phase == com.lazydoglab.zisee.screen.ScreenSharePhase.ACTIVE
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(if (shareActive) "正在共享屏幕" else "正在准备共享…",
                        fontSize = 13.sp, color = CallText, fontWeight = FontWeight.Medium)
                    TextButton(onClick = { interaction++; onStopShare() },
                        colors = ButtonDefaults.textButtonColors(contentColor = CallDanger)) { Text("停止共享") }
                }
                // §6.1: manual, always-available choice; no automatic classification in V1.
                if (shareActive && !compactHeight) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(com.lazydoglab.zisee.rtc.ScreenContentMode.TEXT to "文字清晰",
                        com.lazydoglab.zisee.rtc.ScreenContentMode.MOTION to "动态流畅").forEach { (mode, label) ->
                        TextButton(onClick = { interaction++; onSetScreenContentMode(mode) },
                            modifier = Modifier.semantics { selected = state.screenContentMode == mode },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = if (state.screenContentMode == mode) CallAccent else CallMuted)) {
                            Text(label, fontSize = 12.sp)
                        }
                    }
                }
            }
            if (localArMarking || remoteArMarking) {
                val toolModifier = Modifier.align(Alignment.BottomCenter).zIndex(2f).safeDrawingPadding()
                    .padding(horizontal = 16.dp).padding(bottom = bottomControlsHeight + 8.dp)
                    .onSizeChanged { arToolsHeight = with(density) { it.height.toDp() } }
                    .clip(RoundedCornerShape(22.dp)).background(DockInk.copy(alpha = 0.88f))
                    .border(1.dp, CallAccent.copy(alpha = .25f), RoundedCornerShape(22.dp))
                    .then(if (compactHeight) Modifier.horizontalScroll(rememberScrollState()) else Modifier)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                val tools: @Composable () -> Unit = {
                listOf(
                    com.lazydoglab.zisee.ar.session.MarkerKind.PIN to "标点",
                    com.lazydoglab.zisee.ar.session.MarkerKind.ARROW to "箭头",
                    com.lazydoglab.zisee.ar.session.MarkerKind.CIRCLE to "圈",
                ).forEach { (kind, label) ->
                    TextButton(onClick = { interaction++; arKind = kind; arDrawing = false },
                        modifier = Modifier.defaultMinSize(minWidth = 48.dp).semantics { selected = arKind == kind && (!arDrawing || !localArMarking) },
                        contentPadding = PaddingValues(horizontal = 6.dp),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = if (arKind == kind && (!arDrawing || !localArMarking)) CallAccent else CallMuted)) { Text(label) }
                }
                if (localArMarking) TextButton(onClick = { interaction++; arDrawing = true },
                    modifier = Modifier.defaultMinSize(minWidth = 48.dp).semantics { selected = arDrawing },
                    contentPadding = PaddingValues(horizontal = 6.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = if (arDrawing) CallAccent else CallMuted)) { Text("手绘") }
                if (localArMarking) TextButton(onClick = onStopAr,
                    colors = ButtonDefaults.textButtonColors(contentColor = CallDanger)) { Text("停止 AR") }
                TextButton(enabled = state.arOwnMarkerCount > 0, onClick = { interaction++; onArUndo() },
                    modifier = Modifier.defaultMinSize(minWidth = 48.dp), contentPadding = PaddingValues(horizontal = 6.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = CallAccent)) { Text("撤销") }
                TextButton(enabled = state.arOwnMarkerCount > 0, onClick = { interaction++; confirmArClear = true },
                    modifier = Modifier.defaultMinSize(minWidth = 48.dp), contentPadding = PaddingValues(horizontal = 6.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = CallDanger)) { Text("清除我的") }
                if (compactHeight && tip != null) Text(tip, Modifier.padding(horizontal = 8.dp),
                    fontSize = 12.sp, color = CallMuted)
                }
                if (compactHeight) Row(toolModifier, verticalAlignment = Alignment.CenterVertically) { tools() }
                else FlowRow(toolModifier, horizontalArrangement = Arrangement.Center,
                    verticalArrangement = Arrangement.Center) { tools() }
            }
            if (controls) {
                Box(Modifier.fillMaxWidth().height(190.dp)
                    .background(Brush.verticalGradient(listOf(CallScrim.copy(alpha = 0.72f), Color.Transparent))))
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(260.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, CallScrim.copy(alpha = 0.78f)))))
                Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().safeDrawingPadding()
                    .zIndex(3f).padding(horizontal = 20.dp, vertical = 14.dp)
                    .onSizeChanged { topControlsHeight = with(density) { it.height.toDp() } },
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(state.peerName, fontSize = 19.sp, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            if (state.machine.phase == CallPhase.CONNECTED) Box(Modifier.size(6.dp).clip(CircleShape).background(CallAccent))
                            Text(if (state.machine.phase == CallPhase.CONNECTED)
                                "%02d:%02d".format(duration / 60, duration % 60) else state.status,
                                fontSize = 13.sp, color = CallMuted, maxLines = 1)
                        }
                        if (scene || state.remoteShare.sharing) Row(Modifier.heightIn(min = 28.dp).clip(RoundedCornerShape(14.dp))
                            .background(CallAccent.copy(alpha = 0.16f))
                            .border(1.dp, CallAccent.copy(alpha = 0.30f), RoundedCornerShape(14.dp))
                            .padding(start = 9.dp, end = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            Canvas(Modifier.size(14.dp)) {
                                scale(size.width / 24f, size.width / 24f, Offset.Zero) { callIcon("show", CallAccent, knockout = CallInk) }
                            }
                            Text(if (state.remoteShare.sharing) "对方屏幕" else "对方现场",
                                fontSize = 12.sp, color = CallAccent, fontWeight = FontWeight.Medium)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (localMode in setOf(CameraMode.FACE, CameraMode.BACK_ONLY) && !sharing && !state.remoteShare.sharing) {
                            Box(Modifier.size(48.dp).clip(CircleShape).background(Color(0xFF0C1216).copy(alpha = 0.5f))
                                .border(1.dp, CallText.copy(alpha = 0.10f), CircleShape)
                                .clickable(enabled = state.cameraEnabled && !starting) { interaction++; onSwitch() }
                                .semantics { role = Role.Button; contentDescription = "切换前后摄像头" },
                                contentAlignment = Alignment.Center) {
                                Canvas(Modifier.size(20.dp)) {
                                    scale(size.width / 24f, size.width / 24f, Offset.Zero) {
                                        callIcon("swap", if (state.cameraEnabled && !starting) CallText else CallFaint)
                                    }
                                }
                            }
                        }
                        Box(Modifier.size(48.dp).clip(CircleShape)
                            .background(if (state.speakerOn) CallAccent else Color(0xFF0C1216).copy(alpha = 0.5f))
                            .border(1.dp, CallText.copy(alpha = 0.10f), CircleShape)
                            .clickable { interaction++; onSpeaker() }
                            .semantics { role = Role.Button; contentDescription = if (state.speakerOn) "免提已开启" else "免提已关闭" },
                            contentAlignment = Alignment.Center) {
                            Canvas(Modifier.size(20.dp)) {
                                scale(size.width / 24f, size.width / 24f, Offset.Zero) {
                                    callIcon(if (state.speakerOn) "speaker" else "speaker-off",
                                        if (state.speakerOn) CallAccentInk else CallText)
                                }
                            }
                        }
                    }
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().safeDrawingPadding()
                    .zIndex(3f).padding(bottom = 10.dp)
                    .onSizeChanged { bottomControlsHeight = with(density) { it.height.toDp() } },
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.widthIn(max = 440.dp).fillMaxWidth().padding(horizontal = 12.dp).clip(DockShape)
                        .background(DockInk.copy(alpha = 0.66f))
                        .border(1.dp, CallText.copy(alpha = 0.08f), DockShape).padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.Top) {
                        DockButton(if (state.muted) "已静音" else "静音", "mic", off = state.muted,
                            modifier = Modifier.weight(1f),
                            showLabel = true, description = if (state.muted) "麦克风已静音，点按取消静音" else "点按静音") { interaction++; onMute() }
                        // A share owns every camera, so both camera controls wait for it to end
                        // rather than offering an action that cannot take effect.
                        DockButton(if (sharing) "已暂停" else if (state.cameraEnabled) "摄像头" else "已关闭",
                            "camera", off = sharing || !state.cameraEnabled,
                            modifier = Modifier.weight(1f),
                            enabled = !sharing, showLabel = true,
                            description = if (sharing) "屏幕共享期间摄像头已暂停" else if (state.cameraEnabled) "点按关闭摄像头" else "摄像头已关闭，点按开启") { interaction++; onCamera() }
                        DockButton(if (localScene) "看我" else "给你看", "show", active = localScene && !sharing,
                            modifier = Modifier.weight(1f),
                            available = !localScene, enabled = state.cameraEnabled && !starting && !sharing,
                            showLabel = true, description = if (sharing) "屏幕共享期间无法开启现场" else "点按${if (localScene) "切回人像" else "展示现场"}") { interaction++; onShowMe() }
                        DockButton("更多", "more", modifier = Modifier.weight(1f), showLabel = true) { interaction++; more = true }
                        DockButton("挂断", "end", modifier = Modifier.weight(1f), danger = true, showLabel = true, action = onEnd)
                    }
                }
            }
        }
        if (more) CallOptions(state, onDismiss = { more = false; interaction++ }, onSwitch = onSwitch,
            onNoiseMode = onNoiseMode, sharing = sharing,
            onStartShare = onStartShare, onStopShare = onStopShare,
            onSetScreenContentMode = onSetScreenContentMode,
            arControls = arControls, debugControls = debugControls)
        if (confirmArClear) AlertDialog(onDismissRequest = { confirmArClear = false },
            title = { Text("清除我的标记？") },
            text = { Text("双方都将看不到你在这个现场放置的 ${state.arOwnMarkerCount} 个标记。此操作不能撤销。") },
            dismissButton = { TextButton(onClick = { confirmArClear = false }) { Text("取消") } },
            confirmButton = { TextButton(onClick = { confirmArClear = false; onArClearOwn() }) { Text("清除") } })
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CallOptions(state: CallUiState, onDismiss: () -> Unit, onSwitch: () -> Unit,
    onNoiseMode: (com.lazydoglab.zisee.rtc.audio.processing.NoiseSuppressionMode) -> Unit,
    sharing: Boolean, onStartShare: () -> Unit, onStopShare: () -> Unit,
    onSetScreenContentMode: (com.lazydoglab.zisee.rtc.ScreenContentMode) -> Unit,
    arControls: @Composable () -> Unit, debugControls: @Composable () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    MaterialTheme(colorScheme = darkColorScheme(primary = CallAccent, onPrimary = CallAccentInk,
        surface = CallPanel, onSurface = CallText)) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState,
        containerColor = CallPanel, contentColor = CallText) {
        DarkSheetSystemBars()
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("通话选项", style = MaterialTheme.typography.titleMedium)
            OptionGroup("画面与共享") {
                val shareBlocker = when {
                    state.showMe.mode == CameraMode.AR -> "请先结束我的 AR 现场"
                    state.remoteShare.sharing -> "对方正在共享屏幕"
                    else -> null
                }
                FullWidthAction(if (sharing) "停止共享屏幕" else "共享我的屏幕",
                    enabled = sharing || shareBlocker == null, danger = sharing) {
                    onDismiss(); if (sharing) onStopShare() else onStartShare()
                }
                Text(shareBlocker ?: "共享期间摄像头会暂停，停止后自动恢复；对方不能操作你的手机。",
                    style = MaterialTheme.typography.bodySmall, color = CallMuted)
                FullWidthAction("切换前后摄像头",
                    enabled = !sharing && state.cameraEnabled && state.showMe.mode !in setOf(CameraMode.STARTING, CameraMode.AR)) {
                    onDismiss(); onSwitch()
                }
                Text(if (sharing) "停止共享后才能切换摄像头。" else
                    "支持双摄时「给你看」会同时展示人像与现场，否则使用后摄。",
                    style = MaterialTheme.typography.bodySmall, color = CallMuted)
                if (sharing && state.screenShare.phase == com.lazydoglab.zisee.screen.ScreenSharePhase.ACTIVE) {
                    Text("共享画面质量", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(com.lazydoglab.zisee.rtc.ScreenContentMode.TEXT to "文字清晰",
                            com.lazydoglab.zisee.rtc.ScreenContentMode.MOTION to "动态流畅").forEach { (mode, label) ->
                            TextButton(onClick = { onSetScreenContentMode(mode) },
                                modifier = Modifier.semantics { selected = state.screenContentMode == mode },
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = if (state.screenContentMode == mode) CallAccent else CallMuted)) {
                                Text(label)
                            }
                        }
                    }
                }
            }
            OptionGroup("AR 现场协作") { arControls() }
            OptionGroup("声音") {
                Text("麦克风降噪", style = MaterialTheme.typography.titleSmall)
                for (mode in com.lazydoglab.zisee.rtc.audio.processing.NoiseSuppressionMode.entries) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(selected = state.stats.audioProcessing.mode == mode,
                            role = Role.RadioButton, onClick = { onNoiseMode(mode) }),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = state.stats.audioProcessing.mode == mode, onClick = null)
                        Text(mode.label)
                    }
                }
                Text(if (state.stats.audioProcessing.state == com.lazydoglab.zisee.rtc.audio.processing.AiState.DEGRADED)
                    "当前使用标准降噪，以保持语音连续。" else "AI 在本机处理语音。自动模式会根据设备状态降级。",
                    style = MaterialTheme.typography.bodySmall, color = CallMuted)
            }
            if (BuildConfig.DEBUG) {
                val stats = state.stats
                OptionGroup("调试详情") {
                    Text("${stats.candidateType} → ${stats.remoteCandidateType} · RTT ${stats.measuredRttMs ?: "—"} ms\n" +
                    "接收 ${stats.videoWidth} × ${stats.videoHeight} · ${stats.videoFps} fps\n" +
                    "发送 ${stats.sentWidth} × ${stats.sentHeight} · ${stats.sentFps} fps\n" +
                    "收/发 ${stats.receiveKbps}/${stats.sendKbps} kbps · 上行估计 ${stats.availableOutgoingKbps ?: "—"} kbps\n" +
                    "${stats.codec} · ${stats.encoder}\n限制 ${stats.qualityLimitation} · 热状态 ${stats.thermalStatus ?: "—"}",
                        style = MaterialTheme.typography.bodySmall, color = CallMuted)
                val audio = stats.audio
                stats.computeQuality.forEach { (track, detail) ->
                    Text("视频计算 $track · $detail", style = MaterialTheme.typography.bodySmall, color = CallMuted)
                }
                stats.encoderTiming.forEach { timing ->
                    Text("编码回调 ${timing.trackId ?: "未归属"}/${timing.codec} · ${timing.samples} 帧\n" +
                        "P50/95/99 ${timing.callbackP50Ms?.toInt()}/${timing.callbackP95Ms?.toInt()}/${timing.callbackP99Ms?.toInt()} ms · QP ${timing.qpMean?.toInt() ?: "—"}",
                        style = MaterialTheme.typography.bodySmall, color = CallMuted)
                }
                stats.codecCapabilities?.let { capability ->
                    Text("WebRTC 硬编声明 ${capability.webRtcHardwareFormats?.sorted()?.joinToString()?.ifEmpty { "无" } ?: "未知"} · " +
                        "Android codec ${capability.components.size} 项（声明值，非实测）",
                        style = MaterialTheme.typography.bodySmall, color = CallMuted)
                }
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
            debugControls()
        }
    }
    }
}

@Composable
private fun DarkSheetSystemBars() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window
        val bars = window?.let { WindowInsetsControllerCompat(it, view) }
        val previousStatus = bars?.isAppearanceLightStatusBars
        val previousNavigation = bars?.isAppearanceLightNavigationBars
        bars?.isAppearanceLightStatusBars = false
        bars?.isAppearanceLightNavigationBars = false
        onDispose {
            previousStatus?.let { bars?.isAppearanceLightStatusBars = it }
            previousNavigation?.let { bars?.isAppearanceLightNavigationBars = it }
        }
    }
}

@Composable
private fun OptionGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(StandardCardShape)
        .background(CallText.copy(alpha = 0.06f)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = CallText)
        content()
    }
}

@Composable
private fun FullWidthAction(label: String, enabled: Boolean = true, danger: Boolean = false,
    onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = if (danger) CallDanger else CallAccent)) {
        Text(label, modifier = Modifier.fillMaxWidth())
    }
}

/** Read each track's upright frame dimensions, never infer the peer's direction from our window. */
@Composable
private fun videoAspect(feed: com.lazydoglab.zisee.rtc.VideoFeed?): Float {
    if (feed == null) return 9f / 16f
    val geometry by feed.geometry.collectAsState()
    return geometry?.aspectRatio ?: (9f / 16f)
}

/** A feed only has geometry once a frame has actually been decoded from it. */
@Composable
private fun videoReady(feed: com.lazydoglab.zisee.rtc.VideoFeed?): Boolean {
    if (feed == null) return false
    val geometry by feed.geometry.collectAsState()
    return geometry != null
}

/** A camera's slot in the layout: its picture when it is live, its dark placeholder when not. */
@Composable
private fun VideoTile(feed: com.lazydoglab.zisee.rtc.VideoFeed?, live: Boolean, modifier: Modifier,
    thumbnail: Boolean, corner: Dp = 0.dp,
    onArTap: ((com.lazydoglab.zisee.rtc.TextureViewRenderer.DisplayedArFrame,
        com.lazydoglab.zisee.ar.annotation.VideoPoint) -> Unit)? = null,
    onArStroke: ((com.lazydoglab.zisee.ar.annotation.ArStrokeInput) -> Unit)? = null) {
    // The picture rounds itself inside the renderer; only the placeholder can be clipped out here,
    // because it is ordinary Compose drawing rather than a TextureView's own layer.
    if (live && feed != null) VideoRenderer(feed, modifier, corner, onArTap, onArStroke)
    // The main tile already sits on the call surface's own background; only a thumbnail needs its
    // placeholder painted, so the base surface is not redrawn under a live full-screen picture.
    else Box(if (!thumbnail) modifier
        else if (corner > 0.dp) modifier.clip(RoundedCornerShape(corner)).background(CallInk)
        else modifier.background(CallInk))
}

@Composable
private fun BoxScope.PipLabel(text: String, compact: Boolean = false) {
    Box(Modifier.align(Alignment.BottomStart).padding(if (compact) 2.dp else 8.dp).heightIn(min = 20.dp)
        .clip(RoundedCornerShape(10.dp)).background(BadgeInk.copy(alpha = 0.66f))
        .padding(horizontal = if (compact) 3.dp else 8.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center) {
        Text(text, fontSize = 11.sp, color = PipInk)
    }
}

/**
 * A dock control. State is carried by shape and a slash rather than colour alone, so mute and
 * camera-off stay legible without colour vision and read correctly to TalkBack.
 */
@Composable
internal fun DockButton(label: String, kind: String, off: Boolean = false, active: Boolean = false,
    available: Boolean = false, danger: Boolean = false, enabled: Boolean = true,
    width: Dp = 56.dp, modifier: Modifier = Modifier, showLabel: Boolean = false,
    description: String = label, action: () -> Unit) {
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
    Column(modifier.then(Modifier.widthIn(min = if (showLabel) 48.dp else width).heightIn(min = 56.dp))
        .clip(if (showLabel) RoundedCornerShape(22.dp) else ButtonShape)
        .clickable(enabled = enabled, onClick = action)
        .semantics { role = Role.Button; contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(if (showLabel) 48.dp else 56.dp).clip(ButtonShape)
            .background(background.copy(alpha = background.alpha * dim))
            .border(1.dp, outline.copy(alpha = outline.alpha * dim), ButtonShape),
            contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(23.dp)) {
                scale(size.width / 24f, size.width / 24f, Offset.Zero) {
                    callIcon(kind, ink.copy(alpha = ink.alpha * dim), knockout, off)
                }
            }
        }
        if (showLabel) Text(label, fontSize = 11.sp, lineHeight = 13.sp,
            color = CallText.copy(alpha = dim), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/** Design glyphs drawn in the shared 24-unit viewBox used across the canvases. */
internal fun DrawScope.callIcon(kind: String, ink: Color, knockout: Color = Color.Transparent, off: Boolean = false) {
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
        "close" -> path("M6 6l12 12M18 6L6 18")
        "code" -> { drawRoundRect(ink, Offset(3f, 5f), Size(18f, 14f), CornerRadius(3f), style = stroke); path("M8 12h8M12 9v6") }
        "copy" -> {
            drawRoundRect(ink, Offset(8.5f, 8.5f), Size(12f, 12f), CornerRadius(2.6f), style = stroke)
            path("M15.5 5.5A2.5 2.5 0 0 0 13 3H6a3 3 0 0 0-3 3v7a2.5 2.5 0 0 0 2.5 2.5")
        }
        "share" -> { path("M12 16V4"); path("M7.5 8.5 12 4l4.5 4.5"); path("M4.5 14v4.5a2 2 0 0 0 2 2h11a2 2 0 0 0 2-2V14") }
        "contact" -> { drawCircle(ink, 4f, Offset(10f, 8.5f), style = stroke); path("M3 20c0-3.6 3.1-6 7-6 1.5 0 2.9.35 4 .96"); path("M18 14v6M15 17h6") }
        "lock" -> { drawRoundRect(ink, Offset(4f, 10.5f), Size(16f, 10.5f), CornerRadius(3f), style = stroke); path("M8 10.5V7.5a4 4 0 0 1 8 0v3") }
        "back" -> path("M15 5l-7 7 7 7")
        "minimize" -> path("M5 9l7 7 7-7")
        "forward" -> path("M9 5l7 7-7 7")
        "gear" -> {
            drawCircle(ink, 3.2f, Offset(12f, 12f), style = stroke)
            path("M19.6 14.4a1.7 1.7 0 0 0 .34 1.87l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.7 1.7 0 0 0-2.9 1.22V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-2.9-1.16l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.7 1.7 0 0 0-1.16-2.9H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.16-2.9l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.7 1.7 0 0 0 2.9-1.16V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 2.9 1.16l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.7 1.7 0 0 0 1.16 2.9H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.3.41z")
        }
    }
    if (off) path("M4 4 20 20")
}
