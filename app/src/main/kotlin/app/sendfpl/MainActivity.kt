package app.sendfpl

import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import app.sendfpl.bt.GarminDevices
import app.sendfpl.ui.AboutScreen
import app.sendfpl.ui.DeviceSheet
import app.sendfpl.ui.HomeScreen
import app.sendfpl.ui.LogSheet
import app.sendfpl.ui.SendFplTheme
import app.sendfpl.ui.SetupSheet
import kotlinx.coroutines.flow.first

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    /** The intent the composition is reading, as state, so a second one delivered here is seen. */
    private var current by mutableStateOf<Intent?>(null)

    /**
     * Whether the intent has been read into the route box. Saved, because a recreated activity is
     * handed the same intent again, and reading it twice would discard what was typed since.
     */
    private var consumed = false

    /** The radio can be switched from quick settings without the app pausing. */
    private val bluetoothState = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON, BluetoothAdapter.STATE_OFF -> vm.refresh()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        consumed = intentRead(savedInstanceState?.getBoolean(CONSUMED) == true, intent.flags)
        current = if (consumed) null else intent
        setContent {
            SendFplTheme {
                App(vm, current) {
                    consumed = true
                    current = null
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // A protected broadcast from system_server, which an unexported receiver would hear too:
        // exporting admits no other sender, and matches DeviceProbe, whose broadcasts come from
        // the Bluetooth process and need it.
        ContextCompat.registerReceiver(
            this,
            bluetoothState,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    override fun onStop() {
        unregisterReceiver(bluetoothState)
        vm.stopSweep()
        super.onStop()
    }

    /** Back from Settings, a permission dialog or the Bluetooth prompt, look again. */
    override fun onResume() {
        super.onResume()
        vm.refresh()
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

private enum class Sheet { NONE, DEVICES, LOG, SETUP }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(vm: MainViewModel, intent: Intent?, onIntentConsumed: () -> Unit) {
    val state by vm.state.collectAsState()
    var sheet by rememberSaveable { mutableStateOf(Sheet.NONE) }
    // The About page takes the home screen's place, and the home screen keeps its saved state,
    // where its scroll is, for when the page closes.
    var aboutOpen by rememberSaveable { mutableStateOf(false) }
    val screens = rememberSaveableStateHolder()
    val context = LocalContext.current

    // Each of these returns through onResume, which refreshes.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {}
    val bluetoothLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {}
    val routeFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(vm::importRouteFile) }
    val credentialLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            vm.importCredential(it)
            sheet = Sheet.NONE
        }
    }

    LaunchedEffect(state.permissionsGranted) {
        if (!state.permissionsGranted && !vm.permissionAsked) {
            vm.permissionAsked = true
            permissionLauncher.launch(GarminDevices.requiredPermissions.toTypedArray())
        }
    }

    // An intent arriving during a transfer waits for it rather than change the route under it.
    LaunchedEffect(intent) {
        val i = intent ?: return@LaunchedEffect
        vm.state.first { !it.busy }
        when (i.action) {
            // A file wins over text: SkyDemon's app has no share for a route, so its plan arrives
            // as a mail attachment shared on.
            Intent.ACTION_SEND -> {
                val stream = IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java)
                if (stream != null) readable(stream)?.let(vm::importRouteFile)
                else i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.let(vm::acceptSharedText)
            }
            Intent.ACTION_VIEW -> i.data?.let(::readable)?.let(vm::importRouteFile)
        }
        // A route arriving is shown, not left behind the About page.
        if (i.action == Intent.ACTION_SEND || i.action == Intent.ACTION_VIEW) aboutOpen = false
        onIntentConsumed()
    }

    if (aboutOpen) {
        AboutScreen(
            onBack = { aboutOpen = false },
            onOpenSetup = { sheet = Sheet.SETUP },
            onOpenLink = { context.start(Intent(Intent.ACTION_VIEW, it.toUri())) },
        )
    } else {
        screens.SaveableStateProvider("home") {
            HomeScreen(
                state = state,
                onRouteChange = vm::setRoute,
                // Providers disagree about the MIME type of these, and .pln has none, so accept
                // anything and sniff.
                onPickRouteFile = { routeFileLauncher.launch(arrayOf("*/*")) },
                onSend = vm::send,
                onCancel = vm::cancelSend,
                onOpenDevices = {
                    vm.refresh()
                    sheet = Sheet.DEVICES
                },
                onOpenLog = { sheet = Sheet.LOG },
                onOpenSetup = { sheet = Sheet.SETUP },
                onOpenAbout = { aboutOpen = true },
                onGrantPermission = {
                    permissionLauncher.launch(GarminDevices.requiredPermissions.toTypedArray())
                },
                onOpenAppSettings = {
                    // Where a pilot has to go once Android has stopped showing the permission
                    // dialog, which it does after the second refusal and never says.
                    context.start(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        )
                    )
                },
                onEnableBluetooth = {
                    try {
                        bluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                    } catch (e: ActivityNotFoundException) {
                        context.start(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                    }
                },
            )
        }
    }

    if (sheet != Sheet.NONE) {
        ModalBottomSheet(onDismissRequest = { sheet = Sheet.NONE }) {
            when (sheet) {
                Sheet.DEVICES -> DeviceSheet(
                    state = state,
                    onSelect = {
                        vm.select(it)
                        sheet = Sheet.NONE
                    },
                    onRefresh = { vm.refresh(force = true) },
                    onRetry = vm::retry,
                    onSelectProfile = vm::selectProfile,
                    onShowAllDevices = vm::setShowAllDevices,
                )

                Sheet.LOG -> LogSheet(lines = state.log, onClear = vm::clearLog)

                Sheet.SETUP -> SetupSheet(
                    credentialPresent = state.credentialPresent == true,
                    onImport = { credentialLauncher.launch(arrayOf("application/json", "*/*")) },
                    onForget = vm::forgetCredential,
                )

                Sheet.NONE -> Unit
            }
        }
    }
}

/**
 * Whether the intent that started the activity was read into the route box already: saved so by
 * the activity that read it, or relaunched from Recents, which hands a task that Back finished
 * its first intent again, with no saved state.
 */
internal fun intentRead(savedConsumed: Boolean, flags: Int): Boolean =
    savedConsumed || flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0

/**
 * [uri] if it is a `content:` URI. A `file:` one names a path this app cannot read, or one in its
 * private storage that no other app should be able to point it at.
 */
private fun readable(uri: Uri): Uri? = uri.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT }

/** Open a screen of the system's, or a browser, either of which a stripped down build may lack. */
private fun Context.start(intent: Intent) {
    try {
        startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        // Nothing to open, and nothing better to offer.
    }
}
