package darpan.hotwax.oms

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
 * DAR-BE-176. The OMS order-items read as data (data/HotWaxDataSourceDefinitionsSeedData.xml, OMS_ORDER_ITEMS_GQL_V1)
 * writes the same records and counts as the coded extract#HotWaxOmsGqlOrderItems path. One scripted HotWax server
 * answers both: the coded OmsGqlClient and the generic GraphQlTransport take the same HTTP sender. Records are compared
 * as multisets (neither path promises an order across orders), after the same JSON round trip the extract file makes.
 *
 * The fixtures hit every edge the coded read has: a line split across two ship groups, an item on a ship group the order
 * did not return, an unkeyed item and an unkeyed order, quantities 0, 2, 3, "1.0", blank and null, a status OMS adds
 * later, missing product and facility names, no customer, an order with no items, and a second day.
 */
class OmsGqlOrderItemsDataDefinitionTests {

    static final Map CONFIG = [baseUrl: "https://oms.test/", authType: "API_KEY", apiToken: "k", omsRestSourceConfigId: "T1"]
    static final List<String> CONNECTION_FIELDS = ["baseUrl", "authType", "username", "password", "apiToken", "headersJson"]
    static final String FROM = "2026-10-01T00:00:00Z"
    static final String THRU = "2026-10-03T00:00:00Z"
    static final Map UNIT_OPTIONS = [emitGrain: "ORDER_LINE_UNIT", applyExchangeExclusion: false, requireShopifyLineId: false]

    private static Map group(String seq, String facility, String method = "STANDARD") {
        return [node: [shipGroupSeqId: seq, facilityId: facility, shipmentMethodTypeId: method]]
    }

    static final List<Map> ORDERS = [
            [orderId: "O1", orderName: "#1", externalId: "SH1", statusId: "ORDER_APPROVED", orderTypeId: "SALES_ORDER",
             orderDate: "2026-10-01T05:00:00Z", grandTotal: "120.50", currencyUomId: "USD",
             paymentPreferences: [[paymentMethodTypeId: "CREDIT_CARD", statusId: "PAYMENT_SETTLED", maxAmount: 120.5]],
             shipGroups: [edges: [group("00001", "F1"), group("00002", "F2", "NEXT_DAY")]],
             billToCustomer: [firstName: "Ada", lastName: " Lovelace "]],
            [orderId: "O2", orderName: "#2", externalId: null, statusId: "ORDER_COMPLETED", orderTypeId: "SALES_ORDER",
             orderDate: "2026-10-01T09:30:00Z", grandTotal: 10, currencyUomId: "USD", paymentPreferences: [],
             shipGroups: [edges: [group("00001", "F3", null)]], billToCustomer: null],
            [orderId: "O4", orderName: "#4", externalId: "SH4", statusId: "ORDER_CREATED", orderTypeId: "SALES_ORDER",
             orderDate: "2026-10-01T20:00:00Z", grandTotal: "0.000", currencyUomId: "USD", paymentPreferences: [],
             shipGroups: [edges: [group("00001", "F1")]], billToCustomer: [firstName: "Bo", lastName: null]],
            [orderId: "O5", orderName: "#5", externalId: "SH5", statusId: "ORDER_APPROVED", orderTypeId: "SALES_ORDER",
             orderDate: "2026-10-02T11:00:00Z", grandTotal: "15", currencyUomId: "USD", paymentPreferences: [],
             shipGroups: [edges: [group("00001", "F2")]], billToCustomer: [firstName: null, lastName: "Cy"]],
    ]

    private static Map item(String order, String seq, String ext, Object qty, String status, String groupSeq, String product) {
        return [orderId: order, orderItemSeqId: seq, externalId: ext, productId: product, quantity: qty, statusId: status, shipGroupSeqId: groupSeq]
    }

    static final List<Map> ITEMS = [
            item("O1", "00001", "L1", 1, "ITEM_COMPLETED", "00001", "P1"),
            item("O1", "00002", "L2", 2, "ITEM_APPROVED", "00002", "P2"),
            item("O1", "00003", "L1", 1, "ITEM_CANCELLED", "00002", "P1"),    // line L1 again, on the other group
            item("O1", "00004", null, "1.0", "ITEM_CREATED", "00001", "P3"),   // unkeyed; "1.0" is no unit; no product name
            item("O1", "00005", "L3", 0, "ITEM_COMPLETED", "00001", "P1"),     // no unit
            item("O1", "00006", "L4", null, "ITEM_APPROVED", "00009", "P2"),   // a ship group the order did not return
            item("O1", "00007", "L5", "", "ITEM_REJECTED", "00001", "P2"),     // blank quantity is one unit; OTHER
            item("O1", "00010", "L1", 1, "ITEM_COMPLETED", "00001", "P1"),     // text order: 00010 after 00003
            item("O2", "00001", "L9", 3, "ITEM_COMPLETED", "00001", "P2"),     // the order has no Shopify id
            item("O5", "00001", "L7", 1, "ITEM_COMPLETED", "00001", "P1"),
    ]
    static final List<Map> PRODUCTS = [[productId: "P1", internalName: "BOLT-1"], [productId: "P2", internalName: "NUT-2"]]
    static final List<Map> FACILITIES = [[facilityId: "F1", facilityName: "North"], [facilityId: "F2", facilityName: "South"]]

    /** A HotWax moqui-gql stand-in: filters by the search string, pages by first/after, refuses cost when asked to. */
    static class Server {
        final List<Map> calls = Collections.synchronizedList(new ArrayList<Map>())
        Integer refuseCostAbove = null

        Closure sender() {
            return { String url, Map headers, String body ->
                Map req = (Map) new JsonSlurper().parseText(body)
                Map vars = (Map) (req.variables ?: [:])
                String op = ((req.query as String) =~ /query\s+(\w+)/).with { it.find() ? it.group(1) : "?" }
                calls << [url: url, apiKey: headers.api_key, op: op, q: vars.q, first: vars.first]
                int first = (vars.first as Number).intValue()
                if (refuseCostAbove != null && first > refuseCostAbove) {
                    return [statusCode: 200, body: JsonOutput.toJson([data: null, errors: [[message: "cost", extensions: [code: "COST_EXCEEDED"]]]])]
                }
                String root
                List<Map> rows
                switch (op) {
                    case "OmsOrderGrain": root = "orders"; rows = ORDERS.findAll { matches(it, (String) vars.q) }; break
                    case "OmsUnitOrderItems": root = "orderItems"; rows = ITEMS.findAll { matches(it, (String) vars.q) }; break
                    case "OmsProductNames": root = "products"; rows = PRODUCTS.findAll { matches(it, (String) vars.q) }; break
                    case "OmsFacilityNames": root = "facilities"; rows = FACILITIES.findAll { matches(it, (String) vars.q) }; break
                    case "OmsExchangeAssocs": root = "orderItemAssocs"; rows = []; break
                    default: throw new IllegalStateException("unexpected query ${op}")
                }
                int after = vars.after ? (vars.after as String).substring(1) as int : 0
                List<Map> page = rows.drop(after).take(first)
                boolean more = after + first < rows.size()
                return [statusCode: 200, body: JsonOutput.toJson([data: [(root): [edges: page.collect { [node: it] },
                        pageInfo: [hasNextPage: more, endCursor: more ? "c${after + first}".toString() : null]]], errors: []])]
            }
        }

        private static boolean matches(Map row, String q) {
            for (String term : q.split(" ")) {
                if (term == "orderTypeId:SALES_ORDER") { if (row.containsKey("orderTypeId") && row.orderTypeId != "SALES_ORDER") return false; continue }
                def range = term =~ /^orderDate:(>=|<)(.+)$/
                if (range.find()) {
                    String d = row.orderDate as String
                    if (range.group(1) == ">=" ? d < range.group(2) : d >= range.group(2)) return false
                    continue
                }
                int colon = term.indexOf(':')
                String field = term.substring(0, colon)
                List<String> ids = term.substring(colon + 1).split(",") as List<String>
                if (!ids.contains(row.get(field)?.toString())) return false
            }
            return true
        }
    }

    static Map definitionRow() {
        File seed = ["data/HotWaxDataSourceDefinitionsSeedData.xml", "runtime/component/darpan-hotwax/data/HotWaxDataSourceDefinitionsSeedData.xml"]
                .collect { new File(it) }.find { it.exists() }
        def doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(seed)
        def rows = doc.getElementsByTagName("darpan.datasource.DataSourceDefinition")
        def row = (0..<rows.length).collect { rows.item(it) as org.w3c.dom.Element }.find { it.getAttribute("dataSourceDefinitionId") == "OMS_ORDER_ITEMS_GQL_V1" }
        String json = row.getElementsByTagName("definitionJson").item(0).textContent
        return [protocol: row.getAttribute("protocol"), statusId: row.getAttribute("statusId"), json: json]
    }

    static Map definition() {
        Map row = definitionRow()
        return DataSourceDefinitionSupport.parse(row.protocol as String, row.json as String, CONNECTION_FIELDS)
    }

    /** The coded path: extract#HotWaxOmsGqlOrderItems's own call, through a real OmsGqlClient on the scripted server. */
    static Map coded(Server server, Map fetchOptions = [:], List rawFilters = null) {
        OmsGqlClient client = new OmsGqlClient(CONFIG, server.sender())
        File out = File.createTempFile("coded-items-", ".json")
        try {
            Map result = OmsExtractSupport.extractOrdersToFile(CONFIG, FROM, THRU, out, null, null, rawFilters, UNIT_OPTIONS,
                    OmsGqlOrderPageSource.fetcher(client, fetchOptions))
            assertEquals([], result.errors, "coded extract errors")
            Map file = (Map) new JsonSlurper().parse(out)
            return [records: file.records, metadata: file.metadata]
        } finally {
            out.delete()
        }
    }

    /** The data path: the definition through the generic transport and runner. */
    static Map data(Server server, Map request, List rawFilters = null) {
        List<Map> records = []
        Map r = new DataSourceRunner(definition(), new GraphQlTransport(definition(), CONFIG, server.sender()))
                .run(request + [filters: SourceFilterSupport.parseRules(rawFilters)]) { List<Map> batch -> records.addAll(batch) }
        return r + [records: new JsonSlurper().parseText(JsonOutput.toJson(records))]
    }

    static List<String> canonical(List records) {
        return records.collect { JsonOutput.toJson(new TreeMap((Map) it)) }.sort()
    }

    static long millis(String iso) { return java.time.Instant.parse(iso).toEpochMilli() }

    private static void assertSameCounts(Map codedMeta, Map data) {
        ["keptNullShopifyIdCount", "nonUnitQuantityCount"].each { String c ->
            assertEquals(codedMeta.get(c), data.counts.get(c) as int, c)
        }
        assertEquals(codedMeta.droppedNullLineIdCount, data.metadata.droppedNullLineIdCount)
    }

    @Test
    void theDefinitionIsValidAndItsQueriesAreTheCodedOnesWordForWord() {
        Map row = definitionRow()
        assertEquals("GRAPHQL", row.protocol)
        assertEquals("DRAFT", row.statusId)
        Map d = definition()
        Map<String, String> docs = ((List<Map>) d.steps).findAll { it.kind == "query" }.collectEntries { [(it.id): it.document] }
        assertEquals(OmsGqlQueries.sharedOrdersPlan().document, docs.orders)
        assertEquals(OmsGqlQueries.unitOrderItemsDocument(), docs.items)
        assertEquals(OmsGqlQueries.productNamesDocument(), docs.products)
        assertEquals(OmsGqlQueries.facilityNamesDocument(), docs.facilities)
        Map orders = ((List<Map>) d.steps)[0]
        assertEquals(OmsGqlQueries.sharedOrdersPlan().reservation, orders.reservation)
        Map paging = (Map) orders.paging
        assertEquals(OmsGqlQueries.sharedOrdersPlan().pageSize,
                (int) Math.floor((((Map) paging.pageSize).budget as double) / (((Map) paging.pageSize).costPerRecord as double)))
        // The filter step offers exactly the fields the coded unit read lets a rule read.
        assertEquals(OmsGqlOrderPageSource.UNIT_FILTER_FIELDS, (((List<Map>) d.steps).find { it.kind == "filter" }.fields as Set))
    }

    @Test
    void aTwoDayWindowWritesTheSameRecordsAndCounts() {
        Map c = coded(new Server())
        Server s = new Server()
        Map d = data(s, [mode: "window", from: millis(FROM), thru: millis(THRU)])
        assertEquals(canonical((List) c.records), canonical((List) d.records))
        assertTrue(((List) c.records).size() > 10, "the fixture must produce units: ${((List) c.records).size()}")
        assertSameCounts((Map) c.metadata, d)
        // Signed in as the connection says, at the connection's URL.
        assertEquals("https://oms.test/rest/s1/graphql", s.calls[0].url)
        assertEquals("k", s.calls[0].apiKey)
    }

    @Test
    void readByOrderIdOrShopifyIdWritesTheSameRecords() {
        Map c = coded(new Server(), [lookupKeys: ["O1", "O2", "O5"], lookupKeyField: "orderId"])
        Map d = data(new Server(), [mode: "ids", keys: ["O1", "O2", "O5"], keyField: "orderId"])
        assertEquals(canonical((List) c.records), canonical((List) d.records))
        assertSameCounts((Map) c.metadata, d)
        Map c2 = coded(new Server(), [lookupKeys: ["SH1"], lookupKeyField: "externalId"])
        Map d2 = data(new Server(), [mode: "ids", keys: ["SH1"], keyField: "externalId"])
        assertEquals(canonical((List) c2.records), canonical((List) d2.records))
        // No key field: orderId, as the coded read defaults.
        Map d3 = data(new Server(), [mode: "ids", keys: ["O5"]])
        assertEquals(canonical((List) coded(new Server(), [lookupKeys: ["O5"]]).records), canonical((List) d3.records))
    }

    @Test
    void exclusionRulesKeepAndCountTheSameUnits() {
        List raw = [[fieldExpression: "unitState", operator: "EXCLUDE_IN", filterValues: "CANCELLED", sequenceNum: 1],
                    [fieldExpression: "facilityId", operator: "INCLUDE_IN", filterValues: "F1,F2", sequenceNum: 2],
                    [fieldExpression: "orderName", operator: "EXCLUDE_IN", filterValues: "#5", sequenceNum: 3]]
        Map c = coded(new Server(), [:], raw)
        Map d = data(new Server(), [mode: "window", from: millis(FROM), thru: millis(THRU)], raw)
        assertEquals(canonical((List) c.records), canonical((List) d.records))
        List<Map> configured = (List<Map>) ((Map) ((Map) c.metadata).filters).configuredExclusions
        configured.each { Map rule ->
            int seq = rule.sequenceNum as int
            assertEquals(rule.excludedCount, (d.excludedByRule.get(seq) ?: 0), "excluded by rule ${seq}")
            assertEquals(rule.fieldAbsentCount, (d.fieldAbsentByRule.get(seq) ?: 0), "field absent for rule ${seq}")
        }
        assertTrue(configured.sum { it.excludedCount + it.fieldAbsentCount } > 0, "the rules must exclude something")
    }

    @Test
    void aCostRefusalHalvesThePageOnBothPathsAndTheRecordsStayTheSame() {
        Server cs = new Server(refuseCostAbove: 10)
        Server ds = new Server(refuseCostAbove: 10)
        Map c = coded(cs)
        Map d = data(ds, [mode: "window", from: millis(FROM), thru: millis(THRU)])
        assertEquals(canonical((List) c.records), canonical((List) d.records))
        assertTrue(ds.calls.any { it.first <= 10 })
    }
}
