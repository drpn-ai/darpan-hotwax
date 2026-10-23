package darpan.hotwax.oms

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-BE-040 fix 3. The sender is injected, so every case runs without a socket. */
class OmsGqlReturnsPageSourceTests {

    private static final Map CONFIG = [baseUrl: "https://gorjana-maarg.hotwax.io", authType: "BEARER", apiToken: "t"]
    private static final long FROM = 1790812800000L   // 2026-10-01T00:00:00Z
    private static final long THRU = 1790899200000L   // 2026-10-02T00:00:00Z

    private List<Map> requests = Collections.synchronizedList([])

    /** Answers by GraphQL root field; each handler takes the variables and returns the connection map. */
    private OmsGqlClient client(Map<String, Closure> handlers) {
        OmsGqlClient c = new OmsGqlClient(CONFIG, { String url, Map headers, String body ->
            Map payload = (Map) new JsonSlurper().parseText(body)
            String root = (payload.query =~ /\{\s*(\w+)\(/)[0][1]
            requests.add([root: root, query: payload.query, variables: payload.variables])
            Closure h = handlers[root]
            if (h == null) throw new AssertionError("unexpected GraphQL root ${root}")
            Object answer = h.call(payload.variables)
            if (answer instanceof Map && ((Map) answer).errors) return [statusCode: 200, body: JsonOutput.toJson(answer)]
            return [statusCode: 200, body: JsonOutput.toJson([data: [(root): answer], errors: []])]
        })
        c.sleeper = { long ms -> }
        return c
    }

    private static Map conn(List nodes, String endCursor = null) {
        return [edges: nodes.collect { [node: it] }, pageInfo: [hasNextPage: endCursor != null, endCursor: endCursor]]
    }

    private static Map ret(String id, Map o = [:]) {
        return [returnId: id, statusId: "RETURN_REQUESTED", entryDate: "2026-10-01T10:00:00Z", externalId: null,
                currencyUomId: "USD", returnChannelEnumId: "ADMIN_RTN_CHANNEL",
                identifications: [[returnIdentificationTypeId: "SHOPIFY_RETURN_ID", idValue: "3744700${id.takeRight(4)}", thruDate: null]]] + o
    }

    private Map run(OmsGqlClient c, List<List<Map>> pages = []) {
        Map result = OmsGqlReturnsPageSource.fetchAll(c, [fromMillis: FROM, thruMillis: THRU, config: CONFIG,
                pageConsumer: { List<Map> records -> pages.add(records) }])
        return result
    }

    private Map<String, Closure> lookupsAnswering() {
        return [returnItems: { Map v -> conn([[returnId: "M0001", returnItemSeqId: "01", orderId: "GR1", orderItemSeqId: "00101",
                                               productId: "P1", returnQuantity: 1, receivedQuantity: 0, returnPrice: 65,
                                               returnReasonId: "UNKNOWN", statusId: "RETURN_REQUESTED", returnTypeId: "RTN_REFUND"]]) },
                orders     : { Map v -> conn([[orderId: "GR1", externalId: "7186636636291", productStoreId: "STORE",
                                               identifications: [[orderIdentificationTypeId: "SHOPIFY_ORD_ID", idValue: "7186636636291"],
                                                                 [orderIdentificationTypeId: "NETSUITE_ORDER_ID", idValue: "81049165"]]]]) },
                orderItems : { Map v -> conn([[orderId: "GR1", orderItemSeqId: "00101", externalId: "16501091238019"]]) },
                products   : { Map v -> conn([[productId: "P1", internalName: "219-001-G"]]) },
                goodIdentifications: { Map v -> conn([[productId: "P1", goodIdentificationTypeId: "NETSUITE_PRODUCT_ID", idValue: "30345", thruDate: null]]) }]
    }

    @Test
    void pagesTheWindowAndHandsOnlyKeptReturnsToTheConsumer() {
        int call = 0
        Map<String, Closure> h = lookupsAnswering() + [returns: { Map v ->
            call++
            return call == 1 ? conn([ret("M0001"), ret("M0002", [statusId: "RETURN_CANCELLED"])], "c1")
                             : conn([ret("M0003", [identifications: [[returnIdentificationTypeId: "NETSUITE_RMA_ID", idValue: "80249409"]]])])
        }]
        List<List<Map>> pages = []
        Map result = run(client(h), pages)

        assertEquals([], result.errors)
        assertEquals("GRAPHQL", result.transport)
        List<Map> records = pages.flatten() as List<Map>
        assertEquals(["M0001"], records*.returnId)
        Map r = records[0]
        assertEquals("37447000001", r.shopifyReturnId)
        assertEquals("7186636636291", r.orderExternalId)
        assertEquals("STORE", r.orderProductStoreId, "the origin order's store rides on the orders lookup already made")
        assertEquals("81049165", r.orderNetsuiteId, "and whether it reached NetSuite (its NETSUITE_ORDER_ID)")
        assertEquals("16501091238019", ((r.items as List)[0] as Map).orderItemExternalId)
        assertEquals("219-001-G", ((r.items as List)[0] as Map).sku)
        assertEquals("30345", ((r.items as List)[0] as Map).productNetsuiteId, "whether the item's product can reach NetSuite at all")
        assertEquals("goodIdentificationTypeId:NETSUITE_PRODUCT_ID productId:P1",
                requests.find { it.root == "goodIdentifications" }.variables.q, "only the NetSuite id type is read")
        assertEquals([returnsCount: 3, excludedNoShopifyRefCount: 1, excludedCancelledCount: 1], result.serverCounts)
        assertEquals("c1", requests.findAll { it.root == "returns" }[1].variables.after, "second page must follow the cursor")
    }

    /** DAR-BE-116 R8 (F-104602): a product with no NETSUITE_PRODUCT_ID, or only an expired one, leaves the item without the field. */
    @Test
    void anItemWhoseProductHasNoLiveNetsuiteIdCarriesNone() {
        Map<String, Closure> h = lookupsAnswering() + [returns: { Map v -> conn([ret("M0001")]) },
                goodIdentifications: { Map v -> conn([[productId: "P1", goodIdentificationTypeId: "NETSUITE_PRODUCT_ID", idValue: "1", thruDate: "2025-01-01T00:00:00Z"]]) }]
        List<List<Map>> pages = []
        run(client(h), pages)
        Map item = (((pages.flatten() as List<Map>)[0].items as List)[0] as Map)
        assertFalse(item.containsKey("productNetsuiteId"), "absent, so the REST-parity item shape is unchanged")
    }

    /** DAR-BE-116: lookups run once per buffer of kept returns, not once per returns page. */
    @Test
    void oneLookupPassCoversTheBufferedReturnsPages() {
        int call = 0
        Map<String, Closure> h = lookupsAnswering() + [returns: { Map v ->
            call++
            return call == 1 ? conn([ret("M0001")], "c1") : conn([ret("M0002")])
        }]
        List<List<Map>> pages = []
        Map result = run(client(h), pages)
        assertEquals([], result.errors)
        assertEquals(["M0001", "M0002"], (pages.flatten() as List<Map>)*.returnId)
        List<Map> itemCalls = requests.findAll { it.root == "returnItems" }
        assertEquals(1, itemCalls.size(), "one returnItems call for both pages")
        assertEquals("returnId:M0001,M0002", ((Map) itemCalls[0].variables).q)
    }

    /** DAR-BE-116: a multi-day window runs as contiguous day slices, each return arriving once. */
    @Test
    void aMultiDayWindowIsFetchedAsContiguousDaySlices() {
        List<String> filters = Collections.synchronizedList([])
        Map<String, Closure> h = lookupsAnswering() + [returns: { Map v ->
            String q = v.q as String
            filters.add(q)
            String day = (q =~ /entryDate:>=(\S+)/)[0][1]
            return conn([ret("R" + day.substring(8, 10) + "01")])
        }]
        List<List<Map>> pages = Collections.synchronizedList([])
        Map result = OmsGqlReturnsPageSource.fetchAll(client(h), [fromMillis: FROM, thruMillis: FROM + 3 * 86400000L,
                config: CONFIG, pageConsumer: { List<Map> records -> pages.add(records) }])
        assertEquals([], result.errors)
        assertEquals(["R0101", "R0201", "R0301"], (pages.flatten() as List<Map>)*.returnId.sort())
        assertEquals(["entryDate:>=2026-10-01T00:00:00Z entryDate:<2026-10-02T00:00:00Z",
                      "entryDate:>=2026-10-02T00:00:00Z entryDate:<2026-10-03T00:00:00Z",
                      "entryDate:>=2026-10-03T00:00:00Z entryDate:<2026-10-04T00:00:00Z"], filters.sort())
    }

    /** DAR-BE-116: R8 (keeps returns with no Shopify reference) and R11 (does not) read the month's returns once. */
    @Test
    void twoReturnsExtractsOfOneWindowShareOneReadAndKeepTheirOwnScope() {
        OmsGqlSharedRead.reset()
        java.util.concurrent.CountDownLatch both = new java.util.concurrent.CountDownLatch(2)
        Map<String, Closure> h = lookupsAnswering() + [returns: { Map v ->
            both.await(5, java.util.concurrent.TimeUnit.SECONDS)
            return conn([ret("M0001"), ret("M0002", [identifications: []]), ret("M0003", [statusId: "RETURN_CANCELLED"])])
        }]
        OmsGqlClient c = client(h)
        Map config = CONFIG + [gqlSharedReads: true]
        def pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            List<List<List<Map>>> pages = [Collections.synchronizedList([]), Collections.synchronizedList([])]
            List futures = [true, false].withIndex().collect { boolean keepNoRef, int i ->
                pool.submit({ ->
                    both.countDown()
                    OmsGqlReturnsPageSource.fetchAll(c, [fromMillis: FROM, thruMillis: THRU, config: config,
                            includeReturnsWithoutShopifyRef: keepNoRef, pageConsumer: { List<Map> records -> pages[i].add(records) }])
                } as java.util.concurrent.Callable)
            }
            List<Map> results = futures.collect { (Map) it.get(10, java.util.concurrent.TimeUnit.SECONDS) }
            results.each { assertEquals([], it.errors) }
            assertEquals(1, requests.count { it.root == "returns" }, "one returns page for both extracts")
            assertEquals(["M0001", "M0002"], (pages[0].flatten() as List<Map>)*.returnId.sort())
            assertEquals(["M0001"], (pages[1].flatten() as List<Map>)*.returnId)
            assertEquals(1, ((Map) results[1].serverCounts).excludedNoShopifyRefCount)
            assertEquals(1, ((Map) results[0].serverCounts).keptNoShopifyRefCount)
            results.each { assertEquals(1, ((Map) it.serverCounts).excludedCancelledCount) }
            assertFalse((pages[0].flatten() as List<Map>).any { it.containsKey(OmsGqlReturnsPageSource.SHARED_VERDICT) })
        } finally {
            pool.shutdownNow()
            OmsGqlSharedRead.reset()
        }
    }

    @Test
    void costExceededHalvesTheReturnsPageAndAsksAgain() {
        List<Integer> firsts = []
        Map<String, Closure> h = lookupsAnswering() + [returns: { Map v ->
            firsts.add(v.first as int)
            if (firsts.size() == 1) return [errors: [[message: "cost too high", extensions: [code: "COST_EXCEEDED"]]]]
            return conn([ret("M0001")])
        }]
        List<List<Map>> pages = []
        assertEquals([], run(client(h), pages).errors)
        assertEquals(firsts[0].intdiv(2), firsts[1])
        assertEquals(["M0001"], (pages.flatten() as List<Map>)*.returnId)
    }

    @Test
    void aCheckAgainstNetSuiteKeepsTheReturnsShopifyNeverSees() {
        Map<String, Closure> h = lookupsAnswering() + [returns: { Map v ->
            conn([ret("M0001"), ret("628087", [identifications: [], returnChannelEnumId: "HAPPINESS_GUARANTEE", statusId: "RETURN_COMPLETED"]),
                  ret("M0002", [statusId: "RETURN_CANCELLED"])])
        }]
        List<List<Map>> pages = []
        Map result = OmsGqlReturnsPageSource.fetcher(client(h), [includeReturnsWithoutShopifyRef: true]).call(
                [fromMillis: FROM, thruMillis: THRU, config: CONFIG, pageConsumer: { List<Map> records -> pages.add(records) }])
        assertEquals([], result.errors)
        assertEquals(["M0001", "628087"], (pages.flatten() as List<Map>)*.returnId)
        assertEquals(null, (pages.flatten() as List<Map>)[1].shopifyReturnId)
        assertEquals([returnsCount: 3, excludedNoShopifyRefCount: 0, excludedCancelledCount: 1, keptNoShopifyRefCount: 1], result.serverCounts,
                "cancelled stays out; the kept ones are counted")
    }

    @Test
    void aCheckOfCancelledReturnsAsksOnlyForThemAndKeepsEveryOne() {
        Map<String, Closure> h = lookupsAnswering() + [returns: { Map v ->
            conn([ret("M0002", [statusId: "RETURN_CANCELLED"]), ret("628087", [identifications: [], statusId: "RETURN_CANCELLED"]),
                  ret("M0001")])
        }]
        List<List<Map>> pages = []
        Map result = OmsGqlReturnsPageSource.fetcher(client(h), [onlyCancelledReturns: true]).call(
                [fromMillis: FROM, thruMillis: THRU, config: CONFIG, pageConsumer: { List<Map> records -> pages.add(records) }])
        assertEquals([], result.errors)
        assertTrue((requests.find { it.root == "returns" }.variables.q as String).startsWith("statusId:RETURN_CANCELLED entryDate:>="))
        assertEquals(["M0002", "628087"], (pages.flatten() as List<Map>)*.returnId, "cancelled ones kept, Shopify reference or not; a stray status dropped")
    }

    @Test
    void theWindowIsHalfOpenOnEntryDateAndTravelsAsAVariable() {
        Map result = run(client([returns: { Map v -> conn([]) }]))
        assertEquals([], result.errors)
        Map first = requests[0]
        assertEquals("entryDate:>=2026-10-01T00:00:00Z entryDate:<2026-10-02T00:00:00Z", first.variables.q)
        // Moqui's request sanitizer rejects `<` inside the document; it is safe only as a variable.
        assertFalse((first.query as String).contains("<"))
        assertFalse((first.query as String).toLowerCase().contains("mutation"))
    }

    @Test
    void noLookupIsMadeForAPageWithNothingKept() {
        run(client([returns: { Map v -> conn([ret("M0002", [statusId: "RETURN_CANCELLED"])]) }]))
        assertEquals(["returns"], requests*.root)
    }

    @Test
    void returnItemsFollowTheirOwnCursor() {
        int itemCalls = 0
        Map<String, Closure> h = lookupsAnswering() + [returns: { Map v -> conn([ret("M0001")]) },
                returnItems: { Map v ->
                    itemCalls++
                    return itemCalls == 1
                            ? conn([[returnId: "M0001", returnItemSeqId: "01", orderId: "GR1", orderItemSeqId: "00101", productId: "P1",
                                     returnQuantity: 1, returnPrice: 10]], "i1")
                            : conn([[returnId: "M0001", returnItemSeqId: "02", orderId: "GR1", orderItemSeqId: "00101", productId: "P1",
                                     returnQuantity: 1, returnPrice: 5]])
                }]
        List<List<Map>> pages = []
        run(client(h), pages)
        Map r = (pages.flatten() as List<Map>)[0]
        assertEquals(["01", "02"], (r.items as List)*.returnItemSeqId, "a truncated items page would understate returnTotal")
        assertEquals("15", r.returnTotal.toString())
    }

    @Test
    void aGraphQlErrorFailsTheExtractRatherThanReturningAnEmptyOne() {
        Map result = run(client([returns: { Map v -> [data: null, errors: [[message: "Field 'returns' is undefined", extensions: [code: "FieldUndefined"]]]] }]))
        assertTrue((result.errors as List).any { (it as String).contains("FieldUndefined") }, "${result.errors}")
    }

    @Test
    void aLookupErrorFailsTheExtractToo() {
        // A missing order lookup would emit records with null Shopify ids that read as real differences.
        Map<String, Closure> h = lookupsAnswering() + [returns: { Map v -> conn([ret("M0001")]) },
                orders: { Map v -> [data: null, errors: [[message: "cost", extensions: [code: "COST_EXCEEDED"]]]] }]
        List<List<Map>> pages = []
        Map result = run(client(h), pages)
        assertTrue((result.errors as List).any { (it as String).contains("COST_EXCEEDED") }, "${result.errors}")
        assertEquals([], pages, "nothing reaches the consumer from a page whose lookups failed")
    }

    @Test
    void aWindowIsRequired() {
        Map result = OmsGqlReturnsPageSource.fetchAll(client([:]), [fromMillis: null, thruMillis: THRU, config: CONFIG,
                pageConsumer: { List<Map> r -> }])
        assertTrue((result.errors as List).any { (it as String).contains("window") })
    }
}
