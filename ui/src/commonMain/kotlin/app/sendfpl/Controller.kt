package app.sendfpl

import app.sendfpl.cxp.AuthException
import app.sendfpl.cxp.Carrier
import app.sendfpl.cxp.Coordinate
import app.sendfpl.cxp.Credential
import app.sendfpl.cxp.CxpEvent
import app.sendfpl.cxp.Profile
import app.sendfpl.cxp.Profiles
import app.sendfpl.cxp.SessionException
import app.sendfpl.route.ParsedRoute
import app.sendfpl.route.RouteImporter
import app.sendfpl.route.RouteParser
import app.sendfpl.ui.resources.*
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The route and the transfer, as both apps hold them. */
data class RouteState(
    /** Null until the store has been read, so the setup card does not flash on every start. */
    val credentialPresent: Boolean? = null,
    /**
     * The navigator model whose parser limits apply: the picker's, until a navigator identifies
     * itself during a send and the send adopts the model it names. A route is previewed against it
     * before any navigator has been reached. Starts as the model the [Store] remembers, or the
     * GPS 175.
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
     * The route's half of whether Send is enabled. A platform adds its navigator's. Needs
     * [preview] rather than [parsed]: a name too long for the selected navigator parses but does
     * not render, and the box should say so before a session does.
     */
    val canSend: Boolean
        get() = !busy && !importing && credentialPresent == true && preview != null

    /**
     * Whether the last send went out under the picker's model, the navigator having named none this
     * app has limits for.
     */
    val usedPickerModel: Boolean
        get() = outcome is Outcome.Success && info.productData?.profile == null
}

/**
 * What an app keeps across launches for the [Controller]: the credential, and the model last
 * chosen or identified. Called on the controller's dispatcher for blocking work.
 */
interface Store {
    /** The credential a transfer authenticates with, or null. */
    fun loadCredential(): Credential?

    /** Parses [json] as a credential and stores it, or throws what the parse refused. */
    fun saveCredential(json: String)

    fun clearCredential()

    /**
     * The model last chosen or identified, by product id. 0, or an id outside
     * [Profiles.byProductId], leaves the picker on the GPS 175.
     */
    var profileProductId: Long
}

/**
 * One transfer's way to the navigator, as a platform opens it: an RFCOMM socket on Android, an
 * External Accessory session on an iPhone.
 */
interface Line {
    /** Opens the way, blocking until it is open or failed, and returns what a client speaks over. */
    suspend fun open(onEvent: (CxpEvent) -> Unit): Carrier

    /** Closes the way, open or still opening, from any thread: what makes Cancel immediate. */
    fun close()
}

/** The store holds nothing a transfer could authenticate with. */
class NoCredentialException : Exception("no credential imported")

/**
 * Whether Cancel can still call off a transfer at this step. From [Step.VERIFYING] on, the whole
 * plan has gone out, and a Cancel would only report a delivered plan as cancelled.
 */
internal val Step?.cancellable: Boolean get() = this != Step.VERIFYING && this != Step.DONE

/** The platform gave no bytes for a file that was picked, opened or shared. */
class UnopenableFileException : Exception("could not open the file")

/** Whether [e] is the way to the navigator failing, as a platform's link reports it. */
internal expect fun isLinkFailure(e: Throwable): Boolean

/**
 * The half of an app that is the same on every platform: the route in the box, a file imported,
 * the model whose limits apply, the credential's presence, and the transfer, each failure worded
 * for the pilot. A platform hands it a [Store], a [Line] for each transfer, [scope], whose
 * dispatcher runs the controller, and [io], where blocking work runs. A transfer updates [state]
 * from [io] too, and every change goes through `update`, so each is atomic.
 */
class Controller(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val store: Store,
    /** A line for the platform's own log, which leaves route content out. See [log]. */
    private val trace: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow(RouteState())
    val state: StateFlow<RouteState> = _state.asStateFlow()

    /**
     * Whether the model in [RouteState.device] has been settled during this launch, by the picker
     * or by a navigator identifying itself, so the remembered one arriving late does not override
     * it.
     */
    @Volatile
    private var profileSettled = false

    /**
     * Completed once [restoreProfile] has read the remembered model, whatever it found. [adopt]
     * waits for it, so a file opened with the app is imported for that model, not the default.
     */
    private val profileRestored = CompletableDeferred<Unit>()

    /**
     * The positions the imported file gave the names the route box shows bare. See
     * `ParsedRoute.placed`. Kept apart from the box so an edit keeps them, since they settle a
     * duplicated identifier. The next import replaces them, and an emptied box or shared text
     * clears them.
     */
    private var placed: Map<String, List<Coordinate>> = emptyMap()

    /**
     * The file the current route was imported from, so a change of model can shorten its names
     * again. The bytes rather than a platform's handle, whose grant may not outlive the screen.
     */
    private var lastImport: Pair<String?, ByteArray>? = null

    /**
     * The import in flight, so a second one supersedes it rather than racing it: otherwise the
     * slower would win, and a route could be shortened for a model the picker no longer shows.
     */
    private var importJob: Job? = null

    private var sendJob: Job? = null

    /** The line a transfer is holding, so [cancelSend] can close it from another thread. */
    @Volatile
    private var activeLine: Line? = null

    /**
     * Whether the pilot cancelled, so a transfer is not reported as failed when closing the line
     * throws an ordinary link failure before the coroutine notices the cancellation.
     */
    @Volatile
    private var cancelRequested = false

    init {
        restoreProfile()
    }

    /**
     * Start the picker on the model last chosen or identified, applied through [useProfile] like a
     * tap on the picker so a route already in the box is rendered again for it.
     */
    private fun restoreProfile() {
        scope.launch {
            try {
                val saved = withContext(io) { store.profileProductId }
                val p = Profiles.byProductId[saved] ?: return@launch
                if (profileSettled || p == _state.value.device) return@launch
                useProfile(p)
            } finally {
                profileRestored.complete(Unit)
            }
        }
    }

    /** What the platform read of the store: whether a credential is there. */
    fun credentialPresent(present: Boolean) {
        _state.update { it.copy(credentialPresent = present) }
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
     * A route file picked, opened or shared: `.fpl`, `.gpx`, `.kml`, `.pln`, SkyDemon's own
     * `.flightplan`, or a Garmin `.gfp`. [read] returns its name, which chooses the parser, and at
     * most [ROUTE_FILE_LIMIT] of its bytes, and runs where blocking work does.
     */
    fun importFile(read: () -> Pair<String?, ByteArray>) {
        launchImport {
            val result = withContext(io) { runCatching { read() } }
            result.onSuccess { (name, bytes) -> adopt(name, bytes) }
                .onFailure { e ->
                    _state.update { it.copy(routeError = describe(e)) }
                }
        }
    }

    /** Make [block] the import in flight, [RouteState.importing] set until the last one ends. */
    private fun launchImport(block: suspend () -> Unit) {
        importJob?.cancel()
        val job = scope.launch { block() }
        importJob = job
        _state.update { it.copy(importing = true) }
        job.invokeOnCompletion {
            if (importJob === job) _state.update { s -> s.copy(importing = false) }
        }
    }

    /**
     * Import bytes already in hand against the selected model, for a first import and a re-import
     * alike. Parses on [io]: a parse of up to [ROUTE_FILE_LIMIT] bytes.
     */
    private suspend fun adopt(name: String?, bytes: ByteArray) {
        profileRestored.await()
        val device = _state.value.device
        val result = withContext(io) {
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

    /**
     * A credential file picked: [read] returns its text, at most [CREDENTIAL_FILE_LIMIT] of it, and
     * runs where blocking work does, as storing it does.
     */
    fun importCredential(read: () -> String) {
        scope.launch {
            val result = withContext(io) { runCatching { store.saveCredential(read()) } }
            result.onSuccess {
                _state.update { s -> s.copy(credentialPresent = true, outcome = null) }
            }.onFailure { e ->
                // A refusal names the field at fault in the English of its throw site.
                val message = if (e is AuthException) {
                    say(Res.string.credential_invalid, e.message.orEmpty())
                } else {
                    describe(e)
                }
                _state.update { it.copy(outcome = Outcome.Failure(message, null)) }
            }
        }
    }

    fun forgetCredential() {
        scope.launch { withContext(io) { store.clearCredential() } }
        _state.update { it.copy(credentialPresent = false) }
    }

    fun clearLog() = _state.update { it.copy(log = emptyList()) }

    /** Clear the last outcome, which named a navigator the pilot has just chosen another over. */
    fun dismissOutcome() = _state.update { it.copy(outcome = null) }

    /**
     * Report a failure found before any transfer began, such as the radio found off at Send. The
     * platform words it.
     */
    fun fail(message: Wording) = _state.update { it.copy(outcome = Outcome.Failure(message, null)) }

    /** A string resource, for the messages this class produces rather than a Composable. */
    private fun say(res: StringResource, vararg args: Any) = Wording { stringResource(res, *args) }

    /** A throw site's English, or [fallback] where it wrote none. */
    private fun english(e: Throwable, fallback: StringResource): Wording =
        e.message?.let { message -> Wording { message } } ?: say(fallback)

    /**
     * What to show for a failure: a `Problem`, an oversized or unopenable file in the pilot's
     * language, anything else in the English its throw site wrote.
     */
    private fun describe(e: Throwable): Wording {
        e.problem?.let { problem -> return Wording { problem.text() } }
        return when (e) {
            is FileTooLargeException -> Wording {
                // Both units worded, so that humanLimit alone decides which one is shown.
                val megabytes = stringResource(Res.string.size_megabytes, e.limit / (1024 * 1024))
                val kilobytes = stringResource(Res.string.size_kilobytes, e.limit / 1024)
                stringResource(Res.string.file_too_big, humanLimit(e.limit, { megabytes }, { kilobytes }))
            }
            is UnopenableFileException -> say(Res.string.file_unopenable)
            else -> english(e, Res.string.file_unreadable)
        }
    }

    /**
     * What to show for a transfer that failed at [step]: the reasons a pilot can act on in their
     * language, and the protocol's own English for the rest, which the log carries in full anyway.
     */
    private fun describeTransfer(e: Throwable, step: Step?): Wording = when {
        e.problem != null -> describe(e)
        e is NoCredentialException -> say(Res.string.credential_missing)
        e is SessionException -> when (e.reason) {
            SessionException.Reason.UPLOAD_REFUSED -> say(Res.string.transfer_refused)
            SessionException.Reason.CREDENTIAL_REFUSED -> say(Res.string.transfer_credential_refused)
            SessionException.Reason.NO_REPLY -> say(Res.string.transfer_no_reply)
            null -> english(e, Res.string.transfer_failed)
        }
        isLinkFailure(e) && step == Step.CONNECTING -> say(Res.string.transfer_unreachable)
        isLinkFailure(e) -> say(Res.string.transfer_link_lost)
        else -> english(e, Res.string.transfer_failed)
    }

    /**
     * Add a line to the log on screen, and [traced] to the platform's. They differ where the line
     * holds route content: a bug report reads the platform's, the screen log leaves the phone only
     * if sent.
     */
    fun log(line: String, traced: String = line) {
        trace(traced)
        _state.update { it.copy(log = it.log + line) }
    }

    /**
     * Choose the navigator model whose parser limits apply. An imported route is imported again,
     * not just re-rendered, because names are shortened to the model's caps at import. Not during
     * a send, whose route is already built. It clears the last outcome, which can name the
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

    /** Remember [p] for the next launch. */
    private fun rememberProfile(p: Profile) {
        scope.launch {
            withContext(io) { store.profileProductId = p.productId }
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
     * Cancel a transfer in flight. Closing the line is what makes this immediate: the transfer
     * blocks in the platform's connect and in reads, which a coroutine cancellation does not
     * interrupt. Cancelling the job stops the steps that would follow.
     */
    fun cancelSend() {
        // The wait for a refusal ends on its own within the grace.
        if (!_state.value.step.cancellable) return
        cancelRequested = true
        activeLine?.close()
        sendJob?.cancel()
    }

    /**
     * Send the route in the box over [line] to the navigator [target] names in the log, and report
     * how it went. [onSent] runs on success, before the outcome is shown, for what a platform
     * records of a navigator that took a plan.
     */
    fun send(line: Line, target: String, onSent: () -> Unit = {}) {
        val s = _state.value
        if (s.busy) return
        val route = s.parsed ?: return

        cancelRequested = false
        activeLine = line
        _state.update {
            it.copy(busy = true, outcome = null, log = emptyList(), step = Step.CONNECTING, info = DeviceInfo())
        }

        sendJob = scope.launch {
            val result = try {
                withContext(io) { runCatching { transfer(line, target, route, s.device) } }
            } catch (e: CancellationException) {
                // `withContext` throws on a cancelled parent, so nothing below would run.
                activeLine?.close()
                activeLine = null
                log("cancelled")
                _state.update { it.copy(busy = false, step = null, outcome = null) }
                throw e
            }
            result.onSuccess {
                onSent()
                _state.update { it.copy(busy = false, step = Step.DONE, outcome = Outcome.Success) }
            }.onFailure { e ->
                if (cancelRequested) {
                    log("cancelled")
                    _state.update { it.copy(busy = false, step = null, outcome = null) }
                    return@onFailure
                }
                val where = _state.value.step
                // A refusal can quote the route, which the platform's log must not see. See [log].
                log("failed: ${e.message}", "failed: ${(e.problem ?: e)::class.simpleName}")
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
    private suspend fun transfer(line: Line, target: String, route: ParsedRoute, profile: Profile): String {
        val credential = store.loadCredential() ?: throw NoCredentialException()
        currentCoroutineContext().ensureActive()
        log("connecting to $target")
        // One exit: the navigator allows one client per channel, and a leaked line would hold it.
        try {
            // [line] is [activeLine] before it opens, so Cancel can close an open in progress.
            val carrier = line.open(::logEvent)
            if (!currentCoroutineContext().isActive) throw CancellationException("cancelled while connecting")
            val client = ConnextClient(
                carrier = carrier,
                credential = credential,
                onStep = { step -> _state.update { it.copy(step = step) } },
                onEvent = ::logEvent,
            )
            val sent = client.transfer(
                route,
                profile,
                onModel = { identified, effective ->
                    _state.update { it.copy(info = client.info) }
                    // Silence, or a model with no measured profile, keeps the picker's model.
                    if (identified != null) {
                        profileSettled = true
                        store.profileProductId = identified.productId
                    }
                    if (effective != profile) {
                        log("using the ${effective.name} limits, which the navigator asked for")
                        // The preview is what is sent, so it follows the model.
                        val reviewed = review(Result.success(route), effective)
                        _state.update {
                            it.copy(device = effective, routeError = reviewed.error, preview = reviewed.preview)
                        }
                    }
                },
                // The plan itself stays out of the platform's log. See [log].
                onRoute = { text -> log("route (${text.length} bytes): $text", "route (${text.length} bytes)") },
            )
            return sent.route
        } finally {
            line.close()
            activeLine = null
        }
    }

    private fun logEvent(event: CxpEvent) {
        log(
            when (event) {
                is CxpEvent.Sent -> "→ ${event.packet.describe()}"
                is CxpEvent.Received -> "← ${event.packet.describe()}"
                is CxpEvent.Resent -> "↻ ${event.packet.describe()} (retry ${event.attempt})"
                is CxpEvent.Note -> "  ${event.text}"
                is CxpEvent.Frame -> (if (event.sent) "→ " else "← ") + event.frame.describe()
            }
        )
    }
}
