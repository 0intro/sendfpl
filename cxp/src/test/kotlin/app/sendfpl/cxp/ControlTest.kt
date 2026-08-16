package app.sendfpl.cxp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The control codec against a reference implementation's literals, written out by hand, so a
 * divergence fails here rather than reaching certified avionics.
 */
class ControlTest {
    /** What `registerForMessageType(0x10005000, PRDC, FT_PRI_FPL_XFER)` puts on the wire. */
    @Test
    fun `set mode wire form`() {
        val body = encodeControl(listOf(setMode(FplId.SUPPORTED_ELEMENTS, CxpMode.PRDC, PRIORITY_FPL_TRANSFER)))
        assertArrayEquals(hex("00 00500010 01 28"), body)
        assertEquals("the length is what identifies the opcode", 7, body.size)
    }

    /** Seven bytes must be uniquely SET_MODE, which is the whole basis for reading the observed frame. */
    @Test
    fun `only set mode is seven bytes`() {
        for (op in Opcode.SET_MODE..Opcode.COMPLETE) {
            val total = 5 + Opcode.extraLen(op)
            assertEquals("opcode $op", op == Opcode.SET_MODE, total == 7)
        }
    }

    @Test
    fun `round trip`() {
        val cmds = listOf(
            setMode(FplId.SUPPORTED_ELEMENTS, CxpMode.PRDC, PRIORITY_FPL_TRANSFER),
            setMode(FplId.UPLOAD_TO_AVIONICS, CxpMode.RQST, PRIORITY_FPL_TRANSFER),
            ControlCmd(Opcode.REQUEST, 0x1000a000L),
            ControlCmd(Opcode.METADATA, FplId.USER_WAYPOINT_LIST, flag = 1),
            ControlCmd(Opcode.ABORT, FplId.MINIMAL_TRANSFER_TO_SIMPLE_AVIONICS),
        )
        assertEquals(cmds, decodeControl(encodeControl(cmds)))
    }

    @Test
    fun `rejects what the navigator would not accept`() {
        for (bad in listOf(
            byteArrayOf(0x00, 0x01, 0x02),
            hex("00 01020304"),
            hex("00 01020304 01"),
            hex("05 01020304"),
        )) {
            runCatching { decodeControl(bad) }
                .onSuccess { org.junit.Assert.fail("accepted a truncated body") }
        }
        runCatching { encodeControl(listOf(setMode(1L, 3, 0))) }
            .onSuccess { org.junit.Assert.fail("mode 3 must be refused") }
    }

    /**
     * Decoding is deliberately looser than encoding: `cxp_app_ctrl_write` reads a mode byte, hands
     * it to `cxp_app_fm_set_mode` and continues regardless of the return, so a mode above 2 is a
     * properly formed frame carrying a command the navigator ignores.
     */
    @Test
    fun `a bad mode decodes but does not encode`() {
        val cmds = decodeControl(hex("00 01020304 30 00"))
        assertEquals(0x30, cmds[0].mode)
        runCatching { encodeControl(cmds) }
            .onSuccess { org.junit.Assert.fail("a mode of 0x30 must not be emitted") }
    }

    @Test
    fun `a control frame carries id 0 and no END bit`() {
        val f = controlFrame(listOf(setMode(FplId.SUPPORTED_ELEMENTS, CxpMode.PRDC, PRIORITY_FPL_TRANSFER)))
        assertEquals(0L, f.cxpId)
        assertEquals(FrameType.CONTROL, f.type)
        assertFalse("a control frame must not set END", f.isLast)
        assertTrue(f.payload.size == 7)
    }
}
