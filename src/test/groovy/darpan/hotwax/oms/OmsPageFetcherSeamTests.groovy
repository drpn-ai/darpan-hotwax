package darpan.hotwax.oms

import groovy.json.JsonSlurper
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-064 Task 5. With a pageFetcher the REST pipeline — counters, exchange manifest, filter
 * metadata, the streaming sink, the abort-on-error — runs over pages the fetcher supplies. That is
 * what makes the GraphQL extract's output contract identical by construction.
 */
class OmsPageFetcherSeamTests {

    private static final Map CONFIG = [baseUrl: "https://x.test", authType: "NONE", omsRestSourceConfigId: "T1"]

    private static Map raw(String id, Map extra = [:]) {
        return [orderId: id, orderName: "#" + id, externalId: "SH" + id, statusId: "ORDER_COMPLETED",
                orderTypeId: "SALES_ORDER", orderDate: "2026-09-24T17:00:00Z", grandTotal: 10] + extra
    }

    /** A fetcher that delivers the given raw pages through the real shaper. */
    private static Closure fetcherOf(List<List<Map>> pages, List seenCtx = null) {
        return { Map ctx ->
            seenCtx?.add(ctx)
            pages.each { List<Map> page ->
                Map bundle = OmsRestSourceSupport.shapePageRecords(page, (Map) ctx.extractOptions,
                        (List) ctx.excludeRules, (Set) ctx.keepFieldSet) + [rawCount: page.size()]
                ((Closure) ctx.pageConsumer).call(bundle)
            }
            return [errors: [], statusCode: 200, attemptCount: pages.size(), transport: "GRAPHQL",
                    pagination: [strategy: "GRAPHQL_CURSOR", pageCount: pages.size()]]
        }
    }

    @Test
    void fetchedPagesFlowThroughTheRestCountersSinkAndMetadata() {
        File out = File.createTempFile("seam-", ".json")
        try {
            Map result = OmsRestSourceSupport.extractOrdersToFile(CONFIG, "2026-09-24T16:00:00Z", "2026-09-24T20:00:00Z",
                    out, ["orderId", "externalId"], null, null, null, fetcherOf([
                    [raw("M1"), raw("M2", [orderItemAssocs: [[orderItemAssocTypeId: "EXCHANGE", toOrderId: "M0"]]])],
                    [raw("M3", [orderTypeId: "TRANSFER_ORDER"]), raw("M4")]]))

            assertEquals([], result.errors)
            assertEquals(2, result.recordCount)
            assertEquals(1, ((List) result.exchangeManifest).size())
            Map doc = new JsonSlurper().parse(out) as Map
            assertEquals(["M1", "M4"], ((List<Map>) doc.records)*.orderId)
            Map filters = (Map) ((Map) doc.metadata).filters
            assertEquals(1, filters.excludedExchangeOrderCount)
            assertEquals(1, filters.excludedNonSalesOrderCount)
            assertEquals("GRAPHQL", ((Map) doc.metadata).transport)
        } finally { out.delete() }
    }

    @Test
    void theFetcherReceivesTheParsedWindowRulesAndProjection() {
        List seen = []
        File out = File.createTempFile("seam-", ".json")
        try {
            OmsRestSourceSupport.extractOrdersToFile(CONFIG, "2026-09-24T16:00:00Z", "2026-09-24T20:00:00Z", out,
                    ["orderId"], null, [[fieldExpression: "productStoreId", operator: "EXCLUDE_IN", filterValues: "X", sequenceNum: 1]],
                    null, fetcherOf([], seen))
            Map ctx = (Map) seen[0]
            assertEquals(java.time.Instant.parse("2026-09-24T16:00:00Z").toEpochMilli(), ctx.fromMillis)
            assertEquals(["orderId"] as Set, ctx.keepFieldSet)
            assertEquals("productStoreId", ((Map) ((List) ctx.excludeRules)[0]).fieldExpression)
        } finally { out.delete() }
    }

    @Test
    void aFetcherErrorWritesNoFileAndReportsTheError() {
        File out = File.createTempFile("seam-", ".json")
        out.delete()
        Closure failing = { Map ctx ->
            ((Closure) ctx.pageConsumer).call(OmsRestSourceSupport.shapePageRecords([raw("M1")], null, null, null) + [rawCount: 1])
            return [errors: ["OMS GraphQL extract failed [THROTTLED]: throttled"], statusCode: 200, attemptCount: 4,
                    pagination: [:], transport: "GRAPHQL"]
        }
        Map result = OmsRestSourceSupport.extractOrdersToFile(CONFIG, "2026-09-24T16:00:00Z", "2026-09-24T20:00:00Z",
                out, null, null, null, null, failing)
        assertTrue(((List) result.errors)[0].toString().contains("THROTTLED"))
        assertEquals(false, result.dataAvailable)
        assertFalse(out.exists(), "a failed extract must not leave a partial file where reconciliation could read it")
    }
}
