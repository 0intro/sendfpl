package app.sendfpl.cxp

import app.sendfpl.ConnextClient
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** A link that delivers its chunks a read at a time, then waits out each read, and keeps sends. */
private class ChunkLink(chunks: List<ByteArray> = emptyList()) : Link {
    val chunks = ArrayDeque(chunks)
    val sent = mutableListOf<ByteArray>()
    var failure: Exception? = null
    var closed = false

    override fun send(data: ByteArray) {
        sent += data.copyOf()
    }

    override fun receive(timeoutMillis: Long): ByteArray {
        chunks.removeFirstOrNull()?.let { return it }
        failure?.let { throw it }
        sleepMillis(timeoutMillis)
        return ByteArray(0)
    }

    override fun close() {
        closed = true
    }
}

/**
 * The carrier an iPhone's session uses: frames as they are, with no transport. A reference
 * implementation asserts the same cases.
 */
class FrameStreamTest {

    private val a = AppFrame(2, ByteArray(16) { 0xA5.toByte() }, FrameType.BEGIN or FrameType.END).encode()
    private val b = AppFrame(0, byteArrayOf(0, 0, 0, 0, 0, 1, 0x14), FrameType.CONTROL).encode()
    private val c = AppFrame(0x10005000, byteArrayOf(0x2f, 0, 4, 0, 0xc0.toByte(), 0x0d, 3), FrameType.BEGIN or FrameType.END).encode()

    /** Reads until the link has delivered everything and the stream times out. */
    private fun receiveAll(s: FrameStream, link: ChunkLink): List<ByteArray> = buildList {
        while (true) {
            val got = s.receive(20)
            if (got == null) {
                if (link.chunks.isEmpty()) return@buildList
                continue
            }
            add(got)
        }
    }

    @Test
    fun `a frame goes out as it is`() {
        val link = ChunkLink()
        val s = FrameStream(link)
        val frame = AppFrame(0x10005001, "FPN/RI".encodeToByteArray(), FrameType.BEGIN or FrameType.END).encode()
        s.send(frame)
        assertEquals(1, link.sent.size)
        assertContentEquals(frame, link.sent[0])
        assertNull(s.connect(), "there is no transport to negotiate")
    }

    /** Cut anywhere, inside a header or a payload, or several frames to a read. */
    @Test
    fun `a stream comes out as whole frames every byte once and in order`() {
        val stream = a + b + c
        for (cuts in listOf(
            listOf(),
            listOf(1),
            listOf(2),
            listOf(a.size),
            listOf(5, a.size + 3, a.size + b.size + 9),
            listOf(1, 2, 3, 4, 5, 6, 7, 8),
        )) {
            val chunks = buildList {
                var at = 0
                for (cut in cuts) {
                    add(stream.copyOfRange(at, cut))
                    at = cut
                }
                add(stream.copyOfRange(at, stream.size))
            }
            val link = ChunkLink(chunks)
            var got = ByteArray(0)
            for (read in receiveAll(FrameStream(link), link)) {
                var at = 0
                while (at < read.size) at += AppFrame.decode(read, at).consumed
                got += read
            }
            assertContentEquals(stream, got, "cuts $cuts")
        }
    }

    @Test
    fun `a frame still arriving is a timeout`() {
        val s = FrameStream(ChunkLink(listOf(byteArrayOf(APP_VERSION.toByte(), 9))))
        assertNull(s.receive(50))
    }

    /** Handed up as they are, for the client to report, rather than held or dropped unseen. */
    @Test
    fun `bytes that are no frame come back as they are`() {
        for (junk in listOf(
            byteArrayOf(0xc0.toByte(), 0x03, 0x11, 0x00),
            byteArrayOf(APP_VERSION.toByte(), 0x07, 0x00, 0x02),
            byteArrayOf(APP_VERSION.toByte(), 0x09, 0x10, 0x00),
        )) {
            assertContentEquals(junk, FrameStream(ChunkLink(listOf(junk))).receive(50), junk.toHex())
        }
    }

    @Test
    fun `a link failure stays`() {
        val link = ChunkLink().apply { failure = IllegalStateException("link down") }
        val s = FrameStream(link)
        assertFailsWith<IllegalStateException> { s.receive(1_000) }
        assertFailsWith<IllegalStateException> { s.send(byteArrayOf(1)) }
        assertFailsWith<IllegalStateException> { s.flush() }
        s.close()
        assertTrue(link.closed)
    }

    @Test
    fun `the log hears every frame`() {
        val events = mutableListOf<CxpEvent>()
        val link = ChunkLink(listOf(a + b))
        val s = FrameStream(link) { events += it }
        s.send(c)
        s.receive(50)
        assertEquals(
            listOf(true to 0x10005000L, false to 2L, false to 0L),
            events.map { (it as CxpEvent.Frame).let { e -> e.sent to e.frame.cxpId } },
        )
    }

    /**
     * What a client writes on a bare stream before it reads anything: the two registrations, the
     * id list of the auth messages, and AUTH_USER, for a reference implementation's dummy
     * credential. That implementation asserts the same bytes.
     */
    @Test
    fun `a client writes these frames before its first read`() {
        val sent = mutableListOf<ByteArray>()
        val link = object : Link {
            override fun send(data: ByteArray) {
                sent += data.copyOf()
            }

            override fun receive(timeoutMillis: Long): ByteArray = throw IllegalStateException("stop")
        }
        val credential = Credential(1, ByteArray(TOKEN_LEN) { it.toByte() }, "ENTITLEMENT-BLOB".encodeToByteArray())
        assertFailsWith<IllegalStateException> {
            ConnextClient(FrameStream(link), credential).connectAndAuthenticate()
        }
        assertEquals(
            // SET_MODE for AUTH_CHALLENGE and AUTH_CONFIRM, PRDC at priority 10
            "01160002000000000002000000010a0004000000010a" +
                // SET_MODE for the id list, PRDC at priority 20
                "010f00020000000000000000000114" +
                // the id list: the id list, the marker, AUTH_RESPONSE, AUTH_USER
                "0118000c0000000000000000020100000300000001000000" +
                // AUTH_USER, the entitlement verbatim
                "0118000c01000000454e5449544c454d454e542d424c4f42",
            sent.fold(ByteArray(0)) { all, b -> all + b }.toHex(),
        )
    }

    /**
     * Whatever the bytes and however cut, every byte is handed up once and in order, a read that
     * begins with a frame holds whole frames, and what stays is the start of one still arriving.
     */
    @Test
    fun `any stream however cut`() {
        val seed = 1321
        val random = Random(seed)
        val pieces = listOf(a, b, c, byteArrayOf(0xc0.toByte(), 1, 2), byteArrayOf(APP_VERSION.toByte()))
        repeat(500) { round ->
            var stream = ByteArray(0)
            repeat(random.nextInt(1, 6)) { stream += pieces[random.nextInt(pieces.size)] }
            if (random.nextBoolean()) stream = stream.copyOfRange(0, random.nextInt(stream.size + 1))
            val step = random.nextInt(1, 17)
            val link = ChunkLink((stream.indices step step).map { stream.copyOfRange(it, minOf(it + step, stream.size)) })
            val s = FrameStream(link)
            var got = ByteArray(0)
            while (true) {
                val read = s.receive(1) ?: if (link.chunks.isEmpty()) break else continue
                if (read.isEmpty()) fail("seed $seed round $round: an empty read")
                if (frameLengthOf(read) > 0) {
                    var at = 0
                    while (at < read.size) {
                        val n = runCatching { AppFrame.decode(read, at).consumed }.getOrNull()
                            ?: fail("seed $seed round $round: part of a frame in ${read.toHex()}")
                        at += n
                    }
                }
                got += read
            }
            assertContentEquals(stream.copyOfRange(0, got.size), got, "seed $seed round $round")
            val rest = stream.copyOfRange(got.size, stream.size)
            if (rest.isNotEmpty()) {
                val m = frameLengthOf(rest)
                assertTrue(m == 0 || m > rest.size, "seed $seed round $round: kept ${rest.toHex()}")
            }
        }
    }

    /** The frame length as the stream reads it: 0 while incomplete, -1 for no frame. */
    private fun frameLengthOf(b: ByteArray): Int {
        if ((b[0].toInt() and 0xFF) != APP_VERSION) return -1
        if (b.size < 3) return 0
        val total = getU16(b, 1)
        return if (total < APP_HEADER_LEN || total > APP_HEADER_LEN + APP_MAX_PAYLOAD) -1 else total
    }
}
