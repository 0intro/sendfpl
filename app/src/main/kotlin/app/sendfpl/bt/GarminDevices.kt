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
 * A paired device the app may be able to talk to, as it looked at one instant.
 *
 * Everything here is read in a single [GarminDevices.bonded] pass, so a `PairedDevice` is a
 * snapshot rather than a live handle. Probe results are *not* part of it: they are keyed by
 * address in the view model, so they survive this list being rebuilt.
 */
data class PairedDevice(
    val name: String,
    val address: String,
    val device: BluetoothDevice,
    /** The SDP cache from pairing time. `null` means it had nothing to say, not "no services". */
    val cachedUuids: List<UUID>? = null,
    /** A link exists right now, possibly another app's. Never means "reachable". */
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
 * Enumeration of bonded devices.
 *
 * Pairing itself belongs in Android Settings, where the navigator's own confirmation prompt
 * appears on the panel, so this lists bonded devices rather than scanning.
 */
class GarminDevices(private val context: Context) {

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    val isEnabled: Boolean get() = adapter?.isEnabled == true

    /** Permissions this Android version needs before any of the below will work. */
    val requiredPermissions: List<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            listOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN)
        }

    fun hasPermissions(): Boolean = requiredPermissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Every bonded device, in a stable order.
     *
     * **Enumeration returns everything and decides nothing.** The sheet does hide rows that are
     * positively not Garmin, but it decides that per device from `DeviceStatus.garmin`, states how
     * many it hid and offers a way back. Filtering here instead would put that judgement in the one
     * place with no evidence to make it on and no way for a pilot to overrule it, and this class
     * knows only the name, which the user can set to anything.
     *
     * The order that is a hint lives in [deviceOrder], not here, because it reads probe verdicts and the
     * upload history, neither of which this class has, and keeping it in a file with no `android.*`
     * imports is what makes it testable. What is left here is only the guarantee that the same
     * bonds come back in the same sequence every time: `bondedDevices` is a `Set` with no defined
     * iteration order, and a list that reshuffles on its own would recompose the sheet for nothing.
     *
     * Blocking: this makes two binder calls per bonded device, so call it off the main thread.
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

    @SuppressLint("MissingPermission")
    fun cancelDiscovery() {
        if (hasPermissions()) runCatching { adapter?.cancelDiscovery() }
    }

    /**
     * The services this device advertised when it was paired.
     *
     * `getUuids` is a local cache, not a query: it costs nothing and pages nothing, and SDP is not
     * run again on its own for a device that is already bonded, so it can be stale. A throw, from a
     * permission revoked during a session, and a null are the same answer here: no evidence, which
     * [capabilityOf] reads as [Capability.UNKNOWN] and never as "no Connext".
     *
     * Sorted and deduplicated because [PairedDevice] equality gates `StateFlow` emission, and a
     * list whose order wandered would recompose the sheet for no reason.
     */
    @SuppressLint("MissingPermission")
    private fun cachedUuids(device: BluetoothDevice): List<UUID>? = runCatching {
        device.uuids?.map { it.uuid }?.distinct()?.sortedBy { it.toString() }
    }.getOrNull()
}

/**
 * Whether a link to [device] exists right now.
 *
 * `BluetoothDevice.isConnected()` is hidden but not restricted: it is `@SystemApi` rather than
 * `@UnsupportedAppUsage`, so it carries no `maxTargetSdk`, and it is flagged `sdk`, the
 * unrestricted bucket, in `hiddenapi-flags.csv` on every release from 11 to 16. Its binder
 * implementation in `AdapterService` checks only `BLUETOOTH_CONNECT`, which we hold. There is no
 * public equivalent: `BluetoothManager.getConnectedDevices` covers GATT only, and
 * `getProfileConnectionState` has no entry for SPP.
 *
 * Still wrapped, because an OEM framework is not AOSP. A throw and a `false` mean the same thing to
 * the caller *no link right now*, which is **not** the same as "unreachable": classic Bluetooth
 * keeps no standing link, so an idle, perfectly usable navigator reads false.
 */
internal fun isDeviceConnected(device: BluetoothDevice): Boolean = runCatching {
    BluetoothDevice::class.java.getMethod("isConnected").invoke(device) as Boolean
}.getOrDefault(false)
