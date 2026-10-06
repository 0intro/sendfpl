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

    /**
     * The Connext product id of the navigator model last chosen in the picker or last identified
     * by a navigator, or 0 when there has been neither.
     *
     * The picker starts there, so a route is previewed for the pilot's own model from the first
     * one: the models differ in what they are sent for a user waypoint, and a preview for the other
     * one would show a route that is not the one sent. The id rather than a picker name, because
     * it is what a navigator reports and what outlives a renamed chip.
     */
    var profileProductId: Long
        get() = prefs.getLong(KEY_PROFILE_PRODUCT_ID, 0L)
        set(value) = prefs.edit { putLong(KEY_PROFILE_PRODUCT_ID, value) }

    private companion object {
        const val PREFS_NAME = "settings"
        const val KEY_SHOW_ALL_DEVICES = "show_all_devices"
        const val KEY_PROFILE_PRODUCT_ID = "profile_product_id"
    }
}
