package darpan.hotwax.oms

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals

/** DAR-BE-129. A by-id lookup is asked of the OMS once per read scope for a while, not once per page. */
class OmsGqlLookupCacheTests {

    private static final String SCOPE = "GORJANA|gorjana_prod|https://oms.test/rest/s1/graphql"

    @AfterEach
    void reset() { OmsGqlLookupCache.reset() }

    private static Closure fetcher(List<List<String>> asked, Map<String, Object> known) {
        return { List<String> ids -> asked.add(ids); known.subMap(ids.findAll { known.containsKey(it) }) }
    }

    @Test
    void aSecondPageAsksOnlyForTheIdsNotYetAnswered() {
        List<List<String>> asked = []
        Closure fetch = fetcher(asked, [P1: "SKU-1", P2: "SKU-2", P3: "SKU-3"])
        assertEquals([P1: "SKU-1", P2: "SKU-2"], OmsGqlLookupCache.lookup(SCOPE, OmsGqlLookupCache.PRODUCT_NAME, ["P1", "P2"], fetch))
        assertEquals([P2: "SKU-2", P3: "SKU-3"], OmsGqlLookupCache.lookup(SCOPE, OmsGqlLookupCache.PRODUCT_NAME, ["P2", "P3"], fetch))
        assertEquals([["P1", "P2"], ["P3"]], asked)
    }

    @Test
    void anIdTheOmsDidNotAnswerIsNotAskedAgain() {
        List<List<String>> asked = []
        Closure fetch = fetcher(asked, [:])
        assertEquals([:], OmsGqlLookupCache.lookup(SCOPE, OmsGqlLookupCache.PRODUCT_NETSUITE_ID, ["P9"], fetch))
        assertEquals([:], OmsGqlLookupCache.lookup(SCOPE, OmsGqlLookupCache.PRODUCT_NETSUITE_ID, ["P9"], fetch))
        assertEquals([["P9"]], asked)
    }

    /** An order's NetSuite id is what a fix changes, so a re-run after the fix must read it again. */
    @Test
    void anIdKindIsAskedAgainOnceItsMinutesHavePassed() {
        long now = 1_000_000L
        OmsGqlLookupCache.clock = { -> now }
        List<List<String>> asked = []
        Closure fetch = fetcher(asked, [M1: [orderId: "M1"]])
        OmsGqlLookupCache.lookup(SCOPE, OmsGqlLookupCache.ORDER, ["M1"], fetch)
        now += OmsGqlLookupCache.ID_TTL_MILLIS - 1
        OmsGqlLookupCache.lookup(SCOPE, OmsGqlLookupCache.ORDER, ["M1"], fetch)
        now += 1
        OmsGqlLookupCache.lookup(SCOPE, OmsGqlLookupCache.ORDER, ["M1"], fetch)
        assertEquals([["M1"], ["M1"]], asked)
    }

    @Test
    void twoReadScopesNeverShareAnAnswer() {
        List<List<String>> asked = []
        Closure fetch = fetcher(asked, [F1: "Chicago"])
        OmsGqlLookupCache.lookup(SCOPE, OmsGqlLookupCache.FACILITY_NAME, ["F1"], fetch)
        OmsGqlLookupCache.lookup("OTHER|cfg|https://oms.test/rest/s1/graphql", OmsGqlLookupCache.FACILITY_NAME, ["F1"], fetch)
        assertEquals([["F1"], ["F1"]], asked)
    }

    @Test
    void aClientWithAnInjectedSenderIsNeverCached() {
        OmsGqlClient client = new OmsGqlClient([baseUrl: "https://oms.test"], { String u, Map h, String b -> [:] })
        List<List<String>> asked = []
        Closure fetch = fetcher(asked, [P1: "SKU-1"])
        OmsGqlLookupCache.lookup(client, OmsGqlLookupCache.PRODUCT_NAME, ["P1", "P1"], fetch)
        OmsGqlLookupCache.lookup(client, OmsGqlLookupCache.PRODUCT_NAME, ["P1"], fetch)
        assertEquals([["P1"], ["P1"]], asked)
    }
}
