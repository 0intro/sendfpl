package app.sendfpl.cxp

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault

@OptIn(ExperimentalForeignApi::class)
internal actual fun secureRandomBytes(n: Int): ByteArray {
    val out = ByteArray(n)
    if (n == 0) return out
    val status = out.usePinned { SecRandomCopyBytes(kSecRandomDefault, n.convert(), it.addressOf(0)) }
    check(status == errSecSuccess) { "SecRandomCopyBytes failed: $status" }
    return out
}
