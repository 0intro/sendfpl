package app.sendfpl.cxp

/**
 * CXP transport framing (Garmin Connext).
 *
 * An RUDP variant. Recovered by reverse engineering two independently compiled binaries that
 * agree with each other: `IOP_E.dll` (GPS 175 trainer 3.21.2) and `libDCI_CONNEXT.so`
 * (FltPlan Go 5.0.21, unstripped). No public specification for Connext exists.
 *
 * Wire format: a header of 8 bytes, an optional payload, and a payload checksum:
 * ```
 *   0        1        2        3        4        5        6        7
 * +--------+--------+--------+--------+--------+--------+--------+--------+
 * |  0xC0  |version |   total length  |  ctrl  |  psn   |  ack   | cksum  |
 * +--------+--------+--------+--------+--------+--------+--------+--------+
 * |                          payload (N bytes)                            |
 * +-----------------------------------------------------------------------+
 * | pcksum |
 * +--------+
 * ```
 * `total length` is `N + 9` when there is a payload and `8` when there is not, and **not** header
 * plus payload. That trailing checksum byte is the single most consequential detail in this file:
 * an implementation that writes `8 + N` produces packets the navigator discards as
 * *"invalid payload"*.
 *
 * A GPS 175 imports a flight plan framed this way, including a 1300 byte route split across two
 * packets, so the layout, both checksums and the length rule are measured rather than inferred.
 */

const val FRAME_START = 0xC0
const val VERSION = 1
const val HEADER_LEN = 8

/** `cxp_t_hdr_bld()` rejects more, capping a packet at 0xFFFF once header and checksum are added. */
const val MAX_PAYLOAD = 0xFFF6

/**
 * Header ctrl byte (offset 4), a bitmask.
 *
 * Confirmed from both the senders and the receiver:
 * * [SYN] `cxp_t_sync_prdc()` builds `ctrl=0x01`
 * * [ACK] `cxp_t_pkt_mngr_proc()` only processes the `ack` field when set
 * * [EAK] `cxp_t_pkt_mngr_prdc()` builds `ctrl=0x04` beside "Send EAKs:"
 *
 * Bit `0x08` is unused by anything examined, presumably RST or NUL, but unconfirmed, so it is
 * deliberately not named.
 */
object Ctrl {
    const val DATA = 0x00
    const val SYN = 0x01
    const val ACK = 0x02
    const val EAK = 0x04

    /** Mask the receiver uses to decide "this packet carries no user data". */
    const val NO_PAYLOAD = 0x05
}

/** The 8 bit checksum in two's complement form, `cxp_t_checksum`. */
fun checksum(buf: ByteArray, from: Int = 0, until: Int = buf.size): Int {
    var sum = 0
    for (i in from until until) sum += buf[i].toInt() and 0xFF
    return (-sum) and 0xFF
}

/**
 * Why a frame could not be decoded.
 *
 * The framer needs to tell three outcomes apart, and reading them off a value rather than off the
 * message is what keeps [iterPackets] independent of the wording. The reference implementation
 * draws the same distinctions as sentinel errors, one per entry here.
 */
enum class CxpError {
    SHORT_HEADER,
    NO_FRAME_START,
    BAD_VERSION,
    HEADER_CHECKSUM,
    BAD_LENGTH,
    TRUNCATED,
    PAYLOAD_CHECKSUM,
    PAYLOAD_TOO_LARGE,
    /** Not a framing fault: a caller asked for something the wire cannot carry. */
    INVALID_ARGUMENT,
}

/** A frame could not be decoded. */
class CxpException(val kind: CxpError, message: String) : Exception(message)

/** One decoded packet, plus how many bytes of the stream it consumed. */
data class Decoded<T>(val value: T, val consumed: Int)

data class Packet(
    val ctrl: Int,
    val psn: Int,
    val ack: Int,
    val payload: ByteArray = ByteArray(0),
) {
    fun encode(): ByteArray {
        if (payload.size > MAX_PAYLOAD) {
            throw CxpException(
                CxpError.PAYLOAD_TOO_LARGE, "payload ${payload.size} exceeds $MAX_PAYLOAD"
            )
        }
        val total = if (payload.isEmpty()) HEADER_LEN else HEADER_LEN + payload.size + 1
        val out = ByteArray(total)
        out[0] = FRAME_START.toByte()
        out[1] = VERSION.toByte()
        out[2] = (total and 0xFF).toByte()
        out[3] = ((total shr 8) and 0xFF).toByte()
        out[4] = (ctrl and 0xFF).toByte()
        out[5] = (psn and 0xFF).toByte()
        out[6] = (ack and 0xFF).toByte()
        out[7] = checksum(out, 0, 7).toByte()
        if (payload.isEmpty()) return out
        payload.copyInto(out, HEADER_LEN)
        out[total - 1] = checksum(payload).toByte()
        return out
    }

    /** True when this packet carries application data the session should deliver upward. */
    val isData: Boolean get() = (ctrl and Ctrl.NO_PAYLOAD) == 0 && payload.isNotEmpty()

    // ByteArray needs structural equality spelled out.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Packet) return false
        return ctrl == other.ctrl && psn == other.psn && ack == other.ack &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int =
        ((ctrl * 31 + psn) * 31 + ack) * 31 + payload.contentHashCode()

    companion object {
        /**
         * Decode one packet from the front of [data].
         *
         * Error messages mirror the distinctions `cxp_t_parse()` logs, so a caller can tell a
         * truncated read from a corrupt one.
         */
        fun decode(data: ByteArray, offset: Int = 0): Decoded<Packet> {
            val avail = data.size - offset
            if (avail < HEADER_LEN) throw CxpException(CxpError.SHORT_HEADER, "short header")
            val first = data[offset].toInt() and 0xFF
            if (first != FRAME_START) {
                throw CxpException(
                    CxpError.NO_FRAME_START, "no frame start (got 0x%02x)".format(first)
                )
            }
            val version = data[offset + 1].toInt() and 0xFF
            if (version != VERSION) {
                throw CxpException(CxpError.BAD_VERSION, "invalid version $version")
            }
            if (checksum(data, offset, offset + HEADER_LEN) != 0) {
                throw CxpException(CxpError.HEADER_CHECKSUM, "invalid header checksum")
            }
            val total = (data[offset + 2].toInt() and 0xFF) or ((data[offset + 3].toInt() and 0xFF) shl 8)
            if (total < HEADER_LEN) throw CxpException(CxpError.BAD_LENGTH, "invalid length $total")
            if (total == HEADER_LEN + 1) {
                throw CxpException(
                    CxpError.BAD_LENGTH, "invalid length: payload of zero with a checksum"
                )
            }
            if (avail < total) throw CxpException(CxpError.TRUNCATED, "truncated payload")

            val ctrl = data[offset + 4].toInt() and 0xFF
            val psn = data[offset + 5].toInt() and 0xFF
            val ack = data[offset + 6].toInt() and 0xFF
            if (total == HEADER_LEN) return Decoded(Packet(ctrl, psn, ack), total)

            // The payload region is the payload plus its checksum, and it must sum to zero.
            if (checksum(data, offset + HEADER_LEN, offset + total) != 0) {
                throw CxpException(CxpError.PAYLOAD_CHECKSUM, "invalid payload checksum")
            }
            val payload = data.copyOfRange(offset + HEADER_LEN, offset + total - 1)
            return Decoded(Packet(ctrl, psn, ack, payload), total)
        }
    }
}

/** Packets decoded from a stream, plus the trailing bytes that form an incomplete packet. */
data class Scan(val packets: List<Packet>, val remainder: ByteArray)

/**
 * Split a byte stream into packets, resynchronising on 0xC0.
 *
 * Mirrors `cxp_t_parse()`, which hunts forward for a frame start byte and drops whatever precedes
 * it. Frame acquisition is the *only* place 0xC0 is meaningful: once a header validates, the
 * framer is driven by length and never looks at payload bytes again, which is why nothing escapes
 * a 0xC0 occurring inside a payload.
 *
 * Recovery from a bad frame is deliberately not the obvious rule. `cxp_t_parse` has already
 * pulled the rejected bytes out of the stream by the time it rejects them, so it resumes hunting
 * *after* them rather than rewinding to marker+1. Rewinding would find frames the navigator never
 * sees. `resync skips the whole header` in `FramingTest` asserts it.
 */
fun iterPackets(stream: ByteArray): Scan {
    val packets = mutableListOf<Packet>()
    var pos = 0
    while (pos < stream.size) {
        var start = -1
        for (i in pos until stream.size) {
            if ((stream[i].toInt() and 0xFF) == FRAME_START) {
                start = i
                break
            }
        }
        if (start < 0) return Scan(packets, ByteArray(0))
        try {
            val (packet, used) = Packet.decode(stream, start)
            packets.add(packet)
            pos = start + used
        } catch (e: CxpException) {
            pos = when (e.kind) {
                // The header is good and the rest of the frame has not arrived.
                CxpError.SHORT_HEADER, CxpError.TRUNCATED ->
                    return Scan(packets, stream.copyOfRange(start, stream.size))

                // The length was believed and only the payload checksum failed, so the C has
                // already pulled the whole frame out of the stream.
                CxpError.PAYLOAD_CHECKSUM -> {
                    val total = (stream[start + 2].toInt() and 0xFF) or
                        ((stream[start + 3].toInt() and 0xFF) shl 8)
                    if (total in HEADER_LEN..(stream.size - start)) start + total
                    else start + HEADER_LEN
                }

                // The header itself was rejected, and those eight bytes are gone with it.
                else -> start + HEADER_LEN
            }
        }
    }
    return Scan(packets, ByteArray(0))
}

/**
 * SYN payload: the parameters each end proposes.
 *
 * `cxp_t_sync_proc()` reads them sequentially off the message stream, so the block is **packed,
 * 9 bytes, no padding**. A **V2** SYN appends a `u32 sync_id`, making the payload 13 bytes and the
 * whole packet 22. A **V1** omits it and the peer assumes `0xFFFFFFFF`.
 *
 * The id is what `syn_init()` logs as `timestamp %u`, one `cxp_ms_timer()` sample taken at the
 * first SYN and held for the session, so a changed id means the peer restarted.
 */
data class SynParams(
    val mxOut: Int = 0,
    val mxRetry: Int = 0,
    val mxCmltv: Int = 0,
    val maxSz: Int = 0,
    val toRetry: Int = 0,
    val toCmltv: Int = 0,
    /** null for a V1 SYN. */
    val syncId: Long? = null,
) {
    val isV2: Boolean get() = syncId != null

    /** 9 bytes for a V1 SYN, 13 if [syncId] is set. */
    fun encodePayload(): ByteArray {
        val out = ByteArray(if (isV2) V2_LEN else V1_LEN)
        out[0] = (mxOut and 0xFF).toByte()
        out[1] = (mxRetry and 0xFF).toByte()
        out[2] = (mxCmltv and 0xFF).toByte()
        putU16(out, 3, maxSz)
        putU16(out, 5, toRetry)
        putU16(out, 7, toCmltv)
        syncId?.let { putU32(out, V1_LEN, it) }
        return out
    }

    /**
     * Combine this end's proposal with the peer's.
     *
     * The result is the **remote's** parameters, with `mxOut` and `maxSz` clamped down to the
     * local values, and only when the local value is not zero. Timeouts are adopted as sent.
     *
     * ```c
     * if ((bVar1 != 0) && (bVar1 < remote_mx_out)) remote_mx_out = bVar1;
     * if ((uVar2 != 0) && (uVar2 < remote_max_sz)) remote_max_sz = uVar2;
     * ```
     *
     * That guard on zero is what makes this rule something other than "each side takes the
     * minimum": a zero from the *peer* wins, and a zero from *us* clamps nothing rather than
     * clamping the peer to zero. Both decompiled builds agree, at their own struct offsets.
     */
    fun negotiate(remote: SynParams) = remote.copy(
        mxOut = if (mxOut != 0 && mxOut < remote.mxOut) mxOut else remote.mxOut,
        maxSz = if (maxSz != 0 && maxSz < remote.maxSz) maxSz else remote.maxSz,
    )

    companion object {
        const val V1_LEN = 9
        const val V2_LEN = 13
        const val NO_SYNC_ID = 0xFFFFFFFFL

        fun decode(payload: ByteArray): SynParams {
            if (payload.size < V1_LEN) {
                throw CxpException(
                    CxpError.BAD_LENGTH, "SYN payload too short (${payload.size})"
                )
            }
            return SynParams(
                mxOut = payload[0].toInt() and 0xFF,
                mxRetry = payload[1].toInt() and 0xFF,
                mxCmltv = payload[2].toInt() and 0xFF,
                maxSz = getU16(payload, 3),
                toRetry = getU16(payload, 5),
                toCmltv = getU16(payload, 7),
                syncId = if (payload.size >= V2_LEN) getU32(payload, V1_LEN) else null,
            )
        }
    }
}

/**
 * Build an EAK payload: the sequence numbers received out of order.
 *
 * `cxp_t_pkt_mngr_prdc()` writes one u8 per queued packet, taken from that packet's `header[5]`.
 * [Packet.encode] appends the payload checksum that the receiver's `total_len - 9` count implies.
 */
fun encodeEak(psns: List<Int>): ByteArray {
    if (psns.isEmpty()) {
        throw CxpException(
            CxpError.INVALID_ARGUMENT, "an EAK with no sequence numbers is meaningless"
        )
    }
    return ByteArray(psns.size) { (psns[it] and 0xFF).toByte() }
}

/**
 * The sequence numbers an EAK acknowledges.
 *
 * `cxp_t_pkt_mngr_proc()` loops `for (i = 0; i < total_len - 9; i++)`, which is exactly the
 * payload once its trailing checksum is removed, and [Packet.decode] has already removed it.
 */
fun decodeEak(payload: ByteArray): List<Int> = payload.map { it.toInt() and 0xFF }

// Helpers for reading and writing little endian integers.

internal fun putU16(buf: ByteArray, at: Int, value: Int) {
    buf[at] = (value and 0xFF).toByte()
    buf[at + 1] = ((value shr 8) and 0xFF).toByte()
}

internal fun putU32(buf: ByteArray, at: Int, value: Long) {
    for (i in 0..3) buf[at + i] = ((value shr (8 * i)) and 0xFF).toByte()
}

internal fun getU16(buf: ByteArray, at: Int): Int =
    (buf[at].toInt() and 0xFF) or ((buf[at + 1].toInt() and 0xFF) shl 8)

internal fun getU32(buf: ByteArray, at: Int): Long {
    var v = 0L
    for (i in 0..3) v = v or ((buf[at + i].toLong() and 0xFF) shl (8 * i))
    return v
}
