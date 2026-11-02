package darpan.hotwax.oms

import groovy.json.JsonSlurper
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-040 fix 3. The returns extractor runs its page source's records (the GraphQL transport) through one
 * pipeline: client filters, the join-key fallback, projection, the sink and the metadata.
 */
class OmsReturnsPageFetcherSeamTests {

    private static final Map CONFIG = [omsRestSourceConfigId: "TEST_CFG", baseUrl: "https://oms.example.com",
                                       apiKey: "test-key", isActive: "Y"]

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
                "blank externalId takes shopifyReturnId")
    }

    @Test
    void projectionAndFiltersApply() {
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
    void anIncludeRuleOnTheOriginOrderStoreKeepsOnlyThatStore() {
        // DAR-BE-068: the store pill filters before projection, so it works without being in keepFieldsBase.
        List filters = [[sequenceNum: 1, fieldExpression: "orderProductStoreId", operator: "INCLUDE_IN", values: ["STORE_US"]]]
        Map result = OmsReturnsSourceSupport.extractReturns(CONFIG, "2026-10-01T00:00:00Z", "2026-10-02T00:00:00Z",
                ["returnId", "externalId"], null, filters,
                [pageFetcher: fetcher([[pending("M1", "111", [orderProductStoreId: "STORE_US"]),
                                        pending("M2", "222", [orderProductStoreId: "STORE_CA"]),
                                        pending("M3", "333")]])])
        assertEquals([], result.errors)
        assertEquals(["M1"], ((List<Map>) result.records)*.returnId)
        assertFalse(((List<Map>) result.records)[0].containsKey("orderProductStoreId"), "projection strips it")
        Map rule = ((List<Map>) ((Map) ((Map) result.requestMetadata).filters).configuredExclusions)[0]
        assertEquals(1, rule.excludedCount, "M2: another store")
        assertEquals(1, rule.fieldAbsentCount, "M3: no origin order, so no store")
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
    void anExtractWithoutAPageSourceIsRefused() {
        Map result = OmsReturnsSourceSupport.extractReturns(CONFIG, "2026-10-01T00:00:00Z", "2026-10-02T00:00:00Z",
                null, null, null, [:])
        assertTrue((result.errors as List).any { (it as String).contains("GraphQL page source") }, result.errors.toString())
    }

    @Test
    void aRealExternalIdIsNeverOverwrittenAndNeitherIdIsInvented() {
        Map refunded = [externalId: "REFUND_1", shopifyReturnId: "RETURN_1"]
        OmsReturnsSourceSupport.applyJoinKeyFallback(refunded)
        assertEquals("REFUND_1", refunded.externalId)
        Map neither = [externalId: null, shopifyReturnId: null]
        OmsReturnsSourceSupport.applyJoinKeyFallback(neither)
        assertNull(neither.externalId)
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
