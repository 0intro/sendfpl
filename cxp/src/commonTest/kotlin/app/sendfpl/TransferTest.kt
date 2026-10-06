package app.sendfpl

import app.sendfpl.cxp.Credential
import app.sendfpl.cxp.DemoNavigator
import app.sendfpl.cxp.FrameStream
import app.sendfpl.cxp.Profile
import app.sendfpl.cxp.Profiles
import app.sendfpl.cxp.SessionException
import app.sendfpl.cxp.TOKEN_LEN
import app.sendfpl.route.RouteParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The apps' whole transfer, against a navigator held in memory over a bare stream. */
class TransferTest {

    private val credential = Credential(1, ByteArray(TOKEN_LEN) { it.toByte() }, byteArrayOf(1))
    private val route = RouteParser.parse("LFPL LOBEL,N48475E002522 LFPK")

    private fun client(navigator: DemoNavigator) =
        ConnextClient(FrameStream(navigator), credential).apply { verdictGraceMillis = 50 }

    @Test
    fun `the route is built for the model the navigator names`() {
        val navigator = DemoNavigator(credential.token, productId = Profiles.GTN.productId)
        var told: Pair<Profile?, Profile>? = null
        val sent = client(navigator).transfer(route, Profiles.GPS175, onModel = { named, used -> told = named to used })
        assertEquals(Profiles.GTN to Profiles.GTN, told)
        assertEquals(Profiles.GTN, sent.profile)
        // A GTN is sent a user waypoint as its position alone.
        assertEquals("FPN/RI:F:LFPL:F:N48475E002522:F:LFPK", sent.route)
        assertEquals(listOf(sent.route), navigator.uploads)
    }

    @Test
    fun `a navigator naming no known model is sent the picked model's route`() {
        val navigator = DemoNavigator(credential.token, productId = 9999)
        var told: Pair<Profile?, Profile>? = null
        val sent = client(navigator).transfer(route, Profiles.GPS175, onModel = { named, used -> told = named to used })
        assertEquals(null to Profiles.GPS175, told)
        assertEquals("FPN/RI:F:LFPL:F:LOBEL,N48475E002522:F:LFPK", sent.route)
        assertEquals(listOf(sent.route), navigator.uploads)
    }

    @Test
    fun `the route reaches onRoute before it goes up`() {
        val navigator = DemoNavigator(credential.token)
        var heard: String? = null
        val sent = client(navigator).transfer(route, Profiles.GPS175, onRoute = { heard = it; assertTrue(navigator.uploads.isEmpty()) })
        assertEquals(sent.route, heard)
    }

    @Test
    fun `a refused upload is UPLOAD_REFUSED`() {
        val navigator = DemoNavigator(credential.token, refuse = true)
        val e = assertFailsWith<SessionException> { client(navigator).transfer(route, Profiles.GPS175) }
        assertEquals(SessionException.Reason.UPLOAD_REFUSED, e.reason)
        assertEquals(emptyList(), navigator.uploads)
    }

    @Test
    fun `a credential the navigator does not hold is CREDENTIAL_REFUSED`() {
        val navigator = DemoNavigator(ByteArray(TOKEN_LEN))
        val e = assertFailsWith<SessionException> { client(navigator).transfer(route, Profiles.GPS175) }
        assertEquals(SessionException.Reason.CREDENTIAL_REFUSED, e.reason)
        assertEquals(emptyList(), navigator.uploads)
    }
}
