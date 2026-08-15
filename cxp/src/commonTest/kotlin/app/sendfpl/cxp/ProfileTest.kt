package app.sendfpl.cxp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Pins the profile table against the same numbers transcribed by hand. A wrong cap does not
 * truncate a name, it desynchronises the navigator's parser and loses the upload, so a change has
 * to be made here too.
 */
class ProfileTest {
    /**
     * The `Profiles` map, transcribed independently. Order: name, waypoint, airport, airway,
     * procedure, transition, route, waypoint count, bare user waypoints, hyphen splits a point.
     * Waypoint count and bare user waypoints are not parser caps: see [Profile.maxWaypoints] and
     * [Profile.bareUserWaypoints].
     */
    private val expected = mapOf(
        2800L to listOf("GNX 375/GPS 175/GNC 355", 5, 4, 5, 10, 5, 0xdc0, 100, false, false),
        1026L to listOf("GTN 6xx/7xx", 5, 4, 5, 10, 5, 0xdc0, 99, true, false),
        3188L to listOf("GTN Xi", 5, 4, 5, 10, 5, 0xdc0, 99, true, true),
    )

    @Test
    fun `the table matches its transcription`() {
        assertEquals(
            expected.size,
            Profiles.byProductId.size,
            "a profile was added or removed without updating this test",
        )
        for ((id, want) in expected) {
            val p = Profiles.forProductId(id)
            assertEquals(want[0], p.name, "name for $id")
            assertEquals(want[1], p.waypointNameLen, "waypoint cap for $id")
            assertEquals(want[2], p.airportNameLen, "airport cap for $id")
            assertEquals(want[3], p.airwayNameLen, "airway cap for $id")
            assertEquals(want[4], p.procedureNameLen, "procedure cap for $id")
            assertEquals(want[5], p.transitionNameLen, "transition cap for $id")
            assertEquals(want[6], p.maxRouteLen, "route length for $id")
            assertEquals(want[7], p.maxWaypoints, "waypoint count for $id")
            assertEquals(want[8], p.bareUserWaypoints, "bare user waypoints for $id")
            assertEquals(want[9], p.hyphenSplitsPoint, "hyphen splits a point for $id")
            assertEquals(id, p.productId, "product id for $id")
        }
    }

    /**
     * A published profile carries every cap, which is stricter than [Profile.isValid] on purpose:
     * a cap left at zero would quietly go unchecked.
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
                assertTrue(n > 0, "$what cap unread for $id (${p.name})")
            }
        }
    }

    /** The profile ids must agree with [ProductId], transcribed separately. */
    @Test
    fun `product ids agree with GarminDevices`() {
        assertEquals(ProductId.G2N, Profiles.GPS175.productId)
        assertEquals(ProductId.GTN_6XX_7XX, Profiles.GTN.productId)
        assertEquals(ProductId.GTN_XI, Profiles.GTN_XI.productId)
    }

    @Test
    fun `an unknown device is refused`() {
        for (bad in listOf(0L, 1L, 2021L, 3247L)) {
            runCatching { Profiles.forProductId(bad) }
                .onSuccess { fail("product id $bad must be refused, got $it") }
                .onFailure { assertTrue(it is UnknownDeviceException) }
        }
        runCatching { Profiles.named("gns430") }
            .onSuccess { fail("an unknown name must be refused") }
            .onFailure { assertTrue(it is UnknownDeviceException) }

        val empty = Profile("nothing", productId = 0, waypointNameLen = 0, airportNameLen = 0, maxRouteLen = 0)
        runCatching { buildRoute(empty, departure = Departure("KSFO")) }
            .onSuccess { fail("an invalid profile must be refused") }
            .onFailure { assertTrue(it is UnknownDeviceException) }
        runCatching { encodeUpload(":DA:KSFO", empty) }
            .onSuccess { fail("an invalid profile must be refused") }
            .onFailure { assertTrue(it is UnknownDeviceException) }
    }

    @Test
    fun `every name the picker offers resolves`() {
        for ((n, p) in Profiles.selectable) {
            assertEquals(p, Profiles.named(n), "$n does not resolve to its own entry")
        }
    }

    /**
     * buildRoute must use the selected device's cap, not the widest across profiles. With every
     * profile's caps equal this cannot be shown by a difference, so it is shown by construction.
     */
    @Test
    fun `buildRoute uses the profile cap`() {
        val tight = Profile("tight", 9999, waypointNameLen = 3, airportNameLen = 4, maxRouteLen = 0xdc0)
        val enroute = listOf(UserWaypoint("LOBEL", 48.79, 2.42))

        buildRoute(Profiles.GPS175, departure = Departure("KSFO"), enroute = enroute)

        runCatching { buildRoute(tight, departure = Departure("KSFO"), enroute = enroute) }
            .onSuccess { fail("a name of 5 must be refused when capped at 3") }
            .onFailure { assertTrue(it.message!!.contains("reads 3")) }
    }

    /**
     * Every cap is enforced, one character over and one character at. An over-long [Waypoint]
     * would arrive under a shorter name, often a different real waypoint, and lose the message.
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
                .onSuccess { fail("one character over the $what cap was accepted") }
                .onFailure {
                    assertTrue(it.message!!.contains(what),
                        "the error should name the $what: ${it.message}")
                    assertTrue((it as FlightPlanException).problem
                        is Problem.ElementIdentTooLong, "the problem should be typed")
                }
            fits()
        }
    }

    /**
     * A cap left at zero has not been read for that device and is not enforced, so a working
     * profile keeps working when a new cap cannot be read for every model at once.
     */
    @Test
    fun `a cap that was never read is not enforced`() {
        val partial = Profile(
            "read for the old caps only", productId = 9999,
            waypointNameLen = 5, airportNameLen = 4, maxRouteLen = 0xdc0,
        )
        buildRoute(partial, Departure("KSFO", "AN-ABSURDLY-LONG-PROCEDURE"),
            listOf(Airway("ALSO-FAR-TOO-LONG")), Arrival("KLAS"))

        // The caps that were read still are, so this is not a way to opt out.
        runCatching {
            buildRoute(partial, Departure("KSFO"), listOf(Waypoint("ABCDEF")), Arrival("KLAS"))
        }.onSuccess { fail("a cap that was read must still be enforced") }
    }

    /**
     * The navigator's flight plan capacity, which nothing else catches: 100 points fit well inside
     * the 3520 byte route limit.
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
            hundred.length <= Profiles.GPS175.maxRouteLen,
            "100 points must stay inside the byte limit, or this no longer isolates the count",
        )

        runCatching { route(101) }
            .onSuccess { fail("101 waypoints must be refused") }
            .onFailure {
                assertTrue(
                    (it as FlightPlanException).problem is Problem.TooManyWaypoints,
                    "the problem should be typed",
                )
            }

        // A GTN holds one fewer from an import, which an upload is: its guide, 190-01007-03
        // Rev. R, section 4.5, truncates an imported plan at 99, and the GTN Xi's, 190-02327-03
        // Rev. G, one over 99.
        for (p in listOf(Profiles.GTN, Profiles.GTN_XI)) {
            fun gtnRoute(n: Int) = buildRoute(
                p,
                Departure("LFPL"),
                (0 until n - 2).map { Coordinate(48.0 + it / 600.0, 2.0) },
                Arrival("LFPK"),
            )
            gtnRoute(99)
            runCatching { gtnRoute(100) }
                .onSuccess {
                    fail("100 waypoints must be refused on the ${p.name}")
                }
                .onFailure {
                    assertTrue(
                        (it as FlightPlanException).problem is Problem.TooManyWaypoints,
                        "the problem should be typed",
                    )
                }
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
