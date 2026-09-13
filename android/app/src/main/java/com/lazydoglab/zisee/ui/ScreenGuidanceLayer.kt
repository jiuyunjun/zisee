package com.lazydoglab.zisee.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
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
    put: (GuidanceInput) -> Unit, command: (GuidanceOp) -> Unit, bottomInset: Dp = 100.dp) {
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
        }
        if (state.overlay || state.semanticAvailable) Row(
            Modifier.align(Alignment.BottomCenter).safeDrawingPadding()
                .padding(horizontal = 16.dp).padding(bottom = bottomInset)
                .clip(RoundedCornerShape(22.dp)).background(DockInk.copy(alpha = .88f))
                .border(1.dp, CallAccent.copy(alpha = .25f), RoundedCornerShape(22.dp))
                .horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically) {
            CollaborationToolIcon("浏览", "guide-browse", active = tool == null) { tool = null }
            GuidanceTool.entries.forEach { item ->
                val (label, icon) = when (item) {
                    GuidanceTool.POINTER -> "指针" to "ar-pin"
                    GuidanceTool.PEN -> "画笔" to "ar-pen"
                    GuidanceTool.CIRCLE -> "圈选" to "ar-circle"
                    GuidanceTool.ARROW -> "箭头" to "ar-arrow"
                    GuidanceTool.NUMBER -> "编号" to "guide-number"
                }
                CollaborationToolIcon(label, icon, active = tool == item,
                    enabled = ready && (state.overlay || item == GuidanceTool.POINTER && state.semanticAvailable)) { tool = item }
            }
            CollaborationToolIcon("撤销", "ar-undo", enabled = state.connected) { command(GuidanceOp.UNDO) }
            CollaborationToolIcon("清除", "ar-trash", enabled = state.connected) { clear = true }
            CollaborationToolIcon(if (preview) "关闭本地叠加" else "本地叠加", "guide-eye", active = preview) { preview = !preview }
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
