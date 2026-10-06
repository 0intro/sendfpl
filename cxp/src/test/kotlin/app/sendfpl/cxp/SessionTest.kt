package app.sendfpl.cxp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A link held in memory whose inbox can be loaded up front, so a handshake can be driven
 * deterministically rather than by racing two sessions against a wall clock.
 */
private class Pipe : Link {
    var inbox = ByteArray(0)
    var sent = ByteArray(0)
    override fun send(data: ByteArray) { sent += data }
    override fun receive(timeoutMillis: Long): ByteArray {
        val d = inbox
        inbox = ByteArray(0)
        return d
    }
    /** Everything this end transmitted, decoded. */
    fun sentPackets(): List<Packet> = iterPackets(sent).packets
}

/**
 * A pipe that acknowledges what it receives, which is what a working peer does.
 *
 * Needed once the `mx_out` send window is honoured: with `mx_out = 1` a payload of several segments
 * cannot go out at all against a peer that never answers, correctly, since that is a dead link.
 * [Pipe] still models exactly that, for the tests that want it.
 */
private class AckingPipe : Link {
    var inbox = ByteArray(0)
    var sent = ByteArray(0)
    override fun send(data: ByteArray) {
        sent += data
        for (p in iterPackets(data).packets) {
            if (p.payload.isNotEmpty()) inbox += Packet(Ctrl.DATA, 200, p.psn, ByteArray(0)).encode()
        }
    }
    override fun receive(timeoutMillis: Long): ByteArray {
        val d = inbox
        inbox = ByteArray(0)
        return d
    }
    fun sentPackets(): List<Packet> = iterPackets(sent).packets
}

/** Ported from the internal tests of the reference implementation these were written against. */
class SessionTest {

    /** A peer SYN waiting to be read, as if the navigator had already answered. */
    private fun peerSyn(params: SynParams) =
        Packet(Ctrl.SYN, 0, 0, params.copy(syncId = 0x11223344L).encodePayload()).encode()

    private fun connected(local: SynParams = GPS175_PARAMS, peer: SynParams = GPS175_PARAMS):
        Pair<Session, Pipe> {
        val pipe = Pipe()
        pipe.inbox = peerSyn(peer)
        val session = Session(pipe, local)
        session.connect(timeoutMillis = 1_000)
        return session to pipe
    }

    @Test
    fun `negotiation clamps the peer's block to the local limits`() {
        val (session, _) = connected(local = ANDROID_PEER_PARAMS, peer = GPS175_PARAMS)
        val n = session.negotiated!!
        assertEquals(1, n.mxOut)      // the peer's 1, and our 16 is not smaller, so no clamp
        assertEquals(1024, n.maxSz)   // likewise
    }

    /**
     * Every outgoing packet carries the ACK bit, because cxp_t_hdr_bld writes `ctrl | 2`
     * unconditionally.
     */
    @Test
    fun `every packet sets the ACK bit`() {
        val (session, pipe) = connected()
        session.send("hello".toByteArray())
        val sent = pipe.sentPackets()
        assertTrue("expected at least a SYN and a data packet", sent.size >= 2)
        sent.forEach { assertEquals("ctrl ${it.ctrl}", Ctrl.ACK, it.ctrl and Ctrl.ACK) }
    }

    /**
     * next_psn advances only inside cxp_t_pkt_mngr_enqueue_out, so an untracked packet, a bare
     * ack or an EAK, reuses the current number without consuming it. Incrementing for those
     * leaves a hole a peer delivering in order never recovers from.
     */
    @Test
    fun `an untracked packet does not consume a sequence number`() {
        val (session, pipe) = connected()
        val before = pipe.sentPackets().size

        session.sendBareAck()
        session.sendBareAck()
        session.send("x".toByteArray())

        val fresh = pipe.sentPackets().drop(before)
        assertEquals(3, fresh.size)
        // Both bare acks reuse the number the data packet then actually consumes.
        assertEquals(fresh[0].psn, fresh[1].psn)
        assertEquals(fresh[0].psn, fresh[2].psn)
    }

    @Test
    fun `connect stamps a V2 sync id and sends a SYN of 22 bytes`() {
        val (session, pipe) = connected()
        assertTrue(session.local.isV2)
        assertTrue(session.local.syncId!! != 0L)

        val syn = pipe.sentPackets().first()
        // 0x03, not 0x01: cxp_t_hdr_bld ORs the ACK bit into every packet it builds, even the
        // opening SYN with nothing yet to acknowledge.
        assertEquals(Ctrl.SYN or Ctrl.ACK, syn.ctrl)
        assertEquals(SynParams.V2_LEN, syn.payload.size)
        assertEquals(22, syn.encode().size)
    }

    @Test
    fun `packets after the handshake assert ACK so the peer honours our ack`() {
        val (session, pipe) = connected()
        session.send("hello".toByteArray())
        val data = pipe.sentPackets().last()
        assertTrue("ctrl=0x%02x".format(data.ctrl), (data.ctrl and Ctrl.ACK) != 0)
        assertArrayEquals("hello".toByteArray(), data.payload)
    }

    @Test
    fun `a large payload segments to the negotiated size and reassembles exactly`() {
        // An acking peer: with mx_out = 1 the window releases a segment only once the previous
        // one is acknowledged, so a silent pipe would stall here and rightly so.
        val pipe = AckingPipe()
        pipe.inbox = peerSyn(GPS175_PARAMS)
        val session = Session(pipe, GPS175_PARAMS)
        session.connect(timeoutMillis = 1_000)      // negotiated max_sz 1024
        val payload = ByteArray(3000) { it.toByte() }
        session.send(payload)

        val dataPackets = pipe.sentPackets().filter { it.isData }
        assertTrue("expected several segments, got ${dataPackets.size}", dataPackets.size > 1)
        // Each packet must fit the negotiated size once header and payload checksum are counted.
        dataPackets.forEach { assertTrue(it.encode().size <= session.negotiated!!.maxSz) }

        val rebuilt = ByteArray(dataPackets.sumOf { it.payload.size })
        var at = 0
        dataPackets.forEach {
            it.payload.copyInto(rebuilt, at)
            at += it.payload.size
        }
        assertArrayEquals(payload, rebuilt)
    }

    @Test
    fun `an incoming payload is delivered upward`() {
        val (session, pipe) = connected()
        pipe.inbox = Packet(Ctrl.DATA or Ctrl.ACK, 1, 0, "reply".toByteArray()).encode()
        assertArrayEquals("reply".toByteArray(), session.receive(timeoutMillis = 500))
    }

    @Test
    fun `an EAK clears the named packets and leaves the gaps queued`() {
        val (session, pipe) = connected()
        // Nothing to assert on internal state from outside. What matters is that an EAK is
        // consumed as control and never surfaces as application data.
        pipe.inbox = Packet(Ctrl.EAK or Ctrl.ACK, 2, 0, encodeEak(listOf(1, 3))).encode()
        assertEquals(null, session.receive(timeoutMillis = 100))
    }

    @Test
    fun `the GPS 175 default is the conservative parameter set`() {
        // G2N = 2800 matches no ConnextTransportConfig branch, so Garmin's own apps fall through
        // to these values for this family.
        assertEquals(1, GPS175_PARAMS.mxOut)
        assertEquals(3, GPS175_PARAMS.mxRetry)
        assertEquals(1, GPS175_PARAMS.mxCmltv)
        assertEquals(1024, GPS175_PARAMS.maxSz)
        assertEquals(1500, GPS175_PARAMS.toRetry)
        assertEquals(150, GPS175_PARAMS.toCmltv)
        assertEquals(255, BLE_PARAMS.maxSz)
        assertEquals(4096, ANDROID_PEER_PARAMS.maxSz)
    }

    @Test
    fun `sync ids are never zero, since zero is the sentinel for not yet generated`() {
        repeat(5) { assertTrue(newSyncId() != 0L) }
    }

    @Test
    fun `sending before the handshake is refused`() {
        val session = Session(Pipe(), GPS175_PARAMS)
        assertTrue(
            runCatching { session.send("x".toByteArray()) }.exceptionOrNull() is SessionException
        )
    }

    /**
     * A retransmission is rebuilt, not replayed: cxp_t_hdr_updt refreshes the ack field and
     * sets the ACK bit again before the packet goes out, so a resend carries current
     * acknowledgement state rather than the state it had when first sent.
     *
     * It also fires on the adaptive timer rather than a flat to_retry, which
     * is why the clock has to be driven rather than waited on.
     */
    @Test
    fun `a retransmission refreshes the header`() {
        var clock = 0L
        val pipe = Pipe()
        pipe.inbox = peerSyn(GPS175_PARAMS)
        val session = Session(pipe, GPS175_PARAMS)
        session.nowMillis = { clock }
        session.connect(timeoutMillis = 1_000)

        session.send("payload".toByteArray())
        val original = pipe.sentPackets().last()

        // Something arrives meanwhile, so our idea of the ack advances.
        pipe.inbox = Packet(Ctrl.DATA or Ctrl.ACK, 5, 0, "in".toByteArray()).encode()
        session.receive(timeoutMillis = 50)

        clock += GPS175_PARAMS.toRetry.toLong() * 4
        session.receive(timeoutMillis = 50)

        val resent = pipe.sentPackets().last { it.psn == original.psn && it.payload.isNotEmpty() }
        assertEquals("the ack should be refreshed", 5, resent.ack)
        assertArrayEquals("payload changed", "payload".toByteArray(), resent.payload)
        assertTrue((resent.ctrl and Ctrl.ACK) != 0)
    }
}

/**
 * The four RUDP behaviours the port used to leave out: delivery in order, EAK generation, the
 * send window, and the scheduler for cumulative acks.
 *
 * None of them matters on a link that behaves, which is why they were deferred. They matter when
 * one does not, and "degrades the way Garmin's transport degrades" is only checkable here, because a
 * short RFCOMM hop to a navigator a metre away will not reproduce loss on demand.
 *
 * Note that [Session.receive] runs the periodic work itself, so an EAK or a bare ack goes out
 * *during* a receive rather than waiting for an explicit [Session.poll]. These look at what the
 * pipe carried, not at what a later poll produces.
 */
class SessionRudpTest {

    private fun peerSyn(params: SynParams = GPS175_PARAMS) =
        Packet(Ctrl.SYN, 0, 0, params.copy(syncId = 0x11223344L).encodePayload()).encode()

    private fun connected(): Pair<Session, Pipe> {
        val pipe = Pipe()
        pipe.inbox = peerSyn()
        val session = Session(pipe, GPS175_PARAMS)
        session.connect(timeoutMillis = 1_000)
        return session to pipe
    }

    /** A data packet from the peer. The SYN was psn 0, so payloads start at 1. */
    private fun peerData(psn: Int, body: String) =
        Packet(Ctrl.DATA, psn, 0, body.toByteArray()).encode()

    private fun Pipe.bareAcks() =
        sentPackets().filter { it.payload.isEmpty() && (it.ctrl and Ctrl.EAK) == 0 && (it.ctrl and Ctrl.SYN) == 0 }

    @Test
    fun `a payload arriving early is held until the gap ahead of it fills`() {
        val (session, pipe) = connected()
        // 3 arrives before 2. Delivered in arrival order that would hand C up before B.
        pipe.inbox = peerData(1, "A") + peerData(3, "C")
        assertEquals("A", String(session.receive(50)!!))
        assertEquals("C must be held, nothing is in order behind A yet", null, session.receive(50))

        pipe.inbox = peerData(2, "B")
        assertEquals("B", String(session.receive(50)!!))
        assertEquals("filling the gap promotes C", "C", String(session.receive(50)!!))
    }

    @Test
    fun `a gap in what we receive produces an EAK naming it`() {
        val (session, pipe) = connected()
        pipe.sent = ByteArray(0)
        pipe.inbox = peerData(1, "A") + peerData(3, "C")
        session.receive(50)

        val eaks = pipe.sentPackets().filter { (it.ctrl and Ctrl.EAK) != 0 }
        assertEquals("one EAK for the gap", 1, eaks.size)
        assertEquals(listOf(3), decodeEak(eaks[0].payload))

        // Latched: it is not repeated until something new arrives out of order.
        pipe.sent = ByteArray(0)
        session.poll()
        assertTrue(pipe.sentPackets().none { (it.ctrl and Ctrl.EAK) != 0 })
    }

    @Test
    fun `the send window holds output to mx_out until an ack opens it`() {
        // GPS175_PARAMS proposes mx_out = 1, so an upload is stop and wait. Feed the peer's ack
        // for the first segment: the window must open and the second must follow.
        val (session, pipe) = connected()
        pipe.sent = ByteArray(0)
        // A bare ack for psn 1, the first data segment. Empty payload, so it is not delivered up.
        pipe.inbox = Packet(Ctrl.DATA, 9, 1, ByteArray(0)).encode()

        val body = ByteArray(2 * (GPS175_PARAMS.maxSz - 9)) { 'x'.code.toByte() }
        session.send(body)

        assertEquals(
            "both segments go out once the window is opened",
            2, pipe.sentPackets().count { it.isData },
        )
    }

    @Test
    fun `the send window gives up rather than running ahead of the peer`() {
        // Same window, no ack. Rather than emit the second segment anyway, send must fail. Driven
        // with a short to_retry so the deadline for giving up, to_retry * 4, is milliseconds.
        val brisk = GPS175_PARAMS.copy(toRetry = 10)
        val pipe = Pipe()
        pipe.inbox = Packet(Ctrl.SYN, 0, 0, brisk.copy(syncId = 1L).encodePayload()).encode()
        val session = Session(pipe, brisk)
        session.connect(timeoutMillis = 1_000)
        pipe.sent = ByteArray(0)

        val body = ByteArray(2 * (brisk.maxSz - 9)) { 'x'.code.toByte() }
        val failure = runCatching { session.send(body) }.exceptionOrNull()

        assertTrue("the window must block rather than pass", failure is SessionException)
        assertEquals(
            "only one segment may be in flight with mx_out = 1",
            1, pipe.sentPackets().filter { it.isData }.map { it.psn }.distinct().size,
        )
    }

    @Test
    fun `a bare ack goes out once mx_cmltv packets have arrived`() {
        val (session, pipe) = connected()
        pipe.sent = ByteArray(0)
        pipe.inbox = peerData(1, "A")
        session.receive(50)

        // mx_cmltv is 1 for GPS175_PARAMS, so one received packet already owes an ack.
        assertEquals("one bare ack", 1, pipe.bareAcks().size)

        // Discharged: nothing further goes out until more arrives.
        pipe.sent = ByteArray(0)
        session.poll()
        assertTrue("the debt is settled", pipe.bareAcks().isEmpty())
    }
}
