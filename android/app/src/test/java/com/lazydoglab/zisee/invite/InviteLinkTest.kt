package com.lazydoglab.zisee.invite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InviteLinkTest {
    private val token = "A".repeat(42) + "_"

    @Test fun `builds a link a scanner can open`() {
        assertEquals("zisee://join/$token", InviteLink.of(token))
    }

    @Test fun `reads back the token it wrote`() {
        assertEquals(token, InviteLink.token(InviteLink.of(token)))
    }

    @Test fun `accepts a bare code so typing still works`() {
        assertEquals(token, InviteLink.token(token))
    }

    @Test fun `trims whitespace around a pasted value`() {
        assertEquals(token, InviteLink.token("  ${InviteLink.of(token)}\n"))
    }

    @Test fun `accepts every character the backend can issue`() {
        val issued = "abcXYZ0189-_" + "z".repeat(31)
        assertTrue(InviteLink.isToken(issued))
        assertEquals(issued, InviteLink.token(InviteLink.of(issued)))
    }

    @Test fun `rejects a token of the wrong length`() {
        assertNull(InviteLink.token("A".repeat(42)))
        assertNull(InviteLink.token("A".repeat(44)))
    }

    @Test fun `rejects characters outside base64url`() {
        assertNull(InviteLink.token("A".repeat(42) + "+"))
        assertNull(InviteLink.token("A".repeat(42) + "/"))
        assertNull(InviteLink.token("A".repeat(42) + "="))
    }

    @Test fun `rejects anything trailing the token rather than trimming it`() {
        assertNull(InviteLink.token(InviteLink.of(token) + "?next=evil"))
        assertNull(InviteLink.token(InviteLink.of(token) + "#fragment"))
        assertNull(InviteLink.token(InviteLink.of(token) + "/extra"))
    }

    @Test fun `rejects another scheme or host`() {
        assertNull(InviteLink.token("https://join/$token"))
        assertNull(InviteLink.token("zisee://call/$token"))
        assertNull(InviteLink.token("zisee://join//$token"))
    }

    @Test fun `rejects empty and missing input`() {
        assertNull(InviteLink.token(null))
        assertNull(InviteLink.token(""))
        assertNull(InviteLink.token("zisee://join/"))
    }
}
