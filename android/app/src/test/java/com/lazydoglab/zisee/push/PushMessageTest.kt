package com.lazydoglab.zisee.push

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PushMessageTest {
    private val full = mapOf(
        "type" to "call_invite",
        "call_id" to "call-1",
        "caller_id" to "zid_caller",
        "caller_name" to "九云",
        "media_type" to "video",
        "expires_at" to "2026-09-09T15:30:30Z",
        "call_version" to "1",
    )

    @Test fun parsesAFullInvite() {
        val invite = CallInvite.parse(full)!!
        assertEquals("call-1", invite.callId)
        assertEquals("zid_caller", invite.callerId)
        assertEquals("九云", invite.callerName)
        assertEquals("video", invite.mediaType)
        assertEquals(Instant.parse("2026-09-09T15:30:30Z"), invite.expiresAt)
    }

    @Test fun rejectsWrongType() = assertNull(CallInvite.parse(full + ("type" to "call_cancel")))

    @Test fun rejectsMissingCallId() = assertNull(CallInvite.parse(full - "call_id"))

    @Test fun rejectsMissingCallerId() = assertNull(CallInvite.parse(full - "caller_id"))

    @Test fun rejectsMissingOrBadExpiry() {
        assertNull(CallInvite.parse(full - "expires_at"))
        assertNull(CallInvite.parse(full + ("expires_at" to "not-a-time")))
    }

    @Test fun toleratesMissingOptionalFields() {
        val invite = CallInvite.parse(full - "caller_name" - "media_type")!!
        assertEquals("", invite.callerName)
        assertEquals("video", invite.mediaType)
    }
}
