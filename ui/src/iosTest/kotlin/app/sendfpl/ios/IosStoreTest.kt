package app.sendfpl.ios

import app.sendfpl.FileTooLargeException
import app.sendfpl.cxp.AuthException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.writeToURL

/** The iPhone app's reading of a credential file and of a route file. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosStoreTest {

    private val token = "00".repeat(48)

    @Test
    fun `a credential reads as the Android app reads it`() {
        val c = parseCredential("""{"user_id": 7, "token": "$token", "entitlement": "0102"}""")
        assertEquals(7L, c.userId)
        assertContentEquals(ByteArray(48), c.token)
    }

    @Test
    fun `a refusal names what is wrong`() {
        assertEquals("not valid JSON", assertFailsWith<AuthException> { parseCredential("{") }.message)
        val e = assertFailsWith<AuthException> { parseCredential("""{"user_id": 7, "token": "$token"}""") }
        assertEquals("missing \"entitlement\"", e.message)
    }

    private fun file(text: String): NSURL {
        val url = NSURL.fileURLWithPath(NSTemporaryDirectory() + "sendfpl-test-${text.length}.gfp")
        NSString.create(string = text).writeToURL(url, atomically = true, encoding = NSUTF8StringEncoding, error = null)
        return url
    }

    @Test
    fun `a file is read whole up to the limit and refused past it`() {
        val text = "FPN/RI:F:LFPL:F:LFPK"
        assertEquals(text, readAtMost(file(text), text.length).decodeToString())
        val e = assertFailsWith<FileTooLargeException> { readAtMost(file(text), text.length - 1) }
        assertTrue(e.limit == text.length - 1)
    }
}
