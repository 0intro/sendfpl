package app.sendfpl.cxp

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

/**
 * Flight plan transfer over CXP.
 *
 * Recovered from FltPlan Go's Connext message layer and `libadb-lib.so`, cross-checked against
 * the firmware's `IOP_cxp_arinc702a_fpl_str_t`.
 *
 * Two messages matter: the navigator advertises what it accepts on
 * [FplId.SUPPORTED_ELEMENTS], then the app uploads an ARINC 702A route string on
 * [FplId.UPLOAD_TO_AVIONICS]. The upload has **no framing of its own**: raw string bytes,
 * delimited by the stream close.
 */

/**
 * Opens every ARINC 702A route string the navigator will look at.
 *
 * Its parser, `FUN_e6523adc` in SYS_DBM.exe, skips four characters when `str[3] == '/'` and then
 * matches the next two against a subtype table of 29 entries, proceeding only for `RI` (0x12) or `RP`
 * (0x14). A string starting at `:DA:` fails all of that: nothing is skipped, ":D" matches no
 * subtype, and the message is dropped whole.
 *
 * `RI` is the uplink direction, which is what `ARINCFlightPlanManager` builds as `"FPN/" + "RI"`.
 * `RP` is what the navigator writes going the other way, and a GPS 175 sent exactly `FPN/RP` on
 * CXP id 0x10005004 when asked for its own flight plan, which is how the omission was noticed.
 */
const val ROUTE_PREFIX = "FPN/RI"

/** CXP IDs, from `CxpIdType`. Only the handful this app addresses are named here. */
object FplId {
    const val SUPPORTED_ELEMENTS = 0x10005000L
    const val UPLOAD_TO_AVIONICS = 0x10005001L
    const val DOWNLOAD_FROM_AVIONICS = 0x10005002L
    const val MINIMAL_TRANSFER_TO_SIMPLE_AVIONICS = 0x10005004L
    const val DIRECT_TO_DOWNLOAD = 0x10005010L
    const val DIRECT_TO_TRANSFER_TO_SIMPLE_AVIONICS = 0x10005011L
    const val TARGET_NAVIGATOR = 0x10005020L
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
 * A route this cannot build or a coordinate this cannot read.
 *
 * [problem] names the refusal where the pilot is expected to act on it, so the application can
 * word it in their language. See [Problem] for where that line falls: it is null for the refusals
 * that describe a malformed file's internals, which keep their English.
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

// The ARINC 702A route string.
//
// Element order, and the emission order within each group, follow UDB_export_flightplan() and the
// DLS_arinc_fpl_add_*_info() builders in libadb-lib.so. Any part may be omitted, and every argument
// is normalised to null or empty before emission.

sealed interface RouteElement { fun render(): String }

/** A route waypoint, emitted as `:F:<ident>`. */
data class Waypoint(val ident: String) : RouteElement {
    override fun render() = ":F:$ident"
}

/** An airway in dot notation, emitted as `:F:.<ident>`. */
data class Airway(val ident: String) : RouteElement {
    override fun render() = ":F:.$ident"
}

/**
 * A user waypoint at a position, emitted as `:F:<13 chars>`.
 *
 * The coordinate follows the tag bare, with no separator of its own.
 * `dls_arinc_utl_add_waypoint_type_latlon` (@ 0xca4e8) formats `:F:%s` with the encoded string
 * as its only argument.
 */
data class Coordinate(val lat: Double, val lon: Double) : RouteElement {
    override fun render() = ":F:${formatLatLon(lat, lon)}"

    /** The wire form, which is also how the route echoes back to a user. */
    val ident: String get() = formatLatLon(lat, lon)
}

/**
 * A named user waypoint carrying its own position, emitted as `:F:<name>,<latlon>`.
 *
 * Unlike [Coordinate] the name survives, so the navigator stores the point under the name the
 * flight plan gave it rather than a bare position. This is what a Garmin `.fpl` describes when it
 * types a route point `USER WAYPOINT` and puts coordinates for it in the waypoint table.
 *
 * `dls_arinc_utl_add_waypoint_type_published` formats `:F:%s` with the name, then, when a lat/lon
 * string is supplied too, formats that with a second string and concatenates it. That second
 * format is `",%s"`, resolved from the call site's operand in **both** builds rather than from
 * adjacency in `.rodata`: `DAT_0020f833` in Garmin Pilot's ARM64 `libadb-lib.so`, `DAT_001fc3f6`
 * in FltPlan Go's ARM32 one.
 *
 * **UNCONFIRMED:** which capability bit gates the combined form. The builder is the *published*
 * one, suggesting `PUB_FMT`, while it is emitting a position, suggesting `LAT_LON_USER`. [buildRoute]
 * treats `LAT_LON_USER` as the gate and degrades rather than failing, so guessing wrong costs the
 * position and not the upload.
 */
data class UserWaypoint(val ident: String, val lat: Double, val lon: Double) : RouteElement {
    override fun render(): String {
        checkName()
        return ":F:$ident,${formatLatLon(lat, lon)}"
    }

    /**
     * Name and position as one token, so what the user is shown parses back to what will be sent,
     * the same reason [Coordinate.ident] is the wire form.
     */
    val display: String get() = "$ident,${formatLatLon(lat, lon)}"

    /**
     * Reject a name the navigator cannot read back. Bounded by the device profile's waypoint cap,
     * and `dls_str_is_alphanum_or_delims` on the encoding side rejects anything not alphanumeric.
     *
     * [max] defaults to the widest cap across profiles, which is only a syntactic sanity bound.
     * [buildRoute] enforces the selected device's cap, which is the one that actually applies.
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
        if (!ident.all { it in '0'..'9' || it in 'A'..'Z' || it in 'a'..'z' }) {
            throw FlightPlanException(
                "user waypoint name \"$ident\" is not alphanumeric",
                Problem.WaypointNameNotAlphanumeric(ident),
            )
        }
    }
}

/**
 * How many characters of a name the *tokenizer* will take, as opposed to how many the navigator
 * reads.
 *
 * Deliberately wider than any [Profile.waypointNameLen]: a name that runs too long has to survive
 * as one token so it can be refused by name rather than split into two points behind the user's
 * back.
 */
const val NAME_TOKEN_LEN = 8

// User waypoints at a latitude and longitude.
//
// `dls_format_posn_to_str` (libadb-lib.so @ 0xcac48) holds the only reference to the format
// string `%c%02u%03d%c%03u%03d` at 0x1EC3FE:
//
//     N | degrees in 2 digits | tenths of a minute
//     E | degrees in 3 digits | tenths of a minute
//
// so 48.82 N, 2.62 E is `N48492E002372`, 13 characters. Garmin Pilot's
// ARINCDataUtil.formatLatitudeLongitude() is the same arithmetic written in Java, which makes
// this one of the few claims in this repo derived from two independent binaries. Both decoders
// divide the fields of 3 digits by exactly 600.0, and that is what pins them to tenths of a minute
// rather than hundredths or whole minutes.

/**
 * Length of the encoded form. `dls_arinc_utl_validate_lat_lon_str` accepts exactly this many
 * characters and nothing else.
 */
const val LATLON_LEN = 13

/**
 * The bounds a position read from a user or from a file has to fall inside.
 *
 * ICAO's, not Garmin's: a latitude runs to the poles and a longitude to the antimeridian, and
 * anything beyond either is not a place. [formatLatLon] is deliberately looser, because it has to
 * stay faithful to what the navigator's own parser accepts.
 */
const val MAX_LATITUDE = 90.0
const val MAX_LONGITUDE = 180.0

/**
 * Longest token that could be a coordinate: `48.12345678N/122.12345678W` and a little slack.
 * Checked before the regex runs: this is parsed on every keystroke, and two adjacent digit runs
 * are exactly the shape that makes a backtracking engine explore every split point.
 */
private const val LATLON_MAX_TOKEN = 32

/**
 * One coordinate, already reduced to a single token. Hemisphere may lead or follow on each half,
 * and the `/` between them is optional. Every quantifier is bounded, for the reason above.
 */
private val LATLON =
    Regex("""^([NS])?(\d{1,7}(?:\.\d{1,8})?)([NS])?/?([EW])?(\d{1,7}(?:\.\d{1,8})?)([EW])?$""")

/**
 * A coordinate as a human might type it, with a decimal comma and/or a space or comma between
 * the halves. Used only to rewrite such input into a single token before the route is split, and
 * [parseLatLon] remains the authority on what is actually valid. A hemisphere letter is required
 * on both halves, which is what keeps ordinary route tokens (`V334, LIN`) out of it.
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
 * Whole degrees and tenths of a minute, the way the binary does it.
 *
 * Degrees truncate, and the tenths are rounded half away from zero (`MTH_round_d` @ 0xeabde). The
 * result is deliberately **not** carried into the degrees when it reaches 600: Garmin does not
 * clamp either, and their own decoder reads 600 back as a whole degree, since 600 / 600 == 1.0.
 */
private fun splitDegrees(value: Double): Pair<Int, Int> {
    val magnitude = abs(value)
    val degrees = magnitude.toInt()
    return degrees to floor((magnitude - degrees) * 600 + 0.5).toInt()
}

/**
 * Encode a position as the ARINC user waypoint string of 13 characters.
 *
 * The bounds here are the navigator's, so they are wider than [MAX_LONGITUDE], which is what
 * [parseLatLon] applies to a position somebody typed or a file stated. This end stays faithful to
 * what the receiving parser accepts; refusing what cannot be a place belongs at the input.
 */
fun formatLatLon(lat: Double, lon: Double): String {
    // A NaN passes every range check below, because every comparison against one is false. It then
    // truncates to zero degrees and zero tenths and encodes as N00000E000000, a point in the Gulf
    // of Guinea, silently. Nothing in this file produces a NaN, but an importer reading `lat="NaN"`
    // out of somebody's GPX hands one straight here: `toDoubleOrNull` parses that string happily.
    if (lat.isNaN() || lon.isNaN()) throw FlightPlanException("coordinate is not a number")
    // The binary checks the range of the *truncated* degrees (<= 90 / <= 360), so it would pass
    // 90.4. Checking the value itself is strictly tighter.
    if (lat < -90.0 || lat > 90.0) throw FlightPlanException("latitude $lat is outside -90..90")
    if (lon < -360.0 || lon > 360.0) throw FlightPlanException("longitude $lon is outside -360..360")
    val (latDeg, latTenths) = splitDegrees(lat)
    val (lonDeg, lonTenths) = splitDegrees(lon)
    // Locale.ROOT is not decoration: `%d` is localised, so on a device set to an Arabic or Persian
    // locale the default locale renders Arabic Indic digits into the route string. Garmin Pilot's
    // own encoder calls String.format without one and has exactly that defect.
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
        // A decimal point after more digits than a degree field holds is ambiguous, and reading it
        // as plain decimal degrees is silently wrong: `00237.2` is 2 deg 37.2 min, so taking it as
        // 237.2 degrees encodes `48.5N/00237.2E` as N48300E237120 rather than N48300E002372, which
        // is in range, uploads without complaint, and is 234 degrees from where the pilot meant.
        // A fuzz test asserting that anything ParseLatLon accepts survives FormatLatLon is what
        // found it, in both ports.
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
 * Decode a coordinate token, or return null if it is not one.
 *
 * Null means "this is some other kind of identifier", so the caller can fall through to treating
 * it as a waypoint. A token that clearly *is* a coordinate but is malformed throws instead,
 * because silently demoting it to a waypoint named `N48492E00237` is how a bad route reaches an
 * aircraft.
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
    // Check the range here rather than leaving it to formatLatLon. The token is unambiguously a
    // coordinate by this point, so a value out of range is a malformed one and should be refused
    // where the user's input still has a name, not several layers later at encode time.
    //
    // The longitude bound is ICAO's and not Garmin's, and the two differ on purpose. The firmware
    // range checks the *truncated* degrees against 360, so [formatLatLon] accepts up to that and
    // stays faithful to what the navigator will take. Nothing east of 180 degrees is a place, so
    // a coordinate somebody typed or a file stated is refused at 180: being faithful is the
    // encoder's job, and refusing what cannot be a position is this one's.
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
 * Rewrite typed coordinates so each becomes a single token.
 *
 * A decimal comma becomes a point and the separator between the halves becomes `/`, so that
 * splitting the route on commas and spaces afterwards leaves the coordinate intact. Everything
 * that is not shaped like a coordinate is untouched, so `"ksfo, sac, klas"` still splits into three.
 */
fun normaliseCoordinates(text: String): String = LOOSE_LATLON.replace(text) { match ->
    val (lat, lon) = match.destructured
    "${lat.replace(",", ".")}/${lon.replace(",", ".")}"
}

/**
 * A named user waypoint as [UserWaypoint.display] writes it: a name, a comma, and the wire
 * position of 13 characters.
 *
 * **The name bound here is deliberately wider than any [Profile.waypointNameLen].** This pattern
 * decides what is *one token*, not what is valid: a name the navigator is too small for must still
 * be recognised as a single point so that [UserWaypoint.checkName] can name the problem. Narrowing
 * it to the cap would make a name that runs too long split at its comma into a waypoint and a stray
 * coordinate, silently and wrongly, which is the bug this fold exists to prevent.
 *
 * [LOOSE_LATLON] deliberately will not match the position half here, because its lookbehind
 * rejects a candidate preceded by a comma, so without this fold the route would split at the
 * comma and the one point would become two.
 *
 * A comma **is** allowed on either side, which is the one way this differs from [LOOSE_LATLON]'s
 * boundaries. A coordinate has to refuse one, or `V334, LIN` reads as a position. This form cannot
 * be confused that way, since the name is bounded at [NAME_TOKEN_LEN] characters and the position
 * is exactly [LATLON_LEN]. Refusing one would fold nothing in a list separated by commas, which is
 * what `sendfpl -via LOBEL1,N48475E002522,LFPKC2,...` is.
 */
private val NAMED_USER_WAYPOINT = Regex(
    """(?<![A-Z0-9./])([A-Z0-9]{1,$NAME_TOKEN_LEN}),([NS]\d{5}[EW]\d{6})(?![A-Z0-9./])""",
    RegexOption.IGNORE_CASE,
)

/**
 * Rewrite `NAME,<position>` into a single token, the way [normaliseCoordinates] does for a bare
 * coordinate, so that splitting the route on commas afterwards leaves the pair intact.
 *
 * Kept separate from [normaliseCoordinates] rather than folded into it: that function's behaviour
 * is pinned by a golden fixture captured from the implementation that preceded this one, and this
 * form did not exist then.
 */
fun normaliseUserWaypoints(text: String): String = NAMED_USER_WAYPOINT.replace(text) { match ->
    "${match.groupValues[1]}/${match.groupValues[2]}"
}

/** The folded form, as [normaliseUserWaypoints] leaves it and the route parser reads it. */
val USER_WAYPOINT_TOKEN =
    Regex("""^([A-Z0-9]{1,$NAME_TOKEN_LEN})/([NS]\d{5}[EW]\d{6})$""")

/** `:DA:<apt>[:D:<sid>[.<trans>]][:R:<rwy>]` */
data class Departure(
    val airport: String,
    val procedure: String? = null,
    val transition: String? = null,
    val runway: String? = null,
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

/** `:AA:<apt>[:A:<star>[.<trans>]][:R:<rwy>]` */
data class Arrival(
    val airport: String,
    val procedure: String? = null,
    val transition: String? = null,
    val runway: String? = null,
) {
    fun render(): String = buildString {
        append(":AA:").append(airport)
        if (!procedure.isNullOrEmpty()) {
            append(":A:").append(procedure)
            if (!transition.isNullOrEmpty()) append('.').append(transition)
        }
        if (!runway.isNullOrEmpty()) append(":R:").append(runway)
    }
}

/** `:AP:<app>[.<trans>]` */
data class Approach(val procedure: String, val transition: String? = null) {
    fun render(): String = buildString {
        append(":AP:").append(procedure)
        if (!transition.isNullOrEmpty()) append('.').append(transition)
    }
}

/**
 * Assemble an ARINC 702A route string.
 *
 * When [capabilities] is given, elements the navigator did not advertise are dropped rather than
 * emitted, mirroring the real encoder, which takes the negotiated capability set as an input.
 *
 * **The departure and arrival airports are emitted as `:F:` elements**, not left to `:DA:` and
 * `:AA:`. Measured against a real GPS 175, over three routes, one rule:
 *
 * ```
 * FPN/RI:DA:LFPL:AA:LFPK                 "Flight plan import failed."
 * FPN/RI:DA:LFPL:F:PMN:AA:LFPK           imported, containing only PMN
 * FPN/RI:DA:LFPL:F:LFPK:F:LFFZ:AA:LFQB   imported, containing only LFPK LFFZ
 * ```
 *
 * Only `:F:` elements become waypoints. `:DA:` and `:AA:` are accepted by the parser, being two
 * of the fourteen entries in its element table, but contribute no leg, so a route made only of
 * them imports as an empty flight plan, which is what the first line reports.
 *
 * A Garmin Pilot session captured against the same navigator agrees: every point of its route,
 * airports included, is a `:F:` element, and it emits no `:DA:` or `:AA:` at all.
 *
 * `:DA:`/`:AA:` are still emitted when they carry a procedure or runway, that being the only place
 * the grammar has for one. Whether the navigator acts on a procedure attached that way is
 * **UNCONFIRMED**.
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

    // The airports are checked here rather than in their render methods, which return no error.
    // Over AIRPORT_NAME_LEN the navigator's `:DA:`/`:AA:` handler stops inside the identifier and
    // the rest of the route desynchronises behind it, so this is not a cosmetic limit.
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
        val eff = if (capabilities != null && !capabilities.supports(Element.DEPARTURE)) {
            Departure(d.airport, null, null, d.runway)
        } else d
        // Checked after the downgrade, because that is what gets rendered: a procedure the
        // navigator will not be sent cannot desynchronise it.
        device.checkProcedure(eff.procedure, eff.transition)
        // `:DA:` only carries a procedure. It does not put the airport in the flight plan.
        // See the note above [buildRoute].
        if (eff.procedure != null || eff.runway != null) parts += eff.render()
        parts += ":F:" + eff.airport
    }

    for (item in enroute) {
        if (item is Waypoint) {
            device.checkIdent(Problem.ElementIdentTooLong.Kind.WAYPOINT, item.ident, device.waypointNameLen)
        }
        if (item is Airway) {
            device.checkIdent(Problem.ElementIdentTooLong.Kind.AIRWAY, item.ident, device.airwayNameLen)
        }
        if (item is Airway && capabilities != null &&
            !capabilities.supports(Element.AIRWAY_DOT_NOTATION)
        ) {
            throw FlightPlanException(
                "navigator does not support airway dot notation, cannot encode ${item.ident}"
            )
        }
        // Coordinates are gated by the waypoint type byte, not the element mask, and a navigator
        // advertises notations separately from elements.
        if (item is Coordinate && capabilities != null &&
            !capabilities.supportsWaypointType(WaypointType.LAT_LON_USER)
        ) {
            throw FlightPlanException(
                "navigator does not support lat/lon user waypoints, cannot encode ${item.ident}"
            )
        }
        // A named user waypoint degrades instead, because it has something left to say without
        // the position: the navigator can still look the name up. A bare Coordinate has no such
        // fallback, which is why that one is an error and this one is not.
        if (item is UserWaypoint) {
            // The device's own cap, not the syntactic bound render() applies: a name inside that
            // bound can still be too long for this navigator. Checked whatever the capabilities
            // say, because it governs the full `:F:<name>,<posn>` form too.
            item.checkName(device.waypointNameLen)
            if (capabilities != null && !capabilities.supportsWaypointType(WaypointType.LAT_LON_USER)) {
                parts += ":F:${item.ident}"
                continue
            }
        }
        parts += item.render()
    }

    arrival?.let { a ->
        val eff = if (capabilities != null && !capabilities.supports(Element.ARRIVAL)) {
            Arrival(a.airport, null, null, a.runway)
        } else a
        device.checkProcedure(eff.procedure, eff.transition)
        // The arrival airport is a fix for the same reason the departure is.
        parts += ":F:" + eff.airport
        if (eff.procedure != null || eff.runway != null) parts += eff.render()
    }

    approach?.let {
        if (capabilities != null && !capabilities.supports(Element.APPROACH)) {
            throw FlightPlanException("navigator does not support approaches")
        }
        device.checkProcedure(it.procedure, it.transition)
        parts += it.render()
    }

    val body = parts.joinToString("")
    if (body.isEmpty()) throw FlightPlanException("empty route")
    return ROUTE_PREFIX + body
}

/**
 * Refuses an identifier this navigator's parser cannot read whole.
 *
 * **A cap is not a cosmetic limit and exceeding one does not truncate.** The parser reads every
 * identifier through one token reader, which copies at most its cap and leaves the read pointer
 * where it stopped. The handler then commits the point under a shorter name, which is very often a
 * different real waypoint, and returns still pointing inside the original. The main loop meets a
 * character that is not a tag, sets the malformed bit, and resynchronises at the next colon. So
 * one name too long both renames a point and marks the whole upload bad, from a route that reads
 * perfectly well as text.
 *
 * Garmin's own encoder truncates here instead, measuring a token with `UTL_strnlen(tok, 6)` and
 * copying six into a field the parser reads five of. That is not a model to follow: shortening an
 * identifier names a different place, which is exactly the mistake a pilot cannot see.
 *
 * A cap of zero means the field has not been read for this device, and then nothing is enforced.
 * See [Profile.isValid].
 */
private fun Profile.checkIdent(kind: Problem.ElementIdentTooLong.Kind, ident: String?, max: Int) {
    if (max <= 0 || ident == null || ident.length <= max) return
    val what = kind.name.lowercase()
    throw FlightPlanException(
        "$what \"$ident\" is ${ident.length} characters, $name reads $max",
        Problem.ElementIdentTooLong(kind, ident, ident.length, name, max),
    )
}

/**
 * Applies the two caps the `:D:`/`:A:`/`:AP:` handler passes, which differ: ten for the procedure
 * and five for the transition after it.
 */
private fun Profile.checkProcedure(procedure: String?, transition: String?) {
    checkIdent(Problem.ElementIdentTooLong.Kind.PROCEDURE, procedure, procedureNameLen)
    checkIdent(Problem.ElementIdentTooLong.Kind.TRANSITION, transition, transitionNameLen)
}

/**
 * Encode a route string as the UPLOAD_TO_AVIONICS payload.
 *
 * There is no framing: the payload is the string's raw bytes, and the CXP stream close delimits
 * it. The receiver decodes with `(char)readUnsignedByte()`, i.e. Latin 1, while Garmin's sender
 * uses the platform default. The two agree only for ASCII, so encoding fails loudly here rather
 * than silently producing something the navigator reads back as mojibake.
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
    // Checked separately, because the advertised maximum is skipped when a navigator advertises
    // zero or when capabilities is null, and the profile's limit is a hard reject anyway.
    if (payload.size > device.maxRouteLen) {
        throw FlightPlanException(
            "route is ${payload.size} bytes, ${device.name} rejects over ${device.maxRouteLen}",
            Problem.RouteTooLong(payload.size, device.maxRouteLen, device.name),
        )
    }
    return payload
}

/** Decode an UPLOAD_TO_AVIONICS payload back to its route string. */
fun decodeUpload(payload: ByteArray): String = String(payload, Charsets.ISO_8859_1)
