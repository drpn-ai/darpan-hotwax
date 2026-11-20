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
 * go, so queued callers line up. Pure: it returns a duration and the caller sleeps.
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
        long now = (long) clock.call()
        if (tokens == null) {
            tokens = capacity
        } else if (now > updatedAt) {
            tokens = tokens + (now - updatedAt) * rate / 1000d
        }
        // Also when no time passed: a maximum learned since the last call shrinks what is held to the share of it.
        tokens = Math.min(capacity, tokens)
        updatedAt = Math.max(updatedAt, now)
        long wait = tokens >= reservation ? 0L : (long) Math.ceil((reservation - tokens) / rate * 1000d)
        tokens = tokens - reservation
        return wait
    }
}
