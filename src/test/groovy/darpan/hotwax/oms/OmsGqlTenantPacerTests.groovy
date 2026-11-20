package darpan.hotwax.oms

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNotSame
import static org.junit.jupiter.api.Assertions.assertSame
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-BE-328. A tenant in a peak period spends at most its share of the HotWax bucket's restore rate. */
class OmsGqlTenantPacerTests {

    private static final String URL = "https://gorjana-maarg.hotwax.io/rest/s1/graphql"

    @AfterEach
    void reset() { OmsGqlTenantPacer.resetShared() }

    @Test
    void fullBucketLetsTheFirstCallThrough() {
        OmsGqlTenantPacer p = new OmsGqlTenantPacer({ -> 0L })
        // share 25 of max 1000 = capacity 250, starting full
        assertEquals(0L, p.sleepMillisFor(200, 50d, 1000d, 25))
    }

    @Test
    void refillsAtTheSharedRate() {
        OmsGqlTenantPacer p = new OmsGqlTenantPacer({ -> 0L })
        assertEquals(0L, p.sleepMillisFor(250, 50d, 1000d, 25))
        // 25% of 50/s = 12.5/s, so 100 more takes 8 s
        assertEquals(8000L, p.sleepMillisFor(100, 50d, 1000d, 25))
    }

    @Test
    void timePassingRefillsUpToTheShareOfTheMaximumOnly() {
        long now = 0L
        OmsGqlTenantPacer p = new OmsGqlTenantPacer({ -> now })
        assertEquals(0L, p.sleepMillisFor(250, 50d, 1000d, 25))
        now = 8000L                                     // 100 back at 12.5/s
        assertEquals(0L, p.sleepMillisFor(100, 50d, 1000d, 25))
        now = 1_000_000L                                // a long idle spell refills to 250, not beyond
        assertEquals(0L, p.sleepMillisFor(250, 50d, 1000d, 25))
        assertEquals(800L, p.sleepMillisFor(10, 50d, 1000d, 25))
    }

    @Test
    void queuedCallersLineUpBehindOneAnother() {
        OmsGqlTenantPacer p = new OmsGqlTenantPacer({ -> 0L })
        assertEquals(0L, p.sleepMillisFor(250, 50d, 1000d, 25))
        assertEquals(20000L, p.sleepMillisFor(250, 50d, 1000d, 25))
        assertEquals(40000L, p.sleepMillisFor(250, 50d, 1000d, 25))
    }

    @Test
    void separateTenantsOnOneUrlDoNotShare() {
        OmsGqlTenantPacer a = OmsGqlTenantPacer.forKey(URL, "TENANT_A")
        assertSame(a, OmsGqlTenantPacer.forKey(URL, "TENANT_A"))
        OmsGqlTenantPacer b = OmsGqlTenantPacer.forKey(URL, "TENANT_B")
        assertNotSame(a, b)
        assertEquals(0L, a.sleepMillisFor(250, 50d, 1000d, 25))
        assertTrue(a.sleepMillisFor(250, 50d, 1000d, 25) > 0L, "A has drained its share")
        assertEquals(0L, b.sleepMillisFor(250, 50d, 1000d, 25), "B's share is its own and still full")
    }

    @Test
    void shareOfOneHundredNeverWaitsLongerThanTheGovernor() {
        OmsGqlTenantPacer p = new OmsGqlTenantPacer({ -> 0L })
        // 100% is the whole bucket: the governor already holds it, so the pacer adds nothing however much is spent.
        assertEquals(0L, p.sleepMillisFor(1000, 50d, 1000d, 100))
        assertEquals(0L, p.sleepMillisFor(1000, 50d, 1000d, 100))
    }

    @Test
    void aMaximumLearnedLaterShrinksTheBucketToTheShare() {
        OmsGqlTenantPacer p = new OmsGqlTenantPacer({ -> 0L })
        // Before the URL's first answer the governor knows no maximum; the pacer does not block on it.
        assertEquals(0L, p.sleepMillisFor(250, 50d, Double.MAX_VALUE, 25))
        // Once the maximum is known the bucket holds at most 25% of it: 250 is spent, 250 more waits 20 s.
        assertEquals(0L, p.sleepMillisFor(250, 50d, 1000d, 25))
        assertEquals(20000L, p.sleepMillisFor(250, 50d, 1000d, 25))
    }

    @Test
    void aZeroRestoreRateDoesNotDivideByZero() {
        OmsGqlTenantPacer p = new OmsGqlTenantPacer({ -> 0L })
        assertEquals(0L, p.sleepMillisFor(250, 0d, 1000d, 25))
        assertTrue(p.sleepMillisFor(250, 0d, 1000d, 25) > 0L)
    }
}
