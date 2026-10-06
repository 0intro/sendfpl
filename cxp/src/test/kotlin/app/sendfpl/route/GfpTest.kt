package app.sendfpl.route

import app.sendfpl.cxp.Airway
import app.sendfpl.cxp.Approach
import app.sendfpl.cxp.Arrival
import app.sendfpl.cxp.Departure
import app.sendfpl.cxp.Coordinate
import app.sendfpl.cxp.Profiles
import app.sendfpl.cxp.UserWaypoint
import app.sendfpl.cxp.Waypoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Garmin's own published vectors for the flight plan string.
 *
 * Every route below is quoted from *Guideline for GTN Flight Plan and User Waypoint Files*,
 * 190-01007-F0 Rev 1, which Garmin publishes for end users who want to build a flight plan file by
 * hand. It is the only specification in this project that came from a document rather than from a
 * decompiler, a capture or a second build of the same library, and it is the only test material
 * here that owes nothing to the reverse engineering.
 *
 * The same strings are pinned by hand in the reference implementation, and a change to either has
 * to be made in both.
 */
class GfpTest {

    /** Section 2.5, the two complete flight plans. */
    private val departureAndApproach =
        "FPN/RI:DA:KPDX:D:LAVAA5.YKM:R:10R:F:YKM.V448.GEG.V204.HQM:F:SEA,N47261W122186" +
            ":AA:KHIO:A:HELNS5.SEA(13O):AP:R13"

    private val userWaypointsAndApproach =
        "FPN/RI:F:KSLE:F:N45223W121419:F:N42568W122067:AA:KSLE:AP:I31.JAIME"

    /**
     * What re-rendering each published plan produces, where it is not the plan itself.
     *
     * **An entry here is a decision on the record, not a nuisance.** Each is a measured difference
     * between what Garmin's document describes, which is a GTN reading a card, and what this
     * project measured a GPS 175 accepting over Connext. A divergence that is *not* listed fails
     * this test, which is the point: the encoder cannot drift silently away from the document.
     */
    private val knownDivergences = mapOf(
        // **One difference left, and it is not a spelling.** Two that used to be here are gone,
        // both because a GPS 175 was asked rather than reasoned about.
        //
        // The airway chain matches the document exactly now, `YKM.V448.GEG.V204.HQM` in one
        // element: the standalone `:F:.V448` this encoder used to write was answered "flight plan
        // contains unknown waypoints", with the point between the endpoints missing.
        //
        // The arrival runway matches too, `(13O)` on the arrival: a `:R:` of its own was answered
        // "unknown procedure removed from imported flight plan", which costs the procedure and not
        // merely the runway.
        //
        // What remains is that every airport is repeated as a `:F:` fix here, and that is not a
        // style choice: `:DA:` and `:AA:` alone put no waypoint in the plan, so dropping the fix
        // would drop the airport from the flight plan.
        departureAndApproach to
            "FPN/RI:DA:KPDX:D:LAVAA5.YKM:R:10R:F:KPDX:F:YKM.V448.GEG.V204.HQM" +
                ":F:SEA,N47261W122186:F:KHIO:AA:KHIO:A:HELNS5.SEA(13O):AP:R13",
        // **The hardware answered, and the document was right.** This entry used to record that
        // the encoder dropped `:AA:` before an approach, on the grounds that it emitted that
        // element only when a procedure or a runway needed carrying. A GPS 175 was sent
        // `FPN/RI:F:LFPL:F:PMN:F:LFPN:AP:I25R` and imported `LFPL PMN LFPN` with **no approach at
        // all**: accepted, no refusal, and quietly missing the thing that was asked for. So the
        // aerodrome is named now whenever an approach is present, and only the `:F:` remains.
        //
        // That `:F:` is not a style choice: `:AA:` alone puts no waypoint in the plan, measured on
        // hardware, so dropping the fix would drop the airport from the flight plan.
        userWaypointsAndApproach to
            "FPN/RI:F:KSLE:F:N45223W121419:F:N42568W122067:F:KSLE:AA:KSLE:AP:I31.JAIME",
    )

    /**
     * On both models, and that is a property of the document: it writes a user waypoint without a
     * name and a database waypoint with one, which is exactly the split a GTN needs. `SEA` keeps
     * its name and position on a GTN, the way `KSLE` keeps its name.
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
        // Section 2.3.2: "BASET3 arrival to runway 06R at KLAX via HEC". The runway rides on the
        // arrival element here and on a :R: of its own in what the encoder writes, and both have
        // to mean the same thing.
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
     * What the route box cannot show, flagged so the app can say so before it is lost.
     *
     * `identifiers` is a list of fixes, because that is what a typed route is. A `.gfp` can carry
     * a departure procedure, a transition, a runway and an approach, none of which [RouteParser]
     * can build, so editing the box silently rebuilds the route without them. The flag is what
     * the home screen warns from.
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
        // The shape this project emitted until a GPS 175 refused it. A `.gfp` written by an older
        // build, or by anything else that made the same mistake, still has to read back as what it
        // was: the reader takes both spellings and the writer emits only the one that works.
        val route = Gfp.parse("FPN/RI:F:SAC:F:.V334:F:LIN")
        assertEquals(listOf("SAC", "V334", "LIN"), route.identifiers)
        assertTrue(route.enroute[1] is Airway)
        assertEquals("FPN/RI:F:SAC.V334.LIN", route.render(Profiles.GPS175))
    }

    @Test
    fun `a navigator's own FPN slash RP is read`() {
        // A GPS 175 volunteered exactly this on CXP id 0x10005004 when asked for its flight plan.
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
     * Reading a route back and writing it out again changes nothing.
     *
     * Two thousand routes built from the whole element vocabulary, from a fixed seed so a failure
     * is reproducible. This is the invariant the reader exists to satisfy, and it is worth more
     * than any number of further examples: it says the reader is the encoder's inverse rather than
     * merely agreeing with it on the cases somebody thought of.
     *
     * **Idempotence rather than equality**, and the difference is a real property of the wire form
     * rather than a weakening to make the test pass. A position whose tenths of a minute round to
     * 600 is left that way by the encoder, deliberately, because Garmin's encoder does the same;
     * their decoder then reads 600 tenths as a whole degree, since 600 / 600 is 1. So
     * `N49157E002600` reads back as 2 degrees exactly and writes out as `N49157E003000`: the same
     * place said the other way. One application settles it, and the test asserts exactly that
     * rather than an equality that does not hold.
     */
    @Test
    fun `parsing and rendering is idempotent across the whole vocabulary`() {
        val random = kotlin.random.Random(48929)
        val letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        fun ident(max: Int) = (1..random.nextInt(1, max + 1))
            .map { letters[random.nextInt(letters.length)] }.joinToString("")

        repeat(2000) { attempt ->
            // Two points at least. The encoder will happily build `FPN/RI:F:KSFO`, and the reader
            // refuses it for the same reason the route box does: one place is not a navigation.
            // Points first, then an airway is offered *between* two of them. An airway at
            // either end of the route names no leg and the encoder refuses it, the way Garmin's
            // own client does, so generating one there would be generating an invalid route
            // rather than testing the round trip.
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
                runway = if (random.nextBoolean()) "%02d".format(random.nextInt(1, 37)) + "LRCO"
                    [random.nextInt(4)] else null,
                at = somewhere(),
            )
            val arrival = if (random.nextBoolean()) null else Arrival(
                airport = ident(4),
                procedure = if (random.nextBoolean()) ident(6) else null,
                transition = if (random.nextBoolean()) ident(5) else null,
                at = somewhere(),
            )
            val approach =
                if (random.nextBoolean()) null
                else Approach(ident(4), if (random.nextBoolean()) ident(5) else null)

            val once = ParsedRoute(departure, enroute, arrival, approach).render(Profiles.GPS175)
            val twice = Gfp.parse(once).render(Profiles.GPS175)
            val thrice = Gfp.parse(twice).render(Profiles.GPS175)
            assertEquals("attempt $attempt, from $once", twice, thrice)
        }
    }

    @Test
    fun `a position at the tenths boundary is the one thing that moves`() {
        // 2 degrees 59.98 minutes is 600 tenths after rounding, and the encoder does not carry
        // that into the degrees. The decoder does, because 600 / 600 is a whole degree. Both
        // spellings are the same place and the navigator reads them alike, and this is the whole
        // of the difference between "renders back identically" and "is idempotent".
        val once = ParsedRoute(
            enroute = listOf(Coordinate(49.2619444, 2.9997222), Coordinate(48.0, 3.0)),
        ).render(Profiles.GPS175)
        assertEquals("FPN/RI:F:N49157E002600:F:N48000E003000", once)

        val twice = Gfp.parse(once).render(Profiles.GPS175)
        assertEquals("FPN/RI:F:N49157E003000:F:N48000E003000", twice)
        assertEquals(twice, Gfp.parse(twice).render(Profiles.GPS175))
    }

    @Test
    fun `a plan is the first line, and the rest of the file is not read`() {
        // Garmin's rule, and it is theirs rather than a convenience: a GTN reads the first line
        // and treats anything after it as a reason the import may fail.
        val route = Gfp.parse("FPN/RI:F:KSFO:F:KLAS\nFPN/RI:F:EGLL:F:EGPH\n")
        assertEquals(listOf("KSFO", "KLAS"), route.identifiers)
    }
}
