package app.sendfpl.route

import app.sendfpl.cxp.Problem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The reader's refusals and decodings, from bytes written out here, so that every platform runs
 * them. The JVM's tests compare the same documents with Xerces.
 */
class XmlTest {

    /** The bytes of [parts] in order: a String as UTF-8, an Int as one byte. */
    private fun bytes(vararg parts: Any): ByteArray = parts.fold(ByteArray(0)) { out, part ->
        out + when (part) {
            is String -> part.encodeToByteArray()
            is Int -> byteArrayOf(part.toByte())
            else -> error("a part is a String or a byte")
        }
    }

    private fun text(vararg parts: Any) = parseXml(bytes(*parts)).textContent

    private fun refused(vararg parts: Any) =
        assertFailsWith<XmlException> { parseXml(bytes(*parts)) }

    private fun nested(depth: Int) = "<a>".repeat(depth) + "</a>".repeat(depth)

    /** Elements nest to 256 deep, and a file past that is refused before the stack runs out. */
    @Test
    fun `elements nest 256 deep and no deeper`() {
        parseXml(nested(256).encodeToByteArray())
        refused(nested(257))
        refused(nested(100_000))
    }

    @Test
    fun `each single-byte encoding decodes its own table`() {
        fun declared(name: String, vararg b: Int) =
            text("<?xml version=\"1.0\" encoding=\"$name\"?><r>", *b.toTypedArray(), "</r>")
        assertEquals("€Œ", declared("windows-1252", 0x80, 0x8C))
        assertEquals("€Œ", declared("ISO-8859-15", 0xA4, 0xBC))
        assertEquals("¤¼", declared("ISO-8859-1", 0xA4, 0xBC))
        assertEquals("A", declared("US-ASCII", 0x41))
    }

    @Test
    fun `UTF-16 reads in either order with a byte order mark`() {
        val units = "<r>é</r>".map { it.code }
        val little = units.flatMap { listOf(it and 0xFF, it shr 8) }.toTypedArray()
        val big = units.flatMap { listOf(it shr 8, it and 0xFF) }.toTypedArray()
        assertEquals("é", text(0xFF, 0xFE, *little))
        assertEquals("é", text(0xFE, 0xFF, *big))
    }

    @Test
    fun `bytes their encoding forbids are refused`() {
        refused("<r>", 0xC3, "</r>")
        refused("<?xml version=\"1.0\" encoding=\"US-ASCII\"?><r>", 0xE9, "</r>")
        // UTF-16 of an odd length.
        refused(0xFF, 0xFE, "<", 0, "r", 0, "/", 0, ">", 0, 0x41)
    }

    /** The encoding is read from the whole declaration, however much white space it holds. */
    @Test
    fun `a declaration is read to its end`() {
        val declaration = "<?xml version=\"1.0\"" + " ".repeat(300) + "encoding=\"ISO-8859-1\"?>"
        assertEquals("é", text(declaration, "<r>", 0xE9, "</r>"))
    }

    /** A namespace declaration binds a prefix that is a name. */
    @Test
    fun `a prefix that is no name is refused`() {
        refused("<r xmlns:1p=\"u\"/>")
        refused("<r xmlns:-p=\"u\"/>")
        parseXml("<r xmlns:p1=\"u\"/>".encodeToByteArray())
    }

    /**
     * An encoding neither the reader nor the platform knows is refused by its name, so the pilot
     * can be told which. ANSI is a name some Windows programs write for their code page.
     */
    @Test
    fun `an encoding no platform knows is named`() {
        val e = refused("<?xml version=\"1.0\" encoding=\"ANSI\"?><r/>")
        assertEquals("ANSI", e.encoding)
        val file = bytes("<?xml version=\"1.0\" encoding=\"ANSI\"?><gpx><rte/></gpx>")
        val thrown = runCatching { RouteImporter.import("r.gpx", file) }.exceptionOrNull()
        val problem = (thrown as? RouteParseException)?.problem
        assertEquals(Problem.UnreadableEncoding("ANSI"), problem)
    }
}
