package app.sendfpl.cxp

import java.security.SecureRandom

private val random = SecureRandom()

internal actual fun secureRandomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }
