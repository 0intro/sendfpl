package app.sendfpl.route

import app.sendfpl.cxp.Profile
import app.sendfpl.cxp.Airway
import app.sendfpl.cxp.Approach
import app.sendfpl.cxp.Arrival
import app.sendfpl.cxp.Coordinate
import app.sendfpl.cxp.Departure
import app.sendfpl.cxp.FlightPlanException
import app.sendfpl.cxp.Problem
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
     * Whether this route holds something [identifiers] cannot say.
     *
     * A departure or arrival procedure, a transition, a runway or an approach: [Gfp] builds all of
     * them and [RouteParser] can build none, so a route carrying one displays in the route box as
     * a bare list of fixes and loses it the moment that text is edited and re-parsed.
     *
     * Read by the UI to warn before the loss rather than after it. Not a defect in [identifiers]:
     * a list of identifiers is what a typed route is, and widening it would only produce text that
     * parses back to something else.
     */
    val carriesProcedures: Boolean
        get() = departure?.let { it.procedure != null || it.runway != null } == true ||
            arrival?.let { it.procedure != null || it.runway != null } == true ||
            approach != null

    /**
     * Whether the route holds a point of the pilot's own, which a navigator that names imported
     * user waypoints itself is sent as a bare position. Read by the UI to say so under the box,
     * since the box shows the name. See [app.sendfpl.cxp.Profile.bareUserWaypoints].
     */
    val carriesUserWaypoints: Boolean get() = enroute.any { it is UserWaypoint }

    /**
     * Every position a file gave an identifier the navigator's database knows, by identifier: an
     * aerodrome's and each [Waypoint]'s, in route order, one entry per distinct wire position.
     *
     * The route box shows such a point by its name, and this is how the position comes back when
     * the box is parsed again: [RouteParser.parse] takes it as a memo. A user waypoint's position
     * is not here. It is the pilot's, and the box shows it.
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
     * The route as the text that reads back as it, for the route box.
     *
     * The box is filled from this after an import and parsed again on every keystroke, so each
     * element is written the way [RouteParser] reads it back as the same bytes on the wire:
     *
     * - A coordinate is its wire form, and a user waypoint is its name and position,
     *   `LOBEL,N48475E002522`.
     * - An aerodrome or a database point is its name, `LFPL` or `PMN`, even where the file gave
     *   its position: [placed] keeps the position, and [RouteParser.parse] given it puts the
     *   position back. A name placed at two positions keeps its position in the box,
     *   `D,N50015E001168`, since the name alone could not say which.
     * - A waypoint whose name the airway heuristic would take for an airway is written `=E9`.
     * - An airway the heuristic would not recognise is written `!C1318`, and so is one at either
     *   end of the box, where its name would be read as an aerodrome.
     *
     * An aerodrome placed at two positions comes back from the box as a named point rather than
     * an aerodrome, which is the same bytes: an aerodrome only differs where it carries a
     * procedure, and the box cannot say one anyway. See [carriesProcedures].
     *
     * One shape still cannot make the trip: a waypoint whose name reads as a coordinate, which no
     * identifier in a navigation database has.
     */
    val identifiers: List<String>
        get() {
            val placed = placed
            // Bare where the memo can give the position back, which is wherever the name has one.
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
                            // The wire form, so what is echoed back is what will be sent and
                            // parses back to the same position.
                            is Coordinate -> element.ident
                            // Name and position both: the position is the pilot's, and no memo
                            // holds it.
                            is UserWaypoint -> element.display
                        }
                    )
                }
                arrival?.let { add(stated(it.airport, it.at)) }
            }
        }
}

/**
 * A route text or a route file this cannot read.
 *
 * [problem] names the refusal where the pilot is expected to act on it, so the application can
 * word it in their language, and is null where the message describes a malformed file's internals.
 * See [Problem].
 */
class RouteParseException(
    message: String,
    val problem: Problem? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /**
     * Re-throw what the coordinate reader said, keeping its problem and its cause.
     *
     * The reader's message is already written for a pilot, so it is passed through rather than
     * replaced, and carrying the cause keeps a stack trace pointing at the line that refused the
     * token.
     */
    constructor(cause: FlightPlanException) : this(
        cause.message ?: "that is not a position this can read",
        cause.problem,
        cause,
    )
}

/**
 * Parse a typed route such as `KSFO SAC V334 LIN KLAS`.
 *
 * Separators are spaces or commas. The first and last identifiers are the departure and arrival
 * airports, and everything between is en route.
 *
 * Distinguishing an airway from a waypoint is a heuristic here, matching `send_fpl.py`: a letter
 * or two followed by digits (`V334`, `J50`, `Q13`, `UL9`) is an airway. Garmin's own encoder
 * decides this from the nav database, which we do not have, so the guess can be forced either
 * way: `!` makes a token an airway, `=` makes it a waypoint.
 *
 * **Both directions, and the second was missing.** The heuristic matches a short alphanumeric
 * name as readily as an airway designator, so a fix called `E9` or `D45` went out as `:F:.E9`
 * with nothing a pilot could type to stop it. The reference implementation's `-via` takes the
 * same two prefixes, because the two front ends have to accept the same text.
 *
 * A token may also be a coordinate: `N48.82/E2.62`, `N48,8200/E2,62000`, `48.82N/2.62E`,
 * `N4849/E00237` or the wire form `N48492E002372`. Because a comma is both a decimal mark in the
 * first of those and the separator between route tokens, coordinates are folded into single
 * tokens before the split. See `normaliseCoordinates`.
 */
object RouteParser {

    private val AIRWAY = Regex("^[A-Z]{1,2}\\d{1,3}[A-Z]?$")

    /**
     * The longest a token can be and still be an aerodrome identifier.
     *
     * ICAO's, not a navigator's. A location indicator is four characters (Doc 7910) and an FAA
     * identifier is three or four, digits allowed, so `KSFO`, `LFPL` and `0S9` all qualify. The
     * device profiles happen to carry 4 for `:DA:`/`:AA:` as well, and that coincidence is not
     * what this rule is about: reading a token as an aerodrome is a question about the alphabet
     * of identifiers, and it has the same answer whichever navigator the route is bound for.
     */
    private const val ICAO_IDENT_LEN = 4

    /**
     * Reads the route box.
     *
     * [placed] is the memo of an imported route, [ParsedRoute.placed]: the positions a file gave
     * the names it stated, which the box shows bare. A bare name found there once takes its
     * position back, and a name with a position found there is the file's database point rather
     * than a user waypoint of the pilot's. A typed route has no memo, and reads as it always did.
     */
    fun parse(input: String, placed: Map<String, List<Coordinate>> = emptyMap()): ParsedRoute {
        // Coordinates are folded into single tokens first, so that the split below cannot cut a
        // decimal comma, the gap between a coordinate's two halves, or the comma joining a named
        // user waypoint to its position.
        val tokens = normaliseUserWaypoints(normaliseCoordinates(input))
            .split(' ', ',', '\n', '\t')
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }
        if (tokens.size < 2) {
            throw RouteParseException(
                "a route needs at least a departure and an arrival, e.g. \"KSFO KLAS\"",
                Problem.RouteTooShort,
            )
        }
        val departure = aerodrome(tokens.first())
        val arrival = aerodrome(tokens.last())
        // An end that is not an aerodrome is simply the first or last point of the route. Slicing
        // rather than dropping keeps it in the list exactly once.
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
     * The token read as an aerodrome identifier, or null when it is an ordinary point.
     *
     * **An end is not special on the wire.** A route is a list of `:F:` fixes; the departure and
     * arrival differ only in that `:DA:`/`:AA:` can carry a procedure for them, and that element
     * is emitted only when there is a procedure or a runway to put in it. So a token that cannot
     * be an aerodrome identifier is not an error, it is a point, and the route starts there.
     *
     * That matters for ordinary flying rather than for exotic files: a VFR navigation from one
     * unlicensed strip to another has no ICAO code at either end, and a planner exporting it
     * writes a name and a position and nothing else. Requiring four letters there refused real
     * routes.
     *
     * The bound is [ICAO_IDENT_LEN]. Anything longer, anything holding a `/` from a folded
     * coordinate, and anything carrying a `!` or `=` prefix, is a point.
     *
     * A prefix at either end therefore makes that airport an en route fix, and the wire result is
     * the same: the encoder emits `:F:<airport>` regardless, and omits `:DA:`/`:AA:` where there
     * is no procedure or runway to put in it.
     */
    private fun aerodrome(token: String): String? {
        if (token.isEmpty() || token.length > ICAO_IDENT_LEN) return null
        if (!token.all { it in 'A'..'Z' || it in '0'..'9' }) return null
        return token
    }

    private fun element(token: String, placed: Map<String, List<Coordinate>>): RouteElement {
        if (token.startsWith("!")) return Airway(token.removePrefix("!"))

        // `=` **suppresses the airway heuristic**; it does not assert a type. Asserting one read
        // well and was wrong for any token that is not an identifier: `=N48.82/E2.62` became a
        // waypoint *named* after a coordinate, which goes out as `:F:N48.82/E2.62` and is not a
        // position anything can read. Suppressing instead leaves every other form classifying as
        // it would have, and the only token whose answer changes is the one the prefix is for.
        val forced = token.startsWith("=")
        val bare = if (forced) token.removePrefix("=") else token

        // Coordinates are tested before the airway heuristic: the wire form N48492E002372
        // is not shaped like an airway, but nothing guarantees a future form would not be.
        val coordinate = try {
            parseLatLon(bare)
        } catch (e: FlightPlanException) {
            throw RouteParseException(e)
        }
        val named = USER_WAYPOINT_TOKEN.matchEntire(bare)
        return when {
            coordinate != null -> coordinate
            // A name carrying its own position. Tested after the coordinate forms, which
            // cannot reach here anyway: none of them ends in a bare position of 13 characters.
            named != null -> namedPoint(named.groupValues[1], named.groupValues[2], placed)
            !forced && AIRWAY.matches(bare) -> Airway(bare)
            else -> Waypoint(bare, placed[bare]?.singleOrNull())
        }
    }

    /**
     * True when [token] would be read as an airway, which is how [ParsedRoute.identifiers] knows to
     * mark one so that the box reads it back as what it was.
     */
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
}
