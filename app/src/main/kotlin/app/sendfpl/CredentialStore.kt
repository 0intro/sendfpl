package app.sendfpl

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.edit
import app.sendfpl.cxp.AuthException
import app.sendfpl.cxp.Credential
import app.sendfpl.cxp.TOKEN_LEN
import org.json.JSONObject
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Storage for the Connext credential.
 *
 * **Every build ships Garmin key material.** The credential is a single fixed application secret
 * compiled into Garmin's own apps, and there is no mechanism by which an independent client is
 * issued one of its own, so a client either presents that secret or authenticates to nothing.
 * Keeping it out of release builds is possible, by putting the asset in the `debug` source set,
 * and the price is a released app that does nothing until its user decompiles an APK. The owner
 * decided the app should carry it, knowing the cost: publishing puts the secret within reach of
 * anyone who downloads the app and looks.
 *
 * A credential the user supplies overrides the bundled one. That is the recovery path rather than
 * the normal one: if Garmin rotates the secret, [save] takes a working replacement without waiting
 * for a new release.
 *
 * Encrypted under a 256 bit AES key in GCM mode held in the Android Keystore, kept in an ordinary
 * [SharedPreferences] file, excluded from backup by the manifest, and never logged, since
 * [Credential.toString] elides both blobs.
 *
 * The keystore is used directly rather than through `androidx.security:security-crypto`, whose
 * 1.1.0 release deprecated its entire API in favour of exactly this. The exposure is the same:
 * that library's key was not bound to user authentication or to an unlocked device either.
 */
class CredentialStore(private val context: Context) {

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Whether a stored credential can actually be read back, not merely whether one was written.
     *
     * The distinction matters on the one failure this storage really has: a key that no longer
     * decrypts, after a keystore reset or a restore onto another device. Reporting *present* there
     * would leave [adoptBundled] convinced the app is provisioned, and the failure would surface
     * much later as a rejected authentication at the navigator. Reporting *absent* costs one
     * decrypt of a few hundred bytes and lets the next start adopt the bundled credential again.
     */
    val isPresent: Boolean get() = load() != null

    fun load(): Credential? {
        val blob = prefs.getString(KEY_BLOB, null) ?: return null
        // Catching rather than checking: a key that has been invalidated throws from deep inside
        // the keystore provider, and every one of those outcomes means the same thing here.
        val json = runCatching { decrypt(blob) }.getOrNull() ?: return null
        return runCatching { parse(json) }.getOrNull()
    }

    /**
     * Stored as the same JSON [parse] accepts, so there is one representation of a credential in
     * the app rather than one for import and another for storage.
     */
    fun save(credential: Credential) {
        val json = JSONObject()
            .put("user_id", credential.userId)
            .put("token", credential.token.toHex())
            .put("entitlement", credential.entitlement.toHex())
            .toString()
        // clear() before the write, not just an overwrite of one key. This file holds the
        // credential and nothing else, so anything else in it is a leftover from a build that
        // stored things differently, and a wipe is the whole of the migration such a build needs.
        prefs.edit {
            clear()
            putString(KEY_BLOB, encrypt(json))
        }
    }

    fun clear() {
        prefs.edit { clear() }
    }

    /**
     * Adopt the credential bundled with the build, when nothing is stored yet.
     *
     * **This is the primary path for every user, not a developer convenience.** The `stageCredential`
     * task puts `assets/creds.json` into every variant, so a fresh install comes up able to
     * authenticate.
     *
     * Runs on every start, which has a consequence worth knowing: [clear] does not stick. Forget
     * removes the stored credential, and the next process start adopts the bundled one again. That
     * is the intended behaviour, falling back to the default rather than bricking the app, and
     * the setup sheet says so rather than implying permanence.
     *
     * Silent on a missing or malformed asset. The failure is visible where it matters: the send
     * button stays disabled and the import sheet still asks for a file.
     */
    fun adoptBundled(): Boolean {
        if (isPresent) return false
        val json = runCatching {
            context.assets.open(BUNDLED_ASSET).use { it.readBytes().decodeToString() }
        }.getOrNull() ?: return false
        val credential = runCatching { parse(json) }.getOrNull() ?: return false
        save(credential)
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
     * The key, created on first use and thereafter fetched by alias.
     *
     * Not exportable, by construction: an AndroidKeyStore secret key is a handle to material the
     * app never sees, so the worst a stolen `connext-credential.xml` gives up is ciphertext.
     *
     * Held for the life of the process. Fetching it is a binder round trip into the keystore and
     * [isPresent] is read on every device refresh, so this is the difference between doing that
     * once and doing it on a path the UI waits for.
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
         * Staged into every variant's assets by the `stageCredential` task, from whichever
         * `creds.json` the build was pointed at.
         *
         * Nothing is checked in under a source set's `assets` directory, and nothing ever should
         * be: the credential is Garmin key material, this repository is public, and a build that
         * has no credential is the normal state of a clone. A build that does have one gets it
         * from an untracked file outside the source tree.
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
         * `{"user_id": 1, "token": "<96 hex>", "entitlement": "<hex>"}`
         *
         * Fails with a message that says which field is wrong, because an import that goes wrong
         * quietly shows up much later as an unexplained auth failure.
         */
        fun parse(json: String): Credential {
            val obj = runCatching { JSONObject(json) }.getOrElse {
                throw AuthException("not valid JSON: ${it.message}")
            }
            for (field in listOf("user_id", "token", "entitlement")) {
                if (!obj.has(field)) throw AuthException("missing \"$field\"")
            }
            val userId = obj.optLong("user_id", -1L)
            if (userId < 0) throw AuthException("\"user_id\" must be zero or a positive integer")
            val token = fromHexChecked(obj.getString("token"), "token")
            if (token.size != TOKEN_LEN) {
                throw AuthException(
                    "\"token\" must be $TOKEN_LEN bytes (${TOKEN_LEN * 2} hex characters), " +
                        "got ${token.size}"
                )
            }
            val entitlement = fromHexChecked(obj.getString("entitlement"), "entitlement")
            if (entitlement.isEmpty()) throw AuthException("\"entitlement\" is empty")
            return Credential(userId, token, entitlement)
        }

        private fun fromHexChecked(s: String, field: String): ByteArray {
            val clean = s.replace(" ", "").replace("\n", "")
            if (clean.length % 2 != 0 || !clean.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                throw AuthException("\"$field\" is not hexadecimal")
            }
            return fromHex(clean)
        }

        private fun fromHex(s: String): ByteArray =
            ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

        private fun ByteArray.toHex(): String =
            joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}

/**
 * Frame an IV and a ciphertext into one preference value: `[1 byte ivLen][iv][ciphertext]`, Base64.
 *
 * One value rather than two, so a write cannot land in part and leave an IV that belongs to a
 * different ciphertext. The length is carried rather than assumed: AES/GCM from the keystore hands
 * back an IV of 12 bytes today, and a stored blob that hardcoded that would be undecryptable the
 * day it did not.
 *
 * `java.util.Base64` rather than `android.util.Base64`, which is API 26 and therefore exactly
 * minSdk, and which unlike the Android one also runs in a JVM unit test. That is the whole reason
 * this framing is a pair of plain functions: the keystore half cannot be tested off a device, and
 * this half is where a mistake of one byte silently corrupts a credential.
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
    // `>=` and not `>`: a length that reaches exactly the end of the buffer leaves no ciphertext,
    // and GCM never produces none, since the tag alone is 16 bytes.
    if (ivLen == 0 || 1 + ivLen >= raw.size) return null
    return raw.copyOfRange(1, 1 + ivLen) to raw.copyOfRange(1 + ivLen, raw.size)
}
