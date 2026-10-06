package app.sendfpl.cxp

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The route is parsed again on every keystroke, on the main thread, so the coordinate regexes have
 * to stay cheap. Two unbounded `\d+` runs would let a backtracking engine try every split of a
 * long digit string, so [parseLatLon] bounds its quantifiers and guards the length.
 */
class LatLonPerfTest {

    private val inputs = listOf(
        "KSFO N48,8200/E2,62000 KLAS",
        "KSFO SAC V334 LIN BTY KLAS",
        // Adversarial: long digit runs with no hemisphere to terminate them, repeated matches
        // that nearly succeed, and trailing characters that fail the lookahead.
        "N" + "9".repeat(400),
        "N123456,".repeat(60) + "X",
        "1234567890".repeat(40) + "N",
        "N48.8200/E2.62000 ".repeat(50),
    )

    @Test
    fun `normalising and parsing stay far below a frame budget`() {
        // The JIT matters more than the loop count, so warm it up first.
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
        // The length guard returns null rather than throwing, so the caller reads an identifier.
        assertTrue(parseLatLon("N" + "1".repeat(200) + "E002372") == null)
    }
}
