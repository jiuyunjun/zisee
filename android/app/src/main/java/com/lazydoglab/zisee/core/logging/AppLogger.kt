package com.lazydoglab.zisee.core.logging

import android.util.Log

/** Allowlisted events only: no free-form payloads, names, credentials, SDP or exceptions. */
enum class AppEvent { RTC_NETWORK_CHANGED, RTC_NETWORK_MONITOR_FAILED, RTC_ICE_RESTART, IDENTITY_READ_FAILED, IDENTITY_WRITE_FAILED, SESSION_LOGOUT_FAILED,
    AR_CHANNEL_FAILED, AR_TAP, AR_CAMERA_CONFIG,
    RTC_COMPUTE_PLAN, RTC_COMPUTE_UNAVAILABLE, RTC_COMPUTE_BYPASS, RTC_COMPUTE_STATS, RTC_COMPUTE_TIER, RTC_COMPUTE_LATE, RTC_COMPUTE_RESIZE, RTC_COMPUTE_ENCODE_SLOW,
    RTC_COMPUTE_CAPABILITY_FAILED,
    RTC_VIDEO_BANDWIDTH_MODE, RTC_HANDOVER, RTC_HANDOVER_VIDEO, RTC_QUALITY_RESTORED, RTC_LOCAL_CANDIDATE, RTC_BITRATE_SEEDED, RTC_NETWORK_LOSING, RTC_BACKUP_PATH, RTC_RELAY_PINNED, RTC_TURN_PERMISSION_PRUNED, RTC_ROUTE_UNREACHABLE,
    RTC_CELLULAR_STANDBY, RTC_CELLULAR_STANDBY_FAILED, RTC_ROUTE_RECOVERED, RTC_CANDIDATE_OVERFLOW, RTC_ICE_PAIRS, RTC_SIGNALING_READY,
    RTC_NATIVE_ERROR, RTC_BIND_FAILED, RTC_SOCKET_FAILED, RTC_CODEC_FAILED, RTC_AUDIO_PROCESSING,
    RTC_CAPABILITY_UNAVAILABLE, RTC_STATS_UNAVAILABLE, RTC_QUALITY_REJECTED, RTC_QUALITY_CHANGED, RTC_ADAPTATION_PLAN, RTC_MEDIA_FAILED, RTC_RELEASE_FAILED, CALL_FAILED, CALL_CLEANUP_FAILED, CALL_SERVICE_FAILED,
    RTC_ICE_STATE, RTC_SDP_FAILED, RTC_SHOW_ME_FALLBACK, RTC_TRACK_ROUTED, RTC_SELECTED_CANDIDATE, RTC_SETUP_MS, RTC_FIRST_FRAME_MS, CALL_NOT_STARTED,
    SCREEN_SHARE_STATE, SCREEN_SHARE_FAILED, SCREEN_SHARE_SERVICE_FAILED,
    PUSH_RECEIVED, PUSH_EXPIRED, PUSH_IGNORED, PUSH_REGISTER_FAILED }

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
        "sdp_create_failed", "local_description_missing", "no_ice_candidates",
        "sdp_set_local_offer_failed", "sdp_set_local_answer_failed", "sdp_rollback_failed",
        "sdp_set_remote_offer_failed", "sdp_set_remote_answer_failed",
        "screen_not_available", "initial_negotiation_only",
        "call_service_failed", "screen_service_failed", "screen_consent_unavailable",
        "screen_projection_unavailable", "screen_capture_unavailable",
        // MediaSignaling
        "signaling_closed", "signaling_send", "signaling_timeout",
        // CallViewModel
        "invite_expired", "ice_disconnected", "media_failed", "ice_timeout",
    ) + AuthFailureReasons

    fun of(error: Throwable): String = error.message?.takeIf { it in codes } ?: error.javaClass.simpleName
}

/** `AuthFailure` already carries its enum name as the message, so those names are safe to keep. */
private val AuthFailureReasons =
    com.lazydoglab.zisee.auth.remote.AuthFailure.Reason.entries.map { it.name }.toSet()
