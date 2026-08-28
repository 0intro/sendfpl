package app.sendfpl.route

/*
 * An XML reader for route files, the same code on every platform, so that one file imports the
 * same way on each. It reads XML 1.0 with namespaces into a tree and refuses what is not
 * well-formed, as a conforming parser must. A document declaring version 1.1 is read by 1.0's
 * rules, as section 2.8 of XML 1.0 has a 1.0 processor do. It never fetches or expands anything:
 * a document type is refused, and the only entities are the five predefined ones and character
 * references.
 */

/** A node of a document: an element, or the text between elements. */
internal sealed interface XmlNode

/** Character data, CDATA sections included, with adjacent runs joined. */
internal data class XmlText(val text: String) : XmlNode

/**
 * An element. [name] is its qualified name as written, and [namespace] the URI its prefix, or the
 * default namespace, binds it to. [attributes] are keyed by qualified name, namespace
 * declarations included.
 */
internal data class XmlElement(
    val name: String,
    val namespace: String?,
    val attributes: Map<String, String>,
    val children: List<XmlNode>,
) : XmlNode {
    val localName: String get() = name.substringAfter(':')

    /** The text of every descendant, in document order. */
    val textContent: String get() = buildString { appendText(this@XmlElement) }

    /** The value of attribute [name], or the empty string when it is absent. */
    fun attribute(name: String): String = attributes[name].orEmpty()

    /** The first child element whose local name is [name]. */
    fun child(name: String): XmlElement? =
        children.firstOrNull { it is XmlElement && it.localName == name } as XmlElement?

    /** Every element below this one whose local name is [name], in document order. */
    fun descendants(name: String): List<XmlElement> = buildList { collect(this@XmlElement, name) }

    /** The prefix this element binds to [uri], its own or one it declares, or null. */
    fun prefixOf(uri: String): String? {
        if (namespace == uri && ':' in name) return name.substringBefore(':')
        return attributes.entries.firstOrNull { it.key.startsWith("xmlns:") && it.value == uri }
            ?.key?.removePrefix("xmlns:")
    }
}

private fun StringBuilder.appendText(e: XmlElement) {
    for (c in e.children) when (c) {
        is XmlText -> append(c.text)
        is XmlElement -> appendText(c)
    }
}

private fun MutableList<XmlElement>.collect(e: XmlElement, name: String) {
    for (c in e.children) {
        if (c !is XmlElement) continue
        if (c.localName == name) add(c)
        collect(c, name)
    }
}

/**
 * Why a document could not be read. [doctype] marks one refused for declaring a document type, and
 * [encoding] names the encoding of one declared in an encoding neither this nor the platform
 * decodes.
 */
internal class XmlException(
    message: String,
    val doctype: Boolean = false,
    val encoding: String? = null,
) : Exception(message)

/**
 * How deep elements may nest. Route files nest a dozen deep at most, and the bound keeps a hostile
 * file from overflowing the stack of the recursive reader below.
 */
private const val MAX_DEPTH = 256

private const val XML_NS = "http://www.w3.org/XML/1998/namespace"
private const val XMLNS_NS = "http://www.w3.org/2000/xmlns/"

/** Read a whole document from its bytes, and return its root element. */
internal fun parseXml(bytes: ByteArray): XmlElement = XmlReader(decodeXml(bytes)).document()

/**
 * The document's characters: the encoding its byte order mark or its declaration names, with
 * every line ending made a line feed, as XML 1.0 section 2.11 has it. Six encodings are decoded
 * here, and any other a declaration names by the platform, as [platformDecode] says.
 */
internal fun decodeXml(b: ByteArray): String {
    val text = when {
        b.startsWith(0xEF, 0xBB, 0xBF) -> utf8(b, 3)
        b.startsWith(0xFE, 0xFF) -> utf16(b, 2, bigEndian = true)
        b.startsWith(0xFF, 0xFE) -> utf16(b, 2, bigEndian = false)
        b.startsWith(0x00, 0x3C, 0x00, 0x3F) -> utf16(b, 0, bigEndian = true)
        b.startsWith(0x3C, 0x00, 0x3F, 0x00) -> utf16(b, 0, bigEndian = false)
        else -> {
            val declared = declaredEncoding(b)
            when (declared?.uppercase()) {
                null, "UTF-8", "UTF8" -> utf8(b, 0)
                "US-ASCII", "ASCII" -> ascii(b)
                "ISO-8859-1", "ISO8859-1", "ISO_8859-1", "LATIN1", "L1" -> single(b, null)
                "ISO-8859-15", "ISO8859-15", "ISO_8859-15", "LATIN-9", "LATIN9" -> single(b, LATIN9)
                "WINDOWS-1252", "CP1252" -> single(b, CP1252)
                // The declaration was read as ASCII, so a decoding that does not give it back is
                // of an encoding that writes it otherwise, such as EBCDIC.
                else -> platformDecode(b, declared)?.takeIf { it.startsWith("<?xml") }
                    ?: throw XmlException(
                        "the declared encoding, $declared, is not one this reads",
                        encoding = declared.takeIf { it.length <= 40 && ENCODING_NAME.matches(it) },
                    )
            }
        }
    }
    return text.replace("\r\n", "\n").replace('\r', '\n')
}

private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
    size >= prefix.size && prefix.indices.all { (this[it].toInt() and 0xFF) == prefix[it] }

/**
 * The encoding an ASCII-compatible declaration names, read from its bytes up to the `?>` that ends
 * it, or null. A declaration may hold any amount of white space, so the read runs to its end.
 */
private fun declaredEncoding(b: ByteArray): String? {
    if (!b.startsWith(0x3C, 0x3F, 0x78, 0x6D, 0x6C)) return null
    val decl = buildString {
        for (i in b.indices) {
            if (b[i] == '?'.code.toByte() && i + 1 < b.size && b[i + 1] == '>'.code.toByte()) break
            append((b[i].toInt() and 0xFF).toChar())
        }
    }
    return ENCODING_IN_DECLARATION.find(decl)?.groupValues?.get(2)
}

private val ENCODING_IN_DECLARATION = Regex("""\sencoding\s*=\s*(["'])([^"']*)\1""")

private fun utf8(b: ByteArray, from: Int): String = try {
    b.decodeToString(from, b.size, throwOnInvalidSequence = true)
} catch (e: CharacterCodingException) {
    throw XmlException("the file is not valid UTF-8")
}

private fun utf16(b: ByteArray, from: Int, bigEndian: Boolean): String {
    if ((b.size - from) % 2 != 0) throw XmlException("the file is not valid UTF-16")
    return buildString((b.size - from) / 2) {
        for (i in from until b.size step 2) {
            val hi = b[i].toInt() and 0xFF
            val lo = b[i + 1].toInt() and 0xFF
            append((if (bigEndian) hi shl 8 or lo else lo shl 8 or hi).toChar())
        }
    }
}

private fun ascii(b: ByteArray): String = buildString(b.size) {
    for (x in b) {
        if (x < 0) throw XmlException("the file is declared ASCII and holds a byte over 0x7F")
        append(x.toInt().toChar())
    }
}

/** A single-byte encoding: Latin-1, with [high] replacing its characters from 0x80 where given. */
private fun single(b: ByteArray, high: Map<Int, Char>?): String = buildString(b.size) {
    for (x in b) {
        val v = x.toInt() and 0xFF
        append(high?.get(v) ?: v.toChar())
    }
}

/** Where ISO 8859-15 differs from ISO 8859-1. */
private val LATIN9 = mapOf(
    0xA4 to '€', 0xA6 to 'Š', 0xA8 to 'š', 0xB4 to 'Ž',
    0xB8 to 'ž', 0xBC to 'Œ', 0xBD to 'œ', 0xBE to 'Ÿ',
)

/** Windows-1252's characters from 0x80 to 0x9F. Its five unassigned bytes read as U+FFFD. */
private val CP1252 = (
    "\u20AC\uFFFD\u201A\u0192\u201E\u2026\u2020\u2021\u02C6\u2030\u0160\u2039\u0152\uFFFD\u017D\uFFFD" +
        "\uFFFD\u2018\u2019\u201C\u201D\u2022\u2013\u2014\u02DC\u2122\u0161\u203A\u0153\uFFFD\u017E\u0178"
    ).withIndex().associate { (i, c) -> 0x80 + i to c }

/** The namespaces in scope: a prefix's binding, the default namespace under the key "". */
private class Scope(val parent: Scope?, val bindings: Map<String, String>) {
    fun lookup(prefix: String): String? = bindings[prefix] ?: parent?.lookup(prefix)
}

private class XmlReader(private val s: String) {
    private var i = 0

    fun document(): XmlElement {
        if (s.startsWith("<?xml") && s.length > 5 && isSpace(s[5])) declaration()
        misc()
        if (s.startsWith("<!DOCTYPE", i)) {
            throw XmlException("the file declares a document type", doctype = true)
        }
        if (!s.startsWith("<", i) || i + 1 >= s.length || !isNameStart(codePoint(i + 1))) {
            throw XmlException("the file holds no root element")
        }
        val root = element(Scope(null, mapOf("xml" to XML_NS)), 1)
        misc()
        if (i < s.length) throw XmlException("the file holds more after its root element")
        return root
    }

    private fun fail(what: String): Nothing = throw XmlException("$what, at character $i")

    private fun declaration() {
        i = 5
        spaces(required = true)
        if (!s.startsWith("version", i)) fail("the declaration has no version")
        i += "version".length
        val version = eqValue()
        if (version != "1.0" && version != "1.1") fail("XML version $version is not one this reads")
        var spaced = spaces()
        if (spaced && s.startsWith("encoding", i)) {
            i += "encoding".length
            val name = eqValue()
            if (!ENCODING_NAME.matches(name)) fail("\"$name\" is not an encoding name")
            spaced = spaces()
        }
        if (spaced && s.startsWith("standalone", i)) {
            i += "standalone".length
            val v = eqValue()
            if (v != "yes" && v != "no") fail("standalone must be yes or no")
            spaces()
        }
        if (!s.startsWith("?>", i)) fail("the declaration does not end with ?>")
        i += 2
    }

    /** `S? = S?` then a quoted value, returned without its quotes. */
    private fun eqValue(): String {
        spaces()
        if (i >= s.length || s[i] != '=') fail("= expected")
        i++
        spaces()
        if (i >= s.length || (s[i] != '"' && s[i] != '\'')) fail("a quoted value expected")
        val q = s[i]
        val end = s.indexOf(q, i + 1)
        if (end < 0) fail("an unterminated value")
        val v = s.substring(i + 1, end)
        i = end + 1
        return v
    }

    /** Skip white space, and say whether there was any. */
    private fun spaces(required: Boolean = false): Boolean {
        val start = i
        while (i < s.length && isSpace(s[i])) i++
        if (required && i == start) fail("white space expected")
        return i > start
    }

    /** Comments, processing instructions and white space, as may stand outside the root. */
    private fun misc() {
        while (true) {
            spaces()
            when {
                s.startsWith("<!--", i) -> comment()
                s.startsWith("<?", i) -> pi()
                else -> return
            }
        }
    }

    private fun comment() {
        i += 4
        val end = s.indexOf("--", i)
        if (end < 0) fail("an unterminated comment")
        if (!s.startsWith("-->", end)) fail("-- inside a comment")
        checkChars(i, end)
        i = end + 3
    }

    private fun pi() {
        i += 2
        val target = name()
        if (target.equals("xml", ignoreCase = true)) fail("a processing instruction named xml")
        val end = s.indexOf("?>", i)
        if (end < 0) fail("an unterminated processing instruction")
        if (end > i && !isSpace(s[i])) fail("white space expected after the target")
        checkChars(i, end)
        i = end + 2
    }

    private fun element(outer: Scope, depth: Int): XmlElement {
        if (depth > MAX_DEPTH) fail("elements nested deeper than $MAX_DEPTH")
        i++
        val qname = name()
        val raw = LinkedHashMap<String, String>()
        while (true) {
            val spaced = spaces()
            if (s.startsWith("/>", i) || s.startsWith(">", i)) break
            if (!spaced) fail("white space expected between attributes")
            val an = name()
            val v = attributeValue()
            if (raw.put(an, v) != null) fail("the attribute $an is given twice")
        }
        val bindings = HashMap<String, String>()
        for ((an, v) in raw) {
            if (an == "xmlns") {
                if (v == XML_NS || v == XMLNS_NS) fail("the default namespace bound to a reserved URI")
                bindings[""] = v
            } else if (an.startsWith("xmlns:")) {
                val p = an.removePrefix("xmlns:")
                when {
                    p.isEmpty() || ':' in p || !isNameStart(p.codePointAt(0)) ->
                        fail("$an is not a qualified name")
                    p == "xmlns" -> fail("the prefix xmlns declared")
                    p == "xml" && v != XML_NS -> fail("the prefix xml bound to another URI")
                    p != "xml" && (v == XML_NS || v == XMLNS_NS) -> fail("a reserved URI bound to $p")
                    v.isEmpty() -> fail("the prefix $p bound to nothing")
                }
                bindings[p] = v
            }
        }
        val scope = if (bindings.isEmpty()) outer else Scope(outer, bindings)
        val namespace = resolve(qname, scope, element = true)
        val seen = HashSet<Pair<String, String>>()
        for (an in raw.keys) {
            if (an == "xmlns" || an.startsWith("xmlns:")) continue
            val uri = resolve(an, scope, element = false) ?: continue
            if (!seen.add(uri to an.substringAfter(':'))) fail("the attribute $an is given twice")
        }
        if (s.startsWith("/>", i)) {
            i += 2
            return XmlElement(qname, namespace, raw, emptyList())
        }
        i++
        return XmlElement(qname, namespace, raw, content(qname, scope, depth))
    }

    /**
     * The namespace a qualified name binds to, checking its shape. A leading colon names no prefix,
     * as Xerces reads one, so `:level` is `level` with no prefix.
     */
    private fun resolve(qname: String, scope: Scope, element: Boolean): String? {
        val colon = qname.indexOf(':')
        val unprefixed = colon < 0 ||
            (colon == 0 && qname.length > 1 && qname.indexOf(':', 1) < 0 && isNameStart(qname.codePointAt(1)))
        if (unprefixed) return if (element) scope.lookup("")?.takeIf { it.isNotEmpty() } else null
        if (colon == 0 || colon == qname.length - 1 || qname.indexOf(':', colon + 1) >= 0 ||
            !isNameStart(qname.codePointAt(colon + 1))
        ) {
            fail("$qname is not a qualified name")
        }
        val prefix = qname.substring(0, colon)
        if (prefix == "xmlns") fail("$qname uses the reserved prefix xmlns")
        return scope.lookup(prefix) ?: fail("the prefix of $qname is not declared")
    }

    private fun content(qname: String, scope: Scope, depth: Int): List<XmlNode> {
        val out = mutableListOf<XmlNode>()
        val text = StringBuilder()
        fun flush() {
            if (text.isNotEmpty()) out += XmlText(text.toString())
            text.clear()
        }
        while (true) {
            if (i >= s.length) fail("the element $qname is not closed")
            when {
                s.startsWith("</", i) -> {
                    i += 2
                    val end = name()
                    spaces()
                    if (end != qname) fail("</$end> closes <$qname>")
                    if (i >= s.length || s[i] != '>') fail("> expected")
                    i++
                    flush()
                    return out
                }
                s.startsWith("<!--", i) -> comment()
                s.startsWith("<![CDATA[", i) -> {
                    val start = i + 9
                    val end = s.indexOf("]]>", start)
                    if (end < 0) fail("an unterminated CDATA section")
                    checkChars(start, end)
                    text.append(s, start, end)
                    i = end + 3
                }
                s.startsWith("<?", i) -> pi()
                s.startsWith("<", i) -> {
                    flush()
                    out += element(scope, depth + 1)
                }
                s[i] == '&' -> text.append(reference())
                else -> {
                    val start = i
                    while (i < s.length && s[i] != '<' && s[i] != '&') i++
                    for (k in start until i - 2) {
                        if (s[k] == ']' && s[k + 1] == ']' && s[k + 2] == '>') fail("]]> in text")
                    }
                    checkChars(start, i)
                    text.append(s, start, i)
                }
            }
        }
    }

    /** A quoted attribute value, its references replaced and its white space made spaces. */
    private fun attributeValue(): String {
        spaces()
        if (i >= s.length || s[i] != '=') fail("= expected after an attribute name")
        i++
        spaces()
        if (i >= s.length || (s[i] != '"' && s[i] != '\'')) fail("a quoted attribute value expected")
        val q = s[i++]
        val out = StringBuilder()
        while (true) {
            if (i >= s.length) fail("an unterminated attribute value")
            val c = s[i]
            when {
                c == q -> {
                    i++
                    return out.toString()
                }
                c == '<' -> fail("< in an attribute value")
                c == '&' -> out.append(reference())
                isSpace(c) -> {
                    out.append(' ')
                    i++
                }
                else -> {
                    checkChars(i, i + charLength(i))
                    out.append(s, i, i + charLength(i))
                    i += charLength(i)
                }
            }
        }
    }

    /** `&name;` or a character reference, as the text it stands for. */
    private fun reference(): String {
        val end = s.indexOf(';', i)
        if (end < 0) fail("an unterminated reference")
        val ref = s.substring(i + 1, end)
        i = end + 1
        if (ref.startsWith("#")) {
            val cp = if (ref.startsWith("#x")) {
                ref.drop(2).takeIf { it.isNotEmpty() && it.all(::isHexDigit) }?.toIntOrNull(16)
            } else {
                ref.drop(1).takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }?.toIntOrNull()
            }
            if (cp == null || !isXmlChar(cp)) fail("&$ref; is not a character")
            return codePointString(cp)
        }
        return when (ref) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            else -> fail("the entity &$ref; is not declared")
        }
    }

    private fun name(): String {
        val start = i
        if (i >= s.length || !isNameStart(codePoint(i))) fail("a name expected")
        i += charLength(i)
        while (i < s.length && isNameChar(codePoint(i))) i += charLength(i)
        return s.substring(start, i)
    }

    private fun codePoint(at: Int): Int = s.codePointAt(at)

    private fun charLength(at: Int): Int =
        if (s[at].isHighSurrogate() && at + 1 < s.length && s[at + 1].isLowSurrogate()) 2 else 1

    /** Refuse a character XML 1.0 does not allow, or a lone surrogate, in [from] until [to]. */
    private fun checkChars(from: Int, to: Int) {
        var k = from
        while (k < to) {
            val cp = s.codePointAt(k)
            if (!isXmlChar(cp)) {
                i = k
                fail("a character XML does not allow, U+${cp.toString(16).uppercase()}")
            }
            k += if (cp > 0xFFFF) 2 else 1
        }
    }
}

/** A code point at [at], joining a surrogate pair, or the lone surrogate itself. */
private fun String.codePointAt(at: Int): Int {
    val c = this[at]
    if (c.isHighSurrogate() && at + 1 < length && this[at + 1].isLowSurrogate()) {
        return 0x10000 + ((c.code - 0xD800) shl 10) + (this[at + 1].code - 0xDC00)
    }
    return c.code
}

private fun codePointString(cp: Int): String =
    if (cp < 0x10000) cp.toChar().toString()
    else charArrayOf((0xD800 + ((cp - 0x10000) shr 10)).toChar(), (0xDC00 + ((cp - 0x10000) and 0x3FF)).toChar()).concatToString()

private fun isSpace(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\r'

private fun isHexDigit(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

/** XML 1.0's Char, section 2.2. */
private fun isXmlChar(cp: Int) = cp == 0x9 || cp == 0xA || cp == 0xD || cp in 0x20..0xD7FF ||
    cp in 0xE000..0xFFFD || cp in 0x10000..0x10FFFF

/** XML 1.0 fifth edition's NameStartChar, section 2.3. */
private fun isNameStart(cp: Int) = cp == ':'.code || cp in 'A'.code..'Z'.code || cp == '_'.code ||
    cp in 'a'.code..'z'.code || cp in 0xC0..0xD6 || cp in 0xD8..0xF6 || cp in 0xF8..0x2FF ||
    cp in 0x370..0x37D || cp in 0x37F..0x1FFF || cp in 0x200C..0x200D || cp in 0x2070..0x218F ||
    cp in 0x2C00..0x2FEF || cp in 0x3001..0xD7FF || cp in 0xF900..0xFDCF || cp in 0xFDF0..0xFFFD ||
    cp in 0x10000..0xEFFFF

/** XML 1.0 fifth edition's NameChar. */
private fun isNameChar(cp: Int) = isNameStart(cp) || cp == '-'.code || cp == '.'.code ||
    cp in '0'.code..'9'.code || cp == 0xB7 || cp in 0x300..0x36F || cp in 0x203F..0x2040

private val ENCODING_NAME = Regex("[A-Za-z][A-Za-z0-9._-]*")
