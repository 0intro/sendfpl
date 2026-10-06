package app.sendfpl.cxp

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

/**
 * Flight plan transfer over CXP, recovered from FltPlan Go's Connext message layer and
 * `libadb-lib.so` and checked against the firmware's `IOP_cxp_arinc702a_fpl_str_t`. The navigator
 * advertises what it accepts on [FplId.SUPPORTED_ELEMENTS], and the app uploads an ARINC 702A
 * route string on [FplId.UPLOAD_TO_AVIONICS].
 */

/**
 * Opens every route string. The navigator's parser, `FUN_e6523adc` in SYS_DBM.exe, skips four
 * characters when `str[3] == '/'` and goes on only for the subtype `RI` (0x12) or `RP` (0x14),
 * dropping anything else whole. `RI` is the uplink, as `ARINCFlightPlanManager` builds it; a
 * GPS 175 sends `FPN/RP` on 0x10005004 going the other way.
 */
const val ROUTE_PREFIX = "FPN/RI"

/** CXP IDs, from `CxpIdType`. Only the handful this app addresses are named here. */
object FplId {
    const val SUPPORTED_ELEMENTS = 0x10005000L
    const val UPLOAD_TO_AVIONICS = 0x10005001L
    const val MINIMAL_TRANSFER_TO_SIMPLE_AVIONICS = 0x10005004L
    const val DIRECT_TO_TRANSFER_TO_SIMPLE_AVIONICS = 0x10005011L
    const val USER_WAYPOINT_LIST = 0x10005030L
}

/** Route elements a navigator can accept (u32 bitmask). */
object Element {
    const val AIRWAY_DOT_NOTATION = 0x00001
    const val DEPARTURE = 0x00002
    const val ARRIVAL = 0x00004
    const val APPROACH = 0x00008
    const val WPT = 0x00010
    const val HOLD_AT_WPT = 0x00020
    const val ALONG_WPT = 0x00040
    const val COMPANY_ROUTE = 0x00080
    const val DES_FPL_SEG = 0x00100
    const val WPT_CLIMB = 0x00200
    const val REPORTING_PT = 0x00400
    const val LATERAL_OFFSET = 0x00800
    const val AIRWAY_INTERCEPT = 0x01000
    const val INTERCEPT_COURSE_FROM = 0x02000
    const val CRUISE_SEGMENT = 0x04000
    const val EXPLICIT_AIRPORT_DESIG = 0x08000
    const val STANDALONE_RUNWAYS = 0x10000
}

/** Waypoint notations a navigator can accept (u8 bitmask). */
object WaypointType {
    const val PUB_FMT = 0x1
    const val LAT_LON_USER = 0x2
    const val PB_PB_FMT = 0x4
    const val PDB_FMT = 0x8
}

/**
 * A route this cannot build or a coordinate this cannot read. [problem] names a refusal the pilot
 * can act on, and is null for one about a malformed file's internals: see [Problem].
 */
class FlightPlanException(message: String, val problem: Problem? = null) : Exception(message)

/** The navigator's advertised capabilities: 7 bytes, little endian. */
data class SupportedElements(
    val elements: Int = 0,
    val maxTextLength: Int = 0,
    val waypointTypes: Int = 0,
) {
    fun encode(): ByteArray {
        val out = ByteArray(SIZE)
        putU32(out, 0, elements.toLong() and 0xFFFFFFFFL)
        putU16(out, 4, maxTextLength)
        out[6] = (waypointTypes and 0xFF).toByte()
        return out
    }

    fun supports(element: Int): Boolean = (elements and element) != 0

    /** Waypoint notations live in their own byte, not the element mask. */
    fun supportsWaypointType(waypointType: Int): Boolean = (waypointTypes and waypointType) != 0

    companion object {
        const val SIZE = 7

        fun decode(data: ByteArray): SupportedElements {
            if (data.size != SIZE) throw FlightPlanException("expected $SIZE bytes, got ${data.size}")
            return SupportedElements(
                elements = getU32(data, 0).toInt(),
                maxTextLength = getU16(data, 4),
                waypointTypes = data[6].toInt() and 0xFF,
            )
        }
    }
}

// The route string's elements. Their order, and the order within each, follow
// UDB_export_flightplan() and the DLS_arinc_fpl_add_*_info() builders in libadb-lib.so.

sealed interface RouteElement { fun render(): String }

/**
 * A point the navigator's database is expected to know, emitted as `:F:<ident>`, or
 * `:F:<ident>,<latlon>` when [at] is set, as Garmin's guideline for GTN flight plan files writes
 * `:F:KSLE,N44546W123001`.
 *
 * [at] is the position a file gave the point, a tie breaker between two points of one name: the
 * navigator takes its own point of that name within 0.01 degree of it. Further off, a GPS 175
 * makes a user waypoint of it at the file's position and a GTN locks it. See [statedFix].
 */
data class Waypoint(val ident: String, val at: Coordinate? = null) : RouteElement {
    override fun render() = ":F:" + statedFix(ident, at, null)
}

/**
 * An airway, chained inside the `:F:` element of the points either side of it,
 * `:F:MANAK.UT183.TIRAV`, as [chainFixes] builds it and Garmin's guideline specifies. It is not an
 * element of its own: a GPS 175 reads a standalone `:F:.UT183` as the name of a waypoint, fails to
 * resolve it, and drops it. [render] returns the bare identifier.
 */
data class Airway(val ident: String) : RouteElement {
    override fun render() = ident
}

/** One entry in the run of `:F:` elements from the departure airport to the arrival airport. */
private data class Fix(val text: String, val isAirway: Boolean = false)

/**
 * Renders the run of `:F:` elements, folding each airway into the element of the point that joins
 * it, so an alternating chain is one element: `:F:LAX.T306.ELP.J183.CLL`, the guideline's own
 * example. An airway at either end of the run names no leg and is refused, as Garmin's client does.
 */
private fun chainFixes(run: List<Fix>): List<String> {
    val out = mutableListOf<String>()
    var i = 0
    while (i < run.size) {
        if (run[i].isAirway) {
            throw FlightPlanException(
                "airway \"${run[i].text}\" needs a waypoint before it to join at",
                Problem.AirwayWithoutEntry(run[i].text),
            )
        }
        val seg = StringBuilder(run[i].text)
        i++
        while (i < run.size && run[i].isAirway) {
            if (i + 1 >= run.size || run[i + 1].isAirway) {
                throw FlightPlanException(
                    "airway \"${run[i].text}\" needs a waypoint after it to leave at",
                    Problem.AirwayWithoutExit(run[i].text),
                )
            }
            seg.append(".").append(run[i].text).append(".").append(run[i + 1].text)
            i += 2
        }
        out += ":F:$seg"
    }
    return out
}

/**
 * A user waypoint at a position, emitted as `:F:<13 chars>` with no separator:
 * `dls_arinc_utl_add_waypoint_type_latlon` (@ 0xca4e8) formats `:F:%s` with the position alone.
 */
data class Coordinate(val lat: Double, val lon: Double) : RouteElement {
    override fun render() = ":F:${formatLatLon(lat, lon)}"

    /** The wire form, which is also how the route echoes back to a user. */
    val ident: String get() = formatLatLon(lat, lon)
}

/**
 * A point of the pilot's own, emitted as `:F:<name>,<latlon>`, or as `:F:<latlon>` to a navigator
 * that would lock the name: see [Profile.bareUserWaypoints]. A point a database knows is a
 * [Waypoint].
 *
 * The position decides before the name: the `:F:` handler records both, and the builder after it
 * (`FUN_e6541f9c`, `FUN_e653a784` in the GPS 175's database manager) takes a database point of
 * that name only within 0.01 degree of the position, on each axis.
 *
 * `dls_arinc_utl_add_waypoint_type_published` writes `:F:%s` and then the position with `",%s"`
 * (`DAT_0020f833` in Garmin Pilot's ARM64 `libadb-lib.so`, `DAT_001fc3f6` in FltPlan Go's ARM32
 * one). Which capability bit gates this form is unconfirmed: the builder is the published one,
 * suggesting `PUB_FMT`, but it writes a position, suggesting `LAT_LON_USER`. [buildRoute] takes
 * `LAT_LON_USER` and falls back to the name, so a wrong guess costs the position, not the upload.
 */
data class UserWaypoint(val ident: String, val lat: Double, val lon: Double) : RouteElement {
    override fun render(): String {
        checkName()
        return ":F:$ident,${formatLatLon(lat, lon)}"
    }

    /** Name and position as one token, so what the user is shown parses back to what is sent. */
    val display: String get() = "$ident,${formatLatLon(lat, lon)}"

    /**
     * Rejects a name the navigator cannot read back: empty, longer than [max], or holding anything
     * but the upper-case letters and digits `dls_str_is_alphanum_or_delims` allows on the encoding
     * side. [buildRoute] passes the device's cap.
     */
    fun checkName(max: Int = Profiles.maxAnyWaypointNameLen) {
        if (ident.isEmpty()) throw FlightPlanException("user waypoint has no name")
        if (ident.length > max) {
            throw FlightPlanException(
                "user waypoint name \"$ident\" is ${ident.length} characters, " +
                    "the navigator reads $max and would lose the position",
                Problem.WaypointNameTooLong(ident, ident.length, max),
            )
        }
        if (!ident.all { it in '0'..'9' || it in 'A'..'Z' }) {
            throw FlightPlanException(
                "user waypoint name \"$ident\" is not upper-case letters and digits",
                Problem.WaypointNameNotAlphanumeric(ident),
            )
        }
    }
}

/**
 * How many characters of a name the route tokenizer takes. Wider than any
 * [Profile.waypointNameLen], so a name too long stays one token and is refused by name rather than
 * split into two points.
 */
internal const val NAME_TOKEN_LEN = 8

// Positions on the wire. `dls_format_posn_to_str` (libadb-lib.so @ 0xcac48) formats
// `%c%02u%03d%c%03u%03d` (0x1EC3FE), as Garmin Pilot's ARINCDataUtil.formatLatitudeLongitude()
// does in Java:
//
//     N | degrees in 2 digits | tenths of a minute
//     E | degrees in 3 digits | tenths of a minute
//
// so 48.82 N, 2.62 E is `N48492E002372`. Both decoders divide the 3 digit fields by 600.0, which
// pins them to tenths of a minute.

/** Length of the encoded form, the only one `dls_arinc_utl_validate_lat_lon_str` accepts. */
internal const val LATLON_LEN = 13

/**
 * The bounds of a position typed or read from a file: ICAO's, the poles and the antimeridian.
 * [formatLatLon] keeps the looser longitude bound of Garmin's encoder.
 */
const val MAX_LATITUDE = 90.0
const val MAX_LONGITUDE = 180.0

/**
 * Longest token that could be a coordinate, `48.12345678N/122.12345678W` and some slack. Checked
 * before the regex: this runs on every keystroke, and adjacent digit runs make a backtracking
 * engine try every split point.
 */
private const val LATLON_MAX_TOKEN = 32

/**
 * One coordinate as a single token: a hemisphere before or after each half, and an optional `/`
 * between them. Every quantifier is bounded, for the reason above.
 */
private val LATLON =
    Regex("""^([NS])?(\d{1,7}(?:\.\d{1,8})?)([NS])?/?([EW])?(\d{1,7}(?:\.\d{1,8})?)([EW])?$""")

/**
 * A coordinate as typed, with a decimal comma or a space or comma between the halves, to be
 * rewritten as one token before the route is split; [parseLatLon] decides what is valid. Both
 * halves need a hemisphere letter, which keeps `V334, LIN` out.
 */
private val LOOSE_LATLON = Regex(
    """(?<![A-Z0-9.,])""" +
        """([NS]\d{1,6}(?:[.,]\d{1,8})?|\d{1,6}(?:[.,]\d{1,8})?[NS])""" +
        """[ \t]*[/,]?[ \t]*""" +
        """([EW]\d{1,6}(?:[.,]\d{1,8})?|\d{1,6}(?:[.,]\d{1,8})?[EW])""" +
        """(?![A-Z0-9.,])""",
    RegexOption.IGNORE_CASE,
)

/**
 * Whole degrees and tenths of a minute, as the binary computes them: degrees truncate and tenths
 * round half away from zero (`MTH_round_d` @ 0xeabde). Garmin's encoder stops there, so tenths
 * that round up to 600 stay 600. [carryTenths] is where this departs from it.
 */
private fun splitDegrees(value: Double): Pair<Int, Int> {
    val magnitude = abs(value)
    val degrees = magnitude.toInt()
    return degrees to floor((magnitude - degrees) * 600 + 0.5).toInt()
}

/**
 * Tenths that rounded up to 600 carried into the degrees, which Garmin's encoder does not do. Its
 * Android decoder reads `E002600` back as 3 degrees, but a GPS 175 stores it as 2°59.9′, a tenth of
 * a minute short of the place it was rounded to, whether the plan came by upload or from a card.
 */
private fun carryTenths(split: Pair<Int, Int>): Pair<Int, Int> {
    val (degrees, tenths) = split
    return if (tenths >= 600) degrees + 1 to tenths - 600 else split
}

/**
 * Encodes a position as the 13 character wire form, within the bounds of Garmin's encoder,
 * `dls_arinc_format_posn_to_str`, whose 360 degrees of longitude are wider than [MAX_LONGITUDE].
 * The navigator's parser checks no range at all. The tenths of a minute are rounded and then
 * carried by [carryTenths], so that 2.99972 degrees is `E003000` where Garmin's encoder writes
 * `E002600`.
 */
fun formatLatLon(lat: Double, lon: Double): String {
    // A NaN passes every range check below, since every comparison with it is false, and would
    // encode as 0, 0.
    if (lat.isNaN() || lon.isNaN()) throw FlightPlanException("coordinate is not a number")
    // The binary checks the truncated degrees (<= 90 / <= 360), so it would pass 90.4.
    if (lat < -90.0 || lat > 90.0) throw FlightPlanException("latitude $lat is outside -90..90")
    if (lon < -360.0 || lon > 360.0) throw FlightPlanException("longitude $lon is outside -360..360")
    val (latDeg, latTenths) = carryTenths(splitDegrees(lat))
    val (lonDeg, lonTenths) = carryTenths(splitDegrees(lon))
    // `%d` is localised: without Locale.ROOT an Arabic or Persian locale writes Arabic-Indic
    // digits, a defect Garmin Pilot's own encoder has.
    return String.format(
        Locale.ROOT, "%s%02d%03d%s%03d%03d",
        if (lat >= 0) "N" else "S", latDeg, latTenths,
        if (lon >= 0) "E" else "W", lonDeg, lonTenths,
    )
}

/** One half of a coordinate, in whichever notation it was written. */
private fun degreesFromDigits(text: String, isLon: Boolean): Double {
    val width = if (isLon) 3 else 2
    val dot = text.indexOf('.')
    if (dot >= 0) {
        // A decimal point after more digits than the degree field holds is ambiguous: `00237.2` is
        // 2 degrees 37.2 minutes, and read as 237.2 degrees it is in range and silently wrong.
        if (dot > width) {
            throw FlightPlanException(
                "${if (isLon) "longitude" else "latitude"} \"$text\" is ambiguous: $dot digits " +
                    "before the decimal point, but a ${if (isLon) "longitude" else "latitude"} " +
                    "degree field holds $width. Write decimal degrees, ${width + 2} digits for " +
                    "whole minutes, or ${width + 3} digits for tenths of a minute",
                Problem.AmbiguousCoordinate(text, isLon, width),
            )
        }
        return text.toDouble()
    }
    if (text.length <= width) return text.toInt().toDouble()
    val degrees = text.substring(0, width).toInt()
    val rest = text.substring(width)
    val minutes = rest.toIntOrNull()
    // 600 tenths stay legal: Garmin's own encoder rounds 59.96 minutes up to 600.
    if (minutes != null && (rest.length == 2 && minutes >= 60 || rest.length == 3 && minutes > 600)) {
        throw FlightPlanException(
            "${if (isLon) "longitude" else "latitude"} \"$text\" has 60 minutes or more",
            Problem.MinutesOutOfRange(text, isLon),
        )
    }
    return when (rest.length) {
        2 -> degrees + rest.toInt() / 60.0        // DDMM / DDDMM, the ICAO form
        3 -> degrees + rest.toInt() / 600.0       // DDMMM / DDDMMM, the wire form
        else -> throw FlightPlanException(
            "cannot read ${if (isLon) "longitude" else "latitude"} \"$text\": expected decimal " +
                "degrees, ${width + 2} digits (whole minutes) or ${width + 3} digits " +
                "(tenths of a minute)",
            Problem.UnreadableCoordinate(text, isLon, width),
        )
    }
}

/**
 * Decodes a coordinate token, or returns null if it is not one, so the caller can treat it as a
 * waypoint. A malformed coordinate throws rather than becoming a waypoint named `N48492E00237`.
 */
fun parseLatLon(token: String): Coordinate? {
    val text = token.trim()
    if (text.length > LATLON_MAX_TOKEN) return null
    val match = LATLON.matchEntire(text.uppercase()) ?: return null
    val (latPre, latNum, latPost, lonPre, lonNum, lonPost) = match.destructured
    if ((latPre.isNotEmpty() && latPost.isNotEmpty()) ||
        (lonPre.isNotEmpty() && lonPost.isNotEmpty())
    ) {
        throw FlightPlanException(
            "\"$token\" has two hemispheres on one coordinate",
            Problem.TwoHemispheres(token),
        )
    }
    val latHemi = latPre.ifEmpty { latPost }
    val lonHemi = lonPre.ifEmpty { lonPost }
    if (latHemi.isEmpty() || lonHemi.isEmpty()) return null
    val lat = degreesFromDigits(latNum, isLon = false).let { if (latHemi == "S") -it else it }
    val lon = degreesFromDigits(lonNum, isLon = true).let { if (lonHemi == "W") -it else it }
    // Refused here, where the input still has a name, rather than at encode time.
    if (lat < -MAX_LATITUDE || lat > MAX_LATITUDE) {
        throw FlightPlanException(
            "\"$token\" has latitude $lat, outside -90..90",
            Problem.LatitudeOutOfRange(token, lat),
        )
    }
    if (lon < -MAX_LONGITUDE || lon > MAX_LONGITUDE) {
        throw FlightPlanException(
            "\"$token\" has longitude $lon, outside -180..180",
            Problem.LongitudeOutOfRange(token, lon),
        )
    }
    return Coordinate(lat, lon)
}

/**
 * Rewrites typed coordinates as single tokens, with a point for a decimal comma and `/` between
 * the halves, so that splitting the route on commas and spaces leaves them whole.
 */
fun normaliseCoordinates(text: String): String = LOOSE_LATLON.replace(text) { match ->
    val (lat, lon) = match.destructured
    "${lat.replace(",", ".")}/${lon.replace(",", ".")}"
}

/**
 * A named user waypoint as [UserWaypoint.display] writes it: up to [NAME_TOKEN_LEN] characters of
 * name, a comma, and the wire position. [LOOSE_LATLON] refuses a position after a comma, so without
 * this fold the route would split there into two points. Unlike it, this allows a comma on either
 * side, so it folds in a list separated by commas: its fixed shape cannot be misread.
 */
private val NAMED_USER_WAYPOINT = Regex(
    """(?<![A-Z0-9./])([A-Z0-9]{1,$NAME_TOKEN_LEN}),([NS]\d{5}[EW]\d{6})(?![A-Z0-9./])""",
    RegexOption.IGNORE_CASE,
)

/** Rewrites `NAME,<position>` as one token, as [normaliseCoordinates] does a bare coordinate. */
fun normaliseUserWaypoints(text: String): String = NAMED_USER_WAYPOINT.replace(text) { match ->
    "${match.groupValues[1]}/${match.groupValues[2]}"
}

/** The folded form, as [normaliseUserWaypoints] leaves it and the route parser reads it. */
val USER_WAYPOINT_TOKEN =
    Regex("""^([A-Z0-9]{1,$NAME_TOKEN_LEN})/([NS]\d{5}[EW]\d{6})$""")

/**
 * `:DA:<apt>[:D:<sid>[.<trans>]][:R:<rwy>]`
 *
 * [at] is the aerodrome's position, sent on its `:F:` fix and never on `:DA:`, whose handler reads
 * four characters and stops. See [statedFix].
 */
data class Departure(
    val airport: String,
    val procedure: String? = null,
    val transition: String? = null,
    val runway: String? = null,
    val at: Coordinate? = null,
) {
    fun render(): String = buildString {
        append(":DA:").append(airport)
        if (!procedure.isNullOrEmpty()) {
            append(":D:").append(procedure)
            if (!transition.isNullOrEmpty()) append('.').append(transition)
        }
        if (!runway.isNullOrEmpty()) append(":R:").append(runway)
    }
}

/**
 * `:AA:<apt>[:A:<star>[.<trans>][(<rwy>)]]`
 *
 * The runway rides in parentheses on the procedure, as Garmin's guideline specifies, while a
 * departure keeps its `:R:`. A GPS 175 drops `:A:MOU9W.MOU:R:27R` as an unknown procedure and
 * expands `:A:MOU9W.MOU(27R)` into its legs. With no procedure to carry it, the runway is dropped:
 * the grammar has nowhere else for it.
 */
data class Arrival(
    val airport: String,
    val procedure: String? = null,
    val transition: String? = null,
    val runway: String? = null,
    /** The aerodrome's position, on its `:F:` fix and never on `:AA:`, as on [Departure.at]. */
    val at: Coordinate? = null,
) {
    fun render(): String = buildString {
        append(":AA:").append(airport)
        if (!procedure.isNullOrEmpty()) {
            append(":A:").append(procedure)
            if (!transition.isNullOrEmpty()) append('.').append(transition)
            if (!runway.isNullOrEmpty()) append('(').append(runway).append(')')
        }
    }
}

/** One or two digits and an optional location letter: what [normaliseRunway] can repair. */
private val RUNWAY = Regex("^([0-9]{1,2})([LCRO]?)$")

/**
 * Puts a runway in the only shape the navigator's parser reads, exactly two digits and one location
 * letter, or refuses it. `O` is Garmin's "no location specified": a GPS 175 fails the whole import
 * on `:R:26` and takes `:R:26O`.
 */
internal fun normaliseRunway(runway: String?): String? {
    if (runway.isNullOrEmpty()) return null
    val m = RUNWAY.matchEntire(runway.trim().uppercase())
        ?: throw FlightPlanException(
            "runway \"$runway\" is not two digits and a location letter, " +
                "which is the only shape the navigator reads"
        )
    val number = m.groupValues[1].toInt()
    if (number < 1 || number > 36) {
        throw FlightPlanException("runway \"$runway\" is not a runway number")
    }
    // Not String.format, which writes the digits of the phone's locale.
    return number.toString().padStart(2, '0') + m.groupValues[2].ifEmpty { "O" }
}

/** `:AP:<app>[.<trans>]` */
data class Approach(val procedure: String, val transition: String? = null) {
    fun render(): String = buildString {
        append(":AP:").append(procedure)
        if (!transition.isNullOrEmpty()) append('.').append(transition)
    }
}

/**
 * Assembles an ARINC 702A route string.
 *
 * With [capabilities], a procedure or a position the navigator does not advertise is left out, and
 * an airway, a coordinate or an approach is refused. A point goes out according to what it is, the
 * navigator's model and its capabilities:
 *
 * ```
 * element                       capabilities null, or LAT_LON_USER   without LAT_LON_USER
 * Waypoint                      NAME                                 NAME
 * Waypoint with at, aerodrome   NAME,POSN                            NAME
 * UserWaypoint, GPS 175         NAME,POSN                            NAME
 * UserWaypoint, GTN or GTN Xi   POSN, or NAME,POSN (1)               NAME
 * Coordinate                    POSN                                 error
 *
 * (1) at an airway's entry or exit
 * ```
 *
 * A user waypoint's name is checked against the model's cap even where it is not sent, so that a
 * route is valid on every model. A point named with a hyphen is refused for the GTN Xi, which reads
 * the hyphen as the start of another point: see [Profile.hyphenSplitsPoint].
 *
 * The departure and arrival airports go out as `:F:` elements, as Garmin Pilot sends them, because
 * only `:F:` elements become waypoints. `:DA:` and `:AA:` add no leg: a GPS 175 imports
 * `FPN/RI:DA:LFPL:F:PMN:AA:LFPK` as PMN alone and fails `FPN/RI:DA:LFPL:AA:LFPK` as empty. They
 * are sent only to carry a procedure or a runway.
 */
fun buildRoute(
    device: Profile,
    departure: Departure? = null,
    enroute: List<RouteElement> = emptyList(),
    arrival: Arrival? = null,
    approach: Approach? = null,
    capabilities: SupportedElements? = null,
): String {
    if (!device.isValid) {
        throw UnknownDeviceException("no flight plan profile for \"${device.name}\"")
    }
    val parts = mutableListOf<String>()
    // The `:F:` elements from the departure airport to the arrival airport, airways included,
    // rendered once by [chainFixes].
    val run = mutableListOf<Fix>()

    // Over [Profile.airportNameLen] the `:DA:`/`:AA:` handler stops inside the identifier, and the
    // rest of the route desynchronises behind it.
    departure?.let { checkIdentifier(it.airport) }
    arrival?.let { checkIdentifier(it.airport) }
    // Each is a `:F:` fix too.
    departure?.let { device.checkPointName(it.airport) }
    arrival?.let { device.checkPointName(it.airport) }
    listOf(
        Problem.AirportNameTooLong.End.DEPARTURE to departure?.airport,
        Problem.AirportNameTooLong.End.ARRIVAL to arrival?.airport,
    ).forEach { (end, apt) ->
        if (apt != null && apt.length > device.airportNameLen) {
            val what = end.name.lowercase()
            throw FlightPlanException(
                "$what airport \"$apt\" is ${apt.length} characters, " +
                    "${device.name} reads ${device.airportNameLen}",
                Problem.AirportNameTooLong(
                    end, apt, apt.length, device.name, device.airportNameLen,
                ),
            )
        }
    }

    departure?.let { d ->
        val eff = (if (capabilities != null && !capabilities.supports(Element.DEPARTURE)) {
            d.copy(procedure = null, transition = null)
        } else d).let { it.copy(runway = normaliseRunway(it.runway)) }
        // After the downgrade: a procedure that is not sent cannot desynchronise the parser.
        device.checkProcedure(eff.procedure, eff.transition)
        // `:DA:` only carries a procedure or a runway; the airport is its `:F:` fix.
        if (!eff.procedure.isNullOrEmpty() || eff.runway != null) parts += eff.render()
        run += Fix(statedFix(eff.airport, eff.at, capabilities))
    }

    val noLatLon = capabilities != null && !capabilities.supportsWaypointType(WaypointType.LAT_LON_USER)
    for ((i, item) in enroute.withIndex()) {
        run += when (item) {
            // The aerodrome's rule, which render() cannot apply without the capabilities.
            is Waypoint -> {
                device.checkIdent(Problem.ElementIdentTooLong.Kind.WAYPOINT, item.ident, device.waypointNameLen)
                device.checkPointName(item.ident)
                Fix(statedFix(item.ident, item.at, capabilities))
            }

            is Airway -> {
                device.checkIdent(Problem.ElementIdentTooLong.Kind.AIRWAY, item.ident, device.airwayNameLen)
                if (capabilities != null && !capabilities.supports(Element.AIRWAY_DOT_NOTATION)) {
                    throw FlightPlanException(
                        "navigator does not support airway dot notation, cannot encode ${item.ident}"
                    )
                }
                Fix(item.render(), isAirway = true)
            }

            is Coordinate -> {
                if (noLatLon) {
                    throw FlightPlanException(
                        "navigator does not support lat/lon user waypoints, cannot encode ${item.ident}"
                    )
                }
                Fix(item.render().removePrefix(":F:"))
            }

            // Unlike a coordinate, a named point degrades to its name, which the navigator can
            // still look up.
            is UserWaypoint -> {
                item.checkName(device.waypointNameLen)
                when {
                    noLatLon -> Fix(item.ident)
                    // Not at an airway's entry or exit: an airway is joined at a database fix,
                    // and a bare position there would cost the airway on every model.
                    device.bareUserWaypoints && !joinsAirway(enroute, i) -> Fix(formatLatLon(item.lat, item.lon))
                    else -> Fix(item.render().removePrefix(":F:"))
                }
            }
        }
    }

    // The arrival as it is sent, after the downgrade.
    val arrived = arrival?.let { a ->
        (if (capabilities != null && !capabilities.supports(Element.ARRIVAL)) {
            a.copy(procedure = null, transition = null)
        } else a).let { it.copy(runway = normaliseRunway(it.runway)) }
    }
    if (arrived != null) {
        device.checkProcedure(arrived.procedure, arrived.transition)
        // The arrival airport closes the run, and an airway may end at it.
        run += Fix(statedFix(arrived.airport, arrived.at, capabilities))
    }
    if (run.isNotEmpty()) parts += chainFixes(run)

    // `:AA:` only with a procedure, which carries the runway (see [Arrival]). Without one, an
    // approach names the aerodrome below.
    val arrivalNamed = arrived != null && !arrived.procedure.isNullOrEmpty()
    if (arrivalNamed) parts += arrived.render()

    // See [Profile.maxWaypoints] for why this count is a lower bound.
    val points = run.count { !it.isAirway }
    if (device.maxWaypoints > 0 && points > device.maxWaypoints) {
        throw FlightPlanException(
            "the route has $points waypoints, and ${device.name} holds " +
                "${device.maxWaypoints} in one flight plan",
            Problem.TooManyWaypoints(points, device.maxWaypoints, device.name),
        )
    }

    approach?.let {
        // An approach needs its aerodrome in an `:AA:`, as Garmin's guideline says: a GPS 175
        // imports `:F:LFPL:F:PMN:F:LFPN:AP:I25R` without the approach.
        if (arrival == null) throw FlightPlanException("an approach needs the arrival aerodrome")
        if (capabilities != null && !capabilities.supports(Element.APPROACH)) {
            throw FlightPlanException("navigator does not support approaches")
        }
        // Unlike a departure's or an arrival's, an approach is nothing without its procedure.
        if (it.procedure.isEmpty()) throw FlightPlanException("empty approach procedure")
        device.checkProcedure(it.procedure, it.transition)
        if (!arrivalNamed) parts += ":AA:" + arrival.airport
        parts += it.render()
    }

    val body = parts.joinToString("")
    if (body.isEmpty()) throw FlightPlanException("empty route")
    return ROUTE_PREFIX + body
}

/**
 * The `:F:` fix of an aerodrome or a [Waypoint], with its position unless the navigator has said it
 * reads no lat/lon user waypoints. Garmin's client writes an aerodrome this way,
 * `:F:LFPL,N48493E002374`: the position breaks a tie between two points of one name. The rule is
 * the same on every model, since a GTN resolves a database point by name and position as a GPS 175
 * does.
 */
private fun statedFix(ident: String, at: Coordinate?, capabilities: SupportedElements?): String {
    if (at == null) return ident
    if (capabilities != null && !capabilities.supportsWaypointType(WaypointType.LAT_LON_USER)) {
        return ident
    }
    return "$ident,${formatLatLon(at.lat, at.lon)}"
}

/** Whether the enroute element at [i] is an airway's entry or exit. */
private fun joinsAirway(enroute: List<RouteElement>, i: Int): Boolean =
    enroute.getOrNull(i - 1) is Airway || enroute.getOrNull(i + 1) is Airway

/**
 * Refuses an identifier this navigator's parser cannot read whole: see [UnknownDeviceException]
 * for what a name over a cap does. Garmin's own encoder truncates instead, measuring a token with
 * `UTL_strnlen(tok, 6)` and copying six characters into a field the parser reads five of, but a
 * shorter identifier names a different place. A cap of zero is not enforced. See [Profile.isValid].
 */
private fun Profile.checkIdent(kind: Problem.ElementIdentTooLong.Kind, ident: String, max: Int) {
    checkIdentifier(ident)
    if (max <= 0 || ident.length <= max) return
    val what = kind.name.lowercase()
    throw FlightPlanException(
        "$what \"$ident\" is ${ident.length} characters, $name reads $max",
        Problem.ElementIdentTooLong(kind, ident, ident.length, name, max),
    )
}

/** Refuses a name a `:F:` element would not carry to this device whole. */
private fun Profile.checkPointName(ident: String) {
    if (hyphenSplitsPoint && '-' in ident) {
        throw FlightPlanException(
            "\"$ident\": the $name reads its hyphen as the start of another point",
            Problem.HyphenInPointName(ident, name),
        )
    }
}

/** Applies the two caps the `:D:`/`:A:`/`:AP:` handler passes, for procedure and transition. */
private fun Profile.checkProcedure(procedure: String?, transition: String?) {
    if (!procedure.isNullOrEmpty()) {
        checkIdent(Problem.ElementIdentTooLong.Kind.PROCEDURE, procedure, procedureNameLen)
    }
    if (!transition.isNullOrEmpty()) {
        checkIdent(Problem.ElementIdentTooLong.Kind.TRANSITION, transition, transitionNameLen)
    }
}

/**
 * Refuses an identifier the navigator cannot be sent: empty, or holding anything but the upper-case
 * letters, digits and hyphens Garmin Pilot's encoder allows, as in an approach named `R16-Y`
 * (`dls_arinc_utl_validate_waypoint_ident`, through `dls_str_is_alphanum_or_delims(id, NULL, n,
 * 1)`). The navigator's token reader `FUN_e654bf2c` also reads lower case, but stops at any other
 * character, and the parser then fails the import or misreads the rest.
 */
private fun checkIdentifier(ident: String) {
    if (ident.isEmpty()) throw FlightPlanException("an empty identifier", Problem.EmptyIdentifier)
    if (ident.any { it !in 'A'..'Z' && it !in '0'..'9' && it != '-' }) {
        throw FlightPlanException(
            "\"$ident\" is not an identifier, which is upper-case letters, digits and hyphens",
            Problem.BadIdentifier(ident),
        )
    }
}

/**
 * Encodes a route string as the UPLOAD_TO_AVIONICS payload: its raw bytes, with no framing, the
 * stream close delimiting them. ASCII only, where both ends agree: the receiver reads Latin 1
 * (`(char)readUnsignedByte()`), and Garmin's sender writes the platform default.
 */
fun encodeUpload(
    route: String,
    device: Profile,
    capabilities: SupportedElements? = null,
): ByteArray {
    if (!device.isValid) {
        throw UnknownDeviceException("no flight plan profile for \"${device.name}\"")
    }
    if (route.isEmpty()) throw FlightPlanException("empty route string")
    if (route.any { it.code > 0x7F }) {
        throw FlightPlanException("route must be ASCII: ${route.filter { it.code > 0x7F }}")
    }
    val payload = route.toByteArray(Charsets.US_ASCII)
    val max = capabilities?.maxTextLength ?: 0
    if (max > 0 && payload.size > max) {
        throw FlightPlanException(
            "route is ${payload.size} bytes, navigator advertised a maximum of $max"
        )
    }
    // The profile's limit holds even where the navigator advertises no maximum.
    if (payload.size > device.maxRouteLen) {
        throw FlightPlanException(
            "route is ${payload.size} bytes, ${device.name} rejects over ${device.maxRouteLen}",
            Problem.RouteTooLong(payload.size, device.maxRouteLen, device.name),
        )
    }
    return payload
}

/** Decodes an UPLOAD_TO_AVIONICS payload back to its route string. */
internal fun decodeUpload(payload: ByteArray): String = String(payload, Charsets.ISO_8859_1)
