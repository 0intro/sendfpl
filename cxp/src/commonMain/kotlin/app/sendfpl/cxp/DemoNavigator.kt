package app.sendfpl.cxp

import kotlin.concurrent.Volatile

/**
 * A GPS 175 in memory: the navigator's side of a session over a [FrameStream], as a trainer at
 * software 3.21.2 holds one with no transport, for tests and for an app with no navigator at hand.
 * It asks for the client's id list, challenges AUTH_USER, checks the response against [token],
 * sends its own id list once satisfied, opens the upload channel when the client announces it,
 * publishes its capabilities and its identification once they are registered for, and takes an
 * upload in silence, or refuses it with ABORT when [refuse] is set. Like a navigator, it publishes
 * only what the client registered for.
 */
class DemoNavigator(
    private val token: ByteArray,
    private val productId: Long = Profiles.GPS175.productId,
    private val refuse: Boolean = false,
) : Link {
    /** The routes uploaded to it, in order. */
    val uploads = mutableListOf<String>()

    private val out = ArrayDeque<ByteArray>()
    private var inbox = ByteArray(0)
    private val parts = mutableMapOf<Long, MutableList<AppFrame>>()
    private val registered = mutableSetOf<Long>()
    private val held = mutableMapOf<Long, ByteArray>()
    private var challenge = ByteArray(0)
    private var confirmed = false
    @Volatile
    private var closed = false

    init {
        // A navigator asks for the client's id list as soon as its stack is up.
        out += controlFrame(listOf(setMode(ID_LIST_CXP_ID, CxpMode.PRDC, PRIORITY_CONTROL))).encode()
    }

    override fun send(data: ByteArray) {
        check(!closed) { "the demo navigator is closed" }
        inbox += data
        while (true) {
            val frame = runCatching { AppFrame.decode(inbox) }.getOrNull() ?: break
            inbox = inbox.copyOfRange(frame.consumed, inbox.size)
            take(frame.value)
        }
    }

    override fun receive(timeoutMillis: Long): ByteArray {
        check(!closed) { "the demo navigator is closed" }
        out.removeFirstOrNull()?.let { return it }
        sleepMillis(minOf(timeoutMillis, 10))
        return ByteArray(0)
    }

    override fun close() {
        closed = true
    }

    private fun take(f: AppFrame) {
        if (f.type and FrameType.CONTROL != 0 && f.cxpId == ID_LIST_CXP_ID) {
            for (c in decodeControl(f.payload)) if (c.op == Opcode.SET_MODE) register(c)
            return
        }
        val frames = if (f.type and FrameType.BEGIN != 0) mutableListOf(f) else parts[f.cxpId]?.apply { add(f) }
        if (frames == null) return
        if (!f.isLast) {
            parts[f.cxpId] = frames
            return
        }
        parts.remove(f.cxpId)
        message(f.cxpId, reassemble(frames))
    }

    private fun register(c: ControlCmd) {
        if (c.mode == CxpMode.OFF) {
            registered -= c.cxpId
            return
        }
        registered += c.cxpId
        if (c.cxpId == ID_LIST_CXP_ID) publish(ID_LIST_CXP_ID, encodeIdList(if (confirmed) AFTER_AUTH else BEFORE_AUTH))
        held.remove(c.cxpId)?.let { publish(c.cxpId, it) }
    }

    private fun message(id: Long, body: ByteArray) {
        when (id) {
            AuthId.USER -> challenge()
            AuthId.RESPONSE -> respond(body)
            ID_LIST_CXP_ID -> if (confirmed && FplId.UPLOAD_TO_AVIONICS in decodeIdList(body)) {
                out += controlFrame(listOf(setMode(FplId.UPLOAD_TO_AVIONICS, CxpMode.PRDC, PRIORITY_DEFAULT))).encode()
            }
            FplId.UPLOAD_TO_AVIONICS -> if (refuse) {
                out += controlFrame(listOf(ControlCmd(Opcode.ABORT, id))).encode()
            } else {
                uploads += latin1(body)
            }
        }
    }

    private fun challenge() {
        challenge = secureRandomBytes(CHALLENGE_LEN)
        publish(AuthId.CHALLENGE, challenge)
    }

    /** A response that does not verify draws a CONFIRM of zeros and a fresh challenge. */
    private fun respond(body: ByteArray) {
        val r = runCatching { AuthResponse.decode(body) }.getOrNull() ?: return
        if (!constantTimeEquals(r.digest, digest(token, challenge))) {
            publish(AuthId.CONFIRM, ByteArray(NONCE_LEN))
            challenge()
            return
        }
        confirmed = true
        publish(AuthId.CONFIRM, digest(token, r.nonce))
        publish(ID_LIST_CXP_ID, encodeIdList(AFTER_AUTH))
        publish(FplId.SUPPORTED_ELEMENTS, CAPABILITIES.encode())
        publish(ID_CONNECTED_LRU, identification())
    }

    /** Send [body] on [id] if it is registered for, and hold it until it is otherwise. */
    private fun publish(id: Long, body: ByteArray) {
        if (id !in registered) {
            held[id] = body
            return
        }
        for (frame in segment(id, body, SPP_PAYLOAD)) out += frame.encode()
    }

    /** `u32 product_id, char name[12], char version[8]`, as on `0x00001006`. */
    private fun identification(): ByteArray {
        val b = ByteArray(PRODUCT_DATA_SIZE)
        putU32(b, 0, productId)
        "GPS 175".encodeToByteArray().copyInto(b, 4)
        "3.30".encodeToByteArray().copyInto(b, 16)
        return b
    }

    private companion object {
        /** What a GPS 175 negotiates on SPP, less a packet's and a frame's headers. */
        const val SPP_PAYLOAD = 1024 - 17

        /** A GPS 175's capabilities, as its trainer publishes them. */
        val CAPABILITIES = SupportedElements(elements = 0x0004002f, maxTextLength = 3520, waypointTypes = 3)

        /** The ids a navigator announces before the client authenticates, as a trainer's. */
        val BEFORE_AUTH = listOf(ID_LIST_CXP_ID, 0x101L, AuthId.CONFIRM, AuthId.CHALLENGE)

        /** And after: its own, and the flight plan set. */
        val AFTER_AUTH = BEFORE_AUTH + listOf(
            ID_CONNECTED_LRU, FplId.SUPPORTED_ELEMENTS, 0x10005002L, FplId.MINIMAL_TRANSFER_TO_SIMPLE_AVIONICS,
        )
    }
}
