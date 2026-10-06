package app.sendfpl

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * When each navigator last accepted a flight plan, which tells two identically named units apart
 * and is an ordering key in `deviceOrder`.
 *
 * Unencrypted, as it holds no key material, but named in both blocks of
 * `res/xml/data_extraction_rules.xml` so no Bluetooth address is copied to another device. Wall
 * clock time, unlike probe freshness, because it must survive a reboot and be shown as a date.
 */
class DeviceHistory(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Address to the epoch milliseconds of the last successful upload. */
    fun lastUsed(): Map<String, Long> =
        prefs.all.entries.mapNotNull { (k, v) -> (v as? Long)?.let { k to it } }.toMap()

    fun recordUpload(address: String, atMillis: Long = System.currentTimeMillis()) {
        prefs.edit { putLong(address, atMillis) }
    }

    /** Drop devices that are no longer bonded, so a unit you unpaired does not linger here. */
    fun retainOnly(addresses: Set<String>) {
        val stale = prefs.all.keys - addresses
        if (stale.isEmpty()) return
        prefs.edit { stale.forEach { remove(it) } }
    }

    private companion object {
        const val PREFS_NAME = "device_history"
    }
}
