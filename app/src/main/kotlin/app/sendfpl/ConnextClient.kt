package app.sendfpl

import app.sendfpl.cxp.AppException
import app.sendfpl.cxp.AppFrame
import app.sendfpl.cxp.AuthId
import app.sendfpl.cxp.ControlCmd
import app.sendfpl.cxp.Credential
import app.sendfpl.cxp.Ctrl
import app.sendfpl.cxp.CxpEvent
import app.sendfpl.cxp.CxpMode
import app.sendfpl.cxp.FplId
import app.sendfpl.cxp.FrameType
import app.sendfpl.cxp.GPS175_PARAMS
import app.sendfpl.cxp.ID_CONNECTED_LRU
import app.sendfpl.cxp.ID_LIST_CXP_ID
import app.sendfpl.cxp.ID_MARKER
import app.sendfpl.cxp.Link
import app.sendfpl.cxp.Opcode
import app.sendfpl.cxp.PRIORITY_CONTROL
import app.sendfpl.cxp.PRIORITY_DEFAULT
import app.sendfpl.cxp.PRIORITY_FPL_TRANSFER
import app.sendfpl.cxp.Packet
import app.sendfpl.cxp.ProductData
import app.sendfpl.cxp.Profile
import app.sendfpl.cxp.Session
import app.sendfpl.cxp.SessionException
import app.sendfpl.cxp.SupportedElements
import app.sendfpl.cxp.SynParams
import app.sendfpl.cxp.buildResponse
import app.sendfpl.cxp.buildUser
import app.sendfpl.cxp.checkConfirm
import app.sendfpl.cxp.controlFrame
import app.sendfpl.cxp.decodeControl
import app.sendfpl.cxp.decodeIdList
import app.sendfpl.cxp.decodeProductData
import app.sendfpl.cxp.encodeUpload
import app.sendfpl.cxp.idListFrame
import app.sendfpl.cxp.reassemble
import app.sendfpl.cxp.segment
import app.sendfpl.cxp.setMode
import java.util.Locale

/** [id] as the protocol log writes it, `0x10005001`, whatever the phone's language. */
private fun hex(id: Long): String = "0x%08x".format(Locale.ROOT, id)

/**
 * Where a transfer is. [VERIFYING] begins once the navigator has acknowledged the whole plan: it
 * holds it, and only a refusal can still come.
 */
enum class Step { CONNECTING, HANDSHAKING, AUTHENTICATING, NEGOTIATING, UPLOADING, VERIFYING, DONE }

/** What the navigator told us about itself. */
data class DeviceInfo(
    val negotiated: SynParams? = null,
    val capabilities: SupportedElements? = null,
    /** What the navigator said it is, on [app.sendfpl.cxp.ID_CONNECTED_LRU]. */
    val productData: ProductData? = null,
)

/**
 * The auth messages the navigator sends. `CXP_auth_server_pwrp` creates their entries at mode
 * OFF, `cxp_app_fm_add(t, 4, 0)` and `cxp_app_fm_add(t, 2, 0x5dc)`, and `prdc_action_lcl`
 * enqueues an auth message only where `entry[1] == 1`, so no challenge comes until we register
 * for them. Being below the 0xFFF threshold, they can be registered for before authorization.
 */
private val AUTH_IDS = listOf(AuthId.CHALLENGE, AuthId.CONFIRM)

/**
 * How many challenges to answer before giving up. The navigator challenges until a response
 * verifies, so a credential it does not hold would otherwise hang the transfer.
 */
private const val AUTH_ATTEMPTS = 4

/**
 * A bound on a message being reassembled. The largest a navigator sends is a flight plan of a few
 * kilobytes, so a message past this is malformed and would otherwise grow without end.
 */
private const val MAX_MESSAGE = 64 shl 10

/** How long an upload waits for the navigator to open the channel before pushing regardless. */
private const val CHANNEL_GRACE_MILLIS = 4_000L

/**
 * How long to wait for the navigator to refuse an uploaded plan. A GPS 175 refuses one about 80 ms
 * after its last segment.
 */
private const val UPLOAD_VERDICT_MILLIS = 3_000L

/**
 * How long a transfer waits to learn the navigator's model before building the route. A GPS 175
 * says it about 90 ms after its capabilities; one that never says keeps the picker's model.
 */
private const val PRODUCT_DATA_GRACE_MILLIS = 1_000L

/**
 * `CXP_ID_PRODUCT_DATA`, 4096 in Garmin Pilot's `CxpIdType`. Despite the name, a GPS 175 identifies
 * itself on [app.sendfpl.cxp.ID_CONNECTED_LRU], and no capture holds a frame on this id.
 */
private const val ID_PRODUCT_DATA = 0x00001000L

/** `CXP_ID_NOTIFICATION`, the sixth member of the flight plan support set. */
const val ID_NOTIFICATION = 0x1000a000L

/** The ids the client registers for, Garmin Pilot's `ConnextFlightPlanControl.msgSupportSet`. */
private val FLIGHT_PLAN_IDS = listOf(
    FplId.SUPPORTED_ELEMENTS,
    FplId.UPLOAD_TO_AVIONICS,
    FplId.MINIMAL_TRANSFER_TO_SIMPLE_AVIONICS,
    FplId.USER_WAYPOINT_LIST,
    FplId.DIRECT_TO_TRANSFER_TO_SIMPLE_AVIONICS,
    ID_NOTIFICATION,
)

/** The full id list, announced after CONFIRM: every id this client may send. */
private val SENT_IDS = listOf(ID_MARKER, AuthId.USER, AuthId.RESPONSE, ID_PRODUCT_DATA) + FLIGHT_PLAN_IDS

/**
 * The id list sent before authentication: the control channel, our peer marker and the two auth
 * messages we send, nothing above `0xFFF`. `cxp_app_fm_add` checks `cxp_auth_id_is_authorized`
 * when it creates an entry, so an id above `0xFFF` announced before CONFIRM gets flag `0x80`,
 * which the walk that opens receive entries (`0xd8417288`, through `FUN_d841841c`) skips;
 * `cxp_app_fm_update_auth` clears it later but walks nothing, so the id stays at mode OFF. Garmin
 * Pilot announces the same four, and its full list after CONFIRM.
 */
private val AUTH_ONLY_IDS = listOf(ID_LIST_CXP_ID, ID_MARKER, AuthId.RESPONSE, AuthId.USER)

/**
 * Drives one CXP session: SYN, authentication, capability negotiation, upload. Blocking, so call
 * it from a background dispatcher. Each step is reported through [onStep] and each packet through
 * [onEvent], so a failure shows where it stopped.
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

    /** How long [upload] waits for the navigator to refuse the plan. */
    internal var verdictGraceMillis = UPLOAD_VERDICT_MILLIS

    /** Send one application message, segmented so a frame fits a transport payload. */
    private fun sendMessage(cxpId: Long, payload: ByteArray = ByteArray(0)) {
        val negotiated = session.negotiated ?: throw SessionException("session not connected")
        // A transport packet costs 8 header bytes plus a payload checksum, and the app frame
        // spends 8 more on its own header.
        val room = negotiated.maxSz - 17
        for (frame in segment(cxpId, payload, room.coerceAtLeast(1))) {
            session.send(frame.encode())
        }
    }

    /**
     * Frames decoded and not yet read. One transport payload can carry several: a GPS 175 answers
     * our registration with a control frame and an AUTH_CHALLENGE in one.
     */
    private val pending = ArrayDeque<AppFrame>()

    /**
     * The next application frame, or null if none arrives within [timeoutMillis]. Bytes that are
     * not a frame are dropped rather than ending the session. The navigator's parser,
     * `FUN_d8418c6c`, would carry an incomplete frame over to the next payload; no sender splits one.
     */
    private fun nextFrame(timeoutMillis: Long): AppFrame? {
        while (pending.isEmpty()) {
            val payload = session.receive(timeoutMillis) ?: return null
            var at = 0
            while (at < payload.size) {
                val (frame, used) = try {
                    AppFrame.decode(payload, at)
                } catch (e: AppException) {
                    onEvent(CxpEvent.Note("dropped ${payload.size - at} bytes: ${e.message}"))
                    break
                }
                pending.addLast(frame)
                at += used
            }
        }
        return pending.removeFirst()
    }

    /** The last SET_MODE the navigator sent for each id. */
    private val peerMode = mutableMapOf<Long, ControlCmd>()

    /** The ids the navigator advertised on [ID_LIST_CXP_ID], empty until its list arrives. */
    var peerIds: List<Long> = emptyList()
        private set

    /** What the navigator said it is, on [ID_CONNECTED_LRU], or null until it says. */
    var productData: ProductData? = null
        private set

    /**
     * Wait up to [timeoutMillis] for the navigator to say which model it is, and return that, or
     * null. Nothing orders it against the capabilities, and a GPS 175 sends it after them, so a
     * route is built only after this: the models differ in what they are sent for a user waypoint.
     */
    fun awaitProductData(timeoutMillis: Long = PRODUCT_DATA_GRACE_MILLIS): ProductData? {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (productData == null) {
            val left = (deadline - System.nanoTime()) / 1_000_000L
            if (left <= 0) {
                onEvent(CxpEvent.Note("the navigator did not say which model it is"))
                return null
            }
            stash(receiveMessage(minOf(left, 200)))
        }
        return productData
    }

    /** Record the navigator's identification, noting a product id with no measured profile. */
    private fun adoptProductData(body: ByteArray) {
        val pd = runCatching { decodeProductData(body) }.getOrNull()
        if (pd == null) {
            onEvent(CxpEvent.Note("navigator sent 0x00001006, which did not decode"))
            return
        }
        productData = pd
        info = info.copy(productData = pd)
        onEvent(CxpEvent.Note("navigator identifies as $pd"))
        if (pd.profile == null) {
            onEvent(CxpEvent.Note("no measured parser profile for product id ${pd.productId}"))
        }
    }

    /** Wait for the navigator's id list, reporting whether it came. */
    private fun awaitPeerIds(timeoutMillis: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (peerIds.isEmpty()) {
            if (System.nanoTime() >= deadline) return false
            stash(receiveMessage(200))
        }
        return true
    }

    /** Ids the navigator has asked us to send (control opcode 1). */
    private val peerWants = mutableSetOf<Long>()

    /** Ids the navigator has refused (control opcode 4). */
    private val peerAborted = mutableSetOf<Long>()

    /** Messages parked for a later wait: SUPPORTED_ELEMENTS can come before anything awaits it. */
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

    /** A control body as commands for the log, such as `SET_MODE 0x10005001 mode=PRDC prio=10`. */
    private fun describeControl(body: ByteArray): String {
        val cmds = runCatching { decodeControl(body) }.getOrNull()
            ?: return "unparsed ${body.size}B: " + body.joinToString(" ") { "%02x".format(Locale.ROOT, it) }
        return cmds.joinToString(", ") { c ->
            val op = when (c.op) {
                Opcode.SET_MODE -> "SET_MODE"
                Opcode.REQUEST -> "REQUEST"
                Opcode.DATA -> "NEW_DATA"
                Opcode.CANCEL -> "CANCEL"
                Opcode.ABORT -> "ABORT"
                Opcode.HOLD -> "HOLD"
                Opcode.METADATA -> "METADATA"
                Opcode.COMPRESS -> "COMPRESS"
                else -> "op${c.op}"
            }
            val mode = when (c.mode) {
                CxpMode.OFF -> "OFF"
                CxpMode.PRDC -> "PRDC"
                CxpMode.RQST -> "RQST"
                else -> "mode${c.mode}"
            }
            if (c.op == Opcode.SET_MODE) {
                "%s 0x%08x mode=%s prio=%d".format(Locale.ROOT, op, c.cxpId, mode, c.priority)
            } else {
                "%s 0x%08x".format(Locale.ROOT, op, c.cxpId)
            }
        }
    }

    /**
     * Record what the navigator's control commands say about each id. A body that does not decode
     * is ignored: the control channel is advisory here.
     */
    private fun absorbControl(frame: AppFrame) {
        val cmds = runCatching { decodeControl(frame.payload) }.getOrNull() ?: return
        for (cmd in cmds) when (cmd.op) {
            Opcode.SET_MODE -> peerMode[cmd.cxpId] = cmd
            Opcode.REQUEST -> peerWants.add(cmd.cxpId)
            Opcode.ABORT -> peerAborted.add(cmd.cxpId)
        }
    }

    /**
     * Tell the navigator we have data ready for [cxpId]: `CXP_app_notify`, control opcode 2, which
     * the receiver's `cxp_app_fm_new_data` turns into the flag its pump reports to the id's owner.
     */
    fun notify(cxpId: Long) {
        session.send(controlFrame(listOf(ControlCmd(Opcode.DATA, cxpId))).encode())
    }

    /** Whether the navigator opens [cxpId], by a live mode or a request, within [timeoutMillis]. */
    private fun awaitChannel(cxpId: Long, timeoutMillis: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (true) {
            if (cxpId in peerWants) return true
            peerMode[cxpId]?.let { if (it.mode != CxpMode.OFF) return true }
            if (System.nanoTime() >= deadline) return false
            stash(receiveMessage(200))
        }
    }

    /**
     * The frames of each message still being reassembled, kept here rather than in
     * [receiveMessage] so that a wait which times out between two segments does not lose the first.
     */
    private val partial = HashMap<Long, MutableList<AppFrame>>()

    /**
     * The next complete application message, or null if none arrives within [timeoutMillis].
     * Control frames, the navigator's id list and its identification are absorbed on the way, which
     * is how the waits here learn of them. A message starts with BEGIN, as `cxp_app_fm_in`
     * requires, so a frame with no message open is the tail of a lost one and is dropped.
     */
    private fun receiveMessage(timeoutMillis: Long): AppFrame? {
        while (true) {
            val frame = nextFrame(timeoutMillis) ?: return null
            // A control frame is out of band: id 0, no END bit, part of no message.
            if (frame.type and FrameType.CONTROL != 0 && frame.cxpId == 0L) {
                onEvent(CxpEvent.Note("control: " + describeControl(frame.payload)))
                absorbControl(frame)
                continue
            }
            val parts = if (frame.type and FrameType.BEGIN != 0) mutableListOf(frame)
            else partial[frame.cxpId]?.apply { add(frame) }
            if (parts == null) {
                onEvent(CxpEvent.Note("dropped a frame of ${hex(frame.cxpId)} with no message open"))
                continue
            }
            if (parts.sumOf { it.payload.size } > MAX_MESSAGE) {
                partial.remove(frame.cxpId)
                val note = "dropped a message of ${hex(frame.cxpId)} past $MAX_MESSAGE bytes"
                onEvent(CxpEvent.Note(note))
                continue
            }
            if (!frame.isLast) {
                partial[frame.cxpId] = parts
                continue
            }
            partial.remove(frame.cxpId)
            val body = reassemble(parts)
            when (frame.cxpId) {
                // The navigator's own id list is bookkeeping, not a reply anyone waits for.
                ID_LIST_CXP_ID -> runCatching { decodeIdList(body) }.getOrNull()?.let { ids ->
                    peerIds = ids
                    onEvent(CxpEvent.Note(
                        "navigator advertises ${ids.size} ids: " + ids.joinToString(" ") { hex(it) }
                    ))
                }
                // The navigator says what it is, once; [awaitProductData] waits for it.
                ID_CONNECTED_LRU -> adoptProductData(body)
                else -> return AppFrame(frame.cxpId, body, frame.type)
            }
        }
    }

    /** The next message, which the navigator owes us within [timeoutMillis]. */
    private fun requireMessage(timeoutMillis: Long = 10_000): AppFrame =
        receiveMessage(timeoutMillis)
            ?: throw SessionException("timed out waiting for a reply from the navigator", SessionException.Reason.NO_REPLY)

    /**
     * The next message on [cxpId], parking any other for whoever waits for it: the notification on
     * `0x1000a000` arrives about once a second, so it can come first.
     */
    private fun await(cxpId: Long, timeoutMillis: Long): AppFrame {
        takeStashed(cxpId)?.let { return it }
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (true) {
            val left = (deadline - System.nanoTime()) / 1_000_000L
            if (left <= 0) throw SessionException("no ${hex(cxpId)} from the navigator", SessionException.Reason.NO_REPLY)
            val frame = receiveMessage(minOf(left, 200)) ?: continue
            if (frame.cxpId == cxpId) return frame
            stash(frame)
        }
    }

    /** The transport handshake: our SYN and the navigator's. */
    internal fun connect() {
        onStep(Step.HANDSHAKING)
        info = info.copy(negotiated = session.connect())
    }

    /**
     * SYN, then USER to CHALLENGE to RESPONSE to CONFIRM, then the registrations and the full id
     * list that open the upload channel.
     */
    fun connectAndAuthenticate() {
        connect()

        onStep(Step.AUTHENTICATING)
        // The navigator sends no challenge until we register for it ([AUTH_IDS]).
        register(AUTH_IDS, CxpMode.PRDC, PRIORITY_DEFAULT)
        // Ask for the navigator's id list the way it asks for ours: `CXP_app_new` sets its receive
        // table's id 0 to (PRDC, CXP_PRIORITY_CONTROL).
        register(listOf(ID_LIST_CXP_ID), CxpMode.PRDC, PRIORITY_CONTROL)

        // Announce the ids we send, without which the navigator drops them ([ID_LIST_CXP_ID]),
        // but only the auth ones until CONFIRM ([AUTH_ONLY_IDS]).
        sendIdList(AUTH_ONLY_IDS)

        sendMessage(AuthId.USER, buildUser(credential))

        // Answer challenges until a CONFIRM verifies. While unsatisfied the navigator challenges on
        // the 1500 ms period of `cxp_app_fm_add(t, 2, 0x5dc)`, with fresh bytes from
        // `cxp_auth_open` each time, so answer the newest. A rejected response draws a CONFIRM of
        // sixteen zero bytes before the next challenge (`cxp_auth_close`, `FUN_d84191d8`), and so
        // does a response to a challenge already replaced, so a CONFIRM that does not verify is a
        // lost round. Only [AUTH_ATTEMPTS] challenges without a verified CONFIRM blame the
        // credential. A CONFIRM may answer an earlier response, so every nonce sent is a candidate.
        val nonces = mutableListOf<ByteArray>()
        var challenges = 0
        while (true) {
            val frame = requireMessage()
            when (frame.cxpId) {
                AuthId.CHALLENGE -> {
                    if (++challenges > AUTH_ATTEMPTS) {
                        throw SessionException(
                            "the navigator issued $AUTH_ATTEMPTS challenges without accepting a " +
                                "response. The credential is not one it holds",
                            SessionException.Reason.CREDENTIAL_REFUSED,
                        )
                    }
                    val r = buildResponse(credential.userId, credential.token, frame.payload)
                    sendMessage(AuthId.RESPONSE, r.encode())
                    nonces += r.nonce
                }

                AuthId.CONFIRM -> {
                    if (nonces.isEmpty()) {
                        throw SessionException("AUTH_CONFIRM arrived before any challenge")
                    }
                    if (nonces.any { checkConfirm(credential.token, it, frame.payload) }) break
                    onEvent(
                        CxpEvent.Note("AUTH_CONFIRM did not verify; answering the next challenge")
                    )
                }

                else -> throw SessionException(
                    "expected AUTH_CHALLENGE or AUTH_CONFIRM, got id ${hex(frame.cxpId)}"
                )
            }
        }

        // Wait for the navigator's id list before registering anything else, as Garmin Pilot does
        // (`if (type == CxpIdType.CXP_ID_LIST)`). Best effort: that order is read from its code,
        // not observed.
        if (!awaitPeerIds(10_000)) onEvent(CxpEvent.Note("no id list from the navigator"))

        // The flight plan set. Its upload id is one we send, for which registering does nothing:
        // a SET_MODE configures the peer's send table.
        register()

        // Register for the navigator's identification, which it sends only when registered for,
        // as Garmin Pilot does: `SET_MODE 0x00001006 mode=PRDC prio=10`.
        register(listOf(ID_CONNECTED_LRU), CxpMode.PRDC, PRIORITY_DEFAULT)

        // Announce everything we may send, now that every id is authorized: the navigator walks
        // the entries this list creates and opens the upload channel. Nothing is sent on
        // `0x00001000`, where the navigator answers an empty request with ABORT.
        sendIdList()
    }

    /**
     * Tell the navigator which CXP ids we want, and in what mode, as
     * `ConnextDevice.registerForMessageType` does: one SET_MODE per id, in one control frame. The
     * navigator publishes nothing it has not been asked for; Garmin Pilot asks in PRDC.
     */
    fun register(
        ids: List<Long> = FLIGHT_PLAN_IDS,
        mode: Int = CxpMode.PRDC,
        priority: Int = PRIORITY_FPL_TRANSFER,
    ) {
        session.send(controlFrame(ids.map { setMode(it, mode, priority) }).encode())
    }

    /** Declare which CXP ids this client will send, on [ID_LIST_CXP_ID]. */
    fun sendIdList(ids: List<Long> = SENT_IDS) {
        session.send(idListFrame(ids).encode())
    }

    /** Read the navigator's advertised flight plan capabilities. */
    fun negotiateCapabilities(): SupportedElements {
        onStep(Step.NEGOTIATING)
        // No request: a client can only receive on 0x10005000, and the navigator's handler reads
        // 7 bytes from an incoming one, so an empty request is dropped. register() asked for it.
        val frame = await(FplId.SUPPORTED_ELEMENTS, 10_000)
        val caps = SupportedElements.decode(frame.payload)
        info = info.copy(capabilities = caps)
        return caps
    }

    /**
     * Upload an ARINC 702A route string, wait until the transport has acknowledged it
     * ([Session.flush]), and fail if the navigator refuses it.
     */
    fun upload(
        route: String,
        device: Profile,
        capabilities: SupportedElements?,
        timeoutMillis: Long = 10_000,
    ) {
        onStep(Step.UPLOADING)
        // The one channel a flight plan goes up on. In the navigator's CXP id table (the
        // trainer's `IOP_E.dll` at `0x1005e864`) `0x10005004` has no write handler, so the
        // navigator cannot receive on it: it is a download channel.
        val id = FplId.UPLOAD_TO_AVIONICS
        // The id list sent after CONFIRM normally opens the channel, and the navigator answers
        // `SET_MODE 0x10005001 mode=PRDC prio=10`, as it does for Garmin Pilot, which sends no
        // notification. Failing that, a notification (control opcode 2) can also run the walk that
        // opens it. The push goes ahead either way, since a refusal comes back as an ABORT.
        if (!awaitChannel(id, CHANNEL_GRACE_MILLIS)) {
            onEvent(CxpEvent.Note("channel still shut, notifying and waiting again"))
            notify(id)
            if (!awaitChannel(id, CHANNEL_GRACE_MILLIS)) {
                onEvent(CxpEvent.Note("channel not opened, sending anyway"))
            }
        }
        peerAborted.remove(id)
        sendMessage(id, encodeUpload(route, device, capabilities))
        session.flush(timeoutMillis)
        onStep(Step.VERIFYING)
        awaitUploadVerdict(id, verdictGraceMillis)
        onStep(Step.DONE)
    }

    /**
     * Wait out the grace for a refusal of the message just pushed: control opcode 4, ABORT, naming
     * the id. [Session.flush] shows only that the transport acknowledged the bytes. A GPS 175
     * refuses about 80 ms after the last segment and never answers a plan it accepts, so silence is
     * success. The refusal comes from `cxp_app_fm_in_begin`: the receive entry's mode is off,
     * because the channel was never opened or, on a GPS 175, Flight Plan Import is off, or a
     * message is already open on the id.
     */
    private fun awaitUploadVerdict(cxpId: Long, timeoutMillis: Long) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (true) {
            if (cxpId in peerAborted) throw refused(cxpId)
            val left = (deadline - System.nanoTime()) / 1_000_000L
            if (left <= 0) return
            val frame = try {
                receiveMessage(minOf(left, 200))
            } catch (e: Exception) {
                // A refusal read just before the link failed is still the answer.
                if (cxpId in peerAborted) throw refused(cxpId)
                throw e
            }
            stash(frame)
        }
    }

    private fun refused(cxpId: Long) = SessionException(
        ("the navigator refused the flight plan on 0x%08x with ABORT. Is Flight Plan Import on? " +
            "On a GPS 175 it is under Home > System > Connext Setup").format(Locale.ROOT, cxpId),
        SessionException.Reason.UPLOAD_REFUSED,
    )
}

/** A packet as the protocol log writes it, in the root locale whatever the phone's language. */
internal fun Packet.describe(): String {
    val flags = buildList {
        if (ctrl and Ctrl.SYN != 0) add("SYN")
        if (ctrl and Ctrl.ACK != 0) add("ACK")
        if (ctrl and Ctrl.EAK != 0) add("EAK")
    }.ifEmpty { listOf("DATA") }.joinToString("|")
    val frame = if (isData) {
        runCatching { AppFrame.decode(payload).value }.getOrNull()
            ?.let { " id=${hex(it.cxpId)} type=0x%02x %dB".format(Locale.ROOT, it.type, it.payload.size) }
            .orEmpty()
    } else ""
    return "%s psn=%d ack=%d %dB%s".format(Locale.ROOT, flags, psn, ack, payload.size, frame)
}
