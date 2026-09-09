package com.zisee.app.rtc

/** Buffer coordinates and rotation stay separate; the renderer applies rotation exactly once. */
data class VideoGeometry(val width: Int, val height: Int, val rotation: Int) {
    init { require(width > 0 && height > 0 && rotation in setOf(0, 90, 180, 270)) }
    val displayWidth: Int get() = if (rotation % 180 == 0) width else height
    val displayHeight: Int get() = if (rotation % 180 == 0) height else width
    val aspectRatio: Float get() = displayWidth.toFloat() / displayHeight
}
