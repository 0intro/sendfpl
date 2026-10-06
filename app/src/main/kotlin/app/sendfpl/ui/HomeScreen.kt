package app.sendfpl.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
import app.sendfpl.Wording
import app.sendfpl.bt.Indicator
import app.sendfpl.cxp.Profile
import app.sendfpl.cxp.Profiles

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
                // Edge to edge, the window does not shrink for the keyboard, so the column does.
                .consumeWindowInsets(padding)
                .imePadding()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.credentialPresent == false) {
                SetupNeeded(onOpenSetup)
            }

            Column {
                DeviceChip(state, onOpenDevices)
                if (state.anotherAnswers) AnotherAnswers(onOpenDevices)
            }

            OutlinedTextField(
                value = state.routeText,
                onValueChange = onRouteChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.route_label)) },
                placeholder = { Text(stringResource(R.string.route_placeholder)) },
                supportingText = {
                    val err = state.routeError
                    if (err != null) {
                        Text(err.text(), color = MaterialTheme.colorScheme.error)
                    } else {
                        Text(stringResource(R.string.route_help))
                    }
                },
                isError = state.routeError != null,
                trailingIcon = {
                    IconButton(onClick = onPickRouteFile, enabled = !state.busy) {
                        Icon(
                            painterResource(R.drawable.ic_file_open),
                            contentDescription = stringResource(R.string.route_open_file),
                        )
                    }
                },
                // Identifiers: upper case, and no autocorrect, which would rewrite four letter
                // identifiers into dictionary words.
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii,
                ),
                singleLine = false,
                enabled = !state.busy,
            )

            if (state.parsed?.carriesProcedures == true) ProcedureWarning()
            if (state.device.bareUserWaypoints && state.parsed?.carriesUserWaypoints == true) {
                UserWaypointsNote()
            }

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
                        Icon(painterResource(R.drawable.ic_send), contentDescription = null)
                    }
                    Text(
                        text = if (state.busy) stepLabel(state.step)
                        else stringResource(R.string.send_button),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                // Once the plan is delivered there is nothing left to call off.
                if (state.busy && state.step != Step.VERIFYING) {
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
                }
            }

            state.outcome?.let {
                OutcomeCard(it, state.device, state.usedPickerModel, onOpenLog)
            }

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
 * The short form of the safety statement, on the main screen so it needs no second action. The
 * long form and the trademark statement are on [AboutScreen].
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
 * What the route box cannot say, said next to it: an edit rebuilds the route from the text and
 * drops any procedure, runway or approach. See `ParsedRoute.carriesProcedures`. A warning rather
 * than a lock, because rebuilding from the text is what an edit means.
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
 * Why the preview sends a user waypoint as a bare position while the box keeps its name, which
 * would otherwise look like a mistake. See `Profile.bareUserWaypoints`.
 */
@Composable
private fun UserWaypointsNote() {
    Text(
        stringResource(R.string.route_user_waypoints_positions),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The two things that stop a transfer before it starts, each with its fix. The settings page sits
 * beside the permission request because after a second refusal Android silently stops showing the
 * dialog, and a request then returns denied with nothing on screen.
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
        Step.VERIFYING -> R.string.step_verifying
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
 * The selected navigator, and whether it answered. A tick means it answered, never merely that it
 * is selected.
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
                painterResource(
                    when {
                        selected == null -> R.drawable.ic_error_outline
                        status?.indicator == Indicator.LIVE -> R.drawable.ic_check_circle
                        status?.indicator == Indicator.SILENT -> R.drawable.ic_bluetooth_disabled
                        status?.indicator == Indicator.CHECKING -> R.drawable.ic_bluetooth_connected
                        // Selected, nothing known against it, so neither a tick nor a warning.
                        else -> R.drawable.ic_bluetooth
                    }
                ),
                // The tick is the only sign the navigator answered; the label says the rest.
                contentDescription = if (selected != null && status?.indicator == Indicator.LIVE) {
                    stringResource(R.string.device_icon_answered)
                } else null,
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

/**
 * Said under the chip while another navigator answers and the selected one does not. A line to
 * the sheet, never a switch: two units of one model look alike, and Send aims where the pilot
 * chose.
 */
@Composable
private fun AnotherAnswers(onOpenDevices: () -> Unit) {
    TextButton(onClick = onOpenDevices) {
        Text(stringResource(R.string.device_another_answers))
    }
}

/**
 * [device] is the model a send used, which decides what its panel shows next, and [pickerModel]
 * says it was the picker's, the navigator having named none with limits here.
 */
@Composable
private fun OutcomeCard(
    outcome: Outcome,
    device: Profile,
    pickerModel: Boolean,
    onOpenLog: () -> Unit,
) {
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
                    painterResource(if (success) R.drawable.ic_check_circle else R.drawable.ic_error_outline),
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
                is Outcome.Success -> {
                    Text(
                        stringResource(importedPlanHint(device)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (pickerModel) {
                        Text(
                            stringResource(R.string.outcome_sent_picker_model, device.name),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

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
                    Text(outcome.message.text(), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onOpenLog) {
                        Text(stringResource(R.string.outcome_see_log))
                    }
                }
            }
        }
    }
}

/**
 * What the panel does with an imported plan, as each model's pilot's guide says. A GPS 175 raises
 * an advisory with a Preview key, id 0x36 in its message table, "%u new imported flight plan%s
 * available for preview."; a GTN flashes its MSG key, and its message offers New FPL. Either way
 * the plan waits in the catalog, pending, until the pilot previews it. Nothing asks for a
 * confirmation, so the pilot is never sent looking for one.
 */
private fun importedPlanHint(device: Profile): Int = when (device.productId) {
    Profiles.GTN.productId, Profiles.GTN_XI.productId -> R.string.outcome_sent_detail_gtn
    else -> R.string.outcome_sent_detail
}

/**
 * [Wording] in the language the screen is in now. Reads the configuration, as `stringResource`
 * does, so that a change of language words it again.
 */
@Composable
@ReadOnlyComposable
private fun Wording.text(): String {
    LocalConfiguration.current
    return words(LocalContext.current)
}
