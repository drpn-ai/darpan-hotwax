package darpan.hotwax.oms

import groovy.json.JsonSlurper
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-040 fix 3. The returns extractor runs its page source's records (the GraphQL transport) through one
 * pipeline: the join-key fallback, projection, the sink and the metadata. DAR-BE-318 (Task 18): no client filters; a run's
 * Keep runs in darpan's Spark over the file.
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
                null, null, [pageFetcher: fetcher([[pending("M1", "37447008387")]])])
        assertEquals([], result.errors)
        assertEquals("37447008387", ((List<Map>) result.records)[0].externalId,
                "blank externalId takes shopifyReturnId")
    }

    /**
     * DAR-BE-318 (Task 18): every return the page source hands over is written, whatever its channel, store or class; the
     * projection keeps the asked fields, items and the class; the metadata names no rule.
     */
    @Test
    void everyReturnIsWrittenAndProjectedWithItsClass() {
        Map result = OmsReturnsSourceSupport.extractReturns(CONFIG, "2026-10-01T00:00:00Z", "2026-10-02T00:00:00Z",
                ["returnId", "externalId", "orderProductStoreId"], null,
                [pageFetcher: fetcher([[pending("M1", "111", [orderProductStoreId: "STORE_US", omsReturnClass: "KEEP"]),
                                        pending("M2", "222", [returnChannelEnumId: "POS_RTN_CHANNEL", omsReturnClass: "KEEP"]),
                                        pending("M3", null, [statusId: "RETURN_CANCELLED", omsReturnClass: "CANCELLED"])]])])
        assertEquals([], result.errors)
        assertEquals(["M1", "M2", "M3"], ((List<Map>) result.records)*.returnId)
        assertEquals(["returnId", "externalId", "items", "orderProductStoreId", "omsReturnClass"],
                ((List<Map>) result.records)[0].keySet() as List, "the asked fields, items and the class, in the record's order")
        assertEquals(["returnId", "externalId", "items", "omsReturnClass"], ((List<Map>) result.records)[1].keySet() as List)
        assertEquals("CANCELLED", ((List<Map>) result.records)[2].omsReturnClass)
        assertFalse(((Map) ((Map) result.requestMetadata).filters).containsKey("configuredExclusions"), "the read applies no rule")
    }

    @Test
    void theFetchersCountsReachTheMetadata() {
        Map result = OmsReturnsSourceSupport.extractReturns(CONFIG, "2026-10-01T00:00:00Z", "2026-10-02T00:00:00Z",
                null, null, [pageFetcher: fetcher([[pending("M1", "111")]])])
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
                null, null, [:])
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
                target, null, null, [pageFetcher: failing])
        assertTrue((result.errors as List).any { (it as String).contains("THROTTLED") })
        assertFalse(target.exists(), "a partial extract must not stay on disk looking like a real one")
    }

    @Test
    void theFileCarriesTheGraphQlRecords() {
        File target = File.createTempFile("oms-returns-seam-", ".json")
        Map result = OmsReturnsSourceSupport.extractReturnsToFile(CONFIG, "2026-10-01T00:00:00Z", "2026-10-02T00:00:00Z",
                target, null, null, [pageFetcher: fetcher([[pending("M1", "111")], [pending("M2", "222")]])])
        assertEquals([], result.errors)
        assertEquals(2, result.recordCount)
        Map doc = (Map) new JsonSlurper().parse(target)
        assertEquals(["M1", "M2"], ((List<Map>) doc.records)*.returnId)
        assertNull(result.records, "extractReturnsToFile keeps its contract: no records key")
        target.delete()
    }
}
