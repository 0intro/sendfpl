package app.sendfpl.cxp

import java.util.Random
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** A link whose inbox a test loads by hand, and which records what was sent. */
private class Loopback : Link {
    var inbox = ByteArray(0)
    var sent = ByteArray(0)
    override fun send(data: ByteArray) { sent += data }
    override fun receive(timeoutMillis: Long): ByteArray {
        val d = inbox
        inbox = ByteArray(0)
        return d
    }
    fun sentPackets(): List<Packet> = iterPackets(sent).packets
}

/**
 * How the session recovers from what a lossy link does: stale acknowledgements, repeats, a lost or
 * repeated SYN, and data before the handshake. A reference implementation asserts the same cases.
 */
class SessionRecoveryTest {

    private fun syn(psn: Int, ack: Int, id: Long, params: SynParams = GPS175_PARAMS) =
        Packet(Ctrl.SYN or Ctrl.ACK, psn, ack, params.copy(syncId = id).encodePayload()).encode()

    private fun data(psn: Int, body: String) =
        Packet(Ctrl.DATA or Ctrl.ACK, psn, 0, body.toByteArray()).encode()

    private fun connected(params: SynParams = GPS175_PARAMS): Pair<Session, Loopback> {
        val link = Loopback()
        link.inbox = syn(0, 1, 0xBEEFL, params)
        val session = Session(link, params)
        session.connect(timeoutMillis = 1_000)
        return session to link
    }

    @Test
    fun `an acknowledgement outside the send window changes nothing`() {
        val wide = ANDROID_PEER_PARAMS
        val (session, link) = connected(wide)
        repeat(3) { session.send("x".toByteArray()) }   // psns 2..4
        link.sent = ByteArray(0)

        // One behind the last acknowledged, and one for a packet never sent.
        link.inbox = Packet(Ctrl.ACK, 9, 0, ByteArray(0)).encode() +
            Packet(Ctrl.ACK, 9, 9, ByteArray(0)).encode()
        session.poll()
        session.receive(10)

        // All three are still outstanding, so flush cannot finish against a silent peer.
        val failure = runCatching { session.flush(timeoutMillis = 50) }.exceptionOrNull()
        assertTrue("flush should still be waiting, got $failure", failure is SessionException)
    }

    @Test
    fun `a packet already delivered is acknowledged again and not delivered twice`() {
        val (session, link) = connected()
        link.inbox = data(1, "one")
        assertEquals("one", String(session.receive(50)!!))
        link.sent = ByteArray(0)

        link.inbox = data(1, "one")
        assertNull(session.receive(50))
        val sent = link.sentPackets()
        assertTrue("no EAK for a duplicate", sent.none { (it.ctrl and Ctrl.EAK) != 0 })
        assertTrue("a bare ack of psn 1", sent.any { it.payload.isEmpty() && it.ack == 1 })
    }

    @Test
    fun `data before the peer's SYN is dropped`() {
        val link = Loopback()
        link.inbox = data(7, "early") + syn(0, 1, 0xBEEFL) + data(1, "first")
        val session = Session(link, GPS175_PARAMS)
        session.connect(timeoutMillis = 1_000)
        assertEquals("first", String(session.receive(50)!!))
        assertNull(session.receive(50))
    }

    @Test
    fun `a SYN too short to decode is ignored`() {
        val link = Loopback()
        link.inbox = Packet(Ctrl.SYN or Ctrl.ACK, 0, 1, byteArrayOf(1, 2, 3)).encode() +
            syn(0, 1, 0xBEEFL)
        val session = Session(link, GPS175_PARAMS)
        session.connect(timeoutMillis = 1_000)
        assertEquals(1024, session.negotiated!!.maxSz)
    }

    @Test
    fun `a repeated SYN is only acknowledged, and a new sync id ends the session`() {
        val (session, link) = connected()
        link.inbox = data(1, "a") + syn(0, 1, 0xBEEFL) + data(2, "b")
        assertEquals("a", String(session.receive(50)!!))
        assertEquals("a repeated SYN rewound nothing", "b", String(session.receive(50)!!))

        link.inbox = syn(0, 1, 0xCAFEL)
        val failure = runCatching { session.receive(50) }.exceptionOrNull()
        assertTrue("got $failure", failure is SessionException)
        assertTrue(
            "the failure sticks",
            runCatching { session.receive(50) }.exceptionOrNull() is SessionException,
        )
    }

    @Test
    fun `an EAK marks the gap before its last entry for resend at once`() {
        var clock = 0L
        val link = Loopback()
        link.inbox = syn(0, 1, 0xBEEFL, ANDROID_PEER_PARAMS)
        val session = Session(link, ANDROID_PEER_PARAMS)
        session.nowMillis = { clock }
        session.connect(timeoutMillis = 1_000)
        repeat(3) { session.send(byteArrayOf(it.toByte())) }   // psns 2..4
        link.sent = ByteArray(0)

        // 4 arrived, 3 did not; the ack covers 2. No time passes, so only the EAK can resend 3.
        link.inbox = Packet(Ctrl.EAK or Ctrl.ACK, 9, 2, encodeEak(listOf(4))).encode()
        session.receive(10)
        assertEquals(listOf(3), link.sentPackets().filter { it.isData }.map { it.psn })
    }

    /** A session on [params] whose timers read [clock], connected to a peer that says no more. */
    private fun clocked(params: SynParams, clock: () -> Long): Pair<Session, Loopback> {
        val link = Loopback()
        link.inbox = syn(0, 1, 0xBEEFL, params)
        val session = Session(link, params)
        session.nowMillis = clock
        session.connect(timeoutMillis = 1_000)
        return session to link
    }

    /**
     * The retransmit timer follows the oldest packet in flight. Here an EAK releases psn 3, the
     * cumulative ack then covers psns 2 and 3, and psn 4 is lost: it must still be resent when the
     * timeout expires.
     */
    @Test
    fun `the retransmit timer follows the oldest packet in flight`() {
        var clock = 0L
        val (session, link) = clocked(ANDROID_PEER_PARAMS) { clock }
        session.send("a".toByteArray())   // psn 2
        session.send("b".toByteArray())   // psn 3

        link.inbox = Packet(Ctrl.EAK or Ctrl.ACK, 9, 1, encodeEak(listOf(3))).encode()
        session.receive(10)
        link.inbox = Packet(Ctrl.ACK, 9, 3, ByteArray(0)).encode()
        session.receive(10)
        session.send("c".toByteArray())   // psn 4
        link.sent = ByteArray(0)

        clock += ANDROID_PEER_PARAMS.toRetry
        session.poll()
        assertEquals(listOf(4), link.sentPackets().filter { it.isData }.map { it.psn })
    }

    /**
     * The send window counts every psn past the cumulative ack, as `window_check` does: packets an
     * EAK released leave no room while the one before them is missing, since the peer accepts
     * nothing past its own window.
     */
    @Test
    fun `packets an EAK released still fill the send window`() {
        val params = ANDROID_PEER_PARAMS.copy(mxOut = 4, toRetry = 5)
        val (session, link) = clocked(params) { 0L }
        repeat(4) { session.send("x".toByteArray()) }   // psns 2..5
        link.inbox = Packet(Ctrl.EAK or Ctrl.ACK, 9, 1, encodeEak(listOf(3, 4, 5))).encode()
        session.receive(10)
        link.sent = ByteArray(0)

        val failure = runCatching { session.send("y".toByteArray()) }.exceptionOrNull()
        assertTrue("got $failure", failure is SessionException)
        assertTrue("psn 6 went out", link.sentPackets().none { it.isData && it.psn == 6 })
    }

    @Test
    fun `a failed session refuses send and flush and writes nothing`() {
        val (session, link) = connected()
        link.inbox = syn(0, 1, 0xCAFEL)
        assertTrue(runCatching { session.receive(50) }.exceptionOrNull() is SessionException)
        link.sent = ByteArray(0)

        val send = runCatching { session.send("x".toByteArray()) }.exceptionOrNull()
        assertTrue("send: $send", send is SessionException)
        val flush = runCatching { session.flush(50) }.exceptionOrNull()
        assertTrue("flush: $flush", flush is SessionException)
        assertTrue(link.sentPackets().isEmpty())
    }

    @Test
    fun `an EAK without the ACK bit releases nothing`() {
        val (session, link) = connected(ANDROID_PEER_PARAMS)
        session.send("x".toByteArray())   // psn 2
        link.inbox = Packet(Ctrl.EAK, 9, 1, encodeEak(listOf(2))).encode()
        session.receive(10)
        val failure = runCatching { session.flush(timeoutMillis = 50) }.exceptionOrNull()
        assertTrue("psn 2 should still be outstanding", failure is SessionException)
    }

    @Test
    fun `an empty payload is refused`() {
        val (session, link) = connected()
        link.sent = ByteArray(0)
        assertTrue(runCatching { session.send(ByteArray(0)) }.isFailure)
        assertTrue(link.sentPackets().isEmpty())
    }

    @Test
    fun `a lost SYN is resent about a second later`() {
        val times = mutableListOf<Long>()
        val link = object : Link {
            var reply = ByteArray(0)
            override fun send(data: ByteArray) {
                for (p in iterPackets(data).packets) {
                    if ((p.ctrl and Ctrl.SYN) == 0) continue
                    times += System.nanoTime() / 1_000_000
                    if (times.size > 1) reply = syn(0, p.psn, 0xBEEFL)
                }
            }
            override fun receive(timeoutMillis: Long): ByteArray {
                if (reply.isEmpty()) Thread.sleep(minOf(timeoutMillis, 5))
                return reply.also { reply = ByteArray(0) }
            }
        }
        Session(link, GPS175_PARAMS).connect(timeoutMillis = 5_000)
        assertEquals(2, times.size)
        val gap = times[1] - times[0]
        assertTrue("resent after $gap ms, want about 1000", gap in 900..2000)
    }

    /**
     * Two sessions exchange 300 messages, more than one trip round the sequence space, over a link
     * that loses, repeats or delays one packet in ten. Everything arrives once, in order, with a
     * window of one packet and with a wider one, where EAKs come into play.
     */
    @Test
    fun `a lossy link delivers everything once and in order`() = lossy(mxOut = 1, seed = 1)

    @Test
    fun `a lossy link delivers everything with a wider window too`() = lossy(mxOut = 4, seed = 1)

    private fun lossy(mxOut: Int, seed: Long) {
        val params = SynParams(
            mxOut = mxOut, mxRetry = 10, mxCmltv = 1, maxSz = 64, toRetry = 20, toCmltv = 5,
        )
        val wire = LossyWire(Random(seed))
        val a = Session(wire.end(0), params)
        val b = Session(wire.end(1), params)

        var bFailure: Throwable? = null
        val handshake = thread { runCatching { b.connect(5_000) }.onFailure { bFailure = it } }
        a.connect(5_000)
        handshake.join()
        bFailure?.let { fail("b: $it") }
        wire.lossy = true

        val n = 300
        val got = mutableListOf<String>()
        val receiver = thread {
            try {
                while (got.size < n) got += String(b.receive(10_000) ?: break)
                b.receive(500)   // keep acknowledging until the sender has heard the last ack
            } catch (e: Throwable) {
                bFailure = e
            }
        }
        repeat(n) { a.send("message $it".toByteArray()) }
        a.flush(10_000)
        receiver.join()
        bFailure?.let { fail("receiver: $it") }

        assertEquals(List(n) { "message $it" }, got)
        assertTrue(
            "the link lost ${wire.lost}, repeated ${wire.repeated}, delayed ${wire.delayed}",
            wire.lost > 0 && wire.repeated > 0 && wire.delayed > 0,
        )
    }

    /** Two ends in memory. Once lossy, one packet in ten each is lost, repeated or delayed. */
    private class LossyWire(private val random: Random) {
        @Volatile var lossy = false
        private val buf = arrayOf(ByteArray(0), ByteArray(0))
        private val late = arrayOf(ByteArray(0), ByteArray(0))
        var lost = 0
        var repeated = 0
        var delayed = 0

        fun end(me: Int): Link = object : Link {
            override fun send(data: ByteArray) = synchronized(this@LossyWire) {
                val to = 1 - me
                for (p in iterPackets(data).packets) {
                    val raw = p.encode()
                    when (if (lossy) random.nextInt(10) else 9) {
                        0 -> lost++
                        1 -> { repeated++; buf[to] += raw + raw }
                        2 -> { delayed++; late[to] += raw }
                        else -> buf[to] += raw
                    }
                }
            }

            override fun receive(timeoutMillis: Long): ByteArray {
                val got = synchronized(this@LossyWire) {
                    (buf[me] + late[me]).also { buf[me] = ByteArray(0); late[me] = ByteArray(0) }
                }
                if (got.isEmpty()) Thread.sleep(minOf(timeoutMillis, 1))
                return got
            }
        }
    }
}
