package app.sendfpl.cxp

/**
 * `CXP_ID_WAP_CONNECTED_LRU`, which is where a navigator says what it is.
 *
 * The name is why it went undecoded: an LRU is a line replaceable unit, a box, and the question
 * "which connected LRU" is asking which box is on the other end of the wireless link.
 *
 * It is not [app.sendfpl.ID_PRODUCT_DATA]. That id is advertised by the navigator and registered
 * for by Garmin's own client, and no capture of this navigator contains a frame carrying it in
 * either direction.
 */
const val ID_CONNECTED_LRU = 0x00001006L

/** The wire size of the identification struct. */
const val PRODUCT_DATA_SIZE = 24

/**
 * A navigator's own account of what it is.
 *
 * ```
 * u32  product_id       little-endian
 * char product_name[12] NUL-padded
 * char sw_version[8]    NUL-padded
 * ```
 *
 * Read off hardware on two units in two sessions, byte-identical: `2800 / "GPS 175" / "3.22"`,
 * with the version field matching what the panel displayed.
 */
data class ProductData(
    val productId: Long,
    val name: String,
    val version: String,
) {
    /**
     * The parser profile this navigator asks for, or null when no profile has been measured for
     * it.
     *
     * Null is never a reason to fall back to another model's caps. An identifier cap that is too
     * generous does not truncate a name, it desynchronises the parser and the upload is rejected,
     * so an unrecognised navigator has to keep failing the way it did before it identified itself.
     */
    val profile: Profile? get() = runCatching { Profiles.forProductId(productId) }.getOrNull()

    override fun toString() = "$productId \"$name\" software \"$version\""
}

/**
 * Parses the identification struct.
 *
 * Both text fields are NUL-padded rather than NUL-terminated, so each is cut at the first NUL and
 * anything after it is padding. A field with no NUL at all is a full-width value, which is why the
 * cut is conditional.
 */
fun decodeProductData(payload: ByteArray): ProductData {
    require(payload.size == PRODUCT_DATA_SIZE) {
        "product data: expected $PRODUCT_DATA_SIZE bytes, got ${payload.size}"
    }
    var id = 0L
    for (i in 3 downTo 0) id = (id shl 8) or (payload[i].toLong() and 0xFF)
    return ProductData(
        productId = id,
        name = payload.cstring(4, 12),
        version = payload.cstring(16, 8),
    )
}

/** Reads a NUL-padded fixed-width field. */
private fun ByteArray.cstring(offset: Int, width: Int): String {
    var end = offset + width
    for (i in offset until offset + width) {
        if (this[i].toInt() == 0) {
            end = i
            break
        }
    }
    return String(this, offset, end - offset, Charsets.ISO_8859_1)
}
