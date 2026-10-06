package app.sendfpl.cxp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * Ported from the internal tests of the reference implementation these were first written against.
 *
 * The digests below are output captured from the working Python implementation for a dummy
 * token, not the real credential, which this repository does not contain.
 */
class AuthTest {

    private val token = ByteArray(TOKEN_LEN) { it.toByte() }
    private val challenge = ByteArray(16) { it.toByte() }
    private val nonce = hex("ffeeddccbbaa99887766554433221100")

    @Test
    fun `response matches the reference bytes exactly`() {
        val r = buildResponse(1L, token, challenge, nonce)
        assertEquals(
            "0100000000c6748d505d4f4173d3c494c2053e34ffeeddccbbaa99887766554433221100",
            r.encode().toHex(),
        )
    }

    @Test
    fun `response is u32le user_id then digest then nonce`() {
        val raw = buildResponse(1L, token, challenge, nonce).encode()
        assertEquals(RESPONSE_LEN, raw.size)
        assertEquals(1L, getU32(raw, 0))
        assertArrayEquals(
            MessageDigest.getInstance("MD5").digest(token + challenge),
            raw.copyOfRange(4, 20),
        )
        assertArrayEquals(nonce, raw.copyOfRange(20, 36))
    }

    @Test
    fun `response round trips`() {
        val r = buildResponse(1L, token, challenge, nonce)
        assertEquals(r, AuthResponse.decode(r.encode()))
    }

    @Test
    fun `the device proves itself by hashing back our nonce`() {
        val confirm = expectedConfirm(token, nonce)
        assertEquals("16eb566eb6784ad2b2490c8807e76cdc", confirm.toHex())
        assertTrue(checkConfirm(token, nonce, confirm))
        assertFalse(checkConfirm(token, nonce, ByteArray(16)))
        assertFalse(checkConfirm(ByteArray(TOKEN_LEN), nonce, confirm))
    }

    @Test
    fun `the secret is hashed first - prefix, not suffix`() {
        val md = MessageDigest.getInstance("MD5")
        assertFalse(digest(token, challenge).contentEquals(md.digest(challenge + token)))
    }

    @Test
    fun `AUTH_USER is the entitlement blob verbatim`() {
        val entitlement = "ENTITLEMENT BLOB".toByteArray()
        val cred = Credential(1L, token, entitlement)
        assertArrayEquals(entitlement, buildUser(cred))
    }

    @Test
    fun `a malformed credential is refused at construction`() {
        assertTrue(runCatching { Credential(1L, ByteArray(10), hex("00")) }
            .exceptionOrNull() is AuthException)
        assertTrue(runCatching { Credential(1L, token, ByteArray(0)) }
            .exceptionOrNull() is AuthException)
    }

    @Test
    fun `a challenge or nonce of the wrong size is refused`() {
        assertTrue(runCatching { buildResponse(1L, token, ByteArray(15)) }
            .exceptionOrNull() is AuthException)
        assertTrue(runCatching { expectedConfirm(token, ByteArray(15)) }
            .exceptionOrNull() is AuthException)
    }

    @Test
    fun `toString never leaks key material`() {
        val s = Credential(1L, token, "secret entitlement".toByteArray()).toString()
        assertFalse(s.contains("secret entitlement"))
        assertFalse(s.contains(token.toHex()))
    }

    @Test
    fun `auth ids are the numbers cxp_auth_open dispatches on`() {
        assertEquals(1L, AuthId.USER)
        assertEquals(2L, AuthId.CHALLENGE)
        assertEquals(3L, AuthId.RESPONSE)
        assertEquals(4L, AuthId.CONFIRM)
    }
}
