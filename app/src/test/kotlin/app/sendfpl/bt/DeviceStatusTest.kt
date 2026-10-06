package app.sendfpl.bt

import java.util.UUID
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ordinary Bluetooth profiles, so a device can be given a populated cache that is not Connext. */
private val A2DP_SINK: UUID = UUID.fromString("0000110b-0000-1000-8000-00805f9b34fb")
private val HANDSFREE: UUID = UUID.fromString("0000111e-0000-1000-8000-00805f9b34fb")

/** What the stack records when SDP fails at pairing and there are no EIR UUIDs to fall back on. */
private val NIL_UUID: UUID = UUID(0L, 0L)

/** An arbitrary point on a monotonic clock, far enough from zero to subtract from. */
private const val NOW = 10_000_000L

private fun facts(
    name: String = "GPS 175",
    uuids: List<UUID>? = listOf(CONNEXT_SPP_UUID),
    probe: Probe = Probe.NotProbed,
    acl: Boolean = false,
    address: String = "AA:BB:CC:DD:EE:FF",
) = DeviceFacts(address = address, name = name, cachedUuids = uuids, probe = probe, aclConnected = acl)

private fun silent(atMillis: Long) = Probe.Silent(atMillis, Probe.Reason.TIMED_OUT)

/**
 * Distinct addresses for the ordering tests, in ascending order. The address is `deviceOrder`'s
 * last key, so where a test expects [A] before [B] on a tie, that key is what it asserts.
 */
private val ADDRESSES = listOf(
    "11:11:11:11:11:11",
    "22:22:22:22:22:22",
    "33:33:33:33:33:33",
    "44:44:44:44:44:44",
)
private val A = ADDRESSES[0]
private val B = ADDRESSES[1]
private val C = ADDRESSES[2]

/** No cache, an empty one, a failed one, a Connext one, and one that rules the device out. */
private val CACHES: List<List<UUID>?> = listOf(
    null,
    emptyList(),
    listOf(NIL_UUID),
    listOf(CONNEXT_SPP_UUID),
    listOf(A2DP_SINK, HANDSFREE),
)

private val NAMES = listOf("GPS 175", "Pioneer AVH-Z", "")

private val PROBES = listOf(
    Probe.NotProbed,
    Probe.Probing(),
    Probe.Probing(silent(NOW)),
    Probe.Live(NOW),
    silent(NOW),
    Probe.Silent(NOW, Probe.Reason.REQUEST_REFUSED),
)

/** Every combination of the four things an order reads, each device with its own address. */
private fun crossProduct(): List<DeviceFacts> = buildList {
    var i = 0
    for (cache in CACHES) {
        for (probe in PROBES) {
            for (acl in listOf(false, true)) {
                for (name in NAMES) {
                    add(
                        DeviceFacts(
                            address = "AA:BB:CC:DD:%02X:%02X".format(i / 256, i % 256),
                            name = name,
                            cachedUuids = cache,
                            probe = probe,
                            aclConnected = acl,
                        )
                    )
                    i++
                }
            }
        }
    }
}

class DeviceStatusTest {

    /**
     * Also the tripwire for reading [CONNEXT_SPP_UUID] out of `ConnextLink.kt`, whose classes
     * touch Android. If this fails there, move the constant to a file of its own; never copy it.
     */
    @Test
    fun `a cached Connext UUID is capability enough`() {
        val s = classify(facts(), NOW)
        assertEquals(Capability.CONNEXT, s.capability)
        assertFalse(s.dimmed)
    }

    @Test
    fun `a bonded car stereo is dimmed and says why`() {
        val s = classify(facts(name = "Car Multimedia", uuids = listOf(A2DP_SINK, HANDSFREE)), NOW)
        assertEquals(Capability.OTHER, s.capability)
        assertTrue(s.dimmed)
        assertEquals(Detail.NO_CONNEXT, s.detail)
    }

    @Test
    fun `an empty cache is not evidence of absence`() {
        val s = classify(facts(name = "Pixel Buds", uuids = null), NOW)
        assertEquals(Capability.UNKNOWN, s.capability)
        assertFalse(s.dimmed)
        assertNull(s.label)
        assertNull(s.detail)
    }

    @Test
    fun `a uuid of all zeros is a failed sdp, not a service list`() {
        val s = classify(facts(uuids = listOf(NIL_UUID)), NOW)
        assertEquals(Capability.UNKNOWN, s.capability)
        assertFalse(s.dimmed)
    }

    @Test
    fun `a probe that answered marks the device live`() {
        val s = classify(facts(probe = Probe.Live(NOW - 1_000)), NOW)
        assertEquals(Liveness.LIVE, s.liveness)
        assertEquals(Indicator.LIVE, s.indicator)
        assertFalse(s.dimmed)
    }

    @Test
    fun `silence dims but says it may still work`() {
        val s = classify(facts(probe = silent(NOW - 1_000)), NOW)
        assertEquals(Liveness.SILENT, s.liveness)
        assertEquals(Indicator.SILENT, s.indicator)
        assertTrue(s.dimmed)
        assertEquals(Detail.UNREACHABLE, s.detail)
    }

    /**
     * A stale silence must keep dimming: decaying to "unknown", which is not dimmed, would show a
     * navigator that is switched off as healthy for the six seconds a page takes. A verdict ages
     * in its wording, never in whether it is shown.
     */
    @Test
    fun `a stale silence keeps dimming and reports its age`() {
        val s = classify(facts(probe = silent(NOW - 600_000)), NOW)
        assertEquals(Liveness.SILENT, s.liveness)
        assertTrue(s.dimmed)
        assertEquals(Label.NO_ANSWER, s.label)
        assertEquals(Age(10, Age.Unit.MINUTES), s.age)
    }

    /** The same rule, at the other end: rechecking must not brighten the row while it runs. */
    @Test
    fun `rechecking keeps showing what it last knew`() {
        val s = classify(facts(probe = Probe.Probing(silent(NOW - 1_000))), NOW)
        assertTrue(s.dimmed)
        assertEquals(Liveness.SILENT, s.liveness)
        assertEquals(Indicator.CHECKING, s.indicator)
        assertEquals(Label.CHECKING, s.label)
        // No age beside it: "checking…" is about now, so dating it would date something that has
        // no date. The verdict being rechecked still shows through in the dimming and the detail.
        assertNull(s.age)
        assertEquals(Detail.UNREACHABLE, s.detail)
        assertFalse("nothing to retry while one is running", s.canRetry)
    }

    @Test
    fun `rechecking a device nothing is known about does not dim it`() {
        val s = classify(facts(probe = Probe.Probing()), NOW)
        assertFalse(s.dimmed)
        assertEquals(Indicator.CHECKING, s.indicator)
    }

    @Test
    fun `an acl link means live and possibly taken`() {
        val s = classify(facts(acl = true), NOW)
        assertEquals(Liveness.LIVE, s.liveness)
        assertTrue(s.occupied)
        assertFalse(s.dimmed)
        assertEquals(Detail.LINK_HELD, s.detail)
    }

    /** The stack refusing to ask is evidence about this phone, not about the navigator. */
    @Test
    fun `a refused sdp request is not the device's fault`() {
        val s = classify(facts(probe = Probe.Silent(NOW, Probe.Reason.REQUEST_REFUSED)), NOW)
        assertEquals(Liveness.UNKNOWN, s.liveness)
        assertFalse(s.dimmed)
    }

    @Test
    fun `retry is offered exactly where paging again would help`() {
        assertTrue(classify(facts(probe = silent(NOW)), NOW).canRetry)
        assertTrue(classify(facts(probe = Probe.NotProbed), NOW).canRetry)
        assertFalse(classify(facts(probe = Probe.Live(NOW)), NOW).canRetry)
        assertFalse(classify(facts(acl = true), NOW).canRetry)
        // The cache rules this one out, and a sweep never pages such a device either.
        assertFalse(classify(facts(uuids = listOf(A2DP_SINK)), NOW).canRetry)
    }

    /**
     * The invariant the whole feature is built to keep, asserted as a property rather than as one
     * more example: a dimmed row is a soft filter, and every signal here can be wrong in the
     * direction that hides the pilot's own navigator.
     */
    @Test
    fun `nothing dims on absence of evidence`() {
        val caches = listOf(null, emptyList(), listOf(NIL_UUID))
        val probes = listOf(Probe.NotProbed, Probe.Probing(), Probe.Probing(Probe.NotProbed))
        val names = listOf("GPS 175", "Pioneer AVH-Z", "")
        for (cache in caches) {
            for (probe in probes) {
                for (name in names) {
                    val s = classify(facts(name = name, uuids = cache, probe = probe), NOW)
                    val where = "cache=$cache probe=$probe name=$name"
                    assertFalse(where, s.dimmed)
                    assertEquals(where, Capability.UNKNOWN, s.capability)
                    assertEquals(where, Liveness.UNKNOWN, s.liveness)
                }
            }
        }
    }

    /** Only the rounding: the wording lives in the sheet's resources, where it is translated. */
    @Test
    fun `an age is rounded to the unit it will be said in`() {
        assertEquals(Age(0, Age.Unit.JUST_NOW), ago(NOW - 900, NOW))
        assertEquals(Age(0, Age.Unit.JUST_NOW), ago(NOW + 5_000, NOW)) // never negative
        assertEquals(Age(8, Age.Unit.SECONDS), ago(NOW - 8_000, NOW))
        assertEquals(Age(2, Age.Unit.MINUTES), ago(NOW - 150_000, NOW))
        assertEquals(Age(3, Age.Unit.HOURS), ago(NOW - 3 * 3_600_000L, NOW))
    }

    /** What keeps a sweep from paging the headphones. Asserted over every state, not one. */
    @Test
    fun `a device the cache rules out is never paged`() {
        val probes = listOf(
            Probe.NotProbed,
            Probe.Live(NOW),
            silent(NOW),
            silent(NOW - 600_000),
        )
        val names = listOf("Garmin GNX 375", "Car Multimedia")
        val ruled = buildList {
            probes.forEachIndexed { i, probe ->
                names.forEachIndexed { j, name ->
                    add(
                        facts(
                            name = name,
                            uuids = listOf(A2DP_SINK, HANDSFREE),
                            probe = probe,
                            address = "AA:BB:CC:DD:$i:$j",
                        )
                    )
                }
            }
        }
        assertEquals(emptyList<String>(), probePlan(ruled, NOW, budget = 100))
    }

    @Test
    fun `an empty cache with a name that looks like Garmin is paged`() {
        val f = listOf(facts(name = "GNC 355", uuids = null))
        assertEquals(listOf("AA:BB:CC:DD:EE:FF"), probePlan(f, NOW))
    }

    @Test
    fun `an empty cache with an unremarkable name is not`() {
        val f = listOf(facts(name = "WH-1000XM4", uuids = null))
        assertEquals(emptyList<String>(), probePlan(f, NOW))
    }

    @Test
    fun `the budget is respected and Connext devices come first`() {
        val list = (0..9).map { i ->
            facts(
                name = "Garmin something $i",
                uuids = if (i >= 6) listOf(CONNEXT_SPP_UUID) else null,
                address = "AA:BB:CC:DD:EE:0$i",
            )
        }
        assertEquals(
            listOf("AA:BB:CC:DD:EE:06", "AA:BB:CC:DD:EE:07", "AA:BB:CC:DD:EE:08", "AA:BB:CC:DD:EE:09"),
            probePlan(list, NOW, budget = 4),
        )
    }

    /**
     * Freshness governs when to ask again, not what to show: see
     * `a stale silence keeps dimming and reports its age`.
     */
    @Test
    fun `a device probed recently is skipped`() {
        val f = listOf(facts(probe = Probe.Live(NOW - 1_000)))
        assertEquals(emptyList<String>(), probePlan(f, NOW))
        // What "Refresh" passes, and the reason an explicit ask probes again.
        assertEquals(listOf("AA:BB:CC:DD:EE:FF"), probePlan(f, NOW, freshMillis = 0L))
    }

    @Test
    fun `a stale verdict is asked again`() {
        val f = listOf(facts(probe = silent(NOW - 600_000)))
        assertEquals(listOf("AA:BB:CC:DD:EE:FF"), probePlan(f, NOW))
    }

    @Test
    fun `a probe already in flight is not started twice`() {
        val f = listOf(facts(probe = Probe.Probing(silent(NOW - 600_000))))
        assertEquals(emptyList<String>(), probePlan(f, NOW, freshMillis = 0L))
    }

    @Test
    fun `nothing is probed during a send`() {
        val f = listOf(facts(), facts(name = "GNX 375", uuids = null, address = "11:22:33:44:55:66"))
        assertEquals(emptyList<String>(), probePlan(f, NOW, busy = true))
    }

    @Test
    fun `a device that answered leads one that did not`() {
        val here = facts(name = "GPS175 1111", probe = Probe.Live(NOW), address = A)
        val hangar = facts(name = "GPS175 2222", probe = silent(NOW), address = B)
        assertEquals(listOf(A, B), deviceOrder(listOf(hangar, here)))
    }

    /** The dimming rule as an order: evidence for, then nothing known, then evidence against. */
    @Test
    fun `silence sinks a row, but no answer at all does not`() {
        val answered = facts(probe = Probe.Live(NOW), address = A)
        val unasked = facts(probe = Probe.NotProbed, address = B)
        val quiet = facts(probe = silent(NOW), address = C)
        assertEquals(listOf(A, B, C), deviceOrder(listOf(quiet, unasked, answered)))
    }

    @Test
    fun `a car stereo never leads, even connected`() {
        val stereo = facts(name = "Pioneer AVH-Z", uuids = listOf(A2DP_SINK, HANDSFREE), acl = true, address = A)
        val navigator = facts(name = "GPS 175", probe = silent(NOW), address = B)
        assertEquals(listOf(B, A), deviceOrder(listOf(stereo, navigator)))
    }

    /**
     * Why `navigatorRank` is a rank and not the boolean `worthProbing` computes: both devices are
     * "not worth paging", one on evidence against it and one on no evidence at all, and only four
     * values keep the navigator above the stereo when the stereo holds a live link.
     */
    @Test
    fun `a renamed navigator with no cache outranks a device already ruled out`() {
        val stereo = facts(name = "Pioneer AVH-Z", uuids = listOf(A2DP_SINK, HANDSFREE), acl = true, address = A)
        val renamed = facts(name = "N123AB", uuids = null, address = B)
        assertEquals(listOf(B, A), deviceOrder(listOf(stereo, renamed)))
    }

    @Test
    fun `the Connext cache outranks a name that merely looks Garmin`() {
        val guessed = facts(name = "GNC 355", uuids = null, address = A)
        val proven = facts(name = "N123AB", uuids = listOf(CONNEXT_SPP_UUID), address = B)
        assertEquals(listOf(B, A), deviceOrder(listOf(guessed, proven)))
    }

    /** What decides the cold start, when nothing has been paged and both rows look identical. */
    @Test
    fun `the last navigator used leads its identical twin`() {
        val one = facts(name = "GPS175", address = A)
        val other = facts(name = "GPS175", address = B)
        assertEquals(listOf(B, A), deviceOrder(listOf(one, other), mapOf(B to 1L)))
    }

    /** "Nearby first" beats "the usual one". */
    @Test
    fun `answering outranks having been used before`() {
        val usual = facts(name = "GPS175 1111", probe = silent(NOW), address = A)
        val here = facts(name = "GPS175 2222", probe = Probe.Live(NOW), address = B)
        assertEquals(listOf(B, A), deviceOrder(listOf(usual, here), mapOf(A to 1L)))
    }

    /** An upload only completes against a navigator, so it outranks a cache written before one. */
    @Test
    fun `a completed upload outranks a cache that says otherwise`() {
        val flownWith = facts(name = "Panel", uuids = listOf(A2DP_SINK), address = A)
        val stranger = facts(name = "Speaker", uuids = null, address = B)
        assertEquals(listOf(A, B), deviceOrder(listOf(stranger, flownWith), mapOf(A to 1L)))
    }

    /** The ordering half of `rechecking keeps showing what it last knew`. */
    @Test
    fun `rechecking never moves a row`() {
        val verdicts = listOf(
            Probe.NotProbed,
            Probe.Live(NOW),
            silent(NOW),
            Probe.Silent(NOW, Probe.Reason.REQUEST_REFUSED),
        )
        for (verdict in verdicts) {
            val rest = listOf(
                facts(probe = Probe.Live(NOW), address = B),
                facts(probe = silent(NOW), address = C),
            )
            val settled = listOf(facts(probe = verdict, address = A)) + rest
            val checking = listOf(facts(probe = Probe.Probing(verdict), address = A)) + rest
            assertEquals(verdict.toString(), deviceOrder(settled), deviceOrder(checking))
        }
    }

    @Test
    fun `a refused request never sinks a row`() {
        val refused = facts(probe = Probe.Silent(NOW, Probe.Reason.REQUEST_REFUSED), address = A)
        val unasked = facts(probe = Probe.NotProbed, address = B)
        val timedOut = facts(probe = silent(NOW), address = C)
        assertEquals(listOf(A, B, C), deviceOrder(listOf(timedOut, unasked, refused)))
    }

    @Test
    fun `nothing bonded orders to nothing`() {
        assertEquals(emptyList<String>(), deviceOrder(emptyList()))
    }

    /**
     * Over every combination of the four inputs, ordering returns every bonded device, never a
     * subset, and the same order whatever the input order. The address tiebreak is for that,
     * since two navigators can be renamed to the same string.
     */
    @Test
    fun `ordering is a permutation, never a filter`() {
        val all = crossProduct()
        val lastUsed = all.mapIndexedNotNull { i, f -> if (i % 7 == 0) f.address to 1_000L + i else null }.toMap()
        for (history in listOf(emptyMap(), lastUsed)) {
            val order = deviceOrder(all, history)
            assertEquals(all.map { it.address }.sorted(), order.sorted())
            assertEquals(order, deviceOrder(all.shuffled(Random(1)), history))
            assertEquals(order, deviceOrder(all.reversed(), history))
        }
    }

    /**
     * Nothing sinks on absence of evidence, stated per dimension with the others fixed, since a
     * name that looks like Garmin rightly outranks an unremarkable one where there is no cache.
     * What must never happen is a device we know nothing about falling below one ruled out.
     */
    @Test
    fun `no evidence never sinks below evidence of absence`() {
        // Capability: no cache, an empty one and a failed one all outrank a populated one without
        // Connext in it, whatever the device is doing and whatever it is called.
        val all = crossProduct()
        val order = deviceOrder(all)
        val unknown = all.filter { it.cachedUuids.isNullOrEmpty() || it.cachedUuids == listOf(NIL_UUID) }
        val ruledOut = all.filter { it.cachedUuids == listOf(A2DP_SINK, HANDSFREE) }
        val lastUnknown = unknown.maxOf { order.indexOf(it.address) }
        val firstRuledOut = ruledOut.minOf { order.indexOf(it.address) }
        assertTrue("$lastUnknown < $firstRuledOut", lastUnknown < firstRuledOut)

        // Liveness: only a probe that timed out is evidence of absence. Not being asked, being
        // asked right now, and the stack refusing to ask must all outrank it.
        val quiet = listOf(Probe.NotProbed, Probe.Probing(), Probe.Silent(NOW, Probe.Reason.REQUEST_REFUSED))
        for (cache in CACHES) {
            for (name in NAMES) {
                val timedOut = facts(name = name, uuids = cache, probe = silent(NOW), address = A)
                quiet.forEachIndexed { i, probe ->
                    val f = facts(name = name, uuids = cache, probe = probe, address = ADDRESSES[i + 1])
                    val where = "cache=$cache name=$name probe=$probe"
                    assertEquals(where, listOf(f.address, A), deviceOrder(listOf(timedOut, f)))
                }
            }
        }
    }

    @Test
    fun `the name heuristic covers the shapes it claims`() {
        for (name in listOf("GPS 175", "gps175", "GNC 355", "Garmin GNX", "GI 275", "Flight Stream 510")) {
            assertTrue(name, looksLikeGarmin(name))
        }
        for (name in listOf("Pioneer AVH-Z", "WH-1000XM4", "", "Pixel Buds Pro")) {
            assertFalse(name, looksLikeGarmin(name))
        }
    }

    /**
     * The address block, the one signal a user cannot change and a cache cannot lose. `F0:99:19`
     * and `0C:7E:24` are read off real navigators, the rest come from the IEEE registry. Case is
     * not assumed, or an OEM that returns lower case would hide every navigator at once.
     */
    @Test
    fun `a Garmin address block is recognised, in either case`() {
        for (oui in listOf(
            "00:05:4F", "0C:7E:24", "10:4E:89", "10:C6:FC", "14:13:0B",
            "14:8F:21", "38:F9:F5", "60:3C:68", "64:A3:37", "90:F1:57",
            "A0:28:84", "B4:C2:6A", "C4:CB:33", "E0:48:24", "F0:99:19",
        )) {
            assertTrue(oui, garminOui("$oui:11:22:33"))
            assertTrue(oui, garminOui("$oui:11:22:33".lowercase()))
        }
        for (address in listOf("AA:BB:CC:DD:EE:FF", "00:1A:7D:DA:71:13", "F0:99:1A:00:00:00", "", "F0:99")) {
            assertFalse(address, garminOui(address))
        }
    }

    /**
     * The filter's bound, as a property: the predicate is a union of signals for Garmin, so if
     * anything at all says Garmin, the row survives. The converse, the safe one to get wrong, is
     * one example below.
     */
    @Test
    fun `no device with a Garmin signal is ever hidden`() {
        for (f in crossProduct()) {
            for (hasUploaded in listOf(false, true)) {
                val signal = hasUploaded ||
                    capabilityOf(f.cachedUuids) == Capability.CONNEXT ||
                    garminOui(f.address) ||
                    looksLikeGarmin(f.name)
                if (!signal) continue
                val where = "cache=${f.cachedUuids} name=${f.name} uploaded=$hasUploaded"
                assertTrue(where, classify(f, NOW, hasUploaded).garmin)
            }
        }
    }

    /**
     * An upload outranks the cache, as in `navigatorRank`: a navigator at that address accepted a
     * flight plan, so a cache that says otherwise is stale and must not hide it.
     */
    @Test
    fun `a device that accepted a flight plan is never hidden`() {
        val stale = facts(name = "N123AB", uuids = listOf(A2DP_SINK, HANDSFREE), address = "AA:BB:CC:DD:EE:FF")
        assertFalse(classify(stale, NOW).garmin)
        assertTrue(classify(stale, NOW, hasUploaded = true).garmin)
    }

    /**
     * What Send aims at. A unit next door that answers while the pilot's own is still off must
     * never take the selection: two `GPS175`s look identical on the chip.
     */
    @Test
    fun `the choice stands while it is bonded, whoever answers`() {
        val mine = facts(name = "GPS175", probe = silent(NOW), address = A)
        val next = facts(name = "GPS175", probe = Probe.Live(NOW), address = B)
        assertEquals(A, selection(listOf(mine, next), current = A))
        assertEquals(A, selection(listOf(next, mine), current = A, lastUsed = mapOf(A to 1L, B to 2L)))
    }

    /** The same rule over every combination of what a sweep can learn. */
    @Test
    fun `nothing learned moves a bonded choice`() {
        val all = crossProduct()
        val history = all.mapIndexedNotNull { i, f -> if (i % 5 == 0) f.address to 1_000L + i else null }
            .toMap()
        for (f in all) {
            assertEquals(f.toString(), f.address, selection(all, current = f.address, lastUsed = history))
        }
    }

    @Test
    fun `an unpaired choice gives way to the navigator last sent to, answering or not`() {
        val here = facts(name = "GPS175", probe = Probe.Live(NOW), address = A)
        val usual = facts(name = "GPS175", probe = silent(NOW), address = B)
        // C was the choice, and its upload outlives its bond.
        assertEquals(B, selection(listOf(here, usual), current = C, lastUsed = mapOf(B to 5L, C to 9L)))
        assertEquals(B, selection(listOf(here, usual), current = null, lastUsed = mapOf(B to 5L)))
    }

    /** With no choice and no upload: the head of the order, among the rows the sheet keeps. */
    @Test
    fun `nothing is chosen that the sheet would hide`() {
        val pods = facts(name = "AirPods", uuids = null, address = "00:1A:7D:DA:71:13")
        assertNull(selection(listOf(pods), current = null))
        // Ordered after the AirPods by name alone, but kept by its Garmin address.
        val renamed = facts(name = "N123AB", uuids = null, address = "F0:99:19:F1:CA:40")
        assertEquals(renamed.address, selection(listOf(pods, renamed), current = null))
        val navigator = facts(name = "GNC 355", address = A)
        assertEquals(A, selection(listOf(pods, renamed, navigator), current = null))
    }

    /** The bonds read while the radio is still turning on come back empty, not unpaired. */
    @Test
    fun `an empty list keeps the choice`() {
        assertEquals(A, selection(emptyList(), current = A))
        assertNull(selection(emptyList(), current = null))
    }

    @Test
    fun `another navigator answering while the chosen one is silent is worth a line`() {
        val mine = facts(name = "GPS175", probe = silent(NOW), address = A)
        val next = facts(name = "GPS175", probe = Probe.Live(NOW), address = B)
        assertTrue(anotherAnswers(listOf(mine, next), selected = A))
        // Rechecking either keeps the line steady.
        val rechecking = listOf(
            mine.copy(probe = Probe.Probing(silent(NOW))),
            next.copy(probe = Probe.Probing(Probe.Live(NOW))),
        )
        assertTrue(anotherAnswers(rechecking, selected = A))
        // Not once the chosen one answers, nor before it has been asked.
        val unasked = listOf(Probe.NotProbed, Probe.Probing(), Probe.Silent(NOW, Probe.Reason.REQUEST_REFUSED))
        for (probe in unasked + Probe.Live(NOW)) {
            val chosen = mine.copy(probe = probe)
            assertFalse(probe.toString(), anotherAnswers(listOf(chosen, next), selected = A))
        }
        assertFalse(anotherAnswers(listOf(mine, next), selected = null))
    }

    /** A link held by headphones or a car stereo is not a navigator answering. */
    @Test
    fun `only a navigator answering counts`() {
        val mine = facts(name = "GPS175", probe = silent(NOW), address = A)
        val stereo = facts(name = "Pioneer AVH-Z", uuids = listOf(A2DP_SINK, HANDSFREE), acl = true, address = B)
        val pods = facts(name = "AirPods", uuids = null, acl = true, address = C)
        assertFalse(anotherAnswers(listOf(mine, stereo, pods), selected = A))
        assertTrue(anotherAnswers(listOf(mine, pods.copy(name = "GNX 375")), selected = A))
        assertTrue(anotherAnswers(listOf(mine, pods), selected = A, lastUsed = mapOf(C to 1L)))
    }

    /** The case the filter exists for: nothing about this says Garmin. */
    @Test
    fun `a car stereo is hidden`() {
        val stereo = facts(
            name = "Pioneer AVH-Z",
            uuids = listOf(A2DP_SINK, HANDSFREE),
            address = "00:1A:7D:DA:71:13",
        )
        assertFalse(classify(stereo, NOW).garmin)

        // And the three ways it could have kept its row, each on its own.
        assertTrue(classify(stereo.copy(name = "GPS 175"), NOW).garmin)
        assertTrue(classify(stereo.copy(address = "F0:99:19:F1:CA:40"), NOW).garmin)
        assertTrue(classify(stereo.copy(cachedUuids = listOf(CONNEXT_SPP_UUID)), NOW).garmin)
    }
}
