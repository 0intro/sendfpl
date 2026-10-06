package app.sendfpl.bt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import androidx.annotation.RequiresPermission
import app.sendfpl.Trace
import app.sendfpl.cxp.Link
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Garmin's own RFCOMM service UUID, not the standard SPP one (`00001101-…`) that Garmin's APK also
 * carries. The channel is not fixed: `createRfcommSocketToServiceRecord` finds it by SDP.
 */
val CONNEXT_SPP_UUID: UUID = UUID.fromString("58e1f790-aa26-11e3-a5e2-0800200c9a66")

/**
 * A CXP [Link] over a classic Bluetooth RFCOMM socket, which is how a GPS 175 is reached: Garmin's
 * client keeps BLE for the D2 watch service. A background thread pumps reads into a queue so that
 * [receive] can honour a timeout, which `BluetoothSocket`'s input stream cannot. [release] closes
 * the socket; [trace] logs the bytes.
 */
class ConnextLink internal constructor(
    private val input: InputStream,
    private val output: OutputStream,
    private val release: () -> Unit,
    private val trace: (String, ByteArray) -> Unit = Trace::bytes,
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
                val n = input.read(buf)
                if (n < 0) throw EOFException("the navigator closed the link")
                if (n > 0) {
                    val chunk = buf.copyOfRange(0, n)
                    trace("←", chunk)
                    incoming.put(chunk)
                }
            }
        } catch (e: IOException) {
            if (!closed) failure = e
        }
    }, "cxp-rfcomm-reader").apply { isDaemon = true }

    /**
     * Throw once the link has failed or been closed. [close] leaves [failure] null, the reader's
     * IOException being expected then, and without this check every wait would take an empty
     * read for "nothing yet" and run to its deadline instead of stopping at once.
     */
    private fun checkOpen() {
        failure?.let { throw it }
        if (closed) throw IOException("the link was closed")
    }

    /** Start reading, which [connect] does once the socket is connected. */
    internal fun start() = reader.start()

    override fun send(data: ByteArray) {
        checkOpen()
        trace("→", data)
        output.write(data)
        output.flush()
    }

    override fun receive(timeoutMillis: Long): ByteArray {
        // What arrived before a failure comes first: a navigator may answer and close at once.
        val first = incoming.poll() ?: run {
            checkOpen()
            incoming.poll(timeoutMillis, TimeUnit.MILLISECONDS) ?: return ByteArray(0)
        }
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
        runCatching { release() }
    }

    companion object {
        /**
         * Connect to [device] over the Connext RFCOMM service, cancelling discovery first since
         * Android documents that a scan slows a connect attempt. Blocking. [onConnecting] receives
         * the link before the socket connects, so that a caller can abandon the attempt with
         * [ConnextLink.close], the one call meaningful on it until this returns:
         * `BluetoothSocket.connect()` ignores coroutine cancellation and is documented as abortable
         * only by a close from another thread.
         */
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        @SuppressLint("MissingPermission")
        fun connect(
            device: BluetoothDevice,
            cancelDiscovery: (() -> Unit)? = null,
            onConnecting: (ConnextLink) -> Unit = {},
        ): ConnextLink {
            cancelDiscovery?.invoke()
            val socket = try {
                device.createRfcommSocketToServiceRecord(CONNEXT_SPP_UUID)
            } catch (e: IOException) {
                throw IOException("could not create a socket to ${device.address}: is Bluetooth on?", e)
            }
            val link = ConnextLink(socket.inputStream, socket.outputStream, socket::close)
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
            link.start()
            return link
        }
    }
}
