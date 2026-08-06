package darpan.hotwax.oms

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * Streaming contract for extractOrdersToFile: each committed page is appended to the target file
 * and flushed before the next page is fetched, nothing whole-window is buffered in the result,
 * and failed extractions never leave a partial file behind.
 */
class OmsRestSourceStreamingTests {
    private static final JsonSlurper JSON_SLURPER = new JsonSlurper()

    @TempDir
    File tempDir

    @AfterEach
    void resetClient() {
        OmsRestSourceSupport.resetHttpClient()
    }

    @Test
    void streamsCommittedPagesToDiskBeforeFetchingTheNextPage() {
        File target = new File(tempDir, "oms-orders.json")
        Map<Integer, Long> fileLengthAtRequest = [:]
        OmsRestSourceSupport.setHttpClient { Map request ->
            int pageIndex = queryInt(request.url as String, "pageIndex")
            fileLengthAtRequest[pageIndex] = target.length()
            List<List> pages = [salesOrders("O1", "O2"), salesOrders("O3", "O4"), salesOrders("O5", "O6"), salesOrders("O7")]
            List orders = pageIndex < pages.size() ? pages[pageIndex] : []
            return [statusCode: 200, body: JsonOutput.toJson([orders: orders])]
        }

        // Pin sequential mode: the on-disk cadence assertions below describe sequential fetching.
        Map result = OmsRestSourceSupport.extractOrdersToFile(baseConfig([ordersPageSize: 2, ordersFetchConcurrency: 1]),
                "2026-05-01T00:00:00Z", "2026-05-01T01:00:00Z", target)

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertEquals(7, result.recordCount)
        assertTrue(result.dataAvailable as boolean)

        // Pages 0+1 are held only until the pagination strategy commits; by the time page 2 is
        // requested they must already be flushed to disk, and page 2 must be on disk before page 3.
        assertTrue(fileLengthAtRequest[2] > 0L, "pages 0+1 not on disk before page 2 fetch: ${fileLengthAtRequest}")
        assertTrue(fileLengthAtRequest[3] > fileLengthAtRequest[2],
                "page 2 not on disk before page 3 fetch: ${fileLengthAtRequest}")

        Map output = JSON_SLURPER.parseText(target.getText("UTF-8")) as Map
        assertEquals(["O1", "O2", "O3", "O4", "O5", "O6", "O7"], ((List) output.records).collect { it.externalId })
        assertEquals(7, output.metadata.extractedRecordCount)
        assertEquals("pageIndexPageSize", output.metadata.pagination.strategy)

        // The streaming variant must not hand the whole window back in memory.
        assertNull(result.outputText)
        assertTrue(((List) (result.records ?: [])).isEmpty())
    }

    @Test
    void fileContentMatchesLegacyInMemoryOutputText() {
        Closure fakePages = { Map request ->
            int pageIndex = queryInt(request.url as String, "pageIndex")
            List<List> pages = [salesOrders("O100", "O200"), salesOrders("O300")]
            List orders = pageIndex < pages.size() ? pages[pageIndex] : []
            return [statusCode: 200, body: JsonOutput.toJson([orders: orders])]
        }

        OmsRestSourceSupport.setHttpClient(fakePages)
        Map legacy = OmsRestSourceSupport.extractOrders(baseConfig([ordersPageSize: 2]),
                "2026-05-01T00:00:00Z", "2026-05-01T01:00:00Z")
        assertTrue(legacy.errors.isEmpty(), legacy.errors.toString())

        File target = new File(tempDir, "parity.json")
        OmsRestSourceSupport.setHttpClient(fakePages)
        Map streamed = OmsRestSourceSupport.extractOrdersToFile(baseConfig([ordersPageSize: 2]),
                "2026-05-01T00:00:00Z", "2026-05-01T01:00:00Z", target)

        assertTrue(streamed.errors.isEmpty(), streamed.errors.toString())
        assertEquals(legacy.outputText, target.getText("UTF-8"))
        assertEquals(legacy.recordCount, streamed.recordCount)
    }

    @Test
    void filtersEachPageAndAggregatesExcludedCounts() {
        OmsRestSourceSupport.setHttpClient { Map request ->
            int pageIndex = queryInt(request.url as String, "pageIndex")
            List orders = pageIndex == 0 ? [
                    [externalId: "KEEP1", orderTypeId: "SALES_ORDER"],
                    [externalId: "EXCH1", orderTypeId: "SALES_ORDER", orderItemAssocs: [[orderItemAssocTypeId: "EXCHANGE"]]],
                    [externalId: "RET1", orderTypeId: "RETURN_ORDER"],
            ] : pageIndex == 1 ? [
                    [externalId: "KEEP2", orderTypeId: "SALES_ORDER"],
                    [externalId: "EXCH2", orderTypeId: "SALES_ORDER", orderItemAssocs: [[orderItemAssocTypeId: "EXCHANGE"]]],
                    [externalId: "RET2", orderTypeId: "RETURN_ORDER"],
            ] : []
            return [statusCode: 200, body: JsonOutput.toJson([orders: orders])]
        }

        File target = new File(tempDir, "filtered.json")
        Map result = OmsRestSourceSupport.extractOrdersToFile(baseConfig([ordersPageSize: 3]),
                "2026-05-01T00:00:00Z", "2026-05-01T01:00:00Z", target)

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertEquals(2, result.recordCount)
        assertEquals(2, result.requestMetadata.filters.excludedNonSalesOrderCount)
        assertEquals(2, result.requestMetadata.filters.excludedExchangeOrderCount)

        Map output = JSON_SLURPER.parseText(target.getText("UTF-8")) as Map
        assertEquals(["KEEP1", "KEEP2"], ((List) output.records).collect { it.externalId })
        assertEquals(2, output.metadata.extractedRecordCount)
        assertEquals(2, output.metadata.filters.excludedNonSalesOrderCount)
        assertEquals(2, output.metadata.filters.excludedExchangeOrderCount)
        assertFalse(target.getText("UTF-8").contains("EXCH1"))
        assertFalse(target.getText("UTF-8").contains("RET2"))
    }

    @Test
    void deletesPartialFileWhenPaginationFailsMidWindow() {
        File target = new File(tempDir, "failing.json")
        long lengthAtFailingRequest = -1L
        OmsRestSourceSupport.setHttpClient { Map request ->
            int pageIndex = queryInt(request.url as String, "pageIndex")
            if (pageIndex >= 2) {
                lengthAtFailingRequest = target.length()
                return [statusCode: 500, body: '{"error":"boom"}']
            }
            List orders = pageIndex == 0 ? salesOrders("O1", "O2") : salesOrders("O3", "O4")
            return [statusCode: 200, body: JsonOutput.toJson([orders: orders])]
        }

        Map result = OmsRestSourceSupport.extractOrdersToFile(baseConfig([ordersPageSize: 2, ordersFetchConcurrency: 1]),
                "2026-05-01T00:00:00Z", "2026-05-01T01:00:00Z", target)

        assertTrue(result.errors.any { it.contains("status 500") }, result.errors.toString())
        assertEquals(0, result.recordCount)
        assertFalse(result.dataAvailable as boolean)
        // Committed pages really were on disk when the failure hit — and were cleaned up after.
        assertTrue(lengthAtFailingRequest > 0L)
        assertFalse(target.exists(), "partial extract file must be deleted on failure")
    }

    @Test
    void deletesFileWhenMaxPageCountExceeded() {
        File target = new File(tempDir, "truncated.json")
        OmsRestSourceSupport.setHttpClient { Map request ->
            int pageIndex = queryInt(request.url as String, "pageIndex")
            return [statusCode: 200, body: JsonOutput.toJson([orders: salesOrders("P${pageIndex}A", "P${pageIndex}B")])]
        }

        Map result = OmsRestSourceSupport.extractOrdersToFile(baseConfig([ordersPageSize: 2, maxOrdersPageCount: 3]),
                "2026-05-01T00:00:00Z", "2026-05-01T01:00:00Z", target)

        assertTrue(result.errors.any { it.contains("exceeded 3 pages") }, result.errors.toString())
        assertTrue(result.requestMetadata.pagination.truncated as boolean)
        assertFalse(target.exists(), "truncated extract file must be deleted")
    }

    @Test
    void trimsRecordsToKeepFieldsBeforeWriting() {
        OmsRestSourceSupport.setHttpClient { Map ignored ->
            [statusCode: 200, body: JsonOutput.toJson([orders: [
                    [orderId: "OID1", orderName: "GOR-1", externalId: "E1", orderTypeId: "SALES_ORDER",
                     grandTotal: 42.5, orderDate: 1784955159000L, statusId: "ORDER_COMPLETED",
                     shipGroups: [[huge: "blob"]], contactMechs: [[addr: "..."]], roles: [[r: 1]]],
                    [orderId: "OID2", externalId: "E2", orderTypeId: "SALES_ORDER",
                     orderItemAssocs: [[orderItemAssocTypeId: "EXCHANGE"]], shipGroups: [[huge: "blob"]]],
            ]])]
        }

        File target = new File(tempDir, "trimmed.json")
        Map result = OmsRestSourceSupport.extractOrdersToFile(baseConfig(),
                "2026-05-01T00:00:00Z", "2026-05-01T01:00:00Z", target,
                ["orderId", "orderName", "externalId", "grandTotal", "orderDate", "statusId"])

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        // The exchange filter still sees the full record (its assoc field is outside the keep list).
        assertEquals(1, result.recordCount)
        assertEquals(1, result.requestMetadata.filters.excludedExchangeOrderCount)

        Map output = JSON_SLURPER.parseText(target.getText("UTF-8")) as Map
        Map record = ((List) output.records)[0] as Map
        assertEquals(["orderId", "orderName", "externalId", "grandTotal", "orderDate", "statusId"] as Set, record.keySet())
        assertEquals("E1", record.externalId)
        assertFalse(target.getText("UTF-8").contains("shipGroups"))
        assertEquals(["externalId", "grandTotal", "orderDate", "orderId", "orderName", "statusId"],
                output.metadata.projection.keepRecordFields)
    }

    @Test
    void keepsFullRecordsWhenNoKeepFieldsGiven() {
        OmsRestSourceSupport.setHttpClient { Map ignored ->
            [statusCode: 200, body: '{"orders":[{"orderId":"O1","orderTypeId":"SALES_ORDER","shipGroups":[{"a":1}]}]}']
        }

        File target = new File(tempDir, "untrimmed.json")
        Map result = OmsRestSourceSupport.extractOrdersToFile(baseConfig(),
                "2026-05-01T00:00:00Z", "2026-05-01T01:00:00Z", target)

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertTrue(target.getText("UTF-8").contains("shipGroups"))
        Map output = JSON_SLURPER.parseText(target.getText("UTF-8")) as Map
        assertFalse(((Map) output.metadata).containsKey("projection"))
    }

    @Test
    void writesValidEmptyDocumentWhenNoOrders() {
        OmsRestSourceSupport.setHttpClient { Map ignored -> [statusCode: 200, body: '{"orders":[]}'] }

        File target = new File(tempDir, "empty.json")
        Map result = OmsRestSourceSupport.extractOrdersToFile(baseConfig(),
                "2026-05-01T00:00:00Z", "2026-05-01T01:00:00Z", target)

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertEquals(0, result.recordCount)
        assertFalse(result.dataAvailable as boolean)
        Map output = JSON_SLURPER.parseText(target.getText("UTF-8")) as Map
        assertTrue(((List) output.records).isEmpty())
        assertEquals(0, output.metadata.extractedRecordCount)
    }

    @Test
    void leavesNoFileForValidationErrors() {
        OmsRestSourceSupport.setHttpClient { Map ignored -> [statusCode: 200, body: '{"orders":[]}'] }

        File target = new File(tempDir, "invalid.json")
        // A HALF-supplied window (windowStart absent, windowEnd present) is a validation error in
        // its own right now that the window as a whole is optional (see OmsRestSourceSupportTests'
        // returnsValidationErrorsForBadWindows), so the message differs from a plain "is required.".
        Map result = OmsRestSourceSupport.extractOrdersToFile(baseConfig(), null, "2026-05-01T00:00:00Z", target)

        assertTrue(result.errors.any { it.contains("windowStart and windowEnd must be supplied together, or both omitted.") },
                result.errors.toString())
        assertFalse(target.exists(), "no file may be created for a rejected request")
    }

    @Test
    void abortedPaginationStrategyDoesNotLeakPagesIntoFile() {
        // pageIndex strategy never advances (same page back), so extraction falls back to
        // viewIndex; the file must contain each record exactly once.
        OmsRestSourceSupport.setHttpClient { Map request ->
            String url = request.url as String
            if (url.contains("pageIndex=")) {
                return [statusCode: 200, body: JsonOutput.toJson([orders: salesOrders("O1", "O2")])]
            }
            int viewIndex = queryInt(url, "viewIndex")
            List orders = viewIndex == 0 ? salesOrders("O1", "O2") : viewIndex == 1 ? salesOrders("O3") : []
            return [statusCode: 200, body: JsonOutput.toJson([orders: orders])]
        }

        File target = new File(tempDir, "fallback.json")
        Map result = OmsRestSourceSupport.extractOrdersToFile(baseConfig([ordersPageSize: 2]),
                "2026-05-01T00:00:00Z", "2026-05-01T01:00:00Z", target)

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertEquals("viewIndexViewSize", result.requestMetadata.pagination.strategy)
        Map output = JSON_SLURPER.parseText(target.getText("UTF-8")) as Map
        assertEquals(["O1", "O2", "O3"], ((List) output.records).collect { it.externalId })
    }

    private static List<Map<String, Object>> salesOrders(Object... externalIds) {
        return externalIds.collect { [externalId: it.toString(), orderTypeId: "SALES_ORDER"] as Map<String, Object> }
    }

    private static Map<String, Object> baseConfig(Map<String, Object> overrides = [:]) {
        return [
                omsRestSourceConfigId: "KREWE_OMS",
                companyUserGroupId   : "KREWE",
                baseUrl              : "https://dev-maarg.hotwax.io",
                ordersPath           : "/rest/s1/oms/orders",
                authType             : "NONE",
                connectTimeoutSeconds: 5,
                readTimeoutSeconds   : 10,
                isActive             : "Y",
                canReadOrders        : "Y",
        ] + overrides
    }

    private static int queryInt(String url, String key) {
        String query = new URI(url).rawQuery ?: ""
        String value = query.split("&").collect { it.split("=", 2) }
                .find { it[0] == key }?.getAt(1)
        return Integer.parseInt(value)
    }
}
