package app.sendfpl.cxp

/**
 * One navigator's ARINC 702A parser limits: the receiving rules, which are stricter than Garmin's
 * encoder. Each cap is read out of that device's own parser, at the addresses given on its entry
 * in [Profiles], and never inferred from another model. `ProfileTest` pins the table against a
 * copy transcribed by hand.
 */
data class Profile(
    /** The model as Garmin's product table spells it, or its pilot's guide when it has none. */
    val name: String,
    /** The Connext product id the device reports, or 0 when it has not been resolved. */
    val productId: Long,
    /** The identifier cap the `:F:` handler passes to the parser's token reader. */
    val waypointNameLen: Int,
    /** The cap the `:DA:`/`:AA:` handler passes: an ICAO identifier. */
    val airportNameLen: Int,
    /** The cap the `.` handler passes, for the airway chained in `:F:SAC.V334.LIN`. */
    val airwayNameLen: Int = 0,
    /** The first cap the `:D:`/`:A:`/`:AP:` handler passes, for the procedure name. */
    val procedureNameLen: Int = 0,
    /** That handler's second cap, for the transition after the dot in `KEPEC3.BTY`. */
    val transitionNameLen: Int = 0,
    /** The parser's own length check, applied before it looks at any element. A hard reject. */
    val maxRouteLen: Int,
    /**
     * How many waypoints the navigator holds in one flight plan, from the model's pilot's guide,
     * or 0 when it has not been read. Not a parser cap, and far below [maxRouteLen]: a hundred
     * `:F:XXXXX` are 800 of its 3520 bytes. The points this encoder emits are a lower bound on what
     * the navigator ends up with, since it expands procedures and airways out of its own database.
     */
    val maxWaypoints: Int = 0,
    /**
     * Sends a [UserWaypoint] as its position alone, `:F:N48475E002522`, instead of
     * `:F:LOBEL,N48475E002522`.
     *
     * A property of the flight plan builder, not a parser cap: every model parses both forms. A
     * GPS 175 makes a user waypoint of a name its database cannot place (`FUN_e6541f9c` in the
     * device build, the trainer's `FUN_10005f30`). A GTN does so only when the import activates the
     * plan (`FUN_10005dd0` in the 6.72.9 trainer, `FUN_10010bd0` in the GTN Xi), which takes
     * `FPN/RP`, and a Connext upload is `FPN/RI`, so it locks the name. Sent a bare position, a GTN
     * reuses a user waypoint it holds within 0.01 degree, with its name (`FUN_100089d0`), or makes
     * one named `USRnnn`, as its pilot's guide says (section 4.5). A database point keeps its name
     * on every model: see [Waypoint].
     */
    val bareUserWaypoints: Boolean = false,
    /**
     * Reads a hyphen in a `:F:` element as the start of a second place and bearing,
     * `PMN090-MLN180`, rather than as part of a name, so a point named `BTG-A` is taken as BTG
     * wherever its position says. The GTN Xi's parser does (`FUN_10004580`); the GPS 175's and the
     * GTN's read the hyphen into the name. No navigation database holds a hyphenated identifier,
     * so such a name is refused rather than sent.
     */
    val hyphenSplitsPoint: Boolean = false,
) {
    /**
     * Whether the waypoint, airport and route length caps are known. Any other cap left at zero has
     * not been read for this device, and [buildRoute] does not enforce it. `ProfileTest` requires
     * every cap of a published profile.
     */
    val isValid: Boolean get() = waypointNameLen > 0 && airportNameLen > 0 && maxRouteLen > 0
}

/**
 * Thrown when a route is built for a navigator with no measured profile. Another model's caps will
 * not do, because a name over a cap is not truncated: `FUN_e654bf2c` copies at most the cap and
 * leaves the read pointer inside the name, the point is committed under the shorter name with any
 * position after it unread, and the main loop flags the whole message malformed.
 */
class UnknownDeviceException(message: String) : Exception(message)

object Profiles {
    /**
     * GPS 175, GNC 355 and GNX 375: one product id, one firmware family. Read from the device
     * build's `SYS_DBM.exe` at 3.30 and, agreeing, the PC trainer's `DBM.dll` at 3.21.2 (token
     * reader `0x10006b00`; waypoint `0x100069c0`, airport `0x10006bb8`, airway `0x10006c66`,
     * procedure `0x10007076`, transition `0x100070c0`; length check `0x100031c2`).
     * [Profile.maxWaypoints] is from its pilot's guide: 99 flight plans of at most 100 waypoints.
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
        maxWaypoints = 100,
    )

    /**
     * GTN 6xx/7xx, from the 6.72.9 trainer's `DBM_MAIN.dll`, whose parser lives in `udb_rte.c`
     * rather than the GPS 175's `udb_rte_prj.c`. Token reader `0x10007080`; waypoint `0x10006f2a`,
     * airport `0x1000714b`, airway `0x1000723a`, procedure `0x100076ab`, transition `0x100076f5`.
     *
     * Its caps equal [GPS175]'s, recovered independently rather than copied. The parsers agree on
     * everything an encoder can emit, not beyond: the `:H:` holding pattern handler reads a 5
     * character fix name on the GPS 175 and a 13 character one here.
     *
     * [Profile.maxWaypoints] is the import limit in the GTN 725/750 Pilot's Guide, 190-01007-03
     * Rev. R, section 4.5, and an upload is an import.
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
        maxWaypoints = 99,
        bareUserWaypoints = true,
    )

    /**
     * The GTN Xi series, from the 21.3.7 trainer's `dbm_sim.dll`, whose parser is Garmin's DLS one
     * (`FUN_100059c0`, `DLS_arinc_parse_fpl_uplink` in Garmin's own apps) rather than either of the
     * above. A handler tests a name's length after reading it: a waypoint by `cmp $0x5` at
     * `0x10004893` and `0x1000494e`, the rest with `FUN_10074f30`, a search for its NUL within the
     * cap plus one: airport `0x10002874`, airway `0x100044ef`, procedure `0x100037f0`, transition
     * `0x10003809`; length check `0x1000d83d`.
     *
     * Its product id is read, not seen on the wire: the trainer's system software is 006-B3188-XX,
     * and a Flight Stream 510 reports the number in its navigator's part number as that
     * navigator's id (`CXA_prod_info_set_cnnctd_lru`), as 006-B1026 is the GTN's 1026.
     * [Profile.maxWaypoints] is the import limit in the GTN Xi Pilot's Guide, 190-02327-03 Rev. G.
     */
    val GTN_XI = Profile(
        name = "GTN Xi",
        productId = PRODUCT_ID_GTN_XI,
        waypointNameLen = 5,
        airportNameLen = 4,
        airwayNameLen = 5,
        procedureNameLen = 10,
        transitionNameLen = 5,
        maxRouteLen = 0xdc0,
        maxWaypoints = 99,
        bareUserWaypoints = true,
        hyphenSplitsPoint = true,
    )

    /** Keyed by Connext product id. Deliberately short: a device is here only once read. */
    val byProductId: Map<Long, Profile> = mapOf(
        GPS175.productId to GPS175,
        GTN.productId to GTN,
        GTN_XI.productId to GTN_XI,
    )

    /**
     * Every name that selects a profile, aliases included. [mapOf] keeps insertion order, so a
     * picker built from it is stable.
     */
    private val byName: Map<String, Profile> = mapOf(
        "gps175" to GPS175,
        "gnc355" to GPS175,
        "gnx375" to GPS175,
        "gtn" to GTN,
        "gtnxi" to GTN_XI,
    )

    /** One name per profile, for a picker: three of the names select the same entry. */
    val selectable: List<Pair<String, Profile>>
        get() = byName.entries.distinctBy { it.value.productId }.map { it.key to it.value }

    /** An unrecognised id is an error, never a default. */
    fun forProductId(id: Long): Profile =
        byProductId[id] ?: throw UnknownDeviceException("no flight plan profile for product id $id")

    fun named(name: String): Profile =
        byName[name] ?: throw UnknownDeviceException("no flight plan profile named \"$name\"")

    /**
     * The widest waypoint cap across [byProductId]: a sanity bound where no device is in hand, not
     * the real limit, which is the selected profile's cap.
     */
    val maxAnyWaypointNameLen: Int get() = byProductId.values.maxOf { it.waypointNameLen }
}

// Restated rather than read from [ProductId], which comes from Garmin Pilot's product list:
// ProfileTest asserts the two sources agree, and unifying them would delete that check.
private const val PRODUCT_ID_G2N = 2800L
private const val PRODUCT_ID_GTN = 1026L
private const val PRODUCT_ID_GTN_XI = 3188L
