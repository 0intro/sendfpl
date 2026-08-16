package app.sendfpl.ui

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sendfpl.BuildConfig
import app.sendfpl.R
import app.sendfpl.UiState
import app.sendfpl.bt.Age
import app.sendfpl.bt.Capability
import app.sendfpl.bt.Detail
import app.sendfpl.bt.DeviceStatus
import app.sendfpl.bt.Indicator
import app.sendfpl.bt.Label
import app.sendfpl.bt.PairedDevice
import app.sendfpl.cxp.Element
import app.sendfpl.cxp.Profiles
import app.sendfpl.cxp.WaypointType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/**
 * Where to write, shown inline wherever the app invites a reply.
 *
 * The tracker rather than an address: a report there is public, so the next person with the same
 * navigator finds it instead of sending the same mail again. Printed inline rather than linked,
 * because this app opens nothing, and the effect of routing it here is that no address of the
 * author's ships in the binary at all. Play carries one on the store listing, which it requires
 * and displays, for anyone who would rather write privately.
 */
const val CONTACT_NAME = "David du Colombier"
const val PROJECT_URL = "github.com/0intro/sendfpl"
const val ISSUES_URL = "$PROJECT_URL/issues"

/**
 * Paired devices and, once a session has run, what the navigator told us about itself.
 *
 * This doubles as the diagnostic surface: negotiated parameters and advertised capabilities are
 * exactly what you want to see when a transfer behaves oddly.
 *
 * Rows the app has positive reason to doubt are dimmed and say why, but **every row stays
 * selectable**. Each signal behind the dimming can be wrong in the direction that matters (a
 * service cache written before a firmware change, a probe that timed out because the phone's radio
 * was busy), and a false negative that locks a pilot out of their own navigator is far worse than
 * a tap that fails with a clear message. See `bt/DeviceStatus.kt`.
 */
@Composable
fun DeviceSheet(
    state: UiState,
    onSelect: (PairedDevice) -> Unit,
    onRefresh: () -> Unit,
    onRetry: (PairedDevice) -> Unit,
    onSelectProfile: (String) -> Unit = {},
    onShowAllDevices: (Boolean) -> Unit = {},
) {
    // The selected device is listed whatever the filter says. It is the one row whose absence the
    // pilot would certainly notice, since the chip on the home screen names it, and a list that
    // disagrees with the chip is worse than one row too many.
    val visible = state.devices.filter {
        state.showAllDevices ||
            state.status[it.address]?.garmin != false ||
            it.address == state.selected?.address
    }
    val hidden = state.devices.size - visible.size
    Column(
        // Scrollable, because this sheet is taller than a phone. A bottom sheet clips whatever
        // does not fit and offers nothing to drag, so at a system font scale of 1.3 the model
        // picker's limits and the whole note under it, including the address a GTN Xi owner is
        // invited to write to, were simply unreachable. Four paired devices does the same thing
        // at the default scale. LogSheet must **not** get this: it holds a LazyColumn, and a lazy
        // list inside a scrolling parent is measured with unbounded height and throws.
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.devices_title),
                style = MaterialTheme.typography.titleMedium,
            )
            TextButton(onClick = onRefresh) { Text(stringResource(R.string.action_refresh)) }
        }

        // Paging a device takes seconds, one device at a time. Without this the sheet just sits
        // there, and the row's own "checking…" is too quiet to explain a wait of twelve seconds.
        if (state.status.values.any { it.indicator == Indicator.CHECKING }) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        if (state.devices.isEmpty()) {
            Text(
                stringResource(R.string.devices_empty),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            visible.forEach { device ->
                key(device.address) {
                    DeviceRow(
                        device = device,
                        status = state.status[device.address],
                        lastUsed = state.lastUsed[device.address],
                        selected = device.address == state.selected?.address,
                        onSelect = { onSelect(device) },
                        onRetry = { onRetry(device) },
                    )
                }
            }
            FilterNote(
                showingAll = state.showAllDevices,
                hidden = hidden,
                onShowAllDevices = onShowAllDevices,
            )
        }

        val negotiated = state.info.negotiated
        val caps = state.info.capabilities
        if (negotiated != null || caps != null) {
            HorizontalDivider()
            Text(
                stringResource(R.string.session_title),
                style = MaterialTheme.typography.titleMedium,
            )

            negotiated?.let {
                Field(
                    stringResource(R.string.session_transport),
                    stringResource(
                        R.string.session_transport_value, it.maxSz, it.mxOut, it.toRetry
                    ),
                )
                Field(
                    stringResource(R.string.session_session),
                    if (it.isV2) stringResource(R.string.session_v2, it.syncId ?: 0L)
                    else stringResource(R.string.session_v1),
                )
            }
            caps?.let { c ->
                Field(
                    stringResource(R.string.session_max_route),
                    pluralStringResource(
                        R.plurals.session_max_route_value, c.maxTextLength, c.maxTextLength,
                    ),
                )
                Field(stringResource(R.string.session_elements), describeElements(c.elements))
                Field(
                    stringResource(R.string.session_waypoint_formats),
                    describeWaypointTypes(c.waypointTypes),
                )
            }
        }

        HorizontalDivider()
        Text(stringResource(R.string.model_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.model_help),
            style = MaterialTheme.typography.bodySmall,
        )
        // FlowRow and not Row: one of these chips is called "GNX 375/GPS 175/GNC 355", because
        // three models share one profile, and at a larger font scale the pair no longer fits a
        // line. A Row squeezes the second chip instead of wrapping, which turned "GTN 6xx/7xx"
        // into three stacked lines inside a chip.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for ((name, profile) in Profiles.selectable) {
                FilterChip(
                    selected = profile.productId == state.device.productId,
                    onClick = { onSelectProfile(name) },
                    label = { Text(profile.name) },
                )
            }
        }
        Field(
            stringResource(R.string.model_limits),
            pluralStringResource(
                R.plurals.model_limits_value,
                state.device.maxRouteLen,
                state.device.waypointNameLen,
                state.device.airportNameLen,
                state.device.maxRouteLen,
            ),
        )

        WantedNavigators()
    }
}

/**
 * Why the list is short, and an invitation to make it longer.
 *
 * Placed under the model picker because that is where someone with an unlisted navigator finds two
 * chips and no explanation. Only the GTN Xi is named: its parser is present and its identifier
 * limits were not recovered, so hardware would add it. The TXi and GDU 620 are left out of the ask
 * entirely, because they have no route parser at all, so asking for access would imply support is
 * possible when the evidence says otherwise.
 *
 * It stands on its own by rule: the address is inline, because nobody reading this will go
 * looking for a repository to find out where to write. That it is now the tracker rather than a
 * mailbox suits this ask in particular, since an offer of hardware is worth other people seeing.
 */
@Composable
private fun WantedNavigators() {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    HorizontalDivider()
    Text(stringResource(R.string.model_why_two_title), style = MaterialTheme.typography.titleSmall)
    Text(
        stringResource(R.string.model_why_two_body),
        style = MaterialTheme.typography.bodySmall,
        color = muted,
    )
    Text(
        stringResource(R.string.model_wanted_gtn_xi, ISSUES_URL),
        style = MaterialTheme.typography.bodySmall,
        color = muted,
    )
}

/**
 * What the filter did, and how to undo it.
 *
 * **Hiding a row is only defensible because this line is always there.** The state is named either
 * way, the count is exact, and the way back is one tap, so a device the predicate gets wrong costs
 * a pilot a tap rather than their navigator. See `bt/DeviceStatus.kt` for the predicate and why it
 * is the one exception to that file's rule against filtering.
 *
 * Nothing is drawn when the filter is on and it hid nothing, which is the ordinary case for a
 * phone paired only to a navigator: a line reporting zero would be noise on every opening of the
 * sheet.
 */
@Composable
private fun FilterNote(showingAll: Boolean, hidden: Int, onShowAllDevices: (Boolean) -> Unit) {
    if (!showingAll && hidden == 0) return
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (showingAll) stringResource(R.string.devices_showing_all)
            else pluralStringResource(R.plurals.devices_hidden, hidden, hidden),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { onShowAllDevices(!showingAll) }) {
            Text(
                stringResource(
                    if (showingAll) R.string.devices_show_garmin_only
                    else R.string.devices_show_all
                )
            )
        }
    }
}

/**
 * One device.
 *
 * The state is carried by an icon and by words, and only secondarily by
 * [MaterialTheme.colorScheme.onSurfaceVariant], never by the disabled alpha, which *means*
 * disabled and would be a lie on a row that is still tappable. Colour alone would not do the job
 * anyway: it fails colour blindness, it fails a screen washed out by daylight, and it fails
 * completely in the common case where *every* device is dim, since dimness is only legible by
 * comparison. The icon still differs from a live one when there is nothing to compare against.
 */
@Composable
private fun DeviceRow(
    device: PairedDevice,
    status: DeviceStatus?,
    lastUsed: Long?,
    selected: Boolean,
    onSelect: () -> Unit,
    onRetry: () -> Unit,
) {
    val dimmed = status?.dimmed == true
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Null onClick: the whole row is the target, so the button must not be one of its own.
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))

        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    device.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (dimmed) muted else MaterialTheme.colorScheme.onSurface,
                )
                if (status?.capability == Capability.CONNEXT) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            stringResource(R.string.device_connext_badge),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                StateIcon(status?.indicator)
            }

            // The state qualifies the address, so it reads as a parenthesis rather than as a
            // second item in a list. A row with nothing to say is the address alone.
            val label = status?.label?.let { labelText(it, status.age) }
            Text(
                text = label
                    ?.let { stringResource(R.string.device_address_with_state, device.address, it) }
                    ?: device.address,
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )

            status?.detail?.let { detail ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        detailText(detail),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = muted,
                    )
                    // Page this one device again. A pilot who has just switched the unit on should
                    // not have to page every navigator again to find that out.
                    if (status.canRetry) {
                        TextButton(onClick = onRetry) {
                            Text(stringResource(R.string.action_retry))
                        }
                    }
                }
            }

            // What tells two identically named units apart. See `deviceOrder`, where the same
            // fact is an ordering key.
            lastUsed?.let {
                Text(
                    lastUsedText(it),
                    style = MaterialTheme.typography.labelSmall,
                    color = muted,
                )
            }
        }
    }
}

/** The state at a glance. A spinner while paging, because that takes seconds and looks like a hang. */
@Composable
private fun StateIcon(indicator: Indicator?) {
    when (indicator) {
        Indicator.CHECKING -> CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
        )

        Indicator.LIVE -> Icon(
            Icons.Default.BluetoothConnected,
            contentDescription = stringResource(R.string.device_icon_answered),
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary,
        )

        Indicator.SILENT -> Icon(
            Icons.Default.BluetoothDisabled,
            contentDescription = stringResource(R.string.device_icon_no_answer),
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Indicator.NONE, null -> Unit
    }
}

/**
 * The verdict a row reports, with how long ago it was taken.
 *
 * `bt/DeviceStatus.kt` decides *what* to say and rounds the age; the wording is here, because that
 * file has no resources and is deliberately free of Android so its rules stay unit-testable.
 */
@Composable
private fun labelText(label: Label, age: Age?): String = when (label) {
    Label.CHECKING -> stringResource(R.string.device_label_checking)
    Label.CONNECTED -> stringResource(R.string.device_label_connected)
    Label.NO_ANSWER -> stringResource(R.string.device_label_no_answer, ageText(age))
    Label.ANSWERED -> stringResource(R.string.device_label_answered, ageText(age))
}

@Composable
private fun ageText(age: Age?): String = when (age?.unit) {
    null, Age.Unit.JUST_NOW -> stringResource(R.string.age_just_now)
    Age.Unit.SECONDS -> pluralStringResource(R.plurals.age_seconds, age.count.toInt(), age.count)
    Age.Unit.MINUTES -> pluralStringResource(R.plurals.age_minutes, age.count.toInt(), age.count)
    Age.Unit.HOURS -> pluralStringResource(R.plurals.age_hours, age.count.toInt(), age.count)
}

@Composable
private fun detailText(detail: Detail): String = stringResource(
    when (detail) {
        Detail.NO_CONNEXT -> R.string.device_detail_no_connext
        Detail.LINK_HELD -> R.string.device_detail_link_held
        Detail.UNREACHABLE -> R.string.device_detail_unreachable
    }
)

/**
 * The date pattern comes from the locale rather than from a literal, so a French phone reads
 * "14 août" and an American one "Aug 14".
 *
 * `LocalLocale` and not `Locale.getDefault()`: the latter is not observable state, so a sheet left
 * open across a locale change would keep formatting in the old language.
 */
@Composable
private fun lastUsedText(atMillis: Long): String {
    val then = Instant.ofEpochMilli(atMillis).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    val pattern = if (then.year == today.year) "d MMM" else "d MMM yyyy"
    return when {
        then == today -> stringResource(R.string.device_last_used_today)
        then == today.minusDays(1) -> stringResource(R.string.device_last_used_yesterday)
        else -> stringResource(
            R.string.device_last_used_on,
            then.format(DateTimeFormatter.ofPattern(pattern, LocalLocale.current.platformLocale)),
        )
    }
}

/** A label above a value, which is how every fact on this sheet is laid out. */
@Composable
private fun Field(label: String, value: String) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun describeElements(mask: Int): String = describeMask(
    mask,
    Element.DEPARTURE to R.string.element_departure,
    Element.ARRIVAL to R.string.element_arrival,
    Element.APPROACH to R.string.element_approach,
    Element.WPT to R.string.element_waypoints,
    Element.AIRWAY_DOT_NOTATION to R.string.element_airways,
    Element.HOLD_AT_WPT to R.string.element_holds,
    Element.ALONG_WPT to R.string.element_along_track,
)

/**
 * The notations a navigator accepts for a waypoint, which is a separate byte from the element mask, and
 * what gates whether a coordinate can be sent at all.
 */
@Composable
private fun describeWaypointTypes(mask: Int): String = describeMask(
    mask,
    WaypointType.PUB_FMT to R.string.waypoint_format_published,
    WaypointType.LAT_LON_USER to R.string.waypoint_format_latlon,
    WaypointType.PB_PB_FMT to R.string.waypoint_format_pbpb,
    WaypointType.PDB_FMT to R.string.waypoint_format_pbd,
)

/** The bits set in [mask], named and joined, or "none advertised" when it is empty. */
@Composable
private fun describeMask(mask: Int, vararg bits: Pair<Int, Int>): String {
    val names = bits.filter { (bit, _) -> mask and bit != 0 }
        .map { (_, name) -> stringResource(name) }
    return if (names.isEmpty()) stringResource(R.string.session_none_advertised)
    else names.joinToString(", ")
}

/**
 * The CXP exchange, copyable.
 *
 * The protocol is recovered by reverse engineering, and a good deal of it has never met a link
 * that misbehaves, so when something fails the useful artefact is this rather than a message
 * saying it failed. It is written in English whatever the phone's language, because it is destined
 * for a bug report.
 */
@Composable
fun LogSheet(lines: List<String>, onClear: () -> Unit) {
    val clipboard = LocalClipboard.current
    // Putting a clip is a suspend call now, so the button needs a scope to run it in. Tied to this
    // composition, which is right for a sheet: dismiss it during a copy and the copy is abandoned
    // with it, rather than outliving the thing that asked for it.
    val scope = rememberCoroutineScope()
    // Android 13 and newer show this in the copy confirmation, and it is the only place the phone
    // says which app just took the clipboard.
    val clipLabel = stringResource(R.string.log_clip_label)
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.log_title), style = MaterialTheme.typography.titleMedium)
            Row {
                TextButton(
                    onClick = {
                        // Joined outside the coroutine, so what lands on the clipboard is the log
                        // as it read when the button was pressed, not as it reads whenever the
                        // copy happens to run.
                        val text = lines.joinToString("\n")
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(clipLabel, text)))
                        }
                    },
                ) {
                    Text(stringResource(R.string.action_copy))
                }
                TextButton(onClick = onClear) { Text(stringResource(R.string.action_clear)) }
            }
        }
        if (lines.isEmpty()) {
            Text(stringResource(R.string.log_empty), style = MaterialTheme.typography.bodyMedium)
        } else {
            // Wrapped, not clipped to one scrollable line each. Every line used to carry its own
            // horizontal scroll state, which meant reading a log of fifty lines took fifty separate
            // swipes, and inside a LazyColumn those states are reset as items are recycled, so even
            // that did not survive scrolling. The one line worth reading is usually the failure,
            // and it is always the longest.
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(lines) { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            // Hanging indent, so a wrapped continuation is not mistaken for the
                            // next packet.
                            textIndent = TextIndent(restLine = 12.sp),
                        ),
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
                }
            }
        }
    }
}

/**
 * The credential, and what to do if it ever stops working.
 *
 * The app carries one, so this is a replacement path rather than a first run step, and it exists
 * for a real reason rather than a hypothetical one: the credential is a single fixed secret, and
 * if Garmin changes it every shipped version stops authenticating at once.
 */
@Composable
fun SetupSheet(credentialPresent: Boolean, onImport: () -> Unit, onForget: () -> Unit) {
    Column(
        // Scrollable for the same reason as DeviceSheet.
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.credential_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(R.string.credential_body),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            stringResource(R.string.credential_replace_body),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "{\"user_id\": 1, \"token\": \"<96 hex chars>\", \"entitlement\": \"<hex>\"}",
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onImport) {
                Text(
                    stringResource(
                        if (credentialPresent) R.string.credential_replace
                        else R.string.credential_import
                    )
                )
            }
            if (credentialPresent) {
                TextButton(onClick = onForget) { Text(stringResource(R.string.credential_forget)) }
            }
        }
        if (credentialPresent) {
            Text(
                stringResource(R.string.credential_stored_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * What the store listing has to say anyway, said in the app as well.
 *
 * The long form is published at 0intro.github.io/sendfpl/privacy.html, and the repository carries
 * a README and a LICENSE, but none of that is reachable from a cockpit. This is the copy a pilot
 * with the app already open can read: everything is inline text, with no links out, the repository
 * address included, which also means no `INTERNET` permission is implied. Keep it in step with
 * `www/privacy.html`.
 *
 * The Pilot responsibility paragraph below is the canonical wording, and it is repeated verbatim
 * in three places nothing checks: `README.md`, `www/index.html`, and the SAFETY section of
 * `app/src/main/play/listings/en-US/full-description.txt`. They said three different things until
 * they were unified; changing one means changing all four.
 */
@Composable
fun AboutSheet(onOpenSetup: () -> Unit = {}) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        // Scrollable for the same reason as DeviceSheet: without it the Contact section, which is
        // the only address in the app, sat below the fold with no way to reach it.
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(R.string.about_tagline),
            style = MaterialTheme.typography.bodyMedium,
        )

        HorizontalDivider()
        Text(
            stringResource(R.string.about_responsibility_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            stringResource(R.string.about_responsibility_body),
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )

        HorizontalDivider()
        Text(
            stringResource(R.string.about_trademark_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            stringResource(R.string.about_trademark_body, CONTACT_NAME),
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )

        HorizontalDivider()
        Text(
            stringResource(R.string.about_privacy_title),
            style = MaterialTheme.typography.titleSmall,
        )
        // The route is deliberately not in this list. It lives in memory for as long as the app
        // is open and is never written anywhere, so claiming it as stored was over-declaring.
        Text(
            stringResource(R.string.about_privacy_body),
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )
        // Here rather than in a section of its own, because the paragraph above is what it backs.
        // The address is printed, not linked, for the reason in this function's comment.
        Text(
            stringResource(R.string.about_licence, PROJECT_URL),
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )

        HorizontalDivider()
        Text(stringResource(R.string.credential_title), style = MaterialTheme.typography.titleSmall)
        // The only way in. The setup sheet's other entry point is the card on the home screen,
        // which renders only when no credential is stored, and every shipped build carries one,
        // so without this the replacement path was unreachable in exactly the situation it was
        // written for: Garmin changing the secret under a released app.
        Text(
            stringResource(R.string.about_credential_body),
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )
        TextButton(onClick = onOpenSetup) {
            Text(stringResource(R.string.about_manage_credential))
        }

        HorizontalDivider()
        Text(
            stringResource(R.string.about_feedback_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            stringResource(R.string.about_feedback_body),
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )
        Text(
            ISSUES_URL,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
