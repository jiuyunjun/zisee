package com.lazydoglab.zisee.rtc

import org.junit.Assert.*
import org.junit.Test

class SharePresentationTest {
    @Test fun `round trips both states`() {
        assertEquals(SharePresentation.None, SharePresentation.decode(SharePresentation.None.encode()))
        val sharing = SharePresentation(true, "a1b2c3d4e5f6")
        assertEquals(sharing, SharePresentation.decode(sharing.encode()))
    }

    /** The control channel is a peer-supplied byte buffer, so every field is bounded on decode. */
    @Test fun `rejects malformed oversized and inconsistent messages`() {
        for (text in listOf("", "S1", "S1|1", "S1|1|a|b", "S2|1|a", "1|1|a", "S1|2|a",
            // Sharing without a session and a session without sharing are both contradictions.
            "S1|1|", "S1|0|abc",
            // A session id has to stay an identifier, and stay short enough to be bounded.
            "S1|1|a-b", "S1|1|a b", "S1|1|" + "a".repeat(SharePresentation.MAX_SESSION + 1),
            "S1|1|" + "a".repeat(64))) {
            assertNull("decoded $text", SharePresentation.decode(text))
        }
    }

    @Test fun `a camera message never decodes as a share message`() {
        val camera = CameraPresentation(CameraMode.DUAL, true).encode()
        assertNull(SharePresentation.decode(camera))
        assertNull(CameraPresentation.decode(SharePresentation(true, "abc").encode()))
        assertNull(ViewRequest.decode(SharePresentation(true, "abc").encode()))
        assertNull(SharePresentation.decode(ViewRequest.Default.encode()))
    }
}
