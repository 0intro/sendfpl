package app.sendfpl.route

/**
 * [bytes] decoded from [encoding], the name a declaration gives, by the platform's decoders, or
 * null for a name the platform does not know. A byte the encoding does not map reads as U+FFFD.
 * The JVM, and so the Android app, decodes every encoding it knows. Native targets decode none
 * beyond the six [decodeXml] decodes itself.
 */
internal expect fun platformDecode(bytes: ByteArray, encoding: String): String?
