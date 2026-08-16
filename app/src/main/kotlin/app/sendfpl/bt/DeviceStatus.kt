package app.sendfpl.bt

import java.util.UUID

/*
 * Everything decidable about a bonded device, with no Android types in sight.
 *
 * **This file must not import anything from `android.*`.** That is what lets the rules below run
 * under `:app:testDebugUnitTest` on a plain JVM, the way the `:cxp` module does. The parts that do
 * touch Android, reading the SDP cache and paging the radio, live in `GarminDevices` and
 * `DeviceProbe`, which are thin enough that what is left untested is "call the API and convert
 * the result".
 *
 * ## What "usable" can mean
 *
 * Classic Bluetooth holds no standing ACL link: AOSP connects Headset and A2DP on its own, nothing
 * else, and L2CAP drops an idle BR/EDR link four seconds after its last channel closes. So a
 * navigator sitting powered in the panel, in range, and perfectly usable is *not* connected to
 * anything until this app opens a socket. Android's "connected" is not the pilot's "usable", and a
 * list that dims on connection state dims the right device essentially always.
 *
 * Three separate signals, three different costs:
 *
 *  * **capability**, from the SDP cache written at pairing time. Free, instant, and a far better
 *    test than the device's name, which the user can set to anything.
 *  * **reachability**, from an active probe. Seconds, and it pages the radio.
 *  * **occupancy**, from `BluetoothDevice.isConnected()`. Free, and it never means "unreachable".
 *
 * ## The one invariant
 *
 * **Dim only on positive evidence of absence. Never on absence of evidence.**
 *
 * Every signal above can be wrong in the direction that matters: an SDP cache written before a
 * firmware change, a probe timing out because the phone's radio was busy with an audio link. This
 * is the same reasoning that already stops [GarminDevices.bonded] filtering on the name. A dimmed
 * row is a filter with worse ergonomics, and a false negative that locks a pilot out of their own
 * navigator is the worst thing this app can do. Dimming is presentation only, and nothing here
 * disables a row. `nothing dims on absence of evidence` in `DeviceStatusTest` asserts it as a property
 * rather than trusting a review to notice.
 *
 * ## Its corollary
 *
 * **A verdict is never unlearned.** Decaying a stale "no answer" back to "no information", which
 * is not dimmed, would make a navigator that is switched off look healthy every time the sheet is
 * opened after the freshness window, for the six seconds it takes to page it again. Probing again
 * is what corrects a stale verdict; forgetting it as well only hides the answer during precisely
 * the moment someone is looking. Age belongs in the wording, not in whether the row is dimmed at
 * all. [Probe.Probing] carries the verdict it is rechecking for the same reason.
 *
 * ## The same invariant, said as an order
 *
 * [deviceOrder] ranks the list so the navigator that answered leads it. That is the dimming rule
 * turned into a sort: within each dimension, evidence *for* above no evidence above evidence
 * *against*. It is a permutation and never a filter: every bonded device comes back, in a
 * different order.
 *
 * The obvious version of this is wrong, and the way it is wrong is the reason [navigatorRank] is a
 * rank rather than the boolean [worthProbing] already computes. `worthProbing` is false for two
 * quite different devices: a car stereo whose populated cache has no Connext, and a navigator
 * someone renamed `N123AB` whose cache is empty. Tie those together and liveness decides, so the
 * stereo, which really is connected, outranks the pilot's own navigator. Evidence against has to
 * sink *below* no evidence, which needs four values and not two.
 *
 * Two consequences worth stating. [deviceOrder] takes no clock: if position decayed with freshness
 * a row would move without anything having been learned, which is the unlearning bug again in
 * another dimension. And it is deliberately not [probePlan]'s order: that one spends radio budget
 * on what is *unknown*, where this one leads with what is *known*.
 *
 * ## The one place a row is hidden, and what makes that safe
 *
 * [DeviceStatus.garmin] is the exception to everything above, and it is an exception rather than a
 * contradiction only because of four rules the sheet keeps.
 *
 * A pilot with headphones, a car stereo and a watch scrolls past all of them to reach a navigator,
 * so the sheet hides what is positively not Garmin. Garmin Pilot solves this no better: it
 * identifies a Connext device by the SDP UUID, exactly as [capabilityOf] does, and its own device
 * list then filters nothing at all.
 *
 * The predicate is a union of signals *for*, so anything known keeps its row:
 *
 *  * [Capability.CONNEXT], which is Garmin's own test;
 *  * [garminOui], which the user cannot change and which is never absent;
 *  * [looksLikeGarmin], a hint, used here only to keep a row and never to drop one;
 *  * an upload this app completed to that address, which outranks every other signal.
 *
 * And hiding is bounded by the sheet: it states the count of what it hid and offers one tap to
 * show everything, the selected device is never hidden, and the choice is remembered. So a device
 * this gets wrong costs a tap, where a silent filter would cost a pilot their own navigator, which
 * is the failure the rest of this file exists to prevent. `no device with a Garmin signal is ever
 * hidden` in `DeviceStatusTest` asserts the union as a property.
 */

/** How long a probe result stays worth *believing without asking again*. See [probePlan]. */
const val FRESH_MILLIS = 120_000L

/** How many devices one sweep will page. A bonded set of twenty must not become a radio sweep. */
const val PROBE_BUDGET = 4

/** SDP failing at pairing time is recorded as this, not as an empty list. See [capabilityOf]. */
private val NIL_UUID: UUID = UUID(0L, 0L)

/**
 * Every MA-L block IEEE has assigned to Garmin International.
 *
 * The first three octets of a Bluetooth address name the organisation that registered them, so
 * this says *Garmin built the radio* with no cache to consult, nothing to page, and nothing the
 * user can rename. It is the one signal that reaches a model this app has no profile for: a Flight
 * Stream, a GDL, a GTN Xi.
 *
 * Two of these were read off the navigators on the bench, `F0:99:19` and `0C:7E:24`; the rest come
 * from the IEEE registry. **It will go stale** the day Garmin registers a sixteenth, and that is
 * survivable rather than fine: a missing block hides a device only when no other signal fires, and
 * the sheet's own toggle undoes it. Refresh from the registry, not from a device.
 */
private val GARMIN_OUIS = setOf(
    "00:05:4F", "0C:7E:24", "10:4E:89", "10:C6:FC", "14:13:0B",
    "14:8F:21", "38:F9:F5", "60:3C:68", "64:A3:37", "90:F1:57",
    "A0:28:84", "B4:C2:6A", "C4:CB:33", "E0:48:24", "F0:99:19",
)

/**
 * Whether [address] was registered by Garmin.
 *
 * Upper cased rather than trusting the platform: `BluetoothDevice.getAddress()` documents upper
 * case and every device has returned it, but a comparison that depends on that is one OEM away
 * from hiding every navigator at once.
 */
internal fun garminOui(address: String): Boolean =
    address.length >= 8 && address.take(8).uppercase() in GARMIN_OUIS

/**
 * The clock [Probe] timestamps are taken from.
 *
 * Monotonic, so freshness never has to defend itself against the wall clock being adjusted, and
 * plain JVM, so it does not breach this file's rule against Android.
 */
fun nowMillis(): Long = System.nanoTime() / 1_000_000

/** What the SDP cache from pairing time says about a device. */
enum class Capability {
    /** The cache lists Garmin's Connext service. This speaks our protocol. */
    CONNEXT,

    /** The cache is populated and Connext is not in it: positive evidence of absence. */
    OTHER,

    /** The cache is empty or unreadable. No evidence either way, so no conclusions. */
    UNKNOWN,
}

/** Whether the device answered when last asked. */
enum class Liveness { LIVE, SILENT, UNKNOWN }

/** What the row should show at a glance, chosen here so the sheet only has to map it to a drawable. */
enum class Indicator {
    /** Nothing worth showing. */
    NONE,

    /** A probe is in flight, so the sheet draws a spinner rather than an icon. */
    CHECKING,

    /** It answered, or a link is already open. */
    LIVE,

    /** It was asked and stayed silent. */
    SILENT,
}

/**
 * The outcome of an active reachability probe.
 *
 * Deliberately carries no service list. `ACTION_UUID`'s payload cannot report SDP failure:
 * `RemoteDevices.sendUuidIntent` drops the success flag and broadcasts the *cached* UUIDs, so a
 * bonded but dead device still answers with a list. The stack does update its own cache when SDP
 * succeeds, so the next [GarminDevices.bonded] picks fresh UUIDs up for free.
 */
sealed interface Probe {
    data object NotProbed : Probe

    /**
     * A probe is in flight.
     *
     * [previous] is the verdict being rechecked, and it keeps being shown until a new one lands.
     * Without it, rechecking a device would make it look healthy for the six seconds the page
     * takes. See the note on unlearning at the top of this file.
     */
    data class Probing(val previous: Probe? = null) : Probe

    /** It answered. [atMillis] is monotonic, from [nowMillis]. */
    data class Live(val atMillis: Long) : Probe

    data class Silent(val atMillis: Long, val reason: Reason) : Probe

    enum class Reason {
        /** Asked, and nothing came back. Evidence about the device. */
        TIMED_OUT,

        /** The stack refused to ask. Evidence about this phone, not about the device. */
        REQUEST_REFUSED,
    }
}

/** One bonded device, reduced to what the rules below actually read. */
data class DeviceFacts(
    val address: String,
    val name: String,
    /** The SDP cache from pairing time. `null` means it had nothing to say, not "no services". */
    val cachedUuids: List<UUID>?,
    val probe: Probe = Probe.NotProbed,
    /** A link exists right now, possibly another app's. Never means "reachable". */
    val aclConnected: Boolean = false,
)

/**
 * The terse state shown after the address, or null when there is nothing to say.
 *
 * Named rather than worded, because this file has no resources to word it from and the sheet is
 * translated. The same reason [Indicator] is a name rather than a drawable.
 */
enum class Label { CHECKING, CONNECTED, NO_ANSWER, ANSWERED }

/** The sentence that explains a [Label], or null when none is needed. */
enum class Detail {
    /** A populated service cache with no Connext in it. */
    NO_CONNEXT,

    /** Another app holds the link. */
    LINK_HELD,

    /** It was asked and stayed silent. */
    UNREACHABLE,
}

/**
 * How long ago a verdict was taken, rounded to the unit the sheet says it in.
 *
 * The rounding is here and the wording is in the sheet, so the arithmetic stays testable on a
 * plain JVM while the words can be translated. "No answer" invites the question "since when?", and
 * without an answer the row reads as an assertion about now rather than a reading taken at a
 * moment.
 */
data class Age(val count: Long, val unit: Unit) {
    enum class Unit { JUST_NOW, SECONDS, MINUTES, HOURS }
}

/** What the sheet should show for one device. */
data class DeviceStatus(
    val capability: Capability,
    val liveness: Liveness,
    val occupied: Boolean,
    /** Show the row more faintly. Presentation only: it never stops the row being selected. */
    val dimmed: Boolean,
    val indicator: Indicator,
    val label: Label?,
    /** How long ago the verdict [label] reports was taken, where it reports one. */
    val age: Age?,
    val detail: Detail?,
    /** Whether offering to page this one device again would make sense. */
    val canRetry: Boolean,
    /**
     * Whether anything at all says this is a Garmin device.
     *
     * The sheet hides a row where this is false, and nothing else in this file reads it. See the
     * header for the union it is built from and the four rules that make hiding safe.
     */
    val garmin: Boolean,
)

/**
 * Reduce [facts] to what the sheet shows.
 *
 * Pure: no Android, no clock, no I/O. [nowMillis] is passed in so a caller classifies once when the
 * facts change, rather than a Composable recomposing differently for unchanged input.
 */
fun classify(
    facts: DeviceFacts,
    nowMillis: Long,
    /** Whether this app has completed an upload to this address. `DeviceHistory` knows. */
    hasUploaded: Boolean = false,
): DeviceStatus {
    val capability = capabilityOf(facts.cachedUuids)

    val checking = facts.probe is Probe.Probing
    val settled = settled(facts.probe)
    val liveness = livenessOf(facts)

    // Only two things are positive evidence of absence: a populated cache that does not list
    // Connext, and a device that was asked and stayed silent. Everything else leaves the row alone.
    val dimmed = capability == Capability.OTHER || liveness == Liveness.SILENT

    val age = when (settled) {
        is Probe.Live -> ago(settled.atMillis, nowMillis)
        is Probe.Silent -> ago(settled.atMillis, nowMillis)
        else -> null
    }

    val label = when {
        checking -> Label.CHECKING
        facts.aclConnected -> Label.CONNECTED
        liveness == Liveness.SILENT -> Label.NO_ANSWER
        liveness == Liveness.LIVE -> Label.ANSWERED
        else -> null
    }

    val detail = when {
        capability == Capability.OTHER -> Detail.NO_CONNEXT
        facts.aclConnected -> Detail.LINK_HELD
        liveness == Liveness.SILENT -> Detail.UNREACHABLE
        else -> null
    }

    return DeviceStatus(
        capability = capability,
        liveness = liveness,
        occupied = facts.aclConnected,
        dimmed = dimmed,
        indicator = when {
            checking -> Indicator.CHECKING
            liveness == Liveness.LIVE -> Indicator.LIVE
            liveness == Liveness.SILENT -> Indicator.SILENT
            else -> Indicator.NONE
        },
        label = label,
        // Only where the label reports a verdict. "connected" and "checking…" are about now, so
        // an age beside either would date something that has no date.
        age = age.takeIf { label == Label.NO_ANSWER || label == Label.ANSWERED },
        detail = detail,
        // Nothing to retry while one is already running, and no point offering it for a device the
        // cache has ruled out, since probePlan would refuse to page it anyway.
        canRetry = !checking && capability != Capability.OTHER && liveness != Liveness.LIVE,
        // A union of signals *for*, so anything known keeps its row. An upload is listed first
        // because it is the only one that cannot be wrong: this app spoke Connext to that address
        // and a navigator accepted a flight plan, whatever a stale cache now says about it.
        garmin = hasUploaded ||
            capability == Capability.CONNEXT ||
            garminOui(facts.address) ||
            looksLikeGarmin(facts.name),
    )
}

/**
 * Whether the device answered when last asked.
 *
 * Shared with [deviceOrder] rather than derived again there, so where a row *sits* and what it
 * *says* can never drift apart. Reads through [Probe.Probing] to the verdict being rechecked, which is
 * what stops a row moving, or brightening, for the six seconds a page takes.
 */
internal fun livenessOf(facts: DeviceFacts): Liveness {
    val settled = settled(facts.probe)
    return when {
        facts.aclConnected -> Liveness.LIVE
        settled is Probe.Live -> Liveness.LIVE
        // A refused request is evidence about this phone, not about the device.
        settled is Probe.Silent && settled.reason == Probe.Reason.TIMED_OUT -> Liveness.SILENT
        else -> Liveness.UNKNOWN
    }
}

/**
 * The verdict a probe state settles to.
 *
 * [Probe.Probing] with no previous verdict falls back to itself, which matches neither [Probe.Live]
 * nor [Probe.Silent], i.e. "still nothing known", which is correct.
 */
private fun settled(probe: Probe): Probe = (probe as? Probe.Probing)?.previous ?: probe

/**
 * Which addresses are worth paging, most promising first.
 *
 * A device the cache has already ruled out is never probed. That is what keeps this from being
 * antisocial: the car stereo and the headphones are excluded by positive evidence rather than by a
 * blocklist of names, so nothing pages a device that has no business being woken up.
 *
 * Not [deviceOrder]. This one spends a scarce radio budget, so it should lead with what is
 * *unknown*. Ranking a stale [Probe.Live] first would reconfirm something already believed ahead
 * of something never asked.
 */
fun probePlan(
    facts: List<DeviceFacts>,
    nowMillis: Long,
    busy: Boolean = false,
    budget: Int = PROBE_BUDGET,
    freshMillis: Long = FRESH_MILLIS,
): List<String> {
    if (busy) return emptyList()
    return facts
        .filter { worthProbing(it) && !probedRecently(it.probe, nowMillis, freshMillis) }
        .sortedByDescending { capabilityOf(it.cachedUuids) == Capability.CONNEXT }
        .take(budget)
        .map { it.address }
}

/**
 * Every address, the navigator you most likely want first.
 *
 * A permutation of [facts], never a subset. See the header. [lastUsed] is `DeviceHistory`'s map
 * from address to timestamp, and only the ordering between its values is read, so its wall clock
 * does not have to agree with [nowMillis].
 *
 * The keys, all descending:
 *
 *  1. [navigatorRank], which asks whether this is a navigator at all, by evidence.
 *  2. reachability: it answered, then nothing known, then it was asked and stayed silent.
 *  3. last used, most recent first. This is what tells `GPS175 6918` from `GPS175 CA40` before
 *     anything has been paged, so it decides the ordering on a cold start.
 *  4. name, then address. The address is what makes the order *total*: two units can be renamed to
 *     the same string, and without a final key the result would depend on the order the Bluetooth
 *     stack happened to enumerate its bonds in.
 */
fun deviceOrder(
    facts: List<DeviceFacts>,
    lastUsed: Map<String, Long> = emptyMap(),
): List<String> = facts
    .sortedWith(
        compareByDescending<DeviceFacts> { navigatorRank(it, lastUsed) }
            .thenByDescending { livenessRank(livenessOf(it)) }
            .thenByDescending { lastUsed[it.address] ?: 0L }
            .thenBy { it.name }
            .thenBy { it.address }
    )
    .map { it.address }

/**
 * What the SDP cache says.
 *
 * Two edge cases matter. The cache can be `null`, which is "no answer" and not "no services". And
 * when SDP fails during pairing with no EIR UUIDs to fall back on, the stack records a single UUID
 * of all zeros, so a list with entries in it is not evidence that services were seen.
 *
 * [CONNEXT_SPP_UUID] is referenced rather than copied, because a duplicated protocol constant in this
 * repository is a correction waiting to happen. It is a `val` at the top level, so it lives on
 * `ConnextLinkKt`, whose initialiser only calls `UUID.fromString`, and reading it here does not
 * load the `ConnextLink` class, which does touch Android. The tripwire for that is
 * `a cached Connext UUID is capability enough`. If it ever trips, move the constant to a file of
 * its own rather than copying it.
 */
internal fun capabilityOf(cachedUuids: List<UUID>?): Capability {
    val uuids = cachedUuids?.filterNot { it == NIL_UUID } ?: return Capability.UNKNOWN
    if (uuids.isEmpty()) return Capability.UNKNOWN
    return if (CONNEXT_SPP_UUID in uuids) Capability.CONNEXT else Capability.OTHER
}

/**
 * Whether a name looks like a Garmin navigator.
 *
 * A hint of last resort, used only to decide whether a device with *no* SDP cache is worth paging.
 * The name can be set by the user, so this can never rule a device out. See [capabilityOf], which
 * answers the same question from evidence.
 */
internal fun looksLikeGarmin(name: String): Boolean {
    val n = name.uppercase()
    return listOf("GPS 175", "GPS175", "GNC", "GNX", "GTN", "GI 275", "GARMIN", "FLIGHT STREAM")
        .any { n.contains(it) }
}

/** How long ago, rounded to the unit the sheet says it in. Never negative. */
internal fun ago(atMillis: Long, nowMillis: Long): Age {
    val seconds = ((nowMillis - atMillis) / 1_000).coerceAtLeast(0)
    return when {
        seconds < 5 -> Age(0, Age.Unit.JUST_NOW)
        seconds < 60 -> Age(seconds, Age.Unit.SECONDS)
        seconds < 3_600 -> Age(seconds / 60, Age.Unit.MINUTES)
        else -> Age(seconds / 3_600, Age.Unit.HOURS)
    }
}

/**
 * How much reason there is to think this is a navigator, strongest first.
 *
 * The four values matter. [worthProbing] answers nearly the same question as a boolean, and cannot
 * be used here: it is false both for a device ruled *out* and for one nothing is known about, so a
 * connected car stereo would tie with a renamed navigator and then win on liveness.
 *
 *  * **3**, evidence. An upload completed against this address, or the cache written at pairing
 *    time lists Connext. A completed upload is the stronger of the two and is checked first:
 *    `DeviceHistory` is written only after a navigator has accepted a flight plan, so it outranks a
 *    cache that says otherwise, which is the stale cache the header warns about.
 *  * **2**, a guess. No cache, but the name looks like a navigator. [looksLikeGarmin] is confined
 *    to this tier on purpose, because a name the user can set must not reorder two devices there
 *    is evidence about.
 *  * **1**, nothing known.
 *  * **0**, evidence against: a populated cache with no Connext in it.
 */
private fun navigatorRank(facts: DeviceFacts, lastUsed: Map<String, Long>): Int = when {
    facts.address in lastUsed -> 3
    else -> when (capabilityOf(facts.cachedUuids)) {
        Capability.CONNEXT -> 3
        Capability.UNKNOWN -> if (looksLikeGarmin(facts.name)) 2 else 1
        Capability.OTHER -> 0
    }
}

/**
 * Reachability as a sort key.
 *
 * Spelled out rather than taken from `Liveness.ordinal`, which would be wrong: the enum is declared
 * `LIVE, SILENT, UNKNOWN`, so its ordinals would sink an unprobed device below one that was asked
 * and stayed silent, which is dimming on absence of evidence in the ordering dimension.
 */
private fun livenessRank(liveness: Liveness): Int = when (liveness) {
    Liveness.LIVE -> 2
    Liveness.UNKNOWN -> 1
    Liveness.SILENT -> 0
}

private fun worthProbing(facts: DeviceFacts): Boolean = when (capabilityOf(facts.cachedUuids)) {
    Capability.CONNEXT -> true
    Capability.OTHER -> false
    Capability.UNKNOWN -> looksLikeGarmin(facts.name)
}

private fun probedRecently(probe: Probe, nowMillis: Long, freshMillis: Long): Boolean = when (probe) {
    is Probe.Probing -> true
    is Probe.Live -> isFresh(probe.atMillis, nowMillis, freshMillis)
    is Probe.Silent -> isFresh(probe.atMillis, nowMillis, freshMillis)
    Probe.NotProbed -> false
}

/** Timestamps come from [nowMillis], so this needs no defence against a clock going back. */
private fun isFresh(atMillis: Long, nowMillis: Long, freshMillis: Long): Boolean =
    nowMillis - atMillis < freshMillis
