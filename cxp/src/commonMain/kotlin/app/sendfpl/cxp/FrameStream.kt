package app.sendfpl.cxp

/**
 * What moves application frames between a client and a navigator. A [Session] carries them in the
 * CXP transport, over a link that can lose and reorder bytes, as an RFCOMM socket can. A
 * [FrameStream] writes them as they are onto a link that is reliable itself, as an iAP2 External
 * Accessory session is.
 */
interface Carrier {
    /**
     * Bring the carrier up, and return the transport parameters agreed, or null where there are
     * none.
     */
    fun connect(timeoutMillis: Long = 10_000): SynParams?

    /** Write one encoded application frame. */
    fun send(payload: ByteArray)

    /**
     * One or more whole application frames, or null when none arrives within [timeoutMillis].
     * Bytes that cannot begin a frame come as they are, for the client to report and drop.
     */
    fun receive(timeoutMillis: Long = 5_000): ByteArray?

    /** Return once everything sent has gone out, as the carrier can tell, or throw. */
    fun flush(timeoutMillis: Long = 10_000)

    fun close()
}

/** The longest application frame: its header and the largest payload the builder accepts. */
private const val MAX_FRAME = APP_HEADER_LEN + APP_MAX_PAYLOAD

/**
 * A [Carrier] over a link that delivers every byte, in order, which is how a navigator speaks CXP
 * to an iPhone. Over iAP the navigator stacks its application layer directly on the iAP layer, with
 * no transport between them: the GPS 175's `FUN_d847fa1c` builds `[app, iap]` for connection type
 * 3 and `[app, transport]` for the others. So a frame goes out as [AppFrame.encode] wrote it, and
 * the bytes that come in are cut into frames by the length in each header, a partial frame carried
 * over to the next read as the navigator's parser, `FUN_d8418c6c`, does.
 */
class FrameStream(
    private val link: Link,
    private val onEvent: (CxpEvent) -> Unit = {},
) : Carrier {
    private var buf = ByteArray(0)
    private var failure: Exception? = null

    /** Returns null at once: the frames go onto the link as they are. */
    override fun connect(timeoutMillis: Long): SynParams? {
        failure?.let { throw it }
        return null
    }

    override fun send(payload: ByteArray) {
        failure?.let { throw it }
        report(payload, sent = true)
        try {
            link.send(payload)
        } catch (e: Exception) {
            failure = e
            throw e
        }
    }

    /**
     * The whole frames that have arrived, waiting up to [timeoutMillis] for the first of them.
     * Bytes that cannot begin a frame, a wrong version or a length outside the header's range, are
     * returned as they are, for the client to report and drop. The stream then resumes at whatever
     * arrives next.
     */
    override fun receive(timeoutMillis: Long): ByteArray? {
        val deadline = elapsedMillis() + timeoutMillis
        while (true) {
            failure?.let { throw it }
            val n = whole()
            if (n > 0) {
                val out = buf.copyOfRange(0, n)
                buf = buf.copyOfRange(n, buf.size)
                report(out, sent = false)
                return out
            }
            val left = deadline - elapsedMillis()
            if (left <= 0) return null
            val got = try {
                link.receive(minOf(left, 200))
            } catch (e: Exception) {
                failure = e
                throw e
            }
            buf += got
        }
    }

    /**
     * How many leading bytes of the buffer are whole frames, or all of it when it begins with bytes
     * that cannot begin a frame.
     */
    private fun whole(): Int {
        var n = 0
        while (n < buf.size) {
            val m = frameLength(buf, n)
            if (m < 0) return if (n == 0) buf.size else n
            if (m == 0 || n + m > buf.size) return n
            n += m
        }
        return n
    }

    /** Every whole frame in [bytes], for the log. */
    private fun report(bytes: ByteArray, sent: Boolean) {
        var at = 0
        while (at < bytes.size) {
            val m = frameLength(bytes, at)
            if (m <= 0 || at + m > bytes.size) return
            onEvent(CxpEvent.Frame(sent, AppFrame.decode(bytes, at).value))
            at += m
        }
    }

    /** Return at once: [send] returns once the link has taken the frame whole. */
    override fun flush(timeoutMillis: Long) {
        failure?.let { throw it }
    }

    override fun close() = link.close()
}

/**
 * The length of the frame [b] holds at [at], 0 while its header is still incomplete, or -1 when the
 * bytes there cannot begin a frame.
 */
private fun frameLength(b: ByteArray, at: Int): Int {
    if (at >= b.size) return 0
    if ((b[at].toInt() and 0xFF) != APP_VERSION) return -1
    if (b.size - at < 3) return 0
    val total = getU16(b, at + 1)
    if (total < APP_HEADER_LEN || total > MAX_FRAME) return -1
    return total
}
