package com.zisee.app.ui

import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.zisee.app.BuildConfig
import com.zisee.app.call.CallUiState
import com.zisee.app.call.CallViewModel
import com.zisee.app.invite.InviteLink
import com.zisee.app.call.state.CallPhase
import com.zisee.app.rtc.VideoFeed
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

@Composable
fun CallScreen(state: CallUiState, model: CallViewModel) {
    var invite by remember { mutableStateOf("") }
    // A code that arrived through a link replaces whatever was typed, so the field matches the
    // invitation the user just opened.
    LaunchedEffect(state.pendingInvite) {
        if (state.pendingInvite.isNotEmpty()) invite = state.pendingInvite
    }
    var permissionAction by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
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
        ActiveCall(state, model)
        return
    }
    Scaffold { insets ->
        Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = model::close) { Text("返回") }
            Text("视频通话", style = MaterialTheme.typography.headlineMedium)
            Text(state.status)
            state.remote?.let { VideoRenderer(it, Modifier.fillMaxWidth().height(280.dp)) }
            state.local?.let {
                Text("本地画面")
                VideoRenderer(it, Modifier.fillMaxWidth().height(150.dp))
            }
            if (state.invite.isNotEmpty()) {
                Text("邀请码（10 分钟有效）")
                QrCode(InviteLink.of(state.invite), Modifier.size(220.dp))
                Text("用对方手机的相机扫码，或复制链接发过去。", style = MaterialTheme.typography.bodySmall)
                Text(state.invite, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(state.invite)) }) { Text("复制邀请码") }
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(InviteLink.of(state.invite))) }) { Text("复制链接") }
                }
            }
            if (state.machine.phase == CallPhase.INCOMING && state.busy) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { request("accept") }, enabled = permissionAction == null) { Text("接听") }
                    OutlinedButton(onClick = model::reject) { Text("拒绝") }
                }
            }
            if (!state.busy) {
                if (state.contacts.isNotEmpty()) Text("最近联系人", style = MaterialTheme.typography.titleMedium)
                state.contacts.forEach { contact ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(contact.displayName, Modifier.weight(1f))
                        TextButton(onClick = { request("contact:${contact.identityId}") }, enabled = permissionAction == null) { Text("视频呼叫") }
                        TextButton(onClick = { model.removeContact(contact.identityId) }) { Text("移除") }
                    }
                }
                if (state.contactsStatus.isNotEmpty()) Text(state.contactsStatus, style = MaterialTheme.typography.bodySmall)
                Text("接听后会记住对方，下次可以直接呼叫。移除后双方需重新邀请。", style = MaterialTheme.typography.bodySmall)
                Button(onClick = model::createInvite, enabled = BuildConfig.BACKEND_URL.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("创建邀请") }
                OutlinedTextField(value = invite, onValueChange = { invite = it.take(80) }, label = { Text("对方的邀请码或链接") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = { request("join") }, enabled = permissionAction == null && invite.isNotBlank() && BuildConfig.BACKEND_URL.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()) { Text("呼叫对方") }
                Text("接听或呼叫时需要摄像头和麦克风权限。当前测试版离开前台会结束通话。", style = MaterialTheme.typography.bodySmall)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.local != null) OutlinedButton(onClick = model::toggleMute) { Text(if (state.muted) "打开麦克风" else "静音") }
                    Button(onClick = model::stop) { Text("结束") }
                }
            }

        }
    }
}

@Composable
private fun VideoRenderer(feed: VideoFeed, modifier: Modifier, overlay: Boolean = false) {
    androidx.compose.runtime.key(feed) {
        AndroidView(factory = { context -> SurfaceViewRenderer(context).also {
            it.setZOrderMediaOverlay(overlay)
            feed.attach(it); it.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        } }, modifier = modifier, onRelease = { feed.detach(it) })
    }
}


@Composable
private fun ActiveCall(state: CallUiState, model: CallViewModel) {
    var diagnostics by remember { mutableStateOf(false) }
    val background = Color(0xFF101718)
    Surface(color = background, contentColor = Color.White, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("咫尺 · 视频通话", style = MaterialTheme.typography.titleMedium)
                    Text(if (state.machine.phase == CallPhase.CONNECTED && state.stats.videoFrames > 0) "通话中" else state.status,
                        style = MaterialTheme.typography.bodySmall, color = Color(0xFFC7D5D1))
                }
                if (BuildConfig.DEBUG) TextButton(onClick = { diagnostics = !diagnostics }) {
                    Text(if (diagnostics) "收起" else "连接详情", color = Color(0xFFB6E9D5))
                }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
                state.remote?.let { VideoRenderer(it, Modifier.fillMaxSize()) }
                if (state.stats.videoFrames == 0L) Column(Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator(color = Color(0xFFB6E9D5), modifier = Modifier.size(28.dp))
                    Text("正在等待对方画面…", textAlign = TextAlign.Center)
                }
                val previewHeight = minOf(160.dp, maxHeight * 0.45f)
                Column(Modifier.align(Alignment.TopEnd).padding(12.dp).width(100.dp)) {
                    Box(Modifier.fillMaxWidth().height(previewHeight).background(background)) {
                        if (state.cameraEnabled) state.local?.let { VideoRenderer(it, Modifier.fillMaxSize(), overlay = true) }
                        else Text("画面已关闭", Modifier.align(Alignment.Center), style = MaterialTheme.typography.labelSmall)
                    }
                    Text(if (state.muted) "我 · 已静音" else "我", Modifier.fillMaxWidth().background(background).padding(6.dp),
                        style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                }
            }
            if (diagnostics) {
                val stats = state.stats
                fun metric(value: Double?) = value?.let { "%.1f".format(java.util.Locale.ROOT, it) } ?: "—"
                Text((if (stats.sampleAvailable) "" else "统计暂不可用，以下为上次采样\n") +
                    "${stats.candidateType} → ${stats.remoteCandidateType} · RTT ${stats.measuredRttMs ?: "—"} ms\n" +
                    "收/发 ${stats.receiveKbps}/${stats.sendKbps} kbps · 丢包 ${stats.packetsLost}\n" +
                    "接收 ${stats.videoWidth} × ${stats.videoHeight} · ${stats.videoFps} fps\n" +
                    "发送 ${stats.sentWidth} × ${stats.sentHeight} · ${stats.sentFps} fps · 上限 ${stats.quality.name}\n" +
                    "上行估计 ${stats.availableOutgoingKbps ?: "—"} kbps · 上行丢包 ${metric(stats.outboundLoss?.times(100))}%\n" +
                    "编码 ${metric(stats.encodeMs)} ms/帧 · 发送等待 ${metric(stats.sendDelayMs)} ms/包\n" +
                    "接收缓冲 ${metric(stats.jitterBufferMs)} ms/帧 · 卡顿 ${stats.freezes ?: "—"} 次\n" +
                    "${stats.codec} · ${stats.encoder} · 节能编码 ${stats.powerEfficientEncoder ?: "—"}\n" +
                    "限制 ${stats.qualityLimitation} · 热状态 ${stats.thermalStatus ?: "—"}",
                    Modifier.fillMaxWidth().heightIn(max = 100.dp).verticalScroll(rememberScrollState()).padding(12.dp),
                    style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = model::toggleMute, modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (state.muted) Color(0xFFB6E9D5) else Color(0xFF33413E),
                        contentColor = if (state.muted) background else Color.White)) {
                    Text(if (state.muted) "取消静音" else "静音", textAlign = TextAlign.Center)
                }
                Button(onClick = model::toggleCamera, modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF33413E), contentColor = Color.White)) {
                    Text(if (state.cameraEnabled) "关闭画面" else "开启画面", textAlign = TextAlign.Center)
                }
                Button(onClick = model::stop, modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFCA414D), contentColor = Color.White)) { Text("挂断") }
            }
        }
    }
}
