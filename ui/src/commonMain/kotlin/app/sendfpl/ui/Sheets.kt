package app.sendfpl.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sendfpl.DeviceInfo
import app.sendfpl.cxp.Element
import app.sendfpl.cxp.Profile
import app.sendfpl.cxp.Profiles
import app.sendfpl.cxp.WaypointType
import app.sendfpl.ui.resources.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

// Linked on the About page. Replies go to the public tracker, not to a mail address, so the next
// owner of the same navigator finds the report.
internal const val CONTACT_NAME = "David du Colombier"
internal const val PROJECT_URL = "github.com/0intro/sendfpl"
internal const val ISSUES_URL = "$PROJECT_URL/issues"

/**
 * What a navigator sheet says under its list, on either platform: once a session has run, what
 * the navigator told us about itself, which is what to look at when a transfer behaves oddly, and
 * the model picker with the limits it applies. It lays its parts out in the sheet's own column.
 */
@Composable
fun NavigatorDetails(
    info: DeviceInfo,
    device: Profile,
    busy: Boolean,
    onSelectProfile: (String) -> Unit,
) {
    val negotiated = info.negotiated
    val caps = info.capabilities
    val identity = info.productData
    if (negotiated != null || caps != null || identity != null) {
        HorizontalDivider()
        Text(
            stringResource(Res.string.session_title),
            style = MaterialTheme.typography.titleMedium,
        )

        // What the navigator called itself, which is also what chose the limits below.
        identity?.let {
            Field(stringResource(Res.string.session_navigator), it.name)
            Field(stringResource(Res.string.session_software), it.version)
        }
        negotiated?.let {
            Field(
                stringResource(Res.string.session_transport),
                stringResource(
                    Res.string.session_transport_value, it.maxSz, it.mxOut, it.toRetry
                ),
            )
            Field(
                stringResource(Res.string.session_session),
                if (it.isV2) stringResource(Res.string.session_v2, it.syncId ?: 0L)
                else stringResource(Res.string.session_v1),
            )
        }
        caps?.let { c ->
            Field(
                stringResource(Res.string.session_max_route),
                pluralStringResource(
                    Res.plurals.session_max_route_value, c.maxTextLength, c.maxTextLength,
                ),
            )
            Field(stringResource(Res.string.session_elements), describeElements(c.elements))
            Field(
                stringResource(Res.string.session_waypoint_formats),
                describeWaypointTypes(c.waypointTypes),
            )
        }
    }

    HorizontalDivider()
    Text(stringResource(Res.string.model_title), style = MaterialTheme.typography.titleMedium)
    Text(
        stringResource(Res.string.model_help),
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
                selected = profile.productId == device.productId,
                onClick = { onSelectProfile(name) },
                label = { Text(profile.name) },
                // A send's route is already built for its model.
                enabled = !busy,
            )
        }
    }
    Field(
        stringResource(Res.string.model_limits),
        pluralStringResource(
            Res.plurals.model_limits_value,
            device.maxRouteLen,
            device.waypointNameLen,
            device.airportNameLen,
            device.maxRouteLen,
        ),
    )

    WhyTheseModels()
}

/**
 * Why the model list is short, under the picker. It asks for no model: the only others read, the
 * TXi and GDU 620, have no route parser, so asking for them would promise support.
 */
@Composable
private fun WhyTheseModels() {
    HorizontalDivider()
    Text(stringResource(Res.string.model_why_title), style = MaterialTheme.typography.titleSmall)
    Text(
        stringResource(Res.string.model_why_body),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
    Element.DEPARTURE to Res.string.element_departure,
    Element.ARRIVAL to Res.string.element_arrival,
    Element.APPROACH to Res.string.element_approach,
    Element.WPT to Res.string.element_waypoints,
    Element.AIRWAY_DOT_NOTATION to Res.string.element_airways,
    Element.HOLD_AT_WPT to Res.string.element_holds,
    Element.ALONG_WPT to Res.string.element_along_track,
)

/**
 * The notations a navigator accepts for a waypoint: a byte apart from the element mask, and what
 * gates whether a coordinate can be sent at all.
 */
@Composable
private fun describeWaypointTypes(mask: Int): String = describeMask(
    mask,
    WaypointType.PUB_FMT to Res.string.waypoint_format_published,
    WaypointType.LAT_LON_USER to Res.string.waypoint_format_latlon,
    WaypointType.PB_PB_FMT to Res.string.waypoint_format_pbpb,
    WaypointType.PDB_FMT to Res.string.waypoint_format_pbd,
)

/** The bits set in [mask], named and joined, or "none advertised" when it is empty. */
@Composable
private fun describeMask(mask: Int, vararg bits: Pair<Int, StringResource>): String {
    val names = bits.filter { (bit, _) -> mask and bit != 0 }
        .map { (_, name) -> stringResource(name) }
    return if (names.isEmpty()) stringResource(Res.string.session_none_advertised)
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
    val clipLabel = stringResource(Res.string.log_clip_label)
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(Res.string.log_title), style = MaterialTheme.typography.titleMedium)
            Row {
                TextButton(
                    onClick = {
                        // Joined outside the coroutine, so the copy is the log as it read when the
                        // button was pressed.
                        val text = lines.joinToString("\n")
                        scope.launch {
                            clipboard.setClipEntry(plainTextClip(clipLabel, text))
                        }
                    },
                ) {
                    Text(stringResource(Res.string.action_copy))
                }
                TextButton(onClick = onClear) { Text(stringResource(Res.string.action_clear)) }
            }
        }
        if (lines.isEmpty()) {
            Text(stringResource(Res.string.log_empty), style = MaterialTheme.typography.bodyMedium)
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
        // Scrollable: a bottom sheet clips what does not fit, and this one outgrows a phone at a
        // large font scale.
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(Res.string.credential_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(Res.string.credential_body),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            stringResource(Res.string.credential_replace_body),
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
                        if (credentialPresent) Res.string.credential_replace
                        else Res.string.credential_import
                    )
                )
            }
            if (credentialPresent) {
                TextButton(onClick = onForget) { Text(stringResource(Res.string.credential_forget)) }
            }
        }
        if (credentialPresent) {
            Text(
                stringResource(storedCredentialNote),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
