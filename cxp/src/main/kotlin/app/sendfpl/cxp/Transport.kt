package app.sendfpl.cxp

/**
 * CXP transport framing (Garmin Connext), an RUDP variant read from two independently compiled
 * binaries that agree: `IOP_E.dll` (GPS 175 trainer 3.21.2) and `libDCI_CONNEXT.so` (FltPlan Go
 * 5.0.21, unstripped). No public specification exists.
 *
 * A header of 8 bytes, an optional payload, and a payload checksum:
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
 * `total length` is `N + 9` with a payload and 8 without: it counts the payload checksum, and the
 * navigator discards a packet whose length says `8 + N` as "invalid payload". A GPS 175 imports a
 * flight plan framed this way, including a 1300 byte route split across two packets.
 */

internal const val FRAME_START = 0xC0
const val VERSION = 1
internal const val HEADER_LEN = 8

/** `cxp_t_hdr_bld()` rejects more: with header and checksum, a packet is at most 0xFFFF bytes. */
internal const val MAX_PAYLOAD = 0xFFF6

/**
 * Header ctrl byte (offset 4), a bitmask, confirmed from both the senders and the receiver:
 * * [SYN] `cxp_t_sync_prdc()` builds `ctrl=0x01`
 * * [ACK] `cxp_t_pkt_mngr_proc()` only processes the `ack` field when set
 * * [EAK] `cxp_t_pkt_mngr_prdc()` builds `ctrl=0x04` beside "Send EAKs:"
 *
 * Nothing examined sets or tests bit `0x08`, so it is left unnamed.
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

/** Why a frame could not be decoded, as a value so that [iterPackets] recovers by kind. */
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

/** One decoded packet or frame, plus how many bytes of the stream it consumed. */
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
        putU16(out, 2, total)
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
         * Decode one packet at [offset] in [data]. The errors draw the distinctions `cxp_t_parse()`
         * logs, so a caller can tell a truncated read from a corrupt one.
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
class Scan(val packets: List<Packet>, val remainder: ByteArray)

/**
 * Split a byte stream into packets, resynchronising on 0xC0 as `cxp_t_parse()` does: it hunts for
 * a frame start and drops what precedes it. Only frame acquisition looks for 0xC0, so a 0xC0 in a
 * payload needs no escape. The C has already consumed the bytes it rejects, so hunting resumes
 * after them, not at marker+1, which would find frames the navigator never sees
 * (`resync skips the whole header` in `FramingTest`).
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
                // The rest of the frame has not arrived yet.
                CxpError.SHORT_HEADER, CxpError.TRUNCATED ->
                    return Scan(packets, stream.copyOfRange(start, stream.size))

                // Only the payload checksum failed, and the C has already consumed the whole frame.
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
 * SYN payload: the parameters each end proposes. `cxp_t_sync_proc()` reads them one after
 * another, so the block is packed, 9 bytes. A V2 SYN appends a u32 sync id ([newSyncId]), for a
 * payload of 13 bytes and a packet of 22; a V1 SYN omits it and the peer assumes `0xFFFFFFFF`.
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
     * Combine this end's proposal with the peer's: the peer's block, with `mxOut` and `maxSz`
     * clamped to ours where ours is not zero.
     *
     * The rule is `cxp_t_sync_proc`'s, but its limits are fields only `CXP_t_set_rmt_limits`
     * sets, the power-up block zeroes them and neither Garmin client calls the setter, so Garmin's
     * clients adopt the peer's values as sent (a GPS 175 proposes `mx_out` 64). Clamping to our
     * own proposal is this port's choice.
     */
    fun negotiate(remote: SynParams) = remote.copy(
        mxOut = if (mxOut != 0 && mxOut < remote.mxOut) mxOut else remote.mxOut,
        maxSz = if (maxSz != 0 && maxSz < remote.maxSz) maxSz else remote.maxSz,
    )

    companion object {
        const val V1_LEN = 9
        const val V2_LEN = 13

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
 * Build an EAK payload: the sequence numbers received out of order, one u8 each, as
 * `cxp_t_pkt_mngr_prdc()` writes them from each queued packet's `header[5]`.
 */
internal fun encodeEak(psns: List<Int>): ByteArray {
    if (psns.isEmpty()) {
        throw CxpException(
            CxpError.INVALID_ARGUMENT, "an EAK with no sequence numbers is meaningless"
        )
    }
    return ByteArray(psns.size) { (psns[it] and 0xFF).toByte() }
}

/**
 * The sequence numbers an EAK acknowledges. `cxp_t_pkt_mngr_proc()` reads `total_len - 9` of them:
 * the payload without the checksum that [Packet.decode] has already removed.
 */
internal fun decodeEak(payload: ByteArray): List<Int> = payload.map { it.toInt() and 0xFF }

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
