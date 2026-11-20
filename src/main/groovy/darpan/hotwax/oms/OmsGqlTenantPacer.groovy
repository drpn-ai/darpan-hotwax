package darpan.hotwax.oms

import java.util.concurrent.ConcurrentHashMap

/**
 * DAR-BE-328. One tenant's share of a HotWax cost bucket during that tenant's peak period: a token bucket refilled at
 * the share of the bucket's restore rate and holding at most the share of its maximum, starting full. It sits in front
 * of the URL's OmsGqlThrottleGovernor and never replaces it: the governor still keeps every caller on the URL inside the
 * server's bucket, and this keeps the one tenant inside its share. Other tenants on the URL have their own pacer (or
 * none), so a paced tenant never slows them.
 *
 * The rate and maximum are whatever the URL's governor reports at the call (its defaults before the first answer, so
 * nothing blocks waiting for one). Like the governor it may go into debt: each call takes its reservation when it is let
 * go, so queued callers line up; and like the governor (DAR-BE-129) each answer gives back what the read did not cost,
 * since the server charges the actual cost (~70% of the reservation on gorjana), so the share is of what is spent.
 * Pure: it returns a duration and the caller sleeps.
 */
class OmsGqlTenantPacer {

    private static final Map<String, OmsGqlTenantPacer> SHARED = new ConcurrentHashMap<>()

    /** One pacer per (OMS URL, tenant): the share is the tenant's, the bucket is the URL's. */
    static OmsGqlTenantPacer forKey(String url, String tenantId) {
        return SHARED.computeIfAbsent("${url ?: ''}|${tenantId ?: ''}".toString(), { new OmsGqlTenantPacer() })
    }

    static void resetShared() { SHARED.clear() }

    private final Closure<Long> clock
    /** Null until the first call: the bucket starts full at whatever capacity the governor then reports. */
    private Double tokens = null
    private long updatedAt = 0L
    /** The rate and capacity of the last call, so an answer's refund refills and clamps the same way. */
    private double lastRate = 1d
    private double lastCapacity = 0d

    OmsGqlTenantPacer(Closure<Long> clock = { -> System.currentTimeMillis() }) {
        this.clock = clock
    }

    /**
     * Milliseconds to wait before a call reserving `reservation`, and the reservation is taken. A share of 100 or more
     * is the whole bucket, which the governor already holds the URL to, so it never waits here.
     */
    synchronized long sleepMillisFor(int reservation, double urlRestoreRate, double urlMaximum, int sharePct) {
        if (sharePct >= 100) return 0L
        double share = Math.max(1, sharePct) / 100d
        double rate = Math.max(1d, urlRestoreRate * share)
        double capacity = urlMaximum * share
        if (tokens == null) {
            tokens = capacity
            updatedAt = (long) clock.call()
        }
        // Also when no time passed: a maximum learned since the last call shrinks what is held to the share of it.
        refill(rate, capacity)
        lastRate = rate
        lastCapacity = capacity
        long wait = tokens >= reservation ? 0L : (long) Math.ceil((reservation - tokens) / rate * 1000d)
        tokens = tokens - reservation
        return wait
    }

    /**
     * A read let go with `reservation` has its answer: what it did not cost comes back (it may cost more, which is taken).
     * An answer reporting no actual cost (an error, THROTTLED, a failed connection) gives the whole reservation back:
     * the read is asked again and pays again. Never holds more than the share. Before any call there is nothing to settle.
     */
    synchronized void settle(int reservation, Map extensions) {
        if (tokens == null) return
        Object actual = ((extensions?.get("cost") ?: [:]) as Map).get("actualQueryCost")
        double refund = actual instanceof Number ? reservation - ((Number) actual).doubleValue() : (double) reservation
        refill(lastRate, lastCapacity)
        tokens = Math.min(lastCapacity, tokens + refund)
    }

    private void refill(double rate, double capacity) {
        long now = (long) clock.call()
        if (now > updatedAt) tokens = tokens + (now - updatedAt) * rate / 1000d
        tokens = Math.min(capacity, tokens)
        updatedAt = Math.max(updatedAt, now)
    }
}
