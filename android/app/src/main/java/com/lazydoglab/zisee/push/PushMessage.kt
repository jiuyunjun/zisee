package com.lazydoglab.zisee.push

import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * The wake-up payload carried by a `type=call_invite` FCM data message
 * (docs/architecture/CALL_DELIVERY.md §9). It holds only what is needed to ring:
 * the avatar and full profile load later.
 */
data class CallInvite(
    val callId: String,
    val callerId: String,
    val callerName: String,
    val mediaType: String,
    val expiresAt: Instant,
) {
    companion object {
        fun parse(data: Map<String, String>): CallInvite? {
            if (data["type"] != "call_invite") return null
            val callId = data["call_id"]?.takeIf { it.isNotBlank() } ?: return null
            val callerId = data["caller_id"]?.takeIf { it.isNotBlank() } ?: return null
            val expiresAt = data["expires_at"]?.let {
                try { Instant.parse(it) } catch (error: DateTimeParseException) { return null }
            } ?: return null
            return CallInvite(
                callId = callId,
                callerId = callerId,
                callerName = data["caller_name"].orEmpty(),
                mediaType = data["media_type"]?.takeIf { it.isNotBlank() } ?: "video",
                expiresAt = expiresAt,
            )
        }
    }
}
