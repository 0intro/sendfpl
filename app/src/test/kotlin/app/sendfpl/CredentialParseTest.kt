package app.sendfpl

import app.sendfpl.cxp.AuthException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The checks a credential file passes, field by field, as in a reference implementation. */
class CredentialParseTest {

    private val token = (0 until 48).joinToString("") { it.toString(16).padStart(2, '0') }

    private fun refusal(userId: Long, token: String, entitlement: String): String? =
        (runCatching { credentialOf(userId, token, entitlement) }.exceptionOrNull() as? AuthException)?.message

    @Test
    fun `a well formed credential loads, whitespace in the hex and all`() {
        val c = credentialOf(4294967295, token.chunked(32).joinToString("\n  "), "de ad be ef")
        assertEquals(4294967295, c.userId)
        assertEquals(48, c.token.size)
        assertEquals(4, c.entitlement.size)
    }

    @Test
    fun `each field is checked and named`() {
        assertTrue(refusal(-1, token, "aa")!!.contains("user_id"))
        assertTrue(refusal(4294967296, token, "aa")!!.contains("user_id"))
        assertTrue(refusal(1, "00010203", "aa")!!.contains("48 bytes"))
        assertTrue(refusal(1, token, "")!!.contains("entitlement"))
        assertTrue(refusal(1, token, "nothex")!!.contains("hexadecimal"))
        // Arabic-Indic digits are digits to isDigit(), and are not hexadecimal.
        assertTrue(refusal(1, token, "١٢")!!.contains("hexadecimal"))
    }
}
