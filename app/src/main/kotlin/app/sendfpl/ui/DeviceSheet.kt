package app.sendfpl.ui

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.sendfpl.RouteState
import app.sendfpl.UiState
import app.sendfpl.bt.Age
import app.sendfpl.bt.Capability
import app.sendfpl.bt.Detail
import app.sendfpl.bt.DeviceStatus
import app.sendfpl.bt.Indicator
import app.sendfpl.bt.Label
import app.sendfpl.bt.PairedDevice
import app.sendfpl.ui.resources.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Paired devices and, once a session has run, what the navigator told us about itself, which is
 * what to look at when a transfer behaves oddly. Rows the app has positive reason to doubt are
 * dimmed and say why, but every row stays selectable. See `bt/DeviceStatus.kt`.
 */
@Composable
fun DeviceSheet(
    state: UiState,
    route: RouteState,
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
                stringResource(Res.string.devices_title),
                style = MaterialTheme.typography.titleMedium,
            )
            TextButton(onClick = onRefresh) { Text(stringResource(Res.string.action_refresh)) }
        }

        // Paging takes seconds per device, too long a wait for the row's own "checking…" alone.
        if (state.status.values.any { it.indicator == Indicator.CHECKING }) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        if (state.devices.isEmpty()) {
            Text(
                stringResource(
                    when {
                        !state.permissionsGranted -> Res.string.blocked_permission
                        !state.bluetoothEnabled -> Res.string.blocked_bluetooth_off
                        else -> Res.string.devices_empty
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
                        enabled = !route.busy,
                        onSelect = { onSelect(device) },
                        onRetry = { onRetry(device) },
                    )
                }
            }
            // A phone paired with a headset and no navigator needs the same help as an empty list,
            // and the filter alone would only say that it hid the headset.
            if (state.devices.none { state.status[it.address]?.garmin != false }) {
                Text(
                    stringResource(Res.string.devices_empty),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            FilterNote(
                showingAll = state.showAllDevices,
                hidden = hidden,
                onShowAllDevices = onShowAllDevices,
            )
        }

        NavigatorDetails(route.info, route.device, route.busy, onSelectProfile)
    }
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
            text = if (showingAll) stringResource(Res.string.devices_showing_all)
            else pluralStringResource(Res.plurals.devices_hidden, hidden, hidden),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { onShowAllDevices(!showingAll) }) {
            Text(
                stringResource(
                    if (showingAll) Res.string.devices_show_garmin_only
                    else Res.string.devices_show_all
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
                            stringResource(Res.string.device_connext_badge),
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
                    ?.let { stringResource(Res.string.device_address_with_state, device.address, it) }
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
                            Text(stringResource(Res.string.action_retry))
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
            painterResource(Res.drawable.ic_bluetooth_connected),
            contentDescription = stringResource(Res.string.device_icon_answered),
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary,
        )

        Indicator.SILENT -> Icon(
            painterResource(Res.drawable.ic_bluetooth_disabled),
            contentDescription = stringResource(Res.string.device_icon_no_answer),
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Indicator.NONE, null -> Unit
    }
}

/** The verdict a row reports, with how long ago it was taken. */
@Composable
private fun labelText(label: Label, age: Age?): String = when (label) {
    Label.CHECKING -> stringResource(Res.string.device_label_checking)
    Label.CONNECTED -> stringResource(Res.string.device_label_connected)
    Label.NO_ANSWER -> stringResource(Res.string.device_label_no_answer, ageText(age))
    Label.ANSWERED -> stringResource(Res.string.device_label_answered, ageText(age))
}

@Composable
private fun ageText(age: Age?): String = when (age?.unit) {
    null, Age.Unit.JUST_NOW -> stringResource(Res.string.age_just_now)
    Age.Unit.SECONDS -> pluralStringResource(Res.plurals.age_seconds, age.count.toInt(), age.count)
    Age.Unit.MINUTES -> pluralStringResource(Res.plurals.age_minutes, age.count.toInt(), age.count)
    Age.Unit.HOURS -> pluralStringResource(Res.plurals.age_hours, age.count.toInt(), age.count)
}

@Composable
private fun detailText(detail: Detail): String = stringResource(
    when (detail) {
        Detail.NO_CONNEXT -> Res.string.device_detail_no_connext
        Detail.LINK_HELD -> Res.string.device_detail_link_held
        Detail.UNREACHABLE -> Res.string.device_detail_unreachable
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
        then == today -> stringResource(Res.string.device_last_used_today)
        then == today.minusDays(1) -> stringResource(Res.string.device_last_used_yesterday)
        else -> stringResource(
            Res.string.device_last_used_on,
            then.format(DateTimeFormatter.ofPattern(pattern, locale)),
        )
    }
}
