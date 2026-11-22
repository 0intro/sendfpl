package app.sendfpl

/** The accessory's streams failed: the navigator went away, or the session could not open. */
class LinkException(message: String, cause: Throwable? = null) : Exception(message, cause)

internal actual fun isLinkFailure(e: Throwable): Boolean = e is LinkException
