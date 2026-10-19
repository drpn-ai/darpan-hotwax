package darpan.hotwax.oms

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-085 / DAR-BE-086: which order lines OMS booked a return against, by Shopify order id.
 *
 * Fixtures are the gorjana records probed live 2026-10-19:
 *   - 6872256348291 -> M528328, ITEM_APPROVED, NO return items (the false negative's order)
 *   - 7187329777795 -> M1019398, warranty return M240616 on item 02 = line 16502340714627, externalId
 *     WARRANTY-...-REFUND (the false positive's order)
 *   - 7114275815555 -> M851089 + EXC- order M858226, return M202525 keyed by Shopify return 26947223683
 * GraphQL is injected; nothing here opens a socket.
 */
class OmsReturnLinesLookupTests {

    private static final Map CONFIG = [omsRestSourceConfigId: "TEST_CFG", baseUrl: "https://oms.example.com",
                                       apiKey: "test-key", isActive: "Y"]

    private static final List<Map> ORDERS = [
            [orderId: "M528328", externalId: "6872256348291", productStoreId: "STORE"],
            [orderId: "M1019398", externalId: "7187329777795", productStoreId: "STORE"],
            [orderId: "M851089", externalId: "7114275815555", productStoreId: "STORE"],
            [orderId: "M858226", externalId: "7114275815555", productStoreId: "STORE"],
    ]
    private static final List<Map> RETURN_ITEMS = [
            [returnId: "M240616", returnItemSeqId: "01", orderId: "M1019398", orderItemSeqId: "02", statusId: "RETURN_COMPLETED"],
            [returnId: "M202525", returnItemSeqId: "01", orderId: "M851089", orderItemSeqId: "01", statusId: "RETURN_COMPLETED"],
            [returnId: "M999001", returnItemSeqId: "01", orderId: "M851089", orderItemSeqId: "01", statusId: "RETURN_CANCELLED"],
    ]
    private static final List<Map> ORDER_ITEMS = [
            [orderId: "M1019398", orderItemSeqId: "01", externalId: "16502340681859"],
            [orderId: "M1019398", orderItemSeqId: "02", externalId: "16502340714627"],
            [orderId: "M851089", orderItemSeqId: "01", externalId: "17000000000001"],
    ]
    private static final List<Map> RETURNS = [
            [returnId: "M240616", statusId: "RETURN_COMPLETED", entryDate: "2026-09-23T19:51:27.631Z",
             externalId: "WARRANTY-8f0fa38a04ff43c790422d778df8ba8c-REFUND", returnChannelEnumId: "AFTSHP_OL_WTY",
             identifications: [[returnIdentificationTypeId: "SHOPIFY_RETURN_ID", idValue: "WARRANTY-8f0fa38a04ff43c790422d778df8ba8c", thruDate: null],
                               [returnIdentificationTypeId: "SHOPIFY_RTN_ID", idValue: "WARRANTY-8f0fa38a04ff43c790422d778df8ba8c-REFUND", thruDate: null],
                               [returnIdentificationTypeId: "AFTSHIP_WTY_RMA_ID", idValue: "VQHEMEPM", thruDate: null]]],
            [returnId: "M202525", statusId: "RETURN_COMPLETED", entryDate: "2026-08-16T16:05:02.166Z",
             externalId: "26947223683", returnChannelEnumId: "AFTSHIP_RTN_CHANNEL",
             identifications: [[returnIdentificationTypeId: "SHOPIFY_RTN_ID", idValue: "26947223683", thruDate: null]]],
            [returnId: "M999001", statusId: "RETURN_CANCELLED", entryDate: "2026-08-17T10:00:00Z",
             externalId: null, returnChannelEnumId: "AFTSHIP_RTN_CHANNEL", identifications: []],
    ]

    private List<Map> requests = []

    @AfterEach
    void reset() {
        OmsReturnLinesLookupSupport.resetClientFactory()
    }

    /** A fake OMS GraphQL honouring `key:a,b` on the four roots, optionally overriding one root's answer. */
    private void omsAnswers(Map<String, Closure> overrides = [:]) {
        OmsReturnLinesLookupSupport.setClientFactory { Map config ->
            OmsGqlClient c = new OmsGqlClient(config, { String url, Map headers, String body ->
                Map payload = (Map) new JsonSlurper().parseText(body)
                requests.add(payload)
                String query = payload.query as String
                String q = ((Map) payload.variables).q as String
                String key = q.substring(0, q.indexOf(':'))
                Set<String> values = q.substring(q.indexOf(':') + 1).split(",") as Set
                String root = ["returnItems", "orderItems", "returns", "orders"].find { query.contains("${it}(") }
                if (overrides.containsKey(root)) return (Map) overrides[root].call(values)
                List<Map> table = [orders: ORDERS, returnItems: RETURN_ITEMS, orderItems: ORDER_ITEMS, returns: RETURNS][root]
                List<Map> nodes = table.findAll { values.contains(it[key] as String) }
                return [statusCode: 200, body: JsonOutput.toJson([data: [(root): [
                        edges: nodes.collect { [node: it] }, pageInfo: [hasNextPage: false]]], errors: []])]
            })
            c.sleeper = { long ms -> }
            return c
        }
    }

    @Test
    void anOrderOmsBookedNoReturnOnAnswersAnEmptyList() {
        omsAnswers()

        Map result = OmsReturnLinesLookupSupport.lookupReturnLinesByOrderExternalId(CONFIG, ["6872256348291"])

        assertTrue(result.ok as Boolean)
        assertEquals([], ((Map) result.returnLinesByExternalId)["6872256348291"],
                "M528328 has no return items: an empty list, which the verify stage reads as nothing to pair")
    }

    @Test
    void theWarrantyReturnComesBackOnItsLineAndIsNotShopifyKeyed() {
        omsAnswers()

        Map result = OmsReturnLinesLookupSupport.lookupReturnLinesByOrderExternalId(CONFIG, ["7187329777795"])

        List<Map> lines = (List<Map>) ((Map) result.returnLinesByExternalId)["7187329777795"]
        assertEquals(1, lines.size())
        assertEquals("M240616", lines[0].returnId)
        assertEquals("16502340714627", lines[0].lineId, "the Shopify line id, read off OMS OrderItem.externalId")
        assertEquals("AFTSHP_OL_WTY", lines[0].returnChannelEnumId)
        assertFalse(lines[0].shopifyKeyed as Boolean,
                "WARRANTY-<uuid> in every id field: the id join can never match this return")
    }

    @Test
    void aReturnKeyedByANumericShopifyIdIsShopifyKeyedAndACancelledOneIsLeftOut() {
        omsAnswers()

        Map result = OmsReturnLinesLookupSupport.lookupReturnLinesByOrderExternalId(CONFIG, ["7114275815555"])

        List<Map> lines = (List<Map>) ((Map) result.returnLinesByExternalId)["7114275815555"]
        assertEquals(["M202525"], lines*.returnId, "the RETURN_CANCELLED return accounts for nothing")
        assertTrue(lines[0].shopifyKeyed as Boolean)
        assertEquals("17000000000001", lines[0].lineId)
    }

    @Test
    void ordersAnIgnoredFilterAnswersWithAreDropped() {
        // The REST sibling's orderExternalId filter was proven silently ignored (HTTP 200, arbitrary
        // records). Every pass echo-checks what it asked for.
        omsAnswers([orders: { Set<String> values ->
            [statusCode: 200, body: JsonOutput.toJson([data: [orders: [edges: ORDERS.collect { [node: it] },
                    pageInfo: [hasNextPage: false]]], errors: []])]
        }])

        Map result = OmsReturnLinesLookupSupport.lookupReturnLinesByOrderExternalId(CONFIG, ["6872256348291"])

        assertEquals(["6872256348291"], ((Map) result.returnLinesByExternalId).keySet() as List)
        assertEquals([], ((Map) result.returnLinesByExternalId)["6872256348291"])
        assertFalse(requests.any { ((Map) it.variables).q.toString().contains("M1019398") },
                "orders nobody asked for must not drive the next pass")
    }

    @Test
    void anyGraphQlErrorFailsTheWholeLookupRatherThanAnsweringEmpty() {
        omsAnswers([returnItems: { Set<String> values ->
            [statusCode: 200, body: JsonOutput.toJson([data: null, errors: [[message: "cost exceeded", extensions: [code: "COST_EXCEEDED"]]]])]
        }])

        Map result = OmsReturnLinesLookupSupport.lookupReturnLinesByOrderExternalId(CONFIG, ["7187329777795"])

        assertFalse(result.ok as Boolean, "an empty answer would read as 'OMS booked nothing' and stop a pairing")
        assertEquals([:], result.returnLinesByExternalId)
        assertTrue((result.errors as List)[0].toString().contains("COST_EXCEEDED"))
    }

    @Test
    void anIdCarryingASeparatorIsRefusedBeforeAnyCall() {
        omsAnswers()

        Map result = OmsReturnLinesLookupSupport.lookupReturnLinesByOrderExternalId(CONFIG, ["123,456"])

        assertFalse(result.ok as Boolean)
        assertEquals([], requests)
    }
}
