package app.sendfpl.cxp

/** [n] bytes from the platform's cryptographic random source. */
internal expect fun secureRandomBytes(n: Int): ByteArray
