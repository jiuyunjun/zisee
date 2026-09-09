package com.lazydoglab.zisee.auth

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityTest {
    @Test fun uuidHasCorrectVersionVariantAndTimestamp() {
        val millis = 1_788_825_600_000L
        val generator = IdentityIdGenerator(Clock.fixed(Instant.ofEpochMilli(millis), ZoneOffset.UTC))
        val ids = (1..1000).map { generator.newId() }
        assertEquals(1000, ids.toSet().size)
        ids.forEach {
            assertTrue(it.startsWith("zid_"))
            val uuid = UUID.fromString(it.removePrefix("zid_"))
            assertEquals(7, uuid.version())
            assertEquals(2, uuid.variant())
            assertEquals(millis, uuid.mostSignificantBits ushr 16)
        }
    }

    @Test fun displayNamePreservesUnicodeAndTrimsWhitespace() {
        assertEquals("九云", DisplayName.normalize("  九云  "))
        assertEquals("😀".repeat(40), DisplayName.normalize("😀".repeat(40)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankNameIsRejected() { DisplayName.normalize(" \t ") }

    @Test(expected = IllegalArgumentException::class)
    fun longNameIsRejected() { DisplayName.normalize("字".repeat(41)) }

    @Test(expected = IllegalArgumentException::class)
    fun controlCharacterIsRejected() { DisplayName.normalize("a\nb") }
}
