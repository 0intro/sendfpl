package app.sendfpl

import app.sendfpl.cxp.AppFrame
import app.sendfpl.cxp.Credential
import app.sendfpl.cxp.Ctrl
import app.sendfpl.cxp.FplId
import app.sendfpl.cxp.FrameType
import app.sendfpl.cxp.GPS175_PARAMS
import app.sendfpl.cxp.ID_CONNECTED_LRU
import app.sendfpl.cxp.Link
import app.sendfpl.cxp.Packet
import app.sendfpl.cxp.Profiles
import app.sendfpl.cxp.SupportedElements
import app.sendfpl.route.RouteParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wait for the navigator to say which model it is, against a link held in memory.
 *
 * A GPS 175 identifies itself about 90 ms after its capability struct, and a route built as soon as
 * the capabilities were in went out for the picker's model. The models differ in what they are
 * sent for a user waypoint, so [ConnextClient.awaitProductData] sits between the two. The
 * reference implementation pins the same three cases against its own client.
 */
class ConnextClientTest {

    /** A navigator that has already answered SYN and then says [messages], in that order. */
    private class Navigator(messages: List<AppFrame>) : Link {
        private var inbox = Packet(
            Ctrl.SYN, 0, 0, GPS175_PARAMS.copy(syncId = 0x11223344L).encodePayload(),
        ).encode() + messages.mapIndexed { i, m ->
            Packet(Ctrl.DATA or Ctrl.ACK, i + 1, 0, m.encode()).encode()
        }.fold(ByteArray(0)) { all, packet -> all + packet }

        override fun send(data: ByteArray) {}
        override fun receive(timeoutMillis: Long): ByteArray = inbox.also { inbox = ByteArray(0) }
    }

    private val credential = Credential(userId = 1, token = ByteArray(48), entitlement = byteArrayOf(1))

    /** One whole message, as the navigator frames it. */
    private fun message(cxpId: Long, payload: ByteArray) =
        AppFrame(cxpId, payload, FrameType.BEGIN or FrameType.END)

    /**
     * The 24 bytes a navigator sends on [ID_CONNECTED_LRU]. Only the id chooses a profile; no
     * GTN's name or version has been captured, so the ones given here are placeholders.
     */
    private fun identification(id: Long, name: String, version: String) =
        message(ID_CONNECTED_LRU, ByteArray(24).also {
            for (i in 0 until 4) it[i] = (id shr (8 * i)).toByte()
            name.toByteArray().copyInto(it, 4)
            version.toByteArray().copyInto(it, 16)
        })

    @Test
    fun `a late identification still chooses the profile`() {
        val client = ConnextClient(
            Navigator(listOf(identification(Profiles.GTN.productId, "GTN 750", "6.72"))),
            credential,
        )
        val said = client.awaitProductData(1_000)
        assertEquals(Profiles.GTN.productId, said!!.productId)
        assertEquals(Profiles.GTN, said.profile)
        assertEquals(said, client.info.productData)

        // What the transfer does next: build for the model the navigator named.
        assertEquals(
            "FPN/RI:F:LFPL:F:N48475E002522:F:LFPK",
            RouteParser.parse("LFPL LOBEL,N48475E002522 LFPK").render(said.profile!!),
        )
    }

    @Test
    fun `no identification costs only the grace`() {
        val client = ConnextClient(Navigator(emptyList()), credential)
        val grace = 300L
        val started = System.nanoTime()
        assertNull(client.awaitProductData(grace))
        val tookMillis = (System.nanoTime() - started) / 1_000_000
        assertTrue("the wait took $tookMillis ms for a grace of $grace", tookMillis < grace + 1_000)
    }

    @Test
    fun `what arrives while waiting is kept for whoever waits for it`() {
        val caps = SupportedElements(elements = 0x0004002f, maxTextLength = 3520, waypointTypes = 3)
        val client = ConnextClient(
            Navigator(
                listOf(
                    message(FplId.SUPPORTED_ELEMENTS, caps.encode()),
                    identification(Profiles.GPS175.productId, "GPS 175", "3.22"),
                )
            ),
            credential,
        )
        assertEquals(Profiles.GPS175, client.awaitProductData(1_000)!!.profile)
        // Read during the wait, parked, and there for the step that wanted it.
        assertEquals(caps, client.negotiateCapabilities())
    }
}
