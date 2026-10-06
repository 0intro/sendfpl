package app.sendfpl.cxp

/**
 * Connext product ids, read out of `GMNConnextProductId` in Garmin Pilot 26.4.1, limited to those
 * a user of this app is plausibly talking to. `ProfileTest` checks them against the profiles.
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

    /**
     * Not in Garmin Pilot's table: the number in the GTN Xi's software part number, 006-B3188,
     * which a Flight Stream 510 reports as its id.
     */
    const val GTN_XI = 3188L

    const val GDL_60 = 3376L
}
