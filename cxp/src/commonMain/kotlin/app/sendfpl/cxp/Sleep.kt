package app.sendfpl.cxp

/** Block the calling thread for [millis], as a link waiting out a read's timeout does. */
internal expect fun sleepMillis(millis: Long)
