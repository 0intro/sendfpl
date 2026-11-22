package app.sendfpl.ios

import app.sendfpl.LinkException
import app.sendfpl.cxp.Link
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.ExternalAccessory.EAAccessory
import platform.ExternalAccessory.EASession
import platform.Foundation.NSCondition
import platform.Foundation.NSDate
import platform.Foundation.NSDefaultRunLoopMode
import platform.Foundation.NSInputStream
import platform.Foundation.NSMachPort
import platform.Foundation.NSOutputStream
import platform.Foundation.NSRunLoop
import platform.Foundation.NSStream
import platform.Foundation.NSStreamStatusAtEnd
import platform.Foundation.NSStreamStatusClosed
import platform.Foundation.NSStreamStatusError
import platform.Foundation.NSStreamStatusNotOpen
import platform.Foundation.NSStreamStatusOpening
import platform.Foundation.NSThread
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runMode

/** The External Accessory protocol a navigator serves CXP on, as its firmware names it. */
const val CONNEXT_PROTOCOL = "com.garmin.connext"

/**
 * A navigator's External Accessory session on [CONNEXT_PROTOCOL], as a [Link]: the application
 * frames go out and come in as they are, with no transport. The session's streams are scheduled on
 * a thread of their own and serviced there. The bytes passing between that thread and the
 * transfer wait in two queues guarded by one condition, which [receive] waits on with a timeout
 * and [close] wakes, as Cancel needs.
 */
@OptIn(ExperimentalForeignApi::class, NativeRuntimeApi::class)
class AccessoryLink private constructor(session: EASession) : Link {
    private val input: NSInputStream = session.inputStream ?: throw LinkException("the session has no input stream")
    private val output: NSOutputStream = session.outputStream ?: throw LinkException("the session has no output stream")

    // Held while the streams run, since they are valid only while it lives. The session ends when
    // it is deallocated, and Apple allows one per accessory and protocol at a time, so the service
    // thread drops it and has it collected once the streams close.
    @Suppress("unused")
    private var session: EASession? = session

    private val lock = NSCondition()

    // Guarded by lock.
    private var inbox = ByteArray(0)
    private var outbox = ByteArray(0)
    private var open = false
    private var failure: String? = null
    private var closed = false

    private val thread = NSThread { service() }

    /** Runs the streams on this thread's run loop until the link closes or fails. */
    private fun service() {
        val loop = NSRunLoop.currentRunLoop
        for (s in listOf<NSStream>(input, output)) {
            s.scheduleInRunLoop(loop, forMode = NSDefaultRunLoopMode)
            s.open()
        }
        // A port of the loop's own, so each turn waits out its slice whatever the streams attach.
        loop.addPort(NSMachPort(), forMode = NSDefaultRunLoopMode)
        val buffer = ByteArray(BUFFER_BYTES)
        while (pump(buffer)) {
            loop.runMode(NSDefaultRunLoopMode, beforeDate = NSDate.dateWithTimeIntervalSinceNow(SLICE_SECONDS))
        }
        for (s in listOf<NSStream>(input, output)) {
            s.close()
            s.removeFromRunLoop(loop, forMode = NSDefaultRunLoopMode)
        }
        session = null
        GC.collect()
    }

    /**
     * Moves what each stream is ready for, and says whether to go on. Reads before it looks for
     * trouble, so the last bytes of a session the navigator ends still arrive.
     */
    private fun pump(buffer: ByteArray): Boolean {
        lock.lock()
        try {
            if (closed || failure != null) return false
            if (!open && opened(input) && opened(output)) {
                open = true
                lock.broadcast()
            }
            var moved = false
            while (input.hasBytesAvailable) {
                val n = buffer.usePinned { input.read(it.addressOf(0).reinterpret(), buffer.size.convert()) }
                if (n <= 0) break
                inbox += buffer.copyOf(n.toInt())
                moved = true
            }
            while (outbox.isNotEmpty() && output.hasSpaceAvailable) {
                val n = outbox.usePinned { output.write(it.addressOf(0).reinterpret(), outbox.size.convert()) }
                if (n <= 0) break
                outbox = outbox.copyOfRange(n.toInt(), outbox.size)
                moved = true
            }
            failure = trouble(input) ?: trouble(output)
            if (moved || failure != null) lock.broadcast()
            return failure == null
        } finally {
            lock.unlock()
        }
    }

    /** What went wrong with [s], or null while it is well. */
    private fun trouble(s: NSStream): String? = when (s.streamStatus) {
        NSStreamStatusError -> s.streamError?.localizedDescription ?: "the accessory stream failed"
        NSStreamStatusAtEnd, NSStreamStatusClosed -> "the navigator ended the session"
        else -> null
    }

    private fun opened(s: NSStream) = s.streamStatus != NSStreamStatusNotOpen && s.streamStatus != NSStreamStatusOpening

    /** Throws if the link can carry nothing more. Called with the lock held. */
    private fun ensureUsable() {
        failure?.let { throw LinkException(it) }
        if (closed) throw LinkException("the link was closed")
    }

    /** Waits for [ready] with the lock held, until [seconds] have passed: false on a timeout. */
    private fun waitFor(seconds: Double, ready: () -> Boolean): Boolean {
        val deadline = NSDate.dateWithTimeIntervalSinceNow(seconds)
        while (!ready()) {
            ensureUsable()
            if (!lock.waitUntilDate(deadline)) return ready()
        }
        return true
    }

    /** Queues [data], and returns once the accessory has taken all of it. */
    override fun send(data: ByteArray) {
        lock.lock()
        try {
            ensureUsable()
            outbox += data
            if (!waitFor(SEND_SECONDS) { outbox.isEmpty() }) {
                throw LinkException("the navigator took no bytes for ${SEND_SECONDS.toInt()} s")
            }
        } finally {
            lock.unlock()
        }
    }

    /** What has arrived, or empty after [timeoutMillis]. Bytes here come before a failure. */
    override fun receive(timeoutMillis: Long): ByteArray {
        lock.lock()
        try {
            if (inbox.isEmpty()) waitFor(timeoutMillis / 1000.0) { inbox.isNotEmpty() || failure != null || closed }
            if (inbox.isEmpty()) ensureUsable()
            val got = inbox
            inbox = ByteArray(0)
            return got
        } finally {
            lock.unlock()
        }
    }

    /** Ends the session from any thread. The service thread closes the streams on its next turn. */
    override fun close() {
        lock.lock()
        closed = true
        lock.broadcast()
        lock.unlock()
    }

    /**
     * Blocks until both streams are open, and throws if they fail first, or do not open within
     * [OPEN_SECONDS], or [close] comes first, which wakes the wait.
     */
    fun awaitOpen() {
        lock.lock()
        try {
            if (!waitFor(OPEN_SECONDS) { open }) {
                throw LinkException("the session's streams did not open within ${OPEN_SECONDS.toInt()} s")
            }
            // A stream that failed as it opened counts as open: the failure is the answer.
            ensureUsable()
        } finally {
            lock.unlock()
        }
    }

    companion object {
        /** How long the run loop waits for an event before the queues are looked at again. */
        private const val SLICE_SECONDS = 0.01
        private const val BUFFER_BYTES = 4096
        private const val OPEN_SECONDS = 10.0
        private const val SEND_SECONDS = 10.0

        /**
         * Starts a session on [accessory], whose streams open on the link's thread: [awaitOpen]
         * waits for them. The framework refuses a session, by returning none, when the accessory
         * has gone or does not serve the protocol, or when the app does not declare it in its
         * Info.plist.
         */
        fun start(accessory: EAAccessory): AccessoryLink {
            val session = try {
                EASession(accessory, CONNEXT_PROTOCOL)
            } catch (e: Throwable) {
                throw LinkException("${accessory.name} refused a session on $CONNEXT_PROTOCOL", e)
            }
            return AccessoryLink(session).also { it.thread.start() }
        }
    }
}
