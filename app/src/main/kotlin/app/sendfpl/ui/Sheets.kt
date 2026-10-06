package app.sendfpl.ui

import android.content.ClipData
import android.text.format.DateFormat
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

// Linked on the About page. Replies go to the public tracker, not to a mail address, so the next
// owner of the same navigator finds the report.
internal const val CONTACT_NAME = "David du Colombier"
internal const val PROJECT_URL = "github.com/0intro/sendfpl"
internal const val ISSUES_URL = "$PROJECT_URL/issues"

/**
 * Paired devices and, once a session has run, what the navigator told us about itself, which is
 * what to look at when a transfer behaves oddly. Rows the app has positive reason to doubt are
 * dimmed and say why, but every row stays selectable. See `bt/DeviceStatus.kt`.
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
    // The selected device is listed whatever the filter says: the home screen's chip names it.
    val visible = state.devices.filter {
        state.showAllDevices ||
            state.status[it.address]?.garmin != false ||
            it.address == state.selected?.address
    }
    val hidden = state.devices.size - visible.size
    Column(
        // Scrollable: a bottom sheet clips what does not fit, and this one outgrows a phone at a
        // large font scale or with a few paired devices. Not LogSheet: its LazyColumn would be
        // measured with unbounded height inside a scrolling parent, and throw.
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

        // Paging takes seconds per device, too long a wait for the row's own "checking…" alone.
        if (state.status.values.any { it.indicator == Indicator.CHECKING }) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        if (state.devices.isEmpty()) {
            Text(
                stringResource(
                    when {
                        !state.permissionsGranted -> R.string.blocked_permission
                        !state.bluetoothEnabled -> R.string.blocked_bluetooth_off
                        else -> R.string.devices_empty
                    }
                ),
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
                        // A send keeps the device it started with.
                        enabled = !state.busy,
                        onSelect = { onSelect(device) },
                        onRetry = { onRetry(device) },
                    )
                }
            }
            // A phone paired with a headset and no navigator needs the same help as an empty list,
            // and the filter alone would only say that it hid the headset.
            if (state.devices.none { state.status[it.address]?.garmin != false }) {
                Text(
                    stringResource(R.string.devices_empty),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            FilterNote(
                showingAll = state.showAllDevices,
                hidden = hidden,
                onShowAllDevices = onShowAllDevices,
            )
        }

        val negotiated = state.info.negotiated
        val caps = state.info.capabilities
        val identity = state.info.productData
        if (negotiated != null || caps != null || identity != null) {
            HorizontalDivider()
            Text(
                stringResource(R.string.session_title),
                style = MaterialTheme.typography.titleMedium,
            )

            // What the navigator called itself, which is also what chose the limits below.
            identity?.let {
                Field(stringResource(R.string.session_navigator), it.name)
                Field(stringResource(R.string.session_software), it.version)
            }
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
        // FlowRow, not Row: at a larger font scale the chips do not fit one line, and a Row
        // squeezes the last ones instead of wrapping them.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for ((name, profile) in Profiles.selectable) {
                FilterChip(
                    selected = profile.productId == state.device.productId,
                    onClick = { onSelectProfile(name) },
                    label = { Text(profile.name) },
                    // A send's route is already built for its model.
                    enabled = !state.busy,
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

        WhyTheseModels()
    }
}

/**
 * Why the model list is short, under the picker. It asks for no model: the only others read, the
 * TXi and GDU 620, have no route parser, so asking for them would promise support.
 */
@Composable
private fun WhyTheseModels() {
    HorizontalDivider()
    Text(stringResource(R.string.model_why_title), style = MaterialTheme.typography.titleSmall)
    Text(
        stringResource(R.string.model_why_body),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * What the filter did, with the exact count and the one tap that undoes it, which is what makes
 * hiding a row safe. See `bt/DeviceStatus.kt`. Nothing is drawn while the filter is on and hid
 * nothing.
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
 * One device. Its state is carried by an icon and words, and only secondarily by a muted colour:
 * never by the disabled alpha, since the row stays tappable, and not by colour alone, which fails
 * colour blindness, daylight, and the common case where every row is dim.
 */
@Composable
private fun DeviceRow(
    device: PairedDevice,
    status: DeviceStatus?,
    lastUsed: Long?,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onRetry: () -> Unit,
) {
    val dimmed = status?.dimmed == true
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                enabled = enabled,
                onClick = onSelect,
                role = Role.RadioButton,
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Null onClick: the whole row is the target, so the button must not be one of its own.
        RadioButton(selected = selected, onClick = null, enabled = enabled)
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
                    if (status.canRetry) {
                        TextButton(onClick = onRetry) {
                            Text(stringResource(R.string.action_retry))
                        }
                    }
                }
            }

            // What tells two identically named units apart.
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

/** The state at a glance: a spinner while paging, which takes seconds. */
@Composable
private fun StateIcon(indicator: Indicator?) {
    when (indicator) {
        Indicator.CHECKING -> CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
        )

        Indicator.LIVE -> Icon(
            painterResource(R.drawable.ic_bluetooth_connected),
            contentDescription = stringResource(R.string.device_icon_answered),
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary,
        )

        Indicator.SILENT -> Icon(
            painterResource(R.drawable.ic_bluetooth_disabled),
            contentDescription = stringResource(R.string.device_icon_no_answer),
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Indicator.NONE, null -> Unit
    }
}

/** The verdict a row reports, with how long ago it was taken. */
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
 * When the device was last used: today, yesterday, or a date in the pattern the locale gives for
 * day and abbreviated month, month first in en-US and day first in fr-FR, with the year only
 * outside the current one. `LocalLocale`, not `Locale.getDefault()`, which is not observable
 * state, so an open sheet follows a locale change.
 */
@Composable
private fun lastUsedText(atMillis: Long): String {
    val then = Instant.ofEpochMilli(atMillis).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    val locale = LocalLocale.current.platformLocale
    val pattern = DateFormat.getBestDateTimePattern(locale, if (then.year == today.year) "dMMM" else "dMMMyyyy")
    return when {
        then == today -> stringResource(R.string.device_last_used_today)
        then == today.minusDays(1) -> stringResource(R.string.device_last_used_yesterday)
        else -> stringResource(
            R.string.device_last_used_on,
            then.format(DateTimeFormatter.ofPattern(pattern, locale)),
        )
    }
}

/** A label above a value. */
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
 * The notations a navigator accepts for a waypoint: a byte apart from the element mask, and what
 * gates whether a coordinate can be sent at all.
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
 * The CXP exchange, copyable: when something fails, this is what a bug report needs, so it is in
 * English whatever the phone's language.
 */
@Composable
fun LogSheet(lines: List<String>, onClear: () -> Unit) {
    val clipboard = LocalClipboard.current
    // Setting a clip suspends. Tied to this composition, so dismissing the sheet abandons a copy.
    val scope = rememberCoroutineScope()
    // Android 13 and newer show this label in the copy confirmation.
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
                        // Joined outside the coroutine, so the copy is the log as it read when the
                        // button was pressed.
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
            // Wrapped, not one horizontally scrolled line each: that is a swipe per line, and a
            // LazyColumn resets the scroll state of the items it recycles.
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
 * The credential, and how to replace it. The app carries one, but it is a single fixed secret: if
 * Garmin changes it, every shipped version stops authenticating at once.
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
