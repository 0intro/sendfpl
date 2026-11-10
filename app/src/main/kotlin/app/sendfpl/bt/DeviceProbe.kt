package app.sendfpl.bt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * `EXTRA_TRANSPORT` by name, since the constant became public SDK well after `minSdk`. Absent
 * reads as classic: the extra is only used to reject an LE link.
 */
private const val EXTRA_TRANSPORT = "android.bluetooth.device.extra.TRANSPORT"
private const val TRANSPORT_LE = 2

/**
 * An active reachability probe: does this device answer right now?
 *
 * [BluetoothDevice.fetchUuidsWithSdp] pages the device but does not report the answer:
 * `ACTION_UUID`'s payload is documented as null on timeout, yet `RemoteDevices.sendUuidIntent`
 * drops the success flag and broadcasts the cache from pairing time, so a switched-off device
 * still answers. That broadcast only says when to give up. The page itself is observed two ways,
 * `ACTION_ACL_CONNECTED` and [isDeviceConnected], because each can fail silently: a wrong receiver
 * export flag drops the broadcast, and reflection can break on an OEM build. A powered GPS 175
 * answers it.
 */
class DeviceProbe(private val context: Context) {

    /**
     * Page [device] and wait for evidence that it is there.
     *
     * Never blocks: the Android calls are quick binder calls, so this is fine on the main
     * dispatcher, where broadcasts arrive. Cancelling unregisters the receiver, but no public API
     * can recall an SDP transaction already started.
     *
     * Probe one device at a time: AOSP starts its `UUID_INTENT_DELAY` timer when the request is
     * made while the native layer runs discoveries one after another, so concurrent probes make
     * the later devices time out.
     */
    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun probe(device: BluetoothDevice, timeoutMillis: Long = DEFAULT_TIMEOUT_MS): Probe {
        if (isDeviceConnected(device)) return Probe.Live(nowMillis())

        // Conflated, so the receiver never blocks and a second signal is harmless.
        val signal = Channel<Boolean>(Channel.CONFLATED)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent ?: return
                val from = IntentCompat.getParcelableExtra(
                    intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java,
                )
                // These are global broadcasts: results for every other device arrive here too.
                if (from?.address != device.address) return

                when (intent.action) {
                    BluetoothDevice.ACTION_ACL_CONNECTED ->
                        if (intent.getIntExtra(EXTRA_TRANSPORT, -1) != TRANSPORT_LE) {
                            signal.trySend(true)
                        }

                    // Time to give up, but the page's link may still be up: L2CAP holds an idle
                    // one for about four seconds.
                    BluetoothDevice.ACTION_UUID -> signal.trySend(isDeviceConnected(device))
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_UUID)
        }

        // RECEIVER_EXPORTED: both actions are <protected-broadcast>, so no other app can forge
        // them, and NOT_EXPORTED would silently drop them. On API 33+ its exemption covers only
        // root and uid 1000 while Bluetooth runs as 1002, and below that ContextCompat implements
        // it as a signature permission the Bluetooth process never holds.
        val registered = runCatching {
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        }.isSuccess
        if (!registered) return Probe.Silent(nowMillis(), Probe.Reason.REQUEST_REFUSED)

        try {
            // Registered first: a cached answer can be broadcast before this call even returns.
            val asked = runCatching { device.fetchUuidsWithSdp() }.getOrDefault(false)
            if (!asked) return Probe.Silent(nowMillis(), Probe.Reason.REQUEST_REFUSED)

            // One last direct look on timeout, in case the broadcast was the detector that failed.
            val live = withTimeoutOrNull(timeoutMillis) { signal.receive() }
                ?: isDeviceConnected(device)

            return if (live) {
                Probe.Live(nowMillis())
            } else {
                Probe.Silent(nowMillis(), Probe.Reason.TIMED_OUT)
            }
        } finally {
            // Every path, including cancellation. Unregistering twice throws.
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    companion object {
        /**
         * Past AOSP's `UUID_INTENT_DELAY` of six seconds, itself just past the 5.12 s classic page
         * timeout, so the `ACTION_UUID` tick normally arrives first.
         */
        const val DEFAULT_TIMEOUT_MS = 8_000L
    }
}
