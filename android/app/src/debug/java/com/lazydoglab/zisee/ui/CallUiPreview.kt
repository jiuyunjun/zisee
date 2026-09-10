package com.lazydoglab.zisee.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lazydoglab.zisee.ar.annotation.ArMessage
import com.lazydoglab.zisee.ar.collaboration.ArCollaborationState
import com.lazydoglab.zisee.ar.session.ArSessionState
import com.lazydoglab.zisee.call.CallUiState
import com.lazydoglab.zisee.call.state.CallPhase
import com.lazydoglab.zisee.call.state.CallSession
import com.lazydoglab.zisee.call.state.CallState
import com.lazydoglab.zisee.rtc.CameraMode
import com.lazydoglab.zisee.rtc.CameraPresentation
import com.lazydoglab.zisee.rtc.MediaStats
import com.lazydoglab.zisee.rtc.ShowMeState
import com.lazydoglab.zisee.rtc.VideoFeed
import com.lazydoglab.zisee.screen.ScreenSharePhase
import com.lazydoglab.zisee.screen.ScreenShareState
import com.lazydoglab.zisee.rtc.audio.AudioBandwidthMode
import com.lazydoglab.zisee.rtc.audio.AudioDeviceState
import com.lazydoglab.zisee.rtc.audio.AudioState
import java.util.UUID

/**
 * Every dimension of the call surface the on-device fixture can vary. One data class, so the
 * control panel and the rendered surface cannot describe two different scenarios.
 */
internal data class PreviewScenario(
    val localMode: CameraMode = CameraMode.FACE,
    val remoteMode: CameraMode = CameraMode.FACE,
    val remoteEnabled: Boolean = true,
    /** Whether any decoded frame has arrived; false shows the "waiting for the peer" placeholder. */
    val remoteFrames: Boolean = true,
    val cameraEnabled: Boolean = true,
    val muted: Boolean = false,
    val speakerOn: Boolean = true,
    val showMeHint: Boolean = false,
    val arState: ArSessionState = ArSessionState.IDLE,
    val arNotice: String = "",
    /** The peer joined the field this end owns. */
    val fieldPeerJoined: Boolean = false,
    /** This end joined the peer's field, which is what enables marking on the peer's scene. */
    val joinedRemoteField: Boolean = false,
    val ownMarkers: Int = 0,
    val audioInterrupted: Boolean = false,
    val audioOnly: Boolean = false,
    val selectedVideoSource: String? = null,
    val peerName: String = "林然",
    val sharePhase: ScreenSharePhase = ScreenSharePhase.IDLE,
    val openMore: Boolean = false,
) {
    val localScene: Boolean get() = localMode in setOf(CameraMode.DUAL, CameraMode.BACK_ONLY, CameraMode.AR)
}

// Fixed ids: the fixture is a layout surface, not a session, and re-rendering must not churn state.
private val PreviewFieldSession: UUID = UUID.fromString("00000000-0000-4000-8000-000000000001")
private val PreviewPeerSession: UUID = UUID.fromString("00000000-0000-4000-8000-000000000002")

/**
 * [feeds] are this end's face, the peer's face, this end's scene and the peer's scene, in that
 * order — the same four tiles a real call can carry.
 */
internal fun PreviewScenario.toUiState(feeds: List<VideoFeed>) = CallUiState(
    visible = true, busy = true, peerName = peerName, status = "通话中",
    // A non-IDLE phase requires a session, so the fixture supplies a complete one.
    machine = CallState(CallPhase.CONNECTED, CallSession("preview", "peer")),
    local = feeds[0], remote = feeds[1], localBack = feeds[2], remoteBack = feeds[3],
    cameraEnabled = cameraEnabled, muted = muted, speakerOn = speakerOn, showMeHint = showMeHint,
    showMe = ShowMeState(localMode), remotePresentation = CameraPresentation(remoteMode, remoteEnabled),
    stats = MediaStats(videoFrames = if (remoteFrames) 1L else 0L,
        audioDevice = AudioDeviceState(state = if (audioInterrupted) AudioState.INTERRUPTED else AudioState.ACTIVE),
        audioBandwidth = if (audioOnly) AudioBandwidthMode.AUDIO_ONLY else AudioBandwidthMode.ALL_VIDEO),
    arState = arState, arNotice = arNotice, arOwnMarkerCount = ownMarkers,
    selectedVideoSource = selectedVideoSource,
    screenShare = ScreenShareState(phase = sharePhase),
    arCollaboration = ArCollaborationState(connected = true,
        localSession = if (localMode == CameraMode.AR) PreviewFieldSession else null,
        fieldPeerJoined = localMode == CameraMode.AR && fieldPeerJoined,
        remote = if (remoteMode == CameraMode.AR) ArMessage.Ready(PreviewPeerSession, depthSupported = false) else null,
        joined = remoteMode == CameraMode.AR && joinedRemoteField),
)

/**
 * The real [ActiveCall] driven by a scenario instead of a call. Controls act on the scenario, so
 * mute, camera-off, view swaps, the marker toolbar and the tips all respond the way they do in a
 * call; nothing here touches a camera, a microphone, ARCore or the network.
 *
 * The scenario panel is mounted in the call surface's own 更多 → 通话选项 sheet rather than as extra
 * chrome, so the surface under test stays pixel-identical to the shipped one.
 */
@Composable
internal fun CallUiPreview(feeds: List<VideoFeed>, initial: PreviewScenario, interactive: Boolean, onExit: () -> Unit) {
    var scenario by remember { mutableStateOf(initial) }
    ActiveCall(scenario.toUiState(feeds),
        onMute = { scenario = scenario.copy(muted = !scenario.muted) },
        onCamera = { scenario = scenario.copy(cameraEnabled = !scenario.cameraEnabled) },
        onShowMe = { scenario = scenario.copy(localMode = if (scenario.localScene) CameraMode.FACE else CameraMode.DUAL) },
        onSwitch = {},
        onSpeaker = { scenario = scenario.copy(speakerOn = !scenario.speakerOn) },
        onEnd = onExit,
        onMinimize = onExit,
        onHintSeen = { scenario = scenario.copy(showMeHint = false) },
        onArMarker = { _, _, _ -> scenario = scenario.copy(ownMarkers = scenario.ownMarkers + 1) },
        onArUndo = { scenario = scenario.copy(ownMarkers = (scenario.ownMarkers - 1).coerceAtLeast(0)) },
        onArClearOwn = { scenario = scenario.copy(ownMarkers = 0) },
        onSelectVideo = { scenario = scenario.copy(selectedVideoSource = it) },
        onStartShare = { scenario = scenario.copy(sharePhase = ScreenSharePhase.STARTING) },
        onStopShare = { scenario = scenario.copy(sharePhase = ScreenSharePhase.STOPPED) },
        initialMore = initial.openMore,
        arControls = {
            Text("使用后置摄像头进行现场跟踪。", fontSize = 13.sp, color = CallMuted)
            TextButton(enabled = scenario.cameraEnabled && scenario.sharePhase !in setOf(
                ScreenSharePhase.STARTING, ScreenSharePhase.ACTIVE), onClick = {}) { Text("开启我的现场") }
        },
        debugControls = { if (interactive) PreviewControls(scenario, onExit) { scenario = it } })
}

/** Debug-only scenario switches. Deliberately plain: this panel is a fixture, not a design surface. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreviewControls(scenario: PreviewScenario, onExit: () -> Unit, onChange: (PreviewScenario) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("界面预览（调试构建）", fontWeight = FontWeight.Medium, color = CallAccent)
        Text("没有摄像头、麦克风、ARCore 或网络；画面是本机合成的测试图。AR 标记的落点与渲染需要真实通话验证。",
            fontSize = 12.sp, color = CallMuted)

        Label("我的摄像头")
        Choices(CameraMode.entries, scenario.localMode) { onChange(scenario.copy(localMode = it)) }
        Label("对方摄像头")
        Choices(CameraMode.entries.filter { it != CameraMode.STARTING }, scenario.remoteMode) {
            onChange(scenario.copy(remoteMode = it))
        }
        Label("AR 会话状态")
        Choices(ArSessionState.entries, scenario.arState) { onChange(scenario.copy(arState = it)) }

        Toggle("对方开启了画面", scenario.remoteEnabled) { onChange(scenario.copy(remoteEnabled = it)) }
        Toggle("已收到对方画面", scenario.remoteFrames) { onChange(scenario.copy(remoteFrames = it)) }
        Toggle("对方已加入我的现场", scenario.fieldPeerJoined) { onChange(scenario.copy(fieldPeerJoined = it)) }
        Toggle("我已加入对方现场", scenario.joinedRemoteField) { onChange(scenario.copy(joinedRemoteField = it)) }
        Toggle("显示小窗切换提示", scenario.showMeHint) { onChange(scenario.copy(showMeHint = it)) }
        Toggle("音频被其他应用中断", scenario.audioInterrupted) { onChange(scenario.copy(audioInterrupted = it)) }
        Toggle("弱网仅保语音", scenario.audioOnly) { onChange(scenario.copy(audioOnly = it)) }
        Toggle("AR 提示语", scenario.arNotice.isNotEmpty()) {
            onChange(scenario.copy(arNotice = if (it) "对方已加入，可以共同放置标记。" else ""))
        }

        Label("我的标记数：${scenario.ownMarkers}")
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onChange(scenario.copy(ownMarkers = scenario.ownMarkers + 1)) }) { Text("+1") }
            TextButton(enabled = scenario.ownMarkers > 0,
                onClick = { onChange(scenario.copy(ownMarkers = scenario.ownMarkers - 1)) }) { Text("-1") }
        }
        TextButton(onClick = onExit) { Text("退出预览", color = CallDanger) }
    }
}

@Composable
private fun Label(text: String) =
    Text(text, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.titleSmall)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Choices(options: List<T>, selected: T, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { option ->
            val active = option == selected
            Text(option.toString(), fontSize = 12.sp, color = if (active) CallAccentInk else CallText,
                modifier = Modifier.clip(RoundedCornerShape(14.dp))
                    .background(if (active) CallAccent else CallText.copy(alpha = 0.10f))
                    .clickable { onSelect(option) }.padding(horizontal = 12.dp, vertical = 7.dp))
        }
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 13.sp, color = CallText)
        Switch(checked = value, onCheckedChange = onChange)
    }
}
