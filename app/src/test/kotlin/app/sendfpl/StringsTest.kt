package app.sendfpl

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

private const val ENGLISH_LISTING = "src/main/play/listings/en-US/full-description.txt"
private const val FRENCH_LISTING = "src/main/play/listings/fr-FR/full-description.txt"

/**
 * The translations, checked as data for what no compiler sees: a format specifier that differs
 * between two languages. `%1$s` where the other file has `%1$d` throws
 * `IllegalFormatConversionException` when the message is shown, and only in one language.
 */
class StringsTest {

    private val defaults = load("src/main/res/values/strings.xml")
    private val french = load("src/main/res/values-fr/strings.xml")

    private fun read(path: String) = File(path).readText()

    /** A resource as shown: aapt2 drops the backslash of `\'` and `\"`. */
    private fun unescape(text: String) = text.replace("\\'", "'").replace("\\\"", "\"")

    /** Prose with its markup gone and its line breaks folded, as a reader sees it. */
    private fun plain(text: String) =
        text.replace(Regex("<[^>]+>|\\*\\*"), "").replace(Regex("\\s+"), " ")

    /** Every `%n$c` in a resource, as the set of position and conversion pairs it uses. */
    private fun specifiers(text: String): Set<String> =
        Regex("""%(\d+\$)?[a-zA-Z]""").findAll(text).map { it.value }.toSet()

    private fun load(path: String): Map<String, List<String>> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path))
        val out = mutableMapOf<String, List<String>>()
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as? Element ?: continue
            val name = e.getAttribute("name").takeIf { it.isNotEmpty() } ?: continue
            out[name] = when (e.tagName) {
                "string" -> listOf(e.textContent)
                "plurals" -> {
                    val items = e.getElementsByTagName("item")
                    (0 until items.length).map { items.item(it).textContent }
                }
                else -> continue
            }
        }
        return out
    }

    @Test
    fun `every translated string uses the same format specifiers as the default`() {
        for ((name, texts) in french) {
            val want = defaults[name] ?: continue
            val wanted = want.flatMap { specifiers(it) }.toSet()
            for (text in texts) {
                assertEquals(
                    "$name: French uses different format specifiers from the default",
                    wanted,
                    specifiers(text),
                )
            }
        }
    }

    /**
     * Nothing is left untranslated by accident. `app_name`, the one exception, is also marked
     * `translatable="false"` in the default file, so lint agrees with this test.
     */
    @Test
    fun `every string the default file declares is translated`() {
        val untranslated = setOf("app_name")
        assertEquals(emptySet<String>(), defaults.keys - french.keys - untranslated)
    }

    /** French counts bytes in octets, so a size limit reads ko and Mo. */
    @Test
    fun `sizes are in the reader's units`() {
        assertEquals(listOf("%1\$d kB"), defaults["size_kilobytes"])
        assertEquals(listOf("%1\$d MB"), defaults["size_megabytes"])
        assertEquals(listOf("%1\$d ko"), french["size_kilobytes"])
        assertEquals(listOf("%1\$d Mo"), french["size_megabytes"])
    }

    /**
     * What the panel shows is quoted in its own English in every language, or a pilot goes looking
     * for a key that does not exist: a GPS 175's advisory and its Preview key, a GTN's MSG and New
     * FPL, and the menu path to Flight Plan Import, which every model shares.
     */
    @Test
    fun `the panel's own words stay in English`() {
        for (table in listOf(defaults, french)) {
            val gps = table.getValue("outcome_sent_detail").single()
            assertTrue(gps, "1 new imported flight plan available for preview" in gps)
            assertTrue(gps, "Preview " in gps)
            val gtn = table.getValue("outcome_sent_detail_gtn").single()
            assertTrue(gtn, "MSG" in gtn && "New FPL" in gtn)
            for (name in listOf("transfer_refused", "devices_empty")) {
                val text = table.getValue(name).single()
                assertTrue(text, "Home → System → Connext Setup" in text)
            }
            assertTrue("Flight Plan Import" in table.getValue("transfer_refused").single())
        }
    }

    /** One verb for pairing, Android's own: its French settings say associer. */
    @Test
    fun `French pairs as Android does`() {
        val texts = french.values.flatten() + read(FRENCH_LISTING)
        for (text in texts) assertFalse(text, "appair" in text.lowercase())
    }

    /**
     * The safety statement says the same wherever a pilot reads it: the About page, README.md,
     * the home page and the store listing in English, the About page and the listing in French.
     */
    @Test
    fun `the safety statement is the same everywhere`() {
        val english = unescape(defaults.getValue("about_responsibility_body").single())
        for (path in listOf("../README.md", "../www/index.html", ENGLISH_LISTING)) {
            assertTrue(path, english in plain(read(path)))
        }
        val inFrench = unescape(french.getValue("about_responsibility_body").single())
        assertTrue(FRENCH_LISTING, inFrench in plain(read(FRENCH_LISTING)))
    }

    /**
     * French puts a no-break space before a colon, a semicolon and a question mark, and inside
     * guillemets, so a line never starts with one. Left out: `!`, which route_help quotes as a
     * character to type, and a colon opening a wire tag such as `:AA:`.
     */
    @Test
    fun `French punctuation never follows a breaking space`() {
        val listing = listOf(FRENCH_LISTING, "src/main/play/listings/fr-FR/short-description.txt",
            "src/main/play/release-notes/fr-FR/default.txt").map(::read)
        for (text in french.values.flatten() + listing) {
            assertFalse(text, Regex(" [:;?](?=\\s|$)| »|« ").containsMatchIn(text))
        }
    }

    /**
     * No em dash and no en dash in either language, since these files are the only prose the
     * application ships. Written as escapes, so that this file does not match a grep for them.
     */
    @Test
    fun `no dash but the plain hyphen`() {
        val forbidden = setOf('\u2014', '\u2013')
        for ((label, table) in listOf("default" to defaults, "French" to french)) {
            for ((name, texts) in table) {
                for (text in texts) {
                    assertTrue(
                        "$label $name holds an em or en dash: $text",
                        text.none { it in forbidden },
                    )
                }
            }
        }
    }
}
