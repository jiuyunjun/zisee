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
 * Main.dc.html / Invite.dc.html / InviteOpen.dc.html: everything the call feature shows before the
 * live video surface takes over. [ActiveCall] handles the surface itself once media is flowing.
 */
@Composable
fun CallScreen(state: CallUiState, model: CallViewModel) {
    var invite by remember { mutableStateOf("") }
    var showJoin by remember { mutableStateOf(false) }
    // A code that arrived through a link replaces whatever was typed and reopens the join field,
    // so the field matches the invitation the user just opened.
    var pendingDismissed by remember(state.pendingInvite) { mutableStateOf(false) }
    LaunchedEffect(state.pendingInvite) {
        if (state.pendingInvite.isNotEmpty()) { invite = state.pendingInvite; showJoin = true }
    }
    var permissionAction by remember { mutableStateOf<String?>(null) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants[Manifest.permission.CAMERA] == true && grants[Manifest.permission.RECORD_AUDIO] == true) {
            when (permissionAction) { "accept" -> model.accept(); "join" -> model.join(invite.trim()); else -> permissionAction?.removePrefix("contact:")?.let(model::callContact) }
        } else model.permissionsDenied()
        permissionAction = null
    }
    fun request(action: String) {
        permissionAction = action
        permissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }
    BackHandler { model.close() }
    if (state.local != null && state.busy) {
        ActiveCall(state, model::toggleMute, model::toggleCamera, { model.toggleShowMe() },
            { model.toggleShowMe(false) }, model::toggleSpeaker, model::stop, model::dismissShowMeHint,
            model::reportViewLayout, model::setNoiseSuppression)
        return
    }
    Surface(color = CallInk, contentColor = CallText, modifier = Modifier.fillMaxSize()) {
        when {
            state.busy && state.machine.phase == CallPhase.INCOMING ->
                IncomingCall(state.peerName, busy = permissionAction != null,
                    onAccept = { request("accept") }, onReject = model::reject)
            state.busy && state.invite.isNotEmpty() ->
                InvitePending(state.invite, onClose = model::close)
            state.busy ->
                ConnectingCall(state.peerName, state.status, onEnd = model::stop)
            else -> CallHome(state, invite, onInviteChange = { invite = it }, showJoin = showJoin,
                onToggleJoin = { showJoin = !showJoin },
                pendingBanner = state.pendingInvite.isNotEmpty() && !pendingDismissed,
                onOpenPending = { request("join") }, onDismissPending = { pendingDismissed = true },
                onCreateInvite = model::createInvite,
                onJoin = { request("join") }, permissionBusy = permissionAction != null,
                onCallContact = { request("contact:$it") }, onRemoveContact = model::removeContact)
        }
    }
}

/** Main.dc.html: brand, primary/secondary call actions, recent contacts. */
@Composable
private fun CallHome(state: CallUiState, invite: String, onInviteChange: (String) -> Unit,
    showJoin: Boolean, onToggleJoin: () -> Unit, pendingBanner: Boolean, onOpenPending: () -> Unit,
    onDismissPending: () -> Unit, onCreateInvite: () -> Unit, onJoin: () -> Unit, permissionBusy: Boolean,
    onCallContact: (String) -> Unit, onRemoveContact: (String) -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp).padding(top = 20.dp, bottom = 28.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("Zisee", fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp)
            Text("咫尺", fontSize = 11.sp, color = CallFaint, letterSpacing = 5.sp)
        }
        Spacer(Modifier.height(46.dp))
        if (pendingBanner) {
            PendingInviteBanner(onOpenPending, onDismissPending, busy = permissionBusy)
            Spacer(Modifier.height(30.dp))
        } else {
            Text("想让谁\n看看你眼前的东西？", fontSize = 26.sp, lineHeight = 38.sp, letterSpacing = (-0.2).sp)
            Spacer(Modifier.height(30.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryButton("开始通话", "camera", height = 62.dp,
                    enabled = BuildConfig.BACKEND_URL.isNotEmpty(), onClick = onCreateInvite)
                SecondaryButton("输入邀请码", "code", onClick = onToggleJoin)
            }
            AnimatedVisibility(showJoin) {
                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LinkField(invite, onInviteChange, placeholder = "对方的邀请码或链接")
                    PrimaryButton("呼叫对方", "camera", height = 58.dp,
                        enabled = !permissionBusy && invite.isNotBlank() && BuildConfig.BACKEND_URL.isNotEmpty(),
                        onClick = onJoin)
                }
            }
            if (BuildConfig.BACKEND_URL.isEmpty())
                Text("尚未配置通话服务器，暂时无法开始通话。", Modifier.padding(top = 10.dp), fontSize = 12.5.sp, color = CallFaint)
        }
        Spacer(Modifier.height(42.dp))
        if (state.contacts.isNotEmpty()) {
            Text("最近", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = CallFaint, letterSpacing = 1.6.sp)
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                state.contacts.forEach { contact ->
                    ContactRow(contact, enabled = !permissionBusy,
                        onCall = { onCallContact(contact.identityId) }, onRemove = { onRemoveContact(contact.identityId) })
                }
            }
        }
        if (state.contactsStatus.isNotEmpty())
            Text(state.contactsStatus, Modifier.padding(top = 8.dp), fontSize = 12.5.sp, color = CallFaint)
        Spacer(Modifier.height(24.dp))
        Text("接听或呼叫时需要摄像头和麦克风权限。当前测试版离开前台会结束通话。",
            fontSize = 12.sp, lineHeight = 18.sp, color = CallCaption, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth())
    }
}

/** InviteOpen.dc.html, adapted: the caller's identity is unknown until the call is redeemed. */
@Composable
private fun PendingInviteBanner(onOpen: () -> Unit, onDismiss: () -> Unit, busy: Boolean) {
    Column(Modifier.fillMaxWidth().clip(CardShape).background(CallPanel)
        .padding(horizontal = 22.dp, vertical = 26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(88.dp).clip(CircleShape).background(Elevated), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(34.dp)) { scale(size.width / 24f, size.width / 24f, Offset.Zero) { callIcon("camera", PipInk) } }
        }
        Spacer(Modifier.height(18.dp))
        Text("邀请你开始 Zisee 通话", fontSize = 17.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(20.dp))
        PrimaryButton("开始通话", "camera", height = 56.dp, enabled = !busy, onClick = onOpen)
        Spacer(Modifier.height(10.dp))
        Text("以后再说", Modifier.clickable(enabled = !busy, onClick = onDismiss).padding(10.dp), fontSize = 13.sp, color = CallFaint)
    }
}

/** Invite.dc.html: white QR card (needs light-on-dark contrast to scan), link row, share action. */
@Composable
private fun InvitePending(invite: String, onClose: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val link = InviteLink.of(invite)
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp).padding(top = 20.dp, bottom = 28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(24.dp).clickable(onClick = onClose)
                .semantics { role = Role.Button; contentDescription = "关闭" }) {
                Canvas(Modifier.fillMaxSize()) { callIcon("close", CallText) }
            }
            Text("邀请", fontSize = 22.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.2).sp)
        }
        Spacer(Modifier.height(28.dp))
        Text("让对方扫码或打开链接，就能看到你并直接开始通话。", fontSize = 15.sp, lineHeight = 24.sp, color = CallMuted)
        Spacer(Modifier.height(26.dp))
        Column(Modifier.fillMaxWidth().clip(CardShape).background(Color(0xFFF4F6F7))
            .padding(26.dp, 26.dp, 26.dp, 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            QrCode(link, Modifier.size(189.dp))
            Spacer(Modifier.height(18.dp))
            Text("用 Zisee 扫码", fontSize = 12.5.sp, color = Color(0xFF78868E))
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().height(58.dp).clip(PillShape).background(CallPanel)
            .padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(link, Modifier.weight(1f), fontSize = 14.5.sp, color = CallMuted, maxLines = 1)
            RoundIconButton("copy", "复制邀请链接", size = 44.dp) { clipboard.setText(AnnotatedString(link)) }
        }
        Spacer(Modifier.height(12.dp))
        PrimaryButton("分享邀请", "share", height = 62.dp, onClick = {
            val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, link) }
            context.startActivity(Intent.createChooser(send, null))
        })
        Spacer(Modifier.height(14.dp))
        Text("复制邀请码", Modifier.align(Alignment.CenterHorizontally)
            .clickable { clipboard.setText(AnnotatedString(invite)) }.padding(8.dp),
            fontSize = 13.sp, color = CallFaint)
        Spacer(Modifier.height(24.dp))
        Text("链接只包含一个邀请码，不包含你的手机号或邮箱。", fontSize = 12.sp, lineHeight = 20.sp,
            color = CallCaption, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
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
        Text("Zisee 视频来电", fontSize = 15.sp, color = CallMuted)
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
            CircularProgressIndicator(Modifier.size(13.dp), color = CallMuted, strokeWidth = 1.6.dp)
            Text(status, fontSize = 15.sp, color = CallMuted, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.weight(1f))
        ControlBall("挂断", "end", danger = true, onClick = onEnd)
        Spacer(Modifier.height(48.dp))
    }
}

@Composable
private fun PersonAvatar(name: String, size: Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(Elevated), contentAlignment = Alignment.Center) {
        Text(name.take(1), fontSize = (size.value * 0.35f).sp, fontWeight = FontWeight.Medium, color = PipInk)
    }
}

/** Main.dc.html contact row: avatar, name, one-tap call. Removal has no mock; kept small and muted. */
@Composable
private fun ContactRow(contact: Contact, enabled: Boolean, onCall: () -> Unit, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        PersonAvatar(contact.displayName, size = 48.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(contact.displayName, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text("轻点即可视频呼叫", fontSize = 13.sp, color = CallFaint)
        }
        RoundIconButton("camera", "视频呼叫${contact.displayName}", size = 44.dp,
            tint = CallAccent, background = CallAccentSoft, enabled = enabled, onClick = onCall)
        Box(Modifier.size(28.dp).clickable(enabled = enabled, onClick = onRemove)
            .semantics { role = Role.Button; contentDescription = "移除${contact.displayName}" },
            contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(13.dp)) { scale(size.width / 24f, size.width / 24f, Offset.Zero) { callIcon("close", CallCaption) } }
        }
    }
}

@Composable
private fun PrimaryButton(label: String, icon: String, height: Dp, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(height).clip(PillShape)
        .background(CallAccent.copy(alpha = if (enabled) 1f else 0.35f))
        .clickable(enabled = enabled, onClick = onClick)
        .semantics { role = Role.Button; contentDescription = label },
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(21.dp)) { scale(size.width / 24f, size.width / 24f, Offset.Zero) { callIcon(icon, CallAccentInk) } }
        Spacer(Modifier.width(10.dp))
        Text(label, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = CallAccentInk)
    }
}

@Composable
private fun SecondaryButton(label: String, icon: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(52.dp).clip(PillShape)
        .border(1.dp, CallText.copy(alpha = 0.13f), PillShape)
        .clickable(onClick = onClick).semantics { role = Role.Button; contentDescription = label },
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(18.dp)) { scale(size.width / 24f, size.width / 24f, Offset.Zero) { callIcon(icon, CallMuted) } }
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 15.sp, color = CallMuted)
    }
}

@Composable
private fun RoundIconButton(icon: String, description: String, size: Dp, tint: Color = CallMuted,
    background: Color = CallText.copy(alpha = 0.06f), enabled: Boolean = true, onClick: () -> Unit) {
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
        Text(label, fontSize = 11.5.sp, color = CallFaint)
    }
}

@Composable
private fun LinkField(value: String, onValueChange: (String) -> Unit, placeholder: String) {
    Box(Modifier.fillMaxWidth().height(58.dp).clip(PillShape).background(CallPanel)
        .padding(horizontal = 20.dp), contentAlignment = Alignment.CenterStart) {
        if (value.isEmpty()) Text(placeholder, fontSize = 14.5.sp, color = CallFaint)
        BasicTextField(value = value, onValueChange = { onValueChange(it.take(80)) }, singleLine = true,
            textStyle = TextStyle(color = CallText, fontSize = 14.5.sp), cursorBrush = SolidColor(CallAccent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth())
    }
}

@Composable
internal fun VideoRenderer(feed: VideoFeed, modifier: Modifier) {
    androidx.compose.runtime.key(feed) {
        val geometry by feed.geometry.collectAsState()
        // The call surface supplies the bars behind a tile smaller than its box.
        BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
            // Compose imposes EXACT dimensions, so size the surface to the upright frame itself
            // and leave bars to its parent, rather than distorting a stretched-to-fit surface.
            val aspect = geometry?.aspectRatio ?: (maxWidth.value / maxHeight.value.coerceAtLeast(1f))
            val width = minOf(maxWidth, maxHeight * aspect)
            val height = minOf(maxHeight, width / aspect.coerceAtLeast(0.001f))
            AndroidView(factory = { context -> TextureViewRenderer(context).also { feed.attach(it) } },
                modifier = Modifier.size(width, height), onRelease = { feed.detach(it) })
        }
    }
}
