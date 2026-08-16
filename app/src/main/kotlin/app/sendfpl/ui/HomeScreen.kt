package app.sendfpl.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sendfpl.Outcome
import app.sendfpl.R
import app.sendfpl.Step
import app.sendfpl.UiState
import app.sendfpl.bt.Indicator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: UiState,
    onRouteChange: (String) -> Unit,
    onPickRouteFile: () -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    onOpenDevices: () -> Unit,
    onOpenLog: () -> Unit,
    onOpenSetup: () -> Unit,
    onOpenAbout: () -> Unit,
    onGrantPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onEnableBluetooth: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    if (state.log.isNotEmpty()) {
                        TextButton(onClick = onOpenLog) {
                            Text(stringResource(R.string.action_log))
                        }
                    }
                    TextButton(onClick = onOpenAbout) {
                        Text(stringResource(R.string.action_about))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!state.credentialPresent) {
                SetupNeeded(onOpenSetup)
            }

            DeviceChip(state, onOpenDevices)

            OutlinedTextField(
                value = state.routeText,
                onValueChange = onRouteChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.route_label)) },
                placeholder = { Text(stringResource(R.string.route_placeholder)) },
                supportingText = {
                    val err = state.routeError
                    if (err != null) {
                        Text(err, color = MaterialTheme.colorScheme.error)
                    } else {
                        Text(stringResource(R.string.route_help))
                    }
                },
                isError = state.routeError != null,
                trailingIcon = {
                    IconButton(onClick = onPickRouteFile) {
                        Icon(
                            Icons.Default.FileOpen,
                            contentDescription = stringResource(R.string.route_open_file),
                        )
                    }
                },
                // A route is identifiers, so the keyboard is set to give identifiers: upper case,
                // and autocorrect off. Left to its defaults the phone lower cases what is typed
                // and rewrites four letter identifiers into dictionary words on the way through.
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii,
                ),
                singleLine = false,
                enabled = !state.busy,
            )

            if (state.parsed?.carriesProcedures == true) ProcedureWarning()

            state.preview?.let { RoutePreview(it) }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onSend,
                    enabled = state.canSend,
                    modifier = Modifier.weight(1f),
                ) {
                    if (state.busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                    }
                    Text(
                        text = if (state.busy) stepLabel(state.step)
                        else stringResource(R.string.send_button),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                // Offered only while busy. See MainViewModel.cancelSend for why a transfer needs
                // calling off rather than waiting out.
                if (state.busy) {
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
                }
            }

            state.outcome?.let { OutcomeCard(it, onOpenLog) }

            if (!state.permissionsGranted || !state.bluetoothEnabled) {
                Blocked(
                    permissionsGranted = state.permissionsGranted,
                    onGrantPermission = onGrantPermission,
                    onOpenAppSettings = onOpenAppSettings,
                    onEnableBluetooth = onEnableBluetooth,
                )
            }

            Disclaimer()
        }
    }
}

/**
 * The short form of the safety statement, on the screen rather than only in the About sheet.
 *
 * A disclaimer that lives only where someone has to go looking for it is a disclaimer by promise.
 * This is the same argument `DeviceRow` already makes about dimming: the thing that matters has to
 * be legible without a second action. The long form, and the trademark statement, are in
 * [AboutSheet], but the instruction that actually protects a flight is here, tied to something the
 * app already tells the pilot to do after a successful send.
 */
@Composable
private fun Disclaimer() {
    Text(
        stringResource(R.string.disclaimer_short),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/**
 * What the route box cannot say, said next to it.
 *
 * The box is filled from `ParsedRoute.identifiers`, which is a list of fixes and nothing else, so
 * a file carrying a departure or arrival procedure, a runway or an approach shows as a bare list
 * of points. Those elements survive right up until the text is edited, at which point the route is
 * rebuilt from the box by a parser that cannot express them and they are gone.
 *
 * Warning rather than preventing, because rebuilding from the text is what an edit *means*. The
 * ARINC preview below already shows the truth; this is what makes a pilot look at it. The line
 * disappears the moment the elements do, which is the right feedback: it is a description of the
 * route in hand, not a sticky notice.
 */
@Composable
private fun ProcedureWarning() {
    Text(
        stringResource(R.string.route_procedures_warning),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The two things that stop a transfer before it starts, each with the action that fixes it.
 *
 * The settings page is offered beside the permission request rather than instead of it, because
 * Android stops showing the permission dialog after the second refusal and never says so: a
 * further request then returns denied with nothing appearing on screen, and a sentence alone
 * would be a dead end.
 */
@Composable
private fun Blocked(
    permissionsGranted: Boolean,
    onGrantPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onEnableBluetooth: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(
                if (!permissionsGranted) R.string.blocked_permission
                else R.string.blocked_bluetooth_off
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!permissionsGranted) {
                TextButton(onClick = onGrantPermission) {
                    Text(stringResource(R.string.action_grant_permission))
                }
                TextButton(onClick = onOpenAppSettings) {
                    Text(stringResource(R.string.action_open_settings))
                }
            } else {
                // Only offered once the permission is held: on Android 12 and newer this request
                // needs BLUETOOTH_CONNECT itself, and without it the dialog never appears.
                TextButton(onClick = onEnableBluetooth) {
                    Text(stringResource(R.string.action_enable_bluetooth))
                }
            }
        }
    }
}

@Composable
private fun stepLabel(step: Step?): String = stringResource(
    when (step) {
        Step.CONNECTING -> R.string.step_connecting
        Step.HANDSHAKING -> R.string.step_handshaking
        Step.AUTHENTICATING -> R.string.step_authenticating
        Step.NEGOTIATING -> R.string.step_negotiating
        Step.UPLOADING -> R.string.step_uploading
        Step.DONE, null -> R.string.step_working
    }
)

@Composable
private fun SetupNeeded(onOpenSetup: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.setup_needed_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.setup_needed_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onOpenSetup) { Text(stringResource(R.string.action_set_up)) }
        }
    }
}

/**
 * The selected navigator, and whether it is actually answering.
 *
 * **A tick means the device answered, never that one is merely selected.** The status has to be on
 * the surface someone actually looks at, or the main screen says everything is fine while the
 * navigator sits switched off in the hangar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceChip(state: UiState, onOpenDevices: () -> Unit) {
    val selected = state.selected
    val status = selected?.let { state.status[it.address] }

    AssistChip(
        onClick = onOpenDevices,
        label = {
            Text(
                text = when {
                    selected == null -> stringResource(R.string.device_none_selected)
                    // Only worth the width when it changes what you would do next.
                    status?.indicator == Indicator.SILENT ->
                        stringResource(R.string.device_chip_no_answer, selected.name)
                    status?.indicator == Indicator.CHECKING ->
                        stringResource(R.string.device_chip_checking, selected.name)
                    else -> selected.name
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingIcon = {
            Icon(
                imageVector = when {
                    selected == null -> Icons.Default.ErrorOutline
                    status?.indicator == Indicator.LIVE -> Icons.Default.CheckCircle
                    status?.indicator == Indicator.SILENT -> Icons.Default.BluetoothDisabled
                    status?.indicator == Indicator.CHECKING -> Icons.Default.BluetoothConnected
                    // Selected, nothing known against it, so neither a tick nor a warning.
                    else -> Icons.Default.Bluetooth
                },
                contentDescription = null,
                modifier = Modifier.size(AssistChipDefaults.IconSize),
            )
        },
    )
}

@Composable
private fun RoutePreview(preview: String) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.route_preview_title),
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                text = preview,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            Text(
                pluralStringResource(R.plurals.route_preview_size, preview.length, preview.length),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OutcomeCard(outcome: Outcome, onOpenLog: () -> Unit) {
    val success = outcome is Outcome.Success
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (success) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (success) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                    contentDescription = null,
                )
                Text(
                    text = stringResource(
                        if (success) R.string.outcome_sent else R.string.outcome_failed
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            when (outcome) {
                // The navigator raises no modal dialog for an imported plan, so telling the pilot
                // to "confirm it" sent them looking for something that never appears. Its own
                // message table has one string for this, id 0x36, reading "%u new imported flight
                // plan%s available for preview." The plan lands in the catalog as pending until it
                // is previewed there.
                is Outcome.Success -> Text(
                    stringResource(R.string.outcome_sent_detail),
                    style = MaterialTheme.typography.bodyMedium,
                )

                is Outcome.Failure -> {
                    outcome.step?.let {
                        Text(
                            stringResource(
                                R.string.outcome_stopped_at,
                                stepLabel(it).removeSuffix("…"),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Text(outcome.message, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onOpenLog) {
                        Text(stringResource(R.string.outcome_see_log))
                    }
                }
            }
        }
    }
}
