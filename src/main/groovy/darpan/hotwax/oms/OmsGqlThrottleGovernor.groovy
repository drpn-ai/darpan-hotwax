package darpan.hotwax.oms

/**
 * Tracks the moqui-gql cost bucket across calls and says how long to wait before the next one.
 *
 * WHY THIS EXISTS AT ALL: the endpoint answers an exhausted bucket with HTTP 200, no data, and a
 * THROTTLED error code. Downstream that is indistinguishable from "this window held no orders", so
 * an unpaced client writes an empty extract and reports a clean reconciliation.
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
    private long updatedAt = 0L

    OmsGqlThrottleGovernor(Closure<Long> clock = { -> System.currentTimeMillis() }) {
        this.clock = clock
    }

    synchronized int getAvailable() { if (!observed) return -1; refill(); return (int) Math.floor(available) }

    /** The bucket refills at the server's rate between calls (DAR-BE-116: 2000/s on gorjana). */
    private void refill() {
        long now = (long) clock.call()
        if (observed && updatedAt > 0L && now > updatedAt) {
            available += (now - updatedAt) * restoreRate / 1000d
        }
        updatedAt = now
    }

    /**
     * Read the bucket back out of a response's `extensions` block. Missing status = no update. An
     * observation only ever LOWERS the estimate: the server reported the bucket before our other in-flight
     * calls landed (live DAR-BE-116: 12 parallel slices took 20000 to 3 and drew 56 THROTTLED), and it is
     * the only source that sees spending by other extracts, so the smaller of the two is the truth.
     */
    synchronized void observe(Map extensions) {
        Map cost = (extensions?.get("cost") ?: [:]) as Map
        Map status = (cost.get("throttleStatus") ?: [:]) as Map
        if (status.get("restoreRate") != null) {
            restoreRate = ((Number) status.get("restoreRate")).intValue()
        }
        if (status.get("currentlyAvailable") != null) {
            refill()
            double reported = ((Number) status.get("currentlyAvailable")).doubleValue()
            available = observed ? Math.min(available, reported) : reported
            observed = true
        }
    }

    /**
     * Milliseconds to wait before issuing a call reserving `reservation`. Zero when the bucket already
     * covers it, or when nothing has been observed yet. Either way the reservation is spent: after a wait
     * the bucket refills to exactly the reservation and the caller's call takes it, so it reads 0.
     */
    synchronized long sleepMillisFor(int reservation) {
        if (!observed) return 0L
        refill()
        if (available >= reservation) {
            // Spent now, so a concurrent caller on the same bucket sees only what is left.
            available -= reservation
            return 0L
        }
        double deficit = reservation - available
        int rate = Math.max(1, restoreRate)
        long wait = (long) Math.ceil(deficit / (double) rate * 1000) + SAFETY_MARGIN_MS
        // The wait is this caller's; the bucket it refills to is spent by this call. Later callers queue
        // behind it because refill() starts from now and the bucket reads 0 until the wait has passed.
        available = 0d
        updatedAt = (long) clock.call() + wait
        return wait
    }
}
