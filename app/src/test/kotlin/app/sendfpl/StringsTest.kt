package app.sendfpl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The translations, checked as data rather than by reading them.
 *
 * A missing string resource or a missing `when` branch is already a compile error, so what is left
 * to go wrong is the thing a compiler cannot see: a format specifier that differs between two
 * languages. `%1$s` where the other file has `%1$d` throws
 * `IllegalFormatConversionException` at the moment the message is shown, and only for the reader
 * of one of them. That is a crash a French pilot meets and nobody else does, in the middle of the
 * one screen where something has already gone wrong.
 *
 * Plain XML parsing on the JVM, so this needs no device, in the spirit of `bt/DeviceStatus.kt`.
 */
class StringsTest {

    private val defaults = load("src/main/res/values/strings.xml")
    private val french = load("src/main/res/values-fr/strings.xml")

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
     * Nothing is left untranslated by accident.
     *
     * `app_name` is the one deliberate exception, and it is marked `translatable="false"` in the
     * default file rather than merely omitted here, so lint agrees with this test.
     */
    @Test
    fun `every string the default file declares is translated`() {
        val untranslated = setOf("app_name")
        assertEquals(emptySet<String>(), defaults.keys - french.keys - untranslated)
    }

    /**
     * No em dash and no en dash, in either language.
     *
     * A rule for both repositories that nothing else enforces. These files are the surface where
     * one is most likely to arrive, since they are the only prose the application ships, and a
     * translation is where one arrives by habit.
     *
     * Written as escapes rather than as the characters, so that this file, which is the one place
     * that has to name them, does not itself become the hit that a grep for them returns.
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
