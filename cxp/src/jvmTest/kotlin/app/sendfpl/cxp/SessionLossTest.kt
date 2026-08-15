package app.sendfpl.cxp

import java.util.Random
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Two sessions over a link that loses, repeats and delays. Each blocks as it waits for the other,
 * so each runs on a thread of its own, and these run on the JVM. A reference implementation
 * asserts the same cases.
 */
class SessionLossTest {

    /**
     * Two sessions exchange 300 messages, more than one trip round the sequence space, over a link
     * that loses, repeats or delays one packet in ten. Everything arrives once, in order, with a
     * window of one packet and with a wider one, where EAKs come into play.
     */
    @Test
    fun `a lossy link delivers everything once and in order`() = lossy(mxOut = 1, seed = 1)

    @Test
    fun `a lossy link delivers everything with a wider window too`() = lossy(mxOut = 4, seed = 1)

    private fun lossy(mxOut: Int, seed: Long) {
        val params = SynParams(
            mxOut = mxOut, mxRetry = 10, mxCmltv = 1, maxSz = 64, toRetry = 20, toCmltv = 5,
        )
        val wire = LossyWire(Random(seed))
        val a = Session(wire.end(0), params)
        val b = Session(wire.end(1), params)

        var bFailure: Throwable? = null
        val handshake = thread { runCatching { b.connect(5_000) }.onFailure { bFailure = it } }
        a.connect(5_000)
        handshake.join()
        bFailure?.let { fail("b: $it") }
        wire.lossy = true

        val n = 300
        val got = mutableListOf<String>()
        val receiver = thread {
            try {
                while (got.size < n) got += String(b.receive(10_000) ?: break)
                b.receive(500)   // keep acknowledging until the sender has heard the last ack
            } catch (e: Throwable) {
                bFailure = e
            }
        }
        repeat(n) { a.send("message $it".toByteArray()) }
        a.flush(10_000)
        receiver.join()
        bFailure?.let { fail("receiver: $it") }

        assertEquals(List(n) { "message $it" }, got)
        assertTrue(
            wire.lost > 0 && wire.repeated > 0 && wire.delayed > 0,
            "the link lost ${wire.lost}, repeated ${wire.repeated}, delayed ${wire.delayed}",
        )
    }

    /** Two ends in memory. Once lossy, one packet in ten each is lost, repeated or delayed. */
    private class LossyWire(private val random: Random) {
        @Volatile var lossy = false
        private val buf = arrayOf(ByteArray(0), ByteArray(0))
        private val late = arrayOf(ByteArray(0), ByteArray(0))
        var lost = 0
        var repeated = 0
        var delayed = 0

        fun end(me: Int): Link = object : Link {
            override fun send(data: ByteArray) = synchronized(this@LossyWire) {
                val to = 1 - me
                for (p in iterPackets(data).packets) {
                    val raw = p.encode()
                    when (if (lossy) random.nextInt(10) else 9) {
                        0 -> lost++
                        1 -> { repeated++; buf[to] += raw + raw }
                        2 -> { delayed++; late[to] += raw }
                        else -> buf[to] += raw
                    }
                }
            }

            override fun receive(timeoutMillis: Long): ByteArray {
                val got = synchronized(this@LossyWire) {
                    (buf[me] + late[me]).also { buf[me] = ByteArray(0); late[me] = ByteArray(0) }
                }
                if (got.isEmpty()) Thread.sleep(minOf(timeoutMillis, 1))
                return got
            }
        }
    }
}
