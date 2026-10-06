package app.sendfpl.bt

import java.util.UUID

/*
 * Everything decidable about a bonded device. No `android.*` imports, so these rules run as plain
 * JVM unit tests; the Android calls live in `GarminDevices` and `DeviceProbe`, and the sheet words
 * and draws the states named here.
 *
 * Classic Bluetooth keeps no standing link: AOSP connects only Headset and A2DP on its own, and
 * L2CAP drops an idle link soon after its last channel closes. A powered navigator in range is
 * not connected until this app opens a socket, so "connected" is not "usable". Three signals:
 *
 *  * capability, from the SDP cache written at pairing time: free, and better than the name,
 *    which the user can set to anything;
 *  * reachability, from an active probe: seconds, and it pages the radio;
 *  * occupancy, from `BluetoothDevice.isConnected()`: free, and never means "unreachable".
 *
 * Each can be wrong (a cache older than a firmware change, a probe that timed out while the radio
 * was busy with audio), so a row dims only on positive evidence of absence, never on absence of
 * evidence, and dimming never disables it. A verdict is never unlearned: forgetting a stale "no
 * answer" would show a switched-off navigator as healthy while it is paged again, so the verdict
 * stands until a new one replaces it, and its age goes in the wording.
 *
 * The sheet hides rows only where [DeviceStatus.garmin] is false, and that is safe because it
 * never hides the selected device, states how many rows it hid, and shows them all in one tap, a
 * choice it remembers.
 */

/** How long a probe result is believed without asking again. See [probePlan]. */
const val FRESH_MILLIS = 120_000L

/** How many devices one sweep will page, so twenty bonds do not become a radio sweep. */
private const val PROBE_BUDGET = 4

/**
 * What the stack caches when SDP fails at pairing time with no EIR UUIDs to fall back on: one UUID
 * of all zeros, so a list with entries in it is not evidence that services were seen.
 */
private val NIL_UUID: UUID = UUID(0L, 0L)

/**
 * Every MA-L block IEEE has assigned to Garmin International. An address in one says Garmin built
 * the radio, with nothing to page and nothing the user can rename, and it reaches models with no
 * profile here: a Flight Stream, a GDL. Refresh from the IEEE registry, not from a device. A
 * missing block hides a device only when no other signal fires.
 */
private val GARMIN_OUIS = setOf(
    "00:05:4F", "0C:7E:24", "10:4E:89", "10:C6:FC", "14:13:0B",
    "14:8F:21", "38:F9:F5", "60:3C:68", "64:A3:37", "90:F1:57",
    "A0:28:84", "B4:C2:6A", "C4:CB:33", "E0:48:24", "F0:99:19",
)

/**
 * Whether [address] was registered by Garmin. Upper cased although `BluetoothDevice.getAddress()`
 * documents upper case, so one OEM that differs cannot hide every navigator at once.
 */
internal fun garminOui(address: String): Boolean =
    address.length >= 8 && address.take(8).uppercase() in GARMIN_OUIS

/**
 * The clock [Probe] timestamps are taken from: monotonic, so freshness ignores changes to the wall
 * clock, and plain JVM, so this file stays free of Android.
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

/** What the row shows at a glance. */
enum class Indicator {
    /** Nothing worth showing. */
    NONE,

    /** A probe is in flight. */
    CHECKING,

    /** It answered, or a link is already open. */
    LIVE,

    /** It was asked and stayed silent. */
    SILENT,
}

/**
 * The outcome of an active reachability probe. It carries no service list: the `ACTION_UUID`
 * payload is the cached list even when SDP fails (see [DeviceProbe]), and an SDP that succeeds
 * updates the cache the next [GarminDevices.bonded] reads.
 */
sealed interface Probe {
    data object NotProbed : Probe

    /** In flight. [previous] is the verdict being rechecked, shown until a new one lands. */
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

/** One bonded device, reduced to what the rules read. */
data class DeviceFacts(
    val address: String,
    val name: String,
    /** The SDP cache from pairing time. `null` means it had nothing to say, not "no services". */
    val cachedUuids: List<UUID>?,
    val probe: Probe = Probe.NotProbed,
    /** A link exists right now, possibly another app's. Never means "reachable". */
    val aclConnected: Boolean = false,
)

/** The terse state shown after the address. */
enum class Label { CHECKING, CONNECTED, NO_ANSWER, ANSWERED }

/** The sentence that explains the row's state. */
enum class Detail {
    /** A populated service cache with no Connext in it. */
    NO_CONNEXT,

    /** Another app holds the link. */
    LINK_HELD,

    /** It was asked and stayed silent. */
    UNREACHABLE,
}

/** How long ago a verdict was taken, rounded to the unit the sheet says it in. */
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
     * Whether anything at all says Garmin: an upload this app completed to the address, Connext in
     * the cache (Garmin Pilot's own test), a Garmin address block, or a name that looks like one.
     * Any one keeps the row when the sheet hides the rest.
     */
    val garmin: Boolean,
)

/**
 * Reduce [facts] to what the sheet shows. Pure, with [nowMillis] passed in, so a caller classifies
 * once when the facts change rather than a Composable recomposing differently for the same input.
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

    // The only positive evidence of absence: a populated cache that does not list Connext, and a
    // device that was asked and stayed silent.
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
        // Only beside a verdict: "connected" and "checking…" are about now.
        age = age.takeIf { label == Label.NO_ANSWER || label == Label.ANSWERED },
        detail = detail,
        // Not while a probe runs, and not for a device the cache has ruled out, which a sweep
        // never pages either.
        canRetry = !checking && capability != Capability.OTHER && liveness != Liveness.LIVE,
        garmin = saysGarmin(facts, hasUploaded),
    )
}

/** [DeviceStatus.garmin], apart so the selection reads the same rule. */
private fun saysGarmin(facts: DeviceFacts, hasUploaded: Boolean): Boolean =
    hasUploaded ||
        capabilityOf(facts.cachedUuids) == Capability.CONNEXT ||
        garminOui(facts.address) ||
        looksLikeGarmin(facts.name)

/**
 * The address to aim at. [current], the device aimed at so far, stands while it is bonded: nothing
 * learned replaces it, since quietly aiming a flight plan at another navigator is what this app
 * must never do. With none, or once it is unpaired, the navigator most recently sent to, else the
 * head of [deviceOrder] among the devices [DeviceStatus.garmin] keeps. An empty list keeps
 * [current]: the radio returns no bonds in any state but on.
 */
fun selection(
    facts: List<DeviceFacts>,
    current: String?,
    lastUsed: Map<String, Long> = emptyMap(),
): String? {
    if (facts.isEmpty()) return current
    val byAddress = facts.associateBy { it.address }
    if (current in byAddress) return current
    lastUsed.entries
        .filter { it.key in byAddress }
        .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
        .firstOrNull()
        ?.let { return it.key }
    return deviceOrder(facts, lastUsed).firstOrNull { address ->
        saysGarmin(byAddress.getValue(address), address in lastUsed)
    }
}

/**
 * Whether another navigator answered while [selected] was asked and stayed silent: worth a line
 * beside the chip, never a reason to change the selection. A navigator is what [navigatorRank]
 * ranks 2 or more, so a link held by headphones does not count.
 */
fun anotherAnswers(
    facts: List<DeviceFacts>,
    selected: String?,
    lastUsed: Map<String, Long> = emptyMap(),
): Boolean {
    val mine = facts.find { it.address == selected } ?: return false
    if (livenessOf(mine) != Liveness.SILENT) return false
    return facts.any {
        it.address != selected &&
            livenessOf(it) == Liveness.LIVE &&
            navigatorRank(it, lastUsed) >= 2
    }
}

/**
 * Whether the device answered when last asked, read through [Probe.Probing] to the verdict being
 * rechecked, so a page in progress neither moves nor brightens a row. Shared with [deviceOrder], so
 * where a row sits and what it says cannot drift apart.
 */
internal fun livenessOf(facts: DeviceFacts): Liveness {
    val settled = settled(facts.probe)
    return when {
        facts.aclConnected -> Liveness.LIVE
        settled is Probe.Live -> Liveness.LIVE
        settled is Probe.Silent && settled.reason == Probe.Reason.TIMED_OUT -> Liveness.SILENT
        else -> Liveness.UNKNOWN
    }
}

/** The verdict a probe state settles to. A [Probe.Probing] with none settles to itself. */
private fun settled(probe: Probe): Probe = (probe as? Probe.Probing)?.previous ?: probe

/**
 * Which addresses are worth paging, most promising first. A device the cache has ruled out is
 * never paged, so car stereos and headphones are left alone on evidence, not by a list of names.
 * Not [deviceOrder]'s order: this spends a radio budget, and ranking what already answered first
 * would reconfirm it ahead of a device never asked.
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
 * Every address, the navigator you most likely want first: a permutation of [facts], never a
 * subset. Ranked by [navigatorRank]; then reachability (answered, nothing known, silent); then
 * most recent use, which tells one `GPS175` from another before anything is paged; then name and
 * address, so two units with the same name keep a stable order. [lastUsed] holds wall clock times
 * from `DeviceHistory`, of which only the order is read. No clock is taken: a row moves when
 * something is learned, never because a verdict aged.
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
 * What the SDP cache says. [CONNEXT_SPP_UUID] is a top-level `val` on `ConnextLinkKt`, whose
 * initialiser only calls `UUID.fromString`, so reading it does not load the Android `ConnextLink`
 * class. The test `a cached Connext UUID is capability enough` trips if that changes: then move
 * the constant to a file of its own, never copy it.
 */
internal fun capabilityOf(cachedUuids: List<UUID>?): Capability {
    val uuids = cachedUuids?.filterNot { it == NIL_UUID } ?: return Capability.UNKNOWN
    if (uuids.isEmpty()) return Capability.UNKNOWN
    return if (CONNEXT_SPP_UUID in uuids) Capability.CONNEXT else Capability.OTHER
}

/**
 * Whether a name looks like a Garmin navigator. A hint only, since the user can set the name: it
 * can make a device with no SDP cache worth paging and rank it as a guess, and keep a row from
 * being hidden, but it never rules a device out.
 */
internal fun looksLikeGarmin(name: String): Boolean {
    val n = name.uppercase()
    return listOf("GPS 175", "GPS175", "GNC", "GNX", "GTN", "GI 275", "GARMIN", "FLIGHT STREAM")
        .any { n.contains(it) }
}

/** How long ago [atMillis] was, as an [Age]. Never negative. */
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
 * How much reason there is to think this is a navigator. Four values, not [worthProbing]'s two,
 * which are false both for a device ruled out and for one nothing is known about: a connected car
 * stereo would then tie with a renamed navigator and win on liveness.
 *
 *  * 3: an upload completed to this address, or Connext in the cache. The upload is checked first:
 *    `DeviceHistory` is written only after a navigator accepted a flight plan, so it outranks a
 *    stale cache.
 *  * 2: no cache, but a name that looks like a navigator. The name decides no other tier, since
 *    a name the user can set must not reorder devices there is evidence about.
 *  * 1: nothing known.
 *  * 0: a populated cache with no Connext in it.
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
 * Reachability as a sort key. Not `Liveness.ordinal`: the enum is declared `LIVE, SILENT, UNKNOWN`,
 * so its ordinals would sink an unprobed device below one that was asked and stayed silent.
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

private fun isFresh(atMillis: Long, nowMillis: Long, freshMillis: Long): Boolean =
    nowMillis - atMillis < freshMillis
