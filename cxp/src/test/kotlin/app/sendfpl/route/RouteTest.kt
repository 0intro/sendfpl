package app.sendfpl.route

import app.sendfpl.cxp.Airway
import app.sendfpl.cxp.Arrival
import app.sendfpl.cxp.Coordinate
import app.sendfpl.cxp.Departure
import app.sendfpl.cxp.FlightPlanException
import app.sendfpl.cxp.Problem
import app.sendfpl.cxp.Profile
import app.sendfpl.cxp.Profiles
import app.sendfpl.cxp.ROUTE_PREFIX
import app.sendfpl.cxp.SupportedElements
import app.sendfpl.cxp.UserWaypoint
import app.sendfpl.cxp.Waypoint
import app.sendfpl.cxp.decodeUpload
import app.sendfpl.cxp.encodeUpload
import app.sendfpl.cxp.formatLatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteParserTest {

    /**
     * The realistic VFR case the store screenshots show. One string exercises the airport, plain
     * fix and named user waypoint paths, and PONTC sits exactly on the GPS 175's five character
     * waypoint cap. A reference implementation pins the same string.
     */
    @Test
    fun `the VFR reference route renders as the reference implementation does`() {
        val text = "LFPL PONTC,N48478E002410 PMN MLN LFGO LFGK LFLA"
        val parsed = RouteParser.parse(text)

        assertEquals("LFPL", parsed.departure!!.airport)
        assertEquals("LFLA", parsed.arrival!!.airport)
        assertEquals(5, parsed.enroute.size)

        val rendered = parsed.render(Profiles.GPS175, null)
        assertEquals(
            "FPN/RI:F:LFPL:F:PONTC,N48478E002410:F:PMN:F:MLN:F:LFGO:F:LFGK:F:LFLA",
            rendered,
        )
        // Seven points, which is what the navigator shows, and well inside the hundred it holds.
        assertEquals(7, Regex(":F:").findAll(rendered).count())

        // A GTN names an imported user waypoint itself and locks one sent with a name, so PONTC
        // goes to it as its position. Everything else is a name its database knows.
        assertEquals(
            "FPN/RI:F:LFPL:F:N48478E002410:F:PMN:F:MLN:F:LFGO:F:LFGK:F:LFLA",
            parsed.render(Profiles.GTN, null),
        )
    }

    @Test
    fun `first and last identifiers are the airports`() {
        val r = RouteParser.parse("KSFO SAC V334 LIN KLAS")
        assertEquals("KSFO", r.departure!!.airport)
        assertEquals("KLAS", r.arrival!!.airport)
        assertEquals(3, r.enroute.size)
    }

    @Test
    fun `tokens shaped like an airway become airways`() {
        val r = RouteParser.parse("KSFO SAC V334 LIN KLAS")
        assertTrue(r.enroute[0] is Waypoint)
        assertTrue(r.enroute[1] is Airway)
        assertTrue(r.enroute[2] is Waypoint)
        assertEquals(
            ROUTE_PREFIX + ":F:KSFO:F:SAC.V334.LIN:F:KLAS",
            r.render(Profiles.GPS175),
        )
    }

    @Test
    fun `a bang prefix forces an airway the heuristic would miss`() {
        val r = RouteParser.parse("EGLL !UL9 TNT EGPH")
        assertTrue(r.enroute[0] is Airway)
        assertEquals(ROUTE_PREFIX + ":F:EGLL.UL9.TNT:F:EGPH", r.render(Profiles.GPS175))
    }

    /**
     * The other half of the `!` escape hatch: a one or two letter name followed by a digit, like
     * `E9`, is an ordinary VFR reporting point in several countries.
     */
    @Test
    fun `an equals prefix forces a waypoint the heuristic would call an airway`() {
        val forced = RouteParser.parse("LFPT =E9 LFPN")
        assertTrue(forced.enroute[0] is Waypoint)
        assertEquals("E9", (forced.enroute[0] as Waypoint).ident)
        assertEquals(ROUTE_PREFIX + ":F:LFPT:F:E9:F:LFPN", forced.render(Profiles.GPS175))

        // The same token without the prefix still goes out as an airway, so the prefix is what
        // changed the answer rather than the heuristic having been loosened.
        val guessed = RouteParser.parse("LFPT E9 LFPN")
        assertTrue(guessed.enroute[0] is Airway)
        assertEquals(ROUTE_PREFIX + ":F:LFPT.E9.LFPN", guessed.render(Profiles.GPS175))
    }

    /**
     * The prefix suppresses the heuristic; it does not assert a type. Asserting would turn
     * `=N48.82/E2.62` into a waypoint named `N48.82/E2.62`, a position nothing can read.
     */
    @Test
    fun `an equals prefix does not turn a position into a name`() {
        val coordinate = RouteParser.parse("LFPT =N48.82/E2.62 LFPN")
        assertTrue(coordinate.enroute[0] is Coordinate)
        assertEquals(
            ROUTE_PREFIX + ":F:LFPT:F:N48492E002372:F:LFPN",
            coordinate.render(Profiles.GPS175),
        )

        // And the prefix does not break the fold either: the name may not run on to the left, and
        // `=` is not a character that boundary test counts as running on.
        val named = RouteParser.parse("LFPT =LOBEL,N48475E002522 LFPN")
        assertTrue(named.enroute[0] is UserWaypoint)
        assertEquals(
            ROUTE_PREFIX + ":F:LFPT:F:LOBEL,N48475E002522:F:LFPN",
            named.render(Profiles.GPS175),
        )
    }

    /**
     * A prefix at either end takes that token out of the aerodrome test, so it becomes an en route
     * fix. The wire result is the same, because an airport with no procedure is a `:F:` anyway.
     */
    @Test
    fun `a prefix at an end makes it a point, and the wire form is unchanged`() {
        val plain = RouteParser.parse("LFPT SAC LFPN")
        val forced = RouteParser.parse("=LFPT SAC LFPN")
        assertEquals("LFPT", plain.departure!!.airport)
        assertEquals(null, forced.departure)
        assertEquals(plain.render(Profiles.GPS175), forced.render(Profiles.GPS175))
    }

    @Test
    fun `commas and mixed case work`() {
        assertEquals(
            RouteParser.parse("KSFO SAC KLAS").render(Profiles.GPS175),
            RouteParser.parse("ksfo, sac , klas").render(Profiles.GPS175),
        )
    }

    @Test
    fun `a route needs at least two identifiers`() {
        assertTrue(runCatching { RouteParser.parse("KSFO") }.exceptionOrNull() is RouteParseException)
        assertTrue(runCatching { RouteParser.parse("") }.exceptionOrNull() is RouteParseException)
    }

    @Test
    fun `the airway heuristic covers the usual shapes`() {
        listOf("V334", "J50", "Q13", "UL9", "A1").forEach {
            assertTrue("$it should look like an airway", RouteParser.looksLikeAirway(it))
        }
        listOf("SAC", "LIN", "KSFO", "BTY").forEach {
            assertTrue("$it should not", !RouteParser.looksLikeAirway(it))
        }
    }

    @Test
    fun `identifiers round trip for display`() {
        assertEquals(
            listOf("KSFO", "SAC", "V334", "LIN", "KLAS"),
            RouteParser.parse("KSFO SAC V334 LIN KLAS").identifiers,
        )
    }

    /**
     * What the box shows has to read back as what it shows. A file or a `.gfp` can hand over an
     * element the box's own guess would read differently, and only those are marked.
     */
    @Test
    fun `identifiers mark only what the box would read differently`() {
        val route = ParsedRoute(
            Departure("LFPT"),
            listOf(
                Waypoint("SAC"), Waypoint("E9"), Airway("V334"), Waypoint("LIN"),
                Airway("C1318"), Waypoint("EGLL"),
            ),
            Arrival("LFPN"),
        )
        assertEquals(
            listOf("LFPT", "SAC", "=E9", "V334", "LIN", "!C1318", "EGLL", "LFPN"),
            route.identifiers,
        )
        val reparsed = RouteParser.parse(route.identifiers.joinToString(" "))
        assertEquals(route.enroute, reparsed.enroute)
        assertEquals(route.render(Profiles.GPS175), reparsed.render(Profiles.GPS175))

        // An airway first in the box would be read as an aerodrome, so it is marked too. A route
        // like that does not render, and the box still says what it is.
        val leading = ParsedRoute(null, listOf(Airway("V334"), Waypoint("LIN")), Arrival("KLAS"))
        assertEquals(listOf("!V334", "LIN", "KLAS"), leading.identifiers)
        assertTrue(RouteParser.parse(leading.identifiers.joinToString(" ")).enroute[0] is Airway)

        // A waypoint forced by hand comes back marked the way it was typed.
        assertEquals(listOf("LFPT", "=E9", "LFPN"), RouteParser.parse("LFPT =E9 LFPN").identifiers)
    }

    @Test
    fun `a Garmin Pilot coordinate survives the split on commas`() {
        // Split on its commas, it would be three pieces making a plausible but wrong route.
        val r = RouteParser.parse("KSFO N48,8200/E2,62000 KLAS")
        assertEquals(1, r.enroute.size)
        assertEquals(Coordinate(48.82, 2.62), r.enroute[0])
        assertEquals(ROUTE_PREFIX + ":F:KSFO:F:N48492E002372:F:KLAS", r.render(Profiles.GPS175))
    }

    @Test
    fun `every accepted coordinate notation gives the same route`() {
        listOf(
            "KSFO N48.82/E2.62 KLAS",
            "KSFO N48,8200/E2,62000 KLAS",
            "KSFO 48.82N/2.62E KLAS",
            "KSFO N48492E002372 KLAS",
            "KSFO N48.82 E2.62 KLAS",
        ).forEach {
            assertEquals(it, ROUTE_PREFIX + ":F:KSFO:F:N48492E002372:F:KLAS", RouteParser.parse(it).render(Profiles.GPS175))
        }
    }

    @Test
    fun `a coordinate echoes back in the wire form and parses back to itself`() {
        val once = RouteParser.parse("KSFO N48,8200/E2,62000 KLAS")
        assertEquals(listOf("KSFO", "N48492E002372", "KLAS"), once.identifiers)
        // What the text field is repopulated with after a file import must mean the same thing.
        val twice = RouteParser.parse(once.identifiers.joinToString(" "))
        assertEquals(once.render(Profiles.GPS175), twice.render(Profiles.GPS175))
    }

    @Test
    fun `coordinates mix with waypoints and airways`() {
        val r = RouteParser.parse("KSFO SAC N48.82/E2.62 V334 KLAS")
        assertTrue(r.enroute[0] is Waypoint)
        assertTrue(r.enroute[1] is Coordinate)
        assertTrue(r.enroute[2] is Airway)
        assertEquals(ROUTE_PREFIX + ":F:KSFO:F:SAC:F:N48492E002372.V334.KLAS", r.render(Profiles.GPS175))
    }

    @Test
    fun `a malformed coordinate is reported rather than sent as a waypoint`() {
        val e = runCatching { RouteParser.parse("KSFO N4849200E002372 KLAS") }.exceptionOrNull()
        assertTrue(e is RouteParseException)
    }
}

class RouteImporterTest {

    private val fpl = """
        <?xml version="1.0" encoding="utf-8"?>
        <flight-plan xmlns="http://www8.garmin.com/xmlschemas/FlightPlan/v1">
          <waypoint-table>
            <waypoint><identifier>KSFO</identifier><type>AIRPORT</type></waypoint>
            <waypoint><identifier>SAC</identifier><type>VOR</type></waypoint>
            <waypoint><identifier>KLAS</identifier><type>AIRPORT</type></waypoint>
          </waypoint-table>
          <route>
            <route-name>KSFO KLAS</route-name>
            <route-point>
              <waypoint-identifier>KSFO</waypoint-identifier>
              <waypoint-type>AIRPORT</waypoint-type>
            </route-point>
            <route-point>
              <waypoint-identifier>SAC</waypoint-identifier>
              <waypoint-type>VOR</waypoint-type>
            </route-point>
            <route-point>
              <waypoint-identifier>KLAS</waypoint-identifier>
              <waypoint-type>AIRPORT</waypoint-type>
            </route-point>
          </route>
        </flight-plan>
    """.trimIndent()

    private val gpx = """
        <?xml version="1.0" encoding="UTF-8"?>
        <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
          <rte>
            <name>KSFO KLAS</name>
            <rtept lat="37.6" lon="-122.4" name="KSFO"/>
            <rtept lat="38.4" lon="-121.5" name="V334"/>
            <rtept lat="36.1" lon="-115.2" name="KLAS"/>
          </rte>
        </gpx>
    """.trimIndent()

    /**
     * What the [gpx] and [kml] fixtures import as. `V334` in them is a point with a position, so it
     * is a named user waypoint like any other, whatever its name looks like. The two aerodromes
     * travel with the positions the files give them, which the box keeps rather than shows: see
     * [v334Wire].
     */
    private val v334Imported = listOf("KSFO", "V334,N38240W121300", "KLAS")

    /** What the same fixtures send a GPS 175. */
    private val v334Wire = ROUTE_PREFIX + ":F:KSFO,N37360W122240:F:V334,N38240W121300:F:KLAS,N36060W115120"

    @Test
    fun `a Garmin fpl route becomes a ParsedRoute`() {
        val r = RouteImporter.importFpl(fpl.byteInputStream())
        assertEquals("KSFO", r.departure!!.airport)
        assertEquals("KLAS", r.arrival!!.airport)
        assertEquals(listOf("KSFO", "SAC", "KLAS"), r.identifiers)
        assertEquals(ROUTE_PREFIX + ":F:KSFO:F:SAC:F:KLAS", r.render(Profiles.GPS175))
    }

    /**
     * A `.fpl` as SkyDemon 3.16.x writes one: `<identifier />` for every `INT`, and the alternate
     * appended after the destination. Verbatim from one export, trimmed to six points.
     */
    private val skyDemon = """
        <?xml version="1.0" encoding="utf-8"?>
        <flight-plan xmlns="http://www8.garmin.com/xmlschemas/FlightPlan/v1">
          <waypoint-table>
            <waypoint><identifier>EDFC</identifier><type>AIRPORT</type>
              <lat>49.938862</lat><lon>9.062555</lon></waypoint>
            <waypoint><identifier /><type>INT</type>
              <lat>49.976944</lat><lon>9.052222</lon></waypoint>
            <waypoint><identifier>MTR</identifier><type>VOR</type>
              <lat>50.276279</lat><lon>8.848625</lon></waypoint>
            <waypoint><identifier /><type>INT</type>
              <lat>50.635563</lat><lon>8.819371</lon></waypoint>
            <waypoint><identifier>EDDW</identifier><type>AIRPORT</type>
              <lat>53.047401</lat><lon>8.786750</lon></waypoint>
            <waypoint><identifier>EDWH</identifier><type>AIRPORT</type>
              <lat>53.069069</lat><lon>8.313486</lon></waypoint>
          </waypoint-table>
          <route>
            <route-name>EDFC EDDW</route-name>
            <route-point><waypoint-identifier>EDFC</waypoint-identifier>
              <waypoint-type>AIRPORT</waypoint-type></route-point>
            <route-point><waypoint-identifier />
              <waypoint-type>INT</waypoint-type></route-point>
            <route-point><waypoint-identifier>MTR</waypoint-identifier>
              <waypoint-type>VOR</waypoint-type></route-point>
            <route-point><waypoint-identifier />
              <waypoint-type>INT</waypoint-type></route-point>
            <route-point><waypoint-identifier>EDDW</waypoint-identifier>
              <waypoint-type>AIRPORT</waypoint-type></route-point>
            <route-point><waypoint-identifier>EDWH</waypoint-identifier>
              <waypoint-type>AIRPORT</waypoint-type></route-point>
          </route>
        </flight-plan>
    """.trimIndent()

    @Test
    fun `a route point with no identifier keeps its position instead of vanishing`() {
        // Without the nameless points, what is left is still a well shaped route that goes
        // somewhere else. A stated identifier travels with the position the file gives it, the
        // aerodromes included.
        val r = RouteImporter.importFpl(skyDemon.byteInputStream())
        assertEquals(
            listOf(
                "EDFC",
                formatLatLon(49.976944, 9.052222),
                "MTR",
                formatLatLon(50.635563, 8.819371),
                "EDDW",
            ),
            r.identifiers,
        )
        assertTrue(r.enroute[0] is Coordinate)
        assertTrue(r.enroute[2] is Coordinate)
        // The box shows the names, and the positions go out with them.
        assertEquals(
            ROUTE_PREFIX + ":F:EDFC," + formatLatLon(49.938862, 9.062555) +
                ":F:" + formatLatLon(49.976944, 9.052222) +
                ":F:MTR," + formatLatLon(50.276279, 8.848625) +
                ":F:" + formatLatLon(50.635563, 8.819371) +
                ":F:EDDW," + formatLatLon(53.047401, 8.786750),
            r.render(Profiles.GPS175),
        )
    }

    @Test
    fun `the alternate appended after the destination is not the destination`() {
        // EDWH is the alternate. The route's own name says the flight ends at EDDW.
        val r = RouteImporter.importFpl(skyDemon.byteInputStream())
        assertEquals("EDFC", r.departure!!.airport)
        assertEquals("EDDW", r.arrival!!.airport)
    }

    @Test
    fun `a waypoint table that does not line up is refused, not read positionally`() {
        // One entry short, so index 4 of the route is index 4 of something else. Handing the
        // nameless point those coordinates would put it 250 km away and say nothing.
        val short = skyDemon.replace(
            """
                <waypoint><identifier>MTR</identifier><type>VOR</type>
                  <lat>50.276279</lat><lon>8.848625</lon></waypoint>
            """.trimIndent().prependIndent("    "),
            "",
        )
        assertEquals(5, Regex("<waypoint>").findAll(short).count())
        val e = runCatching { RouteImporter.importFpl(short.byteInputStream()) }.exceptionOrNull()
        assertTrue("expected a refusal, got $e", e is RouteParseException)
        assertTrue("$e", e!!.message!!.contains("does not line up"))
    }

    @Test
    fun `a route name that agrees with its own last point leaves the route alone`() {
        // The narrowing that keeps this rule off every other exporter's files: it can only fire
        // where a file contradicts itself.
        val agreeing = skyDemon
            .replace("<route-name>EDFC EDDW</route-name>", "<route-name>Day 2</route-name>")
        val r = RouteImporter.importFpl(agreeing.byteInputStream())
        assertEquals("EDWH", r.arrival!!.airport)
        assertEquals(6, r.identifiers.size)
    }

    /**
     * A `.fpl` from EBGB to EBAW through two reporting points called D, one table entry per route
     * point as SkyDemon writes it, with [between] a route point and its entry between the two Ds.
     */
    private fun twoDs(between: Pair<String, String>? = null) = """
        <?xml version="1.0" encoding="utf-8"?>
        <flight-plan xmlns="http://www8.garmin.com/xmlschemas/FlightPlan/v1">
          <waypoint-table>
            <waypoint><identifier>EBGB</identifier><type>AIRPORT</type><country-code>EB</country-code>
              <lat>50.948610</lat><lon>4.391944</lon></waypoint>
            <waypoint><identifier>D</identifier><type>INT</type><country-code>EB</country-code>
              <lat>51.286110</lat><lon>4.233056</lon></waypoint>
            ${between?.second.orEmpty()}
            <waypoint><identifier>D</identifier><type>INT</type><country-code>EB</country-code>
              <lat>50.900000</lat><lon>4.500000</lon></waypoint>
            <waypoint><identifier>EBAW</identifier><type>AIRPORT</type><country-code>EB</country-code>
              <lat>51.189444</lat><lon>4.460278</lon></waypoint>
          </waypoint-table>
          <route>
            <route-point><waypoint-identifier>EBGB</waypoint-identifier>
              <waypoint-type>AIRPORT</waypoint-type><waypoint-country-code>EB</waypoint-country-code></route-point>
            <route-point><waypoint-identifier>D</waypoint-identifier>
              <waypoint-type>INT</waypoint-type><waypoint-country-code>EB</waypoint-country-code></route-point>
            ${between?.first.orEmpty()}
            <route-point><waypoint-identifier>D</waypoint-identifier>
              <waypoint-type>INT</waypoint-type><waypoint-country-code>EB</waypoint-country-code></route-point>
            <route-point><waypoint-identifier>EBAW</waypoint-identifier>
              <waypoint-type>AIRPORT</waypoint-type><waypoint-country-code>EB</waypoint-country-code></route-point>
          </route>
        </flight-plan>
    """.trimIndent()

    @Test
    fun `two points of one identifier keep their own positions`() {
        // The table lines up with the route, so each D is the entry at its own index. By name
        // alone both would take one position, and two in a row would fold into one point.
        val r = RouteImporter.importFpl(twoDs().byteInputStream())
        val wire = ROUTE_PREFIX + ":F:EBGB,N50569E004235:F:D,N51172E004140:F:D,N50540E004300" +
            ":F:EBAW,N51114E004276"
        assertEquals(wire, r.render(Profiles.GPS175))
        assertEquals(listOf("EBGB", "D,N51172E004140", "D,N50540E004300", "EBAW"), r.identifiers)
        assertEquals(wire, RouteParser.parse(r.identifiers.joinToString(" "), r.placed).render(Profiles.GPS175))

        val nik = "<route-point><waypoint-identifier>NIK</waypoint-identifier><waypoint-type>VOR" +
            "</waypoint-type><waypoint-country-code>EB</waypoint-country-code></route-point>" to
            "<waypoint><identifier>NIK</identifier><type>VOR</type><country-code>EB</country-code>" +
            "<lat>51.165080</lat><lon>4.183944</lon></waypoint>"
        assertEquals(
            ROUTE_PREFIX + ":F:EBGB,N50569E004235:F:D,N51172E004140:F:NIK,N51099E004110" +
                ":F:D,N50540E004300:F:EBAW,N51114E004276",
            RouteImporter.importFpl(twoDs(nik).byteInputStream()).render(Profiles.GPS175),
        )
    }

    @Test
    fun `a table that does not line up is read by identifier, type and country code`() {
        // Garmin's schema keys the table on all three, and a route point refers to all three. The
        // table also lists the alternate, EBBR, which is not flown, so it is not in route order.
        fun fpl(country: String) = """
            <?xml version="1.0" encoding="utf-8"?>
            <flight-plan xmlns="http://www8.garmin.com/xmlschemas/FlightPlan/v1">
              <waypoint-table>
                <waypoint><identifier>EBGB</identifier><type>AIRPORT</type><country-code>EB</country-code><lat>50.948610</lat><lon>4.391944</lon></waypoint>
                <waypoint><identifier>D</identifier><type>INT</type><country-code>EB</country-code><lat>51.286110</lat><lon>4.233056</lon></waypoint>
                <waypoint><identifier>D</identifier><type>INT</type><country-code>$country</country-code><lat>51.450000</lat><lon>5.370000</lon></waypoint>
                <waypoint><identifier>EBAW</identifier><type>AIRPORT</type><country-code>EB</country-code><lat>51.189444</lat><lon>4.460278</lon></waypoint>
                <waypoint><identifier>EBBR</identifier><type>AIRPORT</type><country-code>EB</country-code><lat>50.901389</lat><lon>4.484444</lon></waypoint>
              </waypoint-table>
              <route>
                <route-point><waypoint-identifier>EBGB</waypoint-identifier><waypoint-type>AIRPORT</waypoint-type><waypoint-country-code>EB</waypoint-country-code></route-point>
                <route-point><waypoint-identifier>D</waypoint-identifier><waypoint-type>INT</waypoint-type><waypoint-country-code>EB</waypoint-country-code></route-point>
                <route-point><waypoint-identifier>EBAW</waypoint-identifier><waypoint-type>AIRPORT</waypoint-type><waypoint-country-code>EB</waypoint-country-code></route-point>
              </route>
            </flight-plan>
        """.trimIndent()
        assertEquals(
            ROUTE_PREFIX + ":F:EBGB,N50569E004235:F:D,N51172E004140:F:EBAW,N51114E004276",
            RouteImporter.importFpl(fpl("EH").byteInputStream()).render(Profiles.GPS175),
        )

        // Two entries under one key at two places, in a table that does not line up, cannot say
        // which one the route means, so the file is refused rather than given either position.
        val e = runCatching { RouteImporter.importFpl(fpl("EB").byteInputStream()) }.exceptionOrNull()
        assertTrue("expected a refusal, got $e", e is RouteParseException)
        assertTrue("$e", e!!.message!!.contains("\"D\""))
    }

    @Test
    fun `a GPX route becomes a ParsedRoute, every point of it a point`() {
        val r = RouteImporter.importGpx(gpx.byteInputStream())
        assertEquals(v334Imported, r.identifiers)
        assertTrue(r.enroute[0] is UserWaypoint)
        assertEquals(v334Wire, r.render(Profiles.GPS175))
        // A GTN is sent the user waypoint as its position, and the aerodromes as they are.
        assertEquals(
            ROUTE_PREFIX + ":F:KSFO,N37360W122240:F:N38240W121300:F:KLAS,N36060W115120",
            r.render(Profiles.GTN),
        )
    }

    @Test
    fun `the format is sniffed when the file name does not say`() {
        assertEquals(
            listOf("KSFO", "SAC", "KLAS"),
            RouteImporter.import(null, fpl.toByteArray()).identifiers,
        )
        assertEquals(v334Imported, RouteImporter.import("route.bin", gpx.toByteArray()).identifiers)
    }

    @Test
    fun `an unrecognised file is refused`() {
        val e = runCatching { RouteImporter.import("x.txt", "hello".toByteArray()) }.exceptionOrNull()
        assertTrue(e is RouteParseException)
    }

    @Test
    fun `a nameless GPX point becomes a coordinate instead of vanishing`() {
        // Dropping an unnamed <rtept> would import a route of three points as two, silently.
        val nameless = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
              <rte>
                <rtept lat="37.6189" lon="-122.3750" name="KSFO"/>
                <rtept lat="48.82" lon="2.62"/>
                <rtept lat="36.0800" lon="-115.1522" name="KLAS"/>
              </rte>
            </gpx>
        """.trimIndent()
        val r = RouteImporter.importGpx(nameless.byteInputStream())
        assertEquals(1, r.enroute.size)
        assertEquals(Coordinate(48.82, 2.62), r.enroute[0])
        assertEquals(
            ROUTE_PREFIX + ":F:KSFO,N37371W122225:F:N48492E002372:F:KLAS,N36048W115091",
            r.render(Profiles.GPS175),
        )
    }

    @Test
    fun `a named GPX point carries its position, whatever its name looks like`() {
        // GPX has no type, so it cannot say whether a name is one a database knows. Sending the
        // position with it costs nothing and resolves whether or not the navigator has heard of
        // the name, which is what Garmin Pilot itself does with every point it sends.
        // A name shaped like a route designator is still a name: V334 here is a place with a
        // position, and an airway has none.
        val r = RouteImporter.importGpx(gpx.byteInputStream())
        assertEquals(v334Imported, r.identifiers)
        assertTrue(r.enroute[0] is UserWaypoint)

        val named = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
              <rte>
                <rtept lat="37.6" lon="-122.4"><name>KSFO</name></rtept>
                <rtept lat="48.791600627042" lon="2.8691714914193"><name>L'obélisque</name></rtept>
                <rtept lat="36.1" lon="-115.2"><name>KLAS</name></rtept>
              </rte>
            </gpx>
        """.trimIndent()
        val f = RouteImporter.importGpx(named.byteInputStream())
        // Accents fold away, everything that is not a letter or a digit goes, and what is left is
        // cut to the navigator's five characters.
        assertEquals(listOf("KSFO", "LOBEL,N48475E002522", "KLAS"), f.identifiers)
        assertTrue(f.enroute[0] is UserWaypoint)
        assertEquals(
            ROUTE_PREFIX + ":F:KSFO,N37360W122240:F:LOBEL,N48475E002522:F:KLAS,N36060W115120",
            f.render(Profiles.GPS175),
        )
    }

    @Test
    fun `a label at an end is an aerodrome only when the file writes one`() {
        fun from(label: String) = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1"><rte>
              <rtept lat="48.5" lon="2.5"><name>$label</name></rtept>
              <rtept lat="48.69416" lon="2.72049"><name>PMN</name></rtept>
              <rtept lat="48.83749" lon="3.01444"><name>LFPK Coulommiers</name></rtept>
            </rte></gpx>
        """.trimIndent().toByteArray()

        // Cut to four letters these would be aerodromes nobody named, a misnamed point on a
        // GPS 175 and a locked one on a GTN. Each is the pilot's own point at its position.
        for ((label, name) in listOf(
            "Strip" to "STRIP", "Home" to "HOME", "Lille" to "LILLE",
            "Parc Asterix" to "PARCA", "Lfpl Lognes" to "LFPLL",
            // The arrival goes as LFPK, so the file's name for it takes nothing from this one.
            "Lfpk Coulommiers" to "LFPKC",
        )) {
            val r = RouteImporter.import("r.gpx", from(label), Profiles.GTN)
            assertNull(label, r.departure)
            assertEquals(label, "$name,N48300E002300", r.identifiers.first())
            assertEquals(
                label,
                ROUTE_PREFIX + ":F:N48300E002300:F:N48416E002432:F:LFPK,N48502E003009",
                r.render(Profiles.GTN),
            )
        }
        // An identifier written as one is still read as one, alone or before a name.
        for (label in listOf("LFPL", "LFPL LOGNES EMERAINVILLE")) {
            assertEquals(label, "LFPL", RouteImporter.import("r.gpx", from(label)).departure?.airport)
        }
    }

    private val kml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <kml xmlns="http://www.opengis.net/kml/2.2">
          <Document>
            <Placemark><name>Navigation</name><LineString><coordinates>-122.4,37.6,0,-121.5,38.4,0,-115.2,36.1,0</coordinates></LineString></Placemark>
            <Folder>
              <name>Points</name>
              <Placemark><name>KSFO</name><LookAt><longitude>0</longitude><latitude>0</latitude></LookAt><Point><coordinates>-122.4,37.6,0,</coordinates></Point></Placemark>
              <Placemark><name>V334</name><LookAt><longitude>0</longitude><latitude>0</latitude></LookAt><Point><coordinates>-121.5,38.4,0,</coordinates></Point></Placemark>
              <Placemark><name>KLAS</name><LookAt><longitude>0</longitude><latitude>0</latitude></LookAt><Point><coordinates>-115.2,36.1,0,</coordinates></Point></Placemark>
            </Folder>
          </Document>
        </kml>
    """.trimIndent()

    @Test
    fun `a KML separated with commas and nothing else still parses`() {
        // SD-VFR writes every Point with a trailing comma and every LineString as one run of
        // comma separated numbers, where OGC KML separates the tuples with whitespace. A
        // conformant reader sees that path as a single tuple of nine fields.
        val r = RouteImporter.importKml(kml.byteInputStream())
        assertEquals(v334Imported, r.identifiers)
        assertTrue(r.enroute[0] is UserWaypoint)
        // A LookAt also holds a longitude and a latitude, and reading one would put the route
        // at the null island.
        assertEquals(
            ROUTE_PREFIX + ":F:KSFO,N37360W122240:F:V334,N38240W121300:F:KLAS,N36060W115120",
            r.render(Profiles.GPS175),
        )
    }

    @Test
    fun `a KML written the way the spec describes parses too`() {
        val conformant = kml
            .replace("-122.4,37.6,0,-121.5,38.4,0,-115.2,36.1,0", "-122.4,37.6 -121.5,38.4 -115.2,36.1")
            .replace(",0,</coordinates>", "</coordinates>")
        val r = RouteImporter.importKml(conformant.byteInputStream())
        assertEquals(v334Imported, r.identifiers)
    }

    @Test
    fun `a KML point written with a space after each comma is still its position`() {
        // Not KML, which allows no space inside a tuple, but a point holds one tuple, so the
        // spaces cannot be read as anything else.
        val spaced = kml.replace("<coordinates>-121.5,38.4,0,</coordinates>", "<coordinates>-121.5, 38.4, 0</coordinates>")
        assertTrue(spaced != kml)
        assertEquals(v334Wire, RouteImporter.importKml(spaced.byteInputStream()).render(Profiles.GPS175))
    }

    @Test
    fun `a KML path decides the order when the placemarks do not agree with it`() {
        // KML has no route concept, so a folder of placemarks is a bag and document order is a
        // convention. An explicit path is not, so when the two describe the same places it wins.
        val shuffled = kml
            .replace(Regex("<Placemark><name>V334.*?</Placemark>"), "")
            .replace("<name>Points</name>", "<name>Points</name>" +
                "<Placemark><name>V334</name><Point><coordinates>-121.5,38.4,0,</coordinates></Point></Placemark>")
        val r = RouteImporter.importKml(shuffled.byteInputStream())
        assertEquals(v334Imported, r.identifiers)

        // A path that visits somewhere the placemarks do not is not a correspondence, so
        // document order stands rather than a partial match being guessed at. V334 leads, as the
        // point it is, and KSFO is left in the middle, where a name KML never claimed was a
        // database one travels with the position the file gave it.
        val disagreeing = shuffled.replace("-121.5,38.4,0,-115.2", "-99.9,11.1,0,-115.2")
        assertEquals(
            listOf("V334,N38240W121300", "KSFO,N37360W122240", "KLAS"),
            RouteImporter.importKml(disagreeing.byteInputStream()).identifiers,
        )
    }

    private val pln = """
        <?xml version="1.0" encoding="UTF-8"?>
        <SimBase.Document Type="AceXML" version="1,0">
          <Descr>AceXML Document</Descr>
          <FlightPlan.FlightPlan>
            <Title>KSFO to KLAS</Title>
            <FPType>IFR</FPType>
            <DepartureID>KSFO</DepartureID>
            <ATCWaypoint id="KSFO">
              <ATCWaypointType>Airport</ATCWaypointType>
              <WorldPosition>N37° 37' 8.40",W122° 22' 30.00",+000000.00</WorldPosition>
              <ICAO><ICAOIdent>KSFO</ICAOIdent></ICAO>
            </ATCWaypoint>
            <ATCWaypoint id="SAC">
              <ATCWaypointType>VOR</ATCWaypointType>
              <WorldPosition>N38° 26' 36.00",W121° 33' 6.00",+010000.00</WorldPosition>
              <ICAO><ICAOIdent>SAC</ICAOIdent></ICAO>
            </ATCWaypoint>
            <ATCWaypoint id="LOBELISQUE">
              <ATCWaypointType>User</ATCWaypointType>
              <WorldPosition>N48° 47' 29.76",E002° 52' 9.02",+002000.00</WorldPosition>
            </ATCWaypoint>
            <ATCWaypoint id="KLAS">
              <ATCWaypointType>Airport</ATCWaypointType>
              <WorldPosition>N36° 4' 48.00",W115° 9' 12.00",+000000.00</WorldPosition>
              <ICAO><ICAOIdent>KLAS</ICAOIdent></ICAO>
            </ATCWaypoint>
          </FlightPlan.FlightPlan>
        </SimBase.Document>
    """.trimIndent()

    @Test
    fun `a pln says what each point is, and its positions are degrees minutes and seconds`() {
        val r = RouteImporter.import("route.pln", pln.toByteArray())
        // A point the file typed VOR keeps the identifier it states, and travels with the position
        // the file gives it, as every stated identifier and both aerodromes do: an identifier can
        // be duplicated, and the position is what the navigator chooses by. A point typed User is
        // the exporter's own invention, a name with its place.
        assertEquals(listOf("KSFO", "SAC", "LOBEL,N48475E002522"), r.identifiers.dropLast(1))
        assertEquals("KLAS", r.arrival!!.airport)
        val sac = r.enroute[0] as Waypoint
        assertEquals("SAC", sac.ident)
        assertEquals("N38266W121331", sac.at!!.ident)
        assertTrue(r.enroute[1] is UserWaypoint)
        assertEquals(
            ROUTE_PREFIX + ":F:KSFO,N37371W122225:F:SAC,N38266W121331:F:LOBEL,N48475E002522" +
                ":F:KLAS,N36048W115092",
            r.render(Profiles.GPS175),
        )
        // On a GTN the VOR keeps its name, being a database point, and LOBEL goes as a position.
        assertEquals(
            ROUTE_PREFIX + ":F:KSFO,N37371W122225:F:SAC,N38266W121331:F:N48475E002522" +
                ":F:KLAS,N36048W115092",
            r.render(Profiles.GTN),
        )
    }

    @Test
    fun `a pln repeating its airport as a user waypoint is one point, not two`() {
        // What SD-VFR writes: the departure as an Airport point and again as a User point at the
        // same coordinates. The two are the same place at the resolution the navigator is sent,
        // so they are one point, and the one that names an airport is the one worth keeping.
        val doubled = pln.replace(
            "<ATCWaypoint id=\"SAC\">",
            "<ATCWaypoint id=\"KSFOSANFRA\">" +
                "<ATCWaypointType>User</ATCWaypointType>" +
                "<WorldPosition>N37° 37' 8.40\",W122° 22' 30.00\",+000000.00</WorldPosition>" +
                "</ATCWaypoint><ATCWaypoint id=\"SAC\">",
        )
        val r = RouteImporter.import("route.pln", doubled.toByteArray())
        assertEquals("KSFO", r.departure!!.airport)
        assertEquals(2, r.enroute.size)
        assertEquals(
            ROUTE_PREFIX + ":F:KSFO,N37371W122225:F:SAC,N38266W121331:F:LOBEL,N48475E002522" +
                ":F:KLAS,N36048W115092",
            r.render(Profiles.GPS175),
        )
    }

    @Test
    fun `the new formats are recognised by extension and by their root element`() {
        assertEquals(v334Imported, RouteImporter.import("x.kml", kml.toByteArray()).identifiers)
        assertEquals(v334Imported, RouteImporter.import("route.bin", kml.toByteArray()).identifiers)
        assertEquals("KSFO", RouteImporter.import(null, pln.toByteArray()).departure!!.airport)
    }

    @Test
    fun `a zip is named as one rather than failing as broken XML`() {
        val e = runCatching {
            RouteImporter.import("route.kmz", byteArrayOf(0x50, 0x4b, 0x03, 0x04, 0, 0))
        }.exceptionOrNull()
        assertTrue(e is RouteParseException)
        assertTrue(e!!.message!!.contains("zip"))
    }

    /**
     * SD-VFR's export, abridged from a real file: a namespaced document whose `route-point`
     * elements carry only identifiers, with every coordinate in the `waypoint-table`, every point
     * typed USER WAYPOINT, and the same airport at both ends because a VFR navigation is a circuit.
     */
    private val sdvfr = """
        <?xml version="1.0" encoding="UTF-8"?>
        <flight-plan xmlns="http://www8.garmin.com/xmlschemas/FlightPlan/v1"><file-description>LFPLLFAY</file-description><waypoint-table><waypoint><identifier>LFPLL</identifier><type>USER WAYPOINT</type><country-code/><lat>48.821944</lat><lon>2.622778</lon><comment/></waypoint><waypoint><identifier>LOBEL1</identifier><type>USER WAYPOINT</type><country-code/><lat>48.791600627042</lat><lon>2.8691714914193</lon><comment/></waypoint></waypoint-table><route><route-name>LFPLLFAY</route-name><flight-plan-index>1</flight-plan-index><route-point><waypoint-identifier>LFPLL</waypoint-identifier><waypoint-type>USER WAYPOINT</waypoint-type><waypoint-country-code/></route-point><route-point><waypoint-identifier>LOBEL1</waypoint-identifier><waypoint-type>USER WAYPOINT</waypoint-type><waypoint-country-code/></route-point><route-point><waypoint-identifier>LFPLL</waypoint-identifier><waypoint-type>USER WAYPOINT</waypoint-type><waypoint-country-code/></route-point></route></flight-plan>
    """.trimIndent()


    /**
     * The second real file's shape: names that differ only past the navigator's five characters,
     * so truncation alone would make both `POINT`. SD-VFR numbers a point with its position in the
     * route, so `POINT3` is the fourth point and `POINT5` the sixth; the filler keeps that true.
     */
    private val sdvfrCollision = """
        <?xml version="1.0" encoding="UTF-8"?>
        <flight-plan xmlns="http://www8.garmin.com/xmlschemas/FlightPlan/v1"><file-description>LFPLLFOQ</file-description><waypoint-table><waypoint><identifier>LFPLL</identifier><type>USER WAYPOINT</type><lat>48.821944</lat><lon>2.622778</lon></waypoint><waypoint><identifier>ALPHA1</identifier><type>USER WAYPOINT</type><lat>48.5</lat><lon>1.5</lon></waypoint><waypoint><identifier>BRAVO2</identifier><type>USER WAYPOINT</type><lat>48.4</lat><lon>1.4</lon></waypoint><waypoint><identifier>POINT3</identifier><type>USER WAYPOINT</type><lat>48.084858287232</lat><lon>1.0239251659556</lon></waypoint><waypoint><identifier>DELTA4</identifier><type>USER WAYPOINT</type><lat>47.9</lat><lon>2.0</lon></waypoint><waypoint><identifier>POINT5</identifier><type>USER WAYPOINT</type><lat>47.720164538462</lat><lon>2.6321061409492</lon></waypoint></waypoint-table><route><route-name>LFPLLFOQ</route-name><route-point><waypoint-identifier>LFPLL</waypoint-identifier><waypoint-type>USER WAYPOINT</waypoint-type></route-point><route-point><waypoint-identifier>ALPHA1</waypoint-identifier><waypoint-type>USER WAYPOINT</waypoint-type></route-point><route-point><waypoint-identifier>BRAVO2</waypoint-identifier><waypoint-type>USER WAYPOINT</waypoint-type></route-point><route-point><waypoint-identifier>POINT3</waypoint-identifier><waypoint-type>USER WAYPOINT</waypoint-type></route-point><route-point><waypoint-identifier>DELTA4</waypoint-identifier><waypoint-type>USER WAYPOINT</waypoint-type></route-point><route-point><waypoint-identifier>POINT5</waypoint-identifier><waypoint-type>USER WAYPOINT</waypoint-type></route-point><route-point><waypoint-identifier>LFPLL</waypoint-identifier><waypoint-type>USER WAYPOINT</waypoint-type></route-point></route></flight-plan>
    """.trimIndent()

    @Test
    fun `names too long for the navigator are shortened, and stay distinct`() {
        val r = RouteImporter.import("x.fpl", sdvfrCollision.toByteArray())
        // ALPHA1 and the others simply lose the exporter's numbering, because five characters of
        // the label are unique on their own. POINT3 and POINT5 would both truncate to POINT, so
        // both take the route position instead, which is the point of shortening across the whole
        // route rather than name by name: resolving them in arrival order would give POINT and
        // POIN5, which reads like two unrelated places.
        assertEquals(
            listOf("ALPHA", "BRAVO", "POIN3", "DELTA", "POIN5"),
            r.enroute.map { (it as UserWaypoint).ident },
        )
        assertEquals(
            ROUTE_PREFIX + ":F:LFPL,N48493E002374:F:ALPHA,N48300E001300:F:BRAVO,N48240E001240" +
                ":F:POIN3,N48051E001014:F:DELTA,N47540E002000:F:POIN5,N47432E002379" +
                ":F:LFPL,N48493E002374",
            r.render(Profiles.GPS175),
        )
        r.enroute.forEach { assertTrue((it as UserWaypoint).ident.length <= Profiles.GPS175.waypointNameLen) }
    }

    /**
     * A fixture, not a device: every shipped profile has the same caps, so only a different one
     * shows that shortening reads the selected navigator's cap rather than a constant.
     */
    private val narrow = Profile(
        name = "test fixture, not a device",
        productId = 0,
        waypointNameLen = 4,
        airportNameLen = 4,
        maxRouteLen = 0xdc0,
    )

    @Test
    fun `shortening follows the selected profile rather than a constant`() {
        val wide = RouteImporter.import("x.fpl", sdvfrCollision.toByteArray(), Profiles.GPS175)
        val tight = RouteImporter.import("x.fpl", sdvfrCollision.toByteArray(), narrow)

        val wideNames = wide.enroute.map { (it as UserWaypoint).ident }
        val tightNames = tight.enroute.map { (it as UserWaypoint).ident }

        // The same route, the same points, shorter names.
        assertEquals(wideNames.size, tightNames.size)
        tightNames.forEach { assertTrue("$it is over the cap", it.length <= narrow.waypointNameLen) }
        assertEquals("names must stay distinct", tightNames.size, tightNames.toSet().size)
        assertTrue("the cap has to change the answer", wideNames != tightNames)
    }

    @Test
    fun `an SD-VFR export imports as named user waypoints between real airports`() {
        val r = RouteImporter.import("lfpllfay.fpl", sdvfr.toByteArray())
        // The file types every point USER WAYPOINT, so the en route name travels with the
        // position the file gave it, since the navigator has no LOBEL1 to look up.
        assertEquals(listOf("LFPL", "LOBEL,N48475E002522", "LFPL"), r.identifiers)
        assertTrue(r.enroute[0] is UserWaypoint)
        // The exporter's decoration comes off the two airports: LFPLL is LFPL + Lognes. Each keeps
        // the position the file gave it on its :F: fix, since :DA: and :AA: will not take one.
        assertEquals("LFPL", r.departure!!.airport)
        assertEquals("LFPL", r.arrival!!.airport)
        assertEquals(
            ROUTE_PREFIX + ":F:LFPL,N48493E002374:F:LOBEL,N48475E002522:F:LFPL,N48493E002374",
            r.render(Profiles.GPS175),
        )
    }

    @Test
    fun `an imported SD-VFR route survives a trip through the route box`() {
        // identifiers feeds the editable text field, which parses again on every keystroke, so the
        // positions have to survive being displayed and read back: the user waypoint's in the
        // box, and the aerodromes' in the memo the application keeps beside it.
        val r = RouteImporter.import("x.fpl", sdvfr.toByteArray())
        val reparsed = RouteParser.parse(r.identifiers.joinToString(" "), r.placed)
        assertEquals(r.identifiers, reparsed.identifiers)
        for (profile in listOf(Profiles.GPS175, Profiles.GTN)) {
            assertEquals(profile.name, r.render(profile), reparsed.render(profile))
        }
        // The aerodromes come back as aerodromes, with the positions the file gave them.
        assertEquals(r.departure, reparsed.departure)
        assertEquals(r.arrival, reparsed.arrival)
        assertEquals("LOBEL", (reparsed.enroute.single() as UserWaypoint).ident)

        // The position quantises to tenths of a minute through the wire form, as a bare coordinate
        // does, which makes it a fixed point: editing the box repeatedly must not walk the route.
        assertEquals(
            reparsed.identifiers,
            RouteParser.parse(reparsed.identifiers.joinToString(" "), r.placed).identifiers,
        )
    }

    @Test
    fun `a typed route can name a user waypoint and its position`() {
        val r = RouteParser.parse("LFPL LOBEL,N48475E002522 LFPL")
        assertEquals(1, r.enroute.size)
        assertEquals(ROUTE_PREFIX + ":F:LFPL:F:LOBEL,N48475E002522:F:LFPL", r.render(Profiles.GPS175))
        // The comma is also the route separator. Without folding this would be two points.
        assertEquals(listOf("LFPL", "LOBEL,N48475E002522", "LFPL"), r.identifiers)
    }

    /**
     * Separating points with commas has to work as well as separating them with spaces, and then
     * the fold has a comma on both sides of every point.
     */
    @Test
    fun `named user waypoints survive a route separated by commas`() {
        val spaces = RouteParser.parse(
            "LFPL LOBEL,N48475E002522 LFPKC,N48502E003009 VILLA,N48504E002492 LFPL"
        )
        val commas = RouteParser.parse(
            "LFPL,LOBEL,N48475E002522,LFPKC,N48502E003009,VILLA,N48504E002492,LFPL"
        )
        assertEquals(3, commas.enroute.size)
        assertEquals(spaces.identifiers, commas.identifiers)
        assertEquals(
            ROUTE_PREFIX + ":F:LFPL:F:LOBEL,N48475E002522:F:LFPKC,N48502E003009" +
                ":F:VILLA,N48504E002492:F:LFPL",
            commas.render(Profiles.GPS175),
        )
    }

    /**
     * A route in SD-VFR's GPX dialect, reconstructed from what a pilot saw on screen. Its user
     * waypoint named for the A1 motorway has the shape of a route designator. A navigator sent it
     * as an airway, `:F:OBN,N49333E002118.A1.EMEAU,N48566E002594`, finds no airway A1 there and
     * drops the leg.
     */
    private val motorway = """
        <?xml version="1.0" encoding="UTF-8"?>
        <gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1" creator="SkyDreamSoft">
          <wpt lat="50.514722" lon="1.627222"><name>LFAT LE TOUQUET</name></wpt>
          <wpt lat="50.025" lon="1.28"><name>Criel</name></wpt>
          <wpt lat="49.555" lon="2.196667"><name>OBN</name></wpt>
          <wpt lat="49.306667" lon="2.673333"><name>A1</name></wpt>
          <wpt lat="48.943333" lon="2.99"><name>E Meaux</name></wpt>
          <wpt lat="48.821944" lon="2.622778"><name>LFPL LOGNES EMERAINVILLE</name></wpt>
        </gpx>
    """.trimIndent()

    private val motorwayWire = ROUTE_PREFIX +
        ":F:LFAT,N50309E001376:F:CRIEL,N50015E001168:F:OBN,N49333E002118" +
        ":F:A1,N49184E002404:F:EMEAU,N48566E002594:F:LFPL,N48493E002374"

    /** What a GPS 175 advertises: elements 0x0004002f, 3520 bytes, PUB_FMT and LAT_LON_USER. */
    private val gps175 = SupportedElements.decode(
        byteArrayOf(0x2f, 0x00, 0x04, 0x00, 0xc0.toByte(), 0x0d, 0x03)
    )

    @Test
    fun `a user waypoint named like an airway is still a point`() {
        val r = RouteImporter.import("route.gpx", motorway.toByteArray())
        assertTrue(r.enroute.none { it is Airway })
        assertEquals("A1", (r.enroute[2] as UserWaypoint).ident)
        assertEquals(motorwayWire, r.render(Profiles.GPS175))

        // What goes on the wire is what the preview shows: rendered with the capabilities a
        // GPS 175 advertises, and through the upload's own encoding, the bytes are the same.
        val sent = r.render(Profiles.GPS175, gps175)
        assertEquals(motorwayWire, sent)
        assertEquals(motorwayWire, decodeUpload(encodeUpload(sent, Profiles.GPS175, gps175)))

        // And the route box gives back the same route, so editing it cannot turn A1 back into an
        // airway: the box shows the point with its position, and the aerodromes by name.
        assertEquals(
            listOf(
                "LFAT", "CRIEL,N50015E001168", "OBN,N49333E002118", "A1,N49184E002404",
                "EMEAU,N48566E002594", "LFPL",
            ),
            r.identifiers,
        )
        val reparsed = RouteParser.parse(r.identifiers.joinToString(" "), r.placed)
        assertTrue(reparsed.enroute.none { it is Airway })
        assertEquals(motorwayWire, reparsed.render(Profiles.GPS175))

        // A GTN gets every user waypoint as its position, which it names itself, and the two
        // aerodromes with theirs. OBN is a user waypoint here, since the file does not say it is
        // anything else.
        val gtn = ROUTE_PREFIX + ":F:LFAT,N50309E001376:F:N50015E001168:F:N49333E002118" +
            ":F:N49184E002404:F:N48566E002594:F:LFPL,N48493E002374"
        assertEquals(gtn, r.render(Profiles.GTN, gps175))
        assertEquals(gtn, reparsed.render(Profiles.GTN, gps175))
    }

    @Test
    fun `the same route as a KML, and from SkyDemon, is the same points`() {
        val kml = motorway.lines().filter { it.trim().startsWith("<wpt") }.joinToString("\n") { line ->
            val (lat, lon, name) = Regex("""lat="([^"]+)" lon="([^"]+)"><name>([^<]+)""").find(line)!!.destructured
            "<Placemark><name>$name</name><Point><coordinates>$lon,$lat,0</coordinates></Point></Placemark>"
        }.let { "<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document>$it</Document></kml>" }
        assertEquals(motorwayWire, RouteImporter.import("route.kml", kml.toByteArray()).render(Profiles.GPS175))

        // SkyDemon gives an identifier in <sym> to what its database knows, and nothing to a point
        // it does not, which is what a pilot's own A1 is: a name and a position.
        val skyDemon = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx creator="SkyDemon Plan" xmlns="http://www.topografix.com/GPX/1/1"
                xmlns:skd="http://www.skydemon.aero/gpxextensions">
              <rte>
                <rtept lat="49.555" lon="2.196667"><name>OBN</name></rtept>
                <rtept lat="49.306667" lon="2.673333"><name>A1</name></rtept>
                <rtept lat="48.943333" lon="2.99"><name>EMEAU</name></rtept>
              </rte>
            </gpx>
        """.trimIndent()
        val s = RouteImporter.import("route.gpx", skyDemon.toByteArray())
        assertEquals("A1,N49184E002404", s.identifiers[1])
        assertTrue(s.enroute.none { it is Airway })
    }

    /** A `.fpl` with one point in the middle, typed by the file as [type]. */
    private fun fplThrough(ident: String, type: String, position: Boolean) = """
        <?xml version="1.0" encoding="utf-8"?>
        <flight-plan xmlns="http://www8.garmin.com/xmlschemas/FlightPlan/v1">
          <waypoint-table>
            <waypoint><identifier>LFPT</identifier><type>AIRPORT</type></waypoint>
            <waypoint><identifier>$ident</identifier><type>$type</type>${
                if (position) "<lat>49.0</lat><lon>2.3</lon>" else ""
            }</waypoint>
            <waypoint><identifier>LFPN</identifier><type>AIRPORT</type></waypoint>
          </waypoint-table>
          <route>
            <route-point><waypoint-identifier>LFPT</waypoint-identifier></route-point>
            <route-point><waypoint-identifier>$ident</waypoint-identifier></route-point>
            <route-point><waypoint-identifier>LFPN</waypoint-identifier></route-point>
          </route>
        </flight-plan>
    """.trimIndent()

    @Test
    fun `a database point named like an airway stays the point the file typed`() {
        // A reporting point called E9, typed INT by the file, is the file's own word that it is a
        // waypoint. It travels with the position the file gives it, like any stated identifier.
        val positioned = RouteImporter.import("x.fpl", fplThrough("E9", "INT", position = true).toByteArray())
        assertEquals(ROUTE_PREFIX + ":F:LFPT:F:E9,N49000E002180:F:LFPN", positioned.render(Profiles.GPS175))
        assertEquals(ROUTE_PREFIX + ":F:LFPT:F:E9,N49000E002180:F:LFPN", positioned.render(Profiles.GTN))
        // The box shows it by name, marked so the name is not read as an airway, and the memo
        // gives the position back.
        assertEquals("=E9", positioned.identifiers[1])
        val reread = RouteParser.parse(positioned.identifiers.joinToString(" "), positioned.placed)
        assertEquals(positioned.render(Profiles.GPS175), reread.render(Profiles.GPS175))

        // With no position to send, it goes as the identifier alone, and the box marks it so that
        // an edit cannot make an airway of it.
        val bare = RouteImporter.import("x.fpl", fplThrough("E9", "INT", position = false).toByteArray())
        assertEquals(ROUTE_PREFIX + ":F:LFPT:F:E9:F:LFPN", bare.render(Profiles.GPS175))
        assertEquals(listOf("LFPT", "=E9", "LFPN"), bare.identifiers)
        val edited = RouteParser.parse(bare.identifiers.joinToString(" "))
        assertTrue(edited.enroute[0] is Waypoint)
        assertEquals(bare.render(Profiles.GPS175), edited.render(Profiles.GPS175))

        // The simulator's .pln says the same thing with its own words.
        val pln = """
            <?xml version="1.0" encoding="UTF-8"?>
            <SimBase.Document><FlightPlan.FlightPlan>
              <ATCWaypoint id="LFPT"><ATCWaypointType>Airport</ATCWaypointType>
                <ICAO><ICAOIdent>LFPT</ICAOIdent></ICAO></ATCWaypoint>
              <ATCWaypoint id="E9"><ATCWaypointType>Intersection</ATCWaypointType>
                <WorldPosition>N49° 0' 0.00",E002° 18' 0.00",+000000.00</WorldPosition>
                <ICAO><ICAOIdent>E9</ICAOIdent></ICAO></ATCWaypoint>
              <ATCWaypoint id="LFPN"><ATCWaypointType>Airport</ATCWaypointType>
                <ICAO><ICAOIdent>LFPN</ICAOIdent></ICAO></ATCWaypoint>
            </FlightPlan.FlightPlan></SimBase.Document>
        """.trimIndent()
        assertEquals(
            ROUTE_PREFIX + ":F:LFPT:F:E9,N49000E002180:F:LFPN",
            RouteImporter.import("x.pln", pln.toByteArray()).render(Profiles.GPS175),
        )
    }

    @Test
    fun `a database point at an end is not cut down into an aerodrome`() {
        // An intersection is five letters, and the rule that recovers SD-VFR's decorated
        // aerodromes reads four of them: cut down, MANAK would be an aerodrome MANA, which no
        // database holds, and with its position the navigator would make a user waypoint of it.
        val fpl = fplThrough("E9", "INT", position = true)
            .replace("<identifier>LFPT</identifier><type>AIRPORT</type>",
                "<identifier>MANAK</identifier><type>INT</type><lat>46.213333</lat><lon>-0.941667</lon>")
            .replace("<waypoint-identifier>LFPT</waypoint-identifier>",
                "<waypoint-identifier>MANAK</waypoint-identifier>")
        val r = RouteImporter.import("x.fpl", fpl.toByteArray())
        assertEquals(null, r.departure)
        assertEquals("MANAK", r.identifiers.first())
        assertEquals(ROUTE_PREFIX + ":F:MANAK,N46128W000565:F:E9,N49000E002180:F:LFPN", r.render(Profiles.GPS175))
        // Five letters, so the box reads it back as a point too, with its position from the memo.
        val reread = RouteParser.parse(r.identifiers.joinToString(" "), r.placed)
        assertEquals(null, reread.departure)
        assertEquals(r.render(Profiles.GPS175), reread.render(Profiles.GPS175))
    }

    @Test
    fun `a hyphenated identifier keeps its position, in the box too`() {
        // The navigator's reader takes a hyphen in an identifier, and the position is what picks
        // between two points of one name, as for any other stated identifier.
        val r = RouteImporter.import("x.fpl", fplThrough("AB-1", "INT", position = true).toByteArray())
        val wire = ROUTE_PREFIX + ":F:LFPT:F:AB-1,N49000E002180:F:LFPN"
        assertEquals(wire, r.render(Profiles.GPS175))
        assertEquals(listOf("LFPT", "AB-1", "LFPN"), r.identifiers)
        assertEquals(wire, RouteParser.parse(r.identifiers.joinToString(" "), r.placed).render(Profiles.GPS175))

        // Placed twice, the name shows its position in the box, and reads back as the same point.
        val twice = fplThrough("AB-1", "INT", position = true)
            .replace(
                "<waypoint><identifier>LFPN</identifier>",
                "<waypoint><identifier>AB-1</identifier><type>INT</type><lat>49.5</lat><lon>2.5</lon></waypoint>" +
                    "<waypoint><identifier>LFPN</identifier>",
            )
            .replace(
                "<route-point><waypoint-identifier>LFPN</waypoint-identifier>",
                "<route-point><waypoint-identifier>AB-1</waypoint-identifier></route-point>" +
                    "<route-point><waypoint-identifier>LFPN</waypoint-identifier>",
            )
        val t = RouteImporter.import("x.fpl", twice.toByteArray())
        val twiceWire = ROUTE_PREFIX + ":F:LFPT:F:AB-1,N49000E002180:F:AB-1,N49300E002300:F:LFPN"
        assertEquals(twiceWire, t.render(Profiles.GPS175))
        assertEquals(listOf("LFPT", "AB-1,N49000E002180", "AB-1,N49300E002300", "LFPN"), t.identifiers)
        val reparsed = RouteParser.parse(t.identifiers.joinToString(" "), t.placed)
        assertTrue(reparsed.enroute.all { it is Waypoint })
        for (profile in listOf(Profiles.GPS175, Profiles.GTN)) {
            assertEquals(profile.name, twiceWire, reparsed.render(profile))
        }
        // The GTN Xi reads the hyphen as the start of another point, so it is refused the route.
        val xi = runCatching { reparsed.render(Profiles.GTN_XI) }.exceptionOrNull()
        assertEquals(
            Problem.HyphenInPointName("AB-1", "GTN Xi"),
            (xi as? FlightPlanException)?.problem,
        )

        // Typed with no file behind it, the name is the pilot's own point, which holds no hyphen.
        val typed = runCatching {
            RouteParser.parse("LFPT AB-1,N49000E002180 LFPN").render(Profiles.GPS175)
        }.exceptionOrNull()
        assertEquals(Problem.WaypointNameNotAlphanumeric("AB-1"), (typed as? FlightPlanException)?.problem)
    }

    @Test
    fun `an fpl that gives no positions sends identifiers, and a GPX route sends places`() {
        // The fpl fixture types its points AIRPORT and VOR and gives none of them a position, so
        // the identifiers go bare: there is nothing to send with them. GPX carries no type at all
        // but a position on every point, so every point travels as a name with its place.
        assertEquals(ROUTE_PREFIX + ":F:KSFO:F:SAC:F:KLAS", RouteImporter.importFpl(fpl.byteInputStream()).render(Profiles.GPS175))
        val g = RouteImporter.importGpx(gpx.byteInputStream())
        assertEquals(v334Imported, g.identifiers)
        assertTrue(g.enroute[0] is UserWaypoint)
    }

    /**
     * The route box shows a file's database points and aerodromes by name, and the application
     * keeps the positions the file gave them beside it, `ParsedRoute.placed`, so that an edit
     * sends them still. Only what the file placed is remembered, and only under the name it gave.
     */
    @Test
    fun `the route box remembers the positions a file gave the names it shows`() {
        val r = RouteImporter.import("route.pln", pln.toByteArray())
        val text = r.identifiers.joinToString(" ")
        assertEquals("KSFO SAC LOBEL,N48475E002522 KLAS", text)

        // With the memo the box is the file, on either model.
        for (profile in listOf(Profiles.GPS175, Profiles.GTN)) {
            assertEquals(profile.name, r.render(profile), RouteParser.parse(text, r.placed).render(profile))
        }
        // Without one, which is a typed route, the names go bare and the pilot's own point keeps
        // the position it is shown with.
        assertEquals(
            ROUTE_PREFIX + ":F:KSFO:F:SAC:F:LOBEL,N48475E002522:F:KLAS",
            RouteParser.parse(text).render(Profiles.GPS175),
        )

        // An edit keeps what it did not remove.
        assertEquals(
            ROUTE_PREFIX + ":F:KSFO,N37371W122225:F:LOBEL,N48475E002522:F:KLAS,N36048W115092",
            RouteParser.parse("KSFO LOBEL,N48475E002522 KLAS", r.placed).render(Profiles.GPS175),
        )

        // A name typed with the very position the file gave it is that database point, so a GTN
        // keeps its name. With any other position it is the pilot's own point, which a GTN is sent
        // as a position.
        val stated = RouteParser.parse("KSFO SAC,N38266W121331 KLAS", r.placed)
        assertTrue(stated.enroute.single() is Waypoint)
        assertTrue(stated.render(Profiles.GTN).contains(":F:SAC,N38266W121331:"))
        val moved = RouteParser.parse("KSFO SAC,N38300W121300 KLAS", r.placed)
        assertTrue(moved.enroute.single() is UserWaypoint)
        assertTrue(moved.render(Profiles.GTN).contains(":F:N38300W121300:"))

        // `=` is looked through, and `!` makes an airway, which has no position to take.
        assertEquals(
            "N38266W121331",
            (RouteParser.parse("KSFO =SAC KLAS", r.placed).enroute.single() as Waypoint).at!!.ident,
        )
        assertTrue(RouteParser.parse("KSFO LOBEL,N48475E002522 !SAC KLAS", r.placed).enroute[1] is Airway)
    }

    /**
     * A name the file places at two positions cannot be shown bare: the memo could not say which
     * of the two the pilot meant. Two reporting points of one letter in one navigation are
     * ordinary, one per control zone, and SkyDemon states both.
     */
    @Test
    fun `a name a file places twice keeps its position in the box`() {
        val gpx = """
            <?xml version="1.0" encoding="utf-8"?>
            <gpx version="1.1" creator="SkyDemon Plan v3.4.2.26247" xmlns="http://www.topografix.com/GPX/1/1">
              <rte>
                <rtept lat="50.948610" lon="4.391944"><name>GRIMBERGEN / Lint</name><sym>EBGB</sym></rtept>
                <rtept lat="51.286110" lon="4.233056"><name>Delta North</name><sym>D</sym></rtept>
                <rtept lat="50.900000" lon="4.500000"><name>Delta South</name><sym>D</sym></rtept>
                <rtept lat="50.948610" lon="4.391944"><name>GRIMBERGEN / Lint</name><sym>EBGB</sym></rtept>
              </rte>
            </gpx>
        """.trimIndent()
        val r = RouteImporter.import("route.gpx", gpx.toByteArray())
        assertEquals(listOf("EBGB", "D,N51172E004140", "D,N50540E004300", "EBGB"), r.identifiers)

        val wire = ROUTE_PREFIX + ":F:EBGB,N50569E004235:F:D,N51172E004140:F:D,N50540E004300" +
            ":F:EBGB,N50569E004235"
        val reparsed = RouteParser.parse(r.identifiers.joinToString(" "), r.placed)
        for (profile in listOf(Profiles.GPS175, Profiles.GTN)) {
            assertEquals(profile.name, wire, r.render(profile))
            assertEquals(profile.name, wire, reparsed.render(profile))
        }
        // Both are the file's database points, a <sym> with a name, which is what keeps their
        // names on a GTN.
        assertTrue(reparsed.enroute.all { it is Waypoint })
        // And a bare D is ambiguous, so it takes neither position.
        assertNull((RouteParser.parse("EBGB D EBGB", r.placed).enroute.single() as Waypoint).at)
    }

    @Test
    fun `an external entity never reaches a route`() {
        // The property is asserted rather than the mechanism: refusing the document and expanding
        // the entity to nothing are both fine, leaking the file is not.
        val evil = """
            <?xml version="1.0"?>
            <!DOCTYPE flight-plan [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <flight-plan>
              <route>
                <route-point><waypoint-identifier>KSFO</waypoint-identifier></route-point>
                <route-point><waypoint-identifier>&xxe;</waypoint-identifier></route-point>
                <route-point><waypoint-identifier>KLAS</waypoint-identifier></route-point>
              </route>
            </flight-plan>
        """.trimIndent()
        RouteImporter.runCatching { importFpl(evil.byteInputStream()) }.onSuccess { route ->
            assertFalse(route.identifiers.any { it.contains("root") || it.contains("/bin/") })
        }
    }

    @Test
    fun `a route with fewer than two points is refused`() {
        val short = fpl.replace(
            Regex("<route-point>\\s*<waypoint-identifier>SAC.*?</route-point>", RegexOption.DOT_MATCHES_ALL),
            "",
        ).replace(
            Regex("<route-point>\\s*<waypoint-identifier>KLAS.*?</route-point>", RegexOption.DOT_MATCHES_ALL),
            "",
        )
        assertTrue(
            runCatching { RouteImporter.importFpl(short.byteInputStream()) }
                .exceptionOrNull() is RouteParseException
        )
    }

    /**
     * A position a file states has to be on the earth, whatever its notation. Refused rather than
     * dropped, because a point that quietly loses its position leaves a shorter route that still
     * looks like one.
     */
    @Test
    fun `a position off the earth is refused, in every notation`() {
        val offEarth = gpx.replace("""lon="-122.4"""", """lon="-200.5"""")
        val gpxError = runCatching { RouteImporter.importGpx(offEarth.byteInputStream()) }
            .exceptionOrNull()
        assertTrue("$gpxError", gpxError is RouteParseException)
        assertTrue(gpxError!!.message!!.contains("off the earth"))

        val pln = """
            <SimBase.Document><FlightPlan.FlightPlan>
              <ATCWaypoint id="A"><ATCWaypointType>Airport</ATCWaypointType>
                <WorldPosition>N48° 49' 19.00",E200° 37' 22.00",+001500.00</WorldPosition>
              </ATCWaypoint>
              <ATCWaypoint id="B"><ATCWaypointType>Airport</ATCWaypointType>
                <WorldPosition>N48° 50' 19.00",E002° 37' 22.00",+001500.00</WorldPosition>
              </ATCWaypoint>
            </FlightPlan.FlightPlan></SimBase.Document>
        """.trimIndent()
        val plnError = runCatching { RouteImporter.importPln(pln.byteInputStream()) }
            .exceptionOrNull()
        assertTrue("$plnError", plnError is RouteParseException)
        assertTrue(plnError!!.message!!.contains("off the earth"))
    }

    /**
     * `NaN` is a string `toDoubleOrNull` parses happily, and it passes every range test, since
     * every comparison against one is false. Left alone it truncates to zero degrees and encodes
     * as N00000E000000, a point in the Gulf of Guinea, in silence.
     */
    @Test
    fun `a position given as NaN is refused rather than encoded as the Gulf of Guinea`() {
        val notANumber = gpx.replace("""lat="37.6"""", """lat="NaN"""")
        val e = runCatching { RouteImporter.importGpx(notANumber.byteInputStream()) }
            .exceptionOrNull()
        assertTrue("$e", e is RouteParseException)
        assertTrue(e!!.message!!.contains("not a number"))
    }
}

/**
 * One navigation, exported four ways by the same planner, has to arrive at the navigator as one
 * route. The files are a real SD-VFR export of a 22 point circuit, kept whole because an
 * abridgement would be a fifth opinion. They agree only on the positions: one point is `ABBAY10`
 * in the `.fpl`, `ABBAYEDESA` in the `.pln` and `Abbaye de Saint Martin aux Bois` in the `.gpx`
 * and `.kml`, and the `.pln` has two points the others do not.
 */
class RouteConvergenceTest {

    private val files = listOf("chateaux.fpl", "chateaux.gpx", "chateaux.kml", "chateaux.pln")

    private fun fixture(name: String): ByteArray =
        javaClass.getResourceAsStream("/routes/$name")?.use { it.readBytes() }
            ?: error("missing test resource /routes/$name")

    /** Kept here rather than in a fifth file, so that no editor's trailing newline can move it. */
    private val expected = ROUTE_PREFIX +
        ":F:LFPL,N48493E002374" +
        ":F:LOBEL,N48475E002522" + ":F:CLMC2,N48507E003008" + ":F:CARRI,N48595E002590" +
        ":F:SILOS,N49084E002475" + ":F:ABBA5,N49090E002412" + ":F:PARCA,N49080E002344" +
        ":F:ABBA7,N49088E002229" + ":F:CHAT8,N49117E002292" + ":F:SENLI,N49135E002351" +
        ":F:ABB10,N49316E002342" + ":F:ABB11,N49321E002585" + ":F:LFADC,N49260E002483" +
        ":F:PALAI,N49252E002499" + ":F:CHA14,N49208E002588" + ":F:LFJSS,N49207E003170" +
        ":F:DON16,N49201E003216" + ":F:CHA17,N49133E003319" + ":F:CHA18,N49123E003342" +
        ":F:DON19,N49157E003000" + ":F:CLM20,N48507E003008" +
        ":F:LFPL,N48493E002374"

    @Test
    fun `one navigation exported four ways imports as one route`() {
        val routes = files.associateWith { RouteImporter.import(it, fixture(it)) }
        val reference = routes.getValue("chateaux.fpl").identifiers

        routes.forEach { (name, route) ->
            // Point by point before the whole string, so that a disagreement names the place it
            // happened rather than an offset into 488 characters. The identifiers are where a
            // divergence would show first: the four files carry four spellings of every name,
            // and they only agree because each is a prefix of the same folded label.
            reference.forEachIndexed { i, want ->
                assertEquals("$name, point $i", want, route.identifiers.getOrNull(i))
            }
            assertEquals("$name, point count", reference.size, route.identifiers.size)
            assertEquals(name, expected, route.render(Profiles.GPS175))
        }
        // Under the navigator's own 0xdc0, with room to spare, and pinned so that a change to
        // the shortening rules cannot quietly lengthen a route.
        assertEquals(488, expected.length)
    }

    @Test
    fun `the pln repeats its airport at both ends, and that is one point rather than two`() {
        // 24 ATCWaypoints: the departure as an Airport point and again as a User point at the
        // same coordinates, and the same at the destination.
        assertEquals(22, RouteImporter.import("x.fpl", fixture("chateaux.fpl")).identifiers.size)
        val pln = RouteImporter.import("x.pln", fixture("chateaux.pln"))
        assertEquals(22, pln.identifiers.size)
        // The survivor is the one that names an airport, and it is at both ends.
        assertEquals("LFPL", pln.departure!!.airport)
        assertEquals("LFPL", pln.arrival!!.airport)
    }

    @Test
    fun `the four exporters spell an airport three different ways and mean LFPL`() {
        // The .pln says so outright. The .fpl writes LFPLL, four ICAO letters and one of Lognes.
        // The .gpx and .kml write "LFPL LOGNES EMERAINVILLE", where only the first word is an
        // identifier. All three readings are narrow and apply to the ends of a route only: three
        // en route labels in this very file begin with a four letter word, and one of them is
        // Parc Astérix.
        files.forEach { name ->
            val r = RouteImporter.import(name, fixture(name))
            assertEquals(name, "LFPL", r.departure!!.airport)
            assertEquals(name, "LFPL", r.arrival!!.airport)
        }
    }

    @Test
    fun `the route survives the box unchanged`() {
        // An imported route is written back into the editable text field and parsed again on
        // every keystroke, so what it renders must not move.
        val r = RouteImporter.import("chateaux.fpl", fixture("chateaux.fpl"))
        // With the memo the application keeps beside the box, which is what puts LFPL's position
        // back at both ends.
        val once = RouteParser.parse(r.identifiers.joinToString(" "), r.placed)

        // Donjon de Vez sits at 2 degrees 59.98 minutes, 600 tenths after rounding. They are
        // carried into the degrees from the first rendering, so the file and the box send it
        // alike, as E003000, rather than the file sending E002600, which a GPS 175 stores at
        // 59.9 minutes, and the box E003000.
        assertEquals("N49157E003000", formatLatLon(49.261666666667, 2.9997222222222))
        assertEquals(expected, r.render(Profiles.GPS175))
        assertEquals(expected, once.render(Profiles.GPS175))

        // A fixed point, so editing the box repeatedly does not walk the route.
        val twice = RouteParser.parse(once.identifiers.joinToString(" "), r.placed)
        assertEquals(once.identifiers, twice.identifiers)
        assertEquals(once.render(Profiles.GPS175), twice.render(Profiles.GPS175))
    }

    @Test
    fun `an external entity never reaches a route through the new formats either`() {
        // The parser is shared, so this asserts that the KML and PLN importers go through it.
        val evil = listOf(
            """
                <?xml version="1.0"?>
                <!DOCTYPE kml [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <kml><Document>
                  <Placemark><name>KSFO</name><Point><coordinates>-122.4,37.6,0</coordinates></Point></Placemark>
                  <Placemark><name>&xxe;</name><Point><coordinates>-121.5,38.4,0</coordinates></Point></Placemark>
                  <Placemark><name>KLAS</name><Point><coordinates>-115.2,36.1,0</coordinates></Point></Placemark>
                </Document></kml>
            """.trimIndent(),
            """
                <?xml version="1.0"?>
                <!DOCTYPE SimBase.Document [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <SimBase.Document><FlightPlan.FlightPlan>
                  <ATCWaypoint id="KSFO"><ATCWaypointType>Airport</ATCWaypointType></ATCWaypoint>
                  <ATCWaypoint id="&xxe;"><ATCWaypointType>User</ATCWaypointType></ATCWaypoint>
                  <ATCWaypoint id="KLAS"><ATCWaypointType>Airport</ATCWaypointType></ATCWaypoint>
                </FlightPlan.FlightPlan></SimBase.Document>
            """.trimIndent(),
        )
        evil.forEach { body ->
            RouteImporter.runCatching { import(null, body.toByteArray()) }.onSuccess { route ->
                assertFalse(route.identifiers.any { it.contains("root") || it.contains("/bin/") })
            }
        }
    }
}
