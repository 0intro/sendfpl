package app.sendfpl.cxp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The framer, against a reference implementation's own cases: same payload, same expectations.
 * A divergence here shows up as a failure rather than as the app quietly reading a different
 * packet stream from the one the reference reads.
 *
 * The property under test: 0xC0 means "frame start" only while the framer is hunting for one.
 * After a header validates it is driven by length, so a 0xC0 inside a payload is data. Nothing
 * escapes it, because nothing has to.
 */
class FramingTest {
    private val payloadWithMarkers = byteArrayOf(
        0xC0.toByte(), 0xC0.toByte(), 0xC0.toByte(), 0xC0.toByte(),
        0xC0.toByte(), 0xC0.toByte(), 0xC0.toByte(), 0xC0.toByte(),
        'K'.code.toByte(), 'S'.code.toByte(), 'F'.code.toByte(), 'O'.code.toByte(),
        0xC0.toByte(), 0x01, 0x0F, 0x00,
        0xC0.toByte(),
    )

    private fun frame() = Packet(ctrl = 2, psn = 7, ack = 3, payload = payloadWithMarkers).encode()

    @Test
    fun `encode does not escape a payload marker`() {
        val enc = frame()
        assertArrayEquals(
            payloadWithMarkers,
            enc.copyOfRange(HEADER_LEN, HEADER_LEN + payloadWithMarkers.size),
        )
    }

    @Test
    fun `a payload full of markers survives a round trip`() {
        val enc = frame()
        for ((name, stream) in listOf(
            "alone" to enc,
            "back to back" to (enc + enc),
            "after garbage" to (byteArrayOf(0x11, 0x22, 0x33) + enc),
            "garbage between" to (enc + byteArrayOf(0x11, 0x22) + enc),
        )) {
            val want = if (name == "alone" || name == "after garbage") 1 else 2
            val scan = iterPackets(stream)
            assertEquals(name, want, scan.packets.size)
            for (p in scan.packets) assertArrayEquals(name, payloadWithMarkers, p.payload)
        }
    }

    /**
     * Recovery skips the whole rejected header, not one byte.
     *
     * The second case is the one that discriminates: its bad header embeds, at offset 4, eight
     * bytes that are a *valid* standalone packet (`C0 01 08 00 02 01 00 34` sums to zero). A
     * parser rewinding to marker+1 finds and accepts it, and one that skips the rejected header
     * never sees it. The first case only shows recovery still works, which both rules manage.
     */
    @Test
    fun `resync skips the whole header`() {
        val good = Packet(ctrl = 2, psn = 9, ack = 0, payload = "KSFO".toByteArray()).encode()
        val bad = byteArrayOf(0xC0.toByte(), 0x01, 0x08, 0x00, 0xC0.toByte(), 0x00, 0x00, 0x00)

        val recovers = iterPackets(bad + good)
        assertEquals(1, recovers.packets.size)
        assertArrayEquals("KSFO".toByteArray(), recovers.packets[0].payload)

        val embedded = byteArrayOf(
            0xC0.toByte(), 0x01, 0x08, 0x00, 0xC0.toByte(), 0x01, 0x08, 0x00,
            0x02, 0x01, 0x00, 0x34,
        )
        assertEquals(
            "must not rewind into the rejected header",
            0,
            iterPackets(embedded).packets.size,
        )
    }

    /**
     * The price of that rule, pinned so nobody "fixes" it into rewinding: a frame that arrives
     * short takes the following frame down with it. Its length field is believed, so the parser
     * eats into the next frame, fails the payload checksum, and restarts hunting inside that
     * frame, where the next 0xC0 is a payload byte.
     */
    @Test
    fun `a truncated frame costs the next one`() {
        val enc = frame()
        val scan = iterPackets(enc.copyOfRange(0, enc.size - 3) + enc)
        assertEquals(0, scan.packets.size)
    }
}
