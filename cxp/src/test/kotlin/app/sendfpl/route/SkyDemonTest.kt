package app.sendfpl.route

import app.sendfpl.cxp.Coordinate
import app.sendfpl.cxp.Profiles
import app.sendfpl.cxp.ROUTE_PREFIX
import app.sendfpl.cxp.UserWaypoint
import app.sendfpl.cxp.Waypoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Routes SkyDemon wrote.
 *
 * Every fixture here is a file SkyDemon itself exported, and between them they exercise what the
 * SD-VFR set cannot. That set types all twenty two of its points `USER WAYPOINT`, including three
 * real aerodromes and a real VOR, so nothing in it reaches a database point, a NAVAID, an airway
 * intersection or a VFR reporting point. These do.
 *
 * They also cover two shapes the importer used to get wrong in silence and one it refused
 * outright: a `.fpl` whose intersections carry no identifier, an alternate appended after the
 * destination, and a route between two unlicensed strips with no ICAO code at either end.
 */
class SkyDemonTest {

    private fun fixture(name: String): ByteArray =
        javaClass.getResourceAsStream("/routes/$name")?.use { it.readBytes() }
            ?: error("missing test resource /routes/$name")

    /** The wire form of a position is thirteen characters beginning with a hemisphere. */
    private fun isPosition(s: String) = s.length == 13 && (s[0] == 'N' || s[0] == 'S')

    // The same navigation, EDFC to EDDW with EDWH as the alternate, exported by SkyDemon 3.16.x
    // both ways. Fourteen points in each once the alternate is set aside.
    private val nativePositions = listOf(
        "N49563E009038", "N49586E009031", "N50166E008509", "N50381E008492", "N50495E008531",
        "N51028E008575", "N51067E008586", "N51189E009028", "N51303E009067", "N51531E009116",
        "N52223E009054", "N52463E008473", "N52578E008472", "N53028E008472",
    )

    @Test
    fun `the native format is a route of bare positions`() {
        val route = RouteImporter.import("x.flightplan", fixture("skydemon.flightplan"))
        assertEquals(nativePositions, route.identifiers)
        assertTrue(route.enroute.all { it is Coordinate })
        // No aerodrome at either end, because the file names none. There is nothing to promote.
        assertNull(route.departure)
        assertNull(route.arrival)
        assertEquals(
            ROUTE_PREFIX + nativePositions.joinToString("") { ":F:$it" },
            route.render(Profiles.GPS175),
        )
    }

    @Test
    fun `the alternate is a sibling of the legs and not one of them`() {
        // The file carries EDWH as `<Alternate To="N530408.65 E0081848.55" />`. A fifteenth point
        // here would be a flight to Wilhelmshaven.
        val route = RouteImporter.import("x.flightplan", fixture("skydemon.flightplan"))
        assertEquals(14, route.identifiers.size)
        assertTrue(route.identifiers.none { it == "N53041E008188" })
    }

    @Test
    fun `one navigation exported two ways agrees about every position`() {
        val native = RouteImporter.import("x.flightplan", fixture("skydemon.flightplan"))
        val garmin = RouteImporter.import("x.fpl", fixture("skydemon.fpl"))
        assertEquals("point count", native.identifiers.size, garmin.identifiers.size)

        // The two files cannot agree about identifiers, because the native format has none to
        // agree with. What they can agree about is where the aircraft goes, so that is what is
        // asserted: identical wherever the `.fpl` also gives a position.
        native.identifiers.zip(garmin.identifiers).forEachIndexed { i, (position, identifier) ->
            assertTrue("point $i of the native file is $position", isPosition(position))
            if (isPosition(identifier)) assertEquals("point $i", position, identifier)
        }

        // And the four the `.fpl` did name are pinned against the positions the other file gives
        // for them, so "agree where both speak" is not vacuously true.
        assertEquals(
            listOf(
                "EDFC" to "N49563E009038",
                "MTR" to "N50166E008509",
                "WRB" to "N51303E009067",
                "EDDW" to "N53028E008472",
            ),
            garmin.identifiers.zip(native.identifiers).filter { !isPosition(it.first) },
        )
    }

    @Test
    fun `the GPX says what kind of point each one is`() {
        // A circuit from EBGB, and the one fixture that reaches every kind of point at once.
        val route = RouteImporter.import("x.GPX", fixture("skydemon-circuit.GPX"))
        assertEquals(
            listOf(
                // An aerodrome, from <sym>EBGB</sym> rather than from its name.
                "EBGB",
                // User waypoints, their "WP - " prefix stripped so the five characters are spent
                // on the name rather than on the marker.
                "KAPEL,N51011E004222", "BUGG2,N51000E004129",
                // A VOR, whose <name> is "Nicky" and whose <sym> is what the database calls it.
                "NIK",
                // A reporting point with an empty <name> and <sym>D</sym>.
                "D",
                // Belgian VFR reporting points, whose names are identifiers but which SkyDemon
                // gives no <sym>, so they travel as a name with a position.
                "KALLO,N51152E004169", "PORTA,N51138E004265", "ALBER,N51143E004300",
                // A point with neither name nor identifier: a bare position.
                "N51099E004509",
                "LIERA,N51071E004359", "WILLE,N51041E004233",
                // The second visit to Buggenhoutbos, numbered apart from the first because two
                // points in one route may not share an identifier.
                "BUG11,N51000E004129",
                "LONDI,N50594E004179",
                "EBGB",
            ),
            route.identifiers,
        )
        assertEquals("EBGB", route.departure!!.airport)
        assertEquals("EBGB", route.arrival!!.airport)
        assertTrue("a VOR travels as its identifier alone", route.enroute[2] is Waypoint)
        assertTrue(route.enroute[0] is UserWaypoint)
        assertTrue(route.enroute[7] is Coordinate)
    }

    @Test
    fun `a route between two unlicensed strips has no ICAO at either end`() {
        // Little Snoring and Badminton are private strips. Neither has an identifier anywhere in
        // the file, because neither has one at all. This was refused outright until the ends
        // stopped having to be aerodromes.
        val route = RouteImporter.import("x.GPX", fixture("skydemon-strips.GPX"))
        assertNull(route.departure)
        assertNull(route.arrival)
        assertEquals(
            listOf(
                "LITTL,N52516E000545", "KINGS,N52451E000237",
                // Two aerodromes overflown en route, which do have identifiers and travel as them.
                "EGSF", "EGBW",
                "STROU,N51450W002120", "BADMI,N51329W002182",
            ),
            route.identifiers,
        )
        assertEquals(
            ROUTE_PREFIX + ":F:LITTL,N52516E000545:F:KINGS,N52451E000237:F:EGSF:F:EGBW" +
                ":F:STROU,N51450W002120:F:BADMI,N51329W002182",
            route.render(Profiles.GPS175),
        )
    }

    @Test
    fun `a waypoint library is refused as a library`() {
        // SkyDemon's Tools menu exports one of these, and it is a set of places with no order to
        // fly them in. The GPX reader's fallback from <rte> to <wpt> would otherwise import a
        // library of castles as a navigation, in alphabetical order.
        val e = runCatching {
            RouteImporter.import("Castles.userwaypoints.gpx", fixture("skydemon.userwaypoints.gpx"))
        }.exceptionOrNull()
        assertTrue(e is RouteParseException)
        assertTrue("$e", e!!.message!!.contains("waypoint library"))
    }

    @Test
    fun `an upper case extension is still a GPX`() {
        // Every SkyDemon route exported from the Windows planner in the sample set is named .GPX.
        assertEquals(
            RouteImporter.import("route.gpx", fixture("skydemon-strips.GPX")).identifiers,
            RouteImporter.import("route.GPX", fixture("skydemon-strips.GPX")).identifiers,
        )
    }

    @Test
    fun `the native format is recognised with no file name at all`() {
        // A share can lose the name, so the root element has to be enough.
        val route = RouteImporter.import(null, fixture("skydemon.flightplan"))
        assertEquals(nativePositions, route.identifiers)
    }
}
