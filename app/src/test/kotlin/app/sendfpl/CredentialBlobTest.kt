package app.sendfpl

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64
import kotlin.random.Random

/**
 * The half of credential storage that can be tested without a device.
 *
 * [packBlob] and [unpackBlob] frame an IV and a ciphertext into the single preference value the
 * Android Keystore path reads back. Nothing here touches the keystore, which is the point: that
 * side needs a handset, and this side is where a mistake of one byte would hand the cipher an IV
 * shifted along and turn a stored credential into an authentication failure at the navigator.
 */
class CredentialBlobTest {

    /** What AES/GCM from the keystore actually hands back: an IV of 12 bytes, a tag of 16. */
    private val iv = ByteArray(12) { it.toByte() }
    private val ciphertext = ByteArray(64) { (it * 7).toByte() }

    @Test
    fun roundTrips() {
        val (gotIv, gotCiphertext) = unpackBlob(packBlob(iv, ciphertext))!!
        assertArrayEquals(iv, gotIv)
        assertArrayEquals(ciphertext, gotCiphertext)
    }

    /**
     * Every IV length, not just the 12 in use. The length is carried in the blob precisely so a
     * provider that returns something else stays readable, and an assertion that only ever sees
     * 12 would not notice if it had been dropped.
     */
    @Test
    fun roundTripsAtEveryIvLength() {
        for (len in 1..255) {
            val anyIv = Random(len).nextBytes(len)
            val (gotIv, gotCiphertext) = unpackBlob(packBlob(anyIv, ciphertext))!!
            assertArrayEquals("iv length $len", anyIv, gotIv)
            assertArrayEquals("iv length $len", ciphertext, gotCiphertext)
        }
    }

    @Test
    fun refusesAnIvThatDoesNotFitTheLengthByte() {
        assertThrows(IllegalArgumentException::class.java) { packBlob(ByteArray(256), ciphertext) }
        assertThrows(IllegalArgumentException::class.java) { packBlob(ByteArray(0), ciphertext) }
    }

    @Test
    fun refusesAnEmptyCiphertext() {
        assertThrows(IllegalArgumentException::class.java) { packBlob(iv, ByteArray(0)) }
    }

    @Test
    fun rejectsWhatIsNotABlob() {
        assertNull(unpackBlob(""))
        assertNull(unpackBlob("not base64 at all !!"))
        // Valid Base64, but nothing was ever framed into it.
        assertNull(unpackBlob(Base64.getEncoder().encodeToString(ByteArray(0))))
    }

    /**
     * A declared IV length that runs past the end must not be trusted.
     *
     * This is the case that would otherwise throw out of `copyOfRange` inside the store's `load`,
     * where the caller expects null and gets an exception instead.
     */
    @Test
    fun rejectsAnIvLengthThatOverrunsTheBuffer() {
        val raw = ByteArray(8).also { it[0] = 200.toByte() }
        assertNull(unpackBlob(Base64.getEncoder().encodeToString(raw)))
    }

    /** A length that consumes the buffer exactly leaves no ciphertext, which GCM never produces. */
    @Test
    fun rejectsABlobWithNoCiphertextLeft() {
        val raw = ByteArray(1 + iv.size).also { it[0] = iv.size.toByte() }
        assertNull(unpackBlob(Base64.getEncoder().encodeToString(raw)))
    }

    /** Truncation has to be caught by the framing, since the tag check never runs on it. */
    @Test
    fun rejectsATruncatedBlob() {
        val full = Base64.getDecoder().decode(packBlob(iv, ciphertext))
        val short = full.copyOfRange(0, 6)
        val (gotIv, gotCiphertext) = unpackBlob(Base64.getEncoder().encodeToString(short))
            ?: run {
                // Truncated inside the IV: refused outright, which is the wanted outcome.
                return
            }
        // Truncated after a complete IV: what survives must still be a prefix of what went in,
        // never a silent reinterpretation of the bytes.
        assertEquals(iv.size, gotIv.size)
        assertArrayEquals(ciphertext.copyOfRange(0, gotCiphertext.size), gotCiphertext)
    }

    /**
     * The bound on what a picker is allowed to hand the app.
     *
     * Both pickers accept any MIME type, because a file manager types a route or a credential as
     * whatever it feels like, so a mis-picked video reaches the same code. Unbounded that was an
     * out of memory kill rather than a message, and the credential path had no bound at all until
     * this test existed to name one.
     */
    @Test
    fun refusesAFileOverTheLimit() {
        val big = ByteArray(CREDENTIAL_FILE_LIMIT + 1)
        val e = assertThrows(IllegalArgumentException::class.java) {
            big.inputStream().readAtMost(CREDENTIAL_FILE_LIMIT)
        }
        // The size has to read as a size. A limit of 64 KiB printed through an "MB" formatter
        // says "0 MB", which reads as a refusal to accept anything at all.
        assertEquals(true, e.message!!.contains("64 kB"))
    }

    /** Exactly the limit is allowed: the refusal is for what exceeds it. */
    @Test
    fun acceptsAFileAtTheLimit() {
        val exact = Random(7).nextBytes(CREDENTIAL_FILE_LIMIT)
        assertArrayEquals(exact, exact.inputStream().readAtMost(CREDENTIAL_FILE_LIMIT))
    }
}
