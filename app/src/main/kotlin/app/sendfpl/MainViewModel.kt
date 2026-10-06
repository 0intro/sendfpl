package app.sendfpl

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.content.Context
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
import app.sendfpl.cxp.AuthException
import app.sendfpl.cxp.Coordinate
import app.sendfpl.cxp.CxpEvent
import app.sendfpl.cxp.Profile
import app.sendfpl.cxp.Profiles
import app.sendfpl.cxp.SessionException
import app.sendfpl.route.ParsedRoute
import app.sendfpl.route.RouteImporter
import app.sendfpl.route.RouteParser
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Words for the pilot, chosen when they are shown rather than when they are made: the view model
 * outlives a change of language, and a sentence worded beforehand would stay in the old one.
 */
fun interface Wording {
    fun words(context: Context): String
}

/** The outcome of the last send, so the UI can say something specific. */
sealed interface Outcome {
    data object Success : Outcome
    data class Failure(val message: Wording, val step: Step?) : Outcome
}

data class UiState(
    /** Null until the store has been read, so the setup card does not flash on every start. */
    val credentialPresent: Boolean? = null,
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
    /**
     * The navigator model whose parser limits apply: the picker's, until a navigator identifies
     * itself during a send and the send adopts the model it names. A route is previewed against it
     * before any navigator has been reached. Starts as the model `Settings.profileProductId`
     * remembers, or the GPS 175.
     */
    val device: Profile = Profiles.GPS175,
    val routeText: String = "",
    val parsed: ParsedRoute? = null,
    val routeError: Wording? = null,
    val preview: String? = null,
    /** A route file is being read. The box still shows the route before it until it lands. */
    val importing: Boolean = false,
    val busy: Boolean = false,
    val step: Step? = null,
    val outcome: Outcome? = null,
    val info: DeviceInfo = DeviceInfo(),
    val log: List<String> = emptyList(),
) {
    /**
     * Needs [preview] rather than [parsed]: a name too long for the selected navigator parses but
     * does not render, and the box should say so before a Bluetooth session does.
     */
    val canSend: Boolean
        get() = !busy && !importing && credentialPresent == true && permissionsGranted &&
            bluetoothEnabled && selected != null && preview != null

    /**
     * Whether the last send went out under the picker's model, the navigator having named none this
     * app has limits for.
     */
    val usedPickerModel: Boolean
        get() = outcome is Outcome.Success && info.productData?.profile == null
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val devices = GarminDevices(app)
    private val prober = DeviceProbe(app)
    private val credentials = CredentialStore(app)
    private val history = DeviceHistory(app)
    private val settings = Settings(app)

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

    /**
     * Whether the model in [UiState.device] has been settled during this launch, by the picker or
     * by a navigator identifying itself, so the remembered one arriving late does not override it.
     */
    @Volatile
    private var profileSettled = false

    /**
     * Completed once [restoreProfile] has read the remembered model, whatever it found. [adopt]
     * waits for it, so a file opened with the app is imported for that model, not the default.
     */
    private val profileRestored = CompletableDeferred<Unit>()

    init {
        restoreProfile()
    }

    /** A transfer left running would keep the navigator's single client slot. */
    override fun onCleared() = cancelSend()

    /**
     * Start the picker on the model last chosen or identified, applied through [useProfile] like a
     * tap on the picker so a route already in the box is rendered again for it.
     */
    private fun restoreProfile() {
        viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { settings.profileProductId }
                val p = Profiles.byProductId[saved] ?: return@launch
                if (profileSettled || p == _state.value.device) return@launch
                useProfile(p)
            } finally {
                profileRestored.complete(Unit)
            }
        }
    }

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

                _state.update { s ->
                    val pick = selection(facts, s.selected?.address ?: read.chosen, read.lastUsed)
                    s.copy(
                        credentialPresent = read.credentialPresent,
                        permissionsGranted = granted,
                        bluetoothEnabled = enabled,
                        devices = list,
                        lastUsed = read.lastUsed,
                        showAllDevices = read.showAll,
                        selected = when {
                            // Never change aim during a send: the transfer keeps its device, and
                            // the chip would name the wrong navigator.
                            s.busy && s.selected != null -> s.selected
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
     * rather than in the sheet to keep the clock out of composition; a verdict's age is brought up
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
            busy = s.busy,
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
                if (_state.value.busy) return@withLock
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
            if (_state.value.busy) break
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
        if (_state.value.busy) return
        _state.update { it.copy(selected = device, outcome = null) }
        reclassify()
        viewModelScope.launch {
            refreshLock.withLock {
                withContext(Dispatchers.IO) { settings.chosenAddress = device.address }
            }
        }
    }

    /**
     * What the route box shows for one route: the route itself, what is wrong with it, and the
     * string that would go on the wire. Decided together, because rendering is where the selected
     * navigator's identifier caps apply.
     */
    private data class Review(
        val parsed: ParsedRoute?,
        val error: Wording?,
        val preview: String?,
    )

    /** Called outside `_state.update`, whose body may run more than once. */
    private fun review(attempt: Result<ParsedRoute>, device: Profile): Review {
        val route = attempt.getOrNull()
        val rendered = route?.let { runCatching { it.render(device) } }
        val failure = attempt.exceptionOrNull() ?: rendered?.exceptionOrNull()
        return Review(route, failure?.let { describe(it) }, rendered?.getOrNull())
    }

    /** Parse again on every keystroke so the preview and errors track what is typed. */
    fun setRoute(text: String) {
        importJob?.cancel()
        // Typing replaces the file, which a model change would otherwise import over the edit.
        lastImport = null
        if (text.isBlank()) placed = emptyMap()
        val reviewed = review(runCatching { RouteParser.parse(text, placed) }, _state.value.device)
        _state.update {
            it.copy(
                routeText = text,
                parsed = reviewed.parsed,
                routeError = if (text.isBlank()) null else reviewed.error,
                preview = reviewed.preview,
                outcome = null,
            )
        }
    }

    /** Shared text, a new route like one typed into an empty box: no file's positions apply. */
    fun acceptSharedText(text: String) {
        placed = emptyMap()
        setRoute(text.trim())
    }

    /**
     * The positions the imported file gave the names the route box shows bare. See
     * `ParsedRoute.placed`. Kept apart from the box so an edit keeps them, since they settle a
     * duplicated identifier; replaced by the next import, cleared by an emptied box or shared text.
     */
    private var placed: Map<String, List<Coordinate>> = emptyMap()

    /**
     * The file the current route was imported from, so a change of model can shorten its names
     * again. The bytes rather than the [Uri], whose share grant does not outlive the activity.
     */
    private var lastImport: Pair<String?, ByteArray>? = null

    /**
     * The import in flight, so a second one supersedes it rather than racing it: otherwise the
     * slower would win, and a route could be shortened for a model the picker no longer shows.
     */
    private var importJob: Job? = null

    /**
     * A route file picked, opened or shared: `.fpl`, `.gpx`, `.kml`, `.pln`, SkyDemon's own
     * `.flightplan`, or a Garmin `.gfp`.
     */
    fun importRouteFile(uri: Uri) {
        launchImport {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = getApplication<Application>().contentResolver
                    val bytes = resolver.openInputStream(uri)?.use { it.readAtMost(ROUTE_FILE_LIMIT) }
                        ?: throw UnopenableFileException()
                    displayName(uri) to bytes
                }
            }
            result.onSuccess { (name, bytes) -> adopt(name, bytes) }
                .onFailure { e ->
                    _state.update { it.copy(routeError = describe(e)) }
                }
        }
    }

    /** Make [block] the import in flight, with [UiState.importing] set until the last one ends. */
    private fun launchImport(block: suspend () -> Unit) {
        importJob?.cancel()
        val job = viewModelScope.launch { block() }
        importJob = job
        _state.update { it.copy(importing = true) }
        job.invokeOnCompletion {
            if (importJob === job) _state.update { s -> s.copy(importing = false) }
        }
    }

    /**
     * Import bytes already in hand against the selected model, for a first import and a re-import
     * alike. Parses on [Dispatchers.IO]: a DOM parse of up to [ROUTE_FILE_LIMIT] bytes.
     */
    private suspend fun adopt(name: String?, bytes: ByteArray) {
        profileRestored.await()
        val device = _state.value.device
        val result = withContext(Dispatchers.IO) {
            runCatching { RouteImporter.import(name, bytes, device) }
        }
        val reviewed = review(result, device)
        result
            .onSuccess { route ->
                lastImport = name to bytes
                placed = route.placed
                _state.update {
                    it.copy(
                        routeText = route.identifiers.joinToString(" "),
                        parsed = route,
                        // A file can import and still not render, so the error is the review's.
                        routeError = reviewed.error,
                        preview = reviewed.preview,
                        outcome = null,
                    )
                }
            }
            .onFailure { e ->
                // [lastImport] stays: a failed import leaves the route in hand, and its file.
                _state.update { it.copy(routeError = describe(e)) }
            }
    }

    fun importCredential(uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = getApplication<Application>().contentResolver
                        .openInputStream(uri)?.use { it.readAtMost(CREDENTIAL_FILE_LIMIT) }
                        ?.decodeToString()
                        ?: throw UnopenableFileException()
                    // Stored here, on IO: encrypting is a keystore round trip.
                    credentials.save(CredentialStore.parse(text))
                }
            }
            result.onSuccess {
                _state.update { s -> s.copy(credentialPresent = true, outcome = null) }
            }.onFailure { e ->
                // A refusal names the field at fault in the English of its throw site.
                val message = if (e is AuthException) {
                    say(R.string.credential_invalid, e.message.orEmpty())
                } else {
                    describe(e)
                }
                _state.update { it.copy(outcome = Outcome.Failure(message, null)) }
            }
        }
    }

    fun forgetCredential() {
        viewModelScope.launch { withContext(Dispatchers.IO) { credentials.clear() } }
        _state.update { it.copy(credentialPresent = false) }
    }

    fun clearLog() = _state.update { it.copy(log = emptyList()) }

    /** A string resource, for the messages this class produces rather than a Composable. */
    private fun say(id: Int, vararg args: Any) = Wording { it.getString(id, *args) }

    /** A throw site's English, or [fallback] where it wrote none. */
    private fun english(e: Throwable, fallback: Int): Wording =
        e.message?.let { message -> Wording { message } } ?: say(fallback)

    /**
     * What to show for a failure: a `Problem`, an oversized or unopenable file in the pilot's
     * language, anything else in the English its throw site wrote.
     */
    private fun describe(e: Throwable): Wording {
        e.problem?.let { problem -> return Wording { it.say(problem) } }
        return when (e) {
            is FileTooLargeException -> Wording { context ->
                context.getString(
                    R.string.file_too_big,
                    humanLimit(
                        e.limit,
                        megabytes = { context.getString(R.string.size_megabytes, it) },
                        kilobytes = { context.getString(R.string.size_kilobytes, it) },
                    ),
                )
            }
            is UnopenableFileException -> say(R.string.file_unopenable)
            else -> english(e, R.string.file_unreadable)
        }
    }

    /**
     * What to show for a transfer that failed at [step]: the reasons a pilot can act on in their
     * language, and the protocol's own English for the rest, which the log carries in full anyway.
     */
    private fun describeTransfer(e: Throwable, step: Step?): Wording = when {
        e.problem != null -> describe(e)
        e is NoCredentialException -> say(R.string.credential_missing)
        e is SessionException -> when (e.reason) {
            SessionException.Reason.UPLOAD_REFUSED -> say(R.string.transfer_refused)
            SessionException.Reason.CREDENTIAL_REFUSED -> say(R.string.transfer_credential_refused)
            SessionException.Reason.NO_REPLY -> say(R.string.transfer_no_reply)
            null -> english(e, R.string.transfer_failed)
        }
        e is IOException && step == Step.CONNECTING -> say(R.string.transfer_unreachable)
        e is IOException -> say(R.string.transfer_link_lost)
        else -> english(e, R.string.transfer_failed)
    }

    /**
     * Add a line to the log on screen, and [logcat] to logcat. They differ where the line holds
     * route content: a bug report reads logcat, the screen log leaves the phone only if sent.
     */
    private fun log(line: String, logcat: String = line) {
        Trace.line(logcat)
        _state.update { it.copy(log = it.log + line) }
    }

    /** The name the file was shared or picked under, which is what chooses its parser. */
    private fun displayName(uri: Uri): String? = runCatching {
        getApplication<Application>().contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull() ?: uri.lastPathSegment

    /**
     * Choose the navigator model whose parser limits apply. An imported route is imported again,
     * not just re-rendered, because names are shortened to the model's caps at import. Not during
     * a send, whose route is already built; and it clears the last outcome, which can name the
     * model it was sent under.
     */
    fun selectProfile(name: String) {
        if (_state.value.busy) return
        val p = runCatching { Profiles.named(name) }.getOrNull() ?: return
        profileSettled = true
        rememberProfile(p)
        _state.update { it.copy(outcome = null) }
        useProfile(p)
    }

    /** Remember [p] for the next launch. See `Settings.profileProductId`. */
    private fun rememberProfile(p: Profile) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { settings.profileProductId = p.productId }
        }
    }

    /** Apply a model: re-render the route in hand, or import its file again. */
    private fun useProfile(p: Profile) {
        _state.update { it.copy(device = p) }
        val file = lastImport
        if (file == null) {
            // Nothing to shorten again, but the new model may still refuse to render the route.
            val route = _state.value.parsed ?: return
            val reviewed = review(Result.success(route), p)
            _state.update { it.copy(routeError = reviewed.error, preview = reviewed.preview) }
            return
        }
        launchImport { adopt(file.first, file.second) }
    }

    /**
     * Cancel a transfer in flight. Closing the link is what makes this immediate: the transfer
     * blocks in `BluetoothSocket.connect` and in reads, which a coroutine cancellation does not
     * interrupt. Cancelling the job stops the steps that would follow.
     */
    fun cancelSend() {
        // The navigator already holds the plan; closing now would report a delivered plan as
        // cancelled. The wait for a refusal ends on its own within the grace.
        if (_state.value.step == Step.VERIFYING) return
        cancelRequested = true
        activeLink?.close()
        sendJob?.cancel()
    }

    private var sendJob: Job? = null

    /** The link a transfer is holding, so [cancelSend] can close it from another thread. */
    @Volatile
    private var activeLink: ConnextLink? = null

    /**
     * Whether the pilot cancelled, so a transfer is not reported as failed when closing the socket
     * throws an ordinary IOException before the coroutine notices the cancellation.
     */
    @Volatile
    private var cancelRequested = false

    /**
     * Open the link, waiting out a stack still releasing the last attempt: a cancelled connect
     * holds its RFCOMM slot until its SDP query times out, and a connect meanwhile is refused at
     * once (`find_rfc_slot_by_id unable to find RFCOMM slot id`). A refusal is told from a real
     * failure by how long it took. See [REFUSAL_MILLIS].
     */
    // Called only by [transfer], so gated like [send].
    @SuppressLint("MissingPermission")
    private suspend fun connectWaitingOutARefusal(device: BluetoothDevice): ConnextLink {
        val deadline = System.nanoTime() + REFUSAL_BUDGET_MILLIS * 1_000_000L
        var waited = false
        while (true) {
            val started = System.nanoTime()
            try {
                return ConnextLink.connect(device, { devices.cancelDiscovery() }) { link ->
                    activeLink = link
                    // A Cancel that came before the link existed had nothing to close.
                    if (cancelRequested) link.close()
                }
            } catch (e: IOException) {
                val tookMillis = (System.nanoTime() - started) / 1_000_000
                if (tookMillis >= REFUSAL_MILLIS || System.nanoTime() >= deadline) throw e
                if (!waited) {
                    waited = true
                    log("the Bluetooth stack is still releasing the last attempt, waiting")
                }
                delay(SETTLE_MILLIS)
            }
        }
    }

    /** Send the selected route to the selected navigator, and report how it went. */
    // Behind the Send button, which `UiState.canSend` enables only with `permissionsGranted`.
    @SuppressLint("MissingPermission")
    fun send() {
        val s = _state.value
        if (s.busy) return
        val device = s.selected ?: return
        val route = s.parsed ?: return
        if (!devices.isEnabled) {
            _state.update {
                it.copy(bluetoothEnabled = false, outcome = Outcome.Failure(say(R.string.blocked_bluetooth_off), null))
            }
            return
        }

        cancelRequested = false
        _state.update {
            it.copy(busy = true, outcome = null, log = emptyList(), step = Step.CONNECTING, info = DeviceInfo())
        }

        // Stop a sweep already running; `probePlan` plans none while busy.
        probeJob?.cancel()
        settleProbing()

        sendJob = viewModelScope.launch {
            val result = try {
                withContext(Dispatchers.IO) { runCatching { transfer(device, route, s.device) } }
            } catch (e: CancellationException) {
                // `withContext` throws on a cancelled parent, so nothing below would run.
                activeLink?.close()
                activeLink = null
                log("cancelled")
                _state.update { it.copy(busy = false, step = null, outcome = null) }
                throw e
            }
            result.onSuccess {
                // A completed upload proves the device reachable and tells it from a namesake.
                history.recordUpload(device.address)
                probes[device.address] = Probe.Live(nowMillis())
                _state.update {
                    it.copy(
                        busy = false,
                        step = Step.DONE,
                        outcome = Outcome.Success,
                        lastUsed = history.lastUsed(),
                    )
                }
                // The rows keep their places until the next refresh sorts them.
                reclassify()
            }.onFailure { e ->
                if (cancelRequested) {
                    log("cancelled")
                    _state.update { it.copy(busy = false, step = null, outcome = null) }
                    return@onFailure
                }
                val where = _state.value.step
                // A refusal can quote the route, which logcat must not see. See [log].
                log("failed: ${e.message}", "failed: ${e.problem?.javaClass?.simpleName ?: e.javaClass.simpleName}")
                _state.update {
                    it.copy(busy = false, outcome = Outcome.Failure(describeTransfer(e, where), where))
                }
            }
        }
    }

    /**
     * The whole transfer, blocking: connect, authenticate, negotiate, upload. Returns the route
     * string sent. Every packet is logged and the step kept, because on a reverse-engineered
     * protocol the useful output of a failure is where it stopped.
     */
    @SuppressLint("MissingPermission")
    private suspend fun transfer(device: PairedDevice, route: ParsedRoute, profile: Profile): String {
        val credential = credentials.load() ?: throw NoCredentialException()
        currentCoroutineContext().ensureActive()
        log("connecting to ${device.name} (${device.address})")
        // Publishes [activeLink] before connecting, so Cancel can close a connect in progress.
        val link = connectWaitingOutARefusal(device.device)
        // One exit: the navigator allows one client per channel, and a leaked link would hold it.
        try {
            if (!currentCoroutineContext().isActive) throw CancellationException("cancelled while connecting")
            val client = ConnextClient(
                link = link,
                credential = credential,
                onStep = { step -> _state.update { it.copy(step = step) } },
                onEvent = ::logEvent,
            )
            client.connectAndAuthenticate()
            val caps = client.negotiateCapabilities()
            // The route waits for the model, which a GPS 175 names about 90 ms after the
            // capabilities: models differ in what a user waypoint is sent as.
            client.awaitProductData()
            _state.update { it.copy(info = client.info) }
            // Silence, or a model with no measured profile, keeps the picker's model.
            val identified = client.productData?.profile
            val effective = identified ?: profile
            if (identified != null) {
                profileSettled = true
                settings.profileProductId = identified.productId
            }
            if (effective != profile) {
                log("using the ${effective.name} limits, which the navigator asked for")
                // The preview is what is sent, so it follows the model.
                val reviewed = review(Result.success(route), effective)
                _state.update {
                    it.copy(device = effective, routeError = reviewed.error, preview = reviewed.preview)
                }
            }
            val text = route.render(effective, caps)
            // The plan itself stays out of logcat. See [log].
            log("route (${text.length} bytes): $text", "route (${text.length} bytes)")
            client.upload(text, effective, caps)
            return text
        } finally {
            link.close()
            activeLink = null
        }
    }

    private fun logEvent(event: CxpEvent) {
        log(
            when (event) {
                is CxpEvent.Sent -> "→ ${event.packet.describe()}"
                is CxpEvent.Received -> "← ${event.packet.describe()}"
                is CxpEvent.Resent -> "↻ ${event.packet.describe()} (retry ${event.attempt})"
                is CxpEvent.Note -> "  ${event.text}"
            }
        )
    }
}

/** The credential store holds nothing a transfer could authenticate with. */
private class NoCredentialException : Exception("no credential imported")

/** The provider gave no stream for a file that was picked, opened or shared. */
private class UnopenableFileException : IOException("could not open the file")

/**
 * The largest route file this app reads into memory. The picker filters nothing, and an unbounded
 * read of a mis-picked video runs out of memory; a route string is at most 3520 bytes.
 */
internal const val ROUTE_FILE_LIMIT = 4 * 1024 * 1024

/**
 * The largest credential file this app reads. The picker accepts any MIME type, since a file
 * manager may type a `.json` as something else; a credential is a few hundred bytes.
 */
internal const val CREDENTIAL_FILE_LIMIT = 64 * 1024

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

/**
 * A file far larger than anything this reads. Carries the bound, so the message can be worded
 * from resources in the pilot's language.
 */
private class FileTooLargeException(val limit: Int) : IllegalArgumentException(
    "that file is bigger than ${humanLimit(limit)}, which is far more than this needs. " +
        "It is probably not the file you meant to pick"
)

/**
 * A byte count as a pilot would read it, so a limit of 64 KiB does not print as "0 MB". In
 * English units unless the caller words them, as the screen does from its resources.
 */
internal fun humanLimit(
    bytes: Int,
    megabytes: (Int) -> String = { "$it MB" },
    kilobytes: (Int) -> String = { "$it kB" },
): String =
    if (bytes >= 1024 * 1024) megabytes(bytes / (1024 * 1024)) else kilobytes(bytes / 1024)

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
