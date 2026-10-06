package app.sendfpl

import android.bluetooth.BluetoothDevice
import app.sendfpl.bt.PairedDevice
import app.sendfpl.cxp.ProductData
import app.sendfpl.cxp.ProductId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe

/**
 * A device for state that only compares it with null. `BluetoothDevice` has no public
 * constructor, so it is allocated without one; nothing here calls into it.
 */
private val device = PairedDevice(
    name = "GPS175",
    address = "F0:99:19:F1:4D:C6",
    device = Unsafe::class.java.getDeclaredField("theUnsafe")
        .apply { isAccessible = true }
        .let { (it.get(null) as Unsafe).allocateInstance(BluetoothDevice::class.java) as BluetoothDevice },
)

class UiStateTest {

    private val ready = UiState(
        credentialPresent = true,
        permissionsGranted = true,
        bluetoothEnabled = true,
        selected = device,
        preview = "FPN/RI:F:LFPL:F:LFQB",
    )

    @Test
    fun `everything in place can be sent`() {
        assertTrue(ready.canSend)
    }

    /** The box still shows the last route while a file is read, and Send would send that one. */
    @Test
    fun `an import in flight is not sendable`() {
        assertFalse(ready.copy(importing = true).canSend)
    }

    /** Silence and an unmeasured model are sent the picker's limits alike, and both say so. */
    @Test
    fun `a send under the picker's model says so`() {
        fun named(id: Long) =
            UiState(outcome = Outcome.Success, info = DeviceInfo(productData = ProductData(id, "", "")))
        assertTrue(UiState(outcome = Outcome.Success).usedPickerModel)
        assertTrue(named(ProductId.GI_275).usedPickerModel)
        assertFalse(named(ProductId.G2N).usedPickerModel)
        assertFalse(named(ProductId.GTN_6XX_7XX).usedPickerModel)
        assertFalse(UiState().usedPickerModel)
    }
}
