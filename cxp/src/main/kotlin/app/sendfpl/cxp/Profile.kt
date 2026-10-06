package app.sendfpl.cxp

/**
 * One navigator's ARINC 702A parser limits.
 *
 * These are the *receiving* rules, and they are stricter than the encoder's: Garmin's builder
 * validates a user waypoint name against `UTL_strnlen(ident, 6)` while the parser that reads it
 * back takes five. The divergence is a finding about the pair, not a mistake in either.
 *
 * Every cap is read out of that device's own parser, by module and address, and the addresses are
 * on each entry below. Two fields are not caps and say so: [maxWaypoints] is published in the
 * model's pilot's guide, and [bareUserWaypoints] is a property of the flight plan builder after the
 * parser. Nothing is inferred from another model: two devices sharing a value is a measurement, and
 * a device whose caps have not been read is absent from [Profiles] rather than present with
 * plausible numbers.
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
    /** The cap the `.` handler passes, for the airway chained in `:F:SAC.V334.LIN`. */
    val airwayNameLen: Int = 0,
    /** The first cap the `:D:`/`:A:`/`:AP:` handler passes, for the procedure name. */
    val procedureNameLen: Int = 0,
    /** That handler's second cap, for the transition after the dot in `KEPEC3.BTY`. */
    val transitionNameLen: Int = 0,
    /** The parser's own length check, applied before it looks at any element. A hard reject. */
    val maxRouteLen: Int,
    /**
     * How many waypoints the navigator holds in one flight plan, or 0 when it has not been read
     * for this device.
     *
     * This is the one field not read from a parser. It is published in the model's pilot's guide,
     * and it binds a different thing: the parser accepts a route string up to [maxRouteLen] bytes,
     * and the flight plan the result goes into has its own capacity. The two are far apart. A
     * hundred points of `:F:XXXXX` is 800 bytes against a 3520 byte allowance, so a route can pass
     * every byte check and still be more plan than the navigator will hold.
     *
     * What it bounds is the number of points this encoder emits, which is a **lower bound** on
     * what the navigator ends up with: a procedure is expanded into its legs out of the
     * navigator's own database, and an airway is resolved there too. So a route inside this cap
     * can still overrun the unit. The check catches what is knowable from the string rather than
     * pretending to model what is not.
     */
    val maxWaypoints: Int = 0,
    /**
     * Sends a [UserWaypoint] as its position alone, `:F:N48475E002522`, instead of
     * `:F:LOBEL,N48475E002522`.
     *
     * A property of the builder, not a parser cap: both models parse both forms. A GPS 175 creates
     * a user waypoint of a name its database cannot place, whatever the import (`FUN_e6541f9c` in
     * the device build, the trainer's `FUN_10005f30`). A GTN creates one only when the import
     * activates the plan (`FUN_10005dd0` in the 6.72.9 trainer, `FUN_10010bd0` in the GTN Xi),
     * activation needs an `FPN/RP` message, and a Connext upload is `FPN/RI`, taken into the
     * catalogue as "Pending Preview". So a GTN locks the name, and Garmin's trainers show it doing
     * so. A bare position becomes a user waypoint on every builder: a GTN reuses a user waypoint it
     * already holds within 0.01 degree, with its name (`FUN_100089d0`), and otherwise names it
     * `USRnnn`. Garmin's GTN 725/750 Pilot's Guide, 190-01007-03 Rev. R, section 4.5, says the same
     * of its own client. A database point keeps its name on every model, see [Waypoint].
     *
     * False, the default, keeps the name.
     */
    val bareUserWaypoints: Boolean = false,
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
     *
     * [Profile.maxWaypoints] comes from a different source, this model's own pilot's guide, which
     * documents the same software release as the device build and states the limit twice: the
     * catalogue holds 99 flight plans of at most 100 waypoints each, and the active flight plan
     * page displays up to 100.
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
     * Its parser caps are identical to [GPS175]'s, as a result and not a copy: recovered
     * independently from a different module of a different product at a different software level.
     * That is not evidence the caps are a platform constant, though. The two parsers do differ, in a
     * field no profile carries: the `:H:` holding pattern handler reads a 5 character fix name on
     * the GPS 175 and a 13 character one here. They agree on everything an encoder can emit, which
     * is a narrower claim and the one that holds. What the two models differ in is what comes after
     * the parser: the size of an imported flight plan, and what becomes of a user waypoint's name.
     *
     * [Profile.maxWaypoints] is the GTN's own figure, from its own pilot's guide, the GTN 725/750
     * Pilot's Guide, 190-01007-03 Rev. R, which the 6.72.9 trainer ships. The catalogue holds flight
     * plans "of up to 100 waypoints", but an imported one is cut shorter, and an upload is an
     * import: "Flight plans over 99 waypoints long are truncated at 99 waypoints and the last
     * waypoint in the imported/uploaded flight plan may not be the destination airport" (section
     * 4.5). Until that guide was read this was left unset rather than copied from the GPS 175's 100,
     * which would have been one too many.
     *
     * [Profile.bareUserWaypoints]: a GTN locks a user waypoint's name on a Connext import. See the
     * field.
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
