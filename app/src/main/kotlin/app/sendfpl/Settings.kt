package app.sendfpl

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Choices the pilot made about the app itself, as opposed to about a route or a navigator.
 *
 * Its own preferences file rather than a corner of [DeviceHistory]'s: that one is keyed by
 * Bluetooth address, `lastUsed` reads only the `Long` values, and `retainOnly` deletes every key
 * that is not a bonded address, so a flag parked there would vanish the first time a device was
 * unpaired.
 *
 * **Deliberately not excluded from backup**, unlike the other two files this app writes. It holds
 * no address and no key material, and a pilot who set a preference on one phone wants it on the
 * next. `res/xml/data_extraction_rules.xml` says which files are excluded and why.
 */
class Settings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Whether the device sheet lists every bonded device rather than only the Garmin ones.
     *
     * Off by default, so a fresh install shows the short list. Remembered because a pilot who
     * turned it on to reach a navigator the filter got wrong should not have to find the toggle
     * again before the next flight.
     */
    var showAllDevices: Boolean
        get() = prefs.getBoolean(KEY_SHOW_ALL_DEVICES, false)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_ALL_DEVICES, value) }

    private companion object {
        const val PREFS_NAME = "settings"
        const val KEY_SHOW_ALL_DEVICES = "show_all_devices"
    }
}
