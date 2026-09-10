package com.lazydoglab.zisee.screen

/** Chooses what the VirtualDisplay renders at, given the real display it mirrors.
 *
 * Capturing at the panel's own resolution wastes uplink on pixels no receiver can show, and many
 * encoders reject odd dimensions outright. Scale the long edge down to [MAX_LONG_EDGE], keep the
 * aspect ratio so text does not shear, and round both edges to a multiple of [ALIGNMENT].
 *
 * Screen content is read at rest far more often than it moves, so resolution is preferred over
 * frame rate here; the sender's congestion control still decides what actually goes out.
 */
object ScreenCaptureSize {
    const val MAX_LONG_EDGE = 1600
    const val ALIGNMENT = 16

    fun of(width: Int, height: Int): ScreenSize {
        require(width > 0 && height > 0)
        val longEdge = maxOf(width, height)
        val scale = if (longEdge > MAX_LONG_EDGE) MAX_LONG_EDGE.toDouble() / longEdge else 1.0
        return ScreenSize(align(width * scale), align(height * scale))
    }

    /** Never rounds an edge away to zero, and never rounds one back above the source dimension. */
    private fun align(value: Double): Int =
        (Math.round(value / ALIGNMENT).toInt() * ALIGNMENT).coerceAtLeast(ALIGNMENT)
}
