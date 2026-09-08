package com.zisee.app.invite

/**
 * The `zisee://join/<token>` form of an invitation.
 *
 * Kept free of Android types so the parsing rules are covered by JVM tests: a link arrives from
 * outside the app, so it is validated exactly as strictly as a typed code.
 */
object InviteLink {
    const val SCHEME = "zisee"
    const val HOST = "join"
    private const val PREFIX = "$SCHEME://$HOST/"

    /** The backend issues base64url tokens of exactly this shape. */
    private val TOKEN = Regex("[A-Za-z0-9_-]{43}")

    fun of(token: String): String = PREFIX + token

    fun isToken(value: String): Boolean = TOKEN.matches(value)

    /**
     * The token carried by [value], which may be a full link or a bare code, or null when it is
     * neither. Anything trailing the token — a query, a fragment, a second path segment — fails the
     * token pattern and is rejected rather than trimmed.
     */
    fun token(value: String?): String? {
        val trimmed = value?.trim() ?: return null
        val candidate = if (trimmed.startsWith(PREFIX)) trimmed.removePrefix(PREFIX) else trimmed
        return candidate.takeIf { isToken(it) }
    }
}
