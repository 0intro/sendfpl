package app.sendfpl

import android.util.Log

/**
 * The app's log, mirrored to logcat so a failure reported from the field and one reproduced on the
 * bench read the same. [line] is on in every build and carries no credential, no position and no
 * account identifier; [bytes] is debug only.
 */
object Trace {
    const val TAG = "cxp"

    fun line(text: String) = Log.i(TAG, text)

    /**
     * Raw link bytes in the direction [arrow]: the RFCOMM payload, without the L2CAP framing an HCI
     * snoop log would add. Debug builds only, because the bytes sent include the AUTH_USER
     * entitlement.
     */
    fun bytes(arrow: String, data: ByteArray) {
        if (!BuildConfig.DEBUG) return
        val hex = data.joinToString(" ") { "%02x".format(it) }
        // logcat drops a line over about 4000 characters, which a read of 4096 bytes far exceeds.
        hex.chunked(3 * 512).forEachIndexed { i, part ->
            Log.d(TAG, "%s [%d B]%s %s".format(arrow, data.size, if (i == 0) "" else " …", part))
        }
    }
}
