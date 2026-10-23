package darpan.hotwax.oms

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-BE-064 / DAR-BE-052. The bucket is the only thing between a drained budget and an extract that writes zero records and calls itself clean. */
class OmsGqlThrottleGovernorTests {

    private static Map extensionsWith(int available, int restoreRate = 50) {
        return [cost: [requestedQueryCost: 100, actualQueryCost: 100,
                       throttleStatus: [maximumAvailable: 1000, currentlyAvailable: available,
                                        restoreRate: restoreRate]]]
    }

    @Test
    void beforeAnyResponseNothingIsKnownSoNoSleepIsImposed() {
        assertEquals(0L, new OmsGqlThrottleGovernor().sleepMillisFor(1000))
    }

    @Test
    void aFullBucketNeedsNoSleep() {
        OmsGqlThrottleGovernor g = new OmsGqlThrottleGovernor()
        g.observe(extensionsWith(1000))
        assertEquals(0L, g.sleepMillisFor(750))
    }

    @Test
    void aDrainedBucketSleepsLongEnoughToRefillTheReservation() {
        OmsGqlThrottleGovernor g = new OmsGqlThrottleGovernor()
        g.observe(extensionsWith(100))
        // needs 650 more at 50/s = 13s, plus the 500ms safety margin
        assertEquals(13500L, g.sleepMillisFor(750))
    }

    @Test
    void aZeroRestoreRateDoesNotDivideByZero() {
        OmsGqlThrottleGovernor g = new OmsGqlThrottleGovernor()
        g.observe(extensionsWith(0, 0))
        assertTrue(g.sleepMillisFor(750) > 0L)
    }

    @Test
    void anExtensionsBlockWithoutThrottleStatusLeavesTheLastKnownStateAlone() {
        OmsGqlThrottleGovernor g = new OmsGqlThrottleGovernor()
        g.observe(extensionsWith(400))
        g.observe([cost: [actualQueryCost: 10]])
        assertEquals(400, g.getAvailable())
    }

    @Test
    void reservingAfterSleepingSpendsTheRefilledReservation() {
        OmsGqlThrottleGovernor g = new OmsGqlThrottleGovernor()
        g.observe(extensionsWith(100))
        g.sleepMillisFor(750)
        assertEquals(0, g.getAvailable())
    }

    /** DAR-BE-116: parallel slices share one bucket, so a call that goes without waiting spends its reservation. */
    @Test
    void aReservationThatGoesAtOnceIsSpentSoTheNextCallerSeesWhatIsLeft() {
        OmsGqlThrottleGovernor g = new OmsGqlThrottleGovernor()
        g.observe(extensionsWith(1000))
        assertEquals(0L, g.sleepMillisFor(750))
        assertEquals(250, g.getAvailable())
        assertTrue(g.sleepMillisFor(750) > 0L, "only 250 left, so the second caller waits")
    }

    /** DAR-BE-116 live: a response reports the bucket as it was before our other in-flight calls landed. */
    @Test
    void anObservationNeverRaisesTheEstimateAboveWhatInFlightCallsLeft() {
        long now = 1_000_000L
        OmsGqlThrottleGovernor g = new OmsGqlThrottleGovernor({ -> now })
        g.observe(extensionsWith(1000, 2000))
        assertEquals(0L, g.sleepMillisFor(800))     // 200 left
        g.observe(extensionsWith(900, 2000))        // an older response landing late
        assertEquals(200, g.getAvailable())
    }

    @Test
    void theBucketRefillsAtTheServersRateBetweenCalls() {
        long now = 1_000_000L
        OmsGqlThrottleGovernor g = new OmsGqlThrottleGovernor({ -> now })
        g.observe(extensionsWith(0, 2000))
        now += 500L                                 // half a second at 2000/s
        assertEquals(0L, g.sleepMillisFor(900))
        assertEquals(100, g.getAvailable())
    }

    /** DAR-BE-116 live: an overdrawn bucket reports a NEGATIVE balance (-2967, -5102). That is a debt, not "unknown". */
    @Test
    void aNegativeBalanceIsWaitedOffNotTreatedAsNothingObserved() {
        long now = 1_000_000L
        OmsGqlThrottleGovernor g = new OmsGqlThrottleGovernor({ -> now })
        g.observe(extensionsWith(-3000, 2000))
        // 3000 of debt + a 1000 reservation at 2000/s = 2s, plus the 500ms margin
        assertEquals(2500L, g.sleepMillisFor(1000))
    }

    @Test
    void oneOmsUrlIsOneBucket() {
        OmsGqlThrottleGovernor.resetShared()
        OmsGqlThrottleGovernor a = OmsGqlThrottleGovernor.forUrl("https://a.test/rest/s1/graphql")
        assertTrue(a.is(OmsGqlThrottleGovernor.forUrl("https://a.test/rest/s1/graphql")))
        assertFalse(a.is(OmsGqlThrottleGovernor.forUrl("https://b.test/rest/s1/graphql")))
        OmsGqlThrottleGovernor.resetShared()
    }
}
