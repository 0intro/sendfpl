package app.sendfpl

import app.sendfpl.cxp.AppFrame
import app.sendfpl.cxp.AuthId
import app.sendfpl.cxp.AuthResponse
import app.sendfpl.cxp.ControlCmd
import app.sendfpl.cxp.Credential
import app.sendfpl.cxp.Ctrl
import app.sendfpl.cxp.CxpMode
import app.sendfpl.cxp.FplId
import app.sendfpl.cxp.FrameType
import app.sendfpl.cxp.GPS175_PARAMS
import app.sendfpl.cxp.ID_CONNECTED_LRU
import app.sendfpl.cxp.Link
import app.sendfpl.cxp.Opcode
import app.sendfpl.cxp.Packet
import app.sendfpl.cxp.Profiles
import app.sendfpl.cxp.SessionException
import app.sendfpl.cxp.SupportedElements
import app.sendfpl.cxp.controlFrame
import app.sendfpl.cxp.idListFrame
import app.sendfpl.cxp.iterPackets
import app.sendfpl.cxp.segment
import app.sendfpl.cxp.setMode
import app.sendfpl.route.RouteParser
import java.io.IOException
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The client against a navigator held in memory. A reference implementation pins the same cases
 * against its own client.
 */
class ConnextClientTest {

    /**
     * A navigator that answers our SYN, acknowledges every packet it receives, plays [messages] in
     * order, and answers each message we send with whatever [answer] returns. A null entry in
     * [messages] is a read that times out. Once [failure] is set, a read with nothing left to
     * deliver throws it.
     */
    private class Navigator(
        messages: List<ByteArray?> = emptyList(),
        private val answer: (AppFrame) -> List<AppFrame> = { emptyList() },
    ) : Link {
        private var psn = 0
        private val queue = ArrayDeque<ByteArray?>()
        var closed = false
        var failure: IOException? = null

        init {
            queue.addLast(packet(Ctrl.SYN, 1, GPS175_PARAMS.copy(syncId = 0x11223344L).encodePayload()))
            messages.forEach { queue.addLast(it?.let { m -> packet(Ctrl.DATA, 0, m) }) }
        }

        fun packet(ctrl: Int, ack: Int, payload: ByteArray): ByteArray {
            val p = Packet(ctrl or Ctrl.ACK, psn, ack, payload)
            if (payload.isNotEmpty()) psn = (psn + 1) and 0xFF
            return p.encode()
        }

        override fun send(data: ByteArray) {
            for (p in iterPackets(data).packets) {
                if (!p.isData) continue
                queue.addFirst(packet(Ctrl.DATA, p.psn, ByteArray(0)))
                val (frame, _) = AppFrame.decode(p.payload)
                answer(frame).forEach { queue.addLast(packet(Ctrl.DATA, p.psn, it.encode())) }
            }
        }

        override fun receive(timeoutMillis: Long): ByteArray {
            if (closed) throw IOException("link closed")
            if (queue.isEmpty()) {
                failure?.let { throw it }
                return ByteArray(0)
            }
            val next = queue.removeFirst()
            if (next == null) Thread.sleep(timeoutMillis)
            return next ?: ByteArray(0)
        }
    }

    private val credential = Credential(userId = 1, token = ByteArray(48), entitlement = byteArrayOf(1))

    /** One whole message, as the navigator frames it. */
    private fun message(cxpId: Long, payload: ByteArray) =
        AppFrame(cxpId, payload, FrameType.BEGIN or FrameType.END)

    /**
     * The 24 bytes a navigator sends on [ID_CONNECTED_LRU]. Only the id chooses a profile; no
     * GTN's name or version has been captured, so the ones given here are placeholders.
     */
    private fun identification(id: Long, name: String, version: String) =
        message(ID_CONNECTED_LRU, ByteArray(24).also {
            for (i in 0 until 4) it[i] = (id shr (8 * i)).toByte()
            name.toByteArray().copyInto(it, 4)
            version.toByteArray().copyInto(it, 16)
        })

    private val caps = SupportedElements(elements = 0x0004002f, maxTextLength = 3520, waypointTypes = 3)

    private fun connected(navigator: Navigator) =
        ConnextClient(navigator, credential).also { it.connect() }

    @Test
    fun `a late identification still chooses the profile`() {
        val client = connected(
            Navigator(listOf(identification(Profiles.GTN.productId, "GTN 750", "6.72").encode()))
        )
        val said = client.awaitProductData(1_000)
        assertEquals(Profiles.GTN.productId, said!!.productId)
        assertEquals(Profiles.GTN, said.profile)
        assertEquals(said, client.info.productData)

        // What the transfer does next: build for the model the navigator named.
        assertEquals(
            "FPN/RI:F:LFPL:F:N48475E002522:F:LFPK",
            RouteParser.parse("LFPL LOBEL,N48475E002522 LFPK").render(said.profile!!),
        )
    }

    @Test
    fun `no identification costs only the grace`() {
        val client = connected(Navigator())
        val grace = 300L
        val started = System.nanoTime()
        assertNull(client.awaitProductData(grace))
        val tookMillis = (System.nanoTime() - started) / 1_000_000
        assertTrue("the wait took $tookMillis ms for a grace of $grace", tookMillis < grace + 1_000)
    }

    @Test
    fun `what arrives while waiting is kept for whoever waits for it`() {
        val client = connected(
            Navigator(
                listOf(
                    message(FplId.SUPPORTED_ELEMENTS, caps.encode()).encode(),
                    identification(Profiles.GPS175.productId, "GPS 175", "3.22").encode(),
                )
            )
        )
        assertEquals(Profiles.GPS175, client.awaitProductData(1_000)!!.profile)
        // Read during the wait, parked, and there for the step that wanted it.
        assertEquals(caps, client.negotiateCapabilities())
    }

    /** The notification on 0x1000a000 comes about once a second, so it can arrive first. */
    @Test
    fun `negotiation parks a message that arrives before the capabilities`() {
        val client = connected(
            Navigator(
                listOf(
                    message(ID_NOTIFICATION, byteArrayOf(1, 2, 3)).encode(),
                    message(FplId.SUPPORTED_ELEMENTS, caps.encode()).encode(),
                )
            )
        )
        assertEquals(caps, client.negotiateCapabilities())
    }

    /** A control frame and a message in one transport payload, as a real GPS 175 sends them. */
    @Test
    fun `every frame of a payload is read`() {
        val control = controlFrame(listOf(setMode(FplId.UPLOAD_TO_AVIONICS, CxpMode.PRDC, 10)))
        val client = connected(
            Navigator(
                listOf(control.encode() + identification(Profiles.GPS175.productId, "GPS 175", "3.22").encode())
            )
        )
        assertEquals(Profiles.GPS175, client.awaitProductData(1_000)!!.profile)
    }

    @Test
    fun `a message straddling a timed-out wait arrives whole`() {
        val frames = segment(FplId.SUPPORTED_ELEMENTS, caps.encode(), 3).map { it.encode() }
        val client = connected(Navigator(listOf(frames[0], null) + frames.drop(1)))
        // The first wait ends between two segments, and the next must still see all of them.
        assertNull(client.awaitProductData(5))
        assertEquals(caps, client.negotiateCapabilities())
    }

    @Test
    fun `a frame with no message open, and bytes that are no frame, are dropped`() {
        val tail = AppFrame(0x10005004L, byteArrayOf(9), FrameType.END).encode()
        val client = connected(
            Navigator(
                listOf(
                    tail,
                    byteArrayOf(7, 0),
                    message(FplId.SUPPORTED_ELEMENTS, caps.encode()).encode(),
                )
            )
        )
        assertEquals(caps, client.negotiateCapabilities())
    }

    @Test
    fun `a closed link ends a wait at once`() {
        val navigator = Navigator()
        val client = connected(navigator)
        navigator.closed = true
        val started = System.nanoTime()
        val failure = runCatching { client.awaitProductData(5_000) }.exceptionOrNull()
        val tookMillis = (System.nanoTime() - started) / 1_000_000
        assertTrue("got $failure", failure is IOException)
        assertTrue("took $tookMillis ms to notice", tookMillis < 1_000)
    }

    /** A navigator that never accepts a response answers each with zeros and asks again. */
    @Test
    fun `four challenges without a confirm is a credential the navigator does not hold`() {
        val challenge = message(AuthId.CHALLENGE, ByteArray(16)).encode()
        val zero = message(AuthId.CONFIRM, ByteArray(16)).encode()
        val rounds = List(5) { listOf(challenge, zero) }.flatten()
        val client = ConnextClient(Navigator(rounds), credential)
        val failure = runCatching { client.connectAndAuthenticate() }.exceptionOrNull()
        assertTrue("got $failure", failure is SessionException)
        assertTrue(failure!!.message!!, "4 challenges" in failure.message!!)
    }

    /**
     * A rejected response draws a CONFIRM of sixteen zero bytes and a fresh challenge
     * (`cxp_auth_close`), and so does a response to a challenge already replaced: a lost round, in
     * either order with the challenge that follows it.
     */
    @Test
    fun `a confirm that does not verify is a lost round`() {
        for (zeroFirst in listOf(true, false)) {
            var responses = 0
            val navigator = Navigator { frame ->
                when (frame.cxpId) {
                    AuthId.USER -> listOf(message(AuthId.CHALLENGE, ByteArray(16) { 1 }))
                    AuthId.RESPONSE -> if (++responses == 1) {
                        val challenge = message(AuthId.CHALLENGE, ByteArray(16) { 2 })
                        val zero = message(AuthId.CONFIRM, ByteArray(16))
                        if (zeroFirst) listOf(zero, challenge) else listOf(challenge, zero)
                    } else {
                        val nonce = AuthResponse.decode(frame.payload).nonce
                        listOf(
                            message(AuthId.CONFIRM, confirm(nonce)),
                            idListFrame(listOf(FplId.SUPPORTED_ELEMENTS)),
                        )
                    }
                    else -> emptyList()
                }
            }
            ConnextClient(navigator, credential).connectAndAuthenticate()
            assertEquals("zero CONFIRM first: $zeroFirst", 2, responses)
        }
    }

    /** The CONFIRM a navigator holding [credential] returns for [nonce]: `MD5(token || nonce)`. */
    private fun confirm(nonce: ByteArray): ByteArray =
        MessageDigest.getInstance("MD5").run {
            update(credential.token)
            update(nonce)
            digest()
        }

    /**
     * A message that never ends is dropped once it outgrows any message a navigator sends, rather
     * than held without bound, and the next one on its id is read normally.
     */
    @Test
    fun `a message past any a navigator sends is dropped`() {
        val id = FplId.SUPPORTED_ELEMENTS
        val endless = listOf(AppFrame(id, ByteArray(1000), FrameType.BEGIN)) +
            List(70) { AppFrame(id, ByteArray(1000), FrameType.DATA) } +
            AppFrame(id, ByteArray(1000), FrameType.END)
        val messages = endless.map { it.encode() } + message(id, caps.encode()).encode()
        val client = connected(Navigator(messages))
        assertEquals(caps, client.negotiateCapabilities())
    }

    /** A refusal is reported as one, never as a plan delivered. */
    @Test
    fun `an ABORT after the upload is an error`() {
        val upload = FplId.UPLOAD_TO_AVIONICS
        val navigator = Navigator(
            listOf(
                controlFrame(listOf(setMode(upload, CxpMode.PRDC, 10))).encode(),
                message(FplId.SUPPORTED_ELEMENTS, caps.encode()).encode(),
            )
        ) { frame ->
            if (frame.cxpId == upload) listOf(controlFrame(listOf(ControlCmd(Opcode.ABORT, upload))))
            else emptyList()
        }
        val client = connected(navigator)
        val got = client.negotiateCapabilities()
        val failure = runCatching {
            client.upload("FPN/RI:F:LFPL:F:LFPK", Profiles.GPS175, got)
        }.exceptionOrNull()
        assertTrue("got $failure", failure is SessionException)
        assertTrue(failure!!.message!!, "ABORT" in failure.message!!)
    }

    /** What an upload throws, if anything, to a navigator that answers it with [answer]. */
    private fun uploadAnswered(answer: (Navigator) -> List<AppFrame>): Throwable? {
        val upload = FplId.UPLOAD_TO_AVIONICS
        lateinit var navigator: Navigator
        navigator = Navigator(
            listOf(
                controlFrame(listOf(setMode(upload, CxpMode.PRDC, 10))).encode(),
                message(FplId.SUPPORTED_ELEMENTS, caps.encode()).encode(),
            )
        ) { frame -> if (frame.cxpId == upload) answer(navigator) else emptyList() }
        val client = connected(navigator)
        client.verdictGraceMillis = 300
        val got = client.negotiateCapabilities()
        return runCatching {
            client.upload("FPN/RI:F:LFPL:F:LFPK", Profiles.GPS175, got)
        }.exceptionOrNull()
    }

    private fun Throwable?.isRefusal() = this is SessionException && "ABORT" in message!!

    /**
     * Control opcode 7 asks for compression (`cxp_app_ctrl_write`, case 7, flag 0x10000), so it
     * says nothing about the plan, and an ABORT beside it in the same control frame or the next is
     * still a refusal.
     */
    @Test
    fun `compression is no verdict`() {
        val abort = ControlCmd(Opcode.ABORT, FplId.UPLOAD_TO_AVIONICS)
        val compress = ControlCmd(Opcode.COMPRESS, FplId.UPLOAD_TO_AVIONICS, flag = 1)
        for (answer in listOf(
            listOf(controlFrame(listOf(abort, compress))),
            listOf(controlFrame(listOf(compress)), controlFrame(listOf(abort))),
            listOf(controlFrame(listOf(abort)), controlFrame(listOf(compress))),
            listOf(
                controlFrame(listOf(compress)),
                message(ID_NOTIFICATION, byteArrayOf(1)),
                controlFrame(listOf(abort)),
            ),
        )) {
            val failure = uploadAnswered { answer }
            assertTrue("got $failure", failure.isRefusal())
        }
        assertNull(uploadAnswered { listOf(controlFrame(listOf(compress))) })
    }

    /** An ABORT read by the wait's last pass, as the grace runs out, is still a refusal. */
    @Test
    fun `an ABORT read as the grace ends is still a refusal`() {
        val upload = FplId.UPLOAD_TO_AVIONICS
        val navigator = Navigator(
            listOf(
                controlFrame(listOf(setMode(upload, CxpMode.PRDC, 10))).encode(),
                message(FplId.SUPPORTED_ELEMENTS, caps.encode()).encode(),
                null,
                controlFrame(listOf(ControlCmd(Opcode.ABORT, upload))).encode(),
            )
        )
        val client = connected(navigator)
        client.verdictGraceMillis = 250
        val got = client.negotiateCapabilities()
        val failure = runCatching {
            client.upload("FPN/RI:F:LFPL:F:LFPK", Profiles.GPS175, got)
        }.exceptionOrNull()
        assertTrue("got $failure", failure.isRefusal())
    }

    /** An ABORT read just before the link fails is still the refusal it was, not a lost link. */
    @Test
    fun `an ABORT just before the link fails is still the refusal`() {
        val failure = uploadAnswered { navigator ->
            navigator.failure = IOException("link down")
            listOf(controlFrame(listOf(ControlCmd(Opcode.ABORT, FplId.UPLOAD_TO_AVIONICS))))
        }
        assertTrue("got $failure", failure.isRefusal())
    }

    @Test
    fun `an upload the navigator does not refuse succeeds`() {
        val upload = FplId.UPLOAD_TO_AVIONICS
        var received: ByteArray? = null
        val navigator = Navigator(
            listOf(
                controlFrame(listOf(setMode(upload, CxpMode.PRDC, 10))).encode(),
                message(FplId.SUPPORTED_ELEMENTS, caps.encode()).encode(),
            )
        ) { frame ->
            if (frame.cxpId == upload) received = frame.payload
            emptyList()
        }
        val client = connected(navigator)
        client.verdictGraceMillis = 50
        client.upload("FPN/RI:F:LFPL:F:LFPK", Profiles.GPS175, client.negotiateCapabilities())
        assertArrayEquals("FPN/RI:F:LFPL:F:LFPK".toByteArray(), received)
    }
}
