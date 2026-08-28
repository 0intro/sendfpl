package app.sendfpl.route

import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.ErrorHandler
import org.xml.sax.SAXParseException

/**
 * The reader against the JVM's Xerces: on every route file in the tree, and on seeded mutations
 * of each, the two must accept and refuse the same documents
 * and build the same tree from what they accept. The divergences are deliberate, and each has a
 * test of its own.
 */
class XmlParityTest {

    private val samples: List<Pair<String, ByteArray>> =
        ROUTE_FILES.keys.sorted().map { it to routeFile(it) }

    /** The tree Xerces builds, in the reader's terms, or null when it refuses the document. */
    private fun xerces(bytes: ByteArray): XmlElement? {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            // Xerces's grammar alone: 0 lifts the depth limit a JDK's conf/jaxp.properties sets,
            // 100 from JDK 24.
            setAttribute("jdk.xml.maxElementDepth", "0")
        }
        val builder = factory.newDocumentBuilder().apply {
            setErrorHandler(object : ErrorHandler {
                override fun warning(e: SAXParseException) {}
                override fun error(e: SAXParseException) = throw e
                override fun fatalError(e: SAXParseException) = throw e
            })
        }
        val doc = runCatching { builder.parse(ByteArrayInputStream(bytes)) }.getOrNull() ?: return null
        return convert(doc.documentElement)
    }

    private fun convert(e: Element): XmlElement {
        val attributes = LinkedHashMap<String, String>()
        for (i in 0 until e.attributes.length) {
            val a = e.attributes.item(i)
            attributes[a.nodeName] = a.nodeValue
        }
        val children = mutableListOf<XmlNode>()
        val text = StringBuilder()
        var c: Node? = e.firstChild
        while (c != null) {
            when (c.nodeType) {
                Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> text.append(c.nodeValue)
                Node.ELEMENT_NODE -> {
                    if (text.isNotEmpty()) children += XmlText(text.toString())
                    text.clear()
                    children += convert(c as Element)
                }
            }
            c = c.nextSibling
        }
        if (text.isNotEmpty()) children += XmlText(text.toString())
        return XmlElement(e.nodeName, e.namespaceURI, attributes, children)
    }

    private fun ours(bytes: ByteArray): XmlElement? = runCatching { parseXml(bytes) }.getOrNull()

    @Test
    fun `every route file reads as Xerces reads it`() {
        assertTrue(samples.size >= 9, "no samples under src/commonTest/resources/routes")
        for ((name, bytes) in samples) {
            val want = xerces(bytes)
            assertTrue(want != null, "$name: Xerces refuses the sample")
            assertEquals(want, ours(bytes), name)
        }
    }

    private fun gpx(name: String) =
        "<gpx xmlns=\"http://www.topografix.com/GPX/1/1\"><rte>" +
            "<rtept lat=\"48.8\" lon=\"2.6\"><name>$name</name></rtept>" +
            "<rtept lat=\"48.9\" lon=\"2.7\"><name>B</name></rtept></rte></gpx>"

    private fun declared(encoding: String, body: String, charset: String = encoding) =
        "<?xml version=\"1.0\" encoding=\"$encoding\"?>\n$body".toByteArray(charset(charset))

    /** Every encoding the reader decodes, as Xerces decodes it, and the bytes each refuses. */
    @Test
    fun `the six encodings read as Xerces reads them`() {
        val accented = gpx("Ch\u00e2teau de Vaux \u0152uvre \u20ac")
        val latin1 = gpx("Ch\u00e2teau de Vaux")
        val documents = listOf(
            "UTF-8" to accented.encodeToByteArray(),
            "UTF-8 with a byte order mark" to byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
                accented.encodeToByteArray(),
            "UTF-16LE with a byte order mark" to byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
                "<?xml version=\"1.0\" encoding=\"UTF-16\"?>$accented".toByteArray(Charsets.UTF_16LE),
            "UTF-16BE with a byte order mark" to byteArrayOf(0xFE.toByte(), 0xFF.toByte()) +
                "<?xml version=\"1.0\" encoding=\"UTF-16\"?>$accented".toByteArray(Charsets.UTF_16BE),
            "UTF-16BE without one" to "<?xml version=\"1.0\" encoding=\"UTF-16\"?>$accented"
                .toByteArray(Charsets.UTF_16BE),
            "ISO-8859-1" to declared("ISO-8859-1", latin1),
            "latin1" to declared("latin1", latin1, "ISO-8859-1"),
            "ISO-8859-15" to declared("ISO-8859-15", accented),
            "windows-1252" to declared("windows-1252", accented),
            "US-ASCII" to declared("US-ASCII", gpx("Chateau")),
            "windows-1252, unassigned bytes" to declared("windows-1252", gpx("A")).let {
                val at = it.indexOf('A'.code.toByte())
                it.copyOf().also { b -> b[at] = 0x81.toByte() }
            },
        )
        for ((what, bytes) in documents) {
            val want = xerces(bytes)
            assertTrue(want != null, "$what: Xerces refuses it")
            assertEquals(want, ours(bytes), what)
        }
        val refused = listOf(
            "invalid UTF-8" to gpx("A").encodeToByteArray().let { b ->
                b.copyOf().also { it[it.indexOf('A'.code.toByte())] = 0xC3.toByte() }
            },
            "a high byte in US-ASCII" to declared("US-ASCII", latin1, "ISO-8859-1"),
            "an odd length of UTF-16" to byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
                accented.toByteArray(Charsets.UTF_16LE) + byteArrayOf(0x41),
        )
        for ((what, bytes) in refused) {
            assertEquals(null, xerces(bytes), "$what: Xerces")
            assertEquals(null, ours(bytes), "$what: the reader")
        }
    }

    /** Encodings beyond the six, which the JVM decodes for the reader, as Xerces reads them. */
    @Test
    fun `the JVM's encodings read as Xerces reads them`() {
        val central = gpx("\u010c\u00e1slav \u0141\u00f3d\u017a")
        val documents = listOf(
            "windows-1250" to declared("windows-1250", central),
            "ISO-8859-2" to declared("ISO-8859-2", central),
            "KOI8-R" to declared("KOI8-R", gpx("\u041c\u043e\u0441\u043a\u0432\u0430")),
            "Shift_JIS" to declared("Shift_JIS", gpx("\u6210\u7530")),
            "windows-1250, an unassigned byte" to declared("windows-1250", gpx("A")).let {
                val at = it.indexOf('A'.code.toByte())
                it.copyOf().also { b -> b[at] = 0x81.toByte() }
            },
        )
        for ((what, bytes) in documents) {
            val want = xerces(bytes)
            assertTrue(want != null, "$what: Xerces refuses it")
            assertEquals(want, ours(bytes), what)
        }
    }

    /**
     * The three places the reader is apart from Xerces, by design: EBCDIC, which writes even the
     * declaration in bytes of its own and which no route file sampled uses, elements nested past
     * 256, which no route file comes near, and XML 1.1, which the reader reads by 1.0's rules, as
     * a 1.0 processor does. Xerces reads 1.1, where a NEL ends a line and a reference may name a
     * control character.
     */
    @Test
    fun `the deliberate divergences`() {
        val ebcdic = declared("IBM037", gpx("A"))
        assertTrue(xerces(ebcdic) != null, "IBM037: Xerces reads it")
        assertEquals(null, ours(ebcdic), "IBM037: the reader refuses it")
        fun nested(depth: Int) = ("<a>".repeat(depth) + "</a>".repeat(depth)).encodeToByteArray()
        assertEquals(xerces(nested(256)), ours(nested(256)))
        assertTrue(xerces(nested(257)) != null)
        assertEquals(null, ours(nested(257)))
        assertEquals(null, ours(nested(100_000)), "a deep file is refused, not a crash")

        val nel = "<?xml version=\"1.1\"?><r>\u0085</r>".encodeToByteArray()
        assertEquals("\n", xerces(nel)!!.textContent)
        assertEquals("\u0085", ours(nel)!!.textContent)
        val control = "<?xml version=\"1.1\"?><r>&#x1;</r>".encodeToByteArray()
        assertEquals("\u0001", xerces(control)!!.textContent)
        assertEquals(null, ours(control))
    }

    /** Names in namespace declarations and processing instructions, as Xerces reads them. */
    @Test
    fun `names read as Xerces reads them`() {
        for (doc in listOf("<r xmlns:1p=\"u\"/>", "<r xmlns:p1=\"u\"/>", "<?a:b x?><r/>")) {
            val bytes = doc.encodeToByteArray()
            assertEquals(xerces(bytes), ours(bytes), doc)
        }
    }

    private val tokens = listOf(
        "<", ">", "&", "&amp;", "&#0;", "&#x41;", "&#65;", "&bogus;", "]]>", "--", "<!-- c -->",
        "<![CDATA[x<y]]>", "<?pi x?>", "<?xml v?>", "\"", "'", "=", " ", "\t", "\r", "\r\n", "\u0001",
        "\uFFFE", "\u00E9", "\u0300", " xmlns:p=\"\"", " xmlns:p=\"u\"", " p:a=\"1\"", " a=\"1\"",
        "p:", ":", "<a>", "</a>", "<a/>", "<p:a/>", "<!DOCTYPE x>", " xmlns=\"\"", "\uD800", "\u00B7",
    )

    private fun mutate(r: Random, bytes: ByteArray): ByteArray {
        if (bytes.isEmpty()) return bytes
        val at = r.nextInt(bytes.size)
        return when (r.nextInt(5)) {
            0 -> bytes.copyOf().also { it[at] = r.nextInt(256).toByte() }
            1 -> bytes.copyOfRange(0, at) + tokens[r.nextInt(tokens.size)].encodeToByteArray() +
                bytes.copyOfRange(at, bytes.size)
            2 -> bytes.copyOfRange(0, at) + bytes.copyOfRange(minOf(bytes.size, at + 1 + r.nextInt(10)), bytes.size)
            3 -> {
                val end = minOf(bytes.size, at + 1 + r.nextInt(20))
                bytes.copyOfRange(0, end) + bytes.copyOfRange(at, end) + bytes.copyOfRange(end, bytes.size)
            }
            else -> bytes.copyOfRange(0, at)
        }
    }

    @Test
    fun `seeded mutations read as Xerces reads them`() {
        val seed = 175
        val r = Random(seed)
        val disagreements = mutableListOf<String>()
        var accepted = 0
        var cases = 0
        for ((name, original) in samples) {
            repeat(400) { n ->
                var bytes = original
                repeat(1 + r.nextInt(2)) { bytes = mutate(r, bytes) }
                cases++
                val want = xerces(bytes)
                val got = ours(bytes)
                if (want != null) accepted++
                if (want == got) return@repeat
                val what = when {
                    want == null -> "Xerces refuses, the reader accepts"
                    got == null -> "Xerces accepts, the reader refuses: ${runCatching { parseXml(bytes) }.exceptionOrNull()?.message}"
                    else -> "both accept, the trees differ"
                }
                disagreements += "$name #$n: $what"
            }
        }
        assertTrue(accepted > cases / 10, "only $accepted of $cases mutations were accepted, too few to compare trees")
        assertEquals(
            0,
            disagreements.size, "seed $seed, $cases mutations, ${disagreements.size} disagreements:\n" +
                disagreements.take(40).joinToString("\n"),
        )
    }
}
