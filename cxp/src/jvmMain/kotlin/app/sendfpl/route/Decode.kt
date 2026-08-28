package app.sendfpl.route

import java.nio.charset.Charset

internal actual fun platformDecode(bytes: ByteArray, encoding: String): String? {
    // An illegal name and an unsupported one are both IllegalArgumentExceptions.
    val charset = try {
        Charset.forName(encoding)
    } catch (e: IllegalArgumentException) {
        return null
    }
    return String(bytes, charset)
}
