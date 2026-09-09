package com.lazydoglab.zisee.push

import android.util.Log
import com.lazydoglab.zisee.BuildConfig

/**
 * Debug-build push diagnostics, deliberately outside [com.lazydoglab.zisee.core.logging.AppLogger].
 *
 * AppLogger takes allowlisted event names and no free-form payloads, so that a release build cannot
 * leak anything through logcat. A registration token is exactly the kind of thing that rule exists
 * to keep out: whoever holds it, together with the server's credentials, can ring that specific
 * device. It is printed here anyway, and only when [BuildConfig.DEBUG] is set, because the alternative
 * for "does push actually reach this phone" is to place a real call and guess which of eight links
 * in the chain failed. Every call below compiles to a no-op check in release.
 *
 * Read it with:
 *
 *     adb logcat -s ZiseePush
 */
internal object PushDebug {
    private const val TAG = "ZiseePush"

    fun token(provider: String, token: String, alreadyRegistered: Boolean) {
        if (!BuildConfig.DEBUG) return
        Log.i(TAG, "provider=$provider registered=$alreadyRegistered token=$token")
    }

    fun registered(deviceId: String) {
        if (!BuildConfig.DEBUG) return
        Log.i(TAG, "push token accepted by backend for device=$deviceId")
    }

    fun registrationFailed(error: Throwable) {
        if (!BuildConfig.DEBUG) return
        Log.w(TAG, "backend rejected the push token: ${error.javaClass.simpleName}")
    }

    fun tokenUnavailable(error: Throwable) {
        if (!BuildConfig.DEBUG) return
        Log.w(TAG, "no FCM token: ${error.javaClass.simpleName}: ${error.message}. " +
            "On a device without usable Google Play Services this is expected.")
    }

    fun received(callId: String, outcome: PushOutcome) {
        if (!BuildConfig.DEBUG) return
        Log.i(TAG, "call_invite call=$callId -> $outcome")
    }

    fun ignored() {
        if (!BuildConfig.DEBUG) return
        Log.i(TAG, "push received but it was not a call_invite")
    }
}
