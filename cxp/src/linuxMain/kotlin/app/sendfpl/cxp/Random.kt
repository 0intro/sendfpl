package app.sendfpl.cxp

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread

@OptIn(ExperimentalForeignApi::class)
internal actual fun secureRandomBytes(n: Int): ByteArray {
    val out = ByteArray(n)
    if (n == 0) return out
    val f = checkNotNull(fopen("/dev/urandom", "rb")) { "cannot open /dev/urandom" }
    try {
        val got = out.usePinned { fread(it.addressOf(0), 1u, n.convert(), f) }
        check(got.toInt() == n) { "read $got of $n bytes from /dev/urandom" }
    } finally {
        fclose(f)
    }
    return out
}
