package com.lazydoglab.zisee.ui

import android.Manifest
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.lazydoglab.zisee.BuildConfig
import com.lazydoglab.zisee.call.CallUiState
import com.lazydoglab.zisee.call.CallViewModel
import com.lazydoglab.zisee.call.Contact
import com.lazydoglab.zisee.call.state.CallPhase
import com.lazydoglab.zisee.invite.InviteLink
import com.lazydoglab.zisee.rtc.TextureViewRenderer
import com.lazydoglab.zisee.rtc.VideoFeed

/**
 * Invite.dc.html: the call surface, entered only once a call or an invitation actually exists.
 * The home screen it used to duplicate in dark now lives in [ZiseeApp], which is the same
 * Main.dc.html/HomeLight.dc.html layout following the system theme.
 * [ActiveCall] takes over once media is flowing.
 */
@Composable
fun CallScreen(state: CallUiState, model: CallViewModel, onMinimize: () -> Unit = {}) {
    var accepting by remember { mutableStateOf(false) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants[Manifest.permission.CAMERA] == true && grants[Manifest.permission.RECORD_AUDIO] == true) model.accept()
        else model.permissionsDenied()
        accepting = false
    }
    BackHandler { if (state.local != null && state.busy) onMinimize() else model.close() }
    if (state.local != null && state.busy) {
        ArCallGeometry(state, model)
        ActiveCall(state, model::toggleMute, model::toggleCamera, { model.toggleShowMe() },
            { model.toggleShowMe(false) }, model::toggleSpeaker, model::stop, onMinimize,
            model::dismissShowMeHint,
            model::reportViewLayout, model::setNoiseSuppression,
            { frame, point, kind -> model.createArMarker(frame, point, kind) }, model::undoArMarker,
            model::clearOwnArMarkers,
            onSelectVideo = model::selectVideoSource,
            onStartShare = model::startScreenShare, onStopShare = { model.stopScreenShare() },
            onSetScreenContentMode = model::setScreenContentMode,
            arControls = { ArCallControls(state, model) })
        return
    }
    Surface(color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground, modifier = Modifier.fillMaxSize()) {
        when {
            state.busy && state.machine.phase == CallPhase.INCOMING ->
                IncomingCall(state.peerName, busy = accepting, onReject = model::reject, onAccept = {
                    accepting = true
                    permissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
                })
            // Shown from the first frame, code or not: the code arrives one request later and the
            // page fills in, rather than a "calling" screen flashing at a peer who does not exist yet.
            state.inviting -> InvitePending(state.invite, onClose = model::close)
            else -> ConnectingCall(state.peerName, state.status, onEnd = model::stop)
        }
    }
}

/**
 * Invite.dc.html: white QR card (needs light-on-dark contrast to scan), link row, share action.
 * An empty [invite] is the moment before the server has issued the code: the page is already the
 * right page, so it fills in place rather than being preceded by another screen.
 */
@Composable
private fun InvitePending(invite: String, onClose: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val ready = invite.isNotEmpty()
    val link = if (ready) InviteLink.of(invite) else ""
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp).padding(top = 20.dp, bottom = 28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(24.dp).clickable(onClick = onClose)
                .semantics { role = Role.Button; contentDescription = "关闭" }) {
                val ink = MaterialTheme.colorScheme.onBackground
                Canvas(Modifier.fillMaxSize()) { callIcon("close", ink) }
            }
            Text("邀请", fontSize = 22.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.2).sp)
        }
        Spacer(Modifier.height(28.dp))
        Text("让对方扫码或打开链接，就能看到你并直接开始通话。", fontSize = 15.sp, lineHeight = 24.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(26.dp))
        Column(Modifier.fillMaxWidth().clip(CardShape).background(Color(0xFFF4F6F7))
            .padding(26.dp, 26.dp, 26.dp, 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // The card keeps its size either way, so the page does not jump when the code lands.
            Box(Modifier.size(189.dp), contentAlignment = Alignment.Center) {
                if (ready) QrCode(link, Modifier.fillMaxSize())
                else CircularProgressIndicator(Modifier.size(26.dp), color = Color(0xFF78868E), strokeWidth = 2.dp)
            }
            Spacer(Modifier.height(18.dp))
            Text(if (ready) "用 Zisee 扫码" else "正在生成邀请码…", fontSize = 12.5.sp, color = Color(0xFF78868E))
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().height(58.dp).clip(PillShape).background(MaterialTheme.colorScheme.surface)
            .padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (ready) link else "…", Modifier.weight(1f), fontSize = 14.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            if (ready) RoundIconButton("copy", "复制邀请链接", size = 44.dp) { clipboard.setText(AnnotatedString(link)) }
        }
        Spacer(Modifier.height(12.dp))
        PrimaryButton("分享邀请", "share", height = 62.dp, enabled = ready, onClick = {
            val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, link) }
            context.startActivity(Intent.createChooser(send, null))
        })
        Spacer(Modifier.height(14.dp))
        if (ready) Text("复制邀请码", Modifier.align(Alignment.CenterHorizontally)
            .clickable { clipboard.setText(AnnotatedString(invite)) }.padding(8.dp),
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Text("链接只包含一个邀请码，不包含你的手机号或邮箱。", fontSize = 12.sp, lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

/** No incoming-call mock exists; built from the shared avatar + Foundations control-ball pattern. */
@Composable
private fun IncomingCall(peerName: String, busy: Boolean, onAccept: () -> Unit, onReject: () -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        PersonAvatar(peerName, size = 108.dp)
        Spacer(Modifier.height(22.dp))
        Text(peerName, fontSize = 27.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.3).sp)
        Spacer(Modifier.height(10.dp))
        Text("Zisee 视频来电", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(56.dp)) {
            ControlBall("拒绝", "end", danger = true, enabled = !busy, onClick = onReject)
            ControlBall("接听", "camera", accent = true, enabled = !busy, onClick = onAccept)
        }
        Spacer(Modifier.height(48.dp))
    }
}

/** Covers dialing, connecting and reconnecting: one hero, one hangup control. */
@Composable
private fun ConnectingCall(peerName: String, status: String, onEnd: () -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        PersonAvatar(peerName, size = 108.dp)
        Spacer(Modifier.height(22.dp))
        Text(peerName, fontSize = 27.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.3).sp)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            CircularProgressIndicator(Modifier.size(13.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, strokeWidth = 1.6.dp)
            Text(status, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.weight(1f))
        ControlBall("挂断", "end", danger = true, onClick = onEnd)
        Spacer(Modifier.height(48.dp))
    }
}

@Composable
private fun PersonAvatar(name: String, size: Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center) {
        Text(name.take(1), fontSize = (size.value * 0.35f).sp, fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PrimaryButton(label: String, icon: String, height: Dp, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(height).clip(PillShape)
        .background(MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.35f))
        .clickable(enabled = enabled, onClick = onClick)
        .semantics { role = Role.Button; contentDescription = label },
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        val ink = MaterialTheme.colorScheme.onPrimary
        Canvas(Modifier.size(21.dp)) { scale(size.width / 24f, size.width / 24f, Offset.Zero) { callIcon(icon, ink) } }
        Spacer(Modifier.width(10.dp))
        Text(label, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = ink)
    }
}

@Composable
private fun RoundIconButton(icon: String, description: String, size: Dp,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    background: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
    enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.size(size).clip(CircleShape).background(background.copy(alpha = background.alpha * if (enabled) 1f else 0.4f))
        .clickable(enabled = enabled, onClick = onClick)
        .semantics { role = Role.Button; contentDescription = description },
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size * 0.4f)) {
            scale(this.size.width / 24f, this.size.width / 24f, Offset.Zero) { callIcon(icon, tint.copy(alpha = tint.alpha * if (enabled) 1f else 0.4f)) }
        }
    }
}

/** A large isolated action, distinct from the in-call dock: labelled below, per Foundations.dc.html. */
@Composable
private fun ControlBall(label: String, icon: String, danger: Boolean = false, accent: Boolean = false,
    enabled: Boolean = true, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        DockButton(label, icon, danger = danger, active = accent, enabled = enabled, action = onClick)
        Spacer(Modifier.height(9.dp))
        Text(label, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * [corner] rounds the picture through the renderer's own outline. It must not be done with
 * `Modifier.clip` out here: that would draw the TextureView into an ancestor's layer, where its
 * texture composites as nothing and the tile goes transparent. See [TextureViewRenderer].
 */
@Composable
internal fun VideoRenderer(feed: VideoFeed, modifier: Modifier, corner: Dp = 0.dp,
    onArTap: ((com.lazydoglab.zisee.rtc.TextureViewRenderer.DisplayedArFrame,
        com.lazydoglab.zisee.ar.annotation.VideoPoint) -> Unit)? = null) {
    androidx.compose.runtime.key(feed) {
        val geometry by feed.geometry.collectAsState()
        val cornerPx = with(androidx.compose.ui.platform.LocalDensity.current) { corner.toPx() }
        var renderer by remember(feed) { mutableStateOf<com.lazydoglab.zisee.rtc.TextureViewRenderer?>(null) }
        // The role changes outside BoxWithConstraints' size subcomposition. Update the retained
        // view here, so a main/thumbnail swap cannot reuse that subcomposition's old radius.
        androidx.compose.runtime.SideEffect { renderer?.cornerRadius = cornerPx }
        val displayedState = renderer?.displayedArFrame
        val displayed = if (displayedState != null) displayedState.collectAsState().value else null
        // The call surface supplies the bars behind a tile smaller than its box.
        BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
            // Compose imposes EXACT dimensions, so size the surface to the upright frame itself
            // and leave bars to its parent, rather than distorting a stretched-to-fit surface.
            val aspect = geometry?.aspectRatio ?: (maxWidth.value / maxHeight.value.coerceAtLeast(1f))
            val width = minOf(maxWidth, maxHeight * aspect)
            val height = minOf(maxHeight, width / aspect.coerceAtLeast(0.001f))
            AndroidView(factory = { context -> TextureViewRenderer(context).also { renderer = it; feed.attach(it) } },
                modifier = Modifier.size(width, height),
                update = {},
                onRelease = { feed.detach(it); if (renderer === it) renderer = null })
            if (onArTap != null && displayed != null) {
                val density = androidx.compose.ui.platform.LocalDensity.current
                val viewportWidth = with(density) { width.toPx() }
                val viewportHeight = with(density) { height.toPx() }
                Box(Modifier.size(width, height).pointerInput(displayed, viewportWidth, viewportHeight) {
                    detectTapGestures { offset ->
                        val point = com.lazydoglab.zisee.ar.annotation.VideoPointMapper.fromViewport(
                            offset.x, offset.y, viewportWidth, viewportHeight,
                            displayed.geometry, displayed.mirrored,
                            com.lazydoglab.zisee.ar.annotation.VideoPointMapper.Scale.FIT)
                        if (point != null) onArTap(displayed, point)
                    }
                }.semantics { contentDescription = "AR 现场，可轻点放置所选标记" })
            }
        }
    }
}
