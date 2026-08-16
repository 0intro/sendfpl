package app.sendfpl.cxp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The expected values here are computed by hand from `cxp_t_rto_rtt_update`, and a reference
 * implementation asserts the same numbers. Integer truncation at every step is the point: these
 * are shifts, not division, so `750 shr 2` is 187 and not 188.
 */
class RtoTest {

    private var clock = 0L
    private fun rto(toRetry: Int) = Rto({ clock }, toRetry)

    /** One send/ack cycle taking [rtt] milliseconds. */
    private fun Rto.sample(psn: Int, rtt: Long) {
        rttSet(psn)
        clock += rtt
        rttUpdate(psn)
    }

    @Test
    fun `the estimator matches the arithmetic in cxp_t_rto_rtt_update`() {
        val r = rto(60_000)   // ceiling high enough not to mask anything

        r.sample(1, 120)
        assertEquals("srtt", 500L, r.srtt)
        assertEquals("rttvar", 250L, r.rttvar)
        assertEquals("rto", 1500L, r.value)

        // delta=400: rttvar = (250*3 shr 2) + (400 shr 2) = 187+100 = 287
        //            srtt   = (500*7 shr 3) + (100 shr 3) = 437+12  = 449
        //            rto    = 449 + 287*4 = 1597
        r.sample(2, 100)
        assertEquals(449L, r.srtt)
        assertEquals(287L, r.rttvar)
        assertEquals(1597L, r.value)

        // delta=551: rttvar = (287*3 shr 2) + (551 shr 2) = 215+137 = 352
        //            srtt   = (449*7 shr 3) + (1000 shr 3) = 392+125 = 517
        //            rto    = 517 + 352*4 = 1925
        r.sample(3, 1000)
        assertEquals(517L, r.srtt)
        assertEquals(352L, r.rttvar)
        assertEquals(1925L, r.value)
    }

    @Test
    fun `the 500 ms floor applies when four times the variance is smaller`() {
        val r = rto(60_000)
        r.sample(1, 500)
        // A long run of identical RTTs drives delta to zero, so the variance decays by 3/4 a step.
        for (psn in 2..29) r.sample(psn, r.srtt)
        assertTrue("rttvar = ${r.rttvar}", r.rttvar < 125L)
        assertEquals(r.srtt + 500L, r.value)
    }

    /**
     * to_retry is the ceiling and the starting value, not the timer itself, which is what this
     * port assumed at first.
     */
    @Test
    fun `to_retry is a ceiling and an initial value`() {
        val r = rto(1500)
        assertEquals("with no sample yet", 1500L, r.get())

        r.sample(1, 120)      // computes exactly 1500
        r.sample(2, 5000)     // pushes it well above
        assertTrue("rto = ${r.value}", r.value > 1500L)
        assertEquals("clamped", 1500L, r.get())
    }

    @Test
    fun `get doubles the timeout per retry and discards the estimate after the fourth`() {
        val r = rto(60_000)
        r.sample(1, 120)      // rto = 1500

        r.forceRetries(1)
        assertEquals(3000L, r.get())
        assertEquals(6000L, r.get())

        r.forceRetries(4)
        r.get()
        assertFalse("a fourth retry discards the estimate", r.haveEstimate)
    }

    /** Karn's algorithm: a retransmitted packet must not contribute a sample. */
    @Test
    fun `a resent packet yields no RTT sample`() {
        val r = rto(60_000)
        r.sample(1, 120)
        val before = r.srtt

        r.rttSet(2)
        clock += 50
        r.rttClear(2)          // the packet is retransmitted here
        clock += 50
        r.rttUpdate(2)         // the ack arrives, but the sample was abandoned

        assertEquals("srtt moved", before, r.srtt)
    }

    /**
     * Only the packet just past the armed sequence number times out, and only once per expiry,
     * which is Go Back N rather than retransmitting the whole window.
     */
    @Test
    fun `the timer fires once, for the base of the window`() {
        val r = rto(1000)
        r.timerArm(1)          // armedPsn becomes 0

        assertFalse("fired early", r.timerCheck(1))
        clock += 1000
        assertTrue("psn 1 is due", r.timerCheck(1))
        assertFalse("reported twice for one expiry", r.timerCheck(1))
        assertFalse("psn 2 is not the window base", r.timerCheck(2))
        assertEquals(1, r.retries)
    }

    @Test
    fun `acknowledging the last packet sent disarms the timer`() {
        val r = rto(1000)
        r.timerArm(1)
        r.forceRetries(2)

        r.timerReset(4, 5)     // nextPsn-1 == 4 == psn: everything is acknowledged
        assertEquals(0, r.retries)
    }
}
