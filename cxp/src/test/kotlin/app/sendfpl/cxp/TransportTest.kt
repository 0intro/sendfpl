package app.sendfpl.cxp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hex helpers shared by the protocol tests. */
fun hex(s: String): ByteArray {
    val clean = s.replace(" ", "")
    return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

/**
 * Ported from the internal tests of the reference implementation these were first written against.
 *
 * The literal byte strings are output captured from the working Python implementation, so these
 * are genuine vectors from another implementation rather than assertions the port could satisfy
 * by being wrong in the same way twice.
 */
class TransportTest {

    @Test
    fun `data packet matches the reference bytes exactly`() {
        val p = Packet(Ctrl.DATA or Ctrl.ACK, 7, 3, "hello cxp".toByteArray(Charsets.US_ASCII))
        assertEquals("c00112000207032168656c6c6f20637870 81".replace(" ", ""), p.encode().toHex())
    }

    @Test
    fun `total length is N plus 9 and both checksums are zero`() {
        val payload = "hello cxp".toByteArray(Charsets.US_ASCII)
        val raw = Packet(Ctrl.DATA or Ctrl.ACK, 7, 3, payload).encode()
        assertEquals(HEADER_LEN + payload.size + 1, raw.size)
        assertEquals(raw.size, getU16(raw, 2))
        assertEquals(0, checksum(raw, 0, HEADER_LEN))
        assertEquals(0, checksum(raw, HEADER_LEN, raw.size))
    }

    @Test
    fun `round trips`() {
        val p = Packet(Ctrl.DATA or Ctrl.ACK, 7, 3, "hello cxp".toByteArray())
        val raw = p.encode()
        val (back, used) = Packet.decode(raw)
        assertEquals(p, back)
        assertEquals(raw.size, used)
    }

    @Test
    fun `a bare ack is 8 bytes with no payload checksum`() {
        val raw = Packet(Ctrl.ACK, 1, 1).encode()
        assertEquals(HEADER_LEN, raw.size)
        assertEquals(HEADER_LEN, getU16(raw, 2))
    }

    @Test
    fun `a corrupted payload byte is rejected`() {
        val raw = Packet(Ctrl.DATA, 1, 0, "hello cxp".toByteArray()).encode()
        raw[9] = (raw[9].toInt() xor 0xFF).toByte()
        val e = runCatching { Packet.decode(raw) }.exceptionOrNull()
        assertTrue(e is CxpException)
        assertTrue(e!!.message!!.contains("payload checksum"))
    }

    @Test
    fun `bad header checksum and bad version are rejected`() {
        val good = Packet(Ctrl.DATA, 1, 0, "x".toByteArray()).encode()

        val badSum = good.copyOf()
        badSum[7] = (badSum[7].toInt() xor 0xFF).toByte()
        assertTrue(runCatching { Packet.decode(badSum) }.exceptionOrNull() is CxpException)

        val badVer = good.copyOf()
        badVer[1] = 2
        badVer[7] = 0
        badVer[7] = checksum(badVer, 0, 7).toByte()
        val e = runCatching { Packet.decode(badVer) }.exceptionOrNull()
        assertTrue(e!!.message!!.contains("invalid version"))
    }

    @Test
    fun `resynchronises past garbage and reports the trailing partial packet`() {
        val raw = Packet(Ctrl.DATA, 7, 3, "hello cxp".toByteArray()).encode()
        val stream = hex("deadbeef") + raw + raw.copyOfRange(0, 5)
        val scan = iterPackets(stream)
        assertEquals(1, scan.packets.size)
        assertArrayEquals(raw.copyOfRange(0, 5), scan.remainder)
    }

    @Test
    fun `SYN is 9 bytes for V1 and 13 for V2, matching the reference`() {
        val v1 = SynParams(16, 3, 8, 4096, 1500, 150)
        val v2 = v1.copy(syncId = 0xDEADBEEFL)
        assertEquals("1003080010dc059600", v1.encodePayload().toHex())
        assertEquals("1003080010dc059600efbeadde", v2.encodePayload().toHex())
        assertFalse(v1.isV2)
        assertTrue(v2.isV2)
        assertEquals(SynParams.V1_LEN, v1.encodePayload().size)
        assertEquals(SynParams.V2_LEN, v2.encodePayload().size)
    }

    @Test
    fun `a V2 SYN packet is 22 bytes, the 0x16 the SYN builder allocates`() {
        val v2 = SynParams(16, 3, 8, 4096, 1500, 150, syncId = 0xDEADBEEFL)
        val raw = Packet(Ctrl.SYN, 0, 0, v2.encodePayload()).encode()
        assertEquals(0x16, raw.size)
        // cxp_t_sync_check seeks 8 then 9, then reads the u32 at packet offset 17.
        assertEquals(0xDEADBEEFL, getU32(raw, 17))
        val v1 = Packet(Ctrl.SYN, 0, 0, SynParams(16, 3, 8, 4096, 1500, 150).encodePayload()).encode()
        assertEquals(18, v1.size)
    }

    @Test
    fun `max_sz sits at payload offset 3 - packed, no padding byte`() {
        val p = SynParams(16, 3, 8, 4096, 1500, 150).encodePayload()
        assertEquals(4096, getU16(p, 3))
        assertEquals(SynParams(16, 3, 8, 4096, 1500, 150), SynParams.decode(p))
    }

    @Test
    fun `negotiation adopts the peer's block and clamps only when the local value is set`() {
        // Where both sides propose a value other than zero, the outcome looks like a minimum
        // either way.
        val local = SynParams(16, 3, 8, 4096, 1500, 150)
        val remote = SynParams(1, 3, 1, 1024, 1500, 150)
        val n = local.negotiate(remote)
        assertEquals(1, n.mxOut)
        assertEquals(1024, n.maxSz)
        // The rest of the peer's block is taken verbatim.
        assertEquals(3, n.mxRetry)
        assertEquals(1, n.mxCmltv)
        assertEquals(150, n.toCmltv)
    }

    /**
     * The case that separates the real rule from "take the minimum", and the reason this was
     * wrong for a long time: a zero from the peer wins, where a minimum would have
     * substituted the local value.
     */
    @Test
    fun `a zero from the peer is adopted, not replaced`() {
        val local = SynParams(16, 3, 8, 4096, 1500, 150)
        val n = local.negotiate(SynParams(0, 0, 0, 0, 0, 0))
        assertEquals(0, n.mxOut)
        assertEquals(0, n.maxSz)
    }

    /** And a zero on *our* side means no clamp at all, rather than clamping the peer to zero. */
    @Test
    fun `a zero on the local side clamps nothing`() {
        val local = SynParams(0, 0, 0, 0, 0, 0)
        val n = local.negotiate(SynParams(16, 3, 8, 4096, 1500, 150))
        assertEquals(16, n.mxOut)
        assertEquals(4096, n.maxSz)
    }

    @Test
    fun `EAK carries one byte per sequence number and the receiver counts total minus 9`() {
        val psns = listOf(11, 12, 14, 17)
        val raw = Packet(Ctrl.EAK or Ctrl.ACK, 5, 10, encodeEak(psns)).encode()
        assertEquals("c0010d0006050a1d0b0c0e11ca", raw.toHex())
        val (decoded, _) = Packet.decode(raw)
        assertEquals(psns, decodeEak(decoded.payload))
        assertEquals(psns.size, getU16(raw, 2) - 9)
        assertFalse(decoded.isData)
    }

    @Test
    fun `an oversize payload is refused`() {
        val e = runCatching { Packet(Ctrl.DATA, 0, 0, ByteArray(MAX_PAYLOAD + 1)).encode() }
            .exceptionOrNull()
        assertTrue(e is CxpException)
    }

    @Test
    fun `SYN and EAK never count as application data`() {
        assertFalse(Packet(Ctrl.SYN, 0, 0, ByteArray(13)).isData)
        assertFalse(Packet(Ctrl.EAK, 1, 1, hex("05")).isData)
        assertTrue(Packet(Ctrl.DATA, 1, 1, hex("05")).isData)
    }

    @Test
    fun `a truncated stream yields no packets and keeps the bytes`() {
        val raw = Packet(Ctrl.DATA, 1, 0, "hello".toByteArray()).encode()
        val scan = iterPackets(raw.copyOfRange(0, raw.size - 2))
        assertTrue(scan.packets.isEmpty())
        assertEquals(raw.size - 2, scan.remainder.size)
        assertNull(null)
    }
}
