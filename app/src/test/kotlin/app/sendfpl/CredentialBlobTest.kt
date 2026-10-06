package app.sendfpl

import android.security.keystore.KeyPermanentlyInvalidatedException
import java.security.InvalidAlgorithmParameterException
import java.security.InvalidKeyException
import java.security.KeyStoreException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.IllegalBlockSizeException
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The half of credential storage that can be tested without a device: [packBlob] and [unpackBlob]
 * frame an IV and a ciphertext into the one preference value the Android Keystore path reads back,
 * and [lostForGood] decides when that value is given up. A one byte slip in the framing would
 * shift the IV and fail authentication at the navigator.
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
     * Every IV length, not just the 12 in use: the blob carries the length so that a provider
     * returning another stays readable.
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
     * A declared IV length that runs past the end must not be trusted: `copyOfRange` would throw
     * inside the store's `load`, whose caller expects null.
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

    /**
     * Cut inside the IV, the framing refuses a blob; cut after it, the framing hands back a prefix
     * of the ciphertext, which the GCM tag check refuses.
     */
    @Test
    fun rejectsATruncatedBlob() {
        val full = Base64.getDecoder().decode(packBlob(iv, ciphertext))
        fun cut(n: Int) = unpackBlob(Base64.getEncoder().encodeToString(full.copyOfRange(0, n)))

        // Inside the IV: refused outright.
        assertNull(cut(6))
        // After a complete IV: what survives is a prefix of what went in, never the bytes read
        // another way, and the tag check refuses it after that.
        val (gotIv, gotCiphertext) = cut(1 + iv.size + 10)!!
        assertArrayEquals(iv, gotIv)
        assertArrayEquals(ciphertext.copyOfRange(0, 10), gotCiphertext)
    }

    /**
     * Only a failure about the blob or its key lets the bundled credential overwrite a stored one,
     * which may be the pilot's own import. A keystore that cannot answer now says neither.
     */
    @Test
    fun aBusyKeystoreIsNotALostCredential() {
        assertTrue(lostForGood(AEADBadTagException()))
        assertTrue(lostForGood(KeyPermanentlyInvalidatedException()))
        assertTrue(lostForGood(UnrecoverableKeyException()))
        assertTrue(lostForGood(InvalidAlgorithmParameterException()))
        // How the keystore's own errors surface from init and doFinal.
        assertFalse(lostForGood(InvalidKeyException("Keystore operation failed")))
        assertFalse(lostForGood(IllegalBlockSizeException()))
        assertFalse(lostForGood(KeyStoreException()))
        assertFalse(lostForGood(ProviderException()))
    }

    /**
     * The bound on what a picker may hand the app. Both pickers accept any MIME type, since a file
     * manager types a route or a credential as it likes, so a mis-picked video reaches this code
     * and must get a message rather than an out of memory kill.
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
