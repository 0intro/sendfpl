package app.sendfpl.cxp

/**
 * CXP session layer: the RUDP state machine over a byte link.
 *
 * Handles SYN negotiation, sequence numbers, cumulative acknowledgement, EAK consumption,
 * retransmission on an adaptive timer, and segmentation.
 *
 * `ConnextTransportConfig` picks parameters by the **peer's** product id, looked up from the
 * connection table by Bluetooth address, and the result becomes *our* SYN proposal. Garmin tunes
 * what it offers to what the far end can cope with, which works because each side then clamps the
 * other's block down to its own limits.
 *
 * ## Completeness
 *
 * This is now the full RUDP: EAK generation from the set of packets that arrived out of order, the
 * `mx_out` send window, delivery in order with early payloads held until the gap ahead of them
 * fills, and the `mx_cmltv`/`to_cmltv` scheduler for cumulative acks. Comparisons of sequence
 * numbers are modulo 256 throughout, since the field is one byte.
 *
 * None of it is needed to upload one flight plan over a link that behaves, and a short RFCOMM hop
 * to a navigator a metre away rarely drops anything. It is here so that a link which *does* drop
 * something degrades the way Garmin's own transport degrades rather than stalling.
 *
 * ## What hardware has and has not exercised
 *
 * SYN negotiation, sequencing, in-order delivery, the send window and [flush] have all run
 * against a real GPS 175, over routes up to 1300 bytes.
 *
 * **The loss recovery has not.** No EAK, no retransmission and no out of order arrival has ever
 * been observed on a real link, because none has ever been provoked. Those paths rest on the
 * decompiled code alone, and a bench session that drops packets deliberately is what would settle
 * them.
 */

/**
 * What to propose to a GPS 175 / GNC 355 / GNX 375.
 *
 * Their shared product id is `CONNEXT_PRODUCT_ID_G2N = 2800`, named "GNX 375/GPS 175/GNC 355" in
 * Garmin Pilot. 2800 matches **no branch** of `ConnextTransportConfig`, so even Garmin's flagship
 * app falls through to the conservative default for these units, which makes it the right thing
 * for an independent client to send too.
 */
val GPS175_PARAMS = SynParams(
    mxOut = 1, mxRetry = 3, mxCmltv = 1, maxSz = 1024, toRetry = 1500, toCmltv = 150,
)

/** Used when the peer is another Android device, not what an Android client proposes generally. */
val ANDROID_PEER_PARAMS = SynParams(
    mxOut = 16, mxRetry = 3, mxCmltv = 8, maxSz = 4096, toRetry = 1500, toCmltv = 150,
)

/** Built inline for a BLE link rather than looked up by product. */
val BLE_PARAMS = SynParams(
    mxOut = 4, mxRetry = 2, mxCmltv = 1, maxSz = 255, toRetry = 10000, toCmltv = 1000,
)

class SessionException(message: String) : Exception(message)

/**
 * An id for one incarnation of a session, sampled the way the firmware samples it.
 *
 * The SYN builder does `if (state->local_sync_id == 0) state->local_sync_id = cxp_ms_timer();`:
 * one millisecond reading taken at the first SYN and reused for the life of the session. This is
 * the value `syn_init()` logs as `timestamp %u`. It is not a separate field. A peer that sees a
 * different id concludes we restarted. Zero is the "not yet generated" sentinel.
 */
fun newSyncId(): Long {
    val ms = elapsedMillis() and 0xFFFFFFFFL
    return if (ms == 0L) 1L else ms
}

/** Minimal byte link a [Session] drives. Implemented by the RFCOMM socket and by test loopbacks. */
interface Link {
    fun send(data: ByteArray)
    /** Whatever bytes are available, or empty on timeout. */
    fun receive(timeoutMillis: Long): ByteArray
    fun close() {}
}

/** Structured events for the protocol log, which turn "it didn't work" into a bug report. */
sealed interface CxpEvent {
    data class Sent(val packet: Packet) : CxpEvent
    data class Received(val packet: Packet) : CxpEvent
    data class Resent(val packet: Packet, val attempt: Int) : CxpEvent
    data class Note(val text: String) : CxpEvent
}

/**
 * One unacknowledged packet awaiting acknowledgement, the element
 * `cxp_t_pkt_mngr_lock_elem` hands out. [packet] is rewritten on resend, so it is not `val`:
 * `cxp_t_hdr_updt` refreshes the ack field rather than sending the original bytes again.
 */
private class Outstanding(var packet: Packet, var retries: Int = 0, var needsResend: Boolean = false)

/** Comparison of sequence numbers, tolerant of the wrap at 8 bits. */
private fun seqLe(a: Int, b: Int): Boolean = ((b - a) and 0xFF) < 0x80

/**
 * Monotonic milliseconds, which is what every wait deadline in this file is measured against.
 *
 * Monotonic rather than wall clock, so a timeout cannot be lengthened or cut short by the phone
 * adjusting its time mid transfer, and never injected, for the reason on [Session.nowMillis].
 */
private fun elapsedMillis(): Long = System.nanoTime() / 1_000_000L

class Session(
    private val link: Link,
    local: SynParams = GPS175_PARAMS,
    private val onEvent: (CxpEvent) -> Unit = {},
) {
    var local: SynParams = local
        private set
    var remote: SynParams? = null
        private set
    var negotiated: SynParams? = null
        private set

    private var psn = 0
    private var peerPsn: Int? = null
    private var rx = ByteArray(0)
    private val pending = ArrayDeque<ByteArray>()
    private val outstanding = LinkedHashMap<Int, Outstanding>()
    private var connected = false

    /**
     * The highest sequence number delivered to the application, and whether anything has been.
     *
     * Seeded from the SYN's own sequence number rather than left at zero: `cxp_t_pkt_mngr_proc`
     * does `*(undefined1 *)(param_1 + 0x91) = *(undefined1 *)(iVar2 + 5)` when the session comes
     * up, so the first ack refers to the SYN instead of claiming a packet that was never seen.
     */
    private var delivered = 0
    private var haveRx = false

    /** Payloads that arrived ahead of their turn, held until the gap ahead of them fills. */
    private val held = LinkedHashMap<Int, ByteArray>()

    /** Sequence numbers received out of order, which is exactly what an EAK lists. */
    private val ooo = ArrayList<Int>()
    private var eakSent = false

    /** State for the cumulative ack: packets received since our last transmission, and when. */
    private var recvSinceAck = 0
    private var lastTx = 0L

    /**
     * The clock the **timers** read: the retransmission timeout, whose ceiling is `to_retry`
     * rather than its period, and the schedule for cumulative acks.
     *
     * Overridable so a test can drive those comparisons exactly instead of sleeping through them.
     *
     * **A wait deadline must not read this**, and [elapsedMillis] is what they read instead. An
     * injected clock is a value a test sets, so it does not advance on its own: a loop that waits
     * for one to pass, against a link that returns immediately, never ends. The distinction is
     * invisible in production, where both are the same monotonic millisecond count.
     */
    internal var nowMillis: () -> Long = { elapsedMillis() }
    internal val rto = Rto({ nowMillis() }, local.toRetry)

    private fun ackValue(): Int = peerPsn ?: 0

    /**
     * Build and send one packet.
     *
     * The ACK bit is set here, on **every** packet, because that is what Garmin's header builder
     * does, as `header[4] = ctrl | 2` in `cxp_t_hdr_bld`, and again in `cxp_t_hdr_updt` on the
     * retransmit path. So an opening SYN goes out as `0x03` and not as `0x01`, before anything
     * has been received for the ack field to describe.
     *
     * The sequence number advances **only for tracked packets**. `next_psn` moves in exactly one
     * place in the C, inside `cxp_t_pkt_mngr_enqueue_out`, and only once the packet is queued
     * for retransmission, so a bare ack or an EAK reuses the current number without consuming
     * it. Incrementing for those leaves a permanent hole that a peer delivering in order never
     * recovers from, because nothing will ever arrive to fill a number that was never sent.
     */
    private fun emit(ctrl: Int, payload: ByteArray = ByteArray(0), track: Boolean = true): Packet {
        val packet = Packet(ctrl or Ctrl.ACK, psn, ackValue(), payload)
        link.send(packet.encode())
        onEvent(CxpEvent.Sent(packet))
        // Any transmission carries the current ack, so it discharges the debt of the cumulative
        // ack and restarts the to_cmltv clock, which is why the scheduler below only fires when
        // nothing else has gone out.
        recvSinceAck = 0
        lastTx = nowMillis()
        if (!track) return packet

        psn = (psn + 1) and 0xFF
        outstanding[packet.psn] = Outstanding(packet)
        // A SYN does not start an RTT sample: enqueue_out guards rtt_set on `(ctrl & 1) == 0`.
        // The retransmit timer is armed either way.
        if ((ctrl and Ctrl.SYN) == 0) rto.rttSet(packet.psn)
        rto.timerArm(packet.psn)
        return packet
    }

    private fun drain(timeoutMillis: Long) {
        val data = link.receive(timeoutMillis)
        if (data.isEmpty()) return
        rx += data
        val scan = iterPackets(rx)
        rx = scan.remainder
        for (packet in scan.packets) onPacket(packet)
    }

    private fun onPacket(packet: Packet) {
        onEvent(CxpEvent.Received(packet))
        peerPsn = packet.psn
        // Cumulative ack: everything up to and including packet.ack is done. Releasing a packet
        // is also what feeds the RTO estimator, the round trip now being measured.
        outstanding.keys.filter { seqLe(it, packet.ack) }.forEach { seq ->
            outstanding.remove(seq)
            rto.rttUpdate(seq)
            rto.timerReset(seq, psn)
        }

        if ((packet.ctrl and Ctrl.EAK) != 0) {
            // Selectively acknowledged: those arrived. Anything still outstanding below the
            // highest acknowledged number is a gap and will time out into a resend.
            val acked = decodeEak(packet.payload)
            onEvent(CxpEvent.Note("EAK acknowledges $acked"))
            acked.forEach {
                outstanding.remove(it)
                rto.rttUpdate(it)
            }
            return
        }
        if ((packet.ctrl and Ctrl.SYN) != 0) {
            val r = SynParams.decode(packet.payload)
            remote = r
            negotiated = local.negotiate(r)
            rto.toRetry = negotiated!!.toRetry
            connected = true
            delivered = packet.psn
            haveRx = true
            recvSinceAck += 1
            onEvent(CxpEvent.Note("SYN from peer: $r, negotiated ${negotiated}"))
            return
        }
        if (!packet.isData) return

        // A fresh payload invalidates any EAK already sent: the gap it lists may have moved.
        eakSent = false
        recvSinceAck += 1

        if (!haveRx || packet.psn == ((delivered + 1) and 0xFF)) {
            delivered = packet.psn
            haveRx = true
            pending.addLast(packet.payload)
            promote()
        } else {
            remember(packet)
        }
    }

    /**
     * Queue a packet that arrived ahead of its turn: its sequence number for the next EAK, and its
     * payload for delivery once the gap ahead of it is filled.
     */
    private fun remember(packet: Packet) {
        if (packet.psn in ooo) return
        ooo += packet.psn
        held[packet.psn] = packet.payload
    }

    /** Deliver anything held that has become in order, which may cascade. */
    private fun promote() {
        while (true) {
            val next = (delivered + 1) and 0xFF
            val payload = held.remove(next) ?: return
            pending.addLast(payload)
            delivered = next
            ooo.remove(next)
        }
    }

    /**
     * Retransmit whatever is due, on the adaptive timer rather than a flat `to_retry`, which was
     * an early misreading of what `to_retry` meant.
     *
     * Two details from `cxp_t_pkt_mngr_prdc` that are easy to drop: the sample is abandoned
     * before resending (Karn's algorithm, `cxp_t_rto_rtt_clear`), and the header is rebuilt
     * rather than replayed (`cxp_t_hdr_updt`), so a resent packet carries current acknowledgement
     * state instead of the state it had when first sent.
     */
    private fun retransmitDue() {
        val n = negotiated ?: return
        var resent = false
        for ((seq, out) in outstanding.entries.toList()) {
            if (!out.needsResend) out.needsResend = rto.timerCheck(seq)
            if (!out.needsResend) continue
            if (n.mxRetry < rto.retries) {
                outstanding.remove(seq)
                throw SessionException("packet psn=$seq exceeded ${n.mxRetry} retries")
            }
            rto.rttClear(seq)
            val refreshed = out.packet.copy(ctrl = out.packet.ctrl or Ctrl.ACK, ack = ackValue())
            out.packet = refreshed
            link.send(refreshed.encode())
            out.needsResend = false
            out.retries += 1
            resent = true
            recvSinceAck = 0
            lastTx = nowMillis()
            onEvent(CxpEvent.Resent(refreshed, out.retries))
        }
        if (!connected) return

        // Selective acknowledgement. One u8 per sequence number received out of order, which is
        // what makes the receiver's `total_len - 9` count come out right.
        if (!eakSent && ooo.isNotEmpty()) {
            emit(Ctrl.EAK, encodeEak(ooo.toList()), track = false)
            eakSent = true
            return
        }

        // Cumulative acknowledgement. A bare ack goes out once either mx_cmltv packets have
        // arrived since our last transmission or to_cmltv milliseconds have passed, and only when
        // nothing else went out, since any transmission already carried the ack.
        if (!resent && recvSinceAck != 0) {
            val due = lastTx + n.toCmltv <= nowMillis() || n.mxCmltv <= recvSinceAck
            if (due) emit(Ctrl.DATA, track = false)
        }
    }

    /**
     * Block until the send window has room, which is what `mx_out` bounds.
     *
     * Garmin's sender does not run ahead of the negotiated window. With `GPS175Params` proposing
     * `mx_out = 1` this makes the upload strictly stop and wait, which is what a navigator
     * advertising that number is asking for.
     */
    private fun awaitWindow() {
        val n = negotiated ?: return
        if (n.mxOut == 0) return
        val deadline = elapsedMillis() + n.toRetry.toLong() * 4
        while (outstanding.size >= n.mxOut) {
            if (elapsedMillis() > deadline) {
                throw SessionException(
                    "send window still full after ${outstanding.size} outstanding packets"
                )
            }
            drain(50)
            retransmitDue()
        }
    }

    /** Exchange SYN and negotiate parameters. */
    fun connect(timeoutMillis: Long = 10_000): SynParams {
        if (local.syncId == null) local = local.copy(syncId = newSyncId())
        emit(Ctrl.SYN, local.encodePayload())
        val deadline = elapsedMillis() + timeoutMillis
        while (elapsedMillis() < deadline) {
            drain(200)
            retransmitDue()
            negotiated?.let { if (connected) return it }
        }
        throw SessionException("no SYN from peer within ${timeoutMillis}ms")
    }

    /** Send an application payload, segmenting to the negotiated size. */
    fun send(payload: ByteArray) {
        val n = negotiated ?: throw SessionException("session not connected")
        // A packet costs 8 header bytes plus one byte of payload checksum.
        val limit = maxOf(n.maxSz - 9, 1)
        var at = 0
        do {
            awaitWindow()
            val end = minOf(at + limit, payload.size)
            emit(Ctrl.DATA, payload.copyOfRange(at, end))
            at = end
        } while (at < payload.size)
    }

    /**
     * Send a bare acknowledgement: `cxp_t_hdr_bld(0, 0, …)`, a packet of 8 bytes with no payload.
     *
     * It is not tracked and not retransmitted, and it does **not** consume a sequence number: the
     * ack field and the ACK bit ride on it, which is all it is for.
     *
     * The C emits these from `cxp_t_pkt_mngr_prdc` on its own schedule, once either `mx_cmltv`
     * packets have arrived or `to_cmltv` milliseconds have passed. [retransmitDue] now runs that
     * schedule, so this is the manual override rather than the only way one goes out.
     */
    fun sendBareAck() {
        emit(Ctrl.DATA, ByteArray(0), track = false)
    }

    /**
     * Run the periodic work once: retransmit what is due, emit an EAK if anything is missing, and
     * send a bare ack if the policy for cumulative acks calls for one.
     *
     * This is `cxp_t_pkt_mngr_prdc`. [receive] drives it on every pass, and it is public so a
     * caller holding a session open without reading can still keep its acknowledgements current,
     * and so a test can step the state machine without a clock.
     */
    fun poll() = retransmitDue()

    /** Wait for the next application payload, or null on timeout. */
    fun receive(timeoutMillis: Long = 5_000): ByteArray? {
        val deadline = elapsedMillis() + timeoutMillis
        while (true) {
            pending.removeFirstOrNull()?.let { return it }
            if (elapsedMillis() >= deadline) return null
            drain(200)
            retransmitDue()
        }
    }

    /**
     * Wait until everything sent has been acknowledged, retransmitting on the usual schedule.
     *
     * Closing with data still in flight throws it away. [send] returns as soon as a packet has been
     * written, and [awaitWindow] is consulted only *before* the next one, so the last packet of a
     * transfer is never waited on by anything, there being no next one. Observed on a GPS 175: the
     * flight plan was written to the socket, the socket closed microseconds later, and the
     * navigator never acknowledged it.
     *
     * Throws if the peer never catches up, which is a real failure and not something to close over
     * quietly.
     */
    fun flush(timeoutMillis: Long = 10_000) {
        val deadline = elapsedMillis() + timeoutMillis
        while (outstanding.isNotEmpty()) {
            if (elapsedMillis() > deadline) {
                throw SessionException(
                    "${outstanding.size} packet(s) still unacknowledged. " +
                        "The navigator did not confirm receipt"
                )
            }
            drain(50)
            retransmitDue()
        }
    }

    fun close() = link.close()
}
