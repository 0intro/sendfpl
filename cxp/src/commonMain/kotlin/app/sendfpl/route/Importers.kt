package app.sendfpl.route

import app.sendfpl.cxp.Arrival
import app.sendfpl.cxp.Coordinate
import app.sendfpl.cxp.Departure
import app.sendfpl.cxp.MAX_LATITUDE
import app.sendfpl.cxp.MAX_LONGITUDE
import app.sendfpl.cxp.Problem
import app.sendfpl.cxp.Profile
import app.sendfpl.cxp.Profiles
import app.sendfpl.cxp.RouteElement
import app.sendfpl.cxp.UserWaypoint
import app.sendfpl.cxp.Waypoint
import app.sendfpl.cxp.digits
import kotlin.math.round

/**
 * Import a route from a file: a Garmin `.fpl`, GPX, KML, a flight simulator `.pln` or a SkyDemon
 * `.flightplan`, all XML, or a Garmin `.gfp`, which [Gfp] reads.
 *
 * The files agree about little but the positions, so each XML parser only says what its file
 * said, and [fromPoints] decides every identifier for all five. See [fold] and [fitNames]. The XML
 * is read by [parseXml], the same code on every platform.
 */
object RouteImporter {

    /**
     * Pick a parser from the file name, falling back to sniffing the root element.
     *
     * [device] sets how far a name is shortened. Every profile has the same caps today, but a name
     * cut to another model's cap would desynchronise its parser, so the cap is the profile's.
     */
    fun import(
        fileName: String?,
        bytes: ByteArray,
        device: Profile = Profiles.GPS175,
    ): ParsedRoute {
        val lower = fileName?.lowercase().orEmpty()
        return when {
            lower.endsWith(".fpl") -> importFpl(bytes, device)
            lower.endsWith(".gpx") -> importGpx(bytes, device)
            lower.endsWith(".kml") -> importKml(bytes, device)
            lower.endsWith(".pln") -> importPln(bytes, device)
            lower.endsWith(".flightplan") -> importSkyDemon(bytes, device)
            lower.endsWith(".gfp") -> Gfp.parse(bytes.decodeToString())
            else -> {
                val head = bytes.copyOfRange(0, minOf(bytes.size, 512)).decodeToString()
                when {
                    // Not XML, so it is recognised by what it begins with rather than by a tag.
                    head.trimStart('\uFEFF').startsWith("FPN/") ->
                        Gfp.parse(bytes.decodeToString())
                    head.contains("<flight-plan") -> importFpl(bytes, device)
                    head.contains("<gpx") -> importGpx(bytes, device)
                    head.contains("<kml") -> importKml(bytes, device)
                    head.contains("<SimBase.Document") ||
                        head.contains("<FlightPlan.FlightPlan") ->
                            importPln(bytes, device)
                    head.contains("<DivelementsFlightPlanner") ->
                        importSkyDemon(bytes, device)
                    // A KMZ, a zipped KML, is a second format and not read here. It is named
                    // rather than left to an XML error about a byte that is not a tag.
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
     * The schema is Garmin's public format, not one recovered by reverse engineering: check it
     * against a Garmin Pilot export before trusting it. Namespaces are ignored, as exporters differ
     * on declaring one. [alignedTable] and [withoutAlternate] each close a way real files go wrong
     * in silence.
     */
    fun importFpl(bytes: ByteArray, device: Profile = Profiles.GPS175): ParsedRoute {
        val doc = parse(bytes)
        // The waypoint table holds the positions and types, as a route point states only its key.
        val table = all(doc, "waypoint").map { node ->
            TableEntry(
                WaypointKey(
                    text(node.child("identifier")),
                    text(node.child("type")),
                    text(node.child("country-code")),
                ),
                position(node.child("lat")?.textContent, node.child("lon")?.textContent),
            )
        }

        val points = all(doc, "route-point")
        val keys = points.map { node ->
            WaypointKey(
                text(node.child("waypoint-identifier")),
                text(node.child("waypoint-type")),
                text(node.child("waypoint-country-code")),
            )
        }
        val aligned = alignedTable(keys, table)
        val imported = points.mapIndexed { i, node ->
            val key = keys[i]
            val entry = aligned?.get(i) ?: lookUp(key, table)
            val at = position(node.child("lat")?.textContent, node.child("lon")?.textContent)
                ?: entry?.at
            // Exporters differ on which of the two types they fill in.
            val kind = fplKind(key.type ?: entry?.key?.type)
            val ident = key.ident
            if (ident == null && at == null) {
                throw RouteParseException(
                    "point ${i + 1} of ${points.size} has no identifier and no position, and " +
                        "this file's waypoint table does not line up with its route, so there " +
                        "is nothing to read there. Leaving the point out would give a shorter " +
                        "route that still looks right, so the file is refused instead"
                )
            }
            ImportedPoint(ident, ident, at, kind, mayBeDecorated = kind == Kind.USER)
        }
        if (imported.size < 2) {
            throw RouteParseException(
                "flight plan has fewer than two route points", Problem.TooFewPoints
            )
        }
        val named = all(doc, "route-name").firstOrNull()?.textContent
        return fromPoints(withoutAlternate(imported, named), device)
    }

    /** An element's text, or null when it is missing or empty. */
    private fun text(node: XmlElement?): String? = node?.textContent?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * The `<waypoint-table>` in route order, or null when the file does not line up.
     *
     * A key does not always tell two points apart: SkyDemon 3.16.x writes `<identifier />` for
     * every `INT`, and two reporting points called D in one country share one. Every sample lists
     * one entry per route point, in route order, but the format does not promise it, so the lists
     * must be the same length and every route point must be able to mean the entry at its index.
     */
    private fun alignedTable(keys: List<WaypointKey>, table: List<TableEntry>): List<TableEntry>? {
        if (table.size != keys.size) return null
        if (keys.indices.any { !keys[it].canMean(table[it].key) }) return null
        return table
    }

    /**
     * The entry a route point names by its key, in a table that does not line up. Entries it could
     * mean at two places leave nothing to choose by, so the file is refused rather than given
     * either position.
     */
    private fun lookUp(key: WaypointKey, table: List<TableEntry>): TableEntry? {
        if (key.ident == null) return null
        val candidates = table.filter { key.canMean(it.key) }
        val places = candidates.map { it.at }.distinct()
        if (places.size > 1) {
            throw RouteParseException(
                "the waypoint table gives \"${key.ident}\" ${places.size} positions and does not " +
                    "line up with the route, so which one a route point means cannot be told"
            )
        }
        return candidates.firstOrNull()
    }

    /**
     * Drop a trailing alternate, where the file's own route name says the destination is the point
     * before it.
     *
     * SkyDemon appends the alternate as one more `AIRPORT` point: EDFC to EDDW with EDWH as the
     * alternate ends `EDDW`, `EDWH` under `<route-name>EDFC EDDW</route-name>`. Only a file that
     * contradicts itself this way is touched.
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
     * GPX route: `<rte>` with `<rtept name="…">` or a `<name>` child. An unnamed point has the
     * schema's mandatory `lat`/`lon`, so it is kept as a coordinate.
     *
     * Only the first `<rte>` is read, as splicing several would give a journey nobody planned, and
     * a file with none falls back to its `<wpt>` list. `<trk>` is never read: a track is the trail
     * an aircraft flew. SkyDemon's richer points are read by [skyDemonPoint].
     */
    fun importGpx(bytes: ByteArray, device: Profile = Profiles.GPS175): ParsedRoute {
        val doc = parse(bytes)
        val skyDemon = isSkyDemon(doc)
        val route = all(doc, "rte").firstOrNull()
        if (route == null && skyDemon) refuseWaypointLibrary(doc)
        val points = route?.descendants("rtept") ?: all(doc, "wpt")
        val imported = points.mapNotNull { node ->
            val label = node.attribute("name").takeIf { it.isNotBlank() }
                ?: node.child("name")?.textContent?.trim()?.takeIf { it.isNotEmpty() }
            val at = position(node.attribute("lat"), node.attribute("lon"))
            if (label == null && at == null) return@mapNotNull null
            // SkyDemon puts an identifier in `<sym>` only inside a route.
            if (skyDemon && route != null) skyDemonPoint(node, label, at)
            else ImportedPoint(label, null, at, Kind.UNTYPED)
        }
        if (imported.size < 2) {
            throw RouteParseException("GPX route has fewer than two points", Problem.TooFewPoints)
        }
        return fromPoints(imported, device)
    }

    /** SkyDemon's GPX extension namespace. */
    private const val SKYDEMON_GPX_NS = "http://www.skydemon.aero/gpxextensions"

    /**
     * Whether SkyDemon wrote this GPX: it declares SkyDemon's namespace, as every route export
     * sampled does, or its `creator` names SkyDemon, for an export with no extensions.
     */
    private fun isSkyDemon(root: XmlElement): Boolean {
        if (root.prefixOf(SKYDEMON_GPX_NS) != null) return true
        return root.attribute("creator").startsWith("SkyDemon", ignoreCase = true)
    }

    /**
     * A SkyDemon `<rtept>`.
     *
     * ```xml
     * <rtept lat="50.948610" lon="4.391944">
     *   <name>GRIMBERGEN / Lint</name>
     *   <sym>EBGB</sym>
     *   <extensions><skd:level type="A" value="1300" /></extensions>
     * </rtept>
     * ```
     *
     * `<sym>` is the database identifier, `EBGB` or `NIK` for the VOR named "Nicky", but only in a
     * SkyDemon route: elsewhere, SkyDemon's waypoint library included, it is a symbol such as
     * `GreenFlag`. A point with no `<sym>`, such as an unlicensed strip, stays untyped. `WP - `
     * marks a waypoint the pilot invented, so the point is [Kind.USER], and the prefix goes so that
     * `WP - Kapelle-op-den-Bos` becomes `KAPEL` rather than `WPKAP`.
     *
     * A `<sym>` with an empty `<name />` is an identifier SkyDemon gives no name, and it is not
     * taken for a database point: it stays untyped, a name with its position. The one sampled, the
     * reporting point `D` north of Antwerp, is in none of the navigator databases it was looked
     * for in, and a GTN sent a name it cannot place locks the point, where a bare position becomes
     * a user waypoint. Every `<sym>` sampled with a name, an aerodrome or a VOR, is in them. A
     * GPS 175 is sent the same string either way.
     */
    private fun skyDemonPoint(node: XmlElement, label: String?, at: Coordinate?): ImportedPoint {
        val sym = node.child("sym")?.textContent?.trim()?.takeIf { it.isNotEmpty() }
        if (sym != null) {
            return if (label != null) ImportedPoint(label, sym, at, Kind.DATABASE)
            else ImportedPoint(sym, null, at, Kind.UNTYPED)
        }
        if (label != null && label.startsWith(USER_WAYPOINT_PREFIX, ignoreCase = true)) {
            return ImportedPoint(label.drop(USER_WAYPOINT_PREFIX.length).trim(), null, at, Kind.USER)
        }
        return ImportedPoint(label, null, at, Kind.UNTYPED)
    }

    private const val USER_WAYPOINT_PREFIX = "WP - "

    /**
     * Refuse a SkyDemon waypoint library, `Name.userwaypoints.gpx`, which is places and not a
     * route: `<wpt>` elements and no `<rte>`, with `<identifier />` and `<category />` in their
     * `<extensions>`. The `<wpt>` fallback would read it as a route in alphabetical order. The
     * extensions decide, not the file name, which a share can lose.
     */
    private fun refuseWaypointLibrary(doc: XmlElement) {
        val library = all(doc, "wpt").any { node ->
            val extensions = node.descendants("extensions").firstOrNull()
            extensions != null &&
                (extensions.child("identifier") != null || extensions.child("category") != null)
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
     * No point has an identifier or a name: `<ReferencedAirfields />` is empty in every recent
     * sample, `<WaypointNameHints>` appears only in an old one, and SkyDemon looks names up by
     * position when it loads the file. So every point arrives as a bare position, and the GPX
     * export carries the names. `<Alternate>` is not a leg, and a second `<Route>` is not spliced
     * on.
     */
    fun importSkyDemon(bytes: ByteArray, device: Profile = Profiles.GPS175): ParsedRoute {
        val doc = parse(bytes)
        val route = all(doc, "PrimaryRoute").firstOrNull() ?: all(doc, "Route").firstOrNull()
            ?: throw RouteParseException("this SkyDemon file holds no route", Problem.TooFewPoints)
        val positions = buildList {
            add(route.attribute("Start"))
            route.descendants("RhumbLineRoute").forEach { add(it.attribute("To")) }
        }
        val imported = positions.mapIndexed { i, text ->
            // Refused rather than skipped: a route short of a leg still looks like a route.
            val at = divelementsPosition(text) ?: throw RouteParseException(
                "point ${i + 1} of this SkyDemon route is at \"$text\", which is not a hemisphere " +
                    "letter followed by degrees, minutes and seconds",
                Problem.UnreadablePosition(text),
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
     * A `.flightplan` position, `N495619.90 E0090345.20`: a hemisphere letter, then degrees (two
     * for a latitude, three for a longitude), minutes and seconds run together, the fractional
     * seconds optional. Either half may come first, and each appears once.
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
                latMatch != null && lat == null -> lat = dms(latMatch)
                lonMatch != null && lon == null -> lon = dms(lonMatch)
                // Neither half matched, or the same half twice.
                else -> return null
            }
        }
        return coordinate(lat, lon)
    }

    /** Degrees, minutes and seconds from a `.flightplan` or `.pln` match, signed by hemisphere. */
    private fun dms(m: MatchResult): Double {
        val (hemisphere, degrees, minutes, seconds) = m.destructured
        val min = minutes.toDouble()
        val sec = seconds.toDoubleOrNull() ?: throw unreadable(m.value)
        // Sixty stays legal: SD-VFR writes `8' 60.00"` where its rounding carries.
        if (min > 60 || sec > 60) throw unreadable(m.value)
        val value = degrees.toDouble() + min / 60 + sec / 3600
        return if (hemisphere == "S" || hemisphere == "W") -value else value
    }

    private val DIVELEMENTS_LAT = Regex("""^([NS])(\d{2})(\d{2})(\d{2}(?:\.\d+)?)$""")
    private val DIVELEMENTS_LON = Regex("""^([EW])(\d{3})(\d{2})(\d{2}(?:\.\d+)?)$""")

    /**
     * KML: every `<Placemark>` holding a `<Point>`, in document order, unless the document has
     * exactly one `<LineString>` whose vertices match the placemarks one for one, which then
     * decides the order. See [orderByPath].
     */
    fun importKml(bytes: ByteArray, device: Profile = Profiles.GPS175): ParsedRoute {
        val doc = parse(bytes)
        val imported = all(doc, "Placemark").mapNotNull { node ->
            val point = node.descendants("Point").firstOrNull() ?: return@mapNotNull null
            val at = kmlPoint(point.child("coordinates")?.textContent)
            val label = node.child("name")?.textContent?.trim()?.takeIf { it.isNotEmpty() }
            if (label == null && at == null) null else ImportedPoint(label, null, at, Kind.UNTYPED)
        }
        if (imported.size < 2) {
            throw RouteParseException("KML has fewer than two placemarks", Problem.TooFewPoints)
        }

        val lines = all(doc, "LineString")
        val path = lines.singleOrNull()?.let { coordinates(it.child("coordinates")?.textContent) }
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
     * `id` is the exporter's label, at most ten letters and digits, and `<ICAOIdent>` the
     * identifier the simulator's database knows. `<DepartureID>`/`<DestinationID>` repeat the ends
     * of the waypoint list and are not read.
     */
    fun importPln(bytes: ByteArray, device: Profile = Profiles.GPS175): ParsedRoute {
        val imported = all(parse(bytes), "ATCWaypoint").mapNotNull { node ->
            val label = node.attribute("id").trim().takeIf { it.isNotEmpty() }
            val stated = node.child("ICAO")?.child("ICAOIdent")
                ?.textContent?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
            val at = worldPosition(node.child("WorldPosition")?.textContent)
            val kind = plnKind(node.child("ATCWaypointType")?.textContent?.trim())
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
     * What a file said a point is. [UNTYPED] means the file could not say, as GPX and KML cannot,
     * and is not [USER]: only a [USER] name loses SD-VFR's route position suffix in [baseIdent],
     * and [USER] outranks [UNTYPED] where two points collapse into one.
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
        /** Whether this may be an aerodrome SD-VFR decorated: see [DECORATED_AIRPORT]. */
        val mayBeDecorated: Boolean = false,
    )

    /**
     * What Garmin's schema keys a `<waypoint-table>` entry by, and a `<route-point>` refers to it
     * by: identifier, type and country code together. A field the file leaves empty is null.
     */
    private data class WaypointKey(val ident: String?, val type: String?, val country: String?) {
        /**
         * Whether a route point with this key can mean [entry]: the same identifier unless the
         * point states none, and the same type and country code where both state one.
         */
        fun canMean(entry: WaypointKey): Boolean =
            (ident == null || ident.equals(entry.ident, ignoreCase = true)) &&
                agree(type, entry.type) && agree(country, entry.country)

        private fun agree(a: String?, b: String?) =
            a == null || b == null || a.equals(b, ignoreCase = true)
    }

    /** A `<waypoint-table>` entry: what the route points refer back to. */
    private data class TableEntry(val key: WaypointKey, val at: Coordinate?)

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
     * Positions are rounded to six decimal places, a tenth of a metre, so that a point two files
     * write differently lands on the same side of a wire boundary: SD-VFR's `.pln` gives 2°34'15"
     * and its `.fpl` 2.5708333333333, just below it.
     */
    private const val POSITION_SCALE = 1_000_000.0

    /** A position from its two halves as a file writes them, or null if the file gives none. */
    private fun position(lat: String?, lon: String?): Coordinate? {
        val y = lat?.trim().orEmpty()
        val x = lon?.trim().orEmpty()
        if (y.isEmpty() && x.isEmpty()) return null
        return coordinate(y.toDoubleOrNull() ?: throw unreadable(y), x.toDoubleOrNull() ?: throw unreadable(x))
    }

    /** A stated position that does not read, which is refused rather than dropped. */
    private fun unreadable(text: String) = RouteParseException(
        "this file gives a position, \"$text\", that cannot be read",
        Problem.UnreadablePosition(text),
    )

    /**
     * A position, bounded and quantised, or null when the file gives none. Every reader builds one
     * through this. One off the earth is refused rather than dropped, as a route short of a point
     * still looks like a route. So is NaN: `toDoubleOrNull` reads it, it passes every range test,
     * and it would encode as zero degrees.
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
     * A `<Point>`'s `<coordinates>`: one `longitude,latitude[,altitude]` tuple, or null when the
     * file writes none. SD-VFR ends it with a comma, and a space after a comma cannot be read as
     * anything else in a single tuple. Anything else is refused: a decimal comma read short lands
     * in range somewhere else, and a placemark that lost its position would go out as a bare name.
     */
    private fun kmlPoint(text: String?): Coordinate? {
        val written = text?.trim().orEmpty()
        if (written.isEmpty()) return null
        val fields = written.removeSuffix(",").split(',')
        if (fields.size !in 2..3) throw unreadable(written)
        val numbers = fields.map { it.trim().toDoubleOrNull() ?: throw unreadable(written) }
        return coordinate(numbers[1], numbers[0])
    }

    /**
     * A `<LineString>`'s `<coordinates>`. OGC KML separates `longitude,latitude[,altitude]` tuples
     * with whitespace and SD-VFR with nothing, so a single token is read in threes. Pairs with no
     * altitude run together the same way, legal KML that nothing appears to write, are refused
     * when they do not divide into threes, and otherwise misread into a path that matches no
     * placemark, so it orders nothing.
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
            val x = tuple[0].trim().let { it.toDoubleOrNull() ?: throw unreadable(it) }
            val y = tuple[1].trim().let { it.toDoubleOrNull() ?: throw unreadable(it) }
            // [coordinate] checks the range too, but cannot say that the grouping failed.
            if (y < -MAX_LATITUDE || y > MAX_LATITUDE || x < -MAX_LONGITUDE || x > MAX_LONGITUDE) {
                throw RouteParseException(
                    "a KML coordinate does not read as longitude, latitude: $x, $y is off the earth"
                )
            }
            coordinate(y, x)
        }
    }

    /**
     * A `.pln` `<WorldPosition>`, `N48° 49' 19.00",E002° 37' 22.00",+001500.00`, matched by
     * hemisphere letter and loosely on the degree sign, which is written several ways, or null
     * when the file writes none. Written, it needs one latitude and one longitude in degrees,
     * minutes and seconds, or it is refused. The altitude is ignored.
     */
    private fun worldPosition(text: String?): Coordinate? {
        val written = text?.trim().orEmpty()
        if (written.isEmpty()) return null
        val halves = DMS.findAll(written).toList()
        val lat = halves.singleOrNull { it.groupValues[1] == "N" || it.groupValues[1] == "S" }
        val lon = halves.singleOrNull { it.groupValues[1] == "E" || it.groupValues[1] == "W" }
        if (halves.size != 2 || lat == null || lon == null) throw unreadable(written)
        return coordinate(dms(lat), dms(lon))
    }

    private val DMS = Regex("([NSEW])\\s*(\\d+)[^0-9]+(\\d+)'\\s*([0-9.]+)\"")

    private val WHITESPACE = Regex("\\s+")

    /**
     * Placemarks in path order, or null unless the path has one vertex per placemark, each at the
     * same coordinate as a distinct placemark. Two placemarks at one place, as on a circuit that
     * passes a landmark twice, take two vertices and stay distinct.
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
     * Fold a free text label into the alphabet an identifier may use: a letter with marks becomes
     * the letter under them, through [FOLD], anything else but an ASCII letter or digit goes, and
     * the rest is upper case. `L'obélisque` becomes `LOBELISQUE`.
     *
     * SD-VFR's `.fpl` identifier is the first five characters of this fold, its `.pln` id the
     * first ten and its GPX and KML names the label itself, so truncating any of them to the
     * navigator's cap gives the same name.
     */
    private fun fold(text: String?): String {
        if (text == null) return ""
        return buildString {
            for (c in spellOut(text)) {
                when (c) {
                    in 'A'..'Z', in '0'..'9' -> append(c)
                    in 'a'..'z' -> append(c.uppercaseChar())
                    else -> FOLD[c]?.let { append(it) }
                }
            }
        }
    }

    /**
     * Spell out the letters a decomposition leaves whole, which [fold] would delete: `Cœuvres`
     * would fold to `CUVRES`. `ß` and `ẞ` become `ss` and `SS`, so `Großenhain` folds to
     * `GROSSENHAIN`. Which way SD-VFR spells these is unconfirmed, as no sample holds one, and this
     * follows the usual transliteration.
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
        'Þ' to "TH", 'þ' to "th", 'ẞ' to "SS", 'ß' to "ss", 'ı' to "i",
    )

    /**
     * An aerodrome identifier an exporter has decorated, four ICAO letters and up to two more:
     * SD-VFR's `.fpl` names a user waypoint with five characters of its label, plus a sequence
     * number en route, so Lognes (`LFPL`) is `LFPLL`. Inferred from that file alone, so applied
     * only to a `.fpl` user waypoint at an end of the route: a GPX's `Strip` would become `STRI`.
     */
    private val DECORATED_AIRPORT = Regex("^([A-Z]{4})[A-Z0-9]{0,2}$")

    /**
     * The aerodrome identifier a label starts with, `LFPL LOGNES EMERAINVILLE` or `KSFO`: a first
     * word of four capital letters as the file writes it, so that `Home` and `Parc Astérix` stay
     * points.
     */
    private fun icaoWord(label: String?): String? {
        val first = label?.trim()?.split(WHITESPACE)?.firstOrNull() ?: return null
        return first.takeIf { it.length == ICAO_AERODROME_LEN && it.all { c -> c in 'A'..'Z' } }
    }

    /**
     * The identifier a point starts from. An airport or database point keeps the one the file
     * states, as rewriting it would name another point. Otherwise the label is folded, and a
     * [Kind.USER] point loses SD-VFR's suffix, its own 0 based route position: `LOBEL1` at index
     * 1 is `LOBEL`.
     */
    private fun baseIdent(point: ImportedPoint, index: Int, device: Profile): String {
        if (point.kind == Kind.AIRPORT || point.kind == Kind.DATABASE) {
            point.stated?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        val folded = fold(point.label)
        if (point.kind == Kind.USER) {
            val suffix = index.toString()
            // Only after SD-VFR's five character stem, so that a user waypoint called V334 at
            // index 4 keeps its 4.
            if (folded.endsWith(suffix) && folded.length - suffix.length == SDVFR_STEM_LEN) {
                return folded.dropLast(suffix.length)
            }
        }
        return folded
    }

    /** Only a name this importer invented may be rewritten to fit. */
    private fun renameable(point: ImportedPoint) =
        point.kind == Kind.USER || point.kind == Kind.UNTYPED

    /**
     * Shorten names to [Profile.waypointNameLen], keeping them distinct across the route.
     *
     * The navigator's parser reads that many characters and stops inside a longer name, so the
     * `:F:` handler misses the comma before the position and flags the message malformed. A unique
     * truncation is kept, `LOBELISQUE` to `LOBEL`. Every member of a colliding group takes its 0
     * based route position instead, the one thing every format shares, so four abbeys become
     * `ABBA5`, `ABBA7`, `ABB10` and `ABB11`. Identifiers the file stated count as taken.
     */
    private fun fitNames(
        points: List<ImportedPoint>,
        bases: List<String>,
        reserved: Set<String>,
        device: Profile,
    ): List<String> {
        // A stated name goes out as itself or not at all, so it is measured as itself.
        val wanted = points.mapIndexed { i, point ->
            if (renameable(point)) bases[i].take(device.waypointNameLen) else bases[i]
        }
        // An unnamed point goes out as a bare position and collides with nothing.
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
                    yield(base.take((device.waypointNameLen - 2).coerceAtLeast(0)) + digits(n, 2))
                }
            }
            val fit = candidates.firstOrNull { it !in taken } ?: truncated
            taken += fit
            fit
        }
    }

    /**
     * Fold a run of points at one coordinate into the one that says the most: a `.pln` writes
     * each end twice, as an `Airport` and a `User` point. A circuit that passes a landmark twice
     * with something between keeps both.
     */
    private fun collapse(points: List<ImportedPoint>): List<ImportedPoint> {
        val out = mutableListOf<ImportedPoint>()
        for (point in points) {
            val last = out.lastOrNull()
            // The same coordinate, not merely the same wire position: the duplicate is a file
            // repeating itself, and two places can share a wire position.
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
     * An aerodrome at each end where the file gives one, and a point where it does not, as for an
     * unlicensed strip. [RouteParser.parse] reads the ends of the route box the same way.
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
        // Reserved as sent, `LFPL` and not the file's `LFPLL`, so that no en route point is
        // shortened onto one. The file's name for an aerodrome is not sent, so it takes nothing.
        val sent = bases.mapIndexed { i, base ->
            val aerodrome = (i == 0 && departure != null) || (i == bases.lastIndex && arrival != null)
            if (aerodrome) "" else base
        }
        val names = fitNames(points, sent, setOfNotNull(departure, arrival), device)

        val from = if (departure != null) 1 else 0
        val to = if (arrival != null) points.size - 1 else points.size
        val enroute: List<RouteElement> = (from until to).map { i ->
            element(points[i], names[i], device)
        }
        // The aerodrome's position rides on its `:F:` fix, settling a duplicated identifier.
        return ParsedRoute(
            departure?.let { Departure(it, at = points.first().at) },
            enroute,
            arrival?.let { Arrival(it, at = points.last().at) },
        )
    }

    /**
     * The aerodrome identifier for an end, or null when the file gives none: what a point typed
     * `AIRPORT` is called, else SD-VFR's decorated form (`LFPLL` to `LFPL`), else [icaoWord]. An
     * `AIRPORT` identifier over [Profile.airportNameLen] stays a fix, as only `:DA:`/`:AA:` have
     * that cap. A database point is an aerodrome only when its identifier is four letters, like
     * SkyDemon's `<sym>EBGB`, since the recovery rules would cut an intersection `MANAK` to `MANA`.
     */
    private fun airport(point: ImportedPoint, base: String, device: Profile): String? {
        if (base.isEmpty()) return null
        if (point.kind == Kind.AIRPORT) return base.takeIf { it.length <= device.airportNameLen }
        if (point.kind == Kind.DATABASE) {
            return base.takeIf { it.length == ICAO_AERODROME_LEN && it.all { c -> c in 'A'..'Z' } }
        }
        if (point.mayBeDecorated) {
            DECORATED_AIRPORT.matchEntire(base)?.let { return it.groupValues[1] }
        }
        return icaoWord(point.label)
    }

    /** An ICAO location indicator, which is four letters (Doc 7910). */
    private const val ICAO_AERODROME_LEN = 4

    /** How many characters of a label SD-VFR takes before it appends a sequence number. */
    private const val SDVFR_STEM_LEN = 5

    private fun element(point: ImportedPoint, name: String, device: Profile): RouteElement {
        if (name.isEmpty()) {
            return point.at ?: throw RouteParseException("a route point has neither a name nor a position")
        }
        // No point from a file is an airway, whatever its name looks like: every format here
        // describes places, and a reporting point called E9 has the shape of an airway.
        if (renameable(point) && point.at != null) {
            // A name the file invented, or left untyped, goes with its position, as the navigator
            // may know no such name. One that still does not fit falls back to the bare position.
            return runCatching {
                UserWaypoint(name, point.at.lat, point.at.lon)
                    .also { it.checkName(device.waypointNameLen) }
            }.getOrElse { point.at }
        }
        // A stated identifier is not ours to shorten, so one too long is refused.
        if (name.length > device.waypointNameLen) {
            throw RouteParseException(
                "\"$name\" is ${name.length} characters and the navigator reads " +
                    "${device.waypointNameLen}. It came from the file as an identifier, so " +
                    "shortening it would name a different waypoint",
                Problem.StatedIdentTooLong(name, name.length, device.waypointNameLen),
            )
        }
        if (name.any { it !in 'A'..'Z' && it !in '0'..'9' && it != '-' }) {
            throw RouteParseException(
                "\"$name\" is not an identifier the navigator can read",
                Problem.BadIdentifier(name),
            )
        }
        // A database point goes as a [Waypoint] with its position, as Garmin's own client sends
        // one: the navigator picks among duplicated identifiers by position, and a GTN is still
        // sent the name.
        return Waypoint(name, point.at)
    }

    /**
     * Parse, and say so in words when the file is not XML, since a parser's own message means
     * nothing to a pilot. A document type is refused for what it is: none of these formats has
     * one.
     */
    private fun parse(bytes: ByteArray): XmlElement = try {
        parseXml(bytes)
    } catch (e: XmlException) {
        if (e.doctype) {
            throw RouteParseException("this file declares a document type", Problem.UnrecognisedFile)
        }
        e.encoding?.let {
            throw RouteParseException(
                "this file is written in $it, an encoding this does not read. It has to be saved " +
                    "as UTF-8 first",
                Problem.UnreadableEncoding(it),
            )
        }
        throw RouteParseException(
            "this file is not readable as XML. A route has to be the file the planner exported, " +
                "not a screenshot of it or a copy pasted into a document",
            Problem.NotXml,
        )
    }

    /**
     * The elements named [name] by local name, the root among them, whether or not the file
     * bothered to declare a namespace.
     */
    private fun all(root: XmlElement, name: String): List<XmlElement> =
        if (root.localName == name) listOf(root) + root.descendants(name) else root.descendants(name)
}
