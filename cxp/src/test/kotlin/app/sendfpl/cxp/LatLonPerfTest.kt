package app.sendfpl.cxp

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The route is parsed again on every keystroke, on the main thread, so the coordinate regexes have
 * to stay cheap.
 *
 * This is not a speculative test. The first version of [parseLatLon] used two unbounded `\d+`
 * runs, which let a backtracking engine explore every way of splitting a long digit string in
 * two: a token of 400 characters cost ~9.7 ms per pass and produced a visible ANR in the emulator.
 * The quantifiers are bounded and there is a length guard because of that, and this pins it.
 */
class LatLonPerfTest {

    private val inputs = listOf(
        "KSFO N48,8200/E2,62000 KLAS",
        "KSFO SAC V334 LIN BTY KLAS",
        // Adversarial: long digit runs with no hemisphere to terminate them, repeated matches
        // that nearly succeed, and trailing characters that fail the lookahead.
        "N" + "9".repeat(400),
        "N123456,".repeat(60) + "X",
        "1234567890".repeat(40) + "N",       // the shape that used to cost 9.7 ms
        "N48.8200/E2.62000 ".repeat(50),
    )

    @Test
    fun `normalising and parsing stay far below a frame budget`() {
        // Warm up, then measure. The JIT matters more than the loop count here.
        repeat(200) { pass() }

        val start = System.nanoTime()
        repeat(500) { pass() }
        val perPass = (System.nanoTime() - start) / 500.0 / 1_000_000.0

        // One keystroke does this once. 16 ms is a frame, and anything near it would be a bug.
        assertTrue("a full parse pass took %.2f ms".format(perPass), perPass < 2.0)
    }

    private fun pass() = inputs.forEach { text ->
        normaliseCoordinates(text).split(' ', ',').forEach { runCatching { parseLatLon(it) } }
    }

    @Test
    fun `an implausibly long token is not a coordinate`() {
        // The length guard must reject rather than throw. It is not a malformed coordinate,
        // it is simply not one, so the caller falls through to treating it as an identifier.
        assertTrue(parseLatLon("N" + "1".repeat(200) + "E002372") == null)
    }
}
