package app.sendfpl.cxp

/**
 * Connext product IDs, read out of `GMNConnextProductId` in Garmin Pilot 26.4.1.
 *
 * A navigator reports one of these, and it is what selects a [Profile], so the numbers here and
 * the `productId` on each profile are two independent statements of the same fact, and `ProfileTest`
 * asserts they agree. That is the point of stating them twice.
 *
 * The GPS 175 shares [G2N] with the GNC 355 and GNX 375, and Garmin's own display name is literally
 * "GNX 375/GPS 175/GNC 355". There is no separate `GPS175` constant. Note that [G2N] is *not* the
 * GTN family. [GTN_6XX_7XX] is a distinct entry.
 *
 * Only the entries a user of this app is plausibly talking to are listed. Garmin's table is longer,
 * and the omissions are deliberate rather than unknown.
 */
object ProductId {
    const val UNKNOWN = -1L
    const val GNS = 531L
    const val GTN_6XX_7XX = 1026L
    const val GI_275 = 1125L
    const val NXI = 1177L
    const val G3X_TOUCH = 1727L
    const val FLIGHT_STREAM_GRT2X = 1779L
    const val FLIGHT_STREAM_510 = 2021L
    const val GDL_5X = 2513L

    /** GNX 375 / GPS 175 / GNC 355. */
    const val G2N = 2800L

    const val GDL_60 = 3376L

    private val names = mapOf(
        UNKNOWN to "Unknown",
        GNS to "GNS",
        GTN_6XX_7XX to "GTN 6xx/7xx",
        GI_275 to "GI 275",
        NXI to "NXi",
        G3X_TOUCH to "G3X Touch",
        FLIGHT_STREAM_GRT2X to "Flight Stream GRT2x",
        FLIGHT_STREAM_510 to "Flight Stream 510",
        GDL_5X to "GDL 50/52",
        G2N to "GNX 375 / GPS 175 / GNC 355",
        GDL_60 to "GDL 60",
    )

    fun name(id: Long): String = names[id] ?: "Product $id"
}
