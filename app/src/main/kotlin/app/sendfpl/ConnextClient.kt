package app.sendfpl

import app.sendfpl.cxp.Profile
import app.sendfpl.cxp.AppFrame
import app.sendfpl.cxp.AuthId
import app.sendfpl.cxp.AuthResponse
import app.sendfpl.cxp.CxpMode
import app.sendfpl.cxp.PRIORITY_CONTROL
import app.sendfpl.cxp.PRIORITY_DEFAULT
import app.sendfpl.cxp.PRIORITY_FPL_TRANSFER
import app.sendfpl.cxp.ID_LIST_CXP_ID
import app.sendfpl.cxp.ID_MARKER
import app.sendfpl.cxp.ControlCmd
import app.sendfpl.cxp.Opcode
import app.sendfpl.cxp.controlFrame
import app.sendfpl.cxp.decodeControl
import app.sendfpl.cxp.decodeIdList
import app.sendfpl.cxp.idListFrame
import app.sendfpl.cxp.setMode
import app.sendfpl.cxp.Credential
import app.sendfpl.cxp.CxpEvent
import app.sendfpl.cxp.FplId
import app.sendfpl.cxp.FrameType
import app.sendfpl.cxp.GPS175_PARAMS
import app.sendfpl.cxp.Link
import app.sendfpl.cxp.Session
import app.sendfpl.cxp.SessionException
import app.sendfpl.cxp.SupportedElements
import app.sendfpl.cxp.SynParams
import app.sendfpl.cxp.buildUser
import app.sendfpl.cxp.buildResponse
import app.sendfpl.cxp.checkConfirm
import app.sendfpl.cxp.encodeUpload
import app.sendfpl.cxp.segment

/** Where a transfer has got to, as named steps rather than a bare spinner. */
enum class Step { CONNECTING, HANDSHAKING, AUTHENTICATING, NEGOTIATING, UPLOADING, DONE }

/** What the navigator told us about itself. */
data class DeviceInfo(
    val negotiated: SynParams? = null,
    val capabilities: SupportedElements? = null,
)

/**
 * The auth messages the *navigator* sends, so the ones its outgoing table must be switched on for.
 *
 * `CXP_auth_server_pwrp` adds exactly these two, `cxp_app_fm_add(t, 4, 0)` and
 * `cxp_app_fm_add(t, 2, 0x5dc)` with the second carrying a 1500 ms retry period, both at mode
 * OFF. Ids 1 and 3
 * travel the other way and their entries live in *our* table, not its.
 *
 * Both are below the 0xFFF authorization threshold, which is what makes registering for them legal
 * before there is any authorization to check.
 */
val AUTH_IDS = listOf(AuthId.CHALLENGE, AuthId.CONFIRM)

/**
 * How many challenges to answer before giving up.
 *
 * The navigator challenges again every 1500 ms for as long as it is unsatisfied, so without a bound a
 * credential it does not hold would hang the transfer rather than report itself.
 */
const val AUTH_ATTEMPTS = 4

/**
 * How long an upload waits for the navigator to open the channel before pushing regardless.
 * Short, because it is a courtesy rather than a handshake.
 */
const val CHANNEL_GRACE_MILLIS = 4_000L

/**
 * How long to wait for the navigator's verdict on an uploaded plan.
 *
 * A GPS 175's ABORT arrived 81 ms after the last segment, so this is generous by a wide margin.
 * It costs nothing when the answer comes, and the wait ends early either way.
 */
const val UPLOAD_VERDICT_MILLIS = 3_000L


/**
 * The one channel a flight plan goes up on.
 *
 * The navigator's CXP id table, `{ctx, ctx, cxp_id, read_handler, write_handler}` at stride 0x1c,
 * read out of the trainer's `IOP_E.dll` at `0x1005e864`, gives `0x10005001` a null *read* handler
 * and a live write handler, so it can only receive. `0x10005004` has a read handler and a **null
 * write handler**: the navigator cannot receive on it at all, and it appears in the navigator's
 * own notify list beside `0x10005002` and `0x10005010` because it is a *download* channel for this
 * device. So there is no second candidate to prefer between.
 */
const val UPLOAD_ID = FplId.UPLOAD_TO_AVIONICS

/**
 * `CXP_ID_PRODUCT_DATA`, confirmed as 4096 by Garmin Pilot's own `CxpIdType`. Its payload layout is
 * still underived, which is what blocks working out the navigator model on its own.
 */
const val ID_PRODUCT_DATA = 0x00001000L

/** `CXP_ID_NOTIFICATION`, the sixth member of the flight plan support set. */
const val ID_NOTIFICATION = 0x1000a000L

/**
 * The ids the client registers for, from `ConnextFlightPlanControl.msgSupportSet`.
 * Registering is what makes the navigator publish, and nothing arrives unasked.
 */
val FLIGHT_PLAN_IDS = listOf(
    FplId.SUPPORTED_ELEMENTS,
    FplId.UPLOAD_TO_AVIONICS,
    FplId.MINIMAL_TRANSFER_TO_SIMPLE_AVIONICS,
    FplId.USER_WAYPOINT_LIST,
    FplId.DIRECT_TO_TRANSFER_TO_SIMPLE_AVIONICS,
    ID_NOTIFICATION,
)

/** Every id this client will ever send, announced on [ID_LIST_CXP_ID] before any of it is sent. */
val SENT_IDS = listOf(ID_MARKER, AuthId.USER, AuthId.RESPONSE, ID_PRODUCT_DATA) + FLIGHT_PLAN_IDS

/**
 * The id list sent *before* authentication: the control channel, our peer marker, and the two auth
 * messages we send. Nothing above `0xFFF`.
 *
 * Announcing an application id here is what kept `0x10005001` shut for ten runs. The navigator
 * creates a receive entry through `cxp_app_fm_add`, which stamps it by calling
 * `cxp_auth_id_is_authorized` **at creation time**. Before AUTH_CONFIRM an id above `0xFFF` does
 * not pass, so the entry is created with flag `0x80`. The loop at `0xd8417288` that walks the
 * receive entries again to open them iterates with `FUN_d841841c`, which *skips* entries carrying
 * `0x80`, so the entry is invisible to the walk. `cxp_app_fm_update_auth` clears the flag after
 * CONFIRM, but nothing walks again on the strength of that, and the channel stays at mode OFF for
 * the rest of the session.
 *
 * Garmin Pilot, captured against this same navigator, announces exactly these four and sends its
 * full list of 95 ids only after CONFIRM, at which point the navigator answers with
 * `SET_MODE 0x10005001 mode=PRDC prio=10`, which no run of this client had ever seen.
 */
val AUTH_ONLY_IDS = listOf(ID_LIST_CXP_ID, ID_MARKER, AuthId.RESPONSE, AuthId.USER)

/**
 * Drives one CXP session: SYN, the auth handshake of four messages, capability negotiation, upload.
 *
 * Blocking, so call it from a background dispatcher. Every step is reported through [onStep] and
 * every packet through [onEvent], because on a protocol recovered by reverse engineering the
 * useful output of a failure is *where* it stopped.
 */
class ConnextClient(
    link: Link,
    private val credential: Credential,
    params: SynParams = GPS175_PARAMS,
    private val onStep: (Step) -> Unit = {},
    private val onEvent: (CxpEvent) -> Unit = {},
) {
    private val session = Session(link, params, onEvent)

    var info = DeviceInfo()
        private set

    /** Send one application message, segmented so a frame fits a transport payload. */
    private fun sendMessage(cxpId: Long, payload: ByteArray = ByteArray(0)) {
        // A transport packet costs 8 header bytes plus a payload checksum, and the app frame
        // spends 8 more on its own header.
        val room = (session.negotiated?.maxSz ?: 1024) - 17
        for (frame in segment(cxpId, payload, room.coerceAtLeast(1))) {
            session.send(frame.encode())
        }
    }

    /**
     * Frames already decoded out of a transport payload that carried more than one.
     *
     * One transport payload can hold *several* application frames, and decoding only the first
     * silently discards the rest. A real GPS 175 does exactly this: it answered our registration
     * with a payload of 39 bytes holding a control frame and an AUTH_CHALLENGE back to back, and the
     * challenge went in the bin. The symptom was not an error but a 1.5 s stall, until the
     * navigator's periodic retry sent another one.
     */
    private val pending = ArrayDeque<AppFrame>()

    /** The next application frame, reading another payload only once the last is drained. */
    private fun nextFrame(timeoutMillis: Long): AppFrame {
        while (pending.isEmpty()) {
            var payload = session.receive(timeoutMillis)
                ?: throw SessionException("timed out waiting for a reply from the navigator")
            while (payload.isNotEmpty()) {
                val (frame, used) = AppFrame.decode(payload)
                pending.addLast(frame)
                payload = payload.copyOfRange(used, payload.size)
            }
        }
        return pending.removeFirst()
    }

    /**
     * The last mode the navigator declared for each id, and the ids it has asked us to send.
     *
     * Read out of the control frames it sends, which are commands rather than commentary: until one
     * of these says so, a channel we push to is shut.
     */
    private val peerMode = mutableMapOf<Long, ControlCmd>()

    /**
     * What the navigator advertised on [ID_LIST_CXP_ID]: every id it intends to send. Empty until
     * its list arrives.
     */
    var peerIds: List<Long> = emptyList()
        private set

    /** Wait for the navigator's id list. */
    private fun awaitPeerIds(timeoutMillis: Long) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (peerIds.isEmpty()) {
            if (System.nanoTime() >= deadline) throw SessionException("no id list from the navigator")
            stash(runCatching { receiveMessage(200) }.getOrNull())
        }
    }
    private val peerWants = mutableSetOf<Long>()

    /** Ids the navigator has refused (control opcode 4) and completed (opcode 7). */
    private val peerAborted = mutableSetOf<Long>()
    private val peerCompleted = mutableSetOf<Long>()

    /**
     * Messages that arrived while something else was being waited for.
     *
     * The waits in this class drive the control channel by reading whatever turns up, and a
     * navigator publishes on its own schedule: SUPPORTED_ELEMENTS arrived unasked, in the same
     * transport packet as a reply carrying an id list, on the first hardware session. Dropping it there and
     * then blocking for it in [negotiateCapabilities] would deadlock over a message already
     * received, so a wait parks what it is not looking for instead of discarding it.
     */
    private val stashed = ArrayDeque<AppFrame>()

    /** Park a frame a wait was not looking for. */
    private fun stash(frame: AppFrame?) { if (frame != null) stashed.addLast(frame) }

    /** Take a parked frame for [cxpId], if one arrived early. */
    private fun takeStashed(cxpId: Long): AppFrame? {
        val i = stashed.indexOfFirst { it.cxpId == cxpId }
        if (i < 0) return null
        val frame = stashed.elementAt(i)
        stashed.remove(frame)
        return frame
    }

    /**
     * Render a control body as commands rather than hex.
     *
     * The single most useful line in a failed transfer is whether the navigator ever opened the
     * upload channel, which reads `SET_MODE 0x10005001 mode=PRDC`. Leaving that as seven hex bytes meant
     * decoding it by hand afterwards, twice.
     */
    private fun describeControl(body: ByteArray): String {
        val cmds = runCatching { decodeControl(body) }.getOrNull()
            ?: return "unparsed ${body.size}B: " + body.joinToString(" ") { "%02x".format(it) }
        return cmds.joinToString(", ") { c ->
            val op = when (c.op) {
                Opcode.SET_MODE -> "SET_MODE"
                Opcode.REQUEST -> "REQUEST"
                Opcode.DATA -> "NEW_DATA"
                Opcode.CANCEL -> "CANCEL"
                Opcode.ABORT -> "ABORT"
                Opcode.HOLD -> "HOLD"
                Opcode.METADATA -> "METADATA"
                Opcode.COMPLETE -> "COMPLETE"
                else -> "op${c.op}"
            }
            val mode = when (c.mode) {
                CxpMode.OFF -> "OFF"
                CxpMode.PRDC -> "PRDC"
                CxpMode.RQST -> "RQST"
                else -> "mode${c.mode}"
            }
            if (c.op == Opcode.SET_MODE) {
                "%s 0x%08x mode=%s prio=%d".format(op, c.cxpId, mode, c.priority)
            } else {
                "%s 0x%08x".format(op, c.cxpId)
            }
        }
    }

    /**
     * Record what the navigator's control commands say about the channels we care about.
     *
     * A malformed body is ignored rather than fatal: the control channel is advisory to us, and
     * refusing to continue over a command we cannot parse would turn an unknown opcode into a
     * failed transfer.
     */
    private fun absorbControl(frame: AppFrame) {
        val cmds = runCatching { decodeControl(frame.payload) }.getOrNull() ?: return
        for (cmd in cmds) when (cmd.op) {
            Opcode.SET_MODE -> peerMode[cmd.cxpId] = cmd
            Opcode.REQUEST -> peerWants.add(cmd.cxpId)
            Opcode.ABORT -> peerAborted.add(cmd.cxpId)
            Opcode.COMPLETE -> peerCompleted.add(cmd.cxpId)
        }
    }

    /**
     * Tell the navigator we have data ready for an id.
     *
     * This is `CXP_app_notify`, which the reference implementation's applications call instead of
     * writing anything: it sets the "has data" flag and the pump decides what to do with it. Across
     * the link it is control opcode 2, which the receiver turns into
     * `cxp_app_fm_new_data(receive_table, id)`, setting the flag its own pump reports to the application
     * owning that id, and the only way that application learns something is coming.
     */
    fun notify(cxpId: Long) {
        session.send(controlFrame(listOf(ControlCmd(Opcode.DATA, cxpId))).encode())
    }

    /**
     * Wait until the navigator has opened [cxpId], either by declaring a live mode for it or by
     * asking for it outright.
     *
     * Pushing at a channel it has not opened does not fail quietly: a GPS 175 answered an
     * unannounced upload of 0x10005001 with control opcode 4, ABORT, naming the id.
     */
    private fun awaitChannel(cxpId: Long, timeoutMillis: Long) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (true) {
            if (cxpId in peerWants) return
            peerMode[cxpId]?.let { if (it.mode != CxpMode.OFF) return }
            if (System.nanoTime() >= deadline) {
                throw SessionException(
                    "the navigator did not open 0x%08x for us".format(cxpId)
                )
            }
            // receiveMessage drives the control channel, and anything else that turns up is parked
            // for whoever is waiting for it rather than dropped.
            stash(runCatching { receiveMessage(200) }.getOrNull())
        }
    }

    /** Wait for one complete application message, reassembling segments. */
    private fun receiveMessage(timeoutMillis: Long = 10_000): AppFrame {
        val parts = mutableListOf<AppFrame>()
        while (true) {
            val frame = nextFrame(timeoutMillis)
            // A control frame arrives outside the message stream: id 0, no END bit, no relation
            // to the message being assembled. Seven bytes is uniquely a SET_MODE command, laid out
            // as `u8 0 | u32 cxp_id | u8 mode | u8 priority`, which needs no reply. Accumulating it
            // would hang this loop, since it never sets END, and prepend its body to whatever
            // message arrives next. A real GPS 175 sends one immediately after AUTH_USER, which is
            // how this was found.
            if (frame.type and FrameType.CONTROL != 0 && frame.cxpId == 0L) {
                onEvent(CxpEvent.Note("control: " + describeControl(frame.payload)))
                absorbControl(frame)
                continue
            }
            parts.add(frame)
            if (frame.isLast) {
                val body = ByteArray(parts.sumOf { it.payload.size })
                var at = 0
                parts.forEach {
                    it.payload.copyInto(body, at)
                    at += it.payload.size
                }
                parts.clear()
                // The navigator's own id list is bookkeeping, not a reply anyone waits for.
                if (frame.cxpId == ID_LIST_CXP_ID) {
                    val ids = runCatching { decodeIdList(body) }.getOrNull()
                    if (ids != null) {
                        peerIds = ids
                        onEvent(CxpEvent.Note(
                            "navigator advertises ${ids.size} ids: " +
                                ids.joinToString(" ") { id -> "0x%08x".format(id) }
                        ))
                    }
                    continue
                }
                return AppFrame(frame.cxpId, body, frame.type)
            }
        }
    }

    /** SYN, then USER to CHALLENGE to RESPONSE to CONFIRM. */
    fun connectAndAuthenticate() {
        onStep(Step.HANDSHAKING)
        val negotiated = session.connect()
        info = info.copy(negotiated = negotiated)

        onStep(Step.AUTHENTICATING)
        // Subscribe to the two auth messages the navigator sends *before* asking it anything.
        // Not a precaution: it cannot answer without this. `CXP_auth_server_pwrp` creates its
        // outgoing entries for CHALLENGE and CONFIRM through `cxp_app_fm_add`, which sets
        // `entry[1] = 0`, mode OFF. `prdc_action_lcl` enqueues an auth message only where
        // `entry[1] == 1`. Left at OFF, the navigator generates a challenge it never sends.
        // Observed exactly that against a real GPS 175: AUTH_USER acked, one control frame back,
        // then silence.
        register(AUTH_IDS, CxpMode.PRDC, PRIORITY_DEFAULT)
        // Ask for the navigator's own id list. `CXP_app_new` sets its receive table's id 0 to
        // exactly (PRDC, CXP_PRIORITY_CONTROL), which is how the request reaches a peer. The
        // navigator sends us that command on connect, and we had never sent it back. Garmin Pilot
        // registers nothing and asks for nothing until the resulting CXP_ID_LIST arrives.
        register(listOf(ID_LIST_CXP_ID), CxpMode.PRDC, PRIORITY_CONTROL)

        // Declare the *auth* ids, and only those. The navigator adds each id in this list to its
        // receive table. An id absent from it has no entry, and `cxp_file_write` returns without
        // reading the message. Observed: AUTH_USER and AUTH_RESPONSE acknowledged and never
        // processed, because ids 1 and 3 had never been announced.
        //
        // The application ids deliberately wait until CONFIRM. See [AUTH_ONLY_IDS] for why
        // announcing them here is what kept the upload channel shut.
        sendIdList(AUTH_ONLY_IDS)

        sendMessage(AuthId.USER, buildUser(credential))

        // Answer challenges until the navigator confirms.
        //
        // A repeated challenge is not an error. `cxp_app_fm_add(t, 2, 0x5dc)` gives AUTH_CHALLENGE
        // a 1500 ms period and `cxp_auth_open` generates *fresh* random bytes on every send, so the
        // navigator challenges again on that beat until a response verifies. Measured at 1.56 s and
        // 1.58 s apart on a GPS 175, whose second challenge this client used to treat as fatal.
        //
        // The response must answer the *newest* challenge, because the navigator compares against
        // the one it sent last.
        var response: AuthResponse? = null
        var attempt = 0
        while (true) {
            val frame = receiveMessage()
            when (frame.cxpId) {
                AuthId.CHALLENGE -> {
                    if (++attempt > AUTH_ATTEMPTS) {
                        throw SessionException(
                            "the navigator issued $AUTH_ATTEMPTS challenges without accepting a " +
                                "response. The credential is not one it holds"
                        )
                    }
                    val r = buildResponse(credential.userId, credential.token, frame.payload)
                    response = r
                    sendMessage(AuthId.RESPONSE, r.encode())
                }

                AuthId.CONFIRM -> {
                    val r = response
                        ?: throw SessionException("AUTH_CONFIRM arrived before any challenge")
                    if (!checkConfirm(credential.token, r.nonce, frame.payload)) {
                        throw SessionException(
                            "the navigator's AUTH_CONFIRM did not verify. It does not hold this token"
                        )
                    }
                    break
                }

                else -> throw SessionException(
                    "expected AUTH_CHALLENGE or AUTH_CONFIRM, got id 0x%08x".format(frame.cxpId)
                )
            }
        }

        // Wait for the navigator's id list before registering anything else, which is the order
        // Garmin Pilot keeps: everything it does after auth is inside
        // `if (type == CxpIdType.CXP_ID_LIST)`. Best effort, because the ordering is read from the
        // reference client, not observed, so a navigator that sends none must not cost us the
        // transfer.
        runCatching { awaitPeerIds(10_000) }
            .onFailure { onEvent(CxpEvent.Note("no id list from the navigator")) }

        // Ask for the ids we intend to *receive*. Not the set we announce: Garmin Pilot's
        // REQUIRED_REG_SET omits UPLOAD_TO_AVIONICS precisely because registration governs what
        // the peer sends.
        register()

        // Now, and not before, announce everything we may send. This is the step that opens the
        // upload channel: the navigator walks its receive entries again to open them, over the
        // entries this list creates, and with authorization already settled they are visible to it.
        //
        // No PRODUCT_DATA *message* goes with it. `0x00001000` is a channel the navigator
        // publishes on, not one it reads: pushing an empty request at it was answered with
        // `ABORT 0x00001000`, and Garmin Pilot only ever registers to receive it.
        sendIdList()
    }

    /**
     * Tell the navigator which CXP ids we want, and in what mode.
     *
     * `ConnextDevice.registerForMessageType(cxpId, mode, priority)`, as one SET_MODE command per id
     * in a single control frame. **The navigator publishes nothing until it is asked**, so leaving
     * this out makes every later read time out. Garmin Pilot registers its whole required set with
     * `CXP_MODE_PRDC` before requesting anything, which is what this mirrors.
     */
    fun register(
        ids: List<Long> = FLIGHT_PLAN_IDS,
        mode: Int = CxpMode.PRDC,
        priority: Int = PRIORITY_FPL_TRANSFER,
    ) {
        session.send(controlFrame(ids.map { setMode(it, mode, priority) }).encode())
    }

    /**
     * Declare which CXP ids this client will send.
     *
     * Auth first, because the list must be believed before AUTH_USER goes out. The upload id is
     * listed even though it is used only after authorization: an id above 0xFFF enters the
     * navigator's table with `cxp_auth_id_is_authorized` already consulted, and
     * `cxp_app_fm_update_auth` clears the unauthorized flag across the table once CONFIRM lands.
     */
    fun sendIdList(ids: List<Long> = SENT_IDS) {
        session.send(idListFrame(ids).encode())
    }

    /** Read the navigator's advertised flight plan capabilities. */
    fun negotiateCapabilities(): SupportedElements {
        onStep(Step.NEGOTIATING)
        // No request is sent. A client can only receive on 0x10005000, and the navigator's handler
        // reads 7 bytes from an incoming one, so an empty request is dropped in silence. That
        // struct arrives because register() asked for the id.
        val frame = takeStashed(FplId.SUPPORTED_ELEMENTS) ?: receiveMessage()
        if (frame.cxpId != FplId.SUPPORTED_ELEMENTS) {
            throw SessionException(
                "expected SUPPORTED_ELEMENTS, got id 0x%08x".format(frame.cxpId)
            )
        }
        val caps = SupportedElements.decode(frame.payload)
        info = info.copy(capabilities = caps)
        return caps
    }

    /**
     * Upload an ARINC 702A route string, and wait for the navigator to acknowledge it.
     *
     * The wait is not politeness. Nothing else waits for the last packet of a transfer, since
     * [Session.send] returns once a packet is written and the window is consulted before the
     * *next* one, so an
     * upload followed immediately by [close] is thrown at a socket that is then shut. That is what
     * happened on a GPS 175: the plan went out, the socket closed microseconds later, and the
     * navigator never acknowledged it.
     */
    fun upload(
        route: String,
        device: Profile,
        capabilities: SupportedElements?,
        timeoutMillis: Long = 10_000,
    ) {
        onStep(Step.UPLOADING)
        // Announce before pushing. The navigator's receive entry for this id was created by our id
        // list at mode OFF, because `CXP_id_list_write` switches on only ids 1..4, and `cxp_app_fm_in`
        // refuses a body while the mode is off. What opens it is the notification: opcode 2 sets
        // the flag that makes the navigator's pump tell its flight plan application something is
        // coming, and the application then opens the channel.
        //
        // Registering does not do this. A peer's SET_MODE configures the peer's *send* table, which
        // is the right direction for a message we receive and meaningless for one we send.
        val id = UPLOAD_ID
        // Pushing at a channel the navigator has not opened is answered with a decodable ABORT
        // rather than silence, so this goes ahead whatever the control channel has said. Refusing
        // to send would turn an unobserved handshake into a hard failure.
        //
        // The channel is normally already open by now: the id list sent after auth makes the
        // navigator walk its receive entries again to open them, and it answers with
        // `SET_MODE 0x10005001 mode=PRDC prio=10`.
        // That is what a captured Garmin Pilot session shows, and it sends no notification at all.
        //
        // The notification is kept only as a fallback. Control opcode 2 was once believed to be
        // the *only* thing that runs the walk again. A working session contains none, so it
        // is one trigger among others rather than the mechanism. Sending it costs one packet and
        // may still help a navigator that has not published on its own.
        if (!runCatching { awaitChannel(id, CHANNEL_GRACE_MILLIS) }.isSuccess) {
            onEvent(CxpEvent.Note("channel still shut, notifying and waiting again"))
            notify(id)
            runCatching { awaitChannel(id, CHANNEL_GRACE_MILLIS) }
                .onFailure { onEvent(CxpEvent.Note("channel not opened, sending anyway")) }
        }
        sendMessage(id, encodeUpload(route, device, capabilities))
        session.flush(timeoutMillis)
        awaitUploadVerdict(id, UPLOAD_VERDICT_MILLIS)
        onStep(Step.DONE)
    }

    /**
     * Wait for what the navigator makes of the message we just pushed, and fail if it refuses it.
     *
     * Until this existed the client reported a refused upload as a delivered one. Nothing waited
     * for the *application's* answer, since [Session.flush] only establishes that the transport
     * acknowledged our bytes, so "Sent" was true of the link and said nothing about the plan. A
     * real GPS 175 answered two sessions' worth of uploads with ABORT while the app displayed
     * success, which is exactly the observation "it says it worked and nothing appears on screen".
     *
     * The refusal is generated inside the navigator: `cxp_app_fm_in_begin` requires the receive
     * entry's mode byte to be something other than zero, and when it is not it sets the `0x40` flag
     * and `cxp_file_write` calls `cxp_app_fm_complete(..., 3)`. That reaches us as control opcode
     * 4 naming the id, about 80 ms after the last segment on the one unit measured.
     *
     * Silence is treated as acceptance. That is a deliberate asymmetry rather than a proof: the
     * refusal path is read out of the firmware and observed on hardware, whereas no accepted
     * upload has ever been seen, so whether a success is acknowledged at all is **UNCONFIRMED**.
     */
    private fun awaitUploadVerdict(cxpId: Long, timeoutMillis: Long) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (cxpId in peerAborted) {
                throw SessionException(
                    ("the navigator refused the flight plan on 0x%08x with control opcode 4, " +
                        "ABORT. Its receive channel for that id is still off. On this navigator " +
                        "that is a stored setting: Bluetooth Settings > Features > " +
                        "\"Flight Plan Import\"")
                        .format(cxpId)
                )
            }
            if (cxpId in peerCompleted) {
                onEvent(CxpEvent.Note("the navigator completed 0x%08x".format(cxpId)))
                return
            }
            stash(runCatching { receiveMessage(200) }.getOrNull())
        }
        onEvent(CxpEvent.Note("no verdict from the navigator, which neither refused nor acknowledged the plan"))
    }

    fun close() = session.close()
}
