package darpan.hotwax.oms

import darpan.datasource.DataDefinedExtract
import darpan.datasource.DataSourceDefinitionSupport
import darpan.datasource.DataSourceRunner
import darpan.datasource.GraphQlTransport
import darpan.reconciliation.source.SourceFilterSupport
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-176. The OMS returns read as data (data/HotWaxDataSourceDefinitionsSeedData.xml, OMS_RETURNS_GQL_V1) writes the
 * same records and counts as the coded extract#HotWaxOmsGqlReturns path (extractOmsGqlReturns.groovy's own call), for
 * both switches each way, by window and by returnId, with the connector's keep fields and with rules, on one scripted
 * HotWax server.
 *
 * The fixture's ids (R*, RO*, RP*) are its own: the coded path caches product, order and order-line lookups per
 * connection for hours, and other suites' fixtures name O1 and P1. Records are compared as multisets after the JSON
 * round trip, nested objects included, with null and absent as one (the coded read writes a lookup miss as null; the
 * data read leaves an optional field out).
 */
class OmsGqlReturnsDataDefinitionTests {

    static final Map CONFIG = OmsGqlOrderItemsDataDefinitionTests.CONFIG
    static final List<String> CONNECTION_FIELDS = OmsGqlOrderItemsDataDefinitionTests.CONNECTION_FIELDS
    static final String FROM = "2026-10-01T00:00:00Z"
    static final String THRU = "2026-10-03T00:00:00Z"
    static final List<String> BASE = ["returnId", "shopifyReturnId", "externalId", "orderExternalId", "statusId", "entryDate", "returnTotal",
                                      "currencyUomId", "returnChannelEnumId"]

    private static Map ident(String type, String value, String thru = null) {
        return [returnIdentificationTypeId: type, idValue: value, thruDate: thru]
    }

    private static Map ret(String id, String status, String externalId, String entry, List idents, Map more = [:]) {
        return [returnId: id, statusId: status, entryDate: entry, returnDate: "2026-09-28T10:00:00Z", externalId: externalId,
                currencyUomId: "USD", returnChannelEnumId: "ECOM_RTN_CHANNEL", fromPartyId: " CUST1 ", identifications: idents] + more
    }

    static final List<Map> RETURNS = [
            ret("R1", "RETURN_REQUESTED", "SHR1", "2026-10-01T05:00:00Z", [ident("SHOPIFY_RTN_ID", "111"), ident("NETSUITE_CM_ID", "CM1")]),
            // No externalId: the Shopify id is the third identification (an ended one and a WARRANTY one skipped), and the
            // join-key fallback writes it into externalId.
            ret("R2", "RETURN_RECEIVED", null, "2026-10-01T09:00:00Z", [ident("SHOPIFY_RTN_ID", "222", "2026-01-01T00:00:00Z"),
                    ident("SHOPIFY_RETURN_ID", "WARRANTY-ab"), ident("SHOPIFY_RETURN_ID", "333")]),
            ret("R3", "RETURN_REQUESTED", " ", "2026-10-01T10:00:00Z", [ident("AFTSHIP_WTY_RMA_ID", "W1")]),   // no Shopify reference
            ret("R4", "RETURN_CANCELLED", "SHR4", "2026-10-01T11:00:00Z", []),
            ret("R5", "RETURN_REQUESTED", "SHR5", "2026-10-02T01:00:00Z", []),                                  // an exchange, blocked
            ret("R8", "RETURN_REQUESTED", "SHR8", "2026-10-02T02:00:00Z", []),                                  // no items
            ret("R9", "RETURN_COMPLETED", "SHR9", "2026-10-02T03:00:00Z", []),                                  // an exchange, in NetSuite
            ret("R6", "RETURN_REQUESTED", "SHR6", "2026-09-15T00:00:00Z", []),                                  // outside the window
            // Both Shopify types active and numeric, the newer listed first: the older type wins.
            ret("R10", "RETURN_REQUESTED", null, "2026-10-02T04:00:00Z", [ident("SHOPIFY_RETURN_ID", "444"), ident("SHOPIFY_RTN_ID", "555")]),
    ]

    private static Map item(String ret, String seq, String order, String line, String product, Object qty, Object price, String status,
                            String type = "RTN_REFUND") {
        return [returnId: ret, returnItemSeqId: seq, orderId: order, orderItemSeqId: line, productId: product, returnQuantity: qty,
                receivedQuantity: qty == null ? null : "0", returnPrice: price, returnReasonId: "RTN_DEFECTIVE", statusId: status, returnTypeId: type]
    }

    static final List<Map> ITEMS = [
            item("R1", "00002", "RO1", "00002", "RP2", "1.0", "5", "RETURN_REQUESTED"),
            item("R1", "00001", "RO1", "00001", "RP1", 2, "10.00", "RETURN_REQUESTED"),
            item("R2", "00001", "RO2", "00001", "RP1", 1, null, "RETURN_RECEIVED"),
            item("R3", "00001", "RO1", "00001", "RP1", 1, "10", "RETURN_REQUESTED"),
            item("R4", "00001", "RO1", "00002", "RP2", 1, "5", "RETURN_CANCELLED"),
            item("R5", "00001", "RO3", "00001", "RP1", 1, "10", "RETURN_REQUESTED", "RTN_EXCHANGE"),
            item("R9", "00001", "RO3", "00002", "RP2", 1, "5", "RETURN_COMPLETED", "RTN_EXCHANGE"),
            // On the orders sharing the blocked exchange order's Shopify id: R5's own second item comes first (never its own
            // open return), then R6 is open, R7 is done.
            item("R5", "00002", "RO9", "00001", "RP1", 1, "10", "RETURN_REQUESTED"),
            item("R6", "00001", "RO10", "00001", "RP1", 1, "10", "RETURN_REQUESTED"),
            item("R7", "00001", "RO9", "00001", "RP1", 1, "10", "RETURN_COMPLETED"),
    ]

    private static Map order(String id, String externalId, Map more = [:]) {
        return [orderId: id, orderName: "#${id}".toString(), externalId: externalId, productStoreId: "STORE_A", grandTotal: "100.00",
                orderDate: "2026-09-20T00:00:00Z", billToCustomer: [firstName: "Ada", lastName: null], identifications: []] + more
    }

    static final List<Map> ORDERS = [
            order("RO1", "SHO1", [identifications: [[orderIdentificationTypeId: "NETSUITE_ORDER_ID", idValue: "NSO1"]]]),
            order("RO2", "SHO2", [productStoreId: "STORE_B", billToCustomer: null, grandTotal: 0]),
            order("RO3", "SHO3"),
            order("RO9", "SHX9"),                                                            // R5's replacement: not in NetSuite
            order("RO10", "SHX9"),                                                           // the same Shopify order
            order("RO11", "SHX11", [identifications: [[orderIdentificationTypeId: "NETSUITE_ORDER_ID", idValue: "NSO11"]]]),
    ]
    static final List<Map> ORDER_LINES = [[orderId: "RO1", orderItemSeqId: "00001", externalId: "LINE1"],
                                          [orderId: "RO1", orderItemSeqId: "00002", externalId: "LINE2"]]
    static final List<Map> PRODUCTS = [[productId: "RP1", internalName: "RSKU-1"], [productId: "RP2", internalName: ""]]
    static final List<Map> PRODUCT_IDS = [[productId: "RP1", idValue: "NSP-OLD", thruDate: "2026-01-01T00:00:00Z"], [productId: "RP1", idValue: "NSP1", thruDate: null]]
    static final List<Map> RESPONSES = [[returnId: "R5", responses: [[replacementOrderId: null], [replacementOrderId: "RO9"]]],
                                        [returnId: "R9", responses: [[replacementOrderId: "RO11"]]]]

    static class Server {
        final List<Map> calls = Collections.synchronizedList(new ArrayList<Map>())

        Closure sender() {
            return { String url, Map headers, String body ->
                Map req = (Map) new JsonSlurper().parseText(body)
                Map vars = (Map) (req.variables ?: [:])
                String op = ((req.query as String) =~ /query\s+(\w+)/).with { it.find() ? it.group(1) : "?" }
                calls << [op: op, q: vars.q]
                Map<String, List> tables = [OmsReturns: [ "returns", RETURNS], OmsReturnItems: ["returnItems", ITEMS],
                        OmsOrderExternalIds: ["orders", ORDERS], OmsOrderItemExternalIds: ["orderItems", ORDER_LINES],
                        OmsProductNames: ["products", PRODUCTS], OmsProductNetsuiteIds: ["goodIdentifications", PRODUCT_IDS],
                        OmsReturnResponses: ["returns", RESPONSES]]
                List table = tables[op]
                if (table == null) throw new IllegalStateException("unexpected query ${op}")
                List<Map> rows = ((List<Map>) table[1]).findAll { matches(it, (String) vars.q) }
                int first = (vars.first as Number).intValue()
                int after = vars.after ? (vars.after as String).substring(1) as int : 0
                List<Map> page = rows.drop(after).take(first)
                boolean more = after + first < rows.size()
                return [statusCode: 200, body: JsonOutput.toJson([data: [(table[0]): [edges: page.collect { [node: it] },
                        pageInfo: [hasNextPage: more, endCursor: more ? "c${after + first}".toString() : null]]], errors: []])]
            }
        }

        private static boolean matches(Map row, String q) {
            for (String term : (q ?: "").split(" ").findAll { it }) {
                if (term == "goodIdentificationTypeId:NETSUITE_PRODUCT_ID") continue
                def range = term =~ /^entryDate:(>=|<)(.+)$/
                if (range.find()) {
                    String d = row.entryDate as String
                    if (range.group(1) == ">=" ? d < range.group(2) : d >= range.group(2)) return false
                    continue
                }
                int colon = term.indexOf(':')
                if (!(term.substring(colon + 1).split(",") as List).contains(row.get(term.substring(0, colon))?.toString())) return false
            }
            return true
        }
    }

    static Map definitionRow() {
        File seed = ["data/HotWaxDataSourceDefinitionsSeedData.xml", "runtime/component/darpan-hotwax/data/HotWaxDataSourceDefinitionsSeedData.xml"]
                .collect { new File(it) }.find { it.exists() }
        def doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(seed)
        def rows = doc.getElementsByTagName("darpan.datasource.DataSourceDefinition")
        def row = (0..<rows.length).collect { rows.item(it) as org.w3c.dom.Element }.find { it.getAttribute("dataSourceDefinitionId") == "OMS_RETURNS_GQL_V1" }
        return [protocol: row.getAttribute("protocol"), statusId: row.getAttribute("statusId"),
                json: row.getElementsByTagName("definitionJson").item(0).textContent]
    }

    static Map definition() {
        Map row = definitionRow()
        return DataSourceDefinitionSupport.parse(row.protocol as String, row.json as String, CONNECTION_FIELDS)
    }

    /** The connector row's extractOptionsJson, from darpan core's seed. */
    static String connectorOptions() {
        File seed = ["../darpan/data/SourceSystemConnectorSeedData.xml", "runtime/component/darpan/data/SourceSystemConnectorSeedData.xml"]
                .collect { new File(it) }.find { it.exists() }
        String xml = seed.text
        int at = xml.indexOf('systemEnumId="OMS_RETURNS_GQL"')
        def m = xml.substring(at) =~ /extractOptionsJson='([^']*)'/
        assertTrue(m.find())
        return m.group(1)
    }

    /** The coded path: extractOmsGqlReturns.groovy's call, on the scripted server. */
    static Map coded(Map options = [:], List keep = null, List rawFilters = null) {
        File out = File.createTempFile("coded-returns-", ".json")
        try {
            Map result = OmsReturnsSourceSupport.extractReturnsToFile(CONFIG, FROM, THRU, out, keep, null, rawFilters,
                    [pageFetcher: OmsGqlReturnsPageSource.fetcher(new OmsGqlClient(CONFIG, new Server().sender()), options)])
            assertEquals([], result.errors, "coded extract errors")
            Map file = (Map) new JsonSlurper().parse(out)
            return [records: file.records, metadata: file.metadata]
        } finally {
            out.delete()
        }
    }

    static Map data(Map request, List rawFilters = null) {
        List<Map> records = []
        Map d = definition()
        Map r = new DataSourceRunner(d, new GraphQlTransport(d, CONFIG, new Server().sender()))
                .run(request + [filters: SourceFilterSupport.parseRules(rawFilters)]) { List<Map> batch -> records.addAll(batch) }
        return r + [records: new JsonSlurper().parseText(JsonOutput.toJson(records))]
    }

    static Map window(Map more = [:]) {
        return [mode: "window", from: millis(FROM), thru: millis(THRU)] + more
    }

    static long millis(String iso) { return java.time.Instant.parse(iso).toEpochMilli() }

    /** Nulls dropped and keys sorted at every level; lists keep their order (items are in returnItemSeqId order on both). */
    static Object canon(Object v) {
        if (v instanceof Map) return new TreeMap(((Map) v).findAll { k, x -> x != null }.collectEntries { k, x -> [(k): canon(x)] })
        if (v instanceof List) return ((List) v).collect { canon(it) }
        return v
    }

    static List<String> canonical(List records) {
        return records.collect { JsonOutput.toJson(canon(it)) }.sort()
    }

    private static void assertSame(Map c, Map d, String label) {
        assertEquals(canonical((List) c.records), canonical((List) d.records), "${label}: records")
        Map filters = (Map) ((Map) c.metadata).filters
        ["excludedNoShopifyRefCount", "serverReportedReturnsCount", "excludedCancelledCount"].each {
            assertEquals(filters.get(it), d.counts.get(it) as int, "${label}: ${it}")
        }
    }

    @Test
    void theDefinitionIsValidItsQueriesAreTheCodedOnesAndItsOptionsAreTheConnectors() {
        Map row = definitionRow()
        assertEquals("DRAFT", row.statusId)
        Map d = definition()
        Map<String, String> docs = ((List<Map>) d.steps).findAll { it.kind == "query" }.collectEntries { [(it.id): it.document] }
        assertEquals(OmsGqlQueries.returnsDocument(), docs.returns)
        assertEquals(OmsGqlQueries.returnItemsDocument(), docs.items)
        assertEquals(OmsGqlQueries.returnItemsDocument(), docs.openItems)
        assertEquals(OmsGqlQueries.orderExternalIdsDocument(), docs.orders)
        assertEquals(OmsGqlQueries.orderExternalIdsDocument(), docs.exchangeOrders)
        assertEquals(OmsGqlQueries.orderExternalIdsDocument(), docs.pairOrders)
        assertEquals(OmsGqlQueries.orderItemExternalIdsDocument(), docs.orderLines)
        assertEquals(OmsGqlQueries.productNamesDocument(), docs.productNames)
        assertEquals(OmsGqlQueries.productNetsuiteIdsDocument(), docs.productNetsuiteIds)
        assertEquals(OmsGqlQueries.returnResponsesDocument(), docs.responses)
        Map returns = ((List<Map>) d.steps)[0]
        assertEquals(OmsGqlQueries.RETURNS_PAGE_SIZE, ((Map) returns.paging).pageSize)
        assertEquals(OmsGqlQueries.RETURNS_RESERVATION, returns.reservation)
        // DAR-BE-192: withReturnCredit (each refunded payment once, summed) is coded only; expressing it needs a distinct-by
        // step the definition language lacks (DAR-BE-176 follow-up). optionMismatch is also the run-time guard, so a run
        // switched to this DRAFT definition is refused rather than read without credits. Exactly that one gap, nothing else.
        assertEquals(["The definition's options [includeReturnsWithoutShopifyRef, onlyCancelledReturns] are not the connector's " +
                "[includeReturnsWithoutShopifyRef, onlyCancelledReturns, withReturnCredit] (each a boolean)."],
                DataDefinedExtract.optionMismatch(d, connectorOptions()))
        List codedBoth = ((List) new groovy.json.JsonSlurper().parseText(connectorOptions())).findAll { it.name != "withReturnCredit" }
        assertEquals([], DataDefinedExtract.optionMismatch(d, groovy.json.JsonOutput.toJson(codedBoth)))
    }

    @Test
    void aWindowWritesTheSameReturnsAndCountsForEachSwitch() {
        assertSame(coded(), data(window()), "default")
        assertSame(coded([includeReturnsWithoutShopifyRef: true]), data(window(options: [includeReturnsWithoutShopifyRef: true])), "with no reference")
        assertSame(coded([onlyCancelledReturns: true]), data(window(options: [onlyCancelledReturns: true])), "cancelled only")
        assertSame(coded([onlyCancelledReturns: true, includeReturnsWithoutShopifyRef: true]),
                data(window(options: [onlyCancelledReturns: true, includeReturnsWithoutShopifyRef: true])), "both")

        Map d = data(window())
        Map<String, Map> byId = ((List<Map>) d.records).collectEntries { [(it.returnId): it] }
        assertEquals(["R1", "R10", "R2", "R5", "R8", "R9"], byId.keySet().sort())
        assertEquals("555", byId.R10.shopifyReturnId, "the older type first")
        assertEquals(["00001", "00002"], ((List<Map>) byId.R1.items)*.returnItemSeqId, "items in returnItemSeqId order")
        assertEquals([20, 5], ((List<Map>) byId.R1.items)*.lineAmount)
        assertEquals(25, byId.R1.returnTotal)
        assertEquals(["SHO1", "NSO1", "RO1", "CM1", 2], [byId.R1.orderExternalId, byId.R1.orderNetsuiteId, byId.R1.orderId,
                                                         byId.R1.netsuiteCreditMemoId, byId.R1.daysBeforeEntry])
        assertEquals(["333", "333"], [byId.R2.shopifyReturnId, byId.R2.externalId], "the third identification, then the join-key fallback")
        assertEquals([[], 0], [byId.R8.items, byId.R8.returnTotal])
        assertEquals(["RO9", "#RO9", "R6", "RETURN_REQUESTED"], [byId.R5.exchangeOrderId, byId.R5.exchangeOrderName,
                                                                 byId.R5.openReturnOnOrderId, byId.R5.openReturnOnOrderStatus])
        assertEquals(["RO11", "NSO11", null], [byId.R9.exchangeOrderId, byId.R9.exchangeOrderNetsuiteId, byId.R9.openReturnOnOrderId])
        assertEquals(2, d.counts.excludedCancelledCount + d.counts.excludedNoShopifyRefCount)
    }

    @Test
    void readByReturnIdWritesTheSameReturns() {
        List<String> keys = ["R1", "R3", "R4", "R6"]
        assertSame(coded([lookupKeys: keys, lookupKeyField: "returnId"]), data([mode: "ids", keys: keys, keyField: "returnId"]), "by id")
        assertSame(coded([lookupKeys: keys, onlyCancelledReturns: true]), data([mode: "ids", keys: keys, options: [onlyCancelledReturns: true]]), "by id, cancelled")
    }

    @Test
    void theConnectorsKeepFieldsAndRulesKeepAndCountTheSameReturns() {
        assertSame(coded([:], BASE), data(window(keep: BASE)), "keep base")
        Map kept = data(window(keep: BASE))
        assertTrue(((List<Map>) kept.records).every { it.containsKey("items") && !it.containsKey("orderName") }, "items always kept, the rest trimmed")
        List raw = [[fieldExpression: "orderProductStoreId", operator: "EXCLUDE_IN", filterValues: "STORE_B", sequenceNum: 1],
                    [fieldExpression: "statusId", operator: "EXCLUDE_IN", filterValues: "RETURN_COMPLETED", sequenceNum: 2]]
        Map c = coded([:], BASE + ["orderProductStoreId"], raw)
        Map d = data(window(keep: BASE + ["orderProductStoreId"]), raw)
        assertSame(c, d, "rules")
        List<Map> configured = (List<Map>) ((Map) ((Map) c.metadata).filters).configuredExclusions
        configured.each { Map rule ->
            int seq = rule.sequenceNum as int
            assertEquals(rule.excludedCount, (d.excludedByRule.get(seq) ?: 0), "excluded by rule ${seq}")
            assertEquals(rule.fieldAbsentCount, (d.fieldAbsentByRule.get(seq) ?: 0), "field absent for rule ${seq}")
        }
        assertTrue(configured.sum { it.excludedCount } >= 2, configured.toString())
    }
}
