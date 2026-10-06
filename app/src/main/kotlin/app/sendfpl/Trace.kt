package app.sendfpl

import android.util.Log

/**
 * Mirror of the log inside the app to logcat.
 *
 * The screen log is what a pilot sees, and this is what a developer with `adb` sees. They carry the
 * same lines deliberately: a failure reported from the field and a failure reproduced on the
 * bench should be the same text, so one can be matched against the other.
 *
 * Nothing here is conditional on the build type. The lines describe our own traffic to a device
 * the user already paired: no credential, no position, no account identifier. What *is* gated is
 * [bytes], because a raw dump is only useful when someone is reading it.
 */
object Trace {
    const val TAG = "cxp"

    fun line(text: String) = Log.i(TAG, text)

    /**
     * Raw link bytes, in the direction [arrow].
     *
     * This is the one thing an HCI snoop log would add over the log at session level, and this device
     * cannot produce one: enabling it needs `persist.bluetooth.btsnooplogmode`, which is settable
     * only by the system. So the link tap stands in for it, and it is the better tap anyway, since it
     * sees exactly the RFCOMM payload, with no L2CAP framing to strip.
     *
     * Debug only. A release build has no reason to narrate every packet.
     */
    fun bytes(arrow: String, data: ByteArray) {
        if (!BuildConfig.DEBUG) return
        val hex = data.joinToString(" ") { "%02x".format(it) }
        // logcat drops a line over ~4000 characters, and three characters per byte reaches that
        // at 1300, inside the reads of 4096 bytes this traces. Chunked so a long one is never lost.
        hex.chunked(3 * 512).forEachIndexed { i, part ->
            Log.d(TAG, "%s [%d B]%s %s".format(arrow, data.size, if (i == 0) "" else " …", part))
        }
    }
}
