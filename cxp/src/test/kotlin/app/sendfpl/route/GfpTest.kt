package app.sendfpl.route

import app.sendfpl.cxp.Airway
import app.sendfpl.cxp.Approach
import app.sendfpl.cxp.Arrival
import app.sendfpl.cxp.Coordinate
import app.sendfpl.cxp.Departure
import app.sendfpl.cxp.Profiles
import app.sendfpl.cxp.UserWaypoint
import app.sendfpl.cxp.Waypoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Garmin's own published vectors for the flight plan string. The routes cited by section are
 * quoted from "Guideline for GTN Flight Plan and User Waypoint Files", 190-01007-F0 Rev 1, the
 * only test material here that comes from a document rather than from reverse engineering.
 */
class GfpTest {

    /** Section 2.5, the two complete flight plans. */
    private val departureAndApproach =
        "FPN/RI:DA:KPDX:D:LAVAA5.YKM:R:10R:F:YKM.V448.GEG.V204.HQM:F:SEA,N47261W122186" +
            ":AA:KHIO:A:HELNS5.SEA(13O):AP:R13"

    private val userWaypointsAndApproach =
        "FPN/RI:F:KSLE:F:N45223W121419:F:N42568W122067:AA:KSLE:AP:I31.JAIME"

    /**
     * What re-rendering each published plan produces, where it is not the plan itself. Each entry
     * is a measured difference between the document, a GTN reading a card, and what a GPS 175
     * accepts over Connext. A divergence not listed here fails the test.
     */
    private val knownDivergences = mapOf(
        // Each airport is repeated as a `:F:` fix: `:DA:` and `:AA:` alone put no waypoint in the
        // plan, so dropping the fix would drop the airport.
        departureAndApproach to
            "FPN/RI:DA:KPDX:D:LAVAA5.YKM:R:10R:F:KPDX:F:YKM.V448.GEG.V204.HQM" +
                ":F:SEA,N47261W122186:F:KHIO:AA:KHIO:A:HELNS5.SEA(13O):AP:R13",
        // The same `:F:` fix, before `:AA:KSLE`.
        userWaypointsAndApproach to
            "FPN/RI:F:KSLE:F:N45223W121419:F:N42568W122067:F:KSLE:AA:KSLE:AP:I31.JAIME",
    )

    /**
     * On both models: the document writes a user waypoint without a name and a database waypoint
     * with one, which is the split a GTN needs.
     */
    @Test
    fun `Garmin's published flight plans round trip`() {
        for (profile in listOf(Profiles.GPS175, Profiles.GTN)) {
            for (published in listOf(departureAndApproach, userWaypointsAndApproach)) {
                val rendered = Gfp.parse(published).render(profile)
                assertEquals(
                    "${profile.name}: $published",
                    knownDivergences[published] ?: published,
                    rendered,
                )
            }
        }
    }

    @Test
    fun `a departure procedure, its transition and its runway`() {
        // Section 2.3.1: "LAVAA5 departure from runway 10R at KPDX via YKM".
        val d = Gfp.parse("FPN/RI:DA:KPDX:D:LAVAA5.YKM:R:10R:F:KPDX:F:UBG").departure!!
        assertEquals("KPDX", d.airport)
        assertEquals("LAVAA5", d.procedure)
        assertEquals("YKM", d.transition)
        assertEquals("10R", d.runway)
    }

    @Test
    fun `an arrival runway in parentheses is the arrival's runway`() {
        // Section 2.3.2: "BASET3 arrival to runway 06R at KLAX via HEC". A `:R:` of its own after
        // the arrival reads the same, for a file that spells the runway that way.
        val parenthesised = Gfp.parse("FPN/RI:F:KSFO:AA:KLAX:A:BASET3.HEC(06R)").arrival!!
        assertEquals("KLAX", parenthesised.airport)
        assertEquals("BASET3", parenthesised.procedure)
        assertEquals("HEC", parenthesised.transition)
        assertEquals("06R", parenthesised.runway)

        val separate = Gfp.parse("FPN/RI:F:KSFO:AA:KLAX:A:BASET3.HEC:R:06R").arrival!!
        assertEquals(parenthesised, separate)
    }

    @Test
    fun `an arrival with no runway at all`() {
        // Section 2.3.2: "DOWNE4 arrival to KLAX via TNP", runway excluded because it applies to
        // all of them.
        val a = Gfp.parse("FPN/RI:F:KSFO:AA:KLAX:A:DOWNE4.TNP").arrival!!
        assertEquals("DOWNE4", a.procedure)
        assertEquals("TNP", a.transition)
        assertNull(a.runway)
    }

    @Test
    fun `approaches, with a transition and with vectors to final`() {
        // Section 2.3.3. An approach with no transition is loaded with vectors to final.
        val viaMerma = Gfp.parse("FPN/RI:F:KSFO:AA:KLAX:AP:I07R.MERMA").approach!!
        assertEquals("I07R", viaMerma.procedure)
        assertEquals("MERMA", viaMerma.transition)

        val vectors = Gfp.parse("FPN/RI:F:KSFO:AA:KPDX:AP:S28R").approach!!
        assertEquals("S28R", vectors.procedure)
        assertNull(vectors.transition)
    }

    /**
     * What the route box cannot show is flagged so the app can warn before it is lost: a `.gfp`
     * can carry procedures, a runway and an approach, and [RouteParser] builds none of them.
     */
    @Test
    fun `a route carrying procedures says so, and a plain one does not`() {
        assertTrue(Gfp.parse(departureAndApproach).carriesProcedures)
        assertTrue(Gfp.parse("FPN/RI:F:KSFO:AA:KLAX:AP:I07R.MERMA").carriesProcedures)
        assertTrue(Gfp.parse("FPN/RI:DA:KPDX:R:10R:F:KPDX:F:UBG").carriesProcedures)

        // A bare list of fixes is exactly what the box holds, so there is nothing to warn about.
        assertFalse(Gfp.parse("FPN/RI:F:KSFO:F:SAC:F:.V334:F:LIN:F:KLAS").carriesProcedures)
        // An `:AA:` naming an aerodrome and nothing else is not a procedure: it carries no
        // element the route box would lose, and the box shows that airport anyway.
        assertFalse(Gfp.parse("FPN/RI:F:KSLE:F:UBG:AA:KSLE").carriesProcedures)
    }

    @Test
    fun `a database waypoint keeps its identifier, with or without a position`() {
        // Section 2.4.1. UBG is a VOR the database knows, so it needs no position; KSLE carries
        // one because a duplicate identifier would otherwise be ambiguous. Either way it is a
        // database waypoint, so a GTN, which names user waypoints itself, is sent the name too.
        val plan = "FPN/RI:F:KSLE,N44546W123001:F:UBG:F:DIPER,N50208E002037"
        val route = Gfp.parse(plan)
        assertEquals("N44546W123001", (route.enroute[0] as Waypoint).at!!.ident)
        assertNull((route.enroute[1] as Waypoint).at)
        assertEquals(plan, route.render(Profiles.GPS175))
        assertEquals(plan, route.render(Profiles.GTN))

        // The box shows the names, and keeps the positions for when it is read back.
        assertEquals(listOf("KSLE", "UBG", "DIPER"), route.identifiers)
        val reparsed = RouteParser.parse(route.identifiers.joinToString(" "), route.placed)
        assertEquals(plan, reparsed.render(Profiles.GPS175))
        assertEquals(plan, reparsed.render(Profiles.GTN))
    }

    @Test
    fun `a user waypoint is a bare position, in every hemisphere`() {
        // Section 2.4.2, all three examples. "User defined waypoints are specified without a name."
        val route = Gfp.parse("FPN/RI:F:N44124W122451:F:N14544W017479:F:S31240E136502")
        assertTrue(route.enroute.all { it is Coordinate })
        assertEquals(
            listOf("N44124W122451", "N14544W017479", "S31240E136502"),
            route.identifiers,
        )
        // And it survives being written back out, which is the property that matters: the wire
        // form is what the navigator quantises to, so a position that changed here would be a
        // different place.
        assertEquals(
            "FPN/RI:F:N44124W122451:F:N14544W017479:F:S31240E136502",
            route.render(Profiles.GPS175),
        )
    }

    @Test
    fun `an airway chain alternates waypoint and airway`() {
        // Section 2.4.3: "Jet Airway J126 with entry waypoint UBG and exit waypoint RBL".
        val single = Gfp.parse("FPN/RI:F:UBG.J126.RBL")
        assertEquals(listOf("UBG", "J126", "RBL"), single.identifiers)
        assertTrue(single.enroute[1] is Airway)

        // "Entry to Jet Airway T306 at LAX to ELP followed by J183 to CLL followed by V194 to MEI".
        val chained = Gfp.parse("FPN/RI:F:LAX.T306.ELP.J183.CLL.V194.MEI")
        assertEquals(
            listOf("LAX", "T306", "ELP", "J183", "CLL", "V194", "MEI"),
            chained.identifiers,
        )
    }

    @Test
    fun `an airway leg may carry its entry and exit positions`() {
        // Section 2.4.3: "Airway C1318 with entry waypoint LAX and exit waypoint ELKEY, with
        // optional latitude and longitude".
        val route = Gfp.parse("FPN/RI:F:LAX,N33560W118259.C1318.ELKEY,N32410W122031")
        // C1318 has four digits, which the route box's airway heuristic does not take for an
        // airway, so the box is told outright: without the `!` the first edit would turn the airway
        // into a waypoint named C1318 and the leg would be flown direct.
        assertEquals(listOf("LAX", "!C1318", "ELKEY"), route.identifiers)
        // LAX comes back from the box as an aerodrome, being three letters at the start, and with
        // the position it carried: its fix is where the airway is joined either way.
        val reparsed = RouteParser.parse(route.identifiers.joinToString(" "), route.placed)
        assertTrue(reparsed.enroute.any { it is Airway })
        for (profile in listOf(Profiles.GPS175, Profiles.GTN)) {
            assertEquals(
                "FPN/RI:F:LAX,N33560W118259.C1318.ELKEY,N32410W122031",
                reparsed.render(profile),
            )
        }
    }

    @Test
    fun `a waypoint the route box would read as an airway is marked`() {
        // E9 is a point here, by the grammar's own word, and the box's airway heuristic would
        // take it for an airway. The box is told, and an edit keeps it a point.
        val route = Gfp.parse("FPN/RI:F:LFPT:F:E9:F:LFPN")
        assertEquals(listOf("LFPT", "=E9", "LFPN"), route.identifiers)
        val reparsed = RouteParser.parse(route.identifiers.joinToString(" "))
        assertTrue(reparsed.enroute.single() is Waypoint)
        assertEquals(route.render(Profiles.GPS175), reparsed.render(Profiles.GPS175))
    }

    @Test
    fun `an aerodrome named twice folds its position back onto the aerodrome`() {
        // What the encoder writes for a departure with a procedure and a position: `:DA:` names
        // the aerodrome for the procedure, and its `:F:` fix carries the position.
        val sent = "FPN/RI:DA:LFPN:D:AGOP2A:R:25R:F:LFPN,N48451E002064:F:PMN:F:LFPL"
        val route = Gfp.parse(sent)
        assertEquals("AGOP2A", route.departure!!.procedure)
        assertEquals("N48451E002064", route.departure!!.at!!.ident)
        assertEquals(listOf("PMN", "LFPL"), route.enroute.map { (it as Waypoint).ident })
        assertEquals(sent, route.render(Profiles.GPS175))
    }

    @Test
    fun `the old standalone airway still reads back as an airway, and re-renders chained`() {
        // A GPS 175 drops a standalone `.V334` as an unknown waypoint, but a `.gfp` that spells an
        // airway that way still reads back. The writer emits only the chained form.
        val route = Gfp.parse("FPN/RI:F:SAC:F:.V334:F:LIN")
        assertEquals(listOf("SAC", "V334", "LIN"), route.identifiers)
        assertTrue(route.enroute[1] is Airway)
        assertEquals("FPN/RI:F:SAC.V334.LIN", route.render(Profiles.GPS175))
    }

    @Test
    fun `a navigator's own FPN slash RP is read`() {
        // A GPS 175 sends exactly this on CXP id 0x10005004 when asked for its flight plan.
        val route = Gfp.parse("FPN/RP:F:KSFO:F:KLAS")
        assertEquals(listOf("KSFO", "KLAS"), route.identifiers)
    }

    @Test
    fun `what is not a Garmin flight plan is refused by name`() {
        for ((text, why) in mapOf(
            "" to "empty",
            "KSFO KLAS" to "starts with",
            "FPN/RI" to "not followed by",
            "FPN/RI:F:KSFO:Z:X:F:KLAS" to "not a flight plan element",
            "FPN/RI:F:KSFO:F:" to "empty :F:",
            "FPN/RI:F:KSFO:F:A.B.C.D:F:KLAS" to "alternate waypoint, airway, waypoint",
            "FPN/RI:F:KSFO:F:NAME,NOTAPOSITION:F:KLAS" to "character position",
        )) {
            val e = runCatching { Gfp.parse(text) }.exceptionOrNull()
            assertTrue("\"$text\" should have been refused", e is RouteParseException)
            assertTrue("\"$text\" gave: ${e!!.message}", e.message!!.contains(why))
        }
    }

    /**
     * Reading a route back and writing it out again changes nothing, over two thousand routes from
     * the whole element vocabulary and a fixed seed: the reader is the encoder's inverse, and what
     * the encoder writes is already a fixed point, a position at the tenths boundary included,
     * since its 600 tenths are carried into the degrees before they are written.
     */
    @Test
    fun `a rendered route reads back to the same string across the whole vocabulary`() {
        val random = kotlin.random.Random(48929)
        val letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        fun ident(max: Int) = (1..random.nextInt(1, max + 1))
            .map { letters[random.nextInt(letters.length)] }.joinToString("")

        repeat(2000) { attempt ->
            // Two points at least, since the reader refuses a single place, and airways only
            // between points, since the encoder refuses one at either end, as Garmin's client does.
            val points = (1..random.nextInt(2, 9)).map {
                when (random.nextInt(3)) {
                    0 -> Waypoint(ident(5))
                    1 -> Coordinate(random.nextDouble(-89.0, 89.0), random.nextDouble(-179.0, 179.0))
                    else -> UserWaypoint(
                        ident(5),
                        random.nextDouble(-89.0, 89.0),
                        random.nextDouble(-179.0, 179.0),
                    )
                }
            }
            val enroute = buildList {
                points.forEachIndexed { idx, p ->
                    if (idx > 0 && random.nextInt(4) == 0) {
                        add(Airway(ident(2) + random.nextInt(1, 400)))
                    }
                    add(p)
                }
            }
            // An aerodrome may carry its position, which rides on its `:F:` fix and has to fold
            // back onto the aerodrome rather than reading back as a second point.
            fun somewhere() = if (random.nextBoolean()) null else Coordinate(
                random.nextDouble(-89.0, 89.0),
                random.nextDouble(-179.0, 179.0),
            )
            val departure = if (random.nextBoolean()) null else Departure(
                airport = ident(4),
                procedure = if (random.nextBoolean()) ident(6) else null,
                transition = if (random.nextBoolean()) ident(5) else null,
                runway = if (random.nextBoolean()) {
                    random.nextInt(1, 37).toString().padStart(2, '0') + "LRCO"[random.nextInt(4)]
                } else null,
                at = somewhere(),
            )
            val arrival = if (random.nextBoolean()) null else Arrival(
                airport = ident(4),
                procedure = if (random.nextBoolean()) ident(6) else null,
                transition = if (random.nextBoolean()) ident(5) else null,
                at = somewhere(),
            )
            // An approach needs its aerodrome, so only a route with an arrival has one.
            val approach =
                if (arrival == null || random.nextBoolean()) null
                else Approach(ident(4), if (random.nextBoolean()) ident(5) else null)

            val once = ParsedRoute(departure, enroute, arrival, approach).render(Profiles.GPS175)
            val twice = Gfp.parse(once).render(Profiles.GPS175)
            assertEquals("attempt $attempt", once, twice)
        }
    }

    @Test
    fun `a position at the tenths boundary carries into the degrees`() {
        // 2 degrees 59.98 minutes is 600 tenths after rounding, which go out as the next degree:
        // a GPS 175 stores Garmin's own spelling, 600 tenths, at 59.9 minutes. That spelling
        // reads back as 3 degrees, so a file carrying it is written out carried too.
        val once = ParsedRoute(
            enroute = listOf(Coordinate(49.2619444, 2.9997222), Coordinate(48.0, 3.0)),
        ).render(Profiles.GPS175)
        assertEquals("FPN/RI:F:N49157E003000:F:N48000E003000", once)

        val garmins = Gfp.parse("FPN/RI:F:N49157E002600:F:N48000E003000").render(Profiles.GPS175)
        assertEquals(once, garmins)
        assertEquals(once, Gfp.parse(once).render(Profiles.GPS175))
    }

    @Test
    fun `a plan is the first line, and the rest of the file is not read`() {
        // Garmin's rule: a GTN reads the first line and treats anything after it as a reason the
        // import may fail.
        val route = Gfp.parse("FPN/RI:F:KSFO:F:KLAS\nFPN/RI:F:EGLL:F:EGPH\n")
        assertEquals(listOf("KSFO", "KLAS"), route.identifiers)
    }
}
