package app.sendfpl.bt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.UUID

/**
 * A paired device as one [GarminDevices.bonded] pass saw it: a snapshot, not a live handle. Probe
 * results are kept by address in the view model, so they survive this list being rebuilt. The
 * fields mean what they mean in [DeviceFacts].
 */
data class PairedDevice(
    val name: String,
    val address: String,
    val device: BluetoothDevice,
    val cachedUuids: List<UUID>? = null,
    val aclConnected: Boolean = false,
)

/** Reduce a snapshot to the facts, free of Android, that the classifier reads. */
fun PairedDevice.facts(probe: Probe = Probe.NotProbed): DeviceFacts =
    DeviceFacts(
        address = address,
        name = name,
        cachedUuids = cachedUuids,
        probe = probe,
        aclConnected = aclConnected,
    )

/**
 * Enumeration of bonded devices. Pairing belongs in Android Settings, where the navigator's own
 * confirmation prompt appears on the panel, so this lists bonds rather than scanning.
 */
class GarminDevices(private val context: Context) {

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    val isEnabled: Boolean get() = adapter?.isEnabled == true

    fun hasPermissions(): Boolean = requiredPermissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Every bonded device, unfiltered: this knows only the name, which the user can set to
     * anything, so hiding and ranking are decided from evidence by [DeviceStatus.garmin] and
     * [deviceOrder]. Sorted only so the same bonds come back in the same sequence: `bondedDevices`
     * is a `Set` with no defined order, and a list that reshuffles would recompose the sheet.
     *
     * Blocking: this makes binder calls for every bonded device, so call it off the main thread.
     */
    @SuppressLint("MissingPermission")
    fun bonded(): List<PairedDevice> {
        if (!hasPermissions()) return emptyList()
        val devices = adapter?.bondedDevices ?: return emptyList()
        return devices
            .map {
                PairedDevice(
                    name = it.name ?: it.address,
                    address = it.address,
                    device = it,
                    cachedUuids = cachedUuids(it),
                    aclConnected = isDeviceConnected(it),
                )
            }
            .sortedWith(compareBy({ it.name }, { it.address }))
    }

    /**
     * [bonded], or null when the bonds cannot be read: without the permission, or with the radio
     * off at either end of the read. `getBondedDevices` answers an empty set in every state but
     * on, turning on included, and that is not every bond removed.
     */
    fun bondedIfReadable(): List<PairedDevice>? {
        if (!hasPermissions() || !isEnabled) return null
        val list = bonded()
        return list.takeIf { isEnabled }
    }

    @SuppressLint("MissingPermission")
    fun cancelDiscovery() {
        if (hasPermissions()) runCatching { adapter?.cancelDiscovery() }
    }

    /**
     * The services this device advertised when it was paired. `getUuids` is a local cache that
     * pages nothing, and SDP does not run again on its own for a bonded device, so it can be
     * stale. A throw, from a permission revoked during a session, reads as null: no evidence.
     * Sorted and deduplicated because [PairedDevice] equality gates `StateFlow` emission.
     */
    @SuppressLint("MissingPermission")
    private fun cachedUuids(device: BluetoothDevice): List<UUID>? = runCatching {
        device.uuids?.map { it.uuid }?.distinct()?.sortedBy { it.toString() }
    }.getOrNull()

    companion object {
        /** Permissions this Android version needs before any of the above will work. */
        val requiredPermissions: List<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
            } else {
                listOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN)
            }
    }
}

/**
 * Whether a link to [device] exists right now. False, or a throw, means no link, never
 * "unreachable": an idle, usable navigator reads false.
 *
 * `BluetoothDevice.isConnected()` is hidden but not restricted: `@SystemApi`, not
 * `@UnsupportedAppUsage`, so no `maxTargetSdk`, and flagged `sdk` in `hiddenapi-flags.csv` on
 * every release from 11 to 16. `AdapterService` checks only `BLUETOOTH_CONNECT`. No public call
 * covers SPP: `BluetoothManager.getConnectedDevices` is GATT only, and `getProfileConnectionState`
 * has no SPP entry. Wrapped anyway, because an OEM framework is not AOSP.
 */
internal fun isDeviceConnected(device: BluetoothDevice): Boolean = runCatching {
    BluetoothDevice::class.java.getMethod("isConnected").invoke(device) as Boolean
}.getOrDefault(false)
