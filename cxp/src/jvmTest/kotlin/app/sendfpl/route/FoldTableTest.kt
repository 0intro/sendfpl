package app.sendfpl.route

import java.text.Normalizer
import kotlin.test.Test
import kotlin.test.assertEquals

/** The fold table against the JVM's NFD, at every code point. */
class FoldTableTest {

    /** The ASCII letters and digits of [text]'s NFD, upper-cased, which is what a fold keeps. */
    private fun kept(text: String): String = buildString {
        for (c in Normalizer.normalize(text, Normalizer.Form.NFD)) {
            when (c) {
                in 'A'..'Z', in '0'..'9' -> append(c)
                in 'a'..'z' -> append(c.uppercaseChar())
            }
        }
    }

    @Test
    fun `the table is NFD at every code point`() {
        var mismatches = 0
        val first = StringBuilder()
        for (cp in 0x80..0x10FFFF) {
            if (cp in 0xD800..0xDFFF) continue
            val text = String(Character.toChars(cp))
            val want = kept(text)
            // A code point past the BMP is two UTF-16 units, neither of them in the table.
            val got = if (cp > 0xFFFF) "" else FOLD[cp.toChar()]?.toString().orEmpty()
            if (got != want) {
                mismatches++
                if (first.length < 400) first.append("U+${cp.toString(16).uppercase()} $want/$got ")
            }
        }
        assertEquals(0, mismatches, "table entries that NFD disagrees with: $first")
    }

    @Test
    fun `the table holds nothing ASCII`() {
        assertEquals(emptyList<Char>(), FOLD.keys.filter { it.code < 0x80 })
    }
}
