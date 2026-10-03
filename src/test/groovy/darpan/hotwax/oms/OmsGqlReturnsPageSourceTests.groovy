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

    private List<Map> requests = []

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
                orders     : { Map v -> conn([[orderId: "GR1", externalId: "7186636636291"]]) },
                orderItems : { Map v -> conn([[orderId: "GR1", orderItemSeqId: "00101", externalId: "16501091238019"]]) },
                products   : { Map v -> conn([[productId: "P1", internalName: "219-001-G"]]) }]
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
        assertEquals("16501091238019", ((r.items as List)[0] as Map).orderItemExternalId)
        assertEquals("219-001-G", ((r.items as List)[0] as Map).sku)
        assertEquals([returnsCount: 3, excludedNoShopifyRefCount: 1, excludedCancelledCount: 1], result.serverCounts)
        assertEquals("c1", requests.findAll { it.root == "returns" }[1].variables.after, "second page must follow the cursor")
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
