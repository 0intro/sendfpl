package app.sendfpl.ios

import app.sendfpl.Store
import app.sendfpl.cxp.AuthException
import app.sendfpl.cxp.Credential
import app.sendfpl.cxp.credentialOf
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSJSONSerialization
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDefaults
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.dataWithBytes
import platform.Foundation.stringWithContentsOfFile
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecDuplicateItem
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleWhenUnlockedThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.posix.memcpy

/**
 * The iOS app's [Store]. The credential is in the keychain, as a generic password this device
 * alone reads, and only while it is unlocked, so that a backup restores it to this device and no
 * other. The model is in the user defaults, which a backup does carry, as Android's does.
 */
@OptIn(ExperimentalForeignApi::class)
class IosStore : Store {
    override fun loadCredential(): Credential? {
        val json = runCatching { Keychain.read() }.getOrNull() ?: return null
        return runCatching { parseCredential(json) }.getOrNull()
    }

    /** Checks that the file is a credential before anything is stored. */
    override fun saveCredential(json: String) {
        parseCredential(json)
        Keychain.write(json)
    }

    override fun clearCredential() {
        Keychain.delete()
    }

    override var profileProductId: Long
        get() = NSUserDefaults.standardUserDefaults.integerForKey(KEY_PROFILE)
        set(value) = NSUserDefaults.standardUserDefaults.setInteger(value, KEY_PROFILE)

    /**
     * What the app does at launch, before anything reads the store. A keychain item outlives the
     * app, so the first launch of an install clears what an earlier install left there. Then the
     * credential the app carries is adopted when none is stored, so Forget lasts until the app
     * next starts, as on Android. A keychain that refuses a read, as a locked device's does, keeps
     * what it holds. Silent on a missing or malformed bundled file: Send stays disabled and the
     * setup sheet asks for one.
     */
    fun adopt() {
        val defaults = NSUserDefaults.standardUserDefaults
        if (!defaults.boolForKey(KEY_INSTALLED) && Keychain.delete()) defaults.setBool(true, KEY_INSTALLED)
        val stored = runCatching { Keychain.read() }.getOrElse { return }
        if (stored != null) return
        val path = NSBundle.mainBundle.pathForResource(BUNDLED_NAME, BUNDLED_TYPE) ?: return
        val json = NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null) ?: return
        if (runCatching { parseCredential(json) }.isSuccess) runCatching { Keychain.write(json) }
    }

    private companion object {
        const val KEY_PROFILE = "profile_product_id"
        const val KEY_INSTALLED = "installed"

        /** Staged into the app's resources by the release build, as `creds.json`. */
        const val BUNDLED_NAME = "creds"
        const val BUNDLED_TYPE = "json"
    }
}

/**
 * Parse the JSON a user exports from their own APK copy, as the Android app does:
 * `{"user_id": 1, "token": "<96 hex>", "entitlement": "<hex>"}`. Fails naming the field at fault.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun parseCredential(json: String): Credential {
    val data = NSString.create(string = json).dataUsingEncoding(NSUTF8StringEncoding)
    val obj = data?.let { NSJSONSerialization.JSONObjectWithData(it, 0u, null) } as? Map<*, *>
        ?: throw AuthException("not valid JSON")
    for (field in listOf("user_id", "token", "entitlement")) {
        if (!obj.containsKey(field)) throw AuthException("missing \"$field\"")
    }
    val userId = (obj["user_id"] as? NSNumber)?.longLongValue ?: -1L
    return credentialOf(userId, obj["token"].toString(), obj["entitlement"].toString())
}

/** The one keychain item the app keeps. */
@OptIn(ExperimentalForeignApi::class)
private object Keychain {
    private const val SERVICE = "app.sendfpl"
    private const val ACCOUNT = "connext-credential"

    /** The item's text, or null when there is none. Throws when the keychain refuses to say. */
    fun read(): String? = memScoped {
        val query = item()
        CFDictionaryAddValue(query, kSecReturnData, kCFBooleanTrue)
        CFDictionaryAddValue(query, kSecMatchLimit, kSecMatchLimitOne)
        val result = alloc<CFTypeRefVar>()
        val status = SecItemCopyMatching(query, result.ptr)
        CFRelease(query)
        when (status) {
            errSecSuccess -> (CFBridgingRelease(result.value) as? NSData)?.toByteArray()?.decodeToString()
            errSecItemNotFound -> null
            else -> error("the keychain refused a read, status $status")
        }
    }

    /** Stores [text] in place of what is there, in one step, so a refusal leaves the old item. */
    fun write(text: String) {
        val data = text.encodeToByteArray().toNSData()
        val query = item()
        CFDictionaryAddValue(query, kSecAttrAccessible, kSecAttrAccessibleWhenUnlockedThisDeviceOnly)
        add(query, kSecValueData, data)
        var status = SecItemAdd(query, null)
        CFRelease(query)
        if (status == errSecDuplicateItem) {
            val match = item()
            val change = dictionary()
            add(change, kSecValueData, data)
            status = SecItemUpdate(match, change)
            CFRelease(match)
            CFRelease(change)
        }
        check(status == errSecSuccess) { "the keychain refused the credential, status $status" }
    }

    /** Deletes the item, and says whether the keychain is left without one. */
    fun delete(): Boolean {
        val query = item()
        val status = SecItemDelete(query)
        CFRelease(query)
        return status == errSecSuccess || status == errSecItemNotFound
    }

    /** An empty dictionary, which the caller releases. */
    private fun dictionary(): CFMutableDictionaryRef? =
        CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)

    /** A query naming the item, which the caller releases. */
    private fun item(): CFMutableDictionaryRef? {
        val d = dictionary()
        CFDictionaryAddValue(d, kSecClass, kSecClassGenericPassword)
        add(d, kSecAttrService, SERVICE)
        add(d, kSecAttrAccount, ACCOUNT)
        return d
    }

    /** Adds [value], bridged, to [d], which retains it. */
    private fun add(d: CFMutableDictionaryRef?, key: CFStringRef?, value: Any) {
        val v = CFBridgingRetain(value)
        CFDictionaryAddValue(d, key, v)
        CFRelease(v)
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.dataWithBytes(it.addressOf(0), size.convert()) }

@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toByteArray(): ByteArray {
    val out = ByteArray(length.toInt())
    if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}
