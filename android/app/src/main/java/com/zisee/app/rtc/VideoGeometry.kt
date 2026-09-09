package com.zisee.app.rtc

/** Buffer coordinates and rotation stay separate; the renderer applies rotation exactly once. */
data class VideoGeometry(val width: Int, val height: Int, val rotation: Int) {
    init { require(width > 0 && height > 0 && rotation in setOf(0, 90, 180, 270)) }
    val displayWidth: Int get() = if (rotation % 180 == 0) width else height
    val displayHeight: Int get() = if (rotation % 180 == 0) height else width
    val aspectRatio: Float get() = displayWidth.toFloat() / displayHeight
}

/** Surface rotation is counterclockwise; OrientationEventListener angles are clockwise. */
internal class OrientationQuantizer(initialRotation: Int) {
    var rotation: Int = initialRotation; private set

    fun update(sensorDegrees: Int): Int {
        if (sensorDegrees !in 0..359) return rotation
        val currentDegrees = ((4 - rotation) % 4) * 90
        val distance = kotlin.math.abs(sensorDegrees - currentDegrees).let { minOf(it, 360 - it) }
        // Cross 45 degrees plus 15 degrees hysteresis before switching. Flat/unknown keeps history.
        if (distance >= 60) rotation = (4 - ((sensorDegrees + 45) / 90) % 4) % 4
        return rotation
    }
}
