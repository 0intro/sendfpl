package app.sendfpl.bt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import kotlin.random.Random

/** Ordinary Bluetooth profiles, so a device can be given a populated cache that is not Connext. */
private val A2DP_SINK: UUID = UUID.fromString("0000110b-0000-1000-8000-00805f9b34fb")
private val HANDSFREE: UUID = UUID.fromString("0000111e-0000-1000-8000-00805f9b34fb")

/** What the stack records when SDP failed at pairing and there were no EIR UUIDs to fall back on. */
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
 * Distinct addresses for the ordering tests, in ascending order.
 *
 * The address is `deviceOrder`'s last key, so these double as the final tiebreak: where a test expects
 * [A] before [B] on a tie, that is the key being asserted rather than an accident.
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

    // classify

    /**
     * Also the tripwire for reading [CONNEXT_SPP_UUID] out of `ConnextLink.kt`. That file declares
     * classes that touch Android, only its top level `val` is touched here, and this test is what
     * fails loudly if that ever stops being true. The fix would be to move the constant to a file of
     * its own, never to copy it.
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
     * A stale silence must keep dimming. Decaying it back to "unknown", which is not dimmed, would
     * show a navigator that is switched off as perfectly healthy every time the sheet is opened
     * after the freshness window, for the six seconds a page takes. A verdict ages in its wording,
     * never in whether it is shown.
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
        // probePlan would refuse to page this one anyway, so offering would be a broken promise.
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

    // ago

/**
     * The rounding, which is what this file owns. The wording lives in the sheet's resources, so
     * that it can be translated, and this stays a plain JVM test of the arithmetic.
     */
    @Test
    fun `an age is rounded to the unit it will be said in`() {
        assertEquals(Age(0, Age.Unit.JUST_NOW), ago(NOW - 900, NOW))
        assertEquals(Age(0, Age.Unit.JUST_NOW), ago(NOW + 5_000, NOW)) // never negative
        assertEquals(Age(8, Age.Unit.SECONDS), ago(NOW - 8_000, NOW))
        assertEquals(Age(2, Age.Unit.MINUTES), ago(NOW - 150_000, NOW))
        assertEquals(Age(3, Age.Unit.HOURS), ago(NOW - 3 * 3_600_000L, NOW))
    }

    // probePlan

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
     * Freshness governs when to *ask again*, which is a different question from what to *show*.
     * See `a stale silence keeps dimming and reports its age`.
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

    // deviceOrder

    @Test
    fun `a device that answered leads one that did not`() {
        val here = facts(name = "GPS175 CA40", probe = Probe.Live(NOW), address = A)
        val hangar = facts(name = "GPS175 6918", probe = silent(NOW), address = B)
        assertEquals(listOf(A, B), deviceOrder(listOf(hangar, here)))
    }

    /** The dimming rule, said as an order: evidence for, then nothing known, then evidence against. */
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
     * The case that makes [navigatorRank] a rank and not the boolean `worthProbing` already
     * computes. Both of these are "not worth paging", one because we have evidence against it
     * and the other because we have no evidence at all, and only the version with four values keeps the
     * navigator above the stereo when the stereo is the one with a live link.
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

    /** The compromise the request asked for, pinned: "nearby first" beats "the usual one". */
    @Test
    fun `answering outranks having been used before`() {
        val usual = facts(name = "GPS175 6918", probe = silent(NOW), address = A)
        val here = facts(name = "GPS175 CA40", probe = Probe.Live(NOW), address = B)
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
     * Two properties in one, over every combination of the four inputs.
     *
     * Ordering is a *permutation*: every bonded device comes back, because a row that vanishes is a
     * filter, and the whole feature refuses to filter. And the result is a function of the facts
     * alone, since shuffling the input cannot change it, which is what the address tiebreak is for,
     * since two navigators can perfectly well be renamed to the same string.
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
     * The invariant, in the ordering dimension: nothing sinks on absence of evidence.
     *
     * Stated per dimension, holding the others fixed, because unquantified it would be false: a
     * name that looks like Garmin legitimately outranks an unremarkable one *within* the tier where
     * there is no cache to go on. What must never happen is a device we know nothing about falling below
     * one we have positively ruled out.
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

    // looksLikeGarmin

    @Test
    fun `the name heuristic covers the shapes it claims`() {
        for (name in listOf("GPS 175", "gps175", "GNC 355", "Garmin GNX", "GI 275", "Flight Stream 510")) {
            assertTrue(name, looksLikeGarmin(name))
        }
        for (name in listOf("Pioneer AVH-Z", "WH-1000XM4", "", "Pixel Buds Pro")) {
            assertFalse(name, looksLikeGarmin(name))
        }
    }

    // garminOui and the filter

    /**
     * The address block, which is the one signal a user cannot change and a cache cannot lose.
     *
     * `F0:99:19` and `0C:7E:24` are the two read off the navigators on the bench; the rest are from
     * the IEEE registry. Case is not assumed, because a comparison that depended on the platform
     * returning upper case would hide every navigator at once on the OEM that did not.
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
     * The invariant the filter is bounded by, as a property rather than as examples.
     *
     * Hiding a row is the one thing the rest of this file refuses to do, so the predicate is a
     * union of signals *for*: if anything at all says Garmin, the row survives. What is asserted
     * here is that direction only. The converse, that a device with no signal *is* hidden, is one
     * example below, because it is the weaker claim and the safe one to get wrong.
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
     * An upload outranks the cache, which is the same reasoning `navigatorRank` already applies.
     *
     * This app spoke Connext to that address and a navigator accepted a flight plan. A cache that
     * now says otherwise is the stale cache the header warns about, and it must not hide the one
     * device known to work.
     */
    @Test
    fun `a device that accepted a flight plan is never hidden`() {
        val stale = facts(name = "N123AB", uuids = listOf(A2DP_SINK, HANDSFREE), address = "AA:BB:CC:DD:EE:FF")
        assertFalse(classify(stale, NOW).garmin)
        assertTrue(classify(stale, NOW, hasUploaded = true).garmin)
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
