package com.zisee.app.auth

import java.security.SecureRandom
import java.time.Clock
import java.util.UUID

/** UUIDv7: 48-bit epoch milliseconds, version 7, RFC variant, 74 random bits.
 * Public identifier only; never use this value as a credential.
 */
class IdentityIdGenerator(
    private val clock: Clock = Clock.systemUTC(),
    private val random: SecureRandom = SecureRandom(),
) {
    fun newId(): String {
        val millis = clock.millis()
        require(millis in 0..0xFFFFFFFFFFFFL)
        val high = (millis shl 16) or 0x7000L or (random.nextLong() and 0xFFFL)
        val low = (random.nextLong() and 0x3FFFFFFFFFFFFFFFL) or Long.MIN_VALUE
        return "zid_${UUID(high, low)}"
    }
}
