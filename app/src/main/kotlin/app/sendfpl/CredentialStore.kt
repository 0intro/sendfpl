package app.sendfpl

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.core.content.edit
import app.sendfpl.cxp.AuthException
import app.sendfpl.cxp.Credential
import app.sendfpl.cxp.TOKEN_LEN
import java.security.InvalidAlgorithmParameterException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/**
 * Storage for the Connext credential.
 *
 * Every build carries Garmin key material: the credential is one fixed application secret
 * compiled into Garmin's own apps, and no independent client is issued its own. Without it a
 * release would do nothing until its user decompiled an APK; with it, anyone who downloads the
 * app can extract the secret. A credential the user imports overrides the bundled one, so a
 * rotated secret can be replaced without a new release.
 *
 * Encrypted with a 256 bit AES-GCM key held in the Android Keystore, kept in an ordinary
 * [SharedPreferences] file excluded from backup and device transfer, and never logged by a release
 * build: [Credential.toString] elides both blobs, and only a debug build dumps the link bytes,
 * which carry the entitlement as AUTH_USER. The keystore is used directly because
 * `androidx.security:security-crypto` 1.1.0 deprecated its API in favour of this, and its key was
 * not bound to user authentication or an unlocked device either.
 */
class CredentialStore(private val context: Context) {

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Whether a credential can actually be loaded, not merely whether one was written, so a key
     * that no longer decrypts lets [adoptBundled] adopt the bundled one again.
     */
    val isPresent: Boolean get() = load() != null

    /** The bundled credential, held in memory when the keystore would not store it or read it. */
    @Volatile
    private var unstored: Credential? = null

    fun load(): Credential? = stored().getOrNull() ?: unstored

    /**
     * The stored credential, or null when there is none or it can never be read. A failure is the
     * keystore not answering now, which says nothing about the blob. See [lostForGood].
     */
    private fun stored(): Result<Credential?> {
        val blob = prefs.getString(KEY_BLOB, null) ?: return Result.success(null)
        val json = try {
            decrypt(blob)
        } catch (e: Exception) {
            return if (lostForGood(e)) Result.success(null) else Result.failure(e)
        }
        return Result.success(json?.let { runCatching { parse(it) }.getOrNull() })
    }

    /** Stored as the JSON [parse] accepts, the one representation of a credential in the app. */
    fun save(credential: Credential) {
        val json = JSONObject()
            .put("user_id", credential.userId)
            .put("token", credential.token.toHex())
            .put("entitlement", credential.entitlement.toHex())
            .toString()
        // The file holds the credential and nothing else, so anything else in it is a leftover.
        prefs.edit {
            clear()
            putString(KEY_BLOB, encrypt(json))
        }
    }

    fun clear() {
        unstored = null
        prefs.edit { clear() }
    }

    /**
     * Adopt the credential bundled with the build when none can be loaded, which is how every
     * fresh install comes up able to authenticate. [clear] therefore lasts only until this runs
     * again. Silent on a missing or malformed asset: Send stays disabled and the setup sheet still
     * asks for a file. A blob the keystore cannot read now is kept, since it may be the pilot's own
     * import, and the bundled credential serves this run from memory.
     */
    fun adoptBundled(): Boolean {
        val stored = stored()
        if ((stored.getOrNull() ?: unstored) != null) return false
        val json = runCatching {
            context.assets.open(BUNDLED_ASSET).use { it.readBytes().decodeToString() }
        }.getOrNull() ?: return false
        val credential = runCatching { parse(json) }.getOrNull() ?: return false
        if (stored.isFailure) {
            unstored = credential
            return true
        }
        // A keystore that refuses leaves the credential in memory rather than failing every start.
        runCatching { save(credential) }.onFailure { unstored = credential }
        return true
    }

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        // The IV comes from the cipher, never from us: a keystore key uses randomised encryption
        // by default, which is what makes supplying one an error rather than a choice.
        return packBlob(cipher.iv, cipher.doFinal(plaintext.encodeToByteArray()))
    }

    private fun decrypt(blob: String): String? {
        val (iv, ciphertext) = unpackBlob(blob) ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(ciphertext).decodeToString()
    }

    /**
     * The key, created on first use and fetched by alias after. An AndroidKeyStore key is a handle
     * to material the app never sees, so a stolen `connext-credential.xml` gives up only
     * ciphertext. Held, because fetching it is a binder round trip and [isPresent] is read on
     * every refresh.
     */
    private val secretKey: SecretKey by lazy { loadOrCreateKey() }

    private fun loadOrCreateKey(): SecretKey {
        val keystore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keystore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        /**
         * Staged into each variant's assets by its `stage<Variant>Credential` task from an
         * untracked `creds.json`. Never check one in under `assets`: this repository is public.
         */
        private const val BUNDLED_ASSET = "creds.json"

        /** Must match the exclusion in res/xml/data_extraction_rules.xml. */
        const val PREFS_NAME = "connext-credential"

        private const val KEY_BLOB = "credential"

        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "connext-credential"
        private const val KEY_BITS = 256
        private const val TAG_BITS = 128
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        /**
         * Parse the JSON a user exports from their own APK copy:
         * `{"user_id": 1, "token": "<96 hex>", "entitlement": "<hex>"}`. Fails naming the field at
         * fault, since a quiet failure would surface later as an unexplained auth failure.
         */
        fun parse(json: String): Credential {
            val obj = runCatching { JSONObject(json) }.getOrElse {
                throw AuthException("not valid JSON: ${it.message}")
            }
            for (field in listOf("user_id", "token", "entitlement")) {
                if (!obj.has(field)) throw AuthException("missing \"$field\"")
            }
            return credentialOf(obj.optLong("user_id", -1L), obj.getString("token"), obj.getString("entitlement"))
        }

        private fun ByteArray.toHex(): String =
            joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }
}

/**
 * A credential from its three JSON fields, checked, naming the field at fault. Apart from
 * [CredentialStore.parse] so it runs in a JVM test, where `org.json` is a stub.
 */
internal fun credentialOf(userId: Long, token: String, entitlement: String): Credential {
    if (userId !in 0..0xFFFFFFFFL) {
        throw AuthException("\"user_id\" must be an integer from 0 to ${0xFFFFFFFFL}")
    }
    val tokenBytes = fromHexChecked(token, "token")
    if (tokenBytes.size != TOKEN_LEN) {
        throw AuthException(
            "\"token\" must be $TOKEN_LEN bytes (${TOKEN_LEN * 2} hex characters), got ${tokenBytes.size}"
        )
    }
    val entitlementBytes = fromHexChecked(entitlement, "entitlement")
    if (entitlementBytes.isEmpty()) throw AuthException("\"entitlement\" is empty")
    return Credential(userId, tokenBytes, entitlementBytes)
}

/** Hex, tolerating the spaces and line breaks a long value picks up when pasted by hand. */
private fun fromHexChecked(s: String, field: String): ByteArray {
    val clean = s.filterNot { it == ' ' || it == '\n' || it == '\r' || it == '\t' }
    if (clean.length % 2 != 0 || !clean.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) {
        throw AuthException("\"$field\" is not hexadecimal")
    }
    return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

/**
 * Whether [e], from reading the stored blob, means it can never be read: the tag fails, so the
 * key or the blob changed, the key is gone, or the blob's IV is one the cipher refuses. Anything
 * else is the keystore failing now: `doFinal` reports most of its own errors as
 * `IllegalBlockSizeException`, and `init` as a plain `InvalidKeyException`.
 */
internal fun lostForGood(e: Throwable): Boolean =
    e is AEADBadTagException ||
        e is KeyPermanentlyInvalidatedException ||
        e is UnrecoverableKeyException ||
        e is InvalidAlgorithmParameterException

/**
 * Frame an IV and a ciphertext into one preference value, so a write cannot land in part:
 * `[1 byte ivLen][iv][ciphertext]`, Base64. The IV length is carried rather than assumed to be
 * 12. `java.util.Base64` (API 26, the minSdk) rather than Android's, so this half runs in a JVM
 * test: the keystore half cannot be tested off a device.
 */
internal fun packBlob(iv: ByteArray, ciphertext: ByteArray): String {
    require(iv.size in 1..255) { "iv is ${iv.size} bytes, which does not fit in the length byte" }
    require(ciphertext.isNotEmpty()) { "empty ciphertext" }
    val raw = ByteArray(1 + iv.size + ciphertext.size)
    raw[0] = iv.size.toByte()
    iv.copyInto(raw, 1)
    ciphertext.copyInto(raw, 1 + iv.size)
    return Base64.getEncoder().encodeToString(raw)
}

/** The inverse of [packBlob]. Null for anything that is not a blob this wrote. */
internal fun unpackBlob(blob: String): Pair<ByteArray, ByteArray>? {
    val raw = runCatching { Base64.getDecoder().decode(blob) }.getOrNull() ?: return null
    if (raw.isEmpty()) return null
    val ivLen = raw[0].toInt() and 0xFF
    // `>=`: GCM output is never empty, since the tag alone is 16 bytes.
    if (ivLen == 0 || 1 + ivLen >= raw.size) return null
    return raw.copyOfRange(1, 1 + ivLen) to raw.copyOfRange(1 + ivLen, raw.size)
}
