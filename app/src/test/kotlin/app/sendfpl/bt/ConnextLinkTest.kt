package app.sendfpl.bt

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnextLinkTest {

    /**
     * A navigator may answer and close the link at once, as when it refuses a plan: what it said is
     * read before the close is reported.
     */
    @Test
    fun `bytes read before the link fails are delivered first`() {
        val said = byteArrayOf(0xC0.toByte(), 1, 2, 3)
        val link = ConnextLink(ByteArrayInputStream(said), ByteArrayOutputStream(), {}) { _, _ -> }
        link.start()
        val deadline = System.nanoTime() + 2_000_000_000L
        while (link.failure == null && System.nanoTime() < deadline) Thread.sleep(5)
        assertNotNull("the reader never saw the end of the stream", link.failure)

        assertArrayEquals(said, link.receive(100))
        assertTrue(runCatching { link.receive(100) }.exceptionOrNull() is IOException)
    }
}
