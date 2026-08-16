package app.sendfpl

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * When each navigator last accepted a flight plan.
 *
 * This is what tells two identically named units apart, and it is an ordering key in
 * `deviceOrder`, which is where that argument is made in full.
 *
 * Ordinary [SharedPreferences], not the encrypted store [CredentialStore] uses: this holds no key
 * material. It is still kept off any other device, because a Bluetooth address is worth not
 * copying somewhere by accident, and that takes **two** exclusions rather than one:
 * `android:allowBackup="false"` stops the cloud backup, and on Android 12 and newer a
 * device-to-device transfer ignores it entirely. This file is named in both blocks of
 * `res/xml/data_extraction_rules.xml`, and a new preferences file needs a line in both.
 *
 * Timestamps come from the wall clock, [System.currentTimeMillis], unlike the monotonic clock
 * used for probe freshness: this one has to survive a reboot and be rendered as a date, and
 * neither is something `nanoTime` can do.
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
