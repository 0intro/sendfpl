package app.sendfpl

/** An RFCOMM socket reports its failures as an IOException, as every Android stream does. */
internal actual fun isLinkFailure(e: Throwable): Boolean = e is java.io.IOException
