package app.sendfpl.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import app.sendfpl.CREDENTIAL_FILE_LIMIT
import app.sendfpl.Controller
import app.sendfpl.ROUTE_FILE_LIMIT
import app.sendfpl.RouteState
import app.sendfpl.Store
import app.sendfpl.bt.Indicator
import app.sendfpl.ios.AccessoryLine
import app.sendfpl.ios.DemoLine
import app.sendfpl.ios.DemoStore
import app.sendfpl.ios.FilePicker
import app.sendfpl.ios.IosStore
import app.sendfpl.ios.Navigator
import app.sendfpl.ios.NavigatorMemory
import app.sendfpl.ios.NavigatorState
import app.sendfpl.ios.Navigators
import app.sendfpl.ios.takeAtMost
import app.sendfpl.ui.resources.*
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform
import kotlinx.cinterop.BetaInteropApi
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import platform.Foundation.NSBundle
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitYear
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.NSLog
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSURLComponents
import platform.Foundation.NSURLQueryItem
import platform.Foundation.create
import platform.Foundation.currentLocale
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.writeToURL
import platform.UIKit.UIApplication
import platform.UIKit.UIBackgroundTaskInvalid
import platform.UIKit.UIViewController

/** The iPhone app, one per launch. */
private object App {
    /** Every coroutine of the app's. One that fails uncaught is traced, and the app goes on. */
    val scope = MainScope() + CoroutineExceptionHandler { _, e ->
        NSLog("uncaught: ${e::class.simpleName}: ${e.message}".replace("%", "%%"))
    }

    /** A launch with `-demo` sends to the navigator in memory, for a demonstration and UI tests. */
    val demo = "-demo" in NSProcessInfo.processInfo.arguments

    private val keychain = IosStore()

    /** A demonstration keeps a credential of its own, in memory, which its navigator holds too. */
    val store: Store = if (demo) DemoStore() else keychain

    // The line is the format, with its percent signs doubled: Kotlin/Native hands a String in
    // NSLog's variadic arguments over as a C string, which %@ would read as an object.
    val controller = Controller(scope, Dispatchers.IO, store, trace = { NSLog(it.replace("%", "%%")) })
    val navigators = Navigators(NavigatorMemory(), demo)
    val picker = FilePicker()

    /**
     * In a debug build, a launch with `-pick NAME` answers each pick with a file of that name, of
     * the bytes the environment's SENDFPL_PICK holds in Base64, written where the system's picker
     * leaves its copies. The UI test picks that way: the file provider of CI's simulators has lost
     * files it listed.
     */
    @OptIn(ExperimentalNativeApi::class)
    val testPick: Pair<String, String>? = run {
        if (!Platform.isDebugBinary) return@run null
        val args = NSProcessInfo.processInfo.arguments
        val i = args.indexOf("-pick")
        val name = if (i >= 0) args.getOrNull(i + 1) as? String else null
        val data = NSProcessInfo.processInfo.environment["SENDFPL_PICK"] as? String
        if (name != null && data != null) name to data else null
    }

    /** The screens' controller, which the pickers are presented over. */
    var root: UIViewController? = null

    val version: String =
        NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: ""

    init {
        scope.launch {
            val present = withContext(Dispatchers.IO) {
                if (!demo) keychain.adopt()
                store.loadCredential() != null
            }
            controller.credentialPresent(present)
        }
        navigators.start()
    }
}

/** The view controller the Swift app shows, all of it Compose. */
fun MainViewController(): UIViewController {
    val root = ComposeUIViewController { Screens() }
    App.root = root
    return root
}

/**
 * A URL iOS hands the app: a route file opened in it from Files, Mail or AirDrop, or a `sendfpl:`
 * link whose `route` query item is a route as the box takes it. One arriving during a transfer
 * waits for it rather than change the route under it.
 */
fun openUrl(url: NSURL) {
    App.scope.launch {
        App.controller.state.first { !it.busy }
        if (url.isFileURL()) {
            App.controller.importFile { url.lastPathComponent to takeAtMost(url, ROUTE_FILE_LIMIT) }
            return@launch
        }
        if (url.scheme != "sendfpl") return@launch
        // A URL the parser refuses is a nil Kotlin/Native throws for.
        val items = runCatching { NSURLComponents(url, resolvingAgainstBaseURL = false).queryItems }
            .getOrNull()
        val route = items?.filterIsInstance<NSURLQueryItem>()?.firstOrNull { it.name == "route" }?.value
        route?.let(App.controller::acceptSharedText)
    }
}

private enum class Sheet { NONE, DEVICES, LOG, SETUP }

/** What the shared home screen shows: [route], and the navigator Send aims at. */
private fun home(n: NavigatorState, route: RouteState) = HomeState(
    credentialPresent = route.credentialPresent,
    navigator = n.selected?.name,
    indicator = n.selected?.let { if (it.live) Indicator.LIVE else Indicator.SILENT },
    device = route.device,
    routeText = route.routeText,
    parsed = route.parsed,
    routeError = route.routeError,
    preview = route.preview,
    busy = route.busy,
    step = route.step,
    outcome = route.outcome,
    canSend = route.canSend && n.selected?.live == true,
    usedPickerModel = route.usedPickerModel,
    hasLog = route.log.isNotEmpty(),
)

private fun send() {
    val n = App.navigators.state.value.selected ?: return
    val accessory = n.accessory
    val line = when {
        accessory != null -> AccessoryLine(accessory)
        n.demo -> DemoLine(App.store)
        else -> return
    }
    // Time to finish a send the pilot leaves by locking the device, which iOS otherwise suspends.
    val app = UIApplication.sharedApplication
    var task = UIBackgroundTaskInvalid
    task = app.beginBackgroundTaskWithExpirationHandler {
        App.controller.cancelSend()
        app.endBackgroundTask(task)
        task = UIBackgroundTaskInvalid
    }
    App.controller.send(line, "${n.name} (${n.serial})") { App.navigators.recordUpload(n) }
    App.scope.launch {
        App.controller.state.first { !it.busy }
        if (task != UIBackgroundTaskInvalid) app.endBackgroundTask(task)
        task = UIBackgroundTaskInvalid
    }
}

/** A file from the system's picker over the screens, or the one [App.testPick] names. */
@OptIn(BetaInteropApi::class)
private fun pick(onPicked: (NSURL) -> Unit) {
    val root = App.root ?: return
    val test = App.testPick
    if (test == null) {
        App.picker.pick(root, onPicked)
        return
    }
    val data = NSData.create(base64EncodedString = test.second, options = 0u) ?: return
    val url = NSURL.fileURLWithPath(NSTemporaryDirectory()).URLByAppendingPathComponent(test.first) ?: return
    if (data.writeToURL(url, atomically = true)) onPicked(url)
}

private fun pickRouteFile() {
    pick { url ->
        App.controller.importFile { url.lastPathComponent to takeAtMost(url, ROUTE_FILE_LIMIT) }
    }
}

private fun pickCredential(then: () -> Unit) {
    pick { url ->
        App.controller.importCredential { takeAtMost(url, CREDENTIAL_FILE_LIMIT).decodeToString() }
        then()
    }
}

private fun openLink(url: String) {
    val u = NSURL.URLWithString(url) ?: return
    UIApplication.sharedApplication.openURL(u, emptyMap<Any?, Any?>(), null)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Screens() {
    val route by App.controller.state.collectAsState()
    val navigators by App.navigators.state.collectAsState()
    var sheet by remember { mutableStateOf(Sheet.NONE) }
    var aboutOpen by remember { mutableStateOf(false) }

    SendFplTheme {
        if (aboutOpen) {
            AboutScreen(
                version = App.version,
                onBack = { aboutOpen = false },
                onOpenSetup = { sheet = Sheet.SETUP },
                onOpenLink = ::openLink,
                onTryDemo = {
                    App.navigators.demonstrate()
                    App.controller.dismissOutcome()
                    aboutOpen = false
                },
            )
        } else {
            HomeScreen(
                state = home(navigators, route),
                onRouteChange = App.controller::setRoute,
                onPickRouteFile = ::pickRouteFile,
                onSend = ::send,
                onCancel = App.controller::cancelSend,
                onOpenDevices = {
                    App.navigators.refresh()
                    sheet = Sheet.DEVICES
                },
                onOpenLog = { sheet = Sheet.LOG },
                onOpenSetup = { sheet = Sheet.SETUP },
                onOpenAbout = { aboutOpen = true },
                // HomeState.blocked stays null on iOS, which leaves these three to Android.
                onGrantPermission = {},
                onOpenAppSettings = {},
                onEnableBluetooth = {},
            )
        }

        if (sheet != Sheet.NONE) {
            ModalBottomSheet(onDismissRequest = { sheet = Sheet.NONE }) {
                when (sheet) {
                    Sheet.DEVICES -> NavigatorSheet(
                        state = navigators,
                        route = route,
                        onSelect = {
                            App.navigators.select(it)
                            App.controller.dismissOutcome()
                            sheet = Sheet.NONE
                        },
                        onRefresh = App.navigators::refresh,
                        onPair = App.navigators::pair,
                        onSelectProfile = App.controller::selectProfile,
                    )

                    Sheet.LOG -> LogSheet(lines = route.log, onClear = App.controller::clearLog)

                    Sheet.SETUP -> SetupSheet(
                        credentialPresent = route.credentialPresent == true,
                        onImport = { pickCredential { sheet = Sheet.NONE } },
                        onForget = App.controller::forgetCredential,
                    )

                    Sheet.NONE -> Unit
                }
            }
        }
    }
}

/**
 * The navigators iOS has connected, the one Send aims at, iOS's own pairing list, and what a
 * session learned of the navigator with the model picker under it.
 */
@Composable
private fun NavigatorSheet(
    state: NavigatorState,
    route: RouteState,
    onSelect: (Navigator) -> Unit,
    onRefresh: () -> Unit,
    onPair: () -> Unit,
    onSelectProfile: (String) -> Unit,
) {
    Column(
        // Scrollable, as Android's device sheet is, for a large font scale.
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(Res.string.devices_title_ios), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onRefresh) { Text(stringResource(Res.string.action_refresh)) }
        }
        if (state.list.isEmpty()) {
            Text(stringResource(Res.string.devices_empty_ios), style = MaterialTheme.typography.bodyMedium)
        }
        state.list.forEach { n ->
            key(n.serial) {
                NavigatorRow(
                    navigator = n,
                    selected = n.serial == state.selected?.serial,
                    lastUsed = state.lastUsed[n.serial],
                    // A send keeps the navigator it started with.
                    enabled = !route.busy,
                    onSelect = { onSelect(n) },
                )
            }
        }
        TextButton(onClick = onPair, enabled = !route.busy) { Text(stringResource(Res.string.action_pair)) }
        NavigatorDetails(route.info, route.device, route.busy, onSelectProfile)
    }
}

@Composable
private fun NavigatorRow(
    navigator: Navigator,
    selected: Boolean,
    lastUsed: Long?,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Null onClick: the whole row is the target.
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(navigator.name, style = MaterialTheme.typography.bodyLarge)
            val label = stringResource(
                if (navigator.live) Res.string.device_label_connected else Res.string.device_label_not_connected
            )
            Text(
                stringResource(Res.string.device_address_with_state, navigator.serial, label),
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
            lastUsed?.let {
                Text(lastUsedText(it), style = MaterialTheme.typography.labelSmall, color = muted)
            }
        }
    }
}

/**
 * When the navigator last took a plan: today, yesterday, or a date in the pattern the locale gives
 * for day and abbreviated month, with the year only outside the current one, as on Android.
 */
@Composable
private fun lastUsedText(atMillis: Long): String {
    val then = NSDate.dateWithTimeIntervalSince1970(atMillis / 1000.0)
    val calendar = NSCalendar.currentCalendar
    return when {
        calendar.isDateInToday(then) -> stringResource(Res.string.device_last_used_today)
        calendar.isDateInYesterday(then) -> stringResource(Res.string.device_last_used_yesterday)
        else -> {
            val thisYear = calendar.component(NSCalendarUnitYear, NSDate()) == calendar.component(NSCalendarUnitYear, then)
            val f = NSDateFormatter()
            f.locale = NSLocale.currentLocale
            f.setLocalizedDateFormatFromTemplate(if (thisYear) "dMMM" else "dMMMyyyy")
            stringResource(Res.string.device_last_used_on, f.stringFromDate(then))
        }
    }
}
