package app.sendfpl.cxp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from the internal tests of the reference implementation these were written against. */
class FlightPlanTest {

    private val full = SupportedElements(
        elements = Element.DEPARTURE or Element.ARRIVAL or Element.APPROACH or
            Element.WPT or Element.AIRWAY_DOT_NOTATION,
        maxTextLength = 512,
        waypointTypes = WaypointType.PUB_FMT or WaypointType.LAT_LON_USER,
    )

    @Test
    fun `capability struct is 7 bytes little endian, matching the reference`() {
        assertEquals("1f000000000203", full.encode().toHex())
        assertEquals(SupportedElements.SIZE, full.encode().size)
        assertEquals(full, SupportedElements.decode(full.encode()))
    }

    @Test
    fun `capability fields are u32 then u16 then u8`() {
        val raw = full.encode()
        assertEquals(full.elements.toLong(), getU32(raw, 0))
        assertEquals(512, getU16(raw, 4))
        assertEquals(full.waypointTypes, raw[6].toInt() and 0xFF)
        assertTrue(full.supports(Element.APPROACH))
        assertFalse(full.supports(Element.HOLD_AT_WPT))
    }

    @Test
    fun `a full route matches the emission order of UDB_export_flightplan`() {
        val route = buildRoute(Profiles.GPS175, departure = Departure("KSFO", "SSTIK3", "MOD", "28R"),
            enroute = listOf(Waypoint("SAC"), Airway("V334"), Waypoint("LIN"), Waypoint("BTY")),
            arrival = Arrival("KLAS", "KEPEC3", "BTY", "26L"),
            approach = Approach("I26L", "KEPEC"),
            capabilities = full,
        )
        assertEquals(
            ROUTE_PREFIX + ":DA:KSFO:D:SSTIK3.MOD:R:28R:F:KSFO" +
                ":F:SAC.V334.LIN:F:BTY" +
                ":F:KLAS:AA:KLAS:A:KEPEC3.BTY(26L)" +
                ":AP:I26L.KEPEC",
            route,
        )
    }

    @Test
    fun `a minimal route omits every optional part`() {
        assertEquals(
            ROUTE_PREFIX + ":F:KSFO:F:SAC:F:KLAS",
            buildRoute(Profiles.GPS175, Departure("KSFO"), listOf(Waypoint("SAC")), Arrival("KLAS")),
        )
    }

    @Test
    fun `unsupported procedure detail is dropped but the airport is kept`() {
        val limited = SupportedElements(elements = Element.WPT, maxTextLength = 512)
        assertEquals(
            ROUTE_PREFIX + ":DA:KSFO:R:28R:F:KSFO:F:SAC:F:KLAS",
            buildRoute(Profiles.GPS175, departure = Departure("KSFO", "SSTIK3", "MOD", "28R"),
                enroute = listOf(Waypoint("SAC")),
                arrival = Arrival("KLAS", "KEPEC3"),
                capabilities = limited,
            ),
        )
    }

    @Test
    fun `unsupported airways and approaches are refused outright`() {
        val limited = SupportedElements(elements = Element.WPT, maxTextLength = 512)
        assertTrue(runCatching {
            buildRoute(Profiles.GPS175, enroute = listOf(Airway("V334")), capabilities = limited)
        }.exceptionOrNull() is FlightPlanException)
        assertTrue(runCatching {
            buildRoute(Profiles.GPS175, approach = Approach("I26L"), capabilities = limited)
        }.exceptionOrNull() is FlightPlanException)
        assertTrue(runCatching { buildRoute(Profiles.GPS175, ) }.exceptionOrNull() is FlightPlanException)
    }

    @Test
    fun `the upload payload is the raw string bytes with no framing`() {
        val route = ROUTE_PREFIX + ":F:KSFO:F:SAC:F:KLAS"
        val payload = encodeUpload(route, Profiles.GPS175, full)
        assertEquals(route.length, payload.size)
        assertEquals(route, decodeUpload(payload))
    }

    @Test
    fun `a route that runs too long is refused against the advertised maximum`() {
        val small = SupportedElements(maxTextLength = 8)
        val e = runCatching { encodeUpload(ROUTE_PREFIX + ":F:KSFO:F:SAC:F:KLAS", Profiles.GPS175, small) }.exceptionOrNull()
        assertTrue(e is FlightPlanException)
        assertTrue(e!!.message!!.contains("maximum of 8"))
    }

    @Test
    fun `anything outside ASCII is refused rather than silently mangled`() {
        assertTrue(runCatching { encodeUpload("KSFO→KLAS", Profiles.GPS175) }
            .exceptionOrNull() is FlightPlanException)
        assertTrue(runCatching { encodeUpload("", Profiles.GPS175) }.exceptionOrNull() is FlightPlanException)
    }

    @Test
    fun `flight plan ids are the values from CxpIdType`() {
        assertEquals(0x10005000L, FplId.SUPPORTED_ELEMENTS)
        assertEquals(0x10005001L, FplId.UPLOAD_TO_AVIONICS)
    }

    // Lat/lon user waypoints.
    //
    // Every string below was produced by the reference implementation and checked equal before
    // being written here. The format itself is one of the few confirmed from two
    // independent binaries: dls_format_posn_to_str in libadb-lib.so, and Garmin Pilot's
    // ARINCDataUtil.formatLatitudeLongitude() in Java.

    @Test
    fun `a coordinate encodes to hemisphere, degrees and tenths of a minute`() {
        assertEquals("N48492E002372", formatLatLon(48.82, 2.62))
        assertEquals(LATLON_LEN, formatLatLon(48.82, 2.62).length)
        assertEquals("S48492W002372", formatLatLon(-48.82, -2.62))
        assertEquals("N00000E000000", formatLatLon(0.0, 0.0))
        assertEquals("N37371W122225", formatLatLon(37.6189, -122.3750))
        assertEquals("N36048W115091", formatLatLon(36.0800, -115.1522))
    }

    @Test
    fun `the tenths field is deliberately not carried into the degrees`() {
        // Garmin's encoder rounds but never clamps to 599, and their decoder reads 600 back as a
        // whole degree (600 / 600 == 1.0). Matching that beats being tidier than the reference.
        assertEquals("N48600E002372", formatLatLon(48.99999, 2.62))
    }

    @Test
    fun `a coordinate renders bare after the tag`() {
        assertEquals(":F:N48492E002372", Coordinate(48.82, 2.62).render())
        assertEquals(
            ROUTE_PREFIX + ":F:KSFO:F:SAC:F:N48492E002372.V334.KLAS",
            buildRoute(Profiles.GPS175, departure = Departure("KSFO"),
                enroute = listOf(Waypoint("SAC"), Coordinate(48.82, 2.62), Airway("V334")),
                arrival = Arrival("KLAS"),
                capabilities = full,
            ),
        )
        assertEquals(
            ROUTE_PREFIX + ":F:SAEZ:F:S34493W058321:F:SCEL",
            buildRoute(Profiles.GPS175, Departure("SAEZ"), listOf(Coordinate(-34.8222, -58.5358)), Arrival("SCEL")),
        )
    }

    @Test
    fun `positions out of range are refused`() {
        assertTrue(runCatching { formatLatLon(91.0, 0.0) }
            .exceptionOrNull() is FlightPlanException)
        assertTrue(runCatching { formatLatLon(0.0, 361.0) }
            .exceptionOrNull() is FlightPlanException)
    }

    @Test
    fun `a coordinate is gated on the waypoint type byte, not the element mask`() {
        // elements says WPT, but waypointTypes is 0, so a plain waypoint is fine and a
        // coordinate is not.
        val noLatLon = SupportedElements(elements = Element.WPT, maxTextLength = 512)
        assertFalse(noLatLon.supportsWaypointType(WaypointType.LAT_LON_USER))
        assertTrue(full.supportsWaypointType(WaypointType.LAT_LON_USER))

        assertEquals(
            ROUTE_PREFIX + ":F:SAC",
            buildRoute(Profiles.GPS175, enroute = listOf(Waypoint("SAC")), capabilities = noLatLon),
        )
        val e = runCatching {
            buildRoute(Profiles.GPS175, enroute = listOf(Coordinate(48.82, 2.62)), capabilities = noLatLon)
        }.exceptionOrNull()
        assertTrue(e is FlightPlanException)
        assertTrue(e!!.message!!.contains("N48492E002372"))
    }

    /**
     * The named user waypoint, pinned to the same literals a reference implementation asserts.
     * No capture and no earlier implementation
     * ever produced this form, so these are hand vectors read off the encoder: `:F:%s` with the
     * name, then `",%s"` with the position.
     */
    @Test
    fun `a named user waypoint carries its position after a comma`() {
        assertEquals(
            ":F:LOBEL,N48475E002522",
            UserWaypoint("LOBEL", 48.791600627042, 2.8691714914193).render(),
        )
        assertEquals(
            ":F:POIN3,N48051E001014",
            UserWaypoint("POIN3", 48.084858287232, 1.0239251659556).render(),
        )
        assertEquals(
            ":F:VILLA,N48504E002492",
            UserWaypoint("VILLA", 48.839535611534, 2.8198509032238).render(),
        )
        // 48.8375 is 502.5 tenths of a minute exactly, so it lands on the boundary where
        // rounding goes half away from zero, except that 48.8375 is not representable, the double
        // is a hair under, and floor(x + 0.5) gives 502 rather than 503. Pinned in both
        // implementations because it is
        // precisely the kind of value two ports drift apart on.
        assertEquals(":F:LFPKC,N48502E003009", UserWaypoint("LFPKC", 48.8375, 3.014444).render())
        // Both halves as one token, so what is echoed back parses back to what will be sent.
        assertEquals(
            "LOBEL,N48475E002522",
            UserWaypoint("LOBEL", 48.791600627042, 2.8691714914193).display,
        )
    }

    /**
     * Six is the interesting boundary, not seven: it is what Garmin's *encoder* validates against
     * and what SD-VFR therefore writes, so it is the length most likely to be waved through. The
     * navigator's parser stops at five and leaves its read pointer on the sixth character, which
     * costs the point its position and marks the whole message malformed.
     */
    @Test
    fun `a user waypoint name the navigator cannot read is refused here`() {
        listOf("LOBEL1", "TOOLONG", "", "LOB-L", "LOB L").forEach { name ->
            assertTrue(
                "\"$name\" should not render",
                runCatching { UserWaypoint(name, 48.82, 2.62).render() }
                    .exceptionOrNull() is FlightPlanException,
            )
        }
        val e = runCatching { UserWaypoint("LOBEL1", 48.82, 2.62).render() }.exceptionOrNull()
        assertTrue(e!!.message!!.contains("6 characters, the navigator reads 5"))
    }

    /** The `:DA:`/`:AA:` handler reads four and leaves the pointer there. */
    @Test
    fun `an airport longer than four characters is refused`() {
        val e = runCatching {
            buildRoute(Profiles.GPS175, departure = Departure("LFPLL"), arrival = Arrival("LFPL"))
        }.exceptionOrNull()
        assertTrue(e is FlightPlanException)
        assertTrue(e!!.message!!.contains("reads 4"))
        assertTrue(e.message!!.contains(Profiles.GPS175.name))
        assertEquals(
            ROUTE_PREFIX + ":F:LFPL:F:LFPL",
            buildRoute(Profiles.GPS175, departure = Departure("LFPL"), arrival = Arrival("LFPL")),
        )
    }

    /** 0xdc0 is a hard reject in the parser, advertised maximum or not. */
    @Test
    fun `a route over the navigator's own limit is refused`() {
        val long = ROUTE_PREFIX + ":F:LFPL" + ":F:ABCDE".repeat(500) + ":F:LFPL"
        assertTrue(long.length > Profiles.GPS175.maxRouteLen)
        val e = runCatching { encodeUpload(long, Profiles.GPS175) }.exceptionOrNull()
        assertTrue(e is FlightPlanException)
        assertTrue(e!!.message!!.contains("rejects over"))
        assertEquals(Profiles.GPS175.maxRouteLen, encodeUpload(long.take(Profiles.GPS175.maxRouteLen), Profiles.GPS175).size)
    }

    @Test
    fun `without LAT_LON_USER a named user waypoint degrades instead of failing`() {
        val noLatLon = SupportedElements(elements = Element.WPT, maxTextLength = 512)
        val point = UserWaypoint("LOBEL", 48.791600627042, 2.8691714914193)

        assertEquals(
            ROUTE_PREFIX + ":F:LOBEL,N48475E002522",
            buildRoute(Profiles.GPS175, enroute = listOf(point), capabilities = full),
        )
        // The name is something the navigator can still resolve, so the position is dropped and
        // the upload goes. A bare coordinate has no such fallback and stays an error.
        assertEquals(ROUTE_PREFIX + ":F:LOBEL", buildRoute(Profiles.GPS175, enroute = listOf(point), capabilities = noLatLon))
        assertTrue(
            runCatching {
                buildRoute(Profiles.GPS175, enroute = listOf(Coordinate(48.82, 2.62)), capabilities = noLatLon)
            }.exceptionOrNull() is FlightPlanException
        )
        // Degrading still screens the name rather than emitting one the encoder would drop.
        assertTrue(
            runCatching {
                buildRoute(Profiles.GPS175, enroute = listOf(UserWaypoint("TOOLONG", 48.82, 2.62)),
                    capabilities = noLatLon,
                )
            }.exceptionOrNull() is FlightPlanException
        )
    }

    @Test
    fun `every accepted notation reads back to the same position`() {
        listOf("N48.82/E2.62", "N48.8200/E2.62000", "48.82N/2.62E", "N48492E002372").forEach {
            assertEquals(it, Coordinate(48.82, 2.62), parseLatLon(it))
        }
        // Whole minutes rather than tenths, so 49.0' exactly, a different position.
        listOf("N4849/E00237", "4849N00237E").forEach {
            assertEquals(it, "N48490E002370", parseLatLon(it)!!.ident)
        }
        assertEquals(Coordinate(-48.82, -2.62), parseLatLon("S48.82/W2.62"))
    }

    @Test
    fun `an identifier that is not a coordinate reads as null, not as a bad coordinate`() {
        listOf("KSFO", "SAC", "V334", "LIN", "N48", "BTY", "KEPEC").forEach {
            assertEquals(it, null, parseLatLon(it))
        }
    }

    @Test
    fun `something shaped like a coordinate but malformed throws instead of a waypoint`() {
        // Seven digits is no notation we know, and demoting it to a waypoint named N4849200 would
        // send the aircraft somewhere quietly wrong.
        assertTrue(runCatching { parseLatLon("N4849200E002372") }
            .exceptionOrNull() is FlightPlanException)
        assertTrue(runCatching { parseLatLon("N48.82N/E2.62") }
            .exceptionOrNull() is FlightPlanException)
    }

    @Test
    fun `normalising folds a typed coordinate into one token and leaves routes alone`() {
        assertEquals(
            "KSFO N48.8200/E2.62000 KLAS",
            normaliseCoordinates("KSFO N48,8200/E2,62000 KLAS"),
        )
        assertEquals("KSFO N48.82/E2.62 KLAS", normaliseCoordinates("KSFO N48.82 E2.62 KLAS"))
        assertEquals("KSFO 48.82N/2.62E KLAS", normaliseCoordinates("KSFO 48,82N,2,62E KLAS"))
        // Ordinary routes must survive untouched, commas and all.
        assertEquals("ksfo, sac , klas", normaliseCoordinates("ksfo, sac , klas"))
        assertEquals("KSFO SAC V334 LIN KLAS", normaliseCoordinates("KSFO SAC V334 LIN KLAS"))
    }

    /**
     * The input bound is ICAO's and the encoder's is Garmin's, and they differ on purpose.
     *
     * The firmware range checks the *truncated* degrees against 360, so [formatLatLon] accepts up
     * to that and stays faithful to what a navigator will take. Nothing east of 180 degrees is a
     * place, so a coordinate somebody typed or a file stated is refused at 180 instead.
     */
    @Test
    fun `a longitude beyond 180 is refused on the way in and accepted on the way out`() {
        val e = runCatching { parseLatLon("N48492E200000") }.exceptionOrNull()
        assertTrue("expected a refusal, got $e", e is FlightPlanException)
        assertTrue(e!!.message!!.contains("outside -180..180"))
        assertTrue(runCatching { parseLatLon("N48.82/W200.5") }.exceptionOrNull()
            is FlightPlanException)

        // 180 exactly is the antimeridian and is a place.
        assertEquals(180.0, parseLatLon("N00000E180000")!!.lon, 1e-9)

        // The encoder keeps the navigator's own bound, so a route already in the wire form
        // round trips whatever this refuses to read from a pilot.
        assertEquals("N89600E359600", formatLatLon(89.9999, 359.9999))
    }

    /**
     * A decimal point sitting further into a token than a degree field reaches is ambiguous, and
     * reading it as plain degrees is silently wrong: `00237.2` is 2 deg 37.2 min, so taking it as
     * 237.2 degrees puts the coordinate below 234 degrees east of where it belongs.
     */
    @Test
    fun `an ambiguous decimal coordinate is refused, not guessed at`() {
        val e = runCatching { parseLatLon("48.5N/00237.2E") }.exceptionOrNull()
        assertTrue("expected a refusal, got $e", e is FlightPlanException)
        assertTrue(e!!.message!!.contains("ambiguous"))

        // The latitude form is loud on its own, 4849.2 being out of range. It is the longitude
        // that is dangerous, because 237.2 was in range for the encoder and uploaded without
        // complaint.
        assertTrue(runCatching { parseLatLon("N4849.2E00237.2") }.exceptionOrNull()
            is FlightPlanException)
    }

    @Test
    fun `the notations that are supported still parse`() {
        // Decimal degrees, whole minutes, and tenths of a minute, the three the error names.
        assertEquals(48.82, parseLatLon("N48.82/E2.62")!!.lat, 1e-9)
        assertEquals(2.62, parseLatLon("N48.82/E2.62")!!.lon, 1e-9)
        assertEquals(48.0 + 49.0 / 60.0, parseLatLon("N4849/E00237")!!.lat, 1e-9)
        assertEquals(48.82, parseLatLon("N48492E002372")!!.lat, 1e-9)
        assertEquals(2.62, parseLatLon("N48492E002372")!!.lon, 1e-9)
    }

    /**
     * A coordinate out of range is refused where the input still has a name, rather than several
     * layers later at encode time. Anything parseLatLon accepts must survive formatLatLon, which
     * is the property that found the defect above.
     */
    @Test
    fun `anything accepted can be encoded`() {
        val tokens = listOf(
            "N48492E002372", "S48492W002372", "N48.82/E2.62", "48.82N/2.62E",
            "N4849/E00237", "N0E0", "N90000E180000",
        )
        tokens.forEach { token ->
            val c = parseLatLon(token) ?: return@forEach
            val encoded = formatLatLon(c.lat, c.lon)
            assertEquals("$token encoded to $encoded", LATLON_LEN, encoded.length)
        }
        assertTrue(runCatching { parseLatLon("N00000E700") }.exceptionOrNull()
            is FlightPlanException)
    }
    /**
     * An approach must be told which aerodrome it belongs to.
     *
     * Settled on hardware. Without `:AA:` the navigator **accepts the route** and imports it with
     * the approach silently absent, which is worse than a refusal because nothing on the wire says
     * so. With it, the ILS 25R loaded and expanded into its legs.
     *
     * A reference implementation asserts the same three cases.
     */
    @Test
    fun `an approach names its aerodrome, and never twice`() {
        assertEquals(
            "FPN/RI:F:LFPL:F:PMN:F:LFPN:AA:LFPN:AP:I25R",
            buildRoute(
                Profiles.GPS175,
                departure = Departure("LFPL"),
                enroute = listOf(Waypoint("PMN")),
                arrival = Arrival("LFPN"),
                approach = Approach("I25R"),
            ),
        )
        // The arrival's own `:AA:` carries its procedure, so the approach must not write a second.
        assertEquals(
            "FPN/RI:F:LFPO:F:LFPG:AA:LFPG:A:MOU9W(27R):AP:I27R",
            buildRoute(
                Profiles.GPS175,
                departure = Departure("LFPO"),
                arrival = Arrival("LFPG", procedure = "MOU9W", runway = "27R"),
                approach = Approach("I27R"),
            ),
        )
        // No approach, and nothing is added.
        assertEquals(
            "FPN/RI:F:LFPL:F:PMN:F:LFPN",
            buildRoute(
                Profiles.GPS175,
                departure = Departure("LFPL"),
                enroute = listOf(Waypoint("PMN")),
                arrival = Arrival("LFPN"),
            ),
        )
    }

    /**
     * An aerodrome carries the position a file gave it on its own `:F:` fix, and never on
     * `:DA:`/`:AA:`.
     *
     * Pinned in both implementations, so the two encoders cannot drift apart on where the
     * position goes or on when it is left off. The rule is a named user waypoint's: written with
     * no capabilities, which is the preview, and with any navigator that reads lat/lon user
     * waypoints, and dropped only where one says it does not.
     */
    @Test
    fun `an aerodrome's position rides on its fix, under the same rule as a named point`() {
        val lognes = Departure("LFPL", at = Coordinate(48.821944, 2.622778))
        val lobel = listOf(UserWaypoint("LOBEL", 48.791600627042, 2.8691714914193))
        val coulommiers = Arrival("LFPK", at = Coordinate(48.8375, 3.014444))
        val placed = ROUTE_PREFIX +
            ":F:LFPL,N48493E002374:F:LOBEL,N48475E002522:F:LFPK,N48502E003009"

        assertEquals(placed, buildRoute(Profiles.GPS175, lognes, lobel, coulommiers))
        assertEquals(placed, buildRoute(Profiles.GPS175, lognes, lobel, coulommiers, capabilities = full))

        val noLatLon = SupportedElements(elements = Element.WPT, maxTextLength = 512)
        assertEquals(
            ROUTE_PREFIX + ":F:LFPL:F:LOBEL:F:LFPK",
            buildRoute(Profiles.GPS175, lognes, lobel, coulommiers, capabilities = noLatLon),
        )

        // With a procedure the aerodrome is named twice, and only the fix carries the position.
        val withArrival = buildRoute(
            Profiles.GPS175,
            lognes,
            emptyList(),
            Arrival("LFPG", "MOU9W", "MOU", "27R", at = Coordinate(49.009722, 2.547778)),
            capabilities = full,
        )
        assertEquals(
            ROUTE_PREFIX + ":F:LFPL,N48493E002374:F:LFPG,N49006E002329:AA:LFPG:A:MOU9W.MOU(27R)",
            withArrival,
        )
    }

    /**
     * A navigator that takes no departure procedure loses the procedure and keeps everything else,
     * the aerodrome's position included. Copying the departure field by field to drop the
     * procedure is exactly how a new field gets left behind, so this is pinned on its own.
     */
    @Test
    fun `dropping an unsupported procedure keeps the aerodrome's position`() {
        val noProcedures = SupportedElements(
            elements = Element.WPT,
            maxTextLength = 512,
            waypointTypes = WaypointType.LAT_LON_USER,
        )
        assertEquals(
            ROUTE_PREFIX + ":DA:KSFO:R:28R:F:KSFO,N37371W122225:F:KLAS,N36048W115091",
            buildRoute(
                Profiles.GPS175,
                Departure("KSFO", "SSTIK3", "MOD", "28R", at = Coordinate(37.6189, -122.3750)),
                emptyList(),
                Arrival("KLAS", "KEPEC3", "LIN", "26R", at = Coordinate(36.0800, -115.1522)),
                capabilities = noProcedures,
            ),
        )
    }

    /**
     * What each model is sent for each kind of point, pinned in both implementations.
     *
     * One route of every kind of point, under both models and three sets of capabilities, plus the
     * airway chain and the exception at an airway's ends. Every position is a real one: LFPL, LOBEL
     * and LFPK as pinned above, PMN and MLN as a navigator's database has them, and the chain's
     * fixes as Garmin's own client spells them. None sits near a rounding boundary.
     *
     * A GTN locks a named user waypoint on a Connext import and names a bare position itself, so a
     * user waypoint goes to it as a position. A database point keeps its name on both models, and
     * so does a user waypoint at either end of an airway, which is only ever joined at a fix. See
     * [Profile.bareUserWaypoints].
     */
    @Test
    fun `a GTN is sent a user waypoint as its position and a database point with its name`() {
        val measured = SupportedElements.decode(hex("2f000400c00d03"))
        val noLatLon = SupportedElements(elements = Element.WPT, maxTextLength = 512)

        val lognes = Departure("LFPL", at = Coordinate(48.821944, 2.622778))
        val coulommiers = Arrival("LFPK", at = Coordinate(48.8375, 3.014444))
        val lobel = UserWaypoint("LOBEL", 48.791600627042, 2.8691714914193)
        val pmn = Waypoint("PMN", Coordinate(48.69416, 2.72049))
        val mln = Waypoint("MLN", Coordinate(48.45577, 2.81327))
        val everything = listOf(lobel, pmn, mln, Coordinate(48.5, 2.5))
        val named = listOf(lobel, pmn, mln)

        val chainFrom = Departure("LFLL", at = Coordinate(45.72555, 5.08110))
        val chain = listOf(
            Waypoint("MANAK", Coordinate(46.21332, -0.94194)),
            Airway("UT183"),
            Waypoint("TIRAV", Coordinate(46.59889, -1.39083)),
            lobel,
        )
        val chainTo = Arrival("LFRB", at = Coordinate(48.44722, -4.42166))

        val gtn = "FPN/RI:F:LFPL,N48493E002374:F:N48475E002522:F:PMN,N48416E002432" +
            ":F:MLN,N48273E002488:F:N48300E002300:F:LFPK,N48502E003009"
        assertEquals("GTN, no capabilities", gtn,
            buildRoute(Profiles.GTN, lognes, everything, coulommiers))
        assertEquals("GTN, the GPS 175's measured capabilities", gtn,
            buildRoute(Profiles.GTN, lognes, everything, coulommiers, capabilities = measured))
        assertEquals(
            "GPS 175, the same route",
            "FPN/RI:F:LFPL,N48493E002374:F:LOBEL,N48475E002522:F:PMN,N48416E002432" +
                ":F:MLN,N48273E002488:F:N48300E002300:F:LFPK,N48502E003009",
            buildRoute(Profiles.GPS175, lognes, everything, coulommiers, capabilities = measured),
        )
        assertEquals(
            "GTN, no lat/lon user waypoints: every name, and nothing else",
            "FPN/RI:F:LFPL:F:LOBEL:F:PMN:F:MLN:F:LFPK",
            buildRoute(Profiles.GTN, lognes, named, coulommiers, capabilities = noLatLon),
        )
        assertEquals(
            "GTN, the chain",
            "FPN/RI:F:LFLL,N45435E005049:F:MANAK,N46128W000565.UT183.TIRAV,N46359W001234" +
                ":F:N48475E002522:F:LFRB,N48268W004253",
            buildRoute(Profiles.GTN, chainFrom, chain, chainTo),
        )
        assertEquals(
            "GPS 175, the chain",
            "FPN/RI:F:LFLL,N45435E005049:F:MANAK,N46128W000565.UT183.TIRAV,N46359W001234" +
                ":F:LOBEL,N48475E002522:F:LFRB,N48268W004253",
            buildRoute(Profiles.GPS175, chainFrom, chain, chainTo),
        )
        // An airway is only joined at a database fix, so a named point at either end of one keeps
        // its name on a GTN too.
        assertEquals(
            "GTN, a user waypoint at an airway's entry",
            "FPN/RI:F:LOBEL,N48475E002522.UT183.TIRAV",
            buildRoute(Profiles.GTN, enroute = listOf(lobel, Airway("UT183"), Waypoint("TIRAV"))),
        )
        assertEquals(
            "GTN, a user waypoint at an airway's exit",
            "FPN/RI:F:MANAK.UT183.LOBEL,N48475E002522",
            buildRoute(Profiles.GTN, enroute = listOf(Waypoint("MANAK"), Airway("UT183"), lobel)),
        )

        // A bare position has nothing to fall back on without LAT_LON_USER, on a GTN as on a
        // GPS 175.
        assertTrue(
            "a bare position without LAT_LON_USER was encoded",
            runCatching {
                buildRoute(Profiles.GTN, lognes, everything, coulommiers, capabilities = noLatLon)
            }.exceptionOrNull() is FlightPlanException,
        )
        // A name the navigator cannot read is refused even where a GTN would not send it: the
        // route has to be valid on both models.
        for (name in listOf("LOB-L", "TOOLONG")) {
            assertTrue(
                "\"$name\" was encoded for a GTN",
                runCatching {
                    buildRoute(Profiles.GTN, enroute = listOf(UserWaypoint(name, 48.82, 2.62)))
                }.exceptionOrNull() is FlightPlanException,
            )
        }
    }
}
