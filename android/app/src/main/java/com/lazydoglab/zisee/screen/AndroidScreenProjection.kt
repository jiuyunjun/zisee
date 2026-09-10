package com.lazydoglab.zisee.screen

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.view.Surface

/** Owns the supplied fresh MediaProjection and its single VirtualDisplay, but borrows the output
 * Surface. The caller owns the screen SurfaceTextureHelper/VideoSource and must release them AFTER
 * this backend closes. No Bitmap, CPU readback, recording, or microphone is involved.
 *
 * Construct on the supplied non-main handler after current-call/consent/foreground-service checks.
 * resizeOutput updates the borrowed texture buffer dimensions before the display is resized.
 * Construction transfers projection ownership only after argument validation succeeds.
 */
class AndroidScreenProjection(
    private val projection: MediaProjection,
    private val output: Surface,
    private val densityDpi: Int,
    private val handler: Handler,
    private val resizeOutput: (ScreenSize) -> Unit,
) : ScreenProjection {
    private var display: VirtualDisplay? = null
    private var callback: MediaProjection.Callback? = null
    private var attempted = false
    private var closed = false

    init {
        require(densityDpi > 0)
        checkThread()
        require(output.isValid)
    }

    private fun checkThread() {
        check(Looper.myLooper() === handler.looper && handler.looper !== Looper.getMainLooper()) {
            "Projection requires its non-main owner handler"
        }
    }

    override fun start(size: ScreenSize, events: ScreenProjectionEvents) {
        checkThread()
        check(!attempted && !closed)
        attempted = true
        val listener = object : MediaProjection.Callback() {
            override fun onStop() { if (!closed) events.stopped() }
            override fun onCapturedContentResize(width: Int, height: Int) {
                if (closed) return
                if (width !in 1..ScreenSize.MAX_DIMENSION || height !in 1..ScreenSize.MAX_DIMENSION) {
                    events.failed()
                    return
                }
                events.resized(ScreenSize(width, height))
            }
            override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
                if (!closed) events.visibilityChanged(isVisible)
            }
        }
        // Register before createVirtualDisplay; required for modern target SDKs.
        callback = listener
        projection.registerCallback(listener, handler)
        resizeOutput(size)
        display = requireNotNull(projection.createVirtualDisplay(
            "ZiseeScreen", size.width, size.height, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, output, null, handler,
        ))
    }

    override fun resize(size: ScreenSize) {
        checkThread()
        check(!closed)
        val current = requireNotNull(display)
        resizeOutput(size)
        // Never create a second VirtualDisplay on a consumed consent token.
        current.resize(size.width, size.height, densityDpi)
    }

    override fun close() {
        checkThread()
        if (closed) return
        closed = true
        val oldDisplay = display
        val oldCallback = callback
        display = null
        callback = null
        // Always try all releases; propagate cleanup failure to the controller's enum diagnostics.
        var failure: Exception? = null
        fun release(action: () -> Unit) {
            try { action() } catch (error: Exception) {
                if (failure == null) failure = error else failure!!.addSuppressed(error)
            }
        }
        release { oldDisplay?.surface = null }
        release { oldDisplay?.release() }
        release { oldCallback?.let(projection::unregisterCallback) }
        release { projection.stop() }
        failure?.let { throw it }
    }
}
