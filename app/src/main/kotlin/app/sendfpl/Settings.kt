package app.sendfpl

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Choices the pilot made about the app, and the navigator they chose.
 *
 * Their own files because [DeviceHistory.retainOnly] deletes every key that is not a bonded
 * address. `settings` is the one file this app writes that is not excluded from device-to-device
 * transfer: it holds no address and no key material, and a pilot wants these preferences on the
 * next phone. The chosen navigator's address is kept apart, in `selection`, which is excluded.
 * Cloud backup is off for the whole app (`android:allowBackup="false"`). See
 * `res/xml/data_extraction_rules.xml`.
 */
class Settings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val selection: SharedPreferences =
        context.getSharedPreferences(SELECTION_NAME, Context.MODE_PRIVATE)

    /**
     * Whether the device sheet lists every bonded device rather than only the Garmin ones.
     * Remembered, so a pilot who turned it on to reach a navigator the filter got wrong does not
     * have to find the toggle again before the next flight.
     */
    var showAllDevices: Boolean
        get() = prefs.getBoolean(KEY_SHOW_ALL_DEVICES, false)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_ALL_DEVICES, value) }

    /**
     * The Connext product id of the model last chosen in the picker or identified by a navigator,
     * or 0 for neither. The picker starts there, so the first preview is for the pilot's own
     * model; the id rather than a picker name, because it is what a navigator reports.
     */
    var profileProductId: Long
        get() = prefs.getLong(KEY_PROFILE_PRODUCT_ID, 0L)
        set(value) = prefs.edit { putLong(KEY_PROFILE_PRODUCT_ID, value) }

    /**
     * The address of the navigator last picked in the sheet, or null. A restart aims there while
     * it is bonded; the view model forgets it once it is not.
     */
    var chosenAddress: String?
        get() = selection.getString(KEY_ADDRESS, null)
        set(value) = selection.edit {
            if (value == null) remove(KEY_ADDRESS) else putString(KEY_ADDRESS, value)
        }

    private companion object {
        const val PREFS_NAME = "settings"
        const val KEY_SHOW_ALL_DEVICES = "show_all_devices"
        const val KEY_PROFILE_PRODUCT_ID = "profile_product_id"

        /** Must match the exclusions in res/xml/data_extraction_rules.xml. */
        const val SELECTION_NAME = "selection"
        const val KEY_ADDRESS = "address"
    }
}
