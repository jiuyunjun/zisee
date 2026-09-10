package com.lazydoglab.zisee.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lazydoglab.zisee.call.CallUiState
import com.lazydoglab.zisee.rtc.CameraMode
import com.lazydoglab.zisee.rtc.VideoFeed
import kotlin.math.roundToInt

private data class CompactRemote(val feed: VideoFeed?, val live: Boolean, val waiting: Boolean)

private fun compactRemote(state: CallUiState): CompactRemote {
    val source = CallVideoLayout.compactRemote(
        state.remotePresentation.mode, state.selectedVideoSource, state.remoteShare.sharing)
    val backTrack = source == CallVideoLayout.PeerScene &&
        state.remotePresentation.mode in setOf(CameraMode.DUAL, CameraMode.AR)
    // §4.1: watching the peer's share from a compact window keeps showing the share. Its liveness
    // and first frame are its own, not the camera statistics.
    if (source == CallVideoLayout.PeerScreen) return CompactRemote(
        feed = state.remoteScreen, live = state.remoteShare.sharing,
        waiting = state.remoteScreen?.geometry?.value == null,
    )
    return CompactRemote(
        feed = if (backTrack) state.remoteBack else state.remote,
        live = state.remotePresentation.enabled,
        waiting = state.stats.videoFrames == 0L,
    )
}

/** PiP content contains one remote source and no Compose controls; Android supplies RemoteActions. */
@Composable
internal fun SystemPipCall(state: CallUiState) {
    Surface(color = CallInk, contentColor = CallText, modifier = Modifier.fillMaxSize()) {
        CompactRemoteContent(state, Modifier.fillMaxSize(), showName = false)
    }
}

/** Draggable mini-call shown above ordinary Zisee pages. It never changes media ownership. */
@Composable
internal fun InAppMiniCall(
    state: CallUiState,
    onRestore: () -> Unit,
    onMute: () -> Unit,
    onEnd: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val cardWidth = 176.dp
        val cardHeight = 124.dp
        val maxLeft = with(density) { (maxWidth - cardWidth - 32.dp).coerceAtLeast(0.dp).toPx() }
        val maxUp = with(density) { (maxHeight - cardHeight - 32.dp).coerceAtLeast(0.dp).toPx() }
        var drag by remember(maxWidth, maxHeight) { mutableStateOf(Offset.Zero) }
        Box(Modifier.align(Alignment.BottomEnd).padding(16.dp)
            .offset { IntOffset(drag.x.roundToInt(), drag.y.roundToInt()) }
            .size(cardWidth, cardHeight)
            .clip(RoundedCornerShape(18.dp))
            .background(CallInk)
            .border(1.dp, CallText.copy(alpha = 0.20f), RoundedCornerShape(18.dp))
            .pointerInput(maxLeft, maxUp) {
                detectDragGestures { change, amount ->
                    change.consume()
                    drag = Offset(
                        (drag.x + amount.x).coerceIn(-maxLeft, 0f),
                        (drag.y + amount.y).coerceIn(-maxUp, 0f),
                    )
                }
            }
            .clickable(onClick = onRestore)
            .semantics { role = Role.Button; contentDescription = "返回通话" }) {
            CompactRemoteContent(state, Modifier.fillMaxSize(), showName = true)
            Row(Modifier.align(Alignment.BottomEnd).padding(7.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CompactButton(if (state.muted) "取消静音" else "静音", "mic",
                    off = state.muted, onClick = onMute)
                CompactButton("挂断", "end", danger = true, onClick = onEnd)
            }
        }
    }
}

@Composable
private fun CompactRemoteContent(state: CallUiState, modifier: Modifier, showName: Boolean) {
    val remote = compactRemote(state)
    Box(modifier.background(CallInk), contentAlignment = Alignment.Center) {
        if (remote.live && remote.feed != null) VideoRenderer(remote.feed, Modifier.fillMaxSize())
        else Text(if (!remote.live) "对方已关闭画面" else if (remote.waiting) "正在等待画面…" else "仅语音",
            color = CallMuted, fontSize = 12.sp)
        if (showName) Text(state.peerName, Modifier.align(Alignment.TopStart).padding(9.dp)
            .clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 7.dp, vertical = 3.dp), color = CallText, fontSize = 11.sp)
    }
}

@Composable
private fun CompactButton(label: String, icon: String, off: Boolean = false,
    danger: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.size(34.dp).clip(CircleShape)
        .background(if (danger) CallDanger else CallPanel.copy(alpha = 0.90f))
        .clickable(onClick = onClick)
        .semantics { role = Role.Button; contentDescription = label }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(18.dp)) {
            scale(size.width / 24f, size.width / 24f, Offset.Zero) {
                callIcon(icon, Color.White, CallPanel, off)
            }
        }
    }
}
