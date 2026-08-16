package app.sendfpl.cxp

/**
 * One navigator's ARINC 702A parser limits.
 *
 * These are the *receiving* rules, and they are stricter than the encoder's: Garmin's builder
 * validates a user waypoint name against `UTL_strnlen(ident, 6)` while the parser that reads it
 * back takes five. The divergence is a finding about the pair, not a mistake in either.
 *
 * Every field is read out of that device's own parser, by module and address, and the addresses
 * are on each entry below. Nothing is inferred from another model: two devices sharing a value is
 * a measurement, and a device whose caps have not been read is absent from [Profiles] rather than
 * present with plausible numbers.
 *
 * `ProfileTest` pins the whole table against a copy transcribed by hand, so this data cannot be
 * edited casually: a change has to be made twice, on purpose.
 */
data class Profile(
    /** The model as Garmin's own product table spells it. */
    val name: String,
    /** The Connext product id the device reports, or 0 when it has not been resolved. */
    val productId: Long,
    /** The identifier cap the `:F:` handler passes to the parser's token reader. */
    val waypointNameLen: Int,
    /** The cap the `:DA:`/`:AA:` handler passes. An ICAO identifier, in other words. */
    val airportNameLen: Int,
    /** The cap the `.` handler passes, for the airway in `:F:.V334`. */
    val airwayNameLen: Int = 0,
    /** The first cap the `:D:`/`:A:`/`:AP:` handler passes, for the procedure name. */
    val procedureNameLen: Int = 0,
    /** That handler's second cap, for the transition after the dot in `KEPEC3.BTY`. */
    val transitionNameLen: Int = 0,
    /** The parser's own length check, applied before it looks at any element. A hard reject. */
    val maxRouteLen: Int,
) {
    /**
     * Whether this profile carries measured limits.
     *
     * Only the three caps every profile has ever carried are required. A cap added later and left
     * at zero means "not read for this device", and [buildRoute] then does not enforce it, which
     * is what it did for every device before that cap existed. Requiring the newer fields here
     * would instead retire a working profile over missing data, which is a worse answer than the
     * one the code already gave. `ProfileTest` holds the published table to a stricter rule.
     */
    val isValid: Boolean get() = waypointNameLen > 0 && airportNameLen > 0 && maxRouteLen > 0
}

/**
 * Thrown when a route is built for a navigator with no measured profile.
 *
 * Deliberately fatal rather than a fallback to the GPS 175 numbers, because **exceeding an
 * identifier cap does not truncate**. `FUN_e654bf2c` copies at most its limit and leaves the read
 * pointer inside the name, so the `:F:` handler's test for the comma before a position fails, the
 * position is never parsed, and the main loop then meets a character that is not a tag and flags
 * the whole message malformed. One name too long costs that point its coordinates and marks the
 * upload bad. A guessed cap therefore fails an upload rather than degrading it, and it fails
 * invisibly from the route text.
 */
class UnknownDeviceException(message: String) : Exception(message)

object Profiles {
    /**
     * GPS 175, GNC 355 and GNX 375: one product id, one firmware family.
     *
     * Read twice, from two artefacts that agree: the device build's `SYS_DBM.exe` at software 3.30,
     * and the PC trainer's `DBM.dll` at 3.21.2 (token reader `0x10006b00`; waypoint `0x100069c0`,
     * airport `0x10006bb8`, airway `0x10006c66`, procedure `0x10007076`, transition `0x100070c0`;
     * length check `0x100031c2`).
     */
    val GPS175 = Profile(
        name = "GNX 375/GPS 175/GNC 355",
        productId = PRODUCT_ID_G2N,
        waypointNameLen = 5,
        airportNameLen = 4,
        airwayNameLen = 5,
        procedureNameLen = 10,
        transitionNameLen = 5,
        maxRouteLen = 0xdc0,
    )

    /**
     * GTN 6xx/7xx, from the 6.72.9 trainer's `DBM_MAIN.dll`, whose parser lives in `udb_rte.c`
     * rather than the GPS 175's `udb_rte_prj.c`. Token reader `0x10007080`; waypoint `0x10006f2a`,
     * airport `0x1000714b`, airway `0x1000723a`, procedure `0x100076ab`, transition `0x100076f5`.
     *
     * Identical to [GPS175] in every field, as a result and not a copy: recovered independently
     * from a different module of a different product at a different software level. That is not
     * evidence the caps are a platform constant, though. The two parsers do differ, in a field no
     * profile carries: the `:H:` holding pattern handler reads a 5 character fix name here and a
     * 13 character one on the GTN. They agree on everything an encoder can emit, which is a
     * narrower claim and the one that holds.
     */
    val GTN = Profile(
        name = "GTN 6xx/7xx",
        productId = PRODUCT_ID_GTN,
        waypointNameLen = 5,
        airportNameLen = 4,
        airwayNameLen = 5,
        procedureNameLen = 10,
        transitionNameLen = 5,
        maxRouteLen = 0xdc0,
    )

    /** Keyed by Connext product id. Deliberately short: a device is here only once read. */
    val byProductId: Map<Long, Profile> = mapOf(
        GPS175.productId to GPS175,
        GTN.productId to GTN,
    )

    /**
     * Every name that selects a profile, including the aliases three models share.
     *
     * Insertion ordered, as [mapOf] is, so [names] and a picker built from it are stable.
     */
    private val byName: Map<String, Profile> = mapOf(
        "gps175" to GPS175,
        "gnc355" to GPS175,
        "gnx375" to GPS175,
        "gtn" to GTN,
    )

    /** Every selectable name, for help text. Several map to one profile. */
    val names: List<String> get() = byName.keys.toList()

    /**
     * One name per profile, which is what a picker wants: three of [names] select the same entry,
     * and a picker offering all four would draw the same chip three times.
     */
    val selectable: List<Pair<String, Profile>>
        get() = byName.entries.distinctBy { it.value.productId }.map { it.key to it.value }

    /** An unrecognised id is an error, never a default. */
    fun forProductId(id: Long): Profile =
        byProductId[id] ?: throw UnknownDeviceException("no flight plan profile for product id $id")

    fun named(name: String): Profile =
        byName[name] ?: throw UnknownDeviceException("no flight plan profile named \"$name\"")

    /**
     * The widest waypoint cap across [byProductId].
     *
     * Applied where no device is in hand, as a syntactic sanity bound that catches a name no
     * navigator could read. It is not the real limit, since the selected profile's cap is.
     */
    val maxAnyWaypointNameLen: Int get() = byProductId.values.maxOf { it.waypointNameLen }
}

// Restated here rather than read from [ProductId], deliberately: these come from the parser caps
// recovered per device, that table comes from Garmin Pilot's product list, and they are only
// believable while two independent sources agree. ProfileTest asserts they do, and unifying them
// would delete the check rather than satisfy it.
private const val PRODUCT_ID_G2N = 2800L
private const val PRODUCT_ID_GTN = 1026L
