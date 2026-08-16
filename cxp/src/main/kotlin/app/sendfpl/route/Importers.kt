package app.sendfpl.route

import app.sendfpl.cxp.Airway
import app.sendfpl.cxp.Arrival
import app.sendfpl.cxp.Coordinate
import app.sendfpl.cxp.Departure
import app.sendfpl.cxp.MAX_LATITUDE
import app.sendfpl.cxp.MAX_LONGITUDE
import app.sendfpl.cxp.Problem
import app.sendfpl.cxp.RouteElement
import app.sendfpl.cxp.UserWaypoint
import app.sendfpl.cxp.Profile
import app.sendfpl.cxp.Profiles
import app.sendfpl.cxp.Waypoint
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.InputStream
import java.io.StringReader
import java.text.Normalizer
import java.util.Locale
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.round

/**
 * Import a route from a file.
 *
 * Four formats, and one navigation exported in all four has to arrive at the navigator as the
 * same route. That is the whole design, and it is not free: the files agree about the positions
 * and about almost nothing else. A Garmin `.fpl` carries decorated identifiers and no human
 * names, GPX and KML carry human names with accents and spaces and no identifiers, and a `.pln`
 * carries ten character identifiers, coordinates in degrees and minutes and seconds, and two
 * more points than the others.
 *
 * So each parser below does one job only: say what the file said. Everything that decides an
 * identifier happens once, in [fromPoints], for all four. See [fold] and [fitNames].
 *
 * All four are parsed with `javax.xml.parsers`, which exists on Android and on the plain JVM, so
 * these are covered by ordinary unit tests rather than instrumented ones.
 *
 * That shared API is not a shared *implementation*, and the difference has already cost one bug:
 * the JVM runs Xerces, Android runs its own, and they disagree about which parser features exist.
 * A unit test here proves the parsing logic, never that the parser accepts its configuration.
 * see [parse]. Anything touching that has to be tried on a device.
 */
object RouteImporter {

    /**
     * Pick a parser from the file name, falling back to sniffing the root element.
     *
     * [device] is the navigator the route is bound for, and it decides how far a name is
     * shortened. Every profile in the table carries the same two caps today, so the choice changes
     * nothing yet and is load bearing the day one does not: shortening to another model's cap
     * desynchronises the receiving parser rather than trimming a name, which is why the caps live
     * on a profile and an unknown device is refused instead of defaulted.
     *
     * It defaults to the GPS 175, the unit this was built against. The application always passes
     * the model the pilot picked, and re-imports when that changes.
     */
    fun import(
        fileName: String?,
        bytes: ByteArray,
        device: Profile = Profiles.GPS175,
    ): ParsedRoute {
        val lower = fileName?.lowercase().orEmpty()
        return when {
            lower.endsWith(".fpl") -> importFpl(bytes.inputStream(), device)
            lower.endsWith(".gpx") -> importGpx(bytes.inputStream(), device)
            lower.endsWith(".kml") -> importKml(bytes.inputStream(), device)
            lower.endsWith(".pln") -> importPln(bytes.inputStream(), device)
            lower.endsWith(".flightplan") -> importSkyDemon(bytes.inputStream(), device)
            lower.endsWith(".gfp") -> Gfp.parse(String(bytes, Charsets.UTF_8))
            else -> {
                val head = String(bytes.copyOfRange(0, minOf(bytes.size, 512)), Charsets.UTF_8)
                when {
                    // Not XML, so it is recognised by what it begins with rather than by a tag.
                    head.trimStart('\uFEFF').startsWith("FPN/") ->
                        Gfp.parse(String(bytes, Charsets.UTF_8))
                    head.contains("<flight-plan") -> importFpl(bytes.inputStream(), device)
                    head.contains("<gpx") -> importGpx(bytes.inputStream(), device)
                    head.contains("<kml") -> importKml(bytes.inputStream(), device)
                    head.contains("<SimBase.Document") ||
                        head.contains("<FlightPlan.FlightPlan") ->
                            importPln(bytes.inputStream(), device)
                    head.contains("<DivelementsFlightPlanner") ->
                        importSkyDemon(bytes.inputStream(), device)
                    // A KMZ is a zip holding a KML, and unzipping one here would be a second
                    // format rather than a lenient reading of this one. Say so, because the
                    // alternative is an XML error about a byte that is not a tag.
                    bytes.size > 1 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() ->
                        throw RouteParseException(
                            "this looks like a zip. A KMZ has to be unzipped to the KML inside it",
                            Problem.ZipNotRoute,
                        )
                    else -> throw RouteParseException(
                        "unrecognised file: expected a Garmin .fpl or .gfp, a .gpx, a .kml, a " +
                            "SkyDemon .flightplan, or a flight simulator .pln route",
                        Problem.UnrecognisedFile,
                    )
                }
            }
        }
    }

    /**
     * Garmin `.fpl` flight plan XML.
     *
     * ```xml
     * <flight-plan xmlns="http://www8.garmin.com/xmlschemas/FlightPlan/v1">
     *   <waypoint-table>…</waypoint-table>
     *   <route>
     *     <route-point><waypoint-identifier>KSFO</waypoint-identifier>…</route-point>
     *   </route>
     * </flight-plan>
     * ```
     *
     * This schema comes from the public Garmin format, **not** from anything recovered in the
     * reverse engineering, so validate against a file exported by Garmin Pilot before trusting it.
     * Namespaces are ignored deliberately: exporters differ on whether they declare one.
     *
     * Two rules here exist because a real exporter's files needed them, and each closes a way this
     * format goes wrong in silence: [alignedTable], for a route point carrying no identifier to
     * look its position up by, and [withoutAlternate], for an alternate aerodrome appended to the
     * route after the destination.
     */
    fun importFpl(input: InputStream, device: Profile = Profiles.GPS175): ParsedRoute {
        val doc = parse(input)
        // <waypoint-table> is where a Garmin .fpl keeps coordinates and types, keyed by
        // identifier. The route itself often carries only the names.
        //
        // Kept in document order as well as keyed, because an entry may have no identifier to be
        // keyed by, and its position is then reachable no other way. See [alignedTable].
        val entries = elements(doc, "waypoint").map { node ->
            val ident = child(node, "identifier")?.textContent?.trim()?.uppercase().orEmpty()
            val at = position(child(node, "lat")?.textContent, child(node, "lon")?.textContent)
            val type = child(node, "type")?.textContent?.trim()
            ident to TableEntry(at, type)
        }
        val table = entries.filter { it.first.isNotEmpty() }.toMap()

        val points = elements(doc, "route-point")
        val aligned = alignedTable(points, entries)
        val imported = points.mapIndexed { i, node ->
            val ident = fplIdent(node)
            // By name first, since that is what the format means. Positionally only when the name
            // is absent or the table has never heard of it, and only where the file proved that
            // reading is safe.
            val entry = ident?.let { table[it.uppercase()] } ?: aligned?.getOrNull(i)
            // Prefer the route point's own position, falling back to the table's.
            val at = position(child(node, "lat")?.textContent, child(node, "lon")?.textContent)
                ?: entry?.at
            // The route point's own type wins, and the table's is the fallback, because exporters
            // differ on which of the two they fill in.
            val type = child(node, "waypoint-type")?.textContent?.trim() ?: entry?.type
            if (ident == null && at == null) {
                throw RouteParseException(
                    "point ${i + 1} of ${points.size} has no identifier and no position, and " +
                        "this file's waypoint table does not line up with its route, so there " +
                        "is nothing to read there. Leaving the point out would give a shorter " +
                        "route that still looks right, so the file is refused instead"
                )
            }
            ImportedPoint(ident, ident, at, fplKind(type))
        }
        if (imported.size < 2) {
            throw RouteParseException(
                "flight plan has fewer than two route points", Problem.TooFewPoints
            )
        }
        val named = elements(doc, "route-name").firstOrNull()?.textContent
        return fromPoints(withoutAlternate(imported, named), device)
    }

    /** The identifier a `<route-point>` states, or null when it states none. */
    private fun fplIdent(node: Node): String? =
        child(node, "waypoint-identifier")?.textContent?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * The `<waypoint-table>` read positionally, when the file has proved that is safe to do.
     *
     * A `.fpl` keys its table by identifier, which works until an exporter writes a point with no
     * identifier to key it by. SkyDemon 3.16.x did exactly that, emitting `<identifier />` for
     * every `INT`, which its own developer confirmed as a defect. Nine of the fifteen points of
     * one IFR route were nameless, and a lookup that finds nothing leaves the point with no
     * position either.
     *
     * **Dropping such a point is the worst of the options available.** The five that would remain
     * still form a well shaped route, ending at an aerodrome, of a plausible length, so nothing
     * looks wrong and the aircraft is flown somewhere else. The positions are all in the table, in
     * route order, so they are recoverable.
     *
     * The correspondence is checked, not assumed. The two lists have to be the same length, and
     * every route point that *does* state an identifier has to match the table entry at its own
     * index. Nothing in the format promises that ordering, so a file that does not line up returns
     * null here and the caller refuses it rather than handing a point somebody else's coordinates.
     * A route stating no identifiers at all passes on length alone, that being the only reading it
     * admits.
     */
    private fun alignedTable(
        points: List<Node>,
        entries: List<Pair<String, TableEntry>>,
    ): List<TableEntry>? {
        if (entries.size != points.size) return null
        for ((i, node) in points.withIndex()) {
            val ident = fplIdent(node) ?: continue
            if (!ident.equals(entries[i].first, ignoreCase = true)) return null
        }
        return entries.map { it.second }
    }

    /**
     * Drop a trailing alternate, where the file's own route name says the destination is the point
     * before it.
     *
     * SkyDemon appends the alternate aerodrome to the route as one more `AIRPORT` point, and by
     * shape it is indistinguishable from the destination: EDFC to EDDW with EDWH as the alternate
     * exports as a route ending `EDDW`, `EDWH`. Read plainly that is a flight to Wilhelmshaven,
     * and it arrives at the navigator as one, silently.
     *
     * `<route-name>EDFC EDDW</route-name>` is what settles it, and it is why this rule is narrow:
     * it fires only where a file contradicts itself, when the last token of the route's own name
     * is the identifier of the *second to last* point while the last point is a different
     * aerodrome. A file whose name agrees with its point list never meets this, so an exporter
     * writing a route name of its own choosing is unaffected.
     *
     * The alternate is dropped rather than kept, because the route the navigator flies ends at the
     * destination. SkyDemon's own native format agrees: there, `<Alternate>` is a sibling of the
     * legs rather than one of them.
     */
    private fun withoutAlternate(
        points: List<ImportedPoint>,
        routeName: String?,
    ): List<ImportedPoint> {
        if (points.size < 3 || points.last().kind != Kind.AIRPORT) return points
        val destination = routeName?.trim()?.split(WHITESPACE)?.lastOrNull()?.uppercase()
            ?.takeIf { it.isNotEmpty() } ?: return points
        if (points[points.size - 2].stated?.trim()?.uppercase() != destination) return points
        // Both cannot be the destination, and if the file says they are it is saying nothing.
        if (points.last().stated?.trim()?.uppercase() == destination) return points
        return points.dropLast(1)
    }

    /**
     * GPX route: `<rte>` with `<rtept name="…">` or a `<name>` child.
     *
     * `lat`/`lon` are mandatory attributes in the GPX schema, so an unnamed point is still a
     * usable one, so it becomes a coordinate rather than being dropped.
     *
     * The **first** `<rte>` is the route. A file may hold several, one per leg, and reading them
     * all would splice them into a journey nobody planned. A file with no `<rte>` falls back to
     * its standalone `<wpt>` list, which is what an exporter writes when it thinks of the plan as
     * a set of marks. `<trk>` is never read: a track is the trail an aircraft flew, so importing
     * one would produce a route of several thousand points.
     *
     * SkyDemon's route export says more than plain GPX can, and [skyDemonPoint] reads it.
     */
    fun importGpx(input: InputStream, device: Profile = Profiles.GPS175): ParsedRoute {
        val doc = parse(input)
        val skyDemon = isSkyDemon(doc)
        val route = elements(doc, "rte").firstOrNull() as? Element
        if (route == null && skyDemon) refuseWaypointLibrary(doc)
        val points = route?.let { elements(it, "rtept") } ?: elements(doc, "wpt")
        val imported = points.mapNotNull { node ->
            val element = node as? Element
            val label = element?.getAttribute("name")?.takeIf { it.isNotBlank() }
                ?: child(node, "name")?.textContent?.trim()?.takeIf { it.isNotEmpty() }
            val at = position(element?.getAttribute("lat"), element?.getAttribute("lon"))
            if (label == null && at == null) return@mapNotNull null
            // `<sym>` is only read inside a route, which is the only place SkyDemon puts an
            // identifier in it.
            if (skyDemon && route != null) skyDemonPoint(node, label, at)
            else ImportedPoint(label, null, at, Kind.UNTYPED)
        }
        if (imported.size < 2) {
            throw RouteParseException("GPX route has fewer than two points", Problem.TooFewPoints)
        }
        return fromPoints(imported, device)
    }

    /** SkyDemon's GPX extension namespace, declared on every route export it writes. */
    private const val SKYDEMON_GPX_NS = "http://www.skydemon.aero/gpxextensions"

    /**
     * Whether SkyDemon wrote this GPX.
     *
     * Two signals, because neither alone covers every export. The namespace declaration is on all
     * four route exports sampled, back to `SkyDemon Plan v2.5.2.22897`, and is the one that matters
     * since it is what licenses reading `<sym>` as an identifier. The `creator` attribute also
     * names it (`SkyDemon Plan v3.4.2.26247`, `SkyDemon for iPad`, `SkyDemon`), and covers a
     * hypothetical export that carries no extensions at all.
     */
    private fun isSkyDemon(doc: Document): Boolean {
        val root = doc.documentElement ?: return false
        if (runCatching { root.lookupPrefix(SKYDEMON_GPX_NS) }.getOrNull() != null) return true
        return root.getAttribute("creator").startsWith("SkyDemon", ignoreCase = true)
    }

    /**
     * A SkyDemon `<rtept>`, which says three things plain GPX cannot.
     *
     * ```xml
     * <rtept lat="50.948610" lon="4.391944">
     *   <name>GRIMBERGEN / Lint</name>
     *   <sym>EBGB</sym>
     *   <extensions><skd:level type="A" value="1300" /></extensions>
     * </rtept>
     * ```
     *
     * **`<sym>` is the database identifier**, not a symbol: `EBGB` for the aerodrome, `NIK` for the
     * VOR whose `<name>` is "Nicky", `D` for a reporting point whose `<name>` is empty. That is a
     * reading of `<sym>` no other exporter licenses, which is why [isSkyDemon] gates it: the same
     * element in SkyDemon's own waypoint library holds `GreenFlag`.
     *
     * A point with no `<sym>` is one SkyDemon has no identifier for, which happens constantly:
     * unlicensed strips have no ICAO code, so `Little Snoring` and `Badminton` arrive with a name
     * and a position and nothing else. Those stay `UNTYPED`, and go out as a name with a position.
     *
     * **`WP - ` prefixes a waypoint the pilot invented.** Stripping it is not cosmetic: five
     * characters is the whole budget, and `WP - Kapelle-op-den-Bos` truncates to `WPKAP` with the
     * prefix and `KAPEL` without it. It also states what the file otherwise only implies, so those
     * points become `USER` rather than `UNTYPED`.
     */
    private fun skyDemonPoint(node: Node, label: String?, at: Coordinate?): ImportedPoint {
        val sym = child(node, "sym")?.textContent?.trim()?.takeIf { it.isNotEmpty() }
        if (sym != null) return ImportedPoint(label, sym, at, Kind.DATABASE)
        if (label != null && label.startsWith(USER_WAYPOINT_PREFIX, ignoreCase = true)) {
            return ImportedPoint(label.drop(USER_WAYPOINT_PREFIX.length).trim(), null, at, Kind.USER)
        }
        return ImportedPoint(label, null, at, Kind.UNTYPED)
    }

    private const val USER_WAYPOINT_PREFIX = "WP - "

    /**
     * Refuse a SkyDemon waypoint library, which is a bag of places and not a route.
     *
     * `Name.userwaypoints.gpx` is what its Tools menu exports: `<wpt>` elements with no `<rte>` at
     * all, each carrying an empty `<extensions><identifier /><category /></extensions>`. Without
     * this the `<wpt>` fallback above would read one as a route, and a library of thirty six
     * castles would arrive as a thirty six point navigation in alphabetical order.
     *
     * The `<extensions>` pair is the discriminator rather than the file name, because a file name
     * is the one thing a share can lose. It is also a different GPX dialect from the route export
     * above: unprefixed, in the default namespace, and with no `skd` declaration.
     */
    private fun refuseWaypointLibrary(doc: Document) {
        val library = elements(doc, "wpt").any { node ->
            val extensions = (node as? Element)?.let { descendant(it, "extensions") }
            extensions != null &&
                (child(extensions, "identifier") != null || child(extensions, "category") != null)
        }
        if (library) {
            throw RouteParseException(
                "this is a SkyDemon waypoint library, not a route. It is a set of places with no " +
                    "order to fly them in. Open a route instead, or plan one through these points " +
                    "in SkyDemon and export that",
                Problem.WaypointLibrary,
            )
        }
    }

    /**
     * SkyDemon's own `.flightplan`, which is positions and nothing else.
     *
     * ```xml
     * <DivelementsFlightPlanner>
     *   <Aircraft … />
     *   <PrimaryRoute CourseType="GreatCircle" Start="N495619.90 E0090345.20" Rules="Vfr">
     *     <RhumbLineRoute To="N495837.00 E0090308.00" Level="MSL" LevelChange="B" />
     *     <Alternate      To="N530408.65 E0081848.55" Level="MSL" LevelChange="B" />
     *     <ReferencedAirfields />
     *   </PrimaryRoute>
     * </DivelementsFlightPlanner>
     * ```
     *
     * **There are no identifiers and no names anywhere in the file.** Not for the aerodromes, not
     * for a VOR, not for anything: five modern samples carry `<ReferencedAirfields />` empty every
     * time, and `<WaypointNameHints>`, a table keyed by the exact coordinate string, appears only
     * in a file from 2013 and in nothing since. SkyDemon resolves names out of its own database by
     * position when it loads one, so the file has no reason to carry them.
     *
     * A route of bare positions is therefore the honest reading, and it is a complete one: the
     * navigator flies exactly the planned track, and every point arrives as a user waypoint. What
     * is lost is the names, which the file does not have. A pilot who wants them exports the GPX,
     * where SkyDemon writes both.
     *
     * `<Alternate>` is a sibling of the legs rather than one of them, so it is not part of the
     * route. A second `<Route>` beside `<PrimaryRoute>` is a second route and is not spliced on,
     * the same rule the GPX reader applies to a second `<rte>`.
     */
    fun importSkyDemon(input: InputStream, device: Profile = Profiles.GPS175): ParsedRoute {
        val doc = parse(input)
        val route = (
            elements(doc, "PrimaryRoute").firstOrNull() ?: elements(doc, "Route").firstOrNull()
            ) as? Element
            ?: throw RouteParseException("this SkyDemon file holds no route")
        val positions = buildList {
            add(route.getAttribute("Start"))
            elements(route, "RhumbLineRoute").forEach {
                add((it as? Element)?.getAttribute("To").orEmpty())
            }
        }
        val imported = positions.mapIndexed { i, text ->
            // Refused rather than skipped. A position this cannot read is a leg of the flight, and
            // leaving it out would give a shorter route that still looks like one.
            val at = divelementsPosition(text) ?: throw RouteParseException(
                "point ${i + 1} of this SkyDemon route is at \"$text\", which is not a hemisphere " +
                    "letter followed by degrees, minutes and seconds"
            )
            ImportedPoint(null, null, at, Kind.UNTYPED)
        }
        if (imported.size < 2) {
            throw RouteParseException(
                "this SkyDemon route has fewer than two points", Problem.TooFewPoints
            )
        }
        return fromPoints(imported, device)
    }

    /**
     * A `.flightplan` position: `N495619.90 E0090345.20`.
     *
     * Hemisphere letter, then degrees, minutes and seconds run together, two digits of degrees for
     * a latitude and three for a longitude, with the fractional seconds optional. Matched on the
     * hemisphere letter rather than on order, so it does not matter which half comes first, and
     * each half may appear only once.
     *
     * **The decimal point is an escaped literal, and that is not a detail.** The only other reader
     * of this format anywhere wrote it as a bare `.`, which matches any character, so `N514807,00`
     * matched and its seconds `07,00` were read by a locale-aware parser as seven hundred. That
     * put the position 21 km north and 92 km west of where the file said and reported nothing
     * wrong.
     */
    private fun divelementsPosition(text: String?): Coordinate? {
        val tokens = text?.trim()?.split(WHITESPACE)?.filter { it.isNotEmpty() } ?: return null
        if (tokens.size != 2) return null
        var lat: Double? = null
        var lon: Double? = null
        for (token in tokens) {
            val latMatch = DIVELEMENTS_LAT.matchEntire(token)
            val lonMatch = DIVELEMENTS_LON.matchEntire(token)
            when {
                latMatch != null && lat == null -> lat = dms(latMatch) ?: return null
                lonMatch != null && lon == null -> lon = dms(lonMatch) ?: return null
                // Neither half matched, or the same half twice.
                else -> return null
            }
        }
        return coordinate(lat, lon)
    }

    /**
     * Degrees, minutes and seconds run together, signed by the hemisphere letter.
     *
     * Shared by the `.flightplan` reader and the `.pln` one, whose regexes differ in the width of
     * the degree field and in what sits between the fields but agree on the four groups.
     */
    private fun dms(m: MatchResult): Double? {
        val (hemisphere, degrees, minutes, seconds) = m.destructured
        val value = degrees.toDouble() + minutes.toDouble() / 60 +
            (seconds.toDoubleOrNull() ?: return null) / 3600
        return if (hemisphere == "S" || hemisphere == "W") -value else value
    }

    private val DIVELEMENTS_LAT = Regex("""^([NS])(\d{2})(\d{2})(\d{2}(?:\.\d+)?)$""")
    private val DIVELEMENTS_LON = Regex("""^([EW])(\d{3})(\d{2})(\d{2}(?:\.\d+)?)$""")

    /**
     * KML: every `<Placemark>` holding a `<Point>`, in document order.
     *
     * KML has no route concept at all, so document order is the only ordering a set of placemarks
     * carries. When the document also holds exactly one `<LineString>` whose vertices correspond
     * one for one with the placemarks, that path is explicit where document order is a
     * convention, so it decides the order instead. Anything short of an exact correspondence
     * falls back to document order rather than guessing at a partial match. See [orderByPath].
     */
    fun importKml(input: InputStream, device: Profile = Profiles.GPS175): ParsedRoute {
        val doc = parse(input)
        val imported = elements(doc, "Placemark").mapNotNull { node ->
            val point = (node as? Element)?.let { descendant(it, "Point") } ?: return@mapNotNull null
            val at = coordinates(child(point, "coordinates")?.textContent).firstOrNull()
            val label = child(node, "name")?.textContent?.trim()?.takeIf { it.isNotEmpty() }
            if (label == null && at == null) null else ImportedPoint(label, null, at, Kind.UNTYPED)
        }
        if (imported.size < 2) {
            throw RouteParseException("KML has fewer than two placemarks", Problem.TooFewPoints)
        }

        val lines = elements(doc, "LineString")
        val path = lines.singleOrNull()?.let { coordinates(child(it, "coordinates")?.textContent) }
        return fromPoints(path?.let { orderByPath(imported, it) } ?: imported, device)
    }

    /**
     * Microsoft Flight Simulator `.pln`.
     *
     * ```xml
     * <ATCWaypoint id="LFPL">
     *   <ATCWaypointType>Airport</ATCWaypointType>
     *   <WorldPosition>N48° 49' 19.00",E002° 37' 22.00",+001500.00</WorldPosition>
     *   <ICAO><ICAOIdent>LFPL</ICAOIdent></ICAO>
     * </ATCWaypoint>
     * ```
     *
     * The `id` attribute is the exporter's own label, capped at ten characters and stripped of
     * anything but letters and digits. `<ICAOIdent>` is the identifier the simulator's database
     * knows, so it is what a typed point is sent as.
     *
     * The `<DepartureID>`/`<DestinationID>` block at the head of the file is metadata that
     * repeats the first and last waypoint, and is deliberately not read: the waypoint list is the
     * route.
     */
    fun importPln(input: InputStream, device: Profile = Profiles.GPS175): ParsedRoute {
        val imported = elements(parse(input), "ATCWaypoint").mapNotNull { node ->
            val label = (node as? Element)?.getAttribute("id")?.trim()?.takeIf { it.isNotEmpty() }
            val stated = child(node, "ICAO")?.let { child(it, "ICAOIdent") }
                ?.textContent?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
            val at = worldPosition(child(node, "WorldPosition")?.textContent)
            val kind = plnKind(child(node, "ATCWaypointType")?.textContent?.trim())
            if (label == null && stated == null && at == null) null
            else ImportedPoint(label, stated, at, kind)
        }
        if (imported.size < 2) {
            throw RouteParseException(
                "flight plan has fewer than two waypoints", Problem.TooFewPoints
            )
        }
        return fromPoints(imported, device)
    }

    /**
     * What a file said a point is.
     *
     * [UNTYPED] is not a synonym for [USER]. GPX and KML cannot say what a point is, and reading
     * "the file did not say" as "the file said this name is its own invention" is what sends an
     * airway heuristic looking at a name nobody claimed. Both travel as a name with its position;
     * only the [USER] claim is load bearing anywhere else.
     */
    private enum class Kind { AIRPORT, DATABASE, USER, UNTYPED }

    /** A point as a file gave it to us. */
    private data class ImportedPoint(
        /** The rawest thing the file called this point: free text, accents and all. */
        val label: String?,
        /** An identifier the file asserts a database knows. Never invented here. */
        val stated: String?,
        val at: Coordinate?,
        val kind: Kind,
    )

    /** A `<waypoint-table>` entry: what the route points refer back to. */
    private data class TableEntry(val at: Coordinate?, val type: String?)

    /** The `.fpl` type that means "this name is not in any database". */
    private const val USER_WAYPOINT = "USER WAYPOINT"

    private fun fplKind(type: String?): Kind = when {
        type == null -> Kind.UNTYPED
        USER_WAYPOINT.equals(type.trim(), ignoreCase = true) -> Kind.USER
        "AIRPORT".equals(type.trim(), ignoreCase = true) -> Kind.AIRPORT
        else -> Kind.DATABASE
    }

    private fun plnKind(type: String?): Kind = when (type?.lowercase()) {
        "airport" -> Kind.AIRPORT
        "user" -> Kind.USER
        null, "", "none" -> Kind.UNTYPED
        else -> Kind.DATABASE
    }

    /**
     * Positions are rounded to six decimal places, which is a tenth of a metre.
     *
     * Not cosmetic. The wire form quantises to a tenth of a minute, about 185 m, so a point
     * whose true position sits exactly on one of those boundaries rounds either way depending on
     * how the file happened to write it. SD-VFR's `.pln` gives 2°34'15" exactly and its `.fpl`
     * gives 2.5708333333333, a decimal truncated just below the same value, and the two land on
     * opposite sides. Two of the twenty two points in one navigation did this. Rounding first
     * puts them back together and moves nothing else: a tenth of a metre cannot cross a boundary
     * that is 185 m apart unless the point was already on it.
     */
    private const val POSITION_SCALE = 1_000_000.0

    private fun position(lat: String?, lon: String?): Coordinate? =
        coordinate(lat?.trim()?.toDoubleOrNull(), lon?.trim()?.toDoubleOrNull())

    /**
     * A position, bounded and quantised. Every reader here builds one through this.
     *
     * Null means the file gave no position, which is an ordinary thing for a file to do. A
     * position that is stated and is not on the earth is refused instead, because the alternative
     * is a point that quietly loses its coordinates and a route that still looks like a route.
     *
     * `NaN` is refused here rather than at encode time. `toDoubleOrNull` parses the string "NaN"
     * happily, and a NaN passes every range test below it, since every comparison against one is
     * false; it then truncates to zero degrees and encodes as a point in the Gulf of Guinea.
     */
    private fun coordinate(lat: Double?, lon: Double?): Coordinate? {
        val y = lat ?: return null
        val x = lon ?: return null
        if (y.isNaN() || x.isNaN()) {
            throw RouteParseException(
                "this file gives a position that is not a number", Problem.PositionNotANumber
            )
        }
        if (y < -MAX_LATITUDE || y > MAX_LATITUDE || x < -MAX_LONGITUDE || x > MAX_LONGITUDE) {
            throw RouteParseException(
                "this file gives a position at $y, $x, which is off the earth",
                Problem.PositionOffEarth(y, x),
            )
        }
        return Coordinate(quantise(y), quantise(x))
    }

    private fun quantise(v: Double) = round(v * POSITION_SCALE) / POSITION_SCALE

    /**
     * A KML `<coordinates>` list, tolerantly.
     *
     * OGC KML separates `longitude,latitude[,altitude]` tuples with whitespace. SD-VFR separates
     * them with nothing at all, so a twenty two point path arrives as sixty six comma separated
     * numbers, and every `<Point>` arrives with a trailing comma. A conformant parser reads that
     * path as one tuple of sixty six fields.
     *
     * So: split on whitespace first, and when that gives more than one token each token is a
     * tuple. One token is the SD-VFR form, read as groups of three. **What that cannot
     * disambiguate** is a single token of two field tuples with the altitude left out, which is
     * legal KML that nothing appears to write; such a list is refused rather than misread.
     */
    private fun coordinates(text: String?): List<Coordinate> {
        val tokens = text?.trim()?.split(WHITESPACE)?.filter { it.isNotEmpty() } ?: return emptyList()
        if (tokens.isEmpty()) return emptyList()
        val tuples = if (tokens.size > 1) {
            tokens.map { it.split(',').filter { field -> field.isNotBlank() } }
        } else {
            val fields = tokens[0].split(',').filter { it.isNotBlank() }
            if (fields.size > 3 && fields.size % 3 != 0) {
                throw RouteParseException(
                    "a KML coordinate list of ${fields.size} numbers with no spaces in it is not " +
                        "a whole number of longitude, latitude and altitude triples"
                )
            }
            fields.chunked(3)
        }
        return tuples.mapNotNull { tuple ->
            if (tuple.size < 2) return@mapNotNull null
            val x = tuple[0].trim().toDoubleOrNull() ?: return@mapNotNull null
            val y = tuple[1].trim().toDoubleOrNull() ?: return@mapNotNull null
            // Grouping in threes is a reading of the file, so it is checked rather than assumed:
            // a list that is really longitude and latitude pairs, read three at a time, throws an
            // altitude into a longitude and lands out of range almost at once. The message says
            // which reading failed, which [coordinate]'s cannot, so the check is repeated here.
            if (y < -MAX_LATITUDE || y > MAX_LATITUDE || x < -MAX_LONGITUDE || x > MAX_LONGITUDE) {
                throw RouteParseException(
                    "a KML coordinate does not read as longitude, latitude: $x, $y is off the earth"
                )
            }
            coordinate(y, x)
        }
    }

    /**
     * A `.pln` `<WorldPosition>`: `N48° 49' 19.00",E002° 37' 22.00",+001500.00`.
     *
     * Matched on the hemisphere letter rather than on field order, so it does not care which of
     * the two comes first, and loosely on what sits between the degrees and the minutes, because
     * the degree sign has been seen written several ways. The altitude is ignored: the navigator
     * takes a route, not a vertical profile.
     */
    private fun worldPosition(text: String?): Coordinate? {
        if (text == null) return null
        var lat: Double? = null
        var lon: Double? = null
        for (m in DMS.findAll(text)) {
            val signed = dms(m) ?: return null
            val hemisphere = m.groupValues[1]
            if (hemisphere == "N" || hemisphere == "S") lat = lat ?: signed else lon = lon ?: signed
        }
        return coordinate(lat, lon)
    }

    private val DMS = Regex("([NSEW])\\s*(\\d+)[^0-9]+(\\d+)'\\s*([0-9.]+)\"")

    private val WHITESPACE = Regex("\\s+")

    /**
     * Reorder placemarks to follow an explicit path, when the two describe the same places.
     *
     * Every vertex has to consume a distinct placemark at the same wire position, which is what
     * makes this exact rather than a nearest-point match: a path that visits somewhere the
     * placemarks do not, or that has a different number of vertices, returns null and the caller
     * keeps document order. Two placemarks at one position, which a circuit that passes the same
     * landmark twice produces, are consumed by two vertices there and stay distinct.
     */
    private fun orderByPath(points: List<ImportedPoint>, path: List<Coordinate>): List<ImportedPoint>? {
        if (path.size != points.size) return null
        val remaining = points.toMutableList()
        return path.map { vertex ->
            val i = remaining.indexOfFirst { it.at == vertex }
            if (i < 0) return null
            remaining.removeAt(i)
        }
    }

    /**
     * Fold a free text label into the alphabet an identifier is allowed to use.
     *
     * Accents decompose and their marks are dropped, everything that is not a letter or a digit
     * goes, and what is left is upper case. `L'obélisque` becomes `LOBELISQUE` and
     * `Château de Chantilly` becomes `CHATEAUDECHANTILLY`.
     *
     * **This is the hinge the whole four format equivalence turns on**, and it is measured rather
     * than documented: SD-VFR's `.fpl` identifier is the first five characters of this fold, its
     * `.pln` id the first ten, and its GPX and KML names the label itself. Truncating any of them
     * to the navigator's cap gives the same string, which is why one navigation exported four
     * ways can produce one route.
     */
    private fun fold(text: String?): String {
        if (text == null) return ""
        return buildString {
            for (c in Normalizer.normalize(spellOut(text), Normalizer.Form.NFD)) {
                when (c) {
                    in 'A'..'Z', in '0'..'9' -> append(c)
                    in 'a'..'z' -> append(c.uppercaseChar())
                }
            }
        }
    }

    /**
     * Letters that are not accented versions of anything, so nothing decomposes them.
     *
     * `Œ` has no canonical decomposition, so NFD leaves it whole and the filter in [fold] then
     * deletes it: `Cœuvres` would fold to `CUVRES`. That is not hypothetical for this project,
     * since Cœuvres-et-Valsery is fifteen kilometres from Soissons and inside the area of the
     * navigation these rules were derived from.
     *
     * **UNCONFIRMED which way SD-VFR spells them**, because no sample contains one. Writing them
     * out is what a French reader expects and what transliteration conventions say, so that is
     * the guess; if a file ever shows the exporter dropping them instead, this is the one place
     * to change. `ß` needs no entry: `uppercaseChar` already gives `SS`.
     */
    private fun spellOut(text: String): String {
        if (text.none { it in LIGATURES }) return text
        return buildString {
            for (c in text) append(LIGATURES[c] ?: c.toString())
        }
    }

    private val LIGATURES = mapOf(
        'Æ' to "AE", 'æ' to "ae", 'Œ' to "OE", 'œ' to "oe",
        'Ø' to "O", 'ø' to "o", 'Ł' to "L", 'ł' to "l",
        'Đ' to "D", 'đ' to "d", 'Ð' to "D", 'ð' to "d",
        'Þ' to "TH", 'þ' to "th",
    )

    /**
     * An aerodrome identifier an exporter has decorated: four ICAO letters and up to two more.
     *
     * SD-VFR builds every identifier as five characters of its own label plus, en route, a
     * sequence number, so Lognes (`LFPL`, "LOGNES") becomes `LFPLL`, and Coulommiers as the
     * second point becomes `LFPKC2`.
     *
     * **UNCONFIRMED**, and deliberately narrow: this is inference from files by one exporter, so
     * it is applied to the first and last point of a route and nowhere else. A Garmin export
     * types its airports and never meets it.
     */
    private val DECORATED_AIRPORT = Regex("^([A-Z]{4})[A-Z0-9]{0,2}$")

    /**
     * The same inference for a file that has only a human name: SD-VFR writes
     * `LFPL LOGNES EMERAINVILLE`, so a first word of exactly four letters, with more words after
     * it, is an ICAO identifier.
     *
     * **Ends only, and the files prove why.** One 22 point navigation has three en route labels
     * whose first word is four letters: `LFAD COMPIEGNE MARGNY` and `LFJS SOISSONS COURMELLES`,
     * which really are aerodromes overflown as reporting points, and `Parc Astérix`, which is a
     * theme park. Route wide, this rule would send a waypoint called `PARC`.
     */
    private fun icaoWord(label: String?): String? {
        val words = label?.trim()?.split(WHITESPACE)?.filter { it.isNotEmpty() } ?: return null
        if (words.size < 2) return null
        val first = words[0].uppercase()
        return first.takeIf { it.length == 4 && it.all { c -> c in 'A'..'Z' } }
    }

    /**
     * The identifier a point starts from, before the route has a say.
     *
     * An identifier the file states is taken verbatim: a VOR is called what the database calls
     * it, and rewriting that would point the navigator somewhere else. A label is folded, and
     * then, for a point the file called its own invention, SD-VFR's decoration comes off: an
     * identifier ending in its own 0 based route position is that position, so `LOBEL1` at index
     * 1 is `LOBEL` and `ABBAY10` at index 10 is `ABBAY`. The suffix has to *equal* the index, so
     * the rule checks itself against the file rather than trusting a shape.
     */
    private fun baseIdent(point: ImportedPoint, index: Int, device: Profile): String {
        if (point.kind == Kind.AIRPORT || point.kind == Kind.DATABASE) {
            point.stated?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        val folded = fold(point.label)
        if (point.kind == Kind.USER) {
            val suffix = index.toString()
            // The stem has to be the five characters SD-VFR takes from the label, not merely
            // "something followed by the right digits". Without that, a user waypoint genuinely
            // called V334 sitting fourth in a route would lose its 4 and become V33, and a route
            // point named for an airway is not a far fetched file.
            if (folded.endsWith(suffix) &&
                folded.length - suffix.length == device.waypointNameLen
            ) {
                return folded.dropLast(suffix.length)
            }
        }
        return folded
    }

    /** Only a name this importer invented may be rewritten to fit. */
    private fun renameable(point: ImportedPoint) =
        point.kind == Kind.USER || point.kind == Kind.UNTYPED

    /**
     * Shorten names the navigator is too small for, keeping them distinct across the whole route.
     *
     * The parser on the receiving side reads [Profile.waypointNameLen] characters and stops, leaving
     * its read pointer inside the name. The `:F:` handler's test for the comma before a position
     * then fails, so the point loses its coordinates and the message is flagged malformed. A name
     * that runs too long is therefore not a cosmetic problem and cannot simply be passed through.
     *
     * A truncation that is unique in the route is kept, because it reads best: `LOBELISQUE`
     * becomes `LOBEL`. When it is not unique, **every** member of the colliding group takes the
     * point's own 0 based route position instead, not just the later ones, so four abbeys become
     * `ABBA5`, `ABBA7`, `ABB10` and `ABB11` rather than one `ABBAY` and three near misses.
     *
     * The route position is the disambiguator because it is the only thing all four formats
     * share. The `.fpl` states it, and the other three do not, so anything read out of the name
     * would make the same navigation import differently depending on which file it came from.
     * It also happens to be what the exporter itself chose: `ABB10` next to SD-VFR's `ABBAY10`
     * is legible in a way that a rewritten last character is not.
     *
     * Uniqueness is checked against every identifier in the route, the ones the file stated
     * included, since colliding with a real waypoint would be no better.
     */
    private fun fitNames(
        points: List<ImportedPoint>,
        bases: List<String>,
        reserved: Set<String>,
        device: Profile,
    ): List<String> {
        // A name this importer may rewrite is measured by its truncation. One the file stated is
        // measured as itself, because it goes out as itself or not at all.
        val wanted = points.mapIndexed { i, point ->
            if (renameable(point)) bases[i].take(device.waypointNameLen) else bases[i]
        }
        // A point with no name at all is not in any collision: it is going out as a bare
        // position. Counting the empty ones together would number them instead, so two unnamed
        // points in one route would arrive called 1 and 2.
        val shared = wanted.filter { it.isNotEmpty() }
            .groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        val taken = reserved.toMutableSet()
        points.forEachIndexed { i, point -> if (!renameable(point)) taken += wanted[i] }

        return points.mapIndexed { i, point ->
            if (!renameable(point) || bases[i].isEmpty()) return@mapIndexed wanted[i]
            val base = bases[i]
            val truncated = wanted[i]
            val index = i.toString().takeLast(device.waypointNameLen)
            val candidates = sequence {
                if (truncated !in shared) yield(truncated)
                yield(base.take((device.waypointNameLen - index.length).coerceAtLeast(0)) + index)
                for (n in 1..99) {
                    // Locale.ROOT, for the reason formatLatLon gives: `%d` is localised, and a
                    // default locale of ar or fa would number a waypoint in Arabic Indic digits.
                    yield(
                        base.take((device.waypointNameLen - 2).coerceAtLeast(0)) +
                            String.format(Locale.ROOT, "%02d", n)
                    )
                }
            }
            val fit = candidates.firstOrNull { it !in taken } ?: truncated
            taken += fit
            fit
        }
    }

    /**
     * Fold away a run of points the navigator could not tell apart.
     *
     * A `.pln` writes its departure twice, once as an `Airport` point and once as a `User` point
     * at the same coordinates, and the same at the destination, so the same navigation is 24
     * points there and 22 everywhere else. The survivor is the one that says the most: an airport
     * the file named beats a user waypoint invented for the same spot.
     *
     * Only a *run* is folded. A circuit that passes the same landmark twice with something in
     * between is two points and stays two points.
     */
    private fun collapse(points: List<ImportedPoint>): List<ImportedPoint> {
        val out = mutableListOf<ImportedPoint>()
        for (point in points) {
            val last = out.lastOrNull()
            // The same coordinate, not merely the same wire position. Two points a tenth of a
            // minute apart encode alike, and that is ninety metres: near enough for a navigator
            // to draw one leg, not near enough to decide two reporting points are one place. The
            // duplicate this exists for is a file repeating itself, so the two are identical.
            if (last != null && point.at != null && point.at == last.at) {
                if (rank(point.kind) > rank(last.kind)) out[out.size - 1] = point
                continue
            }
            out += point
        }
        return out
    }

    private fun rank(kind: Kind) = when (kind) {
        Kind.AIRPORT -> 3
        Kind.DATABASE -> 2
        Kind.USER -> 1
        Kind.UNTYPED -> 0
    }

    /**
     * An aerodrome at each end where the file gives one, and a point where it does not.
     *
     * **An end is not required to be an aerodrome.** A VFR navigation between two unlicensed
     * strips carries no ICAO code at either end, because there is none to carry: SkyDemon exports
     * `Little Snoring` and `Badminton` as names with positions and nothing else, and its own
     * native format carries no identifier for any point at all.
     *
     * Nothing on the wire needs one either. A route is a list of `:F:` fixes, and the departure
     * and arrival differ only in that `:DA:`/`:AA:` may carry a procedure for them, an element
     * emitted only when there is a procedure or runway to put in it. So an end with no identifier
     * becomes the first or last en route element, exactly as [RouteParser.parse] reads the first
     * and last token of the route box, and the round trip holds.
     */
    private fun fromPoints(imported: List<ImportedPoint>, device: Profile): ParsedRoute {
        val points = collapse(imported)
        if (points.size < 2) {
            throw RouteParseException(
                "every point in this route is at the same place, so there is nothing to fly",
                Problem.AllPointsSame,
            )
        }
        val bases = points.mapIndexed { i, point -> baseIdent(point, i, device) }
        val departure = airport(points.first(), bases.first(), device)
        val arrival = airport(points.last(), bases.last(), device)
        // The two airports are reserved before anything is shortened onto them: what goes on the
        // wire is the trimmed `LFPL`, not the `LFPLL` the file wrote, so reserving the file's
        // spelling would leave the trimmed one free for an en route point to collide with.
        val names = fitNames(points, bases, setOfNotNull(departure, arrival), device)

        // An end that is not an aerodrome stays in the list as a point of its own.
        val from = if (departure != null) 1 else 0
        val to = if (arrival != null) points.size - 1 else points.size
        val enroute: List<RouteElement> = (from until to).map { i ->
            element(points[i], bases[i], names[i], device)
        }
        return ParsedRoute(
            departure?.let { Departure(it) },
            enroute,
            arrival?.let { Arrival(it) },
        )
    }

    /**
     * The aerodrome identifier for an end, or null when the file does not give one.
     *
     * The recovery order is load bearing: a point the file typed `AIRPORT` is called what the file
     * called it, then SD-VFR's decorated form (`LFPLL` to `LFPL`), then a first word of exactly
     * four letters (`LFPL LOGNES EMERAINVILLE`). A base that is nothing like an identifier is
     * simply not an aerodrome, rather than something for `buildRoute` to refuse by length later.
     *
     * The result is bounded by [Profile.airportNameLen], which the first two rules already satisfy by
     * construction. Only a file that types a long identifier `AIRPORT` can exceed it, and such a
     * point is better sent as a fix than refused: the cap belongs to `:DA:`/`:AA:`, which this
     * route will not carry.
     */
    private fun airport(point: ImportedPoint, base: String, device: Profile): String? {
        if (base.isEmpty()) return null
        // The file said so, so nothing here gets to second guess it.
        if (point.kind == Kind.AIRPORT) return base.takeIf { it.length <= device.airportNameLen }
        DECORATED_AIRPORT.matchEntire(base)?.let { return it.groupValues[1] }
        return icaoWord(point.label)
    }

    private fun element(point: ImportedPoint, base: String, name: String, device: Profile): RouteElement {
        if (name.isEmpty()) {
            return point.at ?: throw RouteParseException("a route point has neither a name nor a position")
        }
        // An airway is only ever the file's own word: the fold changed nothing, the route did not
        // shorten or number it, and the file did not call it a point of its own. Without that
        // last part the heuristic would eat what this importer invents, since a two letter label
        // numbered by its route position is exactly the shape it matches.
        if (point.kind != Kind.USER && name == base && base == point.label?.trim()?.uppercase() &&
            RouteParser.looksLikeAirway(name)
        ) {
            return Airway(name)
        }
        if (renameable(point) && point.at != null) {
            // The file either said this name is its own invention or could not say, so send the
            // position with it: the navigator may have nothing to resolve the name against, and a
            // named user waypoint arrives as both. A name that still will not fit falls back to
            // the bare position, which is the half worth keeping.
            return runCatching {
                UserWaypoint(name, point.at.lat, point.at.lon)
                    .also { it.checkName(device.waypointNameLen) }
            }.getOrElse { point.at }
        }
        // An identifier the file stated. It is not ours to shorten, so a name the navigator
        // cannot read is refused rather than turned into a different waypoint.
        if (name.length > device.waypointNameLen) {
            throw RouteParseException(
                "\"$name\" is ${name.length} characters and the navigator reads " +
                    "${device.waypointNameLen}. It came from the file as an identifier, so " +
                    "shortening it would name a different waypoint",
                Problem.StatedIdentTooLong(name, name.length, device.waypointNameLen),
            )
        }
        return Waypoint(name)
    }

    /**
     * Parse, and say so in words when the file is not XML.
     *
     * What comes out of a parser is `Content is not allowed in prolog`, which tells a pilot
     * holding a phone nothing. The wrapping is at this level rather than at each importer's
     * because all four share the parser.
     */
    private fun parse(input: InputStream): Document = try {
        parser().parse(input)
    } catch (e: Exception) {
        throw RouteParseException(
            "this file is not readable as XML. A route has to be the file the planner exported, " +
                "not a screenshot of it or a copy pasted into a document",
            Problem.NotXml,
        )
    }

    private fun parser() =
        DocumentBuilderFactory.newInstance().apply {
            // Required, and implemented everywhere: the importers match on local names.
            isNamespaceAware = true
            isExpandEntityReferences = false

            // Route files come from elsewhere, so do not let one fetch or expand anything. Every
            // knob below is optional, because JAXP lets an implementation refuse one two
            // different ways, and Android's takes both: setFeature throws
            // ParserConfigurationException naming the feature it does not have, and
            // setXIncludeAware is a method on the base class it never overrode, so it throws
            // UnsupportedOperationException.
            //
            // Unguarded, those were two separate crashes on every import a phone ever tried, the
            // second hidden behind the first, while the JVM tests, running on Xerces, reported a
            // healthy parser both times.
            harden { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            harden { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            harden { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            harden { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            harden { isXIncludeAware = false }
        }.newDocumentBuilder().apply {
            // The defence that asks the parser for nothing, and so the one actually relied on:
            // an external entity resolves to nothing instead of to a file or a URL.
            setEntityResolver { _, _ -> InputSource(StringReader("")) }
        }

    /**
     * Apply one hardening step, and accept a parser that has never heard of it.
     *
     * Deliberately broad. The alternative is enumerating which implementation refuses what, on a
     * class the platform is free to swap. A route that imports on a weaker parser beats one that
     * refuses to import at all, given [setEntityResolver] holds the line regardless.
     */
    private inline fun harden(step: () -> Unit) {
        runCatching(step)
    }

    /** Elements by local name, whether or not the file bothered to declare a namespace. */
    private fun elements(doc: Document, name: String): List<Node> =
        doc.getElementsByTagNameNS("*", name).toList().ifEmpty {
            doc.getElementsByTagName(name).toList()
        }

    private fun elements(element: Element, name: String): List<Node> =
        element.getElementsByTagNameNS("*", name).toList().ifEmpty {
            element.getElementsByTagName(name).toList()
        }

    /** The first descendant by local name, at any depth: a `<Point>` may sit in a MultiGeometry. */
    private fun descendant(element: Element, name: String): Node? =
        element.getElementsByTagNameNS("*", name).item(0)
            ?: element.getElementsByTagName(name).item(0)

    private fun child(node: Node, name: String): Node? {
        val kids = node.childNodes
        for (i in 0 until kids.length) {
            val k = kids.item(i)
            if (k.localName == name || k.nodeName == name) return k
        }
        return null
    }

    private fun org.w3c.dom.NodeList.toList(): List<Node> = (0 until length).map { item(it) }
}
