package app.sendfpl

import android.bluetooth.BluetoothDevice
import app.sendfpl.bt.PairedDevice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe

/**
 * A device for state that only compares it with null. `BluetoothDevice` has no public
 * constructor, so it is allocated without one, and nothing here calls into it.
 */
private val device = PairedDevice(
    name = "GPS175",
    address = "F0:99:19:F1:4D:C6",
    device = Unsafe::class.java.getDeclaredField("theUnsafe")
        .apply { isAccessible = true }
        .let { (it.get(null) as Unsafe).allocateInstance(BluetoothDevice::class.java) as BluetoothDevice },
)

/** Send is the route's to enable and the navigator's to allow. */
class UiStateTest {

    private val ui = UiState(permissionsGranted = true, bluetoothEnabled = true, selected = device)
    private val route = RouteState(credentialPresent = true, preview = "FPN/RI:F:LFPL:F:LFQB")

    @Test
    fun `everything in place can be sent`() {
        assertTrue(home(ui, route).canSend)
    }

    @Test
    fun `a route with nowhere to go is not sendable`() {
        assertFalse(home(ui.copy(selected = null), route).canSend)
        assertFalse(home(ui.copy(permissionsGranted = false), route).canSend)
        assertFalse(home(ui.copy(bluetoothEnabled = false), route).canSend)
    }

    /** The box still shows the last route while a file is read, and Send would send that one. */
    @Test
    fun `an import in flight is not sendable`() {
        assertFalse(home(ui, route.copy(importing = true)).canSend)
    }
}
