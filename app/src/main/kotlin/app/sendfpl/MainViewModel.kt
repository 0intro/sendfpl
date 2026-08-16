package app.sendfpl

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.sendfpl.bt.ConnextLink
import app.sendfpl.bt.DeviceProbe
import app.sendfpl.bt.DeviceStatus
import app.sendfpl.bt.FRESH_MILLIS
import app.sendfpl.bt.GarminDevices
import app.sendfpl.bt.PairedDevice
import app.sendfpl.bt.Probe
import app.sendfpl.bt.classify
import app.sendfpl.bt.deviceOrder
import app.sendfpl.bt.facts
import app.sendfpl.bt.nowMillis
import app.sendfpl.bt.probePlan
import app.sendfpl.cxp.Profile
import app.sendfpl.cxp.Profiles
import app.sendfpl.cxp.CxpEvent
import app.sendfpl.cxp.Ctrl
import app.sendfpl.cxp.AppFrame
import app.sendfpl.cxp.Packet
import app.sendfpl.route.ParsedRoute
import app.sendfpl.route.RouteImporter
import app.sendfpl.route.RouteParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** The outcome of the last send, so the UI can say something specific. */
sealed interface Outcome {
    data class Success(val route: String) : Outcome
    data class Failure(val message: String, val step: Step?) : Outcome
}

data class UiState(
    val credentialPresent: Boolean = false,
    val permissionsGranted: Boolean = false,
    val bluetoothEnabled: Boolean = false,
    /**
     * Every bonded device, in display order, with the navigator most likely wanted first.
     *
     * The order comes from `deviceOrder`, and it is recomputed only when the radio is quiet, so
     * rows do not move under a finger while a sweep is paging them one at a time.
     */
    val devices: List<PairedDevice> = emptyList(),
    /**
     * What the sheet should say about each device, keyed by address.
     *
     * Keyed rather than carried on [PairedDevice] because `bonded()` builds fresh instances on
     * every refresh, [selected] holds a *separate* instance of the same device, and a pilot may
     * rename the navigator while using it. Address is the identity, which is the same conclusion
     * Garmin's own `ConnextConnectionTable` reached.
     *
     * May not have an entry for every device, so treat a miss as "nothing to say".
     */
    val status: Map<String, DeviceStatus> = emptyMap(),
    /** Address to the epoch milliseconds of the last upload, which tells two GPS175s apart. */
    val lastUsed: Map<String, Long> = emptyMap(),
    val selected: PairedDevice? = null,
    /**
     * Whether the sheet lists every bonded device rather than only the Garmin ones.
     *
     * The filter itself is `DeviceStatus.garmin`, decided per device; this is only the pilot's
     * standing answer to it. See `bt/DeviceStatus.kt` for what makes hiding a row safe at all.
     */
    val showAllDevices: Boolean = false,
    /**
     * The navigator model whose parser limits apply.
     *
     * Not detected on its own: reading it from CXP_ID_PRODUCT_DATA needs that id's payload layout, which
     * has not been derived. Defaulted to the GPS 175 because that is the unit this app was built
     * against, and changed by the picker. A model with no measured profile is refused rather than
     * given these numbers. See `Profile.kt`.
     */
    val device: Profile = Profiles.GPS175,
    val routeText: String = "",
    val parsed: ParsedRoute? = null,
    val routeError: String? = null,
    val preview: String? = null,
    val busy: Boolean = false,
    val step: Step? = null,
    val outcome: Outcome? = null,
    val info: DeviceInfo = DeviceInfo(),
    val log: List<String> = emptyList(),
) {
    /**
     * [preview] rather than [parsed], because rendering is part of deciding whether a route can be
     * sent at all. A name too long for the selected navigator parses perfectly and refuses to
     * encode, and enabling Send for it puts the refusal at the end of a Bluetooth session instead
     * of in the box the pilot is looking at.
     */
    val canSend: Boolean
        get() = !busy && credentialPresent && permissionsGranted &&
            selected != null && preview != null
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val devices = GarminDevices(app)
    private val prober = DeviceProbe(app)
    private val credentials = CredentialStore(app)
    private val history = DeviceHistory(app)
    private val settings = Settings(app)

    /**
     * Probe results by address, so they survive [UiState.devices] being rebuilt.
     *
     * Unsynchronised, and safe: every write happens on `viewModelScope`'s main dispatcher. The only
     * work pushed to another thread is `bonded()`, which does not touch this.
     */
    private val probes = mutableMapOf<String, Probe>()
    private var probeJob: Job? = null

    /**
     * Whether [UiState.selected] is still the app's guess rather than the pilot's choice.
     *
     * While it is true the selection follows the head of the list, so the navigator that answers a
     * sweep becomes the target with no tap. [select] clears it for good, and nothing moves an
     * explicit choice afterwards, because quietly pointing a flight plan at another navigator is
     * the kind of thing this app must never do. It goes back to true only when the chosen device stops
     * being bonded, or the pilot would never get a sensible default again.
     *
     * Not in [UiState]: nothing renders it, and a field there would emit on every flip. Not
     * persisted either: there is no `SavedStateHandle`, [UiState.selected] does not survive
     * process death today, and guessing afresh from a fresh sweep is the right cold start.
     */
    private var autoSelected = true

    /**
     * Serialises [refresh].
     *
     * Two taps on "Refresh" in quick succession would otherwise interleave at the suspension point
     * in [sweep], and both could win: the second cancels the sweep the first had not yet installed,
     * leaving two running at once. Concurrent probes are the one thing [sweep] must never do.
     */
    private val refreshLock = Mutex()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh()
    }

    /**
     * Read what the Bluetooth stack knows again, classify it again, and page the plausible candidates.
     *
     * [force] is what the sheet's "Refresh" passes: an explicit ask bypasses the freshness window
     * and probes again, where an ordinary refresh reuses a recent answer.
     */
    fun refresh(force: Boolean = false) {
        viewModelScope.launch {
            refreshLock.withLock {
                // Everything the update below reads is gathered here first, and on the IO
                // dispatcher where it touches disk or the keystore. `update` retries on compare
                // and set, so its body may run more than once: bonded() makes two binder calls per
                // device, credentialPresent decrypts a blob under a keystore key, and lastUsed()
                // reads a whole preference file. Binding the selection here too is not just
                // economy, since `autoSelected` must not be assigned from a block that may run
                // twice.
                //
                // adoptBundled is on this path rather than in the constructor: every build carries
                // a credential as an asset, and adopting it means reading that asset, creating a
                // keystore key on first launch and writing a preference file, none of which
                // belongs in front of the first frame. It runs on every start, so `Forget` lasts
                // only until the next one, which is what the setup sheet says.
                val (credentialPresent, list, lastUsed) = withContext(Dispatchers.IO) {
                    credentials.adoptBundled()
                    Triple(credentials.isPresent, devices.bonded(), history.lastUsed())
                }
                // Read here rather than in the update below for the same reason as the rest: this
                // is a file read, and `update` may run its body more than once.
                val showAll = withContext(Dispatchers.IO) { settings.showAllDevices }
                val granted = devices.hasPermissions()
                val enabled = devices.isEnabled

                val kept = _state.value.selected?.let { s -> list.find { d -> d.address == s.address } }
                // The chosen device has been unpaired, so there is no choice left to respect and
                // the app may guess again.
                if (kept == null) autoSelected = true

                _state.update {
                    it.copy(
                        credentialPresent = credentialPresent,
                        permissionsGranted = granted,
                        bluetoothEnabled = enabled,
                        devices = list,
                        lastUsed = lastUsed,
                        selected = kept,
                        showAllDevices = showAll,
                    )
                }

                // Forget devices that have been unpaired.
                val addresses = list.mapTo(mutableSetOf()) { it.address }
                probes.keys.retainAll(addresses)
                history.retainOnly(addresses)

                // Orders the list bonded() has only made stable, and picks the default from its
                // head. Nothing has been paged yet, so this is the order the free evidence and the
                // upload history give, and the sweep below is what can change it.
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
     * Recompute [UiState.status] from the current facts.
     *
     * Done here rather than in the sheet to keep the clock out of composition: `classify` takes a
     * timestamp, and a Composable that reads one would recompose into different output for
     * unchanged input. It also means a stale probe result decays back to "unknown" whenever the
     * list is looked at, which is every time the sheet is opened, so a device does not stay
     * dimmed after the pilot has powered it on.
     *
     * [reorder] additionally sorts the list again and picks the automatic selection again, and is
     * passed only when the radio has gone quiet. A verdict landing during a sweep updates what a
     * row *says* without moving where it *sits*: the sheet is a column of radio buttons, and rows that
     * rearrange every eight seconds under a finger are how a pilot taps the wrong navigator. Both
     * happen in one update, so the list and the statuses can never be computed from different
     * snapshots of `probes`.
     */
    private fun reclassify(reorder: Boolean = false) {
        val now = nowMillis()
        _state.update { s ->
            val devices = if (reorder) ordered(s.devices, s.lastUsed) else s.devices
            val status = devices.associate { d ->
                d.address to classify(
                    d.facts(probes[d.address] ?: Probe.NotProbed),
                    now,
                    hasUploaded = d.address in s.lastUsed,
                )
            }
            s.copy(
                devices = devices,
                selected = when {
                    // Never change aim during a send: `send` captured the device it is talking to,
                    // so this would not redirect the transfer, only make the chip name the wrong
                    // navigator. Adopting one where there was none is not a change of aim, so it is
                    // still allowed.
                    s.busy && s.selected != null -> s.selected
                    // The default is the head of the order, by definition rather than by
                    // coincidence: ranking the navigator most likely wanted first and aiming at it
                    // are the same decision, made once.
                    //
                    // The head of the *visible* order, though. `deviceOrder` ranks a Garmin device
                    // first already, so the two agree whenever there is one to find, and they part
                    // company when there is not: aiming at a row the sheet is hiding would leave
                    // the chip naming a device the pilot cannot see, let alone change.
                    reorder && autoSelected ->
                        devices.firstOrNull { status[it.address]?.garmin != false }
                    else -> s.selected
                },
                status = status,
            )
        }
    }

    /**
     * Page the devices worth paging, one at a time.
     *
     * Sequential is not a style choice: AOSP starts its timer of six seconds when a request is
     * *made* while the native layer queues discoveries and runs them one after another, so probing
     * in parallel makes later devices report timeouts they never earned.
     *
     * Nothing is probed during a send. That is enforced twice, by `probePlan` refusing while busy
     * and by [send] cancelling this job, because the two cover different moments. What neither can
     * undo is an SDP transaction already inside the stack, and there is no public API to recall one.
     */
    private suspend fun sweep(force: Boolean) {
        probeJob?.cancelAndJoin()

        // An address the cancelled sweep left part way through would otherwise look as if it were
        // still running, and so never be probed again. Settled after the join, so nothing can set it
        // again behind us, and settled back to the verdict it was rechecking, never blanked.
        var restored = false
        probes.entries.forEach { e ->
            val p = e.value
            if (p is Probe.Probing) {
                e.setValue(p.previous ?: Probe.NotProbed)
                restored = true
            }
        }
        // No reorder, and none is needed: `livenessOf` reads through Probing to the verdict being
        // rechecked, so Probing(Silent) and Silent already rank the same. Only the wording moves.
        if (restored) reclassify()

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
     * Page one device again, on request.
     *
     * The sheet offers this on a row that did not answer, so a pilot who has just walked out and
     * switched the unit on does not have to page every navigator again to find out.
     */
    fun retry(device: PairedDevice) {
        viewModelScope.launch {
            refreshLock.withLock {
                probeJob?.cancelAndJoin()
                if (_state.value.busy) return@withLock
                probeJob = viewModelScope.launch { probeEach(listOf(device.address)) }
            }
        }
    }

    // BLUETOOTH_CONNECT is checked before the sweep is planned at all: `sweep` returns early
    // unless `permissionsGranted`. Lint cannot follow that, because the check reads a
    // StateFlow rather than calling checkSelfPermission on this line.
    @SuppressLint("MissingPermission")
    private suspend fun probeEach(plan: List<String>) {
        for (address in plan) {
            // A send that started during a sweep also cancels this job, and this covers the gap.
            if (_state.value.busy) break
            val device = _state.value.devices.find { it.address == address } ?: continue
            // Carry the verdict being rechecked, so the row keeps saying what it last knew.
            probes[address] = Probe.Probing(probes[address])
            // No reorder on either of these: a verdict landing during a sweep changes what a row says,
            // not where it sits. The first cannot move a row anyway, since Probing reads through
            // to the verdict it is rechecking, and the second deliberately does not.
            reclassify()
            probes[address] = prober.probe(device.device)
            reclassify()
        }
        // The radio has gone quiet, so the list may settle. Reached on normal completion only:
        // cancelling this job, by `sweep`, `retry` or `send`, throws out of `prober.probe` above
        // and unwinds past here, which is what we want, because each of those is about to start
        // something else. A `finally` would reorder in the middle of exactly the sweeps it should
        // not. The `busy` break above does reach this, and the selection is guarded on `busy`.
        reclassify(reorder = true)
    }

    /**
     * Show every bonded device, or only the Garmin ones.
     *
     * Persisted, because a pilot who turned it on to reach a navigator the filter got wrong should
     * not have to find the toggle again before the next flight. Nothing is reclassified: the
     * predicate is a property of the device, and this only decides whether the sheet acts on it.
     */
    fun setShowAllDevices(show: Boolean) {
        _state.update { it.copy(showAllDevices = show) }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { settings.showAllDevices = show }
        }
    }

    /**
     * Aim at a device because the pilot said so.
     *
     * This is the end of the app guessing. From here the selection stays where it was put, whatever
     * a later sweep learns. See [autoSelected].
     */
    fun select(device: PairedDevice) {
        autoSelected = false
        _state.update { it.copy(selected = device, outcome = null) }
    }

    /**
     * What the route box shows for one route: the route itself, what is wrong with it, and the
     * string that would go on the wire.
     *
     * The three are decided together because they are one answer. Rendering is not merely how a
     * route is displayed: it is where the selected navigator's identifier caps apply, so a route
     * that parses and will not render is a route that cannot be sent, and the box has to say so.
     */
    private data class Review(
        val parsed: ParsedRoute?,
        val error: String?,
        val preview: String?,
    )

    /**
     * Computed outside `_state.update`, whose body may run more than once: this renders a route
     * and words an error, and neither wants doing twice.
     */
    private fun review(attempt: Result<ParsedRoute>, device: Profile): Review {
        val route = attempt.getOrNull()
        val rendered = route?.let { runCatching { it.render(device) } }
        val failure = attempt.exceptionOrNull() ?: rendered?.exceptionOrNull()
        return Review(route, failure?.let { describe(it) }, rendered?.getOrNull())
    }

    /** Parse again on every keystroke so the preview and errors track what is typed. */
    fun setRoute(text: String) {
        // Typing replaces the file. Whatever is in the box is the route from here, so the bytes
        // kept for re-shortening no longer describe it and re-importing them on a model change
        // would quietly undo the edit.
        lastImport = null
        val reviewed = review(runCatching { RouteParser.parse(text) }, _state.value.device)
        _state.update {
            it.copy(
                routeText = text,
                parsed = reviewed.parsed,
                // An empty box is not a mistake, it is a box nobody has typed in yet.
                routeError = if (text.isBlank()) null else reviewed.error,
                preview = reviewed.preview,
                outcome = null,
            )
        }
    }

    /** Shared text, treated exactly like something typed. */
    fun acceptSharedText(text: String) = setRoute(text.trim())

    /**
     * The file the current route was imported from, kept so a change of model can shorten its
     * names again against the new caps.
     *
     * The bytes rather than the [Uri]: a `content://` handed over by a share carries a permission
     * grant that does not outlive the activity, so re-reading it later can fail where re-parsing
     * what was already read cannot. Bounded by [ROUTE_FILE_LIMIT], and a real route file is a few
     * kilobytes. Cleared by [setRoute], since a typed route is no longer the file's.
     */
    private var lastImport: Pair<String?, ByteArray>? = null

    /**
     * The import in flight, so a second one supersedes it rather than racing it.
     *
     * Both callers can be triggered faster than a parse completes: two taps on the model chips, or
     * a file picked while the last one is still being read. Without this the *slower* of the two
     * would land last and win, so a route could end up shortened for a model other than the one
     * the picker shows. Cancelling is the same shape [probeJob] already uses.
     */
    private var importJob: Job? = null

    /**
     * A route file picked, opened or shared: `.fpl`, `.gpx`, `.kml`, `.pln`, SkyDemon's own
     * `.flightplan`, or a Garmin `.gfp`.
     */
    fun importRouteFile(uri: Uri, displayName: String?) {
        importJob?.cancel()
        importJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = getApplication<Application>().contentResolver
                        .openInputStream(uri)?.use { it.readAtMost(ROUTE_FILE_LIMIT) }
                        ?: error(say(R.string.file_unopenable))
                    displayName to bytes
                }
            }
            result.onSuccess { (name, bytes) -> adopt(name, bytes) }
                .onFailure { e ->
                    _state.update { it.copy(routeError = describe(e)) }
                }
        }
    }

    /**
     * Import bytes already in hand, against the model currently selected.
     *
     * Shared by the first import and by a re-import after the model changes, so the two cannot
     * disagree about how a name is shortened.
     *
     * Suspends, and parses on [Dispatchers.IO]. That is not a detail: this is a DOM parse of a
     * file bounded only by [ROUTE_FILE_LIMIT], and both call sites run on the main dispatcher.
     */
    private suspend fun adopt(name: String?, bytes: ByteArray) {
        val device = _state.value.device
        val result = withContext(Dispatchers.IO) {
            runCatching { RouteImporter.import(name, bytes, device) }
        }
        val reviewed = review(result, device)
        result
            .onSuccess { route ->
                lastImport = name to bytes
                _state.update {
                    it.copy(
                        routeText = route.identifiers.joinToString(" "),
                        parsed = route,
                        // A file can import and still not render, when a name it stated is too
                        // long for this navigator, so the error comes from the review rather than
                        // being cleared on the strength of the import having succeeded.
                        routeError = reviewed.error,
                        preview = reviewed.preview,
                        outcome = null,
                    )
                }
            }
            .onFailure { e ->
                // [lastImport] is deliberately left alone. A failed import does not replace the
                // route in hand, so the file that route came from is still the file that route
                // came from, and forgetting it here would quietly stop a later model change from
                // shortening it again.
                _state.update { it.copy(routeError = describe(e)) }
            }
    }

    fun importCredential(uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = getApplication<Application>().contentResolver
                        // Bounded like the route picker, and for the same reason: this picker
                        // accepts */* so that a credential a file manager types as something
                        // else can still be chosen, which also means a mis-picked video reaches
                        // here. Unbounded that was an out of memory kill rather than a message.
                        .openInputStream(uri)?.use { it.readAtMost(CREDENTIAL_FILE_LIMIT) }
                        ?.decodeToString()
                        ?: error(say(R.string.file_unopenable))
                    // Stored here rather than on the way out: encrypting is a keystore round trip,
                    // and it belongs on the same dispatcher as the read that preceded it.
                    credentials.save(CredentialStore.parse(text))
                }
            }
            result.onSuccess {
                _state.update { s -> s.copy(credentialPresent = true, outcome = null) }
            }.onFailure { e ->
                _state.update {
                    it.copy(
                        outcome = Outcome.Failure(
                            e.message ?: say(R.string.credential_invalid), null
                        )
                    )
                }
            }
        }
    }

    fun forgetCredential() {
        credentials.clear()
        _state.update { it.copy(credentialPresent = false) }
    }

    fun clearLog() = _state.update { it.copy(log = emptyList()) }

    /**
     * Add a line to the log on screen, and mirror it to logcat.
     *
     * [logcat] exists for the one line that must not be mirrored whole: the route string is the
     * pilot's flight plan, and logcat is read by a bug report and by `dumpsys`. The log on screen
     * keeps it, because that is a surface the pilot copies deliberately and it never leaves the
     * phone unless they send it.
     *
     * The device address stays in both. It is what tells two identically named navigators apart
     * in a report, and it is already on the screen the report would be written from.
     */
    /** A string resource, for the messages this class produces rather than a Composable. */
    private fun say(id: Int, vararg args: Any): String =
        getApplication<Application>().getString(id, *args)

    /**
     * What to show for a refusal, in the pilot's language where there is one to show.
     *
     * A [Problem] is a refusal the pilot is expected to act on, and it is worded from resources.
     * Everything else falls back to the message the throw site wrote, which is English and is
     * either a diagnostic about a malformed file or something from the protocol layers.
     * [FileTooLargeException] is worded here because it knows the bound and not the language.
     */
    private fun describe(e: Throwable): String {
        e.problem?.let { return getApplication<Application>().say(it) }
        return when (e) {
            is FileTooLargeException -> say(R.string.file_too_big, humanLimit(e.limit))
            else -> e.message ?: say(R.string.file_unreadable)
        }
    }

    private fun log(line: String, logcat: String = line) {
        Trace.line(logcat)
        _state.update { it.copy(log = it.log + line) }
    }

    /**
     * Choose the navigator model whose parser limits apply.
     *
     * An imported route is imported *again* rather than merely re-rendered, because shortening
     * happens at import: the names were cut to the previous model's cap and re-rendering would
     * keep them. Every model in the table reads 5 and 4 today, so this changes nothing yet and
     * is wrong the day one does not, which is the whole reason the caps live on a profile.
     */
    fun selectProfile(name: String) {
        val p = runCatching { Profiles.named(name) }.getOrNull() ?: return
        _state.update { it.copy(device = p) }
        val file = lastImport
        if (file == null) {
            // Nothing to shorten again, so only the rendering can change. It still can: the new
            // model may read fewer characters than the last one, which turns a route that was
            // fine into one that will not encode, and the box has to say so rather than losing
            // its preview in silence.
            val route = _state.value.parsed ?: return
            val reviewed = review(Result.success(route), p)
            _state.update { it.copy(routeError = reviewed.error, preview = reviewed.preview) }
            return
        }
        importJob?.cancel()
        importJob = viewModelScope.launch { adopt(file.first, file.second) }
    }

    /**
     * Cancel a transfer in flight.
     *
     * Every step has its own timeout and they add up to about a minute and a half, so a pilot who
     * has realised the navigator is off would otherwise wait the chain out.
     *
     * **Closing the link is what makes this immediate, not cancelling the job.** The transfer is
     * blocking code on a dispatcher: a coroutine cancellation is only observed at a suspension
     * point, and `BluetoothSocket.connect` and the reads behind it are neither cancellable nor
     * interruptible. Closing the socket from here is what makes them throw. The cancel is still
     * sent, because it is what stops the steps that would otherwise follow.
     */
    fun cancelSend() {
        cancelRequested = true
        runCatching { activeLink?.close() }
        sendJob?.cancel()
    }

    private var sendJob: Job? = null

    /** The link a transfer is holding, so [cancelSend] can close it from another thread. */
    @Volatile
    private var activeLink: ConnextLink? = null

    /**
     * Whether the pilot asked for this, so a cancelled transfer is not reported as a failure.
     *
     * Closing the socket usually wins the race against the coroutine cancellation, so the blocking
     * call throws an ordinary IOException and lands in the failure path with a message about the
     * RFCOMM service being unreachable. That is true and it is not the point: telling someone
     * their transfer failed when they cancelled it is how a real failure later gets ignored.
     */
    @Volatile
    private var cancelRequested = false

    /**
     * Open the link, waiting out a stack that has not finished tidying up the last attempt.
     *
     * **This is the other half of making Cancel work.** Closing the socket aborts the blocking
     * connect at once, but the abandoned attempt goes on unwinding inside the Bluetooth stack: the
     * SDP query it started has to time out before the native RFCOMM slot is released. Until then a
     * fresh connect is refused outright, which reaches the app as an ordinary IOException and read,
     * to a pilot, as "it worked a second ago and now it will not connect".
     *
     * Measured on a Redmi on Android 14, against a navigator that was switched off so the
     * abandoned query ran to its full timeout: a retry 0, 1000 or 2000 ms after a cancel failed in
     * 63 to 75 ms, and one at 3000 ms took 5192 ms. The stack logs
     * `find_rfc_slot_by_id unable to find RFCOMM slot id` on the refusals.
     *
     * So the two cases are told apart by *how long the failure took*, not by its message: a real
     * attempt costs an SDP round trip and a refusal costs nothing. That is what this waits out,
     * rather than a fixed cool-down after a cancel, because the window depends on how long the
     * abandoned query takes to expire, and against a navigator that is actually there it should be
     * far shorter than the worst case measured above.
     *
     * [delay] is a suspension, so a Cancel during the wait still takes effect immediately.
     */
    // The same gate as [send], which is this function's only caller: `UiState.canSend` requires
    // `permissionsGranted`, and it is what enables the button behind it.
    @SuppressLint("MissingPermission")
    private suspend fun connectWaitingOutARefusal(device: BluetoothDevice): ConnextLink {
        val deadline = System.nanoTime() + REFUSAL_BUDGET_MILLIS * 1_000_000L
        var waited = false
        while (true) {
            val started = System.nanoTime()
            try {
                return ConnextLink.connect(device, { devices.cancelDiscovery() }) { activeLink = it }
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

    /**
     * The whole transfer: connect, authenticate, negotiate, upload.
     *
     * Every step and every packet is logged. On a protocol recovered by reverse engineering the
     * useful output of a failure is *where* it stopped, not that it stopped.
     */
    // Same gate as [probeEach], one level up: `UiState.canSend` requires `permissionsGranted`,
    // and it is what enables the button this is behind.
    @SuppressLint("MissingPermission")
    fun send() {
        val s = _state.value
        val device = s.selected ?: return
        val profile = s.device
        val route = s.parsed ?: return
        val credential = credentials.load() ?: run {
            _state.update {
                it.copy(outcome = Outcome.Failure(say(R.string.credential_missing), null))
            }
            return
        }

        cancelRequested = false
        _state.update { it.copy(busy = true, outcome = null, log = emptyList(), step = Step.CONNECTING) }

        // Do not page the radio while a transfer is being set up. `probePlan` already refuses to
        // plan one while busy, and this stops a sweep that was already running.
        probeJob?.cancel()

        sendJob = viewModelScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    runCatching {
                        log("connecting to ${device.name} (${device.address})")
                        // Published to [activeLink] *before* the socket is connected, which is the
                        // whole point of the callback: opening an RFCOMM socket blocks in the
                        // platform for as long as it takes, a coroutine cancellation does not
                        // touch it, and closing the socket is the only thing that will. Set after
                        // the connect, as it was, Cancel did nothing during the one step it was
                        // most wanted for.
                        val link = connectWaitingOutARefusal(device.device)
                        // One exit, whatever happens after the socket is open, so a link can never
                        // be left behind. That is not tidiness: the navigator allows one client per
                        // channel, so a leaked link locks out the next attempt.
                        try {
                            // Cancelled between the socket connecting and this line, so nothing
                            // closed it. The finally below does.
                            if (!isActive) throw CancellationException("cancelled while connecting")
                            val client = ConnextClient(
                                link = link,
                                credential = credential,
                                onStep = { step -> _state.update { it.copy(step = step) } },
                                onEvent = ::logEvent,
                            )
                            client.connectAndAuthenticate()
                            val caps = client.negotiateCapabilities()
                            _state.update { it.copy(info = client.info) }
                            val text = route.render(profile, caps)
                            // The plan itself stays out of logcat. See [log].
                            log("route (${text.length} bytes): $text", "route (${text.length} bytes)")
                            client.upload(text, profile, caps)
                            text
                        } finally {
                            link.close()
                            activeLink = null
                        }
                    }
                }
            } catch (e: CancellationException) {
                // Reached because `withContext` throws on a cancelled parent, so nothing below
                // would run and the spinner would never stop.
                runCatching { activeLink?.close() }
                activeLink = null
                log("cancelled")
                _state.update { it.copy(busy = false, step = null, outcome = null) }
                throw e
            }
            result.onSuccess { text ->
                // A completed upload is the strongest possible answer to both questions the sheet
                // asks. It proves the device was reachable, and it is what tells two identically
                // named navigators apart afterwards.
                history.recordUpload(device.address)
                probes[device.address] = Probe.Live(nowMillis())
                _state.update {
                    it.copy(
                        busy = false,
                        step = Step.DONE,
                        outcome = Outcome.Success(text),
                        lastUsed = history.lastUsed(),
                    )
                }
                // Both keys this touched, the verdict and the upload history, are ordering
                // inputs, and `busy` is already false, so the list may settle around the navigator
                // that just accepted a flight plan.
                reclassify(reorder = true)
            }.onFailure { e ->
                // Cancelling closes the socket, which usually throws out of the blocking call
                // before the coroutine notices it has been cancelled, so this path is reached for
                // a transfer nobody wanted finished. See [cancelRequested].
                if (cancelRequested) {
                    log("cancelled")
                    _state.update { it.copy(busy = false, step = null, outcome = null) }
                    return@onFailure
                }
                val where = _state.value.step
                log("failed: ${e.message}")
                _state.update {
                    it.copy(
                        busy = false,
                        outcome = Outcome.Failure(describe(e), where),
                    )
                }
            }
        }
    }

    private fun logEvent(event: CxpEvent) {
        val line = when (event) {
            is CxpEvent.Sent -> "→ ${describe(event.packet)}"
            is CxpEvent.Received -> "← ${describe(event.packet)}"
            is CxpEvent.Resent -> "↻ ${describe(event.packet)} (retry ${event.attempt})"
            is CxpEvent.Note -> "  ${event.text}"
        }
        log(line)
    }

    private fun describe(p: Packet): String {
        val flags = buildList {
            if (p.ctrl and Ctrl.SYN != 0) add("SYN")
            if (p.ctrl and Ctrl.ACK != 0) add("ACK")
            if (p.ctrl and Ctrl.EAK != 0) add("EAK")
        }.ifEmpty { listOf("DATA") }.joinToString("|")
        val app = if (p.isData) {
            runCatching { AppFrame.decode(p.payload).value }
                .getOrNull()
                ?.let { " id=0x%08x type=0x%02x %dB".format(it.cxpId, it.type, it.payload.size) }
                ?: ""
        } else ""
        return "%s psn=%d ack=%d %dB%s".format(flags, p.psn, p.ack, p.payload.size, app)
    }
}

/**
 * The largest route file this app will read into memory.
 *
 * The platform's own `readBytes()` has no bound, so a mis-picked video was an out of memory
 * crash rather
 * than a message, and the picker deliberately filters nothing. Four mebibytes is far above
 * anything a route needs: the largest real sample here is a 29 kB KML, and the navigator refuses
 * a route string over 3520 bytes whatever the file around it looked like.
 */
internal const val ROUTE_FILE_LIMIT = 4 * 1024 * 1024

/**
 * The largest credential file this app will read.
 *
 * The picker has to accept any MIME type, because a file manager types a `.json` it does not
 * recognise as something else, so a mis-picked video reaches the same code path. A credential is a
 * few hundred bytes and this is three orders of magnitude above that.
 */
internal const val CREDENTIAL_FILE_LIMIT = 64 * 1024

/**
 * Below this, a failed connect never reached the radio.
 *
 * A real attempt costs an SDP round trip and takes seconds; a refused RFCOMM slot comes back in
 * tens of milliseconds. Measured refusals were 63 to 75 ms and a real attempt 5192 ms, so this
 * sits well clear of both. See `MainViewModel.connectWaitingOutARefusal`.
 */
private const val REFUSAL_MILLIS = 500L

/** How long to leave the stack alone between refusals. */
private const val SETTLE_MILLIS = 500L

/**
 * How long to keep waiting one out before reporting it.
 *
 * The worst case measured was between two and three seconds, against a navigator that was switched
 * off so the abandoned SDP query ran to its full timeout. This is that with room, and it only ever
 * elapses when every attempt inside it was refused without reaching the radio, so it costs nothing
 * on any path that was going to work.
 */
private const val REFUSAL_BUDGET_MILLIS = 8_000L

/**
 * A file the picker offered that is far larger than anything this reads.
 *
 * Carries the bound rather than a sentence, so the message can be worded in the pilot's language
 * where the resources are. [humanLimit] is the wording of the number alone, which no translation
 * changes.
 */
class FileTooLargeException(val limit: Int) : IllegalArgumentException(
    "that file is bigger than ${humanLimit(limit)}, which is far more than this needs. " +
        "It is probably not the file you meant to pick"
)

/** A byte count as a pilot would read it, so a limit of 64 KiB does not print as "0 MB". */
internal fun humanLimit(bytes: Int): String =
    if (bytes >= 1024 * 1024) "${bytes / (1024 * 1024)} MB" else "${bytes / 1024} kB"

/**
 * Read at most [limit] bytes, refusing a longer file rather than truncating it.
 *
 * Truncating would hand the importer a half XML document, and what comes back from that is an
 * error about a tag rather than about the file being the wrong thing entirely.
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
