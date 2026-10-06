package app.sendfpl.cxp

/**
 * CXP application layer: addressing data to a numbered CXP ID.
 *
 * Recovered from `cxp_app_xfr_bld_hdr()` in `libDCI_CONNEXT.so`, reached from
 * `CXP_app_outgoing`.
 *
 * ```
 *   0        1        2        3        4        5        6        7
 * +--------+--------+--------+--------+--------+--------+--------+--------+
 * | ver=1  |   total length  |  type  |            cxp_id                 |
 * +--------+--------+--------+--------+--------+--------+--------+--------+
 * |                        payload (total length - 8)                     |
 * +-----------------------------------------------------------------------+
 * ```
 *
 * The u16 and u32 sit on byte boundaries rather than word boundaries, since the builder writes
 * fields sequentially into a byte stream. It refuses a payload of 0xFF9 or more, capping a frame
 * at exactly 0x1000 = 4096, which is also the largest `max_packet_size` seen in SYN. Those two
 * independently read limits agreeing is a good sign both readings are right.
 */

const val APP_HEADER_LEN = 8
const val APP_VERSION = 1

/** `cxp_app_xfr_bld_hdr()` rejects a payload >= 0xFF9. */
const val APP_MAX_PAYLOAD = 0xFF8

/**
 * The type byte, which is a bitmask rather than an enum. Values come from the code that ORs each
 * bit in:
 * * [METADATA] set when `cxp_app_file_read` reports metadata
 * * [CONTROL] the control channel builder passes `type=2` with `cxp_id` 0
 * * [BEGIN] marks the first frame of a message, and a receiver **discards any message that does
 *   not carry it**. `cxp_app_file_write` calls `cxp_app_fm_in_begin` only when `type & 0x04` is
 *   set, and that is the only place the entry's flag `0x10` is set. `cxp_app_fm_in`, which
 *   consumes the body, refuses to run without that flag. For an auth id it is also what calls
 *   `cxp_auth_open` to create the receive context. Observed on a GPS 175: AUTH_USER and
 *   AUTH_RESPONSE sent without BEGIN were acknowledged and never processed.
 * * [END] set when `cxp_app_file_read` returns 1, i.e. the last segment of a message
 * * [COMPRESSED] set when `cxp_utl_compress` succeeded on the payload
 */
object FrameType {
    const val DATA = 0x00
    const val METADATA = 0x01
    const val CONTROL = 0x02
    const val BEGIN = 0x04
    const val END = 0x08
    const val COMPRESSED = 0x10
}

class AppException(message: String) : Exception(message)

data class AppFrame(
    val cxpId: Long,
    val payload: ByteArray = ByteArray(0),
    val type: Int = FrameType.END,
) {
    fun encode(): ByteArray {
        if (payload.size > APP_MAX_PAYLOAD) {
            throw AppException("payload ${payload.size} exceeds $APP_MAX_PAYLOAD, segment it first")
        }
        val total = APP_HEADER_LEN + payload.size
        val out = ByteArray(total)
        out[0] = APP_VERSION.toByte()
        putU16(out, 1, total)
        out[3] = (type and 0xFF).toByte()
        putU32(out, 4, cxpId)
        payload.copyInto(out, APP_HEADER_LEN)
        return out
    }

    val isLast: Boolean get() = (type and FrameType.END) != 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AppFrame) return false
        return cxpId == other.cxpId && type == other.type && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int =
        (cxpId.hashCode() * 31 + type) * 31 + payload.contentHashCode()

    companion object {
        /** Decode one frame from the front of [data], returning bytes consumed. */
        fun decode(data: ByteArray, offset: Int = 0): Decoded<AppFrame> {
            val avail = data.size - offset
            if (avail < APP_HEADER_LEN) throw AppException("short application header")
            val version = data[offset].toInt() and 0xFF
            if (version != APP_VERSION) throw AppException("unexpected app version $version")
            val total = getU16(data, offset + 1)
            if (total < APP_HEADER_LEN) throw AppException("invalid app length $total")
            if (avail < total) throw AppException("truncated application frame")
            val type = data[offset + 3].toInt() and 0xFF
            val cxpId = getU32(data, offset + 4)
            val payload = data.copyOfRange(offset + APP_HEADER_LEN, offset + total)
            return Decoded(AppFrame(cxpId, payload, type), total)
        }
    }
}

/**
 * Split a message into frames, marking only the final one [FrameType.END].
 *
 * A message of zero length still produces one frame, which is how a bare request, asking for
 * SUPPORTED_ELEMENTS say, is expressed.
 */
fun segment(cxpId: Long, payload: ByteArray, limit: Int = APP_MAX_PAYLOAD): List<AppFrame> {
    val cap = minOf(limit, APP_MAX_PAYLOAD)
    if (cap < 1) throw AppException("limit $cap leaves no room for payload")
    if (payload.isEmpty()) {
        return listOf(AppFrame(cxpId, ByteArray(0), FrameType.BEGIN or FrameType.END))
    }
    val starts = payload.indices step cap
    return starts.map { at ->
        var type = FrameType.DATA
        if (at == 0) type = type or FrameType.BEGIN
        if (at + cap >= payload.size) type = type or FrameType.END
        AppFrame(cxpId, payload.copyOfRange(at, minOf(at + cap, payload.size)), type)
    }
}

/** Concatenate frames for one CXP ID back into a message. */
fun reassemble(frames: List<AppFrame>): ByteArray {
    if (frames.isEmpty()) throw AppException("no frames")
    val ids = frames.map { it.cxpId }.toSet()
    if (ids.size != 1) throw AppException("frames span multiple CXP IDs: ${ids.sorted()}")
    val out = ByteArray(frames.sumOf { it.payload.size })
    var at = 0
    for (f in frames) {
        f.payload.copyInto(out, at)
        at += f.payload.size
    }
    return out
}
