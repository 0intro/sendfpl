package app.sendfpl.cxp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Md5Test {

    private fun ByteArray.hex() = joinToString("") { hex(it.toInt() and 0xFF, 2) }

    /** The test suite of RFC 1321, appendix A.5. */
    @Test
    fun `the RFC's suite`() {
        val suite = listOf(
            "" to "d41d8cd98f00b204e9800998ecf8427e",
            "a" to "0cc175b9c0f1b6a831c399e269772661",
            "abc" to "900150983cd24fb0d6963f7d28e17f72",
            "message digest" to "f96b697d7cb7938d525a2f31aaf161d0",
            "abcdefghijklmnopqrstuvwxyz" to "c3fcd3d76192e4007dfb496cca67e13b",
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789" to
                "d174ab98d277d9f5a5611c2c9f419d9f",
            "1234567890".repeat(8) to "57edf4a22be3c955ac49da2e2107b67a",
        )
        for ((text, want) in suite) assertEquals(want, md5(text.encodeToByteArray()).hex(), text)
    }

    @Test
    fun `constantTimeEquals compares contents and lengths`() {
        assertTrue(constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)))
        assertTrue(constantTimeEquals(ByteArray(0), ByteArray(0)))
        assertFalse(constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 4)))
        assertFalse(constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2)))
        assertFalse(constantTimeEquals(byteArrayOf(-128), byteArrayOf(0)))
    }
}
