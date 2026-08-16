package app.sendfpl.cxp

/**
 * The estimator for the retransmission timeout, from the `cxp_t_rto_*` block of `cxp_t_pkt_mngr.c`.
 *
 * This is Jacobson/Karels with Garmin's own constants. `to_retry` is the **ceiling** on the
 * timeout, and the value used before any RTT sample exists. It is not the retransmission period
 * itself, which is the reading the name invites and which two builds of the library were needed to
 * rule out.
 *
 * Karn's algorithm is part of it: [rttClear] is called on every retransmission, so a resent
 * packet can never contribute a sample.
 *
 * Arithmetic is 32 bit and every step truncates, because these are shifts, not division, so
 * `750 shr 2` is 187 and not 188. The masking keeps that faithful on Kotlin's 64 bit Long.
 *
 * State offsets in the ARM32 build, for anyone reading the decompilation again:
 * `0xa0` rttPsn, `0xa4` rttStart, `0xa8` lastRtt, `0xac` haveEstimate, `0xb0` srtt,
 * `0xb4` rttvar, `0xb8` rto, `0xbc` reported, `0xbd` armedPsn, `0xbe` retries, `0xc0` deadline.
 */
internal class Rto(
    /** Milliseconds from an arbitrary origin, as `cxp_ms_timer` does. Injectable so tests are exact. */
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
     * Abandon the sample in flight if it is for [psn]. Karn's algorithm, called from the
     * retransmit path so a resent packet yields no RTT sample.
     */
    fun rttClear(psn: Int) {
        if (rttStart != NOT_TIMING && (psn and 0xFF) == rttPsn) rttStart = NOT_TIMING
    }

    /**
     * Fold an acknowledgement of [psn] into the estimate.
     *
     * ```
     * first sample:  srtt   = (rtt / 500) * 500 + 500
     *                rttvar = srtt shr 1
     * later:         delta  = |srtt - rtt|
     *                rttvar = (rttvar * 3) shr 2 + (delta shr 2)
     *                srtt   = (srtt   * 7) shr 3 + (rtt   shr 3)
     *                rto    = srtt + (rttvar < 125 ? 500 : rttvar shl 2)
     * ```
     */
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
            rttvar = (((rttvar * 3) shr 2) + (delta shr 2)) and MASK32
            srtt = (((srtt * 7) shr 3) + (lastRtt shr 3)) and MASK32
        }

        // The mask is the compiler's: it tests `rttvar * 4 < 500` without letting the shift
        // overflow. Reproduced rather than simplified to `rttvar < 125` so the behaviour matches
        // for absurd inputs too.
        val margin = if ((rttvar and 0x3FFFFFFFL) < 125L) 500L else (rttvar shl 2) and MASK32
        value = (margin + srtt) and MASK32
    }

    /**
     * The current timeout, in milliseconds.
     *
     * It has side effects, which is worth knowing before calling it casually: every call after a
     * retry doubles the stored RTO, and a fourth retry discards the estimate entirely.
     *
     * ```
     * if (retries)     rto = rto shl 1
     * if (retries > 3) haveEstimate = false
     * return (rto == 0 || rto > toRetry) ? toRetry : rto
     * ```
     */
    fun get(): Long {
        if (retries != 0) value = (value shl 1) and MASK32
        if (retries > 3) haveEstimate = false
        val v = value
        return if (v == 0L || toRetry.toLong() < v) toRetry.toLong() else v
    }

    /**
     * Whether [psn] is due for retransmission.
     *
     * Only one packet times out per RTO expiry, and only the one just past the armed sequence
     * number, retransmitting the base of the window rather than everything in flight, which is
     * how Go Back N behaves.
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
            deadline = DISARMED
            retries = 0
            reported = false
            return
        }
        val span = (nextPsn - armedPsn) and 0xFF
        if (((p - armedPsn) and 0xFF) <= span && p != armedPsn) {
            deadline = now() + get()
            armedPsn = p
            reported = false
        }
    }

    /**
     * Drive the retry counter directly, as a run of timeouts would. Exists so the backoff and the
     * discarding of the estimate can be tested without burning real time.
     */
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
         * The C uses 0xFFFFFFFF for both sentinels and relies on u32 wrap for its millisecond
         * timer. Distinct Long sentinels make them unambiguous and remove the wrap entirely.
         */
        const val NOT_TIMING = Long.MIN_VALUE
        const val DISARMED = Long.MAX_VALUE
        const val MASK32 = 0xFFFFFFFFL
    }
}
