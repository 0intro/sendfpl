package app.sendfpl.route

import app.sendfpl.cxp.Airway
import app.sendfpl.cxp.Approach
import app.sendfpl.cxp.Arrival
import app.sendfpl.cxp.Coordinate
import app.sendfpl.cxp.Departure
import app.sendfpl.cxp.FlightPlanException
import app.sendfpl.cxp.NAME_TOKEN_LEN
import app.sendfpl.cxp.Problem
import app.sendfpl.cxp.Profile
import app.sendfpl.cxp.RouteElement
import app.sendfpl.cxp.SupportedElements
import app.sendfpl.cxp.USER_WAYPOINT_TOKEN
import app.sendfpl.cxp.UserWaypoint
import app.sendfpl.cxp.Waypoint
import app.sendfpl.cxp.buildRoute
import app.sendfpl.cxp.normaliseCoordinates
import app.sendfpl.cxp.normaliseUserWaypoints
import app.sendfpl.cxp.parseLatLon

/** A route in the shape [buildRoute] wants, however it was obtained. */
data class ParsedRoute(
    val departure: Departure? = null,
    val enroute: List<RouteElement> = emptyList(),
    val arrival: Arrival? = null,
    val approach: Approach? = null,
) {
    fun render(device: Profile, capabilities: SupportedElements? = null): String =
        buildRoute(device, departure, enroute, arrival, approach, capabilities)

    /**
     * Whether this route holds a procedure, a runway or an approach, which [Gfp] can build and
     * [identifiers] cannot say, so editing the route box would lose it. Read by the UI to warn
     * before the loss.
     */
    val carriesProcedures: Boolean
        get() = departure?.let { it.procedure != null || it.runway != null } == true ||
            arrival?.let { it.procedure != null || it.runway != null } == true ||
            approach != null

    /**
     * Whether the route holds a point of the pilot's own, which some navigators are sent as a bare
     * position. Read by the UI to say so. See [app.sendfpl.cxp.Profile.bareUserWaypoints].
     */
    val carriesUserWaypoints: Boolean get() = enroute.any { it is UserWaypoint }

    /**
     * The positions a file gave its aerodromes and [Waypoint]s, by identifier, in route order and
     * one per distinct wire position. The route box shows these points by name, and
     * [RouteParser.parse] takes this as a memo to put the positions back. A user waypoint's
     * position is not here: the box shows it.
     */
    val placed: Map<String, List<Coordinate>>
        get() {
            val out = LinkedHashMap<String, MutableList<Coordinate>>()
            fun place(ident: String, at: Coordinate?) {
                if (at == null) return
                val positions = out.getOrPut(ident) { mutableListOf() }
                if (positions.none { it.ident == at.ident }) positions += at
            }
            departure?.let { place(it.airport, it.at) }
            enroute.forEach { if (it is Waypoint) place(it.ident, it.at) }
            arrival?.let { place(it.airport, it.at) }
            return out
        }

    /**
     * The route as route box text that [RouteParser] reads back as the same bytes on the wire:
     *
     * - A coordinate is its wire form, and a user waypoint its name and position,
     *   `LOBEL,N48475E002522`.
     * - An aerodrome or a database point is its name, `LFPL` or `PMN`, and [placed] keeps its
     *   position. A name placed at two positions keeps its position in the box, `D,N50015E001168`.
     *   An aerodrome written so reads back as a named point, which sends the same bytes, since the
     *   box carries no procedure.
     * - A waypoint the airway heuristic would take for an airway is written `=E9`.
     * - An airway the heuristic would miss is written `!C1318`, and so is one at either end of the
     *   box, where it would read as an aerodrome.
     *
     * A waypoint named like a coordinate cannot make the trip; no navigation database has one.
     */
    val identifiers: List<String>
        get() {
            val placed = placed
            // Bare where the memo gives the position back, which is wherever the name has only one.
            fun stated(ident: String, at: Coordinate?): String =
                if (at == null || placed[ident].orEmpty().size < 2) ident else "$ident,${at.ident}"
            val ends = enroute.size + listOfNotNull(departure, arrival).size - 1
            val offset = if (departure != null) 1 else 0
            return buildList {
                departure?.let { add(stated(it.airport, it.at)) }
                enroute.forEachIndexed { i, element ->
                    val atEnd = i + offset == 0 || i + offset == ends
                    add(
                        when (element) {
                            // A name with a position is never read as an airway, so only the bare
                            // name needs the prefix.
                            is Waypoint -> stated(element.ident, element.at).let {
                                if (it == element.ident && RouteParser.looksLikeAirway(it)) "=$it"
                                else it
                            }
                            is Airway ->
                                if (RouteParser.looksLikeAirway(element.ident) && !atEnd) element.ident
                                else "!${element.ident}"
                            is Coordinate -> element.ident
                            is UserWaypoint -> element.display
                        }
                    )
                }
                arrival?.let { add(stated(it.airport, it.at)) }
            }
        }
}

/**
 * A route text or a route file this cannot read. [problem] names a refusal the pilot can act on,
 * for the application to word in their language, and is null for a malformed file's internals.
 */
class RouteParseException(
    message: String,
    val problem: Problem? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** Re-throw what the coordinate reader said, keeping its message, problem and cause. */
    constructor(cause: FlightPlanException) : this(
        cause.message ?: "that is not a position this can read",
        cause.problem,
        cause,
    )
}

/**
 * Parse a typed route such as `KSFO SAC V334 LIN KLAS`, separated by whitespace or commas. The
 * first and last tokens are the departure and arrival where they can be aerodrome identifiers.
 *
 * An airway is told from a waypoint by a heuristic: a letter or two followed by digits (`V334`,
 * `J50`, `Q13`, `UL9`) is an airway. Garmin's own encoder decides from its database, which this
 * does not have, so `!` forces an airway and `=` a waypoint, as for a fix called `E9`. The
 * heuristic and both prefixes match a reference implementation, since the two have to accept the
 * same text.
 *
 * A token may also be a coordinate: `N48.82/E2.62`, `N48,8200/E2,62000`, `48.82N/2.62E`,
 * `N4849/E00237` or the wire form `N48492E002372`.
 */
object RouteParser {

    private val AIRWAY = Regex("^[A-Z]{1,2}\\d{1,3}[A-Z]?$")

    /**
     * The longest a token can be and still be an aerodrome identifier: ICAO's four characters
     * (Doc 7910), or an FAA identifier's three or four, digits allowed (`0S9`). It is about
     * identifiers, not a navigator, so it is not [Profile.airportNameLen].
     */
    private const val ICAO_IDENT_LEN = 4

    /**
     * Reads the route box. [placed] is an imported route's [ParsedRoute.placed]: a bare name found
     * there once takes its position back, and a name with a position found there is the file's
     * database point rather than a user waypoint.
     */
    fun parse(input: String, placed: Map<String, List<Coordinate>> = emptyMap()): ParsedRoute {
        // Folded first, so that the split cannot cut a decimal comma, the gap between a
        // coordinate's halves, or the comma joining a named point to its position.
        val tokens = normaliseUserWaypoints(foldHyphenated(normaliseCoordinates(input)))
            .split(SEPARATOR)
            .map { it.uppercase() }
            .filter { it.isNotEmpty() }
        if (tokens.size < 2) {
            throw RouteParseException(
                "a route needs at least a departure and an arrival, e.g. \"KSFO KLAS\"",
                Problem.RouteTooShort,
            )
        }
        val departure = aerodrome(tokens.first())
        val arrival = aerodrome(tokens.last())
        val from = if (departure != null) 1 else 0
        val to = if (arrival != null) tokens.size - 1 else tokens.size
        val enroute = tokens.subList(from, to).map { element(it, placed) }
        return ParsedRoute(
            departure?.let { Departure(it, at = placed[it]?.singleOrNull()) },
            enroute,
            arrival?.let { Arrival(it, at = placed[it]?.singleOrNull()) },
        )
    }

    /**
     * The token as an aerodrome identifier, or null when it cannot be one, which is not an error:
     * an end need not be an aerodrome, as between two unlicensed strips. Every point goes out as a
     * `:F:` fix, and `:DA:`/`:AA:` are added only to carry a procedure, a runway or an approach.
     */
    private fun aerodrome(token: String): String? {
        if (token.isEmpty() || token.length > ICAO_IDENT_LEN) return null
        if (!token.all { it in 'A'..'Z' || it in '0'..'9' }) return null
        return token
    }

    private fun element(token: String, placed: Map<String, List<Coordinate>>): RouteElement {
        if (token.startsWith("!")) return Airway(token.removePrefix("!"))

        // `=` only suppresses the airway heuristic, so that `=N48.82/E2.62` is still a
        // coordinate and not a waypoint named after one.
        val forced = token.startsWith("=")
        val bare = if (forced) token.removePrefix("=") else token

        // Before the airway heuristic, so that no coordinate form can be taken for an airway.
        val coordinate = try {
            parseLatLon(bare)
        } catch (e: FlightPlanException) {
            throw RouteParseException(e)
        }
        val named = USER_WAYPOINT_TOKEN.matchEntire(bare) ?: HYPHENATED_TOKEN.matchEntire(bare)
        return when {
            coordinate != null -> coordinate
            named != null -> namedPoint(named.groupValues[1], named.groupValues[2], placed)
            !forced && AIRWAY.matches(bare) -> Airway(bare)
            else -> Waypoint(bare, placed[bare]?.singleOrNull())
        }
    }

    /** Whether [token] would be read as an airway, for [ParsedRoute.identifiers] to mark it. */
    fun looksLikeAirway(token: String): Boolean = AIRWAY.matches(token.uppercase())

    /**
     * Rebuild a named point from the two halves of a folded token: the file's database point when
     * [placed] holds that name at that position, and a [UserWaypoint] otherwise.
     */
    private fun namedPoint(
        name: String,
        position: String,
        placed: Map<String, List<Coordinate>>,
    ): RouteElement {
        val at = try {
            parseLatLon(position)
        } catch (e: FlightPlanException) {
            throw RouteParseException(e)
        } ?: throw RouteParseException(
            "\"$position\" is not a position", Problem.NotAPosition(position)
        )
        placed[name]?.firstOrNull { it.ident == at.ident }?.let { return Waypoint(name, it) }
        return UserWaypoint(name, at.lat, at.lon)
    }

    /**
     * `AB-1,N49000E002180`, a hyphenated name and its position, as [ParsedRoute.identifiers]
     * writes a database point the file places twice. [normaliseUserWaypoints] folds only a name of
     * letters and digits, which is all a user waypoint may hold.
     */
    private val HYPHENATED_POINT = Regex(
        """(?<![A-Z0-9./-])([A-Z0-9]{1,$NAME_TOKEN_LEN}(?:-[A-Z0-9]{1,$NAME_TOKEN_LEN}){1,3})""" +
            """,([NS]\d{5}[EW]\d{6})(?![A-Z0-9./])""",
        RegexOption.IGNORE_CASE,
    )

    /** [HYPHENATED_POINT] folded, as [USER_WAYPOINT_TOKEN] is a named user waypoint. */
    private val HYPHENATED_TOKEN = Regex("""^([A-Z0-9]+(?:-[A-Z0-9]+)+)/([NS]\d{5}[EW]\d{6})$""")

    private fun foldHyphenated(text: String): String = HYPHENATED_POINT.replace(text) { match ->
        "${match.groupValues[1]}/${match.groupValues[2]}"
    }

    /** Any run of whitespace, a no-break space included, or a comma. */
    private val SEPARATOR = Regex("[\\s\\u00A0\\u202F,]+")
}
