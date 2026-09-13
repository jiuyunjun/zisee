package com.lazydoglab.zisee.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.lazydoglab.zisee.ar.annotation.VideoPoint
import com.lazydoglab.zisee.rtc.VideoFeed
import com.lazydoglab.zisee.screen.*
import kotlinx.coroutines.delay

@Composable
internal fun ScreenGuidanceLayer(state: GuidanceState, feed: VideoFeed?, modifier: Modifier,
    put: (GuidanceInput) -> Unit, command: (GuidanceOp) -> Unit) {
    var tool by remember { mutableStateOf<GuidanceTool?>(null) }
    var clear by remember { mutableStateOf(false) }
    // Whole-display capture commonly includes the field overlay. Permanent local drawing is opt-in.
    var preview by remember { mutableStateOf(false) }
    val geometry = feed?.geometry?.collectAsState()?.value
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(state.session, state.geometry, geometry) { settled = false; delay(300); settled = true }
    val matches = geometry != null && state.width > 0 && state.height > 0 &&
        kotlin.math.abs(geometry.displayWidth.toFloat() / geometry.displayHeight - state.width.toFloat() / state.height) < .01f
    val ready = state.connected && !state.paused && matches && settled
    Box(modifier) {
        AndroidView(factory = { GuidanceCanvas(it) }, modifier = Modifier.fillMaxSize(), update = {
            val toolEnabled = ready && (state.overlay || (tool == GuidanceTool.POINTER && state.semanticAvailable))
            it.state = state; it.tool = tool.takeIf { toolEnabled }; it.renderConfirmed = preview; it.onPut = put
        })
        Column(Modifier.align(Alignment.TopCenter).padding(top = 90.dp).fillMaxWidth()) {
            if (state.paused) Text("对方已暂停屏幕共享", color = CallText)
            else if (state.overlay && !state.connected) Text("连接中断，标注已暂停", color = CallMuted)
            else if (!state.overlay) Text("对方未开启跨应用标注", color = CallMuted)
            else if (!state.semanticAvailable) Text("UI 元素吸附未开启，指针仍按坐标显示", color = CallMuted)
            if (state.notice.isNotEmpty()) Text(state.notice, color = CallDanger)
            if (state.overlay || state.semanticAvailable) Surface(color = CallPanel.copy(alpha = .9f)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { tool = null }) { Text("浏览") }
                    GuidanceTool.entries.forEach { item -> TextButton(enabled = ready && (state.overlay || item == GuidanceTool.POINTER && state.semanticAvailable),
                        onClick = { tool = item }) { Text((if (tool == item) "✓" else "") + when (item) {
                            GuidanceTool.POINTER -> "指针"; GuidanceTool.PEN -> "画笔"; GuidanceTool.CIRCLE -> "圈选"
                            GuidanceTool.ARROW -> "箭头"; GuidanceTool.NUMBER -> "编号"
                        }) } }
                    TextButton(enabled = state.connected && (state.overlay || state.semanticAvailable), onClick = { command(GuidanceOp.UNDO) }) { Text("撤销") }
                    TextButton(enabled = state.connected && (state.overlay || state.semanticAvailable), onClick = { clear = true }) { Text("清除") }
                    TextButton(onClick = { preview = !preview }) { Text(if (preview) "关闭本地叠加" else "本地叠加") }
                }
            }
        }
    }
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("清除标注") },
        text = { Text("清除全部会同时移除双方的标注。") },
        confirmButton = { TextButton(onClick = { command(GuidanceOp.CLEAR_ALL); clear = false }) { Text("清除全部") } },
        dismissButton = { Row {
            TextButton(onClick = { command(GuidanceOp.CLEAR_OWN); clear = false }) { Text("仅清除我的") }
            TextButton(onClick = { clear = false }) { Text("取消") }
        } })
}
