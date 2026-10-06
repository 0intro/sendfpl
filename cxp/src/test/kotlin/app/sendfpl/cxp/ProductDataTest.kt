package app.sendfpl.cxp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The identification struct a navigator sends on `0x00001006`.
 *
 * The vector is the exact 24 bytes read off a GPS 175, byte-identical on two units in two sessions,
 * with the version field matching what the panel displayed. A reference implementation pins the
 * same string.
 */
class ProductDataTest {
    private val vector = (
        "f00a0000" + // u32 le 2800
            "475053203137350000000000" + // char[12] "GPS 175"
            "332e323200000000" // char[8]  "3.22"
        ).hex()

    private fun String.hex() = ByteArray(length / 2) {
        substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }

    @Test
    fun `the measured frame decodes`() {
        assertEquals("the vector is the struct's width", PRODUCT_DATA_SIZE, vector.size)
        val got = decodeProductData(vector)
        assertEquals(2800L, got.productId)
        assertEquals("GPS 175", got.name)
        assertEquals("3.22", got.version)
    }

    /** The whole point of reading it: it selects the parser profile. */
    @Test
    fun `the product id selects the profile`() {
        assertEquals(Profiles.GPS175, decodeProductData(vector).profile)
    }

    /**
     * A navigator whose parser has never been read keeps failing the way it did before it
     * identified itself. Falling back to another model's caps would not truncate a name, it would
     * desynchronise the parser and lose the upload.
     */
    @Test
    fun `an unmeasured product id selects nothing`() {
        val unknown = vector.copyOf().also { it[0] = 0x11; it[1] = 0x22 }
        val got = decodeProductData(unknown)
        assertNull("an unknown id must not fall back to a profile", got.profile)
    }

    /** Both text fields are NUL-padded, so a full-width one has no terminator to cut at. */
    @Test
    fun `a full width field survives`() {
        val raw = ByteArray(PRODUCT_DATA_SIZE)
        raw[0] = 1
        "ABCDEFGHIJKL".toByteArray().copyInto(raw, 4)
        "12345678".toByteArray().copyInto(raw, 16)
        val got = decodeProductData(raw)
        assertEquals(1L, got.productId)
        assertEquals("ABCDEFGHIJKL", got.name)
        assertEquals("12345678", got.version)
    }

    @Test
    fun `a payload of the wrong width is refused`() {
        for (n in listOf(0, 23, 25)) {
            assertThrows(IllegalArgumentException::class.java) {
                decodeProductData(ByteArray(n))
            }
        }
    }
}
