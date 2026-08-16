package app.sendfpl.route

import app.sendfpl.cxp.Airway
import app.sendfpl.cxp.Approach
import app.sendfpl.cxp.Arrival
import app.sendfpl.cxp.Coordinate
import app.sendfpl.cxp.Departure
import app.sendfpl.cxp.FlightPlanException
import app.sendfpl.cxp.Problem
import app.sendfpl.cxp.RouteElement
import app.sendfpl.cxp.UserWaypoint
import app.sendfpl.cxp.Waypoint
import app.sendfpl.cxp.parseLatLon

/**
 * Garmin's `.gfp`, which is not a description of a route but the route itself.
 *
 * A GFP holds one line of ARINC 702A text, the same string this application builds and uploads:
 *
 * ```
 * FPN/RI:F:KSLE:F:N45223W121419:F:N42568W122067:AA:KSLE:AP:I31.JAIME
 * ```
 *
 * That makes this reader unlike the others. The XML importers turn somebody's description of a
 * navigation into a route; this one is the **inverse of the encoder**, and it is written and tested
 * as one: anything the encoder cannot produce is refused rather than approximated, and a route that
 * survives a round trip through both comes out byte for byte as it went in.
 *
 * **It is deliberately not a model of the receiving parser.** A conformance model of the
 * navigator's own reader exists on the reference implementation's side and is kept test-only on
 * purpose, because a second parser presented as shipping code invites being read as a
 * specification. This one answers a narrower question: what did our own encoder mean by this.
 *
 * The grammar is Garmin's, from their published guideline for GTN flight plan files, which is the
 * first specification in this project that came from a document rather than from a decompiler.
 * Two places where the encoder and that document disagree are known and recorded: the encoder
 * writes a standalone airway as `:F:.V334` where the document chains one inside a single element
 * as `:F:SAC.V334.LIN`, and it writes an arrival runway as `:R:26L` where the document writes
 * `(26L)` on the arrival. Both spellings are accepted here.
 */
object Gfp {

    /**
     * `RI` is the uplink direction and is what an encoder writes. `RP` is what a navigator writes
     * going the other way, and a GPS 175 volunteered exactly that when asked for its own flight
     * plan, so a reader that took only `RI` would refuse a file the avionics produced.
     */
    private val PREFIXES = listOf("FPN/RI", "FPN/RP")

    /**
     * Garmin's rule is upper case letters, digits, colons, parentheses, commas and periods. The
     * slash is here because the header itself contains one, and the hyphen because real approach
     * identifiers use it (`I16-Z`) even though the document's own list omits it.
     */
    private const val ALLOWED = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789:().,/-"

    private val RUNWAY_IN_PARENTHESES = Regex("""\(([A-Z0-9]+)\)""")

    /** The 13 character wire position, and nothing looser. */
    private const val POSITION_LEN = 13

    /** Every SkyDemon export sampled carries one, and a GFP is read as text rather than as XML. */
    private const val BYTE_ORDER_MARK = "\uFEFF"

    fun parse(text: String): ParsedRoute {
        // The plan is the first line and the rest is discarded, which is Garmin's own rule rather
        // than a convenience: a GTN reads the first line and treats anything after it as a reason
        // the import may fail.
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
                    departure = d.copy(procedure = procedure, transition = transition)
                }
                "A" -> {
                    val a = arrival
                        ?: throw RouteParseException("an arrival procedure with no :AA: before it")
                    // Garmin puts the arrival runway in parentheses on this element; the encoder
                    // puts it in a `:R:` of its own. Both are read.
                    val runway = RUNWAY_IN_PARENTHESES.find(value)?.groupValues?.get(1)
                    val (procedure, transition) = beforeAndAfterDot(value.substringBefore('('))
                    arrival = a.copy(
                        procedure = procedure,
                        transition = transition,
                        runway = runway ?: a.runway,
                    )
                }
                // A runway belongs to whichever end has been opened most recently, which reads
                // both the document's shape and the encoder's.
                "R" -> when {
                    arrival != null -> arrival = arrival.copy(runway = value)
                    departure != null -> departure = departure.copy(runway = value)
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

        // The encoder writes each airport as a fix as well as naming it in `:DA:`/`:AA:`, because
        // a real GPS 175 puts no waypoint in the plan for `:DA:`/`:AA:` alone.
        // Folding that back is what makes a route this project wrote read as the route it built.
        val from = departure
        if (from != null) (enroute.firstOrNull() as? Waypoint)
            ?.takeIf { it.ident == from.airport }?.let { enroute.removeAt(0) }
        val to = arrival
        if (to != null) (enroute.lastOrNull() as? Waypoint)
            ?.takeIf { it.ident == to.airport }?.let { enroute.removeAt(enroute.size - 1) }

        val points = enroute.size + (if (departure != null) 1 else 0) + (if (arrival != null) 1 else 0)
        if (points < 2) {
            throw RouteParseException(
                "this flight plan has fewer than two points", Problem.TooFewPoints
            )
        }
        return ParsedRoute(departure, enroute, arrival, approach)
    }

    /**
     * One `:F:` element, which may hold several points.
     *
     * A value with no period is a single point. A value with periods is an airway chain,
     * alternating waypoint and airway from a waypoint: `UBG.J126.RBL`, and
     * `LAX.T306.ELP.J183.CLL.V194.MEI` for several airways in a row. Either end of a leg may carry
     * its own position, as `LAX,N33560W118259.C1318.ELKEY,N32410W122031`.
     *
     * A value that *starts* with a period is the encoder's own standalone airway, `.V334`, which
     * the document does not describe and which this project emits between two `:F:` points.
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
            if (j % 2 == 0) point(part) else Airway(requireIdent(part, "airway"))
        }
    }

    /**
     * A point: an identifier, a bare position, or an identifier carrying one.
     *
     * Garmin's document draws a distinction this cannot preserve and does not need to. There, a
     * bare position is a user waypoint and `SEA,N47261W122186` is a *database* waypoint whose
     * position is present only to disambiguate a duplicate identifier. Both spellings render back
     * exactly as they were read, so nothing is lost on the wire; what is lost is a claim about the
     * navigator's database that this side has no way to check.
     */
    private fun point(text: String): RouteElement {
        if (text.isEmpty()) throw RouteParseException("an empty point in a :F: element")
        val comma = text.indexOf(',')
        if (comma < 0) return position(text) ?: Waypoint(requireIdent(text, "waypoint"))
        val name = requireIdent(text.substring(0, comma), "waypoint")
        val where = text.substring(comma + 1)
        val at = position(where)
            ?: throw RouteParseException("\"$where\" is not a $POSITION_LEN character position")
        return UserWaypoint(name, at.lat, at.lon)
    }

    /** The wire position only. Anything else is an identifier, and is not guessed at. */
    private fun position(text: String): Coordinate? {
        if (text.length != POSITION_LEN) return null
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

    private fun requireIdent(value: String, what: String): String {
        if (value.isEmpty()) throw RouteParseException("an empty $what")
        return value
    }

    private fun describe(c: Char): String =
        if (c == ' ') "a space" else "\"$c\""
}
