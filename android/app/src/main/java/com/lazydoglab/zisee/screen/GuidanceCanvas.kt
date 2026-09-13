package com.lazydoglab.zisee.screen

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import com.lazydoglab.zisee.ar.annotation.AnnotationAuthor
import com.lazydoglab.zisee.ar.annotation.VideoPoint
import java.util.UUID
import kotlin.math.*

/** Shared renderer/input for the remote FIT_CENTER video and the local overlay. */
class GuidanceCanvas(context: Context) : View(context) {
    var state = GuidanceState()
        set(value) { if (field.geometry != value.geometry || field.session != value.session || value.paused) draft = null
            field = value; invalidate() }
    var tool: GuidanceTool? = null
    var renderConfirmed = true
    var onPut: (GuidanceInput) -> Unit = {}
    private var draft: GuidanceMark? = null
    private var lastSent = 0L
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private fun rect(): RectF {
        val scale = min(width.toFloat() / state.width.coerceAtLeast(1), height.toFloat() / state.height.coerceAtLeast(1))
        val w = state.width * scale; val h = state.height * scale
        return RectF((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rect = rect(); if (rect.isEmpty) return
        canvas.save(); canvas.clipRect(rect)
        if (renderConfirmed) state.marks.filter { it.id != draft?.id }.forEach { draw(canvas, rect, it) }
        state.semanticTarget?.let { drawSemantic(canvas, rect, it) }
        draft?.let { draw(canvas, rect, it) }
        canvas.restore()
    }
    private fun drawSemantic(canvas: Canvas, content: RectF, target: SemanticTarget) {
        val bounds = target.bounds
        paint.color = 0xff5fd4d6.toInt(); paint.strokeWidth = max(3f, min(content.width(), content.height()) * .008f)
        paint.style = Paint.Style.STROKE
        val rect = RectF(content.left + bounds.left * content.width(), content.top + bounds.top * content.height(),
            content.left + bounds.right * content.width(), content.top + bounds.bottom * content.height())
        canvas.drawRoundRect(rect, paint.strokeWidth * 2, paint.strokeWidth * 2, paint)
    }
    private fun draw(c: Canvas, r: RectF, mark: GuidanceMark) {
        fun x(p: VideoPoint) = r.left + p.x * r.width()
        fun y(p: VideoPoint) = r.top + p.y * r.height()
        val a = mark.points.first(); val b = mark.points.last()
        paint.color = if (mark.author == AnnotationAuthor.FIELD) 0xffffd54f.toInt() else 0xff5fd4d6.toInt()
        paint.strokeWidth = max(2f, min(r.width(), r.height()) * .006f); paint.style = Paint.Style.STROKE
        when (mark.tool) {
            GuidanceTool.PEN -> {
                val path = Path(); path.moveTo(x(a), y(a)); mark.points.drop(1).forEach { path.lineTo(x(it), y(it)) }
                if (mark.points.size == 1) c.drawPoint(x(a), y(a), paint) else c.drawPath(path, paint)
            }
            GuidanceTool.CIRCLE -> c.drawOval(min(x(a), x(b)), min(y(a), y(b)), max(x(a), x(b)), max(y(a), y(b)), paint)
            GuidanceTool.ARROW -> {
                c.drawLine(x(a), y(a), x(b), y(b), paint)
                val angle = atan2(y(b) - y(a), x(b) - x(a)); val length = paint.strokeWidth * 5
                for (turn in listOf(-.5f, .5f)) c.drawLine(x(b), y(b),
                    x(b) - length * cos(angle + turn), y(b) - length * sin(angle + turn), paint)
            }
            GuidanceTool.POINTER -> c.drawCircle(x(a), y(a), paint.strokeWidth * 4, paint)
            GuidanceTool.NUMBER -> {
                val radius = paint.strokeWidth * 5
                c.drawCircle(x(a), y(a), radius, paint)
                paint.style = Paint.Style.FILL; paint.textSize = radius * 1.4f; paint.textAlign = Paint.Align.CENTER
                c.drawText(mark.number.toString(), x(a), y(a) - (paint.ascent() + paint.descent()) / 2, paint)
            }
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val selected = tool ?: return false
        if (state.paused || !state.connected || (!state.overlay && !(selected == GuidanceTool.POINTER && state.semanticAvailable))) return false
        val r = rect(); if (r.isEmpty) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN && !r.contains(event.x, event.y)) return false
        val point = VideoPoint(((event.x - r.left) / r.width()).coerceIn(0f, 1f), ((event.y - r.top) / r.height()).coerceIn(0f, 1f))
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                draft = GuidanceMark(UUID.randomUUID().toString(), AnnotationAuthor.GUIDE, selected, listOf(point))
                lastSent = event.eventTime
            }
            MotionEvent.ACTION_MOVE -> {
                val old = draft ?: return true
                draft = old.copy(points = if (selected == GuidanceTool.PEN) {
                    val points = old.points + point
                    if (points.size > 128) points.filterIndexed { i, _ -> i % 2 == 0 || i == points.lastIndex } else points
                } else listOf(old.points.first(), point))
                if (event.eventTime - lastSent >= 40 && selected == GuidanceTool.PEN) { emit(); lastSent = event.eventTime }
            }
            MotionEvent.ACTION_UP -> {
                if (selected !in setOf(GuidanceTool.POINTER, GuidanceTool.NUMBER)) draft = draft?.let { it.copy(points = it.points + point) }
                emit(); draft = null; performClick()
            }
            MotionEvent.ACTION_CANCEL -> { draft = null }
        }
        invalidate(); return true
    }
    private fun emit() { draft?.let { onPut(GuidanceInput(state.session, state.geometry, it.id, it.tool, it.points.take(128))) } }
    override fun performClick(): Boolean { super.performClick(); return true }
}
