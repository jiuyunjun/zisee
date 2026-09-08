package com.zisee.app.ui

import android.Manifest
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
            when (permissionAction) { "accept" -> model.accept(); "join" -> model.join(invite.trim()) }
        } else model.permissionsDenied()
        permissionAction = null
    }
    fun request(action: String) {
        permissionAction = action
        permissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }
    BackHandler { model.close() }
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
            if (BuildConfig.DEBUG && state.local != null) {
                val stats = state.stats
                Text("ICE ${state.machine.phase} · ${stats.candidateType}→${stats.remoteCandidateType} · RTT ${stats.rttMs} ms\n" +
                    "收/发 ${stats.receiveKbps}/${stats.sendKbps} kbps · 抖动 ${stats.jitterMs} ms · 丢包 ${stats.packetsLost}\n" +
                    "远端视频 ${stats.videoWidth}x${stats.videoHeight}@${stats.videoFps} · ${stats.videoFrames} 帧\n" +
                    "音频收/发 ${stats.audioReceived}/${stats.audioSent} B",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun VideoRenderer(feed: VideoFeed, modifier: Modifier) {
    androidx.compose.runtime.key(feed) {
        AndroidView(factory = { context -> SurfaceViewRenderer(context).also {
            feed.attach(it); it.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        } }, modifier = modifier, onRelease = { feed.detach(it) })
    }
}
