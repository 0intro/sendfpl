package app.sendfpl.route

import app.sendfpl.cxp.Problem
import kotlin.test.Test
import kotlin.test.assertEquals

/** Refusals in encodings the JVM's charsets write for the test. */
class ImportEncodingTest {

    private fun problem(file: String, bytes: ByteArray): Problem? =
        (runCatching { RouteImporter.import(file, bytes) }.exceptionOrNull() as? RouteParseException)?.problem

    /**
     * A document type in UTF-16 is refused as one. A file whose ASCII declaration names an
     * encoding that writes the declaration in other bytes, such as EBCDIC, is refused by that name.
     */
    @Test
    fun `a document type is refused in UTF-16 and EBCDIC by its name`() {
        val body = """
            <!DOCTYPE gpx [<!ENTITY a "aaaaaaaaaa">]>
            <gpx><rte><rtept lat="1" lon="1"><name>&a;</name></rtept>
            <rtept lat="2" lon="2"><name>B</name></rtept></rte></gpx>
        """.trimIndent()
        val bom = byteArrayOf(0xff.toByte(), 0xfe.toByte())
        val utf16 = "<?xml version=\"1.0\" encoding=\"UTF-16\"?>\n$body"
        assertEquals(Problem.UnrecognisedFile, problem("r.gpx", bom + utf16.toByteArray(Charsets.UTF_16LE)))
        assertEquals(Problem.UnrecognisedFile, problem("r.gpx", utf16.toByteArray(Charsets.UTF_16BE)))

        // An ASCII declaration naming EBCDIC, which writes `<!DOCTYPE` in other bytes.
        val ebcdic = java.nio.charset.Charset.forName("IBM037")
        val declared = "<?xml version=\"1.0\" encoding=\"IBM037\"?>".toByteArray(Charsets.US_ASCII) +
            "\n$body".toByteArray(ebcdic)
        assertEquals(Problem.UnreadableEncoding("IBM037"), problem("r.gpx", declared))
    }

    /**
     * A GPX in an encoding outside the six the reader decodes, which the JVM knows, imports with
     * its names decoded and folded: Č to C, where Latin-1 would read the byte as È, and Ł to L.
     * A name is cut to five letters, as `fitNames` cuts it.
     */
    @Test
    fun `a GPX in windows-1250 imports with its names`() {
        val gpx = """
            <?xml version="1.0" encoding="windows-1250"?>
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1"><rte>
            <rtept lat="49.9" lon="15.4"><name>Čáslav</name></rtept>
            <rtept lat="51.8" lon="19.5"><name>Łódź</name></rtept>
            </rte></gpx>
        """.trimIndent().toByteArray(charset("windows-1250"))
        assertEquals(
            listOf("CASLA,N49540E015240", "LODZ,N51480E019300"),
            RouteImporter.import("r.gpx", gpx).identifiers,
        )
    }
}
