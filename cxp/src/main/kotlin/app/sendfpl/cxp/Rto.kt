package app.sendfpl.cxp

/**
 * The estimator for the retransmission timeout, from the `cxp_t_rto_*` block of `cxp_t_pkt_mngr.c`:
 * Jacobson/Karels with Garmin's constants. `to_retry` is the ceiling on the timeout and its value
 * before the first RTT sample, not the retransmission period the name suggests.
 *
 * The estimate, though not the deadlines, is computed in 32 bits as in the C: products are masked
 * before they are shifted, and shifts truncate, so `750 shr 2` is 187.
 *
 * State offsets in the ARM32 build: `0xa0` rttPsn, `0xa4` rttStart, `0xa8` lastRtt,
 * `0xac` haveEstimate, `0xb0` srtt, `0xb4` rttvar, `0xb8` rto, `0xbc` reported, `0xbd` armedPsn,
 * `0xbe` retries, `0xc0` deadline.
 */
internal class Rto(
    /** Milliseconds from an arbitrary origin, like `cxp_ms_timer`; injectable for exact tests. */
    private val now: () -> Long,
    var toRetry: Int,
) {
    private var rttPsn = 0
    private var rttStart = NOT_TIMING
    var lastRtt = 0L
        private set

    var haveEstimate = false
        private set
    var srtt = 0L
        private set
    var rttvar = 0L
        private set
    var value = 0L
        private set

    private var reported = false
    var armedPsn = 0
        private set
    var retries = 0
        private set
    var deadline = DISARMED
        private set

    /** Start timing [psn], if nothing is being timed already. */
    fun rttSet(psn: Int) {
        if (rttStart == NOT_TIMING) {
            rttStart = now()
            rttPsn = psn and 0xFF
        }
    }

    /**
     * Abandon the sample in flight if it is for [psn]: Karn's algorithm, called on every
     * retransmission so a resent packet yields no RTT sample.
     */
    fun rttClear(psn: Int) {
        if (rttStart != NOT_TIMING && (psn and 0xFF) == rttPsn) rttStart = NOT_TIMING
    }

    /** Fold an acknowledgement of [psn] into the estimate. */
    fun rttUpdate(psn: Int) {
        if (rttStart == NOT_TIMING || (psn and 0xFF) != rttPsn) return
        lastRtt = (now() - rttStart) and MASK32
        rttStart = NOT_TIMING

        if (!haveEstimate) {
            haveEstimate = true
            srtt = ((lastRtt / 500) * 500 + 500) and MASK32
            rttvar = srtt shr 1
        } else {
            val delta = if (srtt < lastRtt) lastRtt - srtt else srtt - lastRtt
            rttvar = ((((rttvar * 3) and MASK32) shr 2) + (delta shr 2)) and MASK32
            srtt = ((((srtt * 7) and MASK32) shr 3) + (lastRtt shr 3)) and MASK32
        }

        // The compiler's form of the 32 bit test `(rttvar shl 2) < 500`.
        val margin = if ((rttvar and 0x3FFFFFFFL) < 125L) 500L else (rttvar shl 2) and MASK32
        value = (margin + srtt) and MASK32
    }

    /**
     * The current timeout, in milliseconds. Not a plain getter: once there are retries, each call
     * doubles the stored RTO, and from the fourth retry the estimate is discarded.
     */
    fun get(): Long {
        if (retries != 0) value = (value shl 1) and MASK32
        if (retries > 3) haveEstimate = false
        val v = value
        return if (v == 0L || toRetry.toLong() < v) toRetry.toLong() else v
    }

    /**
     * Whether [psn] is due for retransmission. Each expiry of the timer makes one packet due, the
     * one just past the armed sequence number: the base of the window, not everything in flight.
     * Garmin compares `armed + 1` without reducing it mod 256, so it never finds psn 0 due; this
     * comparison wraps.
     */
    fun timerCheck(psn: Int): Boolean {
        if (deadline <= now()) {
            reported = false
            retries += 1
            deadline = now() + get()
        }
        if (retries == 0 || reported || (psn and 0xFF) != ((armedPsn + 1) and 0xFF)) return false
        reported = true
        return true
    }

    /**
     * Advance the timer when [psn] is acknowledged. [nextPsn] is the session's next sequence
     * number to send (`state + 0x99`).
     */
    fun timerReset(psn: Int, nextPsn: Int) {
        val p = psn and 0xFF
        if (((nextPsn - 1) and 0xFF) == p) {
            timerStop()
            return
        }
        val span = (nextPsn - armedPsn) and 0xFF
        if (((p - armedPsn) and 0xFF) <= span && p != armedPsn) {
            deadline = now() + get()
            armedPsn = p
            reported = false
        }
    }

    /** Disarm the timer and forget the retries. */
    fun timerStop() {
        deadline = DISARMED
        retries = 0
        reported = false
    }

    /**
     * Keep the timer on [oldest], the first packet still in flight. Garmin's timer moves only when
     * the cumulative ack releases a packet itself (`cxp_t_pkt_mngr_proc` calls only `rtt_update`
     * for an EAK), so once an EAK has released the packet past the armed psn, nothing in flight is
     * ever due again and the session stalls. A window of one never sees an EAK.
     */
    fun timerFollow(oldest: Int) {
        val o = oldest and 0xFF
        if (((armedPsn + 1) and 0xFF) != o) {
            armedPsn = (o - 1) and 0xFF
            reported = false
        }
    }

    /** Set the retry counter as a run of timeouts would, so a test need not wait them out. */
    internal fun forceRetries(n: Int) { retries = n }

    /** Start the timer for [psn] if it is not already running. */
    fun timerArm(psn: Int) {
        if (deadline != DISARMED) return
        retries = 0
        deadline = now() + get()
        armedPsn = (psn - 1) and 0xFF
    }

    private companion object {
        /**
         * The C uses 0xFFFFFFFF for both sentinels and lets its millisecond timer wrap at 32 bits.
         * Distinct Long sentinels keep them apart, and Long deadlines do not wrap.
         */
        const val NOT_TIMING = Long.MIN_VALUE
        const val DISARMED = Long.MAX_VALUE
        const val MASK32 = 0xFFFFFFFFL
    }
}
