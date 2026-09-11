package com.lazydoglab.zisee.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.lazydoglab.zisee.ar.annotation.*
import com.lazydoglab.zisee.rtc.TextureViewRenderer
import kotlinx.coroutines.delay
import java.util.UUID

/** Touch preview is deliberately temporary. Every sample resolves the frame currently displayed,
 * even during one gesture; the first pose/frame is never reused for later touch positions.
 */
@Composable
internal fun ArAnnotationInput(modifier: Modifier, width: Float, height: Float,
    displayed: TextureViewRenderer.DisplayedArFrame?,
    onTap: ((TextureViewRenderer.DisplayedArFrame, VideoPoint) -> Unit)?,
    onStroke: ((ArStrokeInput) -> Unit)?) {
    val frameState = rememberUpdatedState(displayed)
    val tapState = rememberUpdatedState(onTap)
    val strokeState = rememberUpdatedState(onStroke)
    var preview by remember { mutableStateOf(emptyList<Offset>()) }
    var drawing by remember { mutableStateOf(false) }
    var previewVersion by remember { mutableIntStateOf(0) }
    LaunchedEffect(width, height) {
        com.lazydoglab.zisee.core.logging.AndroidAppLogger.info(
            com.lazydoglab.zisee.core.logging.AppEvent.AR_TAP, "shown layer=${width.toInt()}x${height.toInt()}")
    }
    LaunchedEffect(previewVersion, drawing) { if (!drawing) { delay(700); preview = emptyList() } }
    fun point(offset: Offset, frame: TextureViewRenderer.DisplayedArFrame) = VideoPointMapper.fromViewport(
        offset.x, offset.y, width, height, frame.geometry, frame.mirrored, VideoPointMapper.Scale.FIT)
    val gestures = if (onStroke == null) Modifier.pointerInput(width, height) {
        detectTapGestures { offset ->
            preview = listOf(offset); previewVersion++
            val frame = frameState.value
            val mapped = frame?.let { point(offset, it) }
            com.lazydoglab.zisee.core.logging.AndroidAppLogger.info(
                com.lazydoglab.zisee.core.logging.AppEvent.AR_TAP,
                if (frame == null) "frame=none" else if (mapped == null) "outside" else "placed")
            if (frame != null && mapped != null) tapState.value?.invoke(frame, mapped)
        }
    } else Modifier.pointerInput(width, height) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val first = frameState.value
            val mapped = first?.let { point(down.position, it) }
            if (first == null || mapped == null) return@awaitEachGesture
            val id = UUID.randomUUID()
            val session = first.identity.sessionId
            val samples = ArrayList<SpatialMarkerRequest>(AnnotationBudget.MAX_BATCH_POINTS)
            var lastFlush = down.uptimeMillis
            var ended = false
            fun emit(phase: ArStrokePhase, points: List<SpatialMarkerRequest> = emptyList()) {
                strokeState.value?.invoke(ArStrokeInput(session, id, phase, points))
            }
            fun flush() { if (samples.isNotEmpty()) { emit(ArStrokePhase.APPEND, samples.toList()); samples.clear() } }
            emit(ArStrokePhase.BEGIN, listOf(SpatialMarkerRequest(first.identity.reference, mapped)))
            drawing = true; preview = listOf(down.position); down.consume()
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (event.changes.any { it.id != down.id && it.pressed }) break
                    val frame = frameState.value ?: break
                    if (frame.identity.sessionId != session) break
                    val position = point(change.position, frame) ?: break
                    val moved = (change.position - preview.last()).getDistance() >= 2f
                    if (moved) {
                        if (preview.size >= AnnotationBudget.MAX_STROKE_POINTS) break
                        samples.add(SpatialMarkerRequest(frame.identity.reference, position))
                        preview = preview + change.position
                        if (samples.size >= AnnotationBudget.MAX_BATCH_POINTS || change.uptimeMillis - lastFlush >= 50) {
                            flush(); lastFlush = change.uptimeMillis
                        }
                    }
                    change.consume()
                    if (!change.pressed) { flush(); emit(ArStrokePhase.END); ended = true; break }
                }
            } finally {
                if (!ended) emit(ArStrokePhase.CANCEL)
                drawing = false; previewVersion++
            }
        }
    }
    Canvas(modifier.then(gestures).semantics { contentDescription = if (onStroke == null)
        "AR 现场，轻点放置标记" else "AR 现场，拖动绘制；离开画面取消整笔" }) {
        if (preview.size == 1) {
            drawCircle(Color(0xffaaf5f2), 18f, preview.first(), style = Stroke(2.5f))
            drawCircle(Color.White, 3f, preview.first())
        } else if (preview.size > 1) {
            val path = Path().apply { moveTo(preview[0].x, preview[0].y); preview.drop(1).forEach { lineTo(it.x, it.y) } }
            drawPath(path, Color(0xffaaf5f2).copy(alpha = if (drawing) 0.8f else 0.35f), style = Stroke(4f))
        }
    }
}
