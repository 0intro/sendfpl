package app.sendfpl.cxp

import app.sendfpl.route.RouteParser
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * What the builder refuses rather than sends, so the navigator's parser never meets it. A reference
 * implementation asserts the same cases and the same strings.
 */
class IdentifierTest {

    private fun refused(build: () -> String): Problem? = try {
        fail("built ${build()}")
        null
    } catch (e: FlightPlanException) {
        e.problem
    }

    @Test
    fun `an identifier the token reader would stop inside is refused`() {
        assertEquals(Problem.EmptyIdentifier, refused { buildRoute(Profiles.GPS175, enroute = listOf(Waypoint(""))) })
        for (name in listOf("PM:N", "PM.N", "PM,N", "PMN(", "pmn")) {
            assertEquals(name, Problem.BadIdentifier(name), refused {
                buildRoute(Profiles.GPS175, enroute = listOf(Waypoint(name)))
            })
        }
        assertEquals(Problem.EmptyIdentifier, refused {
            buildRoute(Profiles.GPS175, enroute = listOf(Waypoint("PMN"), Airway(""), Waypoint("MLN")))
        })
        assertEquals(Problem.EmptyIdentifier, refused { buildRoute(Profiles.GPS175, departure = Departure("")) })
        assertEquals(Problem.BadIdentifier("LF G"), refused { buildRoute(Profiles.GPS175, arrival = Arrival("LF G")) })
        assertEquals(Problem.BadIdentifier("MOU/9W"), refused {
            buildRoute(Profiles.GPS175, arrival = Arrival("LFPG", procedure = "MOU/9W"))
        })
        assertEquals(Problem.WaypointNameNotAlphanumeric("lobel"), refused {
            buildRoute(Profiles.GPS175, enroute = listOf(UserWaypoint("lobel", 48.8, 2.9)))
        })
    }

    @Test
    fun `an airway needs a waypoint on each side`() {
        for ((route, problem) in listOf(
            "LFPL PMN UL975" to Problem.AirwayWithoutExit("UL975"),
            "UL975 PMN LFPL" to Problem.AirwayWithoutEntry("UL975"),
            "KSFO SAC V334 J50 LIN KLAS" to Problem.AirwayWithoutExit("V334"),
        )) {
            assertEquals(route, problem, refused { RouteParser.parse(route).render(Profiles.GPS175) })
        }
    }

    @Test
    fun `an approach is nothing without its procedure`() {
        for (approach in listOf(Approach(""), Approach("", transition = "KEPEC"))) {
            val e = runCatching {
                buildRoute(Profiles.GPS175, arrival = Arrival("LFPG"), approach = approach)
            }.exceptionOrNull()
            assertTrue("$approach: got $e", e is FlightPlanException)
        }
    }

    @Test
    fun `a hyphen is part of an identifier and an empty procedure is absent`() {
        assertEquals(
            "FPN/RI:F:LFPL:F:LFPG:AA:LFPG:AP:R16-Y",
            buildRoute(
                Profiles.GPS175,
                departure = Departure("LFPL", procedure = ""),
                arrival = Arrival("LFPG"),
                approach = Approach("R16-Y"),
            ),
        )
    }

    @Test
    fun `an approach needs its arrival aerodrome`() {
        val e = runCatching {
            buildRoute(Profiles.GPS175, departure = Departure("LFPL"), approach = Approach("I25R"))
        }.exceptionOrNull()
        assertTrue("got $e", e is FlightPlanException && "arrival" in e.message!!)
    }

    @Test
    fun `minutes stop at 59, and tenths at 600`() {
        for ((token, ok) in listOf(
            "N4859E00230" to true, "N4860E00230" to false, "N4875E00230" to false,
            "N48600E002300" to true, "N48601E002300" to false,
            "N48300E002600" to true, "N48300E002601" to false,
        )) {
            val read = runCatching { parseLatLon(token) }
            if (ok) assertTrue("$token: ${read.exceptionOrNull()}", read.getOrNull() != null)
            else assertTrue("$token was read", read.exceptionOrNull() is FlightPlanException)
        }
    }

    /** A phone set to Arabic or Persian writes other digits through String.format. */
    @Test
    fun `a runway is written in ASCII digits whatever the phone's locale`() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("fa"))
            assertEquals("26O", normaliseRunway("26"))
            assertEquals("09L", normaliseRunway("9L"))
            assertEquals(
                "FPN/RI:DA:LFPN:R:25R:F:LFPN,N48450E002060",
                buildRoute(Profiles.GPS175, departure = Departure("LFPN", runway = "25R", at = Coordinate(48.75, 2.1))),
            )
        } finally {
            Locale.setDefault(before)
        }
    }
}
