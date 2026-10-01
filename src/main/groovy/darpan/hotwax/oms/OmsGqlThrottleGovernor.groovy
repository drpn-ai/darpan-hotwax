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

    private int available = -1          // -1 = nothing observed yet
    private int restoreRate = 50

    int getAvailable() { return available }
    int getRestoreRate() { return restoreRate }

    /** Read the bucket back out of a response's `extensions` block. Missing status = no update. */
    void observe(Map extensions) {
        Map cost = (extensions?.get("cost") ?: [:]) as Map
        Map status = (cost.get("throttleStatus") ?: [:]) as Map
        if (status.get("currentlyAvailable") != null) {
            available = ((Number) status.get("currentlyAvailable")).intValue()
        }
        if (status.get("restoreRate") != null) {
            restoreRate = ((Number) status.get("restoreRate")).intValue()
        }
    }

    /**
     * Milliseconds to wait before issuing a call reserving `reservation`. Zero when the bucket already
     * covers it, or when nothing has been observed yet. After returning a non-zero duration the bucket
     * is recorded AS IF refilled to the reservation, because the caller is going to sleep that long.
     */
    long sleepMillisFor(int reservation) {
        if (available < 0 || available >= reservation) return 0L
        int deficit = reservation - available
        int rate = Math.max(1, restoreRate)
        long wait = (long) Math.ceil(deficit / (double) rate * 1000) + SAFETY_MARGIN_MS
        available = reservation
        return wait
    }
}
