package com.lazydoglab.zisee.rtc

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Surface

/** Follows the effective display rotation, including the user's rotation lock. No gravity override. */
class DeviceOrientation(context: Context, private val onChanged: (Int) -> Unit = {}) {
    private val displays = context.getSystemService(DisplayManager::class.java)
    private var registered = false
    private var closed = false
    private val state = DisplayRotationState(displayRotation())
    val rotation: Int get() = displayRotation()
    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            synchronized(this@DeviceOrientation) {
                if (!closed && displayId == Display.DEFAULT_DISPLAY) {
                    state.update(displayRotation())?.let(onChanged)
                }
            }
        }
    }

    @Synchronized fun start() {
        if (registered || closed) return
        displays?.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        registered = displays != null
        state.update(displayRotation())?.let(onChanged)
    }

    @Synchronized fun close() {
        closed = true
        if (registered) displays?.unregisterDisplayListener(listener)
        registered = false
    }

    private fun displayRotation(): Int =
        displays?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
}

/** Repeated display notifications while side-lying with rotation locked do not change capture. */
internal class DisplayRotationState(initial: Int) {
    private var current = initial
    fun update(rotation: Int): Int? {
        if (rotation !in 0..3 || rotation == current) return null
        current = rotation
        return rotation
    }
}
