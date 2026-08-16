package app.sendfpl.bt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import androidx.annotation.RequiresPermission
import app.sendfpl.Trace
import app.sendfpl.cxp.Link
import java.io.IOException
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Garmin's own RFCOMM service UUID.
 *
 * **Not** standard SPP (`00001101-…`), which also appears in Garmin's APK but is not what the
 * Connext link uses. `createRfcommSocketToServiceRecord` performs an SDP lookup for this UUID and
 * connects to whatever channel the navigator advertises, since the channel is not fixed, which is why
 * nothing here hardcodes one.
 */
val CONNEXT_SPP_UUID: UUID = UUID.fromString("58e1f790-aa26-11e3-a5e2-0800200c9a66")

/**
 * A CXP [Link] over a classic Bluetooth RFCOMM socket.
 *
 * The GPS 175 is reached over classic SPP, and the Garmin client's BLE path is filtered to the D2
 * watch service and its own logs talk about "the watch".
 *
 * Reads are pumped by a background thread into a queue so [receive] can honour a timeout.
 * `BluetoothSocket`'s input stream has no timed read of its own.
 */
class ConnextLink private constructor(
    private val socket: BluetoothSocket,
) : Link {

    private val incoming = LinkedBlockingQueue<ByteArray>()

    @Volatile
    private var closed = false

    @Volatile
    var failure: IOException? = null
        private set

    private val reader = Thread({
        val buf = ByteArray(4096)
        try {
            while (!closed) {
                val n = socket.inputStream.read(buf)
                if (n < 0) break
                if (n > 0) {
                    val chunk = buf.copyOfRange(0, n)
                    Trace.bytes("←", chunk)
                    incoming.put(chunk)
                }
            }
        } catch (e: IOException) {
            if (!closed) failure = e
        }
    }, "cxp-rfcomm-reader").apply { isDaemon = true }

    /**
     * Refuse to keep working once the link has been closed.
     *
     * [close] sets `closed` before closing the socket, so the reader thread's own IOException is
     * an expected shutdown and is swallowed, leaving [failure] null. Without a check here the
     * session's waits would read an empty return as "nothing yet" and run to their own deadlines,
     * which for the handshake is ten seconds, so abandoning a transfer would abort the *connect*
     * at once and then sit in the next wait. Throwing makes it prompt at every stage.
     */
    private fun checkOpen() {
        failure?.let { throw it }
        if (closed) throw IOException("the link was closed")
    }

    override fun send(data: ByteArray) {
        checkOpen()
        Trace.bytes("→", data)
        socket.outputStream.write(data)
        socket.outputStream.flush()
    }

    override fun receive(timeoutMillis: Long): ByteArray {
        checkOpen()
        val first = incoming.poll(timeoutMillis, TimeUnit.MILLISECONDS) ?: return ByteArray(0)
        // Coalesce whatever else is already queued, as the session reassembles packets anyway.
        var out = first
        while (true) {
            val more = incoming.poll() ?: break
            out += more
        }
        return out
    }

    override fun close() {
        closed = true
        runCatching { socket.close() }
    }

    companion object {
        /**
         * Connect to [device] over the Connext RFCOMM service.
         *
         * Blocking, so call it from a background dispatcher. Cancels discovery first, which Android
         * documents as necessary because an active scan slows a connect attempt dramatically.
         *
         * [onConnecting] receives the link **before** the socket is connected, and exists so that a
         * caller can abandon the attempt. `BluetoothSocket.connect()` blocks in the platform and is
         * documented as abortable only by [close] from another thread: a coroutine cancellation
         * does nothing to it. Handing the link over only on success would leave the longest step
         * of a transfer the one step that cannot be called off.
         *
         * The link handed over is not usable yet. Only [close] is meaningful on it, and calling
         * that makes the connect below throw promptly.
         */
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        @SuppressLint("MissingPermission")
        fun connect(
            device: BluetoothDevice,
            cancelDiscovery: (() -> Unit)? = null,
            onConnecting: (ConnextLink) -> Unit = {},
        ): ConnextLink {
            cancelDiscovery?.invoke()
            val socket = device.createRfcommSocketToServiceRecord(CONNEXT_SPP_UUID)
            val link = ConnextLink(socket)
            onConnecting(link)
            try {
                socket.connect()
            } catch (e: IOException) {
                runCatching { socket.close() }
                throw IOException(
                    "could not open the Connext RFCOMM service on ${device.address}. " +
                        "Is the navigator paired and in Connext range?",
                    e,
                )
            }
            // Started only once there is something to read from. A cancelled attempt never gets
            // here, so it never leaves a thread behind.
            link.reader.start()
            return link
        }
    }
}
