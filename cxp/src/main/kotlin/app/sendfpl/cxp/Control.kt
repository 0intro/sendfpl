package app.sendfpl.cxp

/**
 * The application control channel.
 *
 * A control frame is an ordinary application frame with `type = CONTROL` and `cxpId = 0`
 * (`cxp_app_xfr_bld_hdr(msg, len, 2, 0)`), whose body is a *sequence* of commands. It is how each
 * end says which CXP ids it wants and in what mode, and it is not part of any message: it carries
 * no END bit, so a receiver that feeds it to a reassembly loop hangs. A real GPS 175 sends one
 * immediately after accepting AUTH_USER.
 *
 * Every command begins the same way:
 * ```
 * u8  opcode
 * u32 cxp_id      little endian
 * ... bytes specific to the opcode
 * ```
 *
 * Read from `cxp_app_ctrl_write` in two builds that agree instruction for instruction: the Flight
 * Stream 510's unstripped `gcc_file_manager` at `0x000d8340` and FltPlan Go's `libDCI_CONNEXT.so`
 * at `0x0004f440`.
 */
object Opcode {
    /** Declares one id's transfer mode and priority, as `registerForMessageType` does. The only one of 7 bytes. */
    const val SET_MODE = 0
    const val REQUEST = 1
    const val DATA = 2
    const val CANCEL = 3
    const val ABORT = 4
    const val HOLD = 5
    const val METADATA = 6
    const val COMPLETE = 7

    /** Bytes following the opcode and id, or -1 when the opcode is unknown. */
    fun extraLen(op: Int): Int = when (op) {
        SET_MODE -> 2
        REQUEST, DATA, CANCEL, ABORT -> 0
        HOLD, METADATA, COMPLETE -> 1
        else -> -1
    }
}

/** Transfer modes, from `CxpModeType`. `cxp_app_fm_set_mode` rejects anything above [RQST]. */
object CxpMode {
    const val OFF = 0
    const val PRDC = 1
    const val RQST = 2
}

/** `FT_PRI_FPL_XFER` from `CxpPriorityType`. Lower is more urgent, and the enum runs from 0 up. */
const val PRIORITY_FPL_TRANSFER = 40

/**
 * `CXP_PRIORITY_DEFAULT`, the value `cxp_app_fm_add` writes into byte 0 of every entry it creates.
 *
 * Sending it back in a [Opcode.SET_MODE] leaves the priority exactly as the peer's own code
 * initialised it, so the command changes the mode and nothing else, which is what registering for
 * the auth channels wants.
 */
const val PRIORITY_DEFAULT = 10

/**
 * `CXP_PRIORITY_CONTROL`.
 *
 * A real GPS 175 announces its own control channel with it: the first thing it sends after
 * accepting AUTH_USER is `setMode(0, PRDC, 20)`.
 */
const val PRIORITY_CONTROL = 20

/** One command from a control frame's body. */
data class ControlCmd(
    val op: Int,
    val cxpId: Long,
    val mode: Int = 0,
    val priority: Int = 0,
    val flag: Int = 0,
)

/** The registration command for one id. */
fun setMode(cxpId: Long, mode: Int, priority: Int) =
    ControlCmd(Opcode.SET_MODE, cxpId, mode = mode, priority = priority)

fun encodeControl(cmds: List<ControlCmd>): ByteArray {
    val out = ArrayList<Byte>(cmds.size * 7)
    for (c in cmds) {
        val extra = Opcode.extraLen(c.op)
        if (extra < 0) {
            throw CxpException(CxpError.INVALID_ARGUMENT, "unknown control opcode ${c.op}")
        }
        if (c.op == Opcode.SET_MODE && c.mode > CxpMode.RQST) {
            throw CxpException(
                CxpError.INVALID_ARGUMENT,
                "invalid transfer mode ${c.mode}, cxp_app_fm_set_mode rejects above ${CxpMode.RQST}",
            )
        }
        out.add(c.op.toByte())
        for (i in 0 until 4) out.add(((c.cxpId shr (i * 8)) and 0xFF).toByte())
        when (extra) {
            2 -> {
                out.add(c.mode.toByte())
                out.add(c.priority.toByte())
            }
            1 -> out.add(c.flag.toByte())
        }
    }
    return out.toByteArray()
}

/**
 * Decode a control body.
 *
 * Deliberately more permissive than [encodeControl]: a mode above [CxpMode.RQST] decodes fine,
 * because `cxp_app_ctrl_write` reads it, hands it to `cxp_app_fm_set_mode` and continues its loop
 * regardless of the return. Refusing to *emit* one is our discipline, not the wire's rule.
 */
fun decodeControl(body: ByteArray): List<ControlCmd> {
    val cmds = mutableListOf<ControlCmd>()
    var i = 0
    while (i < body.size) {
        if (body.size - i < 5) {
            throw CxpException(
                CxpError.TRUNCATED, "truncated control command: ${body.size - i} bytes left"
            )
        }
        val op = body[i].toInt() and 0xFF
        val extra = Opcode.extraLen(op)
        if (extra < 0) throw CxpException(CxpError.BAD_LENGTH, "unknown control opcode $op")
        if (body.size - i < 5 + extra) {
            throw CxpException(
                CxpError.TRUNCATED, "truncated control command: opcode $op needs ${5 + extra}"
            )
        }
        var id = 0L
        for (k in 0 until 4) id = id or ((body[i + 1 + k].toLong() and 0xFF) shl (k * 8))
        cmds += when (extra) {
            2 -> ControlCmd(op, id, mode = body[i + 5].toInt() and 0xFF, priority = body[i + 6].toInt() and 0xFF)
            1 -> ControlCmd(op, id, flag = body[i + 5].toInt() and 0xFF)
            else -> ControlCmd(op, id)
        }
        i += 5 + extra
    }
    return cmds
}

/** Wrap a command sequence in the frame the wire expects: id 0, CONTROL, no END bit. */
fun controlFrame(cmds: List<ControlCmd>): AppFrame =
    AppFrame(cxpId = 0L, payload = encodeControl(cmds), type = FrameType.CONTROL)

/**
 * The id list: the message a node sends on CXP id 0 to declare which ids it will send.
 * **Nothing else it sends will be accepted until it does.**
 *
 * Read from `CXP_id_list_write` in the device build, which names itself in its own log line. For
 * each u32 in the body the receiver calls `cxp_app_fm_add(receive_table, id, 0)` and switches it
 * on, with id 1 at priority 0x12, ids 2/3/4 at 0x13, and an id in 0x100..0x13F is recorded as a peer
 * marker rather than a channel. An id that never appears in such a list has no entry, and
 * `cxp_file_write` returns without touching the message.
 *
 * Not a theoretical gap: a GPS 175 acknowledged our AUTH_USER and AUTH_RESPONSE at the transport
 * layer and answered neither, because ids 1 and 3 had never been announced.
 *
 * The peer asks rather than assuming: `CXP_app_new` sets its *receive* table's id 0 to
 * (PRDC, CXP_PRIORITY_CONTROL), which reaches us as the control frame `00 00 00 00 00 01 14`.
 *
 * The body is bare u32s in little endian order: no count, no header. It is an ordinary application
 * frame, not a control frame, and carries BEGIN|END like any other.
 */
const val ID_LIST_CXP_ID = 0L

/**
 * The id `CXP_app_new` seeds into every node's send table, and so the one every id list carries.
 * It falls in the 0x100..0x13F range that `CXP_id_list_write` stores as a peer marker.
 */
const val ID_MARKER = 0x102L

fun encodeIdList(ids: List<Long>): ByteArray {
    val out = ByteArray(4 * ids.size)
    ids.forEachIndexed { i, id ->
        for (k in 0 until 4) out[4 * i + k] = ((id shr (k * 8)) and 0xFF).toByte()
    }
    return out
}

fun decodeIdList(body: ByteArray): List<Long> {
    if (body.size % 4 != 0) {
        throw CxpException(
            CxpError.BAD_LENGTH, "id list of ${body.size} bytes is not a whole number of ids"
        )
    }
    return (0 until body.size / 4).map { i ->
        var id = 0L
        for (k in 0 until 4) id = id or ((body[4 * i + k].toLong() and 0xFF) shl (k * 8))
        id
    }
}

/** Wrap an id list in the frame the wire expects: id 0, an ordinary data type, BEGIN and END. */
fun idListFrame(ids: List<Long>): AppFrame =
    AppFrame(ID_LIST_CXP_ID, encodeIdList(ids), FrameType.BEGIN or FrameType.END)
