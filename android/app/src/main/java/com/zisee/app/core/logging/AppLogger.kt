package com.zisee.app.core.logging

import android.util.Log

/** Allowlisted events only: no free-form payloads, names, credentials, SDP or exceptions. */
enum class AppEvent { IDENTITY_READ_FAILED, IDENTITY_WRITE_FAILED, SESSION_LOGOUT_FAILED,
    RTC_NATIVE_ERROR, RTC_BIND_FAILED, RTC_SOCKET_FAILED, RTC_CODEC_FAILED,
    RTC_MEDIA_FAILED, RTC_RELEASE_FAILED, CALL_FAILED, CALL_CLEANUP_FAILED,
    RTC_ICE_STATE, RTC_SELECTED_CANDIDATE }

interface AppLogger {
    /** [reason] must come from [FailureReason.of] so only code-authored identifiers are recorded. */
    fun error(event: AppEvent, reason: String? = null)

    /** [detail] must be a bounded identifier such as an enum name or an ICE candidate type. */
    fun info(event: AppEvent, detail: String? = null) = Unit
}

object AndroidAppLogger : AppLogger {
    override fun error(event: AppEvent, reason: String?) {
        Log.e("Zisee", if (reason == null) event.name else "${event.name} reason=$reason")
    }

    override fun info(event: AppEvent, detail: String?) {
        Log.i("Zisee", if (detail == null) event.name else "${event.name} $detail")
    }
}

/**
 * Reduces a failure to a bounded identifier so a swallowed exception still says why it happened.
 *
 * Only the codes this code itself throws survive; any other message is replaced by the exception
 * class name. That keeps server text, SDP, credentials and user input out of the log even when the
 * exception travelled through a third-party library.
 */
object FailureReason {
    private val codes = setOf(
        // NativeRtcSession
        "peer_creation_failed", "camera_unavailable", "audio_focus_denied", "audio_route_failed",
        "sdp_create_failed", "sdp_set_failed", "local_description_missing", "no_ice_candidates",
        "screen_not_available", "initial_negotiation_only",
        // MediaSignaling
        "signaling_closed", "signaling_send",
        // CallViewModel
        "invite_expired", "ice_disconnected", "media_failed", "ice_timeout",
    ) + AuthFailureReasons

    fun of(error: Throwable): String = error.message?.takeIf { it in codes } ?: error.javaClass.simpleName
}

/** `AuthFailure` already carries its enum name as the message, so those names are safe to keep. */
private val AuthFailureReasons =
    com.zisee.app.auth.remote.AuthFailure.Reason.entries.map { it.name }.toSet()
