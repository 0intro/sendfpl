package app.sendfpl.cxp

/**
 * The application control channel: an application frame with `type = CONTROL` and `cxpId = 0`
 * (`cxp_app_xfr_bld_hdr(msg, len, 2, 0)`) whose body is a sequence of commands, by which each end
 * says which CXP ids it wants and in what mode. It is part of no message and carries no END bit,
 * so a receiver that feeds it to a reassembly loop hangs.
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
    /** Declares one id's transfer mode and priority, as `registerForMessageType` does. */
    const val SET_MODE = 0
    const val REQUEST = 1
    const val DATA = 2
    const val CANCEL = 3
    const val ABORT = 4
    const val HOLD = 5
    const val METADATA = 6

    /**
     * Asks for one id's transfers to be compressed (flag 1) or not (0): flag `0x10000` in
     * `cxp_app_ctrl_write`, case 7, which aborts the transfer instead when compression is not
     * enabled. It says nothing about a plan. A GPS 175's `cxp_ctrl_write` (`FUN_d84175a4`) has no
     * case 7.
     */
    const val COMPRESS = 7

    /** Bytes following the opcode and id, or -1 when the opcode is unknown. */
    fun extraLen(op: Int): Int = when (op) {
        SET_MODE -> 2
        REQUEST, DATA, CANCEL, ABORT -> 0
        HOLD, METADATA, COMPRESS -> 1
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
 * `CXP_PRIORITY_DEFAULT`, which `cxp_app_fm_add` writes into byte 0 of every entry it creates. A
 * [Opcode.SET_MODE] carrying it changes the mode and leaves the priority as the peer set it.
 */
const val PRIORITY_DEFAULT = 10

/**
 * `CXP_PRIORITY_CONTROL`. `CXP_app_new` sets its receive table's id 0 to (PRDC, this priority),
 * which asks the peer for its id list: a GPS 175 sends `setMode(0, PRDC, 20)` as soon as the
 * connection comes up.
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

internal fun encodeControl(cmds: List<ControlCmd>): ByteArray {
    var out = ByteArray(0)
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
        val cmd = ByteArray(5 + extra)
        cmd[0] = c.op.toByte()
        putU32(cmd, 1, c.cxpId)
        when (extra) {
            2 -> {
                cmd[5] = c.mode.toByte()
                cmd[6] = c.priority.toByte()
            }
            1 -> cmd[5] = c.flag.toByte()
        }
        out += cmd
    }
    return out
}

/**
 * Decode a control body. A mode above [CxpMode.RQST] decodes, though [encodeControl] refuses one:
 * `cxp_app_ctrl_write` hands it to `cxp_app_fm_set_mode` and carries on whatever that returns.
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
        val id = getU32(body, i + 1)
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
 * The id list: the message a node sends on CXP id 0 to declare the ids it will send, bare little
 * endian u32s with no count, in an ordinary BEGIN|END frame. The receiver's `CXP_id_list_write`
 * adds a receive entry for each id at mode OFF (`cxp_app_fm_add(receive_table, id, 0)`), switches
 * on only ids 1 to 4, and records an id in 0x100..0x13F as a peer marker rather than a channel.
 * `cxp_file_write` drops a message on an id with no entry.
 */
const val ID_LIST_CXP_ID = 0L

/**
 * The id `CXP_app_new` in `libDCI_CONNEXT.so` seeds into a client's send table, and so the one a
 * client's id list carries; a GPS 175's carries `0x101`. Both fall in the 0x100..0x13F range that
 * `CXP_id_list_write` stores as a peer marker instead of opening a channel.
 */
const val ID_MARKER = 0x102L

internal fun encodeIdList(ids: List<Long>): ByteArray {
    val out = ByteArray(4 * ids.size)
    ids.forEachIndexed { i, id -> putU32(out, 4 * i, id) }
    return out
}

fun decodeIdList(body: ByteArray): List<Long> {
    if (body.size % 4 != 0) {
        throw CxpException(
            CxpError.BAD_LENGTH, "id list of ${body.size} bytes is not a whole number of ids"
        )
    }
    return List(body.size / 4) { getU32(body, 4 * it) }
}

/** Wrap an id list in the frame the wire expects: id 0, an ordinary data type, BEGIN and END. */
fun idListFrame(ids: List<Long>): AppFrame =
    AppFrame(ID_LIST_CXP_ID, encodeIdList(ids), FrameType.BEGIN or FrameType.END)
