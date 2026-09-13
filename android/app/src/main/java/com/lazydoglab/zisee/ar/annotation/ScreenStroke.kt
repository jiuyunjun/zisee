package com.lazydoglab.zisee.ar.annotation

import java.util.UUID
import kotlin.math.hypot

/** Temporary image-coordinate feedback, never a world anchor or input for spatial refinement. */
data class ScreenStroke(val id: UUID, val points: List<VideoPoint>)

/** Retain actual input frames so a spatial stroke can explicitly fall back as a whole. */
internal class StrokeTrace(first: SpatialMarkerRequest, var screenLocked: Boolean) {
    private val inputs = mutableListOf(first)
    val samples: List<SpatialMarkerRequest> get() = inputs
    var finished = false
        private set
    fun append(request: SpatialMarkerRequest): Boolean {
        if (finished || inputs.size >= AnnotationBudget.MAX_STROKE_POINTS ||
            request.frame.track != inputs.last().frame.track || request.frame.timestampNs < inputs.last().frame.timestampNs) return false
        inputs.add(request)
        return true
    }
    fun finish() { finished = true }
}

/** Pixel-spaced dashes retain phase across input samples, with a bounded rendering budget. */
internal fun screenStrokeDashes(points: List<VideoPoint>, width: Int, height: Int,
    dashPixels: Float = 12f, maxDashes: Int = 1024): Sequence<Pair<VideoPoint, VideoPoint>> = sequence {
    require(width > 0 && height > 0 && dashPixels.isFinite() && dashPixels > 0 && maxDashes > 0)
    var phase = 0f
    var emitted = 0
    for (index in 1 until points.size) {
        val a = points[index - 1]; val b = points[index]
        val dx = b.x - a.x; val dy = b.y - a.y
        val length = hypot(dx * width, dy * height)
        if (length < .001f) continue
        var offset = 0f
        while (offset < length) {
            val drawing = phase < dashPixels
            val boundary = if (drawing) dashPixels else dashPixels * 2f
            val step = minOf(length - offset, boundary - phase)
            if (step <= .0001f) { phase = if (drawing) dashPixels else 0f; continue }
            if (drawing) {
                if (emitted++ >= maxDashes) return@sequence
                yield(VideoPoint(a.x + dx * (offset / length), a.y + dy * (offset / length)) to
                    VideoPoint((a.x + dx * ((offset + step) / length)).coerceIn(0f, 1f),
                        (a.y + dy * ((offset + step) / length)).coerceIn(0f, 1f)))
            }
            offset += step
            phase += step
            if (phase >= dashPixels * 2f - .0001f) phase = 0f
        }
    }
}
