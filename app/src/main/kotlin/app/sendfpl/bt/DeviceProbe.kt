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
 * `EXTRA_TRANSPORT` by name.
 *
 * The constant only became public SDK well after this app's `minSdk`, and the value is stable, so
 * the string is read directly rather than gating on API level. Absent means "not stated", which is
 * treated as classic, and the extra is only ever used to *reject* an LE link.
 */
private const val EXTRA_TRANSPORT = "android.bluetooth.device.extra.TRANSPORT"
private const val TRANSPORT_LE = 2

/**
 * An active reachability probe: does this device answer *right now*?
 *
 * Classic Bluetooth holds no standing ACL link, so reachability cannot be read anywhere. It has to
 * be asked, and asking pages the radio. [BluetoothDevice.fetchUuidsWithSdp] asks. What it will not
 * do is *tell you the answer*: `ACTION_UUID`'s payload is documented as null on timeout, but
 * `RemoteDevices.sendUuidIntent` drops the success flag and broadcasts the cache built at pairing
 * time, so a bonded device that is switched off still answers with something. The broadcast is
 * therefore used only as the tick that says to give up, which AOSP fires at a fixed
 * `UUID_INTENT_DELAY` of six seconds.
 *
 * The page it forces is what actually gets observed, two independent ways:
 *
 *  * the public `ACTION_ACL_CONNECTED` broadcast, and
 *  * [isDeviceConnected], the reflective `BluetoothDevice.isConnected()`.
 *
 * Both are used because each has a way to fail silently that has been traced through AOSP but not
 * executed on a handset: a wrong receiver export flag drops the broadcast, and reflection can
 * break on an OEM build. Reading the same fact two ways is the technique this repository already
 * relies on elsewhere, and here it costs about ten lines.
 *
 * **Not validated against real hardware.**
 */
class DeviceProbe(private val context: Context) {

    /**
     * Page [device] and wait for evidence that it is there.
     *
     * Suspends, but never blocks: both Android calls are quick binder calls and everything else is
     * a suspension, so this is fine on the main dispatcher, which is also where broadcasts are
     * delivered, saving a `Handler` and a thread hop.
     *
     * Cancellable. Cancelling unregisters the receiver and stops waiting. It cannot recall an SDP
     * transaction the stack has already started, and no public API can.
     *
     * Probe one device at a time. AOSP starts its timer of six seconds when the request is *made*
     * while the native layer queues discoveries and runs them one after another, so concurrent
     * probes make the later devices report timeouts they never earned.
     */
    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun probe(device: BluetoothDevice, timeoutMillis: Long = DEFAULT_TIMEOUT_MS): Probe {
        // A link already exists, so the device is certainly there. Costs nothing and pages nothing.
        if (isDeviceConnected(device)) return Probe.Live(nowMillis())

        // Conflated, so the receiver can never block or fail to deliver, and a second signal after
        // the first is simply dropped. This is why nothing here can be resumed twice.
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

                    // The tick that says to give up. Its payload says nothing, but the link it
                    // would have needed may still be up, since L2CAP holds an idle one for about
                    // four seconds.
                    BluetoothDevice.ACTION_UUID -> signal.trySend(isDeviceConnected(device))
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_UUID)
        }

        // RECEIVER_EXPORTED, not RECEIVER_NOT_EXPORTED. Both actions are <protected-broadcast>, so
        // no other app can forge them, and NOT_EXPORTED would silently drop them on both
        // supported paths: on API 33+ the unexported exemption is granted only to root and uid
        // 1000 while Bluetooth runs as 1002, and below that ContextCompat implements it as a
        // signature permission the Bluetooth process will never hold. The symptom of getting this
        // wrong is every probe timing out.
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
         * Comfortably past AOSP's own `UUID_INTENT_DELAY` of six seconds, which in turn sits just
         * past the 5.12 s classic page timeout. That tick normally arrives well before this.
         */
        const val DEFAULT_TIMEOUT_MS = 8_000L
    }
}
