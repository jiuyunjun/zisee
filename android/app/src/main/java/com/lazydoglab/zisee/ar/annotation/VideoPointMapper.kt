package com.lazydoglab.zisee.ar.annotation

import com.lazydoglab.zisee.rtc.VideoGeometry
import kotlin.math.max
import kotlin.math.min

/** Inverts the actual viewport transform before producing raw-image coordinates. */
object VideoPointMapper {
    enum class Scale { FIT, FILL }
    fun fromViewport(
        x: Float, y: Float, viewportWidth: Float, viewportHeight: Float,
        geometry: VideoGeometry, mirrored: Boolean = false, scale: Scale = Scale.FIT,
    ): VideoPoint? {
        if (!listOf(x, y, viewportWidth, viewportHeight).all { it.isFinite() } ||
            viewportWidth <= 0f || viewportHeight <= 0f || x !in 0f..viewportWidth || y !in 0f..viewportHeight) return null
        val sx = viewportWidth / geometry.displayWidth
        val sy = viewportHeight / geometry.displayHeight
        val factor = if (scale == Scale.FIT) min(sx, sy) else max(sx, sy)
        val width = geometry.displayWidth * factor
        val height = geometry.displayHeight * factor
        var u = (x - (viewportWidth - width) / 2f) / width
        val v = (y - (viewportHeight - height) / 2f) / height
        if (u !in 0f..1f || v !in 0f..1f) return null
        if (mirrored) u = 1f - u
        return when (geometry.rotation) {
            90 -> VideoPoint(v, 1f - u)
            180 -> VideoPoint(1f - u, 1f - v)
            270 -> VideoPoint(1f - v, u)
            else -> VideoPoint(u, v)
        }
    }
}
