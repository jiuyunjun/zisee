package com.lazydoglab.zisee.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

/**
 * Ends screen sharing when the display goes off, as §4.1 requires.
 *
 * The camera and a local AR field already follow the Activity lifecycle, but a projection does not:
 * it is owned by the process and keeps capturing a locked device, where the user can neither see
 * what is being sent nor reach the stop control. Some systems do send a projection stop on lock and
 * some do not, so this does the product's own cleanup rather than trusting every OEM to.
 *
 * Audio and the call itself continue; only the share ends. ACTION_SCREEN_OFF cannot be declared in
 * the manifest, so the owner registers this for the lifetime of a call and closes it afterwards.
 */
class ScreenLockWatcher(private val context: Context, private val onScreenOff: () -> Unit) : AutoCloseable {
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) onScreenOff()
        }
    }
    private var registered = false

    init {
        // A device that refuses the registration must not take the call down with it.
        registered = runCatching {
            context.registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        }.isSuccess
    }

    override fun close() {
        if (!registered) return
        registered = false
        runCatching { context.unregisterReceiver(receiver) }
    }
}
