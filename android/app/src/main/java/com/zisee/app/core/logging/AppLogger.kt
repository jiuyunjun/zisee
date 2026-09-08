package com.zisee.app.core.logging

import android.util.Log

/** Allowlisted events only: no free-form payloads, names, credentials, SDP or exceptions. */
enum class AppEvent { IDENTITY_READ_FAILED, IDENTITY_WRITE_FAILED, SESSION_LOGOUT_FAILED,
    RTC_NATIVE_ERROR, RTC_BIND_FAILED, RTC_SOCKET_FAILED, RTC_CODEC_FAILED,
    RTC_MEDIA_FAILED, RTC_RELEASE_FAILED, CALL_FAILED, CALL_CLEANUP_FAILED }

interface AppLogger {
    fun error(event: AppEvent)
}

object AndroidAppLogger : AppLogger {
    override fun error(event: AppEvent) {
        Log.e("Zisee", event.name)
    }
}
