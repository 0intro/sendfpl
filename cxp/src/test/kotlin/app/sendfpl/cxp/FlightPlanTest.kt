package app.sendfpl.cxp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The route encoder, against vectors a reference implementation asserts. */
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

    /**
     * The format is confirmed in two independent binaries: dls_format_posn_to_str in
     * libadb-lib.so, and Garmin Pilot's ARINCDataUtil.formatLatitudeLongitude().
     */
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
    fun `tenths that round up to 600 carry into the degrees`() {
        // Garmin's encoder leaves them at 600, and a GPS 175 then stores the point at 59.9
        // minutes, a tenth short of where it was rounded to. The second position is the one that
        // showed it, a planner's point at 2 degrees 59 minutes 59 seconds.
        assertEquals("N49000E002372", formatLatLon(48.99999, 2.62))
        assertEquals("N49157E003000", formatLatLon(49.261666666667, 2.9997222222222))
        assertEquals("S49000W003000", formatLatLon(-48.99999, -2.99999))
        // Below the rounding boundary nothing carries.
        assertEquals("N48599E002599", formatLatLon(48.9991, 2.9991))
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
     * The named user waypoint. No capture has this form, so these are hand vectors read off the
     * encoder, `:F:%s` with the name then `",%s"` with the position, and a reference
     * implementation asserts the same literals.
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
        // 48.8375 is 502.5 tenths of a minute, but the double is a hair under, so floor(x + 0.5)
        // gives 502, not 503: the kind of value two implementations drift apart on.
        assertEquals(":F:LFPKC,N48502E003009", UserWaypoint("LFPKC", 48.8375, 3.014444).render())
        // Both halves as one token, so what is echoed back parses back to what will be sent.
        assertEquals(
            "LOBEL,N48475E002522",
            UserWaypoint("LOBEL", 48.791600627042, 2.8691714914193).display,
        )
    }

    /**
     * Six is the boundary that matters: Garmin's encoder validates against it and SD-VFR writes
     * it, but the navigator's parser stops at five and leaves its read pointer on the sixth
     * character, which costs the point its position and marks the whole message malformed.
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
     * The input bound and the encoder's differ on purpose: the firmware checks the truncated
     * degrees against 360, so [formatLatLon] accepts up to that, but nothing east of 180 is a
     * place, so a coordinate typed or read from a file is refused there.
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
        // round trips whatever this refuses to read from a pilot. Both halves round up to 600
        // tenths, which carry into the degrees.
        assertEquals("N90000E360000", formatLatLon(89.9999, 359.9999))
    }

    /**
     * A decimal point further into a token than a degree field reaches is ambiguous: `00237.2` is
     * 2 deg 37.2 min, and reading it as 237.2 degrees is silently wrong.
     */
    @Test
    fun `an ambiguous decimal coordinate is refused, not guessed at`() {
        val e = runCatching { parseLatLon("48.5N/00237.2E") }.exceptionOrNull()
        assertTrue("expected a refusal, got $e", e is FlightPlanException)
        assertTrue(e!!.message!!.contains("ambiguous"))

        // The latitude form fails on its own, 4849.2 being out of range. The longitude is the
        // dangerous one, because 237.2 is within the encoder's range.
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
     * Anything parseLatLon accepts must survive formatLatLon, so a coordinate out of range is
     * refused where the input still has a name, not at encode time.
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
     * An approach names its aerodrome: a GPS 175 imports `:F:LFPL:F:PMN:F:LFPN:AP:I25R` without
     * the approach and without complaint, and with `:AA:LFPN` it loads the ILS 25R.
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
     * An aerodrome carries the position a file gave it on its own `:F:` fix, never on
     * `:DA:`/`:AA:`, under a named user waypoint's rule: written with no capabilities, which is
     * the preview, and for a navigator that reads lat/lon user waypoints, dropped for one that
     * says it does not.
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
     * The GTN Xi reads `:F:BTG-A` as BTG, the Battle Ground VORTAC, with or without a position
     * after it, where the GPS 175 and the GTN read the name whole, so a hyphenated point is refused
     * for the GTN Xi alone, wherever it appears in a `:F:` element.
     */
    @Test
    fun `a hyphen in a point's name is refused for the GTN Xi`() {
        val routes = listOf<(Profile) -> String>(
            { buildRoute(it, enroute = listOf(Waypoint("BTG-A"))) },
            { buildRoute(it, enroute = listOf(Waypoint("BTG-A", Coordinate(45.83333, -122.5)))) },
            {
                buildRoute(it, enroute = listOf(Waypoint("MANAK"), Airway("UT183"), Waypoint("AB-1")))
            },
            { buildRoute(it, Departure("LF-P"), arrival = Arrival("LFPK")) },
            { buildRoute(it, Departure("LFPL"), arrival = Arrival("LF-P")) },
        )
        for (route in routes) {
            route(Profiles.GPS175)
            route(Profiles.GTN)
            val problem = (runCatching { route(Profiles.GTN_XI) }.exceptionOrNull()
                as? FlightPlanException)?.problem
            assertTrue("$problem", problem is Problem.HyphenInPointName)
        }
        // A procedure's hyphen is its variant, `R05-Y`, which every model reads.
        buildRoute(Profiles.GTN_XI, arrival = Arrival("LFGJ"), approach = Approach("R05-Y"))
    }

    /**
     * What each model is sent for each kind of point, under three sets of capabilities: see
     * [Profile.bareUserWaypoints]. The positions are real ones, PMN and MLN as a navigator's
     * database has them and the chain's fixes as Garmin's own client spells them, and none sits
     * near a rounding boundary.
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
        // The GTN Xi's builder is the GTN's, and so is its route.
        for (route in listOf<(Profile) -> String>(
            { buildRoute(it, lognes, everything, coulommiers) },
            { buildRoute(it, lognes, everything, coulommiers, capabilities = measured) },
            { buildRoute(it, lognes, named, coulommiers, capabilities = noLatLon) },
            { buildRoute(it, chainFrom, chain, chainTo) },
            { buildRoute(it, enroute = listOf(lobel, Airway("UT183"), Waypoint("TIRAV"))) },
            { buildRoute(it, enroute = listOf(Waypoint("MANAK"), Airway("UT183"), lobel)) },
        )) {
            assertEquals(route(Profiles.GTN), route(Profiles.GTN_XI))
        }

        // A bare position has nothing to fall back on without LAT_LON_USER, on a GTN as on a
        // GPS 175.
        assertTrue(
            "a bare position without LAT_LON_USER was encoded",
            runCatching {
                buildRoute(Profiles.GTN, lognes, everything, coulommiers, capabilities = noLatLon)
            }.exceptionOrNull() is FlightPlanException,
        )
        // A name the navigator cannot read is refused even where a GTN would not send it: the
        // route has to be valid on every model.
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
