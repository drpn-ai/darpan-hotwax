package darpan.hotwax.oms

/**
 * Tracks the moqui-gql cost bucket across calls and says how long to wait before the next one.
 *
 * WHY THIS EXISTS AT ALL: the endpoint answers an exhausted bucket with HTTP 200, no data, and a
 * THROTTLED error code. Downstream that is indistinguishable from "this window held no orders", so
 * an unpaced client writes an empty extract and reports a clean reconciliation.
 *
 * DAR-BE-129: a token bucket that may go into debt. Each call takes its reservation when it is let go, so callers
 * queue behind one another (the balance goes negative and the next caller waits it off). Each answer then
 * resets the balance to what the server reports, less the reservations still out (let go, not yet answered):
 * the server charges a call's ACTUAL cost (~70% of what is reserved on gorjana), and only its report gives
 * the difference back. Before this, refill() moved the clock past the wait promised to the caller ahead, so
 * queued callers fired together and drew THROTTLED (23% of the 2026-10-26 Oct 1-25 calls), and an answer
 * could only ever lower the estimate, so the unspent reservations were never credited.
 *
 * Pure: it returns a duration and the caller sleeps, so the policy is testable without a clock.
 */
class OmsGqlThrottleGovernor {

    /** Cushion added to every computed wait; the server's clock and ours are not the same clock. */
    private static final long SAFETY_MARGIN_MS = 500L

    /**
     * DAR-BE-116: one bucket per OMS endpoint, because the bucket belongs to the credential, not to an
     * extract. An audit run's parallel slices and its other checks all spend from it.
     */
    private static final Map<String, OmsGqlThrottleGovernor> SHARED = new java.util.concurrent.ConcurrentHashMap<>()

    static OmsGqlThrottleGovernor forUrl(String url) {
        return SHARED.computeIfAbsent(url ?: "", { new OmsGqlThrottleGovernor() })
    }

    static void resetShared() { SHARED.clear() }

    /**
     * DAR-BE-116: calls in flight on this bucket at once, fair (FIFO). Parallel slices of every extract on one
     * OMS credential would otherwise outrun the refill and spend the bucket on THROTTLED answers.
     */
    static final int MAX_IN_FLIGHT = 6
    private final java.util.concurrent.Semaphore inFlight = new java.util.concurrent.Semaphore(MAX_IN_FLIGHT, true)

    void acquireCall() { inFlight.acquire() }

    void releaseCall() { inFlight.release() }

    private final Closure<Long> clock
    private double available = 0d
    /** False until a response has reported the bucket. NOT a sentinel balance: an overdrawn bucket is negative. */
    private boolean observed = false
    private int restoreRate = 50
    private double maximum = Double.MAX_VALUE
    private long updatedAt = 0L
    /** Reservations let go and not yet answered: the server has not reported them yet. */
    private long outstanding = 0L

    OmsGqlThrottleGovernor(Closure<Long> clock = { -> System.currentTimeMillis() }) {
        this.clock = clock
    }

    synchronized int getAvailable() { if (!observed) return -1; refill(); return (int) Math.floor(available) }

    /** The bucket refills at the server's rate between calls (DAR-BE-116: 2000/s on gorjana), up to its maximum. */
    private void refill() {
        long now = (long) clock.call()
        if (observed && now > updatedAt) available = Math.min(maximum, available + (now - updatedAt) * restoreRate / 1000d)
        updatedAt = now
    }

    /**
     * Milliseconds to wait before issuing a call reserving `reservation`, and the reservation is taken: zero when the
     * balance covers it (or nothing is known yet), else the time the refill needs to pay off the balance and the
     * reservation. A later caller sees the debt this one left and queues behind it.
     */
    synchronized long sleepMillisFor(int reservation) {
        outstanding += reservation
        if (!observed) return 0L
        refill()
        long wait = available >= reservation ? 0L :
                (long) Math.ceil((reservation - available) / (double) Math.max(1, restoreRate) * 1000) + SAFETY_MARGIN_MS
        available -= reservation
        return wait
    }

    /**
     * A call let go with `reservation` has its answer: the balance becomes what the server reports less the
     * reservations still out. An answer with no bucket status (an error) only gives the reservation up.
     */
    synchronized void settle(int reservation, Map extensions) {
        outstanding = Math.max(0L, outstanding - reservation)
        Map cost = (extensions?.get("cost") ?: [:]) as Map
        Map status = (cost.get("throttleStatus") ?: [:]) as Map
        if (status.get("restoreRate") != null) restoreRate = ((Number) status.get("restoreRate")).intValue()
        if (status.get("maximumAvailable") != null) maximum = ((Number) status.get("maximumAvailable")).doubleValue()
        if (status.get("currentlyAvailable") != null) {
            available = ((Number) status.get("currentlyAvailable")).doubleValue() - outstanding
            updatedAt = (long) clock.call()
            observed = true
        }
    }

    /** An answer that no reservation of this governor's paid for (a probe, a test). */
    void observe(Map extensions) { settle(0, extensions) }
}
