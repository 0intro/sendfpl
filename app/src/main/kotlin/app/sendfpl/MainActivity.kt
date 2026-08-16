package app.sendfpl

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import app.sendfpl.bt.GarminDevices
import app.sendfpl.ui.AboutSheet
import app.sendfpl.ui.DeviceSheet
import app.sendfpl.ui.HomeScreen
import app.sendfpl.ui.LogSheet
import app.sendfpl.ui.SendFplTheme
import app.sendfpl.ui.SetupSheet

class MainActivity : ComponentActivity() {

    /**
     * The intent the composition is reading, as state rather than as the activity's field.
     *
     * `setIntent` mutates a plain field, which no composition observes, so a second intent
     * delivered to a live activity used to reach [onNewIntent] and stop there. Nothing showed it,
     * because with the default launch mode most senders get a fresh instance and a fresh
     * `onCreate`. It would have surfaced the moment anything set FLAG_ACTIVITY_SINGLE_TOP, which
     * is exactly the kind of latent fault worth closing while the ingestion paths are being
     * widened rather than after.
     */
    private var current by mutableStateOf<Intent?>(null)

    /**
     * Whether the launch intent has already been read into the route box.
     *
     * **This is what stops a rotation throwing away the pilot's edits.** A recreated activity is
     * handed the same intent again, and the view model survives with it, so without this the
     * composition re-imported the shared route over whatever had since been typed. Reproduced by
     * sharing a route in, appending a waypoint and rotating; the appended waypoint vanished. A
     * dark mode switch, a font size change and entering multi-window do the same thing, none of
     * them announced.
     *
     * Saved rather than kept in the field alone, because the field does not survive the
     * recreation either. [onNewIntent] clears it, since a genuinely new intent has to be read.
     *
     * The trade, stated because it is a real one: after the process is killed and restored this
     * reads true, so the route box comes back empty rather than re-importing. That is the right
     * half to lose. The view model holding any edit is gone by then too, so re-importing would
     * not be restoring the pilot's work, it would be replacing it with something older.
     */
    private var consumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consumed = savedInstanceState?.getBoolean(CONSUMED) == true
        current = if (consumed) null else intent
        setContent {
            SendFplTheme {
                App(current) {
                    consumed = true
                    current = null
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(CONSUMED, consumed)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumed = false
        current = intent
    }

    private companion object {
        const val CONSUMED = "intent-consumed"
    }
}

private enum class Sheet { NONE, DEVICES, LOG, SETUP, ABOUT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(initialIntent: Intent?, onIntentConsumed: () -> Unit) {
    val vm: MainViewModel = viewModel()
    val state by vm.state.collectAsState()
    var sheet by remember { mutableStateOf(Sheet.NONE) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { vm.refresh() }

    val context = LocalContext.current

    // Turning the radio on is a system dialog, and its result is the moment to look again: nothing
    // else tells us the answer, since the app reads the adapter's state rather than watching it.
    val bluetoothLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { vm.refresh() }

    val routeFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.importRouteFile(it, displayName(context, it)) } }

    val credentialLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            vm.importCredential(it)
            sheet = Sheet.NONE
        }
    }

    // Ask for Bluetooth permission once, on first composition.
    LaunchedEffect(Unit) {
        if (!state.permissionsGranted) {
            permissionLauncher.launch(
                GarminDevices(
                    // Application context is fine, since we only read the permission list.
                    vm.getApplication()
                ).requiredPermissions.toTypedArray()
            )
        }
    }

    // A shared route or an opened route file arrives as an intent.
    LaunchedEffect(initialIntent) {
        val i = initialIntent ?: return@LaunchedEffect
        when (i.action) {
            // A share carries either a file or some text, never both, and the file is the one
            // that matters for a planner: SkyDemon's Android app has no share chooser for a
            // route, so its plan reaches this app as a mail attachment somebody shared on.
            Intent.ACTION_SEND -> {
                val stream = sharedStream(i)
                if (stream != null) vm.importRouteFile(stream, displayName(context, stream))
                else i.getStringExtra(Intent.EXTRA_TEXT)?.let(vm::acceptSharedText)
            }
            // The same name lookup the picker uses. A provider's last path segment is often an
            // opaque id with no extension on it, and asking for the display name is what gets
            // the file's real name back, so an opened file picks its parser the same way a
            // chosen one does. Sniffing still catches the rest.
            Intent.ACTION_VIEW -> i.data?.let { vm.importRouteFile(it, displayName(context, it)) }
        }
        onIntentConsumed()
    }

    HomeScreen(
        state = state,
        onRouteChange = vm::setRoute,
        onPickRouteFile = {
            // Providers disagree about the MIME type of these, and .pln has none, so accept
            // anything and sniff.
            routeFileLauncher.launch(arrayOf("*/*"))
        },
        onSend = vm::send,
        onCancel = vm::cancelSend,
        onOpenDevices = {
            vm.refresh()
            sheet = Sheet.DEVICES
        },
        onOpenLog = { sheet = Sheet.LOG },
        onOpenSetup = { sheet = Sheet.SETUP },
        onOpenAbout = { sheet = Sheet.ABOUT },
        onGrantPermission = {
            permissionLauncher.launch(
                GarminDevices(vm.getApplication()).requiredPermissions.toTypedArray()
            )
        },
        onOpenAppSettings = {
            // Where a pilot has to go once Android has stopped showing the permission dialog,
            // which it does after the second refusal and never says.
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                )
            )
        },
        onEnableBluetooth = {
            bluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        },
    )

    if (sheet != Sheet.NONE) {
        ModalBottomSheet(onDismissRequest = { sheet = Sheet.NONE }) {
            when (sheet) {
                Sheet.DEVICES -> DeviceSheet(
                    state = state,
                    onSelect = {
                        vm.select(it)
                        sheet = Sheet.NONE
                    },
                    // An explicit ask, so it probes again rather than reusing a recent answer.
                    onRefresh = { vm.refresh(force = true) },
                    onRetry = vm::retry,
                    onSelectProfile = vm::selectProfile,
                    onShowAllDevices = vm::setShowAllDevices,
                )

                Sheet.LOG -> LogSheet(lines = state.log, onClear = vm::clearLog)

                Sheet.SETUP -> SetupSheet(
                    credentialPresent = state.credentialPresent,
                    onImport = { credentialLauncher.launch(arrayOf("application/json", "*/*")) },
                    onForget = vm::forgetCredential,
                )

                Sheet.ABOUT -> AboutSheet(onOpenSetup = { sheet = Sheet.SETUP })

                Sheet.NONE -> Unit
            }
        }
    }
}

/**
 * The file a share carried, if it carried one.
 *
 * The typed overload is API 33 and later, and the untyped one it replaced is deprecated there, so
 * both are needed to cover a minSdk of 26 without a lint suppression that would also hide a real
 * mistake.
 */
private fun sharedStream(intent: Intent): Uri? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
    }

/** The display name where one can be had, used only to pick a parser by extension. */
private fun displayName(context: android.content.Context, uri: Uri): String? =
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull() ?: uri.lastPathSegment
