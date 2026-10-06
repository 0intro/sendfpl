package app.sendfpl

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the route box reads the intent that started the activity, and when it must not. */
class LaunchTest {

    @Test
    fun `a share is read`() {
        assertFalse(intentRead(savedConsumed = false, flags = 0))
    }

    @Test
    fun `a recreated activity keeps what it saved`() {
        assertTrue(intentRead(savedConsumed = true, flags = 0))
    }

    /**
     * Back finishes a task that a share started, and Recents starts it again with the same intent
     * and no saved state: read again, an old route would replace what is in the box.
     */
    @Test
    fun `a relaunch from Recents is not read again`() {
        val fromRecents = Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY
        assertTrue(intentRead(savedConsumed = false, flags = fromRecents))
    }
}
