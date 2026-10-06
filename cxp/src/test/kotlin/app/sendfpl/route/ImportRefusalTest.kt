package app.sendfpl.route

import app.sendfpl.cxp.Problem
import app.sendfpl.cxp.Profiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What an import refuses rather than reads short, and how a typed route is split. */
class ImportRefusalTest {

    private fun gpx(vararg points: Pair<String, String>) = """
        <?xml version="1.0" encoding="UTF-8"?>
        <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1"><rte>
        ${points.joinToString("") { (name, at) -> "<rtept $at><name>$name</name></rtept>" }}
        </rte></gpx>
    """.trimIndent().toByteArray()

    private fun problem(file: String, bytes: ByteArray): Problem? =
        (runCatching { RouteImporter.import(file, bytes) }.exceptionOrNull() as? RouteParseException)?.problem

    /** A decimal comma is a position written wrong, not a position left out. */
    @Test
    fun `a position that does not read is refused, not dropped`() {
        val bytes = gpx(
            "LOGNES" to """lat="48.82" lon="2.62"""",
            "CHATEAU" to """lat="48,5" lon="2.5"""",
            "MELUN" to """lat="48.6" lon="2.67"""",
        )
        assertEquals(Problem.UnreadablePosition("48,5"), problem("r.gpx", bytes))
    }

    @Test
    fun `a letter with no decomposition is spelled out rather than dropped`() {
        val route = RouteImporter.import(
            "r.gpx",
            gpx("Großenhain" to """lat="51.3" lon="13.55"""", "Kırklareli" to """lat="41.7" lon="27.2""""),
        )
        assertEquals(listOf("GROSS", "KIRKL"), route.identifiers.map { it.take(5) })
    }

    /** A KML placemark with [coordinates] in the middle of a route. */
    private fun kml(coordinates: String) = """
        <kml xmlns="http://www.opengis.net/kml/2.2"><Document>
        <Placemark><name>KSFO</name><Point><coordinates>-122.4,37.6,0</coordinates></Point></Placemark>
        <Placemark><name>Lobel</name><Point><coordinates>$coordinates</coordinates></Point></Placemark>
        <Placemark><name>KLAS</name><Point><coordinates>-115.2,36.1,0</coordinates></Point></Placemark>
        </Document></kml>
    """.trimIndent().toByteArray()

    /**
     * A point is one tuple of two or three numbers. Read short, a decimal comma lands in range
     * somewhere else, and a point that gives none would go out as a bare name.
     */
    @Test
    fun `a KML point that is not one position is refused`() {
        for (written in listOf("2,87,48,79,0,0", "2.87,48.79,0 2.88,48.8,0", "2.87", "2.87,,48.79", "east,48.79")) {
            assertEquals(written, Problem.UnreadablePosition(written), problem("r.kml", kml(written)))
        }
        // Nothing written is no position.
        assertEquals(
            listOf("KSFO", "LOBEL", "KLAS"),
            RouteImporter.import("r.kml", kml(" ")).identifiers,
        )
    }

    /** A `.pln` with [position] as the middle point's `<WorldPosition>`. */
    private fun pln(position: String) = """
        <SimBase.Document><FlightPlan.FlightPlan>
          <ATCWaypoint id="KSFO"><ATCWaypointType>Airport</ATCWaypointType>
            <WorldPosition>N37° 37' 8.40",W122° 22' 30.00",+000000.00</WorldPosition>
            <ICAO><ICAOIdent>KSFO</ICAOIdent></ICAO></ATCWaypoint>
          <ATCWaypoint id="LOBELISQUE"><ATCWaypointType>User</ATCWaypointType>
            <WorldPosition>$position</WorldPosition></ATCWaypoint>
          <ATCWaypoint id="KLAS"><ATCWaypointType>Airport</ATCWaypointType>
            <WorldPosition>N36° 4' 48.00",W115° 9' 12.00",+000000.00</WorldPosition>
            <ICAO><ICAOIdent>KLAS</ICAOIdent></ICAO></ATCWaypoint>
        </FlightPlan.FlightPlan></SimBase.Document>
    """.trimIndent().toByteArray()

    @Test
    fun `a pln position needs both halves in degrees, minutes and seconds`() {
        for (written in listOf(
            "N48° 47' 29,76\",E002° 52' 9,02\",+002000,00",
            "N48° 47' 29.76\",E2.8692,+002000.00",
            "N48° 47' 29.76\",N48° 47' 29.76\",+002000.00",
            "+002000.00",
        )) {
            assertEquals(written, Problem.UnreadablePosition(written), problem("r.pln", pln(written)))
        }
        assertEquals(
            "FPN/RI:F:KSFO,N37371W122225:F:LOBEL,N48475E002522:F:KLAS,N36048W115092",
            RouteImporter.import("r.pln", pln("N48° 47' 29.76\",E002° 52' 9.02\",+002000.00"))
                .render(Profiles.GPS175),
        )
    }

    @Test
    fun `a SkyDemon route that does not read says so in the pilot's language`() {
        val flightplan = """
            <DivelementsFlightPlanner>
              <PrimaryRoute CourseType="GreatCircle" Start="N495619.90 E0090345.20">
                <RhumbLineRoute To="N495837,00 E0090308.00" Level="MSL" />
              </PrimaryRoute>
            </DivelementsFlightPlanner>
        """.trimIndent()
        assertEquals(
            Problem.UnreadablePosition("N495837,00 E0090308.00"),
            problem("r.flightplan", flightplan.toByteArray()),
        )
        assertEquals(
            Problem.TooFewPoints,
            problem("r.flightplan", "<DivelementsFlightPlanner><Aircraft /></DivelementsFlightPlanner>".toByteArray()),
        )
    }

    /** None of the formats has one, and a document type is how an entity bomb gets in. */
    @Test
    fun `a document that declares a type is refused`() {
        val bytes = """
            <?xml version="1.0"?>
            <!DOCTYPE gpx [<!ENTITY a "aaaaaaaaaa">]>
            <gpx><rte><rtept lat="1" lon="1"><name>&a;</name></rtept></rte></gpx>
        """.trimIndent().toByteArray()
        assertEquals(Problem.UnrecognisedFile, problem("r.gpx", bytes))
    }

    /**
     * In the encoding the parser reads. Xerces refuses these by itself, as [Problem.NotXml], so the
     * problem shows that the guard, which is all a phone's parser has, caught them first.
     */
    @Test
    fun `a document type is refused in UTF-16 and in a declared encoding`() {
        val body = """
            <!DOCTYPE gpx [<!ENTITY a "aaaaaaaaaa">]>
            <gpx><rte><rtept lat="1" lon="1"><name>&a;</name></rtept>
            <rtept lat="2" lon="2"><name>B</name></rtept></rte></gpx>
        """.trimIndent()
        val bom = byteArrayOf(0xff.toByte(), 0xfe.toByte())
        val utf16 = "<?xml version=\"1.0\" encoding=\"UTF-16\"?>\n$body"
        assertEquals(Problem.UnrecognisedFile, problem("r.gpx", bom + utf16.toByteArray(Charsets.UTF_16LE)))
        assertEquals(Problem.UnrecognisedFile, problem("r.gpx", utf16.toByteArray(Charsets.UTF_16BE)))

        // An ASCII declaration naming EBCDIC, which does not write `<!DOCTYPE` in ASCII at all.
        val ebcdic = java.nio.charset.Charset.forName("IBM037")
        val declared = "<?xml version=\"1.0\" encoding=\"IBM037\"?>".toByteArray(Charsets.US_ASCII) +
            "\n$body".toByteArray(ebcdic)
        assertEquals(Problem.UnrecognisedFile, problem("r.gpx", declared))
    }

    /** What a `.gfp` holds that this cannot send, each refused by name. */
    @Test
    fun `a gfp it cannot send is refused in the pilot's language`() {
        fun gfp(text: String) = (runCatching { Gfp.parse(text) }.exceptionOrNull() as? RouteParseException)?.problem

        // A GPS 175 imports this without the approach, which needs its aerodrome in an :AA:.
        assertEquals(Problem.ApproachWithoutArrival, gfp("FPN/RI:F:LFPL:F:PMN:F:LFPN:AP:I25R"))
        assertEquals(Problem.NotARunway("XYZ"), gfp("FPN/RI:DA:LFPL:R:XYZ:F:LFPL:F:PMN"))
        assertEquals(Problem.NotARunway("37"), gfp("FPN/RI:DA:LFPL:R:37:F:LFPL:F:PMN"))
        assertEquals(Problem.NotARunway("27X"), gfp("FPN/RI:F:LFPO:AA:LFPG:A:MOU9W.MOU(27X)"))
        assertEquals(Problem.NotARunway("(2-7)"), gfp("FPN/RI:F:LFPO:AA:LFPG:A:MOU9W.MOU(2-7)"))
        // Garmin's own direct leg, which is not an airway with no name.
        assertEquals(
            Problem.DirectLeg,
            gfp("FPN/RI:F:LFLL,N45435E005049..MANAK,N46128W000565.UT183.TIRAV,N46359W001234"),
        )
        // What it does read, it still reads.
        assertEquals(
            "FPN/RI:F:LFPO:F:LFPG:AA:LFPG:A:MOU9W.MOU(27R)",
            Gfp.parse("FPN/RI:F:LFPO:AA:LFPG:A:MOU9W.MOU(27R)").render(Profiles.GPS175),
        )
    }

    @Test
    fun `an empty departure procedure is no procedure`() {
        val route = Gfp.parse("FPN/RI:DA:KPDX:D::F:KPDX:F:KSEA")
        assertEquals("FPN/RI:F:KPDX:F:KSEA", route.render(Profiles.GPS175))
    }

    @Test
    fun `a gfp position is the wire form only`() {
        val route = Gfp.parse("FPN/RI:F:LFPL:F:N48492E002372:F:LFPK")
        assertTrue(route.render(Profiles.GPS175).contains(":F:N48492E002372"))
        assertFalse(
            "a position in another order is not read as one",
            runCatching { Gfp.parse("FPN/RI:F:LFPL:F:48492N002372E:F:LFPK").render(Profiles.GPS175) }
                .getOrNull().orEmpty().contains(":F:N48492E002372"),
        )
    }

    @Test
    fun `the route box splits on any space`() {
        assertEquals(
            listOf("LFPL", "PMN", "LFPK"),
            RouteParser.parse("LFPL PMN LFPK").identifiers,
        )
    }
}
