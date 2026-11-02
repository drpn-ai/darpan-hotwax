package darpan.hotwax.oms

import darpan.datasource.DataSourceDefinitionSupport
import darpan.datasource.DataSourceRunner
import darpan.datasource.GraphQlTransport
import darpan.datasource.RecordConditions
import darpan.reconciliation.source.SourceFilterSupport
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-176. The OMS sales-orders read as data (data/HotWaxDataSourceDefinitionsSeedData.xml, OMS_ORDERS_GQL_V1) writes
 * the same records, counts and exchange manifest as the coded extract#HotWaxOmsGqlOrders path, and asks HotWax for the
 * same fields at the same page size for every set of fields a run keeps or filters on.
 *
 * One scripted HotWax server answers both paths, and answers like the real one where it matters: only the fields the
 * document selects come back, payment rows stop at the `first:` asked, and (optionally) the exchange type term is
 * ignored, so a wrong selection shows up as a different record, not as a pass.
 *
 * Records are compared as multisets after the extract file's JSON round trip, with null and absent taken as the same
 * (the coded read writes a selected field HotWax left null; the data read leaves an optional field out): every reader
 * looks a field up by path, where both give null.
 */
class OmsGqlOrdersDataDefinitionTests {

    static final Map CONFIG = OmsGqlOrderItemsDataDefinitionTests.CONFIG
    static final List<String> CONNECTION_FIELDS = OmsGqlOrderItemsDataDefinitionTests.CONNECTION_FIELDS
    static final String FROM = "2026-10-01T00:00:00Z"
    static final String THRU = "2026-10-03T00:00:00Z"
    /** The OMS_GQL connector's keepFieldsBase (darpan/data/SourceSystemConnectorSeedData.xml). */
    static final List<String> BASE = ["orderId", "orderName", "externalId", "grandTotal", "orderDate", "statusId", "currencyUomId",
                                      "billToCustomer", "shipGroups"]

    private static Map opp(String method, String status, Object amount, String ref) {
        return [paymentMethodTypeId: method, statusId: status, maxAmount: amount, manualRefNum: ref]
    }

    private static Map order(Map m) {
        return [orderTypeId: "SALES_ORDER", currencyUomId: "USD", statusFlowId: "STANDARD", productStoreId: "STORE_A",
                salesChannelEnumId: "WEB_SALES_CHANNEL", orderItemCount: 1, lastUpdatedTxStamp: "2026-10-02T00:00:00Z",
                identifications: [], notes: [], statuses: [], billToCustomer: null,
                shipGroups: [edges: [[node: [shipGroupSeqId: "00001", facilityId: "F1", shipmentMethodTypeId: "STANDARD"]]]]] + m
    }

    static final List<Map> ORDERS = [
            order(orderId: "O1", orderName: "#1", externalId: "SH1", statusId: "ORDER_APPROVED", orderDate: "2026-10-01T05:00:00Z",
                    grandTotal: "120.50", paymentPreferences: [opp("CREDIT_CARD", "PAYMENT_SETTLED", 120.5, " t1 ")],
                    billToCustomer: [firstName: "Ada", lastName: null],
                    identifications: [[orderIdentificationTypeId: "SHOPIFY_ORD_ID", idValue: "111"]],
                    notes: [[note: [noteInfo: "duplicate order"]]],
                    statuses: [[statusId: "ORDER_APPROVED", statusDatetime: "2026-10-01T05:01:00Z", orderItemSeqId: null]]),
            order(orderId: "O2", orderName: "#2", externalId: null, statusId: "ORDER_COMPLETED", orderDate: "2026-10-01T09:30:00Z",
                    grandTotal: 10, paymentPreferences: [], productStoreId: "STORE_B"),
            // An exchange's original: left out, listed in the manifest.
            order(orderId: "O3", orderName: "#3", externalId: " SH3 ", statusId: "ORDER_COMPLETED", orderDate: "2026-10-01T11:00:00Z",
                    grandTotal: "40.00", paymentPreferences: [opp("CREDIT_CARD", "PAYMENT_SETTLED", 40, "t9")]),
            // Five payment rows: two slots are full, so are five; refs repeat and one is blank.
            order(orderId: "O4", orderName: "#4", externalId: "SH4", statusId: "ORDER_APPROVED", orderDate: "2026-10-01T20:00:00Z",
                    grandTotal: "0.000", paymentPreferences: [opp("CREDIT_CARD", "PAYMENT_SETTLED", 1, "t2"), opp("CASH", "PAYMENT_RECEIVED", 2, "t3"),
                    opp("CREDIT_CARD", "PAYMENT_SETTLED", 3, "t2"), opp("GIFT_CARD", "PAYMENT_SETTLED", 4, "t4"), opp("CREDIT_CARD", "PAYMENT_SETTLED", 5, "t5")]),
            // Two rows: full at two slots, not at five.
            order(orderId: "O5", orderName: "#5", externalId: "SH5", statusId: "ORDER_CREATED", orderDate: "2026-10-02T02:00:00Z",
                    grandTotal: "15", paymentPreferences: [opp("CREDIT_CARD", "PAYMENT_AUTHORIZED", 7, "t6"), opp(null, "PAYMENT_NOT_RECEIVED", 8, "")]),
            // A non-exchange association (when the server ignores the type term): kept on both paths.
            order(orderId: "O6", orderName: "#6", externalId: "SH6", statusId: "ORDER_APPROVED", orderDate: "2026-10-02T09:00:00Z",
                    grandTotal: "9.99", paymentPreferences: [opp("CREDIT_CARD", "PAYMENT_SETTLED", 9.99, "t7")], productStoreId: null),
            order(orderId: "O7", orderName: "#7", externalId: "SH7", statusId: "ORDER_COMPLETED", orderDate: "2026-10-02T23:59:59Z",
                    grandTotal: "1", paymentPreferences: [opp("CASH", "PAYMENT_RECEIVED", 1, null)]),
            order(orderId: "R1", orderName: "#R1", externalId: "SHR1", orderTypeId: "RETURN_ORDER", statusId: "ORDER_APPROVED",
                    orderDate: "2026-10-01T06:00:00Z", grandTotal: "5", paymentPreferences: []),
    ]
    static final List<Map> ASSOCS = [
            [orderId: "O3", orderItemSeqId: "00001", toOrderId: "O9", toOrderItemSeqId: "00001", orderItemAssocTypeId: "EXCHANGE"],
            [orderId: "O3", orderItemSeqId: "00002", toOrderId: "O10", toOrderItemSeqId: "00001", orderItemAssocTypeId: "EXCHANGE"],
            [orderId: "O6", orderItemSeqId: "00001", toOrderId: "O11", toOrderItemSeqId: "00001", orderItemAssocTypeId: "REPLACEMENT"],
    ]

    /** A HotWax moqui-gql stand-in that returns only what the document selects. */
    static class Server {
        final List<Map> calls = Collections.synchronizedList(new ArrayList<Map>())
        Integer refuseCostAbove = null
        boolean ignoreAssocType = false

        Closure sender() {
            return { String url, Map headers, String body ->
                Map req = (Map) new JsonSlurper().parseText(body)
                Map vars = (Map) (req.variables ?: [:])
                String query = req.query as String
                String op = (query =~ /query\s+(\w+)/).with { it.find() ? it.group(1) : "?" }
                calls << [op: op, q: vars.q, first: vars.first, query: query]
                int first = (vars.first as Number).intValue()
                if (refuseCostAbove != null && op == "OmsOrderGrain" && first > refuseCostAbove) {
                    return [statusCode: 200, body: JsonOutput.toJson([data: null, errors: [[message: "cost", extensions: [code: "COST_EXCEEDED"]]]])]
                }
                String root
                List<Map> rows
                if (op == "OmsOrderGrain") {
                    root = "orders"
                    rows = ORDERS.findAll { matches(it, (String) vars.q) }.collect { selected(it, query) }
                } else if (op == "OmsExchangeAssocs") {
                    root = "orderItemAssocs"
                    rows = ASSOCS.findAll { matches(it, (String) vars.q) }
                } else {
                    throw new IllegalStateException("unexpected query ${op}")
                }
                int after = vars.after ? (vars.after as String).substring(1) as int : 0
                List<Map> page = rows.drop(after).take(first)
                boolean more = after + first < rows.size()
                return [statusCode: 200, body: JsonOutput.toJson([data: [(root): [edges: page.collect { [node: it] },
                        pageInfo: [hasNextPage: more, endCursor: more ? "c${after + first}".toString() : null]]], errors: []])]
            }
        }

        /** The order as HotWax answers this document: selected top-level fields, payment rows cut at first: and their selected fields. */
        private static Map selected(Map order, String query) {
            Set<String> words = (query =~ /[A-Za-z_]+/).collect { it } as Set<String>
            Map out = new LinkedHashMap()
            order.each { k, v -> if (words.contains(k)) out.put(k, v) }
            def slots = query =~ /paymentPreferences\(first: (\d+)\) \{([^}]*)\}/
            if (slots.find() && out.containsKey("paymentPreferences")) {
                Set<String> rowFields = slots.group(2).trim().split(/\s+/) as Set<String>
                out.paymentPreferences = ((List<Map>) order.paymentPreferences).take(slots.group(1) as int)
                        .collect { Map row -> row.findAll { k, v -> rowFields.contains(k) } }
            }
            return out
        }

        private boolean matches(Map row, String q) {
            for (String term : q.split(" ")) {
                if (term == "orderTypeId:SALES_ORDER") { if (row.orderTypeId != "SALES_ORDER") return false; continue }
                if (term.startsWith("orderItemAssocTypeId:")) { if (!ignoreAssocType && row.orderItemAssocTypeId != term.substring(21)) return false; continue }
                def range = term =~ /^orderDate:(>=|<)(.+)$/
                if (range.find()) {
                    String d = row.orderDate as String
                    if (range.group(1) == ">=" ? d < range.group(2) : d >= range.group(2)) return false
                    continue
                }
                int colon = term.indexOf(':')
                List<String> ids = term.substring(colon + 1).split(",") as List<String>
                if (!ids.contains(row.get(term.substring(0, colon))?.toString()?.trim())) return false
            }
            return true
        }
    }

    static Map definitionRow() {
        File seed = ["data/HotWaxDataSourceDefinitionsSeedData.xml", "runtime/component/darpan-hotwax/data/HotWaxDataSourceDefinitionsSeedData.xml"]
                .collect { new File(it) }.find { it.exists() }
        def doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(seed)
        def rows = doc.getElementsByTagName("darpan.datasource.DataSourceDefinition")
        def row = (0..<rows.length).collect { rows.item(it) as org.w3c.dom.Element }.find { it.getAttribute("dataSourceDefinitionId") == "OMS_ORDERS_GQL_V1" }
        return [protocol: row.getAttribute("protocol"), statusId: row.getAttribute("statusId"),
                json: row.getElementsByTagName("definitionJson").item(0).textContent]
    }

    static Map definition() {
        Map row = definitionRow()
        return DataSourceDefinitionSupport.parse(row.protocol as String, row.json as String, CONNECTION_FIELDS)
    }

    /** The coded path: extract#HotWaxOmsGqlOrders's own call (extractOmsGqlOrders.groovy), on the scripted server. */
    static Map coded(Server server, List keep = null, Map fetchOptions = [:], List rawFilters = null) {
        File out = File.createTempFile("coded-orders-", ".json")
        try {
            Map result = OmsExtractSupport.extractOrdersToFile(CONFIG, FROM, THRU, out, keep, null, rawFilters, null,
                    OmsGqlOrderPageSource.fetcher(new OmsGqlClient(CONFIG, server.sender()), fetchOptions))
            assertEquals([], result.errors, "coded extract errors")
            Map file = (Map) new JsonSlurper().parse(out)
            return [records: file.records, metadata: file.metadata,
                    manifest: new JsonSlurper().parseText(JsonOutput.toJson(result.exchangeManifest ?: [])),
                    truncated: result.exchangeManifestTruncated == true]
        } finally {
            out.delete()
        }
    }

    /** The data path: the definition through the generic transport and runner. */
    static Map data(Server server, Map request, List rawFilters = null) {
        List<Map> records = []
        Map d = definition()
        Map r = new DataSourceRunner(d, new GraphQlTransport(d, CONFIG, server.sender()))
                .run(request + [filters: SourceFilterSupport.parseRules(rawFilters)]) { List<Map> batch -> records.addAll(batch) }
        Map manifest = ((List<Map>) r.sidecars).find { it.suffix == "exchange-manifest" } ?: [entries: [], truncated: false]
        return r + [records: new JsonSlurper().parseText(JsonOutput.toJson(records)),
                    manifest: new JsonSlurper().parseText(JsonOutput.toJson(manifest.entries)), truncated: manifest.truncated]
    }

    static Map window(List keep = null) {
        return [mode: "window", from: millis(FROM), thru: millis(THRU), keep: keep]
    }

    static long millis(String iso) { return java.time.Instant.parse(iso).toEpochMilli() }

    /** Null and absent are one: see the class note. */
    static List<String> canonical(List records) {
        return records.collect { Map r -> JsonOutput.toJson(new TreeMap(r.findAll { k, v -> v != null })) }.sort()
    }

    private static void assertSame(Map c, Map d, String label) {
        assertEquals(canonical((List) c.records), canonical((List) d.records), "${label}: records")
        assertEquals(((Map) ((Map) c.metadata).filters).excludedExchangeOrderCount, d.counts.excludedExchangeOrderCount as int, "${label}: exchange count")
        assertEquals(canonical((List) c.manifest), canonical((List) d.manifest), "${label}: manifest")
        assertEquals(c.truncated, d.truncated, "${label}: manifest truncated")
    }

    // ---------------------------------------------------------------- the plan

    private static List<String> tokens(String document) {
        return document.split(/[\s{}]+/).findAll { it }.sort()
    }

    @Test
    void forEveryFieldSetTheDefinitionAsksTheCodedFieldsAtTheCodedPageSize() {
        Map row = definitionRow()
        assertEquals("GRAPHQL", row.protocol)
        assertEquals("DRAFT", row.statusId)
        Map d = definition()
        Map orders = ((List<Map>) d.steps)[0]
        Map pageSize = (Map) ((Map) orders.paging).pageSize
        List<List<String>> fieldSets = [
                [], ["orderId"], BASE, BASE + ["paymentRefNums"], BASE + ["paymentRefNums", "paymentMethodTypeIds"],
                BASE + ["paymentPreferences"], BASE + ["paymentPreferences", "paymentRefNums"], ["paymentRefNums"],
                BASE + ["notes", "statuses", "identifications", "productStoreId", "salesChannelEnumId", "statusFlowId",
                        "orderItemCount", "lastUpdatedTxStamp", "isCompleted", "hasPaymentPreference"],
        ]
        fieldSets.each { List<String> wanted ->
            Map plan = OmsGqlQueries.orderGrainPagePlan(wanted as Set<String>)
            List<Map> on = ((List<Map>) orders.selections).findAll { RecordConditions.matches([wanted: wanted], it.when) }
            String document = DataSourceDefinitionSupport.fillSlots((String) orders.document, on)
            assertEquals(tokens((String) plan.document), tokens(document), "fields asked for ${wanted}")
            double cost = (pageSize.costPerRecord as double) + ((on.collect { (it.cost ?: 0) as double }.sum() ?: 0d) as double)
            int size = Math.min(pageSize.max as int, Math.max(1, (int) Math.floor((pageSize.budget as double) / cost)))
            assertEquals(plan.pageSize, size, "page size for ${wanted}")
        }
        Map assocs = ((List<Map>) d.steps).find { it.id == "assocs" }
        assertEquals(OmsGqlQueries.exchangeAssocsDocument(), assocs.document)
        assertEquals(OmsGqlQueries.ASSOC_PAGE_SIZE, ((Map) assocs.paging).pageSize)
        assertEquals(OmsGqlQueries.ASSOC_RESERVATION, assocs.reservation)
        // A rule may read exactly the fields the coded read lets one read.
        Set<String> offered = (OmsGqlQueries.HEADER_FIELDS + OmsGqlQueries.DERIVED_FIELDS + OmsGqlQueries.NESTED_FIELDS.keySet() +
                [OmsGqlQueries.RAW_PAYMENTS_FIELD]) as Set<String>
        assertEquals(offered, (((List<Map>) d.steps).find { it.kind == "filter" }.fields as Set))
    }

    // ---------------------------------------------------------------- the records

    @Test
    void aTwoDayWindowWritesTheSameOrdersCountsAndManifest() {
        Map c = coded(new Server())
        Map d = data(new Server(), window())
        assertSame(c, d, "no projection")
        assertEquals(["O1", "O2", "O4", "O5", "O6", "O7"], ((List<Map>) d.records)*.orderId.sort())
        assertEquals(1, d.counts.excludedExchangeOrderCount)
        assertEquals([[omsOrderId: "O3", externalId: "SH3", orderName: "#3", toOrderId: "O9", grandTotal: 40.00,
                       orderDate: millis("2026-10-01T11:00:00Z"), statusId: "ORDER_COMPLETED"]], d.manifest)
    }

    @Test
    void theConnectorsKeepFieldsAndEachPaymentReadWriteTheSameOrders() {
        [BASE, BASE + ["paymentRefNums"], BASE + ["paymentRefNums", "paymentMethodTypeIds"], BASE + ["paymentPreferences"],
         BASE + ["paymentPreferences", "paymentRefNums"], ["orderId", "paymentRefNums"],
         BASE + ["notes", "statuses", "identifications", "productStoreId", "isCompleted", "hasPaymentPreference", "paymentPreferenceSlotsFull"]]
                .each { List<String> keep -> assertSame(coded(new Server(), keep), data(new Server(), window(keep)), "keep ${keep}") }

        Map refs = data(new Server(), window(BASE + ["paymentRefNums"]))
        Map<String, Map> byId = ((List<Map>) refs.records).collectEntries { [(it.orderId): it] }
        assertEquals("t1", byId.O1.paymentRefNums)
        assertEquals(null, byId.O4.paymentRefNums, "five rows fill five slots: no list is claimed")
        assertEquals("t6", byId.O5.paymentRefNums, "two rows do not fill five slots")
        assertEquals((BASE + ["paymentRefNums"]) as Set, byId.O1.keySet())
        assertTrue(!byId.O4.containsKey("paymentRefNums"), "a list the slots cut short is not written")
        Map raw = data(new Server(), window(["orderId", "paymentPreferences"]))
        assertTrue(((List<Map>) raw.records).every { it.containsKey("paymentPreferenceSlotsFull") }, "kept with the raw rows")
    }

    @Test
    void readByOrderIdOrShopifyIdWritesTheSameOrders() {
        Map c = coded(new Server(), BASE, [lookupKeys: ["O1", "O3", "O5"], lookupKeyField: "orderId"])
        Map d = data(new Server(), [mode: "ids", keys: ["O1", "O3", "O5"], keyField: "orderId", keep: BASE])
        assertSame(c, d, "by orderId")
        Map c2 = coded(new Server(), BASE, [lookupKeys: ["SH4", "SHR1"], lookupKeyField: "externalId"])
        Map d2 = data(new Server(), [mode: "ids", keys: ["SH4", "SHR1"], keyField: "externalId", keep: BASE])
        assertSame(c2, d2, "by externalId")
        assertEquals(["O4"], ((List<Map>) d2.records)*.orderId, "a return order is not a sales order")
    }

    @Test
    void exclusionRulesAskTheirFieldsAndKeepAndCountTheSameOrders() {
        List raw = [[fieldExpression: "productStoreId", operator: "EXCLUDE_IN", filterValues: "STORE_B", sequenceNum: 1],
                    [fieldExpression: "isCompleted", operator: "EXCLUDE_IN", filterValues: "Y", sequenceNum: 2],
                    [fieldExpression: "paymentMethodTypeIds", operator: "EXCLUDE_IN", filterValues: "CREDIT_CARD", sequenceNum: 3]]
        Map c = coded(new Server(), BASE, [:], raw)
        Map d = data(new Server(), window(BASE), raw)
        assertSame(c, d, "rules")
        List<Map> configured = (List<Map>) ((Map) ((Map) c.metadata).filters).configuredExclusions
        configured.each { Map rule ->
            int seq = rule.sequenceNum as int
            assertEquals(rule.excludedCount, (d.excludedByRule.get(seq) ?: 0), "excluded by rule ${seq}")
            assertEquals(rule.fieldAbsentCount, (d.fieldAbsentByRule.get(seq) ?: 0), "field absent for rule ${seq}")
        }
        assertTrue(configured.sum { it.excludedCount } >= 3, "the rules must exclude something: ${configured}")
    }

    @Test
    void aNonExchangeAssociationNeverLeavesAnOrderOutEvenWhenTheServerIgnoresTheType() {
        Map c = coded(new Server(ignoreAssocType: true))
        Map d = data(new Server(ignoreAssocType: true), window())
        assertSame(c, d, "server ignores the type term")
        assertTrue(((List<Map>) d.records).any { it.orderId == "O6" })
        assertEquals(1, d.counts.ignoredNonExchangeAssocCount)
    }

    @Test
    void aFieldTheReadCannotCarryIsRefusedOnBothPaths() {
        // The coded read answers the refusal as an extract error; the data read throws, and the edge turns that into one.
        Map result = OmsExtractSupport.extractOrdersToFile(CONFIG, FROM, THRU, File.createTempFile("coded-refused-", ".json"),
                ["orderId", "colour"], null, null, null, OmsGqlOrderPageSource.fetcher(new OmsGqlClient(CONFIG, new Server().sender()), [:]))
        assertTrue(((List) result.errors).toString().contains("colour"), result.errors.toString())
        IllegalArgumentException e = assertThrows(IllegalArgumentException) { data(new Server(), window(["orderId", "colour"])) }
        assertTrue(e.message.contains("cannot keep [colour]"), e.message)
    }

    @Test
    void aCostRefusalHalvesThePageOnBothPathsAndTheOrdersStayTheSame() {
        Server ds = new Server(refuseCostAbove: 2)
        assertSame(coded(new Server(refuseCostAbove: 2), BASE), data(ds, window(BASE)), "cost refused")
        assertTrue(ds.calls.any { it.op == "OmsOrderGrain" && (it.first as int) <= 2 })
    }
}
