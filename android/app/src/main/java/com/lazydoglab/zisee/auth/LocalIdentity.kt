package com.lazydoglab.zisee.auth

import java.time.Instant

data class LocalIdentity(
    val identityId: String,
    val displayName: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

object DisplayName {
    const val MAX_LENGTH = 40

    fun normalize(value: String): String = value.trim().also {
        require(it.isNotEmpty()) { "Display name must not be empty" }
        require(it.codePointCount(0, it.length) <= MAX_LENGTH) { "Display name is too long" }
        require(it.none(Char::isISOControl)) { "Display name contains control characters" }
    }
}
