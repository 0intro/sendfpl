package app.sendfpl.cxp

/**
 * CXP session layer: the RUDP state machine over a byte link, after Garmin's `cxp_t_pkt_mngr`.
 * Sequence numbers are one byte and compare modulo 256.
 *
 * SYN negotiation, sequencing, in-order delivery, the send window and [Session.flush] have run
 * against a real GPS 175, with routes up to 1300 bytes. Loss recovery has not: no EAK,
 * retransmission or out of order arrival has been seen on a real link, and those paths rest on the
 * decompiled code alone.
 */

/**
 * What to propose to a GPS 175, GNC 355 or GNX 375. Garmin Pilot's `ConnextTransportConfig` picks
 * its proposal by the peer's product id, and theirs, 2800 (`CONNEXT_PRODUCT_ID_G2N`), matches no
 * branch, so Garmin proposes this conservative default too.
 */
val GPS175_PARAMS = SynParams(
    mxOut = 1, mxRetry = 3, mxCmltv = 1, maxSz = 1024, toRetry = 1500, toCmltv = 150,
)

/** What `ConnextTransportConfig` proposes to another Android device, not to every peer. */
internal val ANDROID_PEER_PARAMS = SynParams(
    mxOut = 16, mxRetry = 3, mxCmltv = 8, maxSz = 4096, toRetry = 1500, toCmltv = 150,
)

/**
 * The negotiated block before the peer's SYN arrives, as `cxp_t_sync_pwrp` leaves it in both client
 * builds. It is what retransmits a lost SYN: up to ten times, at most a second apart.
 */
private val POWER_UP = SynParams(
    mxOut = 1, mxRetry = 10, mxCmltv = 1, maxSz = 128, toRetry = 1000, toCmltv = 1000,
)

/**
 * A session that could not go on. [reason] names the failures a pilot can act on, so that the app
 * can word them in the pilot's language; the message is English and is for the log.
 */
class SessionException(message: String, val reason: Reason? = null) : Exception(message) {
    enum class Reason { NO_REPLY, UPLOAD_REFUSED, CREDENTIAL_REFUSED }
}

/**
 * An id for one incarnation of a session, sampled as the SYN builder samples it,
 * `if (state->local_sync_id == 0) state->local_sync_id = cxp_ms_timer();`, once per session. It is
 * what `syn_init()` logs as `timestamp %u`. Zero means not yet generated.
 */
internal fun newSyncId(): Long {
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

/** Structured events for the protocol log. */
sealed interface CxpEvent {
    data class Sent(val packet: Packet) : CxpEvent
    data class Received(val packet: Packet) : CxpEvent
    data class Resent(val packet: Packet, val attempt: Int) : CxpEvent
    data class Note(val text: String) : CxpEvent
}

/**
 * A packet awaiting acknowledgement, the element `cxp_t_pkt_mngr_lock_elem` hands out. [packet]
 * is rewritten on resend, since `cxp_t_hdr_updt` refreshes its ack field.
 */
private class Outstanding(var packet: Packet, var retries: Int = 0, var needsResend: Boolean = false)

/** Monotonic milliseconds, which every wait deadline reads; see [Session.nowMillis]. */
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

    /**
     * The next sequence number to send, and the last one the peer acknowledged, started as
     * `cxp_t_pkt_mngr_pwrp` starts them: our SYN is psn 1, and the last acknowledged is 0, the ack
     * a navigator's SYN carries before it has seen ours.
     */
    private var psn = 1
    private var ackBase = 0

    private var rx = ByteArray(0)
    private val pending = ArrayDeque<ByteArray>()
    private val outstanding = LinkedHashMap<Int, Outstanding>()
    private var synSent = false

    /** The last sequence number delivered in order, which is what the ack field carries. */
    private var delivered = 0

    /** Packets that arrived ahead of their turn, in arrival order, which is what an EAK lists. */
    private val held = LinkedHashMap<Int, ByteArray>()
    private var eakSent = false

    /** For the cumulative ack: packets received since our last transmission, and when that was. */
    private var recvSinceAck = 0
    private var lastTx = 0L

    /** Set when the session can no longer continue; every later call throws it again. */
    private var failure: SessionException? = null

    /**
     * The clock the timers read, which a test may replace. Wait deadlines read [elapsedMillis]
     * instead: an injected clock does not advance on its own, so a wait on it would never end.
     */
    internal var nowMillis: () -> Long = { elapsedMillis() }
    internal val rto = Rto({ nowMillis() }, POWER_UP.toRetry)

    /**
     * Build and send one packet. The ACK bit is set on every packet, as `cxp_t_hdr_bld` sets it
     * (`header[4] = ctrl | 2`), so the opening SYN goes out as `0x03`. Only a tracked packet takes
     * a sequence number: `next_psn` moves only in `cxp_t_pkt_mngr_enqueue_out`, and a bare ack or
     * an EAK that took one would leave a hole the peer never sees filled.
     */
    private fun emit(ctrl: Int, payload: ByteArray = ByteArray(0), track: Boolean = true): Packet {
        val packet = Packet(ctrl or Ctrl.ACK, psn, delivered, payload)
        link.send(packet.encode())
        onEvent(CxpEvent.Sent(packet))
        // Any transmission carries the ack, so it restarts the cumulative ack count and clock.
        recvSinceAck = 0
        lastTx = nowMillis()
        if (!track) return packet

        psn = (psn + 1) and 0xFF
        outstanding[packet.psn] = Outstanding(packet)
        // No RTT sample for a SYN: enqueue_out guards rtt_set on `(ctrl & 1) == 0`.
        if ((ctrl and Ctrl.SYN) == 0) rto.rttSet(packet.psn)
        rto.timerArm(packet.psn)
        return packet
    }

    private fun drain(timeoutMillis: Long) {
        failure?.let { throw it }
        val data = link.receive(timeoutMillis)
        if (data.isEmpty()) return
        rx += data
        val scan = iterPackets(rx)
        rx = scan.remainder
        for (packet in scan.packets) onPacket(packet)
        failure?.let { throw it }
    }

    /**
     * `cxp_t_pkt_mngr_proc`. Nothing but the peer's SYN counts until both SYNs have gone, and an
     * acknowledgement counts only with the ACK bit set and only within the send window.
     */
    private fun onPacket(packet: Packet) {
        onEvent(CxpEvent.Received(packet))
        if ((packet.ctrl and Ctrl.SYN) != 0) onSyn(packet)
        if (!synSent || negotiated == null) return

        if ((packet.ctrl and Ctrl.ACK) != 0 && acks(packet.ack)) {
            while (ackBase != packet.ack) {
                ackBase = (ackBase + 1) and 0xFF
                if (outstanding.remove(ackBase) != null) {
                    rto.rttUpdate(ackBase)
                    rto.timerReset(ackBase, psn)
                }
            }
            if ((packet.ctrl and Ctrl.EAK) != 0) onEak(packet)
            val oldest = outstanding.keys.firstOrNull()
            if (oldest == null) rto.timerStop() else rto.timerFollow(oldest)
        }
        if (!packet.isData) return

        // Data is taken only within the receive window: the next psn due, up to our mx_out past it.
        // Anything else repeats what was delivered: the C drops it silently, but counting it here
        // acknowledges it again, so a peer whose acknowledgement was lost stops resending.
        recvSinceAck += 1
        val d = (packet.psn - delivered - 1) and 0xFF
        if (d > local.mxOut) return
        eakSent = false
        if (d > 0) {
            held.putIfAbsent(packet.psn, packet.payload)
            return
        }
        delivered = packet.psn
        pending.addLast(packet.payload)
        promote()
    }

    /**
     * Take the peer's SYN. Only the first one negotiates, as in `cxp_t_sync_proc`, and a later one
     * with a new sync id means the peer restarted, which ends the session. A SYN too short to
     * decode is ignored.
     *
     * Two choices are ours. Every SYN counts toward the next ack, so a repeated one is
     * acknowledged, where Garmin counts only the first. And a repeated version-1 SYN, which has no
     * sync id, is not a restart, where `cxp_t_sync_check` takes it for one.
     */
    private fun onSyn(packet: Packet) {
        val r = runCatching { SynParams.decode(packet.payload) }.getOrNull() ?: return
        recvSinceAck += 1
        if (negotiated != null) {
            if (r.syncId != remote?.syncId) {
                failure = SessionException("the navigator restarted the session")
            }
            return
        }
        remote = r
        val n = local.negotiate(r)
        negotiated = n
        rto.toRetry = n.toRetry
        // The first ack names the SYN rather than a packet never seen: `*(param_1 + 0x91) =
        // *(iVar2 + 5)` when the session comes up.
        delivered = packet.psn
        onEvent(CxpEvent.Note("SYN from peer: $r, negotiated $n"))
    }

    /** Whether [ack] lies in the send window, between the last acknowledged and the last sent. */
    private fun acks(ack: Int): Boolean = ((ack - ackBase) and 0xFF) < ((psn - ackBase) and 0xFF)

    /**
     * Release each packet the EAK lists, which arrived, and mark for resend those between the ack
     * and the last one listed, which did not.
     */
    private fun onEak(packet: Packet) {
        val listed = decodeEak(packet.payload)
        onEvent(CxpEvent.Note("EAK acknowledges $listed"))
        for (seq in listed) {
            if (outstanding.remove(seq) != null) rto.rttUpdate(seq)
        }
        val last = listed.lastOrNull() ?: return
        var seq = (packet.ack + 1) and 0xFF
        while (seq != last) {
            outstanding[seq]?.needsResend = true
            seq = (seq + 1) and 0xFF
        }
    }

    /** Deliver whatever held packets are now in order. */
    private fun promote() {
        while (true) {
            val payload = held.remove((delivered + 1) and 0xFF) ?: return
            delivered = (delivered + 1) and 0xFF
            pending.addLast(payload)
        }
    }

    /**
     * Retransmit what the adaptive timer says is due, as `cxp_t_pkt_mngr_prdc` does: the RTT
     * sample is abandoned first (Karn's algorithm, `cxp_t_rto_rtt_clear`), and the header is
     * rebuilt (`cxp_t_hdr_updt`), so a resent packet carries the current ack.
     */
    private fun retransmitDue() {
        failure?.let { throw it }
        val limit = (negotiated ?: POWER_UP).mxRetry
        var resent = false
        for ((seq, out) in outstanding.entries.toList()) {
            if (!out.needsResend) out.needsResend = rto.timerCheck(seq)
            if (!out.needsResend) continue
            if (limit < rto.retries) {
                // Garmin resets the transport here (`CXP_t_active(2)`) and starts over with a new
                // SYN; a one-shot upload ends instead.
                val e = SessionException("packet psn=$seq exceeded $limit retries", SessionException.Reason.NO_REPLY)
                failure = e
                throw e
            }
            rto.rttClear(seq)
            val refreshed = out.packet.copy(ctrl = out.packet.ctrl or Ctrl.ACK, ack = delivered)
            out.packet = refreshed
            link.send(refreshed.encode())
            out.needsResend = false
            out.retries += 1
            resent = true
            recvSinceAck = 0
            lastTx = nowMillis()
            onEvent(CxpEvent.Resent(refreshed, out.retries))
        }
        val n = negotiated ?: return

        // Selective acknowledgement of the held packets, once until more data arrives.
        if (!eakSent && held.isNotEmpty()) {
            emit(Ctrl.EAK, encodeEak(held.keys.toList()), track = false)
            eakSent = true
            return
        }

        // Cumulative acknowledgement: a bare ack once mx_cmltv packets have arrived since our last
        // transmission or to_cmltv milliseconds have passed, unless a resend just carried it.
        if (!resent && recvSinceAck != 0) {
            val due = lastTx + n.toCmltv <= nowMillis() || n.mxCmltv <= recvSinceAck
            if (due) emit(Ctrl.DATA, track = false)
        }
    }

    /** Sequence numbers sent past the cumulative ack, which is what `window_check` counts. */
    private val unacked: Int get() = (psn - ackBase - 1) and 0xFF

    /**
     * Block until the send window has room. Like `window_check`, it counts every psn past the
     * cumulative ack, so a packet an EAK released counts until the ack covers it. Garmin's
     * congestion window, which opens a packet at a time up to mx_out, is not modelled.
     * [GPS175_PARAMS] proposes 1 and [SynParams.negotiate] keeps it, so an upload to a GPS 175 is
     * stop and wait.
     *
     * A peer that stops acknowledging trips the retry limit; the deadline, a timeout later, only
     * bounds one that releases packets by EAK alone.
     */
    private fun awaitWindow() {
        val n = negotiated ?: return
        if (n.mxOut == 0) return
        val deadline = elapsedMillis() + n.toRetry.toLong() * (n.mxRetry + 2)
        while (unacked >= n.mxOut) {
            if (elapsedMillis() > deadline) {
                throw SessionException(
                    "send window still full with $unacked unacknowledged packets",
                    SessionException.Reason.NO_REPLY,
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
        synSent = true
        val deadline = elapsedMillis() + timeoutMillis
        while (elapsedMillis() < deadline) {
            drain(200)
            retransmitDue()
            negotiated?.let { return it }
        }
        throw SessionException("no SYN from peer within ${timeoutMillis}ms", SessionException.Reason.NO_REPLY)
    }

    /**
     * Send an application payload, segmenting to the negotiated size. An empty payload is refused:
     * a packet of no more than its header is never delivered.
     */
    fun send(payload: ByteArray) {
        failure?.let { throw it }
        require(payload.isNotEmpty()) { "an empty payload is never delivered" }
        val n = negotiated ?: throw SessionException("session not connected")
        // A packet costs 8 header bytes plus one byte of payload checksum.
        val limit = maxOf(n.maxSz - 9, 1)
        var at = 0
        while (at < payload.size) {
            awaitWindow()
            val end = minOf(at + limit, payload.size)
            emit(Ctrl.DATA, payload.copyOfRange(at, end))
            at = end
        }
    }

    /** Send a bare acknowledgement now, `cxp_t_hdr_bld(0, 0, …)`, off the cumulative schedule. */
    fun sendBareAck() {
        emit(Ctrl.DATA, ByteArray(0), track = false)
    }

    /**
     * Run the periodic work of `cxp_t_pkt_mngr_prdc` once, as every wait here does on each pass:
     * retransmit what is due, then send an EAK or a cumulative ack if one is due.
     */
    fun poll() = retransmitDue()

    /** Wait for the next application payload, or null on timeout. */
    fun receive(timeoutMillis: Long = 5_000): ByteArray? {
        val deadline = elapsedMillis() + timeoutMillis
        while (true) {
            failure?.let { throw it }
            pending.removeFirstOrNull()?.let { return it }
            if (elapsedMillis() >= deadline) return null
            drain(200)
            retransmitDue()
        }
    }

    /**
     * Wait until everything sent has been acknowledged, or throw. [send] waits for window room only
     * before a packet, so nothing else waits for the last one: closing before this can lose it.
     */
    fun flush(timeoutMillis: Long = 10_000) {
        failure?.let { throw it }
        val deadline = elapsedMillis() + timeoutMillis
        while (outstanding.isNotEmpty()) {
            if (elapsedMillis() > deadline) {
                throw SessionException(
                    "${outstanding.size} packet(s) still unacknowledged. " +
                        "The navigator did not confirm receipt",
                    SessionException.Reason.NO_REPLY,
                )
            }
            drain(50)
            retransmitDue()
        }
    }

    fun close() = link.close()
}
