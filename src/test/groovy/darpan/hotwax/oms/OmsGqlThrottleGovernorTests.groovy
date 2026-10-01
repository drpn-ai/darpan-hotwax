package darpan.hotwax.oms

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
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
    void reservingAfterSleepingAssumesTheBucketRefilledToTheReservation() {
        OmsGqlThrottleGovernor g = new OmsGqlThrottleGovernor()
        g.observe(extensionsWith(100))
        g.sleepMillisFor(750)
        assertEquals(750, g.getAvailable())
    }
}
