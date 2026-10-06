package app.sendfpl.cxp

/**
 * `CXP_ID_WAP_CONNECTED_LRU`, where a navigator says what it is: an LRU is a line replaceable unit,
 * the box at the other end of the wireless link. Not `CXP_ID_PRODUCT_DATA`, 0x00001000: the
 * navigator advertises that id but sends nothing on it, and answers a push there with ABORT.
 */
const val ID_CONNECTED_LRU = 0x00001006L

/** The wire size of the identification struct. */
internal const val PRODUCT_DATA_SIZE = 24

/**
 * A navigator's own account of what it is. A GPS 175 sends `2800`, `"GPS 175"` and `"3.22"`, the
 * version its panel shows.
 *
 * ```
 * u32  product_id       little-endian
 * char product_name[12] NUL-padded
 * char sw_version[8]    NUL-padded
 * ```
 */
data class ProductData(
    val productId: Long,
    val name: String,
    val version: String,
) {
    /**
     * The parser profile for this navigator, or null when none has been measured. The app then
     * builds the route for the model its picker shows, and says so, since another model's caps
     * can cost the whole plan: see [UnknownDeviceException].
     */
    val profile: Profile? get() = runCatching { Profiles.forProductId(productId) }.getOrNull()

    override fun toString() = "$productId \"$name\" software \"$version\""
}

/** Parses the identification struct. */
fun decodeProductData(payload: ByteArray): ProductData {
    require(payload.size == PRODUCT_DATA_SIZE) {
        "product data: expected $PRODUCT_DATA_SIZE bytes, got ${payload.size}"
    }
    return ProductData(
        productId = getU32(payload, 0),
        name = payload.cstring(4, 12),
        version = payload.cstring(16, 8),
    )
}

/** Reads a NUL-padded field, which holds no NUL when the value fills its width. */
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
