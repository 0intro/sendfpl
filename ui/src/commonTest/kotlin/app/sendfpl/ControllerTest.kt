package app.sendfpl

import app.sendfpl.cxp.Carrier
import app.sendfpl.cxp.Credential
import app.sendfpl.cxp.CxpEvent
import app.sendfpl.cxp.DemoNavigator
import app.sendfpl.cxp.FrameStream
import app.sendfpl.cxp.ProductData
import app.sendfpl.cxp.ProductId
import app.sendfpl.cxp.Profiles
import app.sendfpl.cxp.TOKEN_LEN
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private val token = ByteArray(TOKEN_LEN) { it.toByte() }
private val demoCredential = Credential(1, token, byteArrayOf(1))
private const val ROUTE = "LFPL LOBEL,N48475E002522 LFPK"

private class MemoryStore(
    var credential: Credential? = demoCredential,
    override var profileProductId: Long = 0,
) : Store {
    var saved: String? = null

    override fun loadCredential() = credential

    override fun saveCredential(json: String) {
        saved = json
    }

    override fun clearCredential() {
        credential = null
    }
}

/** A navigator in memory, as an iPhone reaches one: frames as they are, with no transport. */
private class DemoLine(private val navigator: DemoNavigator) : Line {
    override suspend fun open(onEvent: (CxpEvent) -> Unit): Carrier = FrameStream(navigator, onEvent)

    override fun close() = navigator.close()
}

/**
 * A navigator in memory whose line holds its first close until [release], as a socket can take its
 * time to close once the plan is up.
 */
private class SlowClosingLine(private val navigator: DemoNavigator) : Line {
    private val released = CompletableDeferred<Unit>()
    private var closes = 0

    fun release() {
        released.complete(Unit)
    }

    override suspend fun open(onEvent: (CxpEvent) -> Unit): Carrier = FrameStream(navigator, onEvent)

    override fun close() {
        if (closes++ == 0) runBlocking { released.await() }
        navigator.close()
    }
}

/**
 * A line that never opens, until it is closed. Its open ignores the coroutine's cancellation, as a
 * socket's connect does, so only [close] ends it.
 */
private class StuckLine : Line {
    private val closed = CompletableDeferred<Unit>()

    override suspend fun open(onEvent: (CxpEvent) -> Unit): Carrier {
        withContext(NonCancellable) { closed.await() }
        throw IllegalStateException("closed while opening")
    }

    override fun close() {
        closed.complete(Unit)
    }
}

/** The apps' shared half, on real threads, since a transfer blocks there as it does on a phone. */
class ControllerTest {

    private fun controller(store: Store = MemoryStore()) =
        Controller(CoroutineScope(Dispatchers.Default + SupervisorJob()), Dispatchers.Default, store)
            .apply { credentialPresent(true) }

    /** The state once [done] holds, waited for on real time. */
    private suspend fun Controller.await(done: (RouteState) -> Boolean): RouteState =
        withContext(Dispatchers.Default) { withTimeout(20_000) { state.first(done) } }

    private suspend fun Controller.sent(line: Line): RouteState {
        send(line, "demo")
        return await { !it.busy && it.outcome != null }
    }

    @Test
    fun `a typed route is previewed for the model in use`() {
        val c = controller()
        c.setRoute(ROUTE)
        assertEquals("FPN/RI:F:LFPL:F:LOBEL,N48475E002522:F:LFPK", c.state.value.preview)
        assertTrue(c.state.value.canSend)
    }

    @Test
    fun `a route goes up and the navigator is described`() = runTest {
        val navigator = DemoNavigator(token)
        val c = controller()
        c.setRoute(ROUTE)
        val end = c.sent(DemoLine(navigator))
        assertEquals(Outcome.Success, end.outcome)
        assertEquals(Step.DONE, end.step)
        assertEquals(listOf("FPN/RI:F:LFPL:F:LOBEL,N48475E002522:F:LFPK"), navigator.uploads)
        assertEquals("connecting to demo", end.log.first())
        assertEquals("GPS 175", end.info.productData?.name)
        assertFalse(end.usedPickerModel)
    }

    /** A GTN is sent a user waypoint as its position alone, and the model is kept for next time. */
    @Test
    fun `a navigator naming another model is sent that model's route`() = runTest {
        val store = MemoryStore()
        val navigator = DemoNavigator(token, productId = Profiles.GTN.productId)
        val c = controller(store)
        c.setRoute(ROUTE)
        val end = c.sent(DemoLine(navigator))
        assertEquals(listOf("FPN/RI:F:LFPL:F:N48475E002522:F:LFPK"), navigator.uploads)
        assertEquals(Profiles.GTN, end.device)
        assertEquals(navigator.uploads.single(), end.preview, "the preview follows the model")
        assertEquals(Profiles.GTN.productId, store.profileProductId)
    }

    /** A Cancel once the plan is up leaves the send the success it was, and recorded as one. */
    @Test
    fun `a cancel after the plan is delivered is ignored`() = runTest {
        val line = SlowClosingLine(DemoNavigator(token))
        val c = controller()
        c.setRoute(ROUTE)
        var recorded = false
        c.send(line, "demo", onSent = { recorded = true })
        c.await { it.step == Step.DONE }
        c.cancelSend()
        line.release()
        val end = c.await { !it.busy }
        assertEquals(Outcome.Success, end.outcome)
        assertTrue(recorded)
    }

    @Test
    fun `a credential the navigator does not hold stops at authentication`() = runTest {
        val navigator = DemoNavigator(ByteArray(TOKEN_LEN))
        val c = controller()
        c.setRoute(ROUTE)
        val failure = assertIs<Outcome.Failure>(c.sent(DemoLine(navigator)).outcome)
        assertEquals(Step.AUTHENTICATING, failure.step)
        assertTrue(navigator.uploads.isEmpty())
    }

    @Test
    fun `a refused upload is a failure`() = runTest {
        val navigator = DemoNavigator(token, refuse = true)
        val c = controller()
        c.setRoute(ROUTE)
        assertIs<Outcome.Failure>(c.sent(DemoLine(navigator)).outcome)
        assertTrue(navigator.uploads.isEmpty())
    }

    @Test
    fun `no credential fails before the line opens`() = runTest {
        val line = StuckLine()
        val c = controller(MemoryStore(credential = null))
        c.setRoute(ROUTE)
        val failure = assertIs<Outcome.Failure>(c.sent(line).outcome)
        assertEquals(Step.CONNECTING, failure.step)
        assertEquals(listOf("failed: no credential imported"), c.state.value.log)
    }

    /** Cancel closes a line still opening, and leaves no outcome to report. */
    @Test
    fun `a cancelled send ends quietly`() = runTest {
        val c = controller()
        c.setRoute(ROUTE)
        c.send(StuckLine(), "demo")
        c.await { it.log.isNotEmpty() }
        c.cancelSend()
        val end = c.await { !it.busy }
        assertNull(end.outcome)
        assertEquals("cancelled", end.log.last())
    }

    @Test
    fun `an imported file shows its identifiers and its preview`() = runTest {
        val c = controller()
        c.importFile { "route.gfp" to "FPN/RI:F:LFPL:F:PMN:F:LFPK".encodeToByteArray() }
        val end = c.await { !it.importing && it.parsed != null }
        assertEquals("LFPL PMN LFPK", end.routeText)
        assertEquals("FPN/RI:F:LFPL:F:PMN:F:LFPK", end.preview)
    }

    /** A file that cannot be read is worded under the box, and the route in the box stays. */
    @Test
    fun `a file that cannot be read is worded under the box`() = runTest {
        val c = controller()
        c.setRoute(ROUTE)
        c.importFile { throw FileTooLargeException(ROUTE_FILE_LIMIT) }
        val end = c.await { !it.importing && it.routeError != null }
        assertEquals(ROUTE, end.routeText)
        assertNotNull(end.parsed)
    }

    /** The picker re-renders the route in hand, and remembers its model. */
    @Test
    fun `choosing a model renders the route for it`() = runTest {
        val store = MemoryStore()
        val c = controller(store)
        c.setRoute(ROUTE)
        c.selectProfile("gtn")
        assertEquals("FPN/RI:F:LFPL:F:N48475E002522:F:LFPK", c.state.value.preview)
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { while (store.profileProductId != Profiles.GTN.productId) delay(10) }
        }
    }

    @Test
    fun `a stored credential is present until forgotten`() = runTest {
        val store = MemoryStore()
        val c = controller(store).apply { credentialPresent(false) }
        c.importCredential { "{}" }
        c.await { it.credentialPresent == true }
        assertEquals("{}", store.saved)
        c.forgetCredential()
        assertEquals(false, c.state.value.credentialPresent)
    }

    /** Silence and an unmeasured model are sent the picker's limits alike, and both say so. */
    @Test
    fun `a send under the picker's model says so`() {
        fun named(id: Long) =
            RouteState(outcome = Outcome.Success, info = DeviceInfo(productData = ProductData(id, "", "")))
        assertTrue(RouteState(outcome = Outcome.Success).usedPickerModel)
        assertTrue(named(ProductId.GI_275).usedPickerModel)
        assertFalse(named(ProductId.G2N).usedPickerModel)
        assertFalse(named(ProductId.GTN_6XX_7XX).usedPickerModel)
        assertFalse(RouteState().usedPickerModel)
        assertNotNull(Profiles.byProductId[ProductId.G2N])
    }
}
