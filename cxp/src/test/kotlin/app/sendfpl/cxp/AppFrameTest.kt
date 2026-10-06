package app.sendfpl.cxp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from the internal tests of the reference implementation these were written against. */
class AppFrameTest {

    @Test
    fun `frame matches the reference bytes exactly`() {
        val f = AppFrame(0x10005001L, ":DA:KSFO:F:SAC:AA:KLAS".toByteArray(), FrameType.END)
        val raw = f.encode()
        assertEquals("011e000801500010", raw.copyOfRange(0, 8).toHex())
        assertEquals(":DA:KSFO:F:SAC:AA:KLAS", String(raw.copyOfRange(8, raw.size)))
    }

    @Test
    fun `header fields sit on byte boundaries in the documented order`() {
        val raw = AppFrame(0x10005001L, ":DA:KSFO:F:SAC:AA:KLAS".toByteArray()).encode()
        assertEquals(APP_VERSION, raw[0].toInt() and 0xFF)
        assertEquals(raw.size, getU16(raw, 1))
        assertEquals(FrameType.END, raw[3].toInt() and 0xFF)
        assertEquals(0x10005001L, getU32(raw, 4))
    }

    @Test
    fun `round trips`() {
        val f = AppFrame(0x10005001L, "route".toByteArray(), FrameType.END)
        val (back, used) = AppFrame.decode(f.encode())
        assertEquals(f, back)
        assertEquals(f.encode().size, used)
    }

    @Test
    fun `the payload cap makes a frame exactly 4096 bytes`() {
        assertEquals(0x1000, APP_MAX_PAYLOAD + APP_HEADER_LEN)
        val e = runCatching { AppFrame(1L, ByteArray(APP_MAX_PAYLOAD + 1)).encode() }.exceptionOrNull()
        assertTrue(e is AppException)
    }

    @Test
    fun `segmentation marks only the final frame END`() {
        val frames = segment(0x10005001L, ByteArray(10_000))
        assertEquals(listOf(4088, 4088, 1824), frames.map { it.payload.size })
        assertEquals(listOf(false, false, true), frames.map { it.isLast })
        assertArrayEquals(ByteArray(10_000), reassemble(frames))
    }

    /**
     * The navigator discards a message whose first frame lacks BEGIN, unread and without
     * complaint: `cxp_app_fm_in` refuses a body until `cxp_app_fm_in_begin` has set the entry's
     * flag 0x10, and only a BEGIN frame runs that. A real GPS 175 acknowledged AUTH_USER and
     * AUTH_RESPONSE at the transport layer, processed neither, and challenged again every 1500 ms
     * indefinitely. Asserting the whole type byte, because `isLast` alone missed this.
     */
    @Test
    fun `the first frame carries BEGIN and the last END`() {
        val many = segment(0x10005001L, ByteArray(10_000))
        assertEquals(
            listOf(FrameType.BEGIN, FrameType.DATA, FrameType.END),
            many.map { it.type },
        )

        // A message of one frame carries both, which is exactly what the navigator sends: its
        // AUTH_CHALLENGE arrives as type 0x0c.
        val one = segment(0x10005001L, hex("616263"))
        assertEquals(listOf(FrameType.BEGIN or FrameType.END), one.map { it.type })
        assertEquals(0x0c, one[0].type)
    }

    @Test
    fun `an empty message is still one frame - that is how a bare request is expressed`() {
        val frames = segment(FplId.SUPPORTED_ELEMENTS, ByteArray(0))
        assertEquals(1, frames.size)
        assertTrue(frames[0].isLast)
        assertEquals(0, frames[0].payload.size)
        assertEquals(FrameType.BEGIN or FrameType.END, frames[0].type)
    }

    @Test
    fun `reassembling frames from different IDs is refused`() {
        val e = runCatching {
            reassemble(listOf(AppFrame(1L, hex("00")), AppFrame(2L, hex("01"))))
        }.exceptionOrNull()
        assertTrue(e is AppException)
    }

    @Test
    fun `a wrong app version is rejected`() {
        val raw = AppFrame(1L, hex("00")).encode()
        raw[0] = 2
        val e = runCatching { AppFrame.decode(raw) }.exceptionOrNull()
        assertTrue(e!!.message!!.contains("app version"))
    }
}
