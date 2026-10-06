package app.sendfpl.route

import app.sendfpl.cxp.Airway
import app.sendfpl.cxp.Approach
import app.sendfpl.cxp.Arrival
import app.sendfpl.cxp.Coordinate
import app.sendfpl.cxp.Departure
import app.sendfpl.cxp.FlightPlanException
import app.sendfpl.cxp.Problem
import app.sendfpl.cxp.RouteElement
import app.sendfpl.cxp.Waypoint
import app.sendfpl.cxp.normaliseRunway
import app.sendfpl.cxp.parseLatLon

/**
 * Garmin's `.gfp`: one line of ARINC 702A text, the same string this application uploads.
 *
 * ```
 * FPN/RI:F:KSLE:F:N45223W121419:F:N42568W122067:AA:KSLE:AP:I31.JAIME
 * ```
 *
 * The grammar comes from Garmin's published guideline for GTN flight plan files. This reader is
 * the inverse of the encoder, not a model of the navigator's parser: an element outside the
 * encoder's vocabulary is refused rather than approximated, and reading a route and writing it out
 * again is idempotent.
 */
object Gfp {

    /** `RI` is what an encoder writes, and `RP` what a GPS 175 answers when asked for its plan. */
    private val PREFIXES = listOf("FPN/RI", "FPN/RP")

    /**
     * Garmin's list, upper case letters, digits, colons, parentheses, commas and periods, plus the
     * header's slash and the hyphen of real approach identifiers (`I16-Z`), which the list omits.
     */
    private const val ALLOWED = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789:().,/-"

    private val RUNWAY_IN_PARENTHESES = Regex("""\(([A-Z0-9]+)\)""")

    /** The length of a wire position, `N48492E002372`. */
    private const val POSITION_LEN = 13

    /** Every SkyDemon export sampled carries one, and a GFP is read as text rather than as XML. */
    private const val BYTE_ORDER_MARK = "\uFEFF"

    fun parse(text: String): ParsedRoute {
        // The plan is the first line, by Garmin's rule: a GTN treats anything after it as a
        // reason the import may fail.
        val line = text.removePrefix(BYTE_ORDER_MARK).lineSequence().firstOrNull()
            ?.trim().orEmpty().uppercase()
        if (line.isEmpty()) {
            throw RouteParseException("this flight plan file is empty", Problem.UnrecognisedFile)
        }
        // The prefix is tested before the character set, so that a file which is not a flight plan
        // at all is told so rather than being told about its first space.
        val prefix = PREFIXES.firstOrNull { line.startsWith(it) }
            ?: throw RouteParseException(
                "a Garmin flight plan starts with ${PREFIXES.joinToString(" or ")}. " +
                    "This one starts \"${line.take(16)}\"",
                Problem.UnrecognisedFile,
            )
        line.firstOrNull { it !in ALLOWED }?.let {
            throw RouteParseException(
                "a Garmin flight plan holds only letters, digits and \":().,/-\", and this one " +
                    "holds ${describe(it)}"
            )
        }
        val body = line.substring(prefix.length)
        if (!body.startsWith(':')) {
            throw RouteParseException("\"$prefix\" is not followed by a flight plan element")
        }

        // Values never contain a colon, so the fields alternate key, value, key, value after the
        // empty string the leading colon leaves.
        val fields = body.split(':')
        var departure: Departure? = null
        var arrival: Arrival? = null
        var approach: Approach? = null
        val enroute = mutableListOf<RouteElement>()

        var i = 1
        while (i < fields.size) {
            val key = fields[i]
            val value = fields.getOrNull(i + 1)
                ?: throw RouteParseException("\":$key:\" has nothing after it")
            when (key) {
                "DA" -> departure = Departure(requireIdent(value, "departure airport"))
                "AA" -> arrival = Arrival(requireIdent(value, "arrival airport"))
                "D" -> {
                    val d = departure
                        ?: throw RouteParseException("a departure procedure with no :DA: before it")
                    val (procedure, transition) = beforeAndAfterDot(value)
                    departure = d.copy(procedure = procedure.ifEmpty { null }, transition = transition)
                }
                "A" -> {
                    val a = arrival
                        ?: throw RouteParseException("an arrival procedure with no :AA: before it")
                    // The runway is in parentheses at the end, as Garmin and the encoder write it.
                    val open = value.indexOf('(')
                    val runway = if (open < 0) null else {
                        val written = value.substring(open)
                        runway(
                            RUNWAY_IN_PARENTHESES.matchEntire(written)?.groupValues?.get(1)
                                ?: throw notARunway(written)
                        )
                    }
                    val (procedure, transition) =
                        beforeAndAfterDot(if (open < 0) value else value.substring(0, open))
                    arrival = a.copy(
                        procedure = procedure.ifEmpty { null },
                        transition = transition,
                        runway = runway ?: a.runway,
                    )
                }
                // The departure's runway, as Garmin and the encoder write it, or the arrival's
                // once one has been read, as earlier builds of the encoder wrote it.
                "R" -> when {
                    arrival != null -> arrival = arrival.copy(runway = runway(value))
                    departure != null -> departure = departure.copy(runway = runway(value))
                    else -> throw RouteParseException("a runway with no airport before it")
                }
                "AP" -> {
                    val (procedure, transition) = beforeAndAfterDot(value)
                    approach = Approach(requireIdent(procedure, "approach"), transition)
                }
                "F" -> enroute += fixes(value)
                else -> throw RouteParseException(
                    "\":$key:\" is not a flight plan element this reads"
                )
            }
            i += 2
        }
        // A GPS 175 imports `:F:LFPL:F:PMN:F:LFPN:AP:I25R` without the approach.
        if (approach != null && arrival == null) {
            throw RouteParseException(
                "an approach with no :AA: to name its aerodrome", Problem.ApproachWithoutArrival
            )
        }

        // The encoder also writes each airport as a `:F:` fix, since `:DA:`/`:AA:` alone put no
        // waypoint in the plan. That fix folds back onto the aerodrome, with any position it has.
        val from = departure
        if (from != null) aerodromeFix(enroute.firstOrNull(), from.airport)?.let { fix ->
            enroute.removeAt(0)
            departure = from.copy(at = fix.position ?: from.at)
        }
        val to = arrival
        if (to != null) aerodromeFix(enroute.lastOrNull(), to.airport)?.let { fix ->
            enroute.removeAt(enroute.size - 1)
            arrival = to.copy(at = fix.position ?: to.at)
        }

        val points = enroute.size + (if (departure != null) 1 else 0) + (if (arrival != null) 1 else 0)
        if (points < 2) {
            throw RouteParseException(
                "this flight plan has fewer than two points", Problem.TooFewPoints
            )
        }
        return ParsedRoute(departure, enroute, arrival, approach)
    }

    /**
     * One `:F:` element: a point, or an airway chain alternating waypoint and airway, as
     * `UBG.J126.RBL` or `LAX.T306.ELP.J183.CLL`, whose points may carry positions,
     * `LAX,N33560W118259.C1318.ELKEY,N32410W122031`. A standalone airway, `.V334`, is not in the
     * document, but earlier builds of the encoder wrote one between two points. Garmin's direct
     * leg, `LFLL..MANAK`, is outside the encoder's vocabulary and refused by name.
     */
    private fun fixes(value: String): List<RouteElement> {
        if (value.isEmpty()) throw RouteParseException("an empty :F: element")
        val parts = value.split('.')
        if (parts.size == 2 && parts[0].isEmpty()) return listOf(Airway(requireIdent(parts[1], "airway")))
        if (parts.size == 1) return listOf(point(parts[0]))
        if (parts.size % 2 == 0) {
            throw RouteParseException(
                "\"$value\" is not an airway chain: it has to alternate waypoint, airway, waypoint"
            )
        }
        return parts.mapIndexed { j, part ->
            when {
                j % 2 == 0 -> point(part)
                part.isEmpty() && parts[j + 1].isNotEmpty() -> throw RouteParseException(
                    "\"$value\" holds a direct leg, \"..\", which this reader does not take",
                    Problem.DirectLeg,
                )
                else -> Airway(requireIdent(part, "airway"))
            }
        }
    }

    /** What an aerodrome's own `:F:` fix carried: its position, or nothing. */
    private class AerodromeFix(val position: Coordinate?)

    /** [element] as the `:F:` fix of [airport], bare or with a position, or null if it is not. */
    private fun aerodromeFix(element: RouteElement?, airport: String): AerodromeFix? = when {
        element is Waypoint && element.ident == airport -> AerodromeFix(element.at)
        else -> null
    }

    /**
     * A point: an identifier, a bare position, or an identifier carrying one, which in Garmin's
     * document is a database [Waypoint] whose position settles a duplicated identifier. It is
     * never a [app.sendfpl.cxp.UserWaypoint], whose name a GTN locks.
     */
    private fun point(text: String): RouteElement {
        if (text.isEmpty()) throw RouteParseException("an empty point in a :F: element")
        val comma = text.indexOf(',')
        if (comma < 0) return position(text) ?: Waypoint(requireIdent(text, "waypoint"))
        val name = requireIdent(text.substring(0, comma), "waypoint")
        val where = text.substring(comma + 1)
        val at = position(where)
            ?: throw RouteParseException("\"$where\" is not a $POSITION_LEN character position")
        return Waypoint(name, at)
    }

    /** The wire position only. Anything else is an identifier, and is not guessed at. */
    private fun position(text: String): Coordinate? {
        if (!WIRE_POSITION.matches(text)) return null
        return try {
            parseLatLon(text)
        } catch (e: FlightPlanException) {
            throw RouteParseException(e)
        }
    }

    private fun beforeAndAfterDot(value: String): Pair<String, String?> {
        val dot = value.indexOf('.')
        if (dot < 0) return value to null
        return value.substring(0, dot) to value.substring(dot + 1).takeIf { it.isNotEmpty() }
    }

    /** [value] if the encoder can send it as a runway, refused by name if it cannot. */
    private fun runway(value: String): String {
        try {
            normaliseRunway(value)
        } catch (e: FlightPlanException) {
            throw notARunway(value, e)
        }
        return value
    }

    private fun notARunway(text: String, cause: Throwable? = null) =
        RouteParseException("\"$text\" is not a runway", Problem.NotARunway(text), cause)

    private fun requireIdent(value: String, what: String): String {
        if (value.isEmpty()) throw RouteParseException("an empty $what")
        return value
    }

    private fun describe(c: Char): String =
        if (c == ' ') "a space" else "\"$c\""

    private val WIRE_POSITION = Regex("[NS][0-9]{5}[EW][0-9]{6}")
}
