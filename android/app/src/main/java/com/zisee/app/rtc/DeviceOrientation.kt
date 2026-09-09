package com.zisee.app.rtc

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.OrientationEventListener
import android.view.Surface

/**
 * Physical device orientation, quantised to the four surface rotations.
 *
 * Video has to arrive the right way up for the viewer however the sender is holding the phone, and
 * the display rotation cannot express that on its own: with auto-rotate off, or on a screen locked
 * to one orientation, it never leaves its starting value, so a sender who turns the phone sideways
 * keeps sending sideways frames. The sensor reports the real orientation either way.
 */
class DeviceOrientation(private val context: Context, private val onChanged: (Int) -> Unit = {}) {
    private val displays = context.getSystemService(DisplayManager::class.java)
    @Volatile var rotation = displayRotation(); private set
    private val quantizer = OrientationQuantizer(rotation)

    private val listener = object : OrientationEventListener(context) {
        override fun onOrientationChanged(degrees: Int) {
            val value = quantizer.update(degrees)
            if (value != rotation) { rotation = value; onChanged(value) }
        }
    }

    fun start() { if (listener.canDetectOrientation()) listener.enable() }
    fun close() = listener.disable()

    /** What the window system believes, which is what libwebrtc already folded into each frame. */
    private fun displayRotation(): Int =
        displays?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0

    /**
     * How much to add to a frame libwebrtc already rotated by the display orientation so that it
     * carries the physical orientation instead. Mirrors the capturer's own sign convention, where a
     * rear camera turns the opposite way from a front one.
     */
    fun correction(frontFacing: Boolean): Int =
        if (listener.canDetectOrientation())
            orientationCorrection(degrees(rotation), degrees(displayRotation()), frontFacing)
        else 0 // No sensor: keep the capturer's display-based rotation rather than a frozen guess.

    private fun degrees(surfaceRotation: Int) = when (surfaceRotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }
}

/**
 * Replaces the display orientation libwebrtc already applied with the physical one.
 *
 * The capturer computes `(cameraOrientation + display)` for a front camera and
 * `(cameraOrientation - display)` for a rear one, so undoing the display term and applying the
 * physical term collapses to a single signed difference.
 */
internal fun orientationCorrection(physicalDegrees: Int, displayDegrees: Int, frontFacing: Boolean): Int {
    val difference = (physicalDegrees - displayDegrees + 360) % 360
    return if (frontFacing) difference else (360 - difference) % 360
}
