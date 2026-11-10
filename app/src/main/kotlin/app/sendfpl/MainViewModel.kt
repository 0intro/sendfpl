package app.sendfpl

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.sendfpl.bt.ConnextLink
import app.sendfpl.bt.DeviceProbe
import app.sendfpl.bt.DeviceStatus
import app.sendfpl.bt.FRESH_MILLIS
import app.sendfpl.bt.GarminDevices
import app.sendfpl.bt.PairedDevice
import app.sendfpl.bt.Probe
import app.sendfpl.bt.anotherAnswers
import app.sendfpl.bt.classify
import app.sendfpl.bt.deviceOrder
import app.sendfpl.bt.facts
import app.sendfpl.bt.nowMillis
import app.sendfpl.bt.probePlan
import app.sendfpl.bt.selection
import app.sendfpl.cxp.Carrier
import app.sendfpl.cxp.CxpEvent
import app.sendfpl.cxp.GPS175_PARAMS
import app.sendfpl.cxp.Session
import app.sendfpl.ui.Blocked
import app.sendfpl.ui.HomeState
import app.sendfpl.ui.resources.Res
import app.sendfpl.ui.resources.blocked_bluetooth_off
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/** The navigators Android knows of. The route and the transfer are the [Controller]'s. */
data class UiState(
    val permissionsGranted: Boolean = false,
    val bluetoothEnabled: Boolean = false,
    /**
     * Every bonded device in display order, the navigator most likely wanted first. Sorted only by
     * a refresh: the sheet opening, its Refresh, a return to the app. Never as a sweep's verdicts
     * land, so rows do not move under a finger.
     */
    val devices: List<PairedDevice> = emptyList(),
    /**
     * What the sheet should say about each device, keyed by address as Garmin's own
     * `ConnextConnectionTable` is: `bonded()` builds fresh [PairedDevice] instances on every
     * refresh, and a navigator can be renamed. A missing entry means nothing to say.
     */
    val status: Map<String, DeviceStatus> = emptyMap(),
    /** Address to the epoch milliseconds of the last upload, which tells two GPS175s apart. */
    val lastUsed: Map<String, Long> = emptyMap(),
    /** What Send aims at, kept until it is unpaired. See `bt.selection`. */
    val selected: PairedDevice? = null,
    /** Another navigator answers while [selected] does not. See `bt.anotherAnswers`. */
    val anotherAnswers: Boolean = false,
    /** Whether the sheet lists every bonded device, or only those `DeviceStatus.garmin` admits. */
    val showAllDevices: Boolean = false,
)

/** What the shared home screen shows: [route], and of [ui] the navigator Send aims at. */
internal fun home(ui: UiState, route: RouteState) = HomeState(
    credentialPresent = route.credentialPresent,
    navigator = ui.selected?.name,
    indicator = ui.selected?.let { ui.status[it.address]?.indicator },
    anotherAnswers = ui.anotherAnswers,
    blocked = when {
        !ui.permissionsGranted -> Blocked.PERMISSION
        !ui.bluetoothEnabled -> Blocked.BLUETOOTH_OFF
        else -> null
    },
    device = route.device,
    routeText = route.routeText,
    parsed = route.parsed,
    routeError = route.routeError,
    preview = route.preview,
    busy = route.busy,
    step = route.step,
    outcome = route.outcome,
    canSend = route.canSend && ui.permissionsGranted && ui.bluetoothEnabled && ui.selected != null,
    usedPickerModel = route.usedPickerModel,
    hasLog = route.log.isNotEmpty(),
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val devices = GarminDevices(app)
    private val prober = DeviceProbe(app)
    private val credentials = CredentialStore(app)
    private val history = DeviceHistory(app)
    private val settings = Settings(app)

    /** The route, the model and the transfer, as the iPhone app holds them too. */
    val controller = Controller(
        scope = viewModelScope,
        io = Dispatchers.IO,
        store = object : Store {
            override fun loadCredential() = credentials.load()

            override fun saveCredential(json: String) = credentials.save(CredentialStore.parse(json))

            override fun clearCredential() = credentials.clear()

            override var profileProductId: Long
                get() = settings.profileProductId
                set(value) {
                    settings.profileProductId = value
                }
        },
        trace = Trace::line,
    )

    /**
     * Probe results by address, so they survive [UiState.devices] being rebuilt. Unsynchronised:
     * it is read and written only on `viewModelScope`'s main dispatcher.
     */
    private val probes = mutableMapOf<String, Probe>()
    private var probeJob: Job? = null

    /**
     * Serialises [refresh], [retry] and [stopSweep]. Two refreshes interleaving in [sweep] could
     * otherwise both launch a sweep, and probes must never run concurrently. [select] writes the
     * choice under it too, so a refresh that read the one before cannot forget the new one.
     */
    private val refreshLock = Mutex()

    private val _state = MutableStateFlow(
        UiState(permissionsGranted = devices.hasPermissions(), bluetoothEnabled = devices.isEnabled)
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * Whether the Bluetooth permission has been asked for in this view model's life, so a rotation
     * or a return from Settings does not ask again.
     */
    var permissionAsked = false

    /**
     * The bundled credential, adopted once per view model. Forget therefore lasts until the app
     * next starts, as the setup sheet says, rather than until the next refresh.
     */
    private val adoption = viewModelScope.launch {
        withContext(Dispatchers.IO) { credentials.adoptBundled() }
    }

    /** A transfer left running would keep the navigator's single client slot. */
    override fun onCleared() = controller.cancelSend()

    /** Whether a send is running: it keeps its device, and nothing is probed meanwhile. */
    private val busy: Boolean get() = controller.state.value.busy

    /**
     * Read the bonded devices again, sort them, aim if nothing is aimed at, and page the plausible
     * ones. [force], from the sheet's Refresh, probes again where an ordinary refresh would reuse a
     * recent answer.
     */
    fun refresh(force: Boolean = false) {
        viewModelScope.launch {
            refreshLock.withLock {
                adoption.join()
                // Read before `update`, whose body may run more than once.
                val read = withContext(Dispatchers.IO) {
                    Snapshot(
                        credentialPresent = credentials.isPresent,
                        bonded = devices.bondedIfReadable(),
                        lastUsed = history.lastUsed(),
                        showAll = settings.showAllDevices,
                        chosen = settings.chosenAddress,
                    )
                }
                val granted = devices.hasPermissions()
                val enabled = devices.isEnabled
                // Bonds that cannot be read say nothing about what was unpaired, and nor does an
                // empty list, which is what a radio changing state returns: either way the choice
                // stays and nothing is pruned.
                val list = read.bonded.orEmpty()
                val facts = list.map { it.facts(probes[it.address] ?: Probe.NotProbed) }

                controller.credentialPresent(read.credentialPresent)
                val sending = busy
                _state.update { s ->
                    val pick = selection(facts, s.selected?.address ?: read.chosen, read.lastUsed)
                    s.copy(
                        permissionsGranted = granted,
                        bluetoothEnabled = enabled,
                        devices = list,
                        lastUsed = read.lastUsed,
                        showAllDevices = read.showAll,
                        selected = when {
                            // Never change aim during a send: the transfer keeps its device, and
                            // the chip would name the wrong navigator.
                            sending && s.selected != null -> s.selected
                            list.isEmpty() -> s.selected
                            else -> list.find { it.address == pick }
                        },
                    )
                }
                if (read.bonded == null) {
                    // A page in flight would end silent with the radio off, blaming the device.
                    probeJob?.cancelAndJoin()
                    settleProbing()
                    reclassify()
                    return@withLock
                }

                if (list.isNotEmpty()) {
                    val addresses = list.mapTo(mutableSetOf()) { it.address }
                    probes.keys.retainAll(addresses)
                    history.retainOnly(addresses)
                    if (read.chosen != null && read.chosen !in addresses) {
                        withContext(Dispatchers.IO) { settings.chosenAddress = null }
                    }
                }

                reclassify(reorder = true)
                // Returns as soon as the sweep is launched, so the lock is held only briefly.
                sweep(force)
            }
        }
    }

    /** Put a device list into display order. The one place `deviceOrder` is applied. */
    private fun ordered(
        list: List<PairedDevice>,
        lastUsed: Map<String, Long>,
    ): List<PairedDevice> {
        val byAddress = list.associateBy { it.address }
        val order = deviceOrder(list.map { it.facts(probes[it.address] ?: Probe.NotProbed) }, lastUsed)
        return order.mapNotNull { byAddress[it] }
    }

    /**
     * Recompute [UiState.status] and [UiState.anotherAnswers] from the current facts. Done here
     * rather than in the sheet to keep the clock out of composition. A verdict's age is brought up
     * to date whenever the sheet is opened. The selection is never touched.
     *
     * [reorder] also sorts the list, which only a refresh asks for: rows that move under a finger
     * are how a pilot taps the wrong navigator.
     */
    private fun reclassify(reorder: Boolean = false) {
        val now = nowMillis()
        _state.update { s ->
            val devices = if (reorder) ordered(s.devices, s.lastUsed) else s.devices
            val facts = devices.map { it.facts(probes[it.address] ?: Probe.NotProbed) }
            s.copy(
                devices = devices,
                status = facts.associate { f ->
                    f.address to classify(f, now, hasUploaded = f.address in s.lastUsed)
                },
                anotherAnswers = anotherAnswers(facts, s.selected?.address, s.lastUsed),
            )
        }
    }

    /**
     * Page the devices worth paging, one at a time, as `DeviceProbe.probe` requires. Nothing is
     * probed during a send: `probePlan` refuses while busy, and [send] cancels this job.
     */
    private suspend fun sweep(force: Boolean) {
        // A refresh that was not asked for, on return to the app say, leaves a sweep running.
        if (!force && probeJob?.isActive == true) return
        probeJob?.cancelAndJoin()
        settleProbing()

        val s = _state.value
        if (!s.permissionsGranted || !s.bluetoothEnabled) return

        val plan = probePlan(
            facts = s.devices.map { it.facts(probes[it.address] ?: Probe.NotProbed) },
            nowMillis = nowMillis(),
            busy = busy,
            freshMillis = if (force) 0L else FRESH_MILLIS,
        )
        if (plan.isEmpty()) return

        probeJob = viewModelScope.launch { probeEach(plan) }
    }

    /**
     * Settle every device a cancelled sweep left being checked back to the verdict it was
     * rechecking, so its row does not say "checking" for ever.
     */
    private fun settleProbing() {
        var settled = false
        probes.entries.forEach { e ->
            val p = e.value
            if (p is Probe.Probing) {
                e.setValue(p.previous ?: Probe.NotProbed)
                settled = true
            }
        }
        // No reorder: `livenessOf` reads through Probing, so only the wording changes.
        if (settled) reclassify()
    }

    /**
     * Stop paging, as the app leaves the screen. The next refresh plans again whatever the sweep
     * did not reach.
     */
    fun stopSweep() {
        viewModelScope.launch {
            refreshLock.withLock {
                probeJob?.cancelAndJoin()
                settleProbing()
            }
        }
    }

    /** Page one device again, as the sheet offers on a row that has not answered. */
    fun retry(device: PairedDevice) {
        viewModelScope.launch {
            refreshLock.withLock {
                probeJob?.cancelAndJoin()
                settleProbing()
                if (busy) return@withLock
                probeJob = viewModelScope.launch { probeEach(listOf(device.address)) }
            }
        }
    }

    // Pages only devices `bonded()` listed, and it lists none without the permission, which lint
    // cannot see.
    @SuppressLint("MissingPermission")
    private suspend fun probeEach(plan: List<String>) {
        for (address in plan) {
            // A send that started during a sweep also cancels this job, and this covers the gap.
            if (busy) break
            val device = _state.value.devices.find { it.address == address } ?: continue
            // Carry the verdict being rechecked, so the row keeps saying what it last knew.
            val previous = probes[address]
            probes[address] = Probe.Probing(previous)
            reclassify()
            val verdict = prober.probe(device.device)
            // A page the radio went off under says nothing about the device.
            probes[address] = if (devices.isEnabled) verdict else previous ?: Probe.NotProbed
            reclassify()
        }
    }

    /**
     * Show every bonded device, or only the Garmin ones. Nothing is reclassified: this only decides
     * whether the sheet acts on `DeviceStatus.garmin`. Persisted, see `Settings.showAllDevices`.
     */
    fun setShowAllDevices(show: Boolean) {
        _state.update { it.copy(showAllDevices = show) }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { settings.showAllDevices = show }
        }
    }

    /**
     * Aim at a device because the pilot said so, until it is unpaired, across restarts too. Not
     * during a send, which keeps its device.
     */
    fun select(device: PairedDevice) {
        if (busy) return
        _state.update { it.copy(selected = device) }
        controller.dismissOutcome()
        reclassify()
        viewModelScope.launch {
            refreshLock.withLock {
                withContext(Dispatchers.IO) { settings.chosenAddress = device.address }
            }
        }
    }

    /** A route file picked, opened or shared, read through its content URI. */
    fun importRouteFile(uri: Uri) {
        controller.importFile {
            val resolver = getApplication<Application>().contentResolver
            val bytes = resolver.openInputStream(uri)?.use { it.readAtMost(ROUTE_FILE_LIMIT) }
                ?: throw UnopenableFileException()
            displayName(uri) to bytes
        }
    }

    fun importCredential(uri: Uri) {
        controller.importCredential {
            getApplication<Application>().contentResolver
                .openInputStream(uri)?.use { it.readAtMost(CREDENTIAL_FILE_LIMIT) }
                ?.decodeToString()
                ?: throw UnopenableFileException()
        }
    }

    /** The name the file was shared or picked under, which is what chooses its parser. */
    private fun displayName(uri: Uri): String? = runCatching {
        getApplication<Application>().contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull() ?: uri.lastPathSegment

    /** Send the selected route to the selected navigator, and report how it went. */
    // Behind the Send button, which `home` enables only with `permissionsGranted`.
    @SuppressLint("MissingPermission")
    fun send() {
        val route = controller.state.value
        if (route.busy || route.parsed == null) return
        val device = _state.value.selected ?: return
        if (!devices.isEnabled) {
            _state.update { it.copy(bluetoothEnabled = false) }
            controller.fail(Wording { stringResource(Res.string.blocked_bluetooth_off) })
            return
        }

        controller.send(RfcommLine(device), "${device.name} (${device.address})") {
            // A completed upload proves the device reachable and tells it from a namesake.
            history.recordUpload(device.address)
            probes[device.address] = Probe.Live(nowMillis())
            _state.update { it.copy(lastUsed = history.lastUsed()) }
            // The rows keep their places until the next refresh sorts them.
            reclassify()
        }

        // Stop a sweep already running. `probePlan` plans none while busy.
        probeJob?.cancel()
        settleProbing()
    }

    /** The RFCOMM socket to [device], one per transfer. */
    private inner class RfcommLine(private val device: PairedDevice) : Line {
        /** The socket, published as soon as it exists, so [close] can end a connect in progress. */
        @Volatile
        private var link: ConnextLink? = null

        @Volatile
        private var closed = false

        // Opened only by a send, so gated like [send].
        @SuppressLint("MissingPermission")
        override suspend fun open(onEvent: (CxpEvent) -> Unit): Carrier =
            Session(connectWaitingOutARefusal(device.device), GPS175_PARAMS, onEvent)

        override fun close() {
            closed = true
            link?.close()
        }

        /**
         * Open the link, waiting out a stack still releasing the last attempt: a cancelled
         * connect holds its RFCOMM slot until its SDP query times out, and a connect meanwhile is
         * refused at once (`find_rfc_slot_by_id unable to find RFCOMM slot id`). A refusal is told
         * from a real failure by how long it took. See [REFUSAL_MILLIS].
         */
        @SuppressLint("MissingPermission")
        private suspend fun connectWaitingOutARefusal(device: BluetoothDevice): ConnextLink {
            val deadline = System.nanoTime() + REFUSAL_BUDGET_MILLIS * 1_000_000L
            var waited = false
            while (true) {
                val started = System.nanoTime()
                try {
                    return ConnextLink.connect(device, { devices.cancelDiscovery() }) { l ->
                        link = l
                        // A Cancel that came before the link existed had nothing to close.
                        if (closed) l.close()
                    }
                } catch (e: IOException) {
                    if (closed) throw e
                    val tookMillis = (System.nanoTime() - started) / 1_000_000
                    if (tookMillis >= REFUSAL_MILLIS || System.nanoTime() >= deadline) throw e
                    if (!waited) {
                        waited = true
                        controller.log("the Bluetooth stack is still releasing the last attempt, waiting")
                    }
                    delay(SETTLE_MILLIS)
                }
            }
        }
    }
}

/**
 * Below this, a failed connect never reached the radio: on Android 14 a refused RFCOMM slot fails
 * in 63 to 75 ms, while a real attempt costs an SDP round trip, 5192 ms against a navigator that
 * is off.
 */
private const val REFUSAL_MILLIS = 500L

/** How long to leave the stack alone between refusals. */
private const val SETTLE_MILLIS = 500L

/**
 * How long to keep retrying refusals before reporting one. Refusals go on for two to three seconds
 * after a cancel when the abandoned SDP query runs to its full timeout, and this elapses only when
 * every attempt was refused.
 */
private const val REFUSAL_BUDGET_MILLIS = 8_000L

/** What [MainViewModel.refresh] reads off the main thread. */
private class Snapshot(
    val credentialPresent: Boolean,
    /** Null when the bonds cannot be read. See `GarminDevices.bondedIfReadable`. */
    val bonded: List<PairedDevice>?,
    val lastUsed: Map<String, Long>,
    val showAll: Boolean,
    /** `Settings.chosenAddress`, where a restart aims. */
    val chosen: String?,
)

/**
 * Read at most [limit] bytes, refusing a longer file rather than truncating it into a half XML
 * document whose error would be about a tag rather than about the file.
 */
internal fun InputStream.readAtMost(limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        if (out.size() + read > limit) throw FileTooLargeException(limit)
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}
