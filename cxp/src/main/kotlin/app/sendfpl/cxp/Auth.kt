package app.sendfpl.cxp

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * CXP authentication.
 *
 * Four numbered messages, mutual and built on nonces, so a captured response cannot be replayed.
 * Recovered from two independently compiled binaries that agree with each other: `IOP_E.dll`
 * (stripped) and `libDCI_CONNEXT.so` (1276 named exports).
 *
 * Four numbered CXP messages:
 * ```
 * client to device   ID 1  AUTH_USER       the entitlement blob, verbatim
 * device to client   ID 2  AUTH_CHALLENGE  16 random bytes
 * client to device   ID 3  AUTH_RESPONSE   36 bytes, see below
 * device to client   ID 4  AUTH_CONFIRM    16 bytes
 *
 * RESPONSE = u32le(user_id) || MD5(token || challenge) || client_nonce
 * CONFIRM  = MD5(token || client_nonce)          (checked locally)
 * ```
 *
 * Note the order: the 48 byte secret is hashed **first**, then the 16 byte challenge, which makes
 * it a MAC with the secret as its prefix. MD5 is dictated by the protocol, not chosen.
 *
 * Authorization: a successful CONFIRM sets bit 2 of the client context's flag byte, after which
 * `cxp_auth_id_is_authorized()` allows any CXP ID. AUTH_USER is not optional: it is what tells
 * the navigator which IDs this credential may use, and flight plan upload sits above the 0xFFF
 * threshold. We never parse it. It is replayed byte by byte.
 */

const val TOKEN_LEN = 48
const val CHALLENGE_LEN = 16
const val NONCE_LEN = 16
const val DIGEST_LEN = 16
const val RESPONSE_LEN = 36

/** CXP IDs of the auth messages, from the name table in `cxp_auth_open()`. */
object AuthId {
    const val USER = 1L
    const val CHALLENGE = 2L
    const val RESPONSE = 3L
    const val CONFIRM = 4L
}

class AuthException(message: String) : Exception(message)

/**
 * Everything needed to authenticate: identity, secret, entitlement.
 *
 * All three are one fixed application credential compiled into the Garmin client, not material
 * created per pairing. Every build now carries it as an asset. See [app.sendfpl.CredentialStore],
 * which records why that reversed and what it costs.
 */
data class Credential(
    val userId: Long,
    /** 48 bytes, hashed as the MD5 prefix. */
    val token: ByteArray,
    /** The AUTH_USER payload, sent verbatim. */
    val entitlement: ByteArray,
) {
    init {
        if (token.size != TOKEN_LEN) {
            throw AuthException("token must be $TOKEN_LEN bytes, got ${token.size}")
        }
        if (entitlement.isEmpty()) {
            throw AuthException("entitlement blob is empty, so AUTH_USER would carry nothing")
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Credential) return false
        return userId == other.userId && token.contentEquals(other.token) &&
            entitlement.contentEquals(other.entitlement)
    }

    override fun hashCode(): Int =
        (userId.hashCode() * 31 + token.contentHashCode()) * 31 + entitlement.contentHashCode()

    /** Never let key material reach a log. */
    override fun toString(): String =
        "Credential(userId=$userId, token=<$TOKEN_LEN bytes>, entitlement=<${entitlement.size} bytes>)"
}

/**
 * `MD5(token || value)`, the keyed digest both sides compute.
 *
 * Mirrors the crypto core (`0x1002aa60` in the firmware, `FUN_00053180` in the client), which is
 * literally init / update(token, 0x30) / update(value, 0x10) / final.
 */
fun digest(token: ByteArray, value: ByteArray): ByteArray {
    if (token.size != TOKEN_LEN) {
        throw AuthException("token must be $TOKEN_LEN bytes, got ${token.size}")
    }
    val md = MessageDigest.getInstance("MD5") // dictated by the protocol, not a choice
    md.update(token)
    md.update(value)
    return md.digest()
}

/**
 * The AUTH_USER payload: the entitlement blob, unmodified.
 *
 * `cxp_auth_open()` case 1 hands the stored blob straight to `CXP_msg_init` with its length, on
 * both the firmware and the client. There is no header and no transformation.
 */
fun buildUser(credential: Credential): ByteArray = credential.entitlement

/** A parsed AUTH_RESPONSE. */
data class AuthResponse(val userId: Long, val digest: ByteArray, val nonce: ByteArray) {
    fun encode(): ByteArray {
        val out = ByteArray(RESPONSE_LEN)
        putU32(out, 0, userId)
        digest.copyInto(out, 4)
        nonce.copyInto(out, 20)
        return out
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AuthResponse) return false
        return userId == other.userId && digest.contentEquals(other.digest) &&
            nonce.contentEquals(other.nonce)
    }

    override fun hashCode(): Int =
        (userId.hashCode() * 31 + digest.contentHashCode()) * 31 + nonce.contentHashCode()

    companion object {
        fun decode(data: ByteArray): AuthResponse {
            if (data.size != RESPONSE_LEN) {
                throw AuthException("response must be $RESPONSE_LEN bytes, got ${data.size}")
            }
            return AuthResponse(getU32(data, 0), data.copyOfRange(4, 20), data.copyOfRange(20, 36))
        }
    }
}

private val random = SecureRandom()

/**
 * Answer an AUTH_CHALLENGE.
 *
 * [nonce] is the client's own 16 random bytes, which the device must hash back as AUTH_CONFIRM.
 * It is a parameter only so captures can be replayed exactly. Leave it null in real use.
 */
fun buildResponse(
    userId: Long,
    token: ByteArray,
    challenge: ByteArray,
    nonce: ByteArray? = null,
): AuthResponse {
    if (challenge.size != CHALLENGE_LEN) {
        throw AuthException("challenge must be $CHALLENGE_LEN bytes, got ${challenge.size}")
    }
    val n = nonce ?: ByteArray(NONCE_LEN).also { random.nextBytes(it) }
    if (n.size != NONCE_LEN) throw AuthException("nonce must be $NONCE_LEN bytes, got ${n.size}")
    return AuthResponse(userId, digest(token, challenge), n)
}

/**
 * The AUTH_CONFIRM the device must return for our nonce.
 *
 * The client precomputes this into `ctx+0x60` while building the response.
 */
fun expectedConfirm(token: ByteArray, nonce: ByteArray): ByteArray {
    if (nonce.size != NONCE_LEN) throw AuthException("nonce must be $NONCE_LEN bytes, got ${nonce.size}")
    return digest(token, nonce)
}

/**
 * Check the device's AUTH_CONFIRM in constant time.
 *
 * The firmware uses `cxp_crypto_memcmp`, an explicit compare in constant time, and
 * MessageDigest's isEqual is the equivalent here.
 */
fun checkConfirm(token: ByteArray, nonce: ByteArray, confirm: ByteArray): Boolean =
    MessageDigest.isEqual(expectedConfirm(token, nonce), confirm)
