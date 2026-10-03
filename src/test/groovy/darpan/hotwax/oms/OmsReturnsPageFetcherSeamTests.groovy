package darpan.hotwax.oms

import groovy.json.JsonSlurper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-040 fix 3. The returns extractor takes an optional page fetcher (the GraphQL transport) and runs
 * its records through EXACTLY the REST pipeline: client filters, the join-key fallback, projection,
 * the sink and the metadata. Nothing downstream may tell the transports apart except `transport`.
 */
class OmsReturnsPageFetcherSeamTests {

    private static final Map CONFIG = [omsRestSourceConfigId: "TEST_CFG", baseUrl: "https://oms.example.com",
                                       apiKey: "test-key", isActive: "Y"]

    @BeforeEach
    void restMustNotBeCalled() {
        OmsReturnsSourceSupport.setHttpClient { Map request -> throw new AssertionError("REST called on the GraphQL path: ${request.url}") }
    }

    @AfterEach
    void reset() { OmsReturnsSourceSupport.resetHttpClient() }

    private static Map pending(String id, String shopifyReturnId, Map o = [:]) {
        return [returnId: id, shopifyReturnId: shopifyReturnId, externalId: null, orderExternalId: "7186636636291",
                statusId: "RETURN_REQUESTED", entryDate: 1790949840688L, returnTotal: 65, currencyUomId: "USD",
                returnChannelEnumId: "ADMIN_RTN_CHANNEL", items: []] + o
    }

    private static Closure fetcher(List<List<Map>> pages, Map serverCounts = [returnsCount: 2, excludedNoShopifyRefCount: 5, excludedCancelledCount: 1]) {
        return { Map ctx ->
            assert ctx.fromMillis != null && ctx.thruMillis != null
            pages.each { ((Closure) ctx.pageConsumer).call(it) }
            return [errors: [], warnings: [], serverCounts: serverCounts, transport: "GRAPHQL"]
        }
    }

    @Test
    void aPendingReturnGetsItsJoinKeyFromShopifyReturnId() {
        Map result = OmsReturnsSourceSupport.extractReturns(CONFIG, "2026-10-01T00:00:00Z", "2026-10-02T00:00:00Z",
                null, null, null, [pageFetcher: fetcher([[pending("M1", "37447008387")]])])
        assertEquals([], result.errors)
        assertEquals("37447008387", ((List<Map>) result.records)[0].externalId,
                "the same fallback REST records get: blank externalId takes shopifyReturnId")
    }

    @Test
    void projectionAndFiltersApplyAsOnRest() {
        List filters = [[sequenceNum: 1, fieldExpression: "returnChannelEnumId", operator: "EXCLUDE_IN", values: ["POS_RTN_CHANNEL"]]]
        Map result = OmsReturnsSourceSupport.extractReturns(CONFIG, "2026-10-01T00:00:00Z", "2026-10-02T00:00:00Z",
                ["returnId", "externalId"], null, filters,
                [pageFetcher: fetcher([[pending("M1", "111"), pending("M2", "222", [returnChannelEnumId: "POS_RTN_CHANNEL"])]])])
        assertEquals(["M1"], ((List<Map>) result.records)*.returnId)
        assertEquals(["returnId", "externalId", "items"] as Set, ((List<Map>) result.records)[0].keySet())
        Map filtersMeta = (Map) ((Map) result.requestMetadata).filters
        assertEquals(1, ((List<Map>) filtersMeta.configuredExclusions)[0].excludedCount)
    }

    @Test
    void theFetchersCountsReachTheMetadata() {
        Map result = OmsReturnsSourceSupport.extractReturns(CONFIG, "2026-10-01T00:00:00Z", "2026-10-02T00:00:00Z",
                null, null, null, [pageFetcher: fetcher([[pending("M1", "111")]])])
        Map meta = (Map) result.requestMetadata
        Map filters = (Map) meta.filters
        assertEquals(5, filters.excludedNoShopifyRefCount)
        assertEquals(2, filters.serverReportedReturnsCount)
        assertEquals(1, filters.excludedCancelledCount)
        assertEquals("GRAPHQL", meta.transport)
    }

    @Test
    void theRestPathCarriesNoTransportOrCancelledKey() {
        // Regression guard: REST metadata is unchanged by the seam.
        OmsReturnsSourceSupport.setHttpClient { Map request ->
            [statusCode: 200, body: '{"returns":[],"returnsCount":0,"hasMore":false,"excludedNoShopifyRefCount":0}']
        }
        Map meta = (Map) OmsReturnsSourceSupport.extractReturns(CONFIG, "2026-10-01T00:00:00Z", "2026-10-02T00:00:00Z",
                null, null, null, [:]).requestMetadata
        assertFalse(meta.containsKey("transport"))
        assertFalse(((Map) meta.filters).containsKey("excludedCancelledCount"))
    }

    @Test
    void aFetcherErrorFailsTheExtractAndLeavesNoFile() {
        File target = File.createTempFile("oms-returns-seam-", ".json")
        Closure failing = { Map ctx ->
            ((Closure) ctx.pageConsumer).call([pending("M1", "111")])
            return [errors: ["OMS GraphQL returns extract failed [THROTTLED]: x"], warnings: [], serverCounts: [:], transport: "GRAPHQL"]
        }
        Map result = OmsReturnsSourceSupport.extractReturnsToFile(CONFIG, "2026-10-01T00:00:00Z", "2026-10-02T00:00:00Z",
                target, null, null, null, [pageFetcher: failing])
        assertTrue((result.errors as List).any { (it as String).contains("THROTTLED") })
        assertFalse(target.exists(), "a partial extract must not stay on disk looking like a real one")
    }

    @Test
    void theFileCarriesTheGraphQlRecords() {
        File target = File.createTempFile("oms-returns-seam-", ".json")
        Map result = OmsReturnsSourceSupport.extractReturnsToFile(CONFIG, "2026-10-01T00:00:00Z", "2026-10-02T00:00:00Z",
                target, null, null, null, [pageFetcher: fetcher([[pending("M1", "111")], [pending("M2", "222")]])])
        assertEquals([], result.errors)
        assertEquals(2, result.recordCount)
        Map doc = (Map) new JsonSlurper().parse(target)
        assertEquals(["M1", "M2"], ((List<Map>) doc.records)*.returnId)
        assertNull(result.records, "extractReturnsToFile keeps its contract: no records key")
        target.delete()
    }
}
