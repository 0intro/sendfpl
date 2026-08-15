package app.sendfpl.cxp

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * The framer, against a reference implementation's own cases. 0xC0 means "frame start" only while
 * the framer is hunting for one: after a header validates, the length drives it, so a 0xC0 in a
 * payload is data and nothing escapes it.
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
        assertContentEquals(
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
            assertEquals(want, scan.packets.size, name)
            for (p in scan.packets) assertContentEquals(payloadWithMarkers, p.payload, name)
        }
    }

    /**
     * Recovery skips the whole rejected header, not one byte. The second case discriminates: its
     * bad header embeds at offset 4 a valid packet, `C0 01 08 00 02 01 00 34`, which a parser
     * rewinding to marker+1 would accept.
     */
    @Test
    fun `resync skips the whole header`() {
        val good = Packet(ctrl = 2, psn = 9, ack = 0, payload = "KSFO".encodeToByteArray()).encode()
        val bad = byteArrayOf(0xC0.toByte(), 0x01, 0x08, 0x00, 0xC0.toByte(), 0x00, 0x00, 0x00)

        val recovers = iterPackets(bad + good)
        assertEquals(1, recovers.packets.size)
        assertContentEquals("KSFO".encodeToByteArray(), recovers.packets[0].payload)

        val embedded = byteArrayOf(
            0xC0.toByte(), 0x01, 0x08, 0x00, 0xC0.toByte(), 0x01, 0x08, 0x00,
            0x02, 0x01, 0x00, 0x34,
        )
        assertEquals(
            0,
            iterPackets(embedded).packets.size,
            "must not rewind into the rejected header",
        )
    }

    /**
     * The price of that rule, pinned so nobody "fixes" it into rewinding: a short frame's length
     * is believed, so the parser eats into the next frame, fails the payload checksum, and resumes
     * hunting inside it.
     */
    @Test
    fun `a truncated frame costs the next one`() {
        val enc = frame()
        val scan = iterPackets(enc.copyOfRange(0, enc.size - 3) + enc)
        assertEquals(0, scan.packets.size)
    }
}
