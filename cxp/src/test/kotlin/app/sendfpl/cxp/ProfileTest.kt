package app.sendfpl.cxp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the profile table against a copy of the same numbers transcribed by hand.
 *
 * These caps come from a navigator's own parser, and getting one wrong does not truncate a name:
 * it desynchronises the parser and the whole upload is rejected, invisibly from the route text. So
 * the table is deliberately awkward to edit: a change has to be made here too, by someone who went
 * back to the firmware. Same discipline as `TransportTest`'s pinned byte strings.
 */
class ProfileTest {
    /**
     * The `Profiles` map, transcribed independently of the map itself.
     *
     * Order: name, waypoint, airport, airway, procedure, transition, route, waypoint count, bare
     * user waypoints.
     *
     * The last two are not parser caps. The waypoint count is published per model in that model's
     * pilot's guide, and a GTN holds 99 from an import where a GPS 175 holds 100. Bare user
     * waypoints is the builder's: a GTN creates a named user waypoint only when an import activates
     * the plan (`FUN_10005dd0`), which a Connext upload never does.
     */
    private val expected = mapOf(
        2800L to listOf("GNX 375/GPS 175/GNC 355", 5, 4, 5, 10, 5, 0xdc0, 100, false),
        1026L to listOf("GTN 6xx/7xx", 5, 4, 5, 10, 5, 0xdc0, 99, true),
    )

    @Test
    fun `the table matches its transcription`() {
        assertEquals(
            "a profile was added or removed without updating this test",
            expected.size,
            Profiles.byProductId.size,
        )
        for ((id, want) in expected) {
            val p = Profiles.forProductId(id)
            assertEquals("name for $id", want[0], p.name)
            assertEquals("waypoint cap for $id", want[1], p.waypointNameLen)
            assertEquals("airport cap for $id", want[2], p.airportNameLen)
            assertEquals("airway cap for $id", want[3], p.airwayNameLen)
            assertEquals("procedure cap for $id", want[4], p.procedureNameLen)
            assertEquals("transition cap for $id", want[5], p.transitionNameLen)
            assertEquals("route length for $id", want[6], p.maxRouteLen)
            assertEquals("waypoint count for $id", want[7], p.maxWaypoints)
            assertEquals("bare user waypoints for $id", want[8], p.bareUserWaypoints)
            assertEquals("product id for $id", id, p.productId)
        }
    }

    /**
     * A published profile carries every cap.
     *
     * Stricter than [Profile.isValid] on purpose. That property requires only the three caps every
     * profile has always had, so a device keeps working when a *newly added* cap has not been read
     * for it, and the encoder then simply does not enforce that one. The leniency is for the code
     * path, not for this table: an entry here with a cap left at zero would quietly stop checking
     * it, so a profile is either read in full or it is not published.
     */
    @Test
    fun `every published profile is read in full`() {
        for ((id, p) in Profiles.byProductId) {
            for ((what, n) in listOf(
                "waypoint" to p.waypointNameLen,
                "airport" to p.airportNameLen,
                "airway" to p.airwayNameLen,
                "procedure" to p.procedureNameLen,
                "transition" to p.transitionNameLen,
                "route" to p.maxRouteLen,
            )) {
                assertTrue("$what cap unread for $id (${p.name})", n > 0)
            }
        }
    }

    /** The profile ids must agree with the device table the Bluetooth layer already carries. */
    @Test
    fun `product ids agree with GarminDevices`() {
        assertEquals(ProductId.G2N, Profiles.GPS175.productId)
        assertEquals(ProductId.GTN_6XX_7XX, Profiles.GTN.productId)
    }

    @Test
    fun `an unknown device is refused`() {
        for (bad in listOf(0L, 1L, 2021L, 3247L)) {
            runCatching { Profiles.forProductId(bad) }
                .onSuccess { org.junit.Assert.fail("product id $bad must be refused, got $it") }
                .onFailure { assertTrue(it is UnknownDeviceException) }
        }
        runCatching { Profiles.named("gtn750xi") }
            .onSuccess { org.junit.Assert.fail("an unknown name must be refused") }
            .onFailure { assertTrue(it is UnknownDeviceException) }

        val empty = Profile("nothing", productId = 0, waypointNameLen = 0, airportNameLen = 0, maxRouteLen = 0)
        runCatching { buildRoute(empty, departure = Departure("KSFO")) }
            .onSuccess { org.junit.Assert.fail("an invalid profile must be refused") }
            .onFailure { assertTrue(it is UnknownDeviceException) }
        runCatching { encodeUpload(":DA:KSFO", empty) }
            .onSuccess { org.junit.Assert.fail("an invalid profile must be refused") }
            .onFailure { assertTrue(it is UnknownDeviceException) }
    }

    @Test
    fun `every advertised name resolves`() {
        for (n in Profiles.names) {
            assertTrue("$n is advertised but does not resolve", Profiles.named(n).isValid)
        }
    }

    /**
     * buildRoute must use the selected device's cap, not the widest across profiles. With the two
     * current profiles equal this cannot be shown by a difference, so it is shown by construction.
     */
    @Test
    fun `buildRoute uses the profile cap`() {
        val tight = Profile("tight", 9999, waypointNameLen = 3, airportNameLen = 4, maxRouteLen = 0xdc0)
        val enroute = listOf(UserWaypoint("LOBEL", 48.79, 2.42))

        buildRoute(Profiles.GPS175, departure = Departure("KSFO"), enroute = enroute)

        runCatching { buildRoute(tight, departure = Departure("KSFO"), enroute = enroute) }
            .onSuccess { org.junit.Assert.fail("a name of 5 must be refused when capped at 3") }
            .onFailure { assertTrue(it.message!!.contains("reads 3")) }
    }

    /**
     * Every cap is enforced, one character over and one character at.
     *
     * The plain [Waypoint] case is the one this test exists for. It went unchecked while the user
     * waypoint beside it was checked, so a typed identifier of any length reached the wire, arrived
     * under a shorter name that is very often a different real waypoint, and took the whole message
     * down with it.
     */
    @Test
    fun `every cap is enforced`() {
        val dep = Departure("KSFO")
        val arr = Arrival("KLAS")
        for ((what, pair) in listOf(
            "waypoint" to Pair(
                { buildRoute(Profiles.GPS175, dep, listOf(Waypoint("ABCDEF")), arr) },
                { buildRoute(Profiles.GPS175, dep, listOf(Waypoint("ABCDE")), arr) },
            ),
            "airway" to Pair(
                { buildRoute(Profiles.GPS175, dep, listOf(Airway("UL975Y")), arr) },
                { buildRoute(Profiles.GPS175, dep, listOf(Airway("UL975")), arr) },
            ),
            "procedure" to Pair(
                { buildRoute(Profiles.GPS175, Departure("KSFO", "ABCDEFGHIJK"), arrival = arr) },
                { buildRoute(Profiles.GPS175, Departure("KSFO", "ABCDEFGHIJ"), arrival = arr) },
            ),
            "transition" to Pair(
                { buildRoute(Profiles.GPS175, Departure("KSFO", "SSTIK3", "ABCDEF"), arrival = arr) },
                { buildRoute(Profiles.GPS175, Departure("KSFO", "SSTIK3", "ABCDE"), arrival = arr) },
            ),
        )) {
            val (over, fits) = pair
            runCatching { over() }
                .onSuccess { org.junit.Assert.fail("one character over the $what cap was accepted") }
                .onFailure {
                    assertTrue("the error should name the $what: ${it.message}",
                        it.message!!.contains(what))
                    assertTrue("the problem should be typed", (it as FlightPlanException).problem
                        is Problem.ElementIdentTooLong)
                }
            fits()
        }
    }

    /**
     * A cap left at zero is one that has not been read for that device, and then nothing is
     * enforced. This is what keeps a working profile working when a new cap is added and cannot be
     * recovered for every model at once.
     */
    @Test
    fun `a cap that was never read is not enforced`() {
        val partial = Profile(
            "read for the old caps only", productId = 9999,
            waypointNameLen = 5, airportNameLen = 4, maxRouteLen = 0xdc0,
        )
        buildRoute(partial, Departure("KSFO", "AN-ABSURDLY-LONG-PROCEDURE"),
            listOf(Airway("ALSO-FAR-TOO-LONG")), Arrival("KLAS"))

        // And the caps that *were* read still are, so this is not a way to opt out.
        runCatching {
            buildRoute(partial, Departure("KSFO"), listOf(Waypoint("ABCDEF")), Arrival("KLAS"))
        }.onSuccess { org.junit.Assert.fail("a cap that was read must still be enforced") }
    }

    /**
     * The navigator's flight plan capacity, which nothing else catches: it is reached at 100
     * points, and 100 points is well inside the 3520 byte route limit.
     *
     * The reference implementation asserts the same boundary.
     */
    @Test
    fun `a route of more points than the navigator holds is refused`() {
        // The two airports are points too, so the enroute list makes up the rest.
        fun route(n: Int) = buildRoute(
            Profiles.GPS175,
            Departure("LFPL"),
            (0 until n - 2).map { Coordinate(48.0 + it / 600.0, 2.0) },
            Arrival("LFPK"),
        )

        val hundred = route(100)
        assertTrue(
            "100 points must stay inside the byte limit, or this no longer isolates the count",
            hundred.length <= Profiles.GPS175.maxRouteLen,
        )

        runCatching { route(101) }
            .onSuccess { org.junit.Assert.fail("101 waypoints must be refused") }
            .onFailure {
                assertTrue(
                    "the problem should be typed",
                    (it as FlightPlanException).problem is Problem.TooManyWaypoints,
                )
            }

        // A GTN holds one fewer from an import, which an upload is: its guide, 190-01007-03
        // Rev. R, section 4.5, truncates an imported plan at 99.
        fun gtnRoute(n: Int) = buildRoute(
            Profiles.GTN,
            Departure("LFPL"),
            (0 until n - 2).map { Coordinate(48.0 + it / 600.0, 2.0) },
            Arrival("LFPK"),
        )
        gtnRoute(99)
        runCatching { gtnRoute(100) }
            .onSuccess { org.junit.Assert.fail("100 waypoints must be refused on a GTN") }
            .onFailure {
                assertTrue(
                    "the problem should be typed",
                    (it as FlightPlanException).problem is Problem.TooManyWaypoints,
                )
            }
    }

    /**
     * An airway is not a waypoint. It rides inside the element of the point that joins it, so a
     * route of four fixes and an airway between two of them is four points.
     */
    @Test
    fun `an airway does not count towards the waypoint total`() {
        val tight = Profiles.GPS175.copy(maxWaypoints = 4)
        buildRoute(
            tight,
            Departure("LFLL"),
            listOf(Waypoint("MANAK"), Airway("UT183"), Waypoint("TIRAV")),
            Arrival("LFRB"),
        )
    }
}
