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
 * DAR-BE-176. The OMS products read as data (data/HotWaxDataSourceDefinitionsSeedData.xml, OMS_PRODUCTS_GQL_V1) writes
 * the same records and counts as the coded extract#HotWaxOmsGqlProducts (OmsGqlProductsPageSource.extractToFile), on one
 * scripted HotWax server. The fixture hits every keying rule the coded read has: a NetSuite id on two sellable products
 * (the lowest productId AS TEXT owns it: P10 before P9), an expired and a blank identification, a product with two ids
 * (the last read wins), not-selling products kept once per NetSuite id no sellable product holds, dropped when one does
 * or when another not-selling product came first, the reasons' priority, a product with no type, and more products than
 * one page. Records are compared as multisets after the JSON round trip, null and absent as one.
 */
class OmsGqlProductsDataDefinitionTests {

    static final Map CONFIG = OmsGqlOrderItemsDataDefinitionTests.CONFIG
    static final List<String> CONNECTION_FIELDS = OmsGqlOrderItemsDataDefinitionTests.CONNECTION_FIELDS

    private static Map product(String id, String type, Map more = [:]) {
        return [productId: id, internalName: "NAME-${id}".toString(), productTypeId: type, isVirtual: "N", isVariant: "Y",
                createdDate: "2026-01-01T00:00:00Z", salesDiscontinuationDate: null] + more
    }

    static final List<Map> PRODUCTS = [
            product("P01", "FINISHED_GOOD"), product("P03", "FINISHED_GOOD"), product("P02", "FINISHED_GOOD"),
            product("P04", "FINISHED_GOOD"),
            product("P05", "FINISHED_GOOD", [isVirtual: "Y"]),
            product("P06", "FINISHED_GOOD", [salesDiscontinuationDate: "2026-06-01T00:00:00Z"]),
            product("P07", "SERVICE"), product("P08", "RAW_MATERIAL"),
            product("P09", "FINISHED_GOOD"),
            product("P10", "MARKETING_PKG_PICK"), product("P9", "FINISHED_GOOD"),
            product("P11", "FINISHED_GOOD", [isVirtual: "Y", salesDiscontinuationDate: "2026-05-01T00:00:00Z"]),
            product("P12", null),
    ] + (1..120).collect { product("F${String.format('%03d', it)}".toString(), "FINISHED_GOOD") }

    private static Map ident(String product, String value, String thru = null) {
        return [productId: product, idValue: value, thruDate: thru, goodIdentificationTypeId: "NETSUITE_PRODUCT_ID"]
    }

    static final List<Map> IDENTIFICATIONS = [
            ident("P01", "100"), ident("P03", "100"),               // shared: P01 owns, P03 is DUPLICATE
            ident("P04", "999", "2026-02-01T00:00:00Z"),           // expired: P04 has none
            ident("P02", ""),                                       // blank: P02 has none
            ident("P05", "200"), ident("P06", "200"),               // not selling, no sellable holds 200: P05 kept once
            ident("P07", "100"),                                     // not selling, 100 is held: dropped
            ident("P09", "300"), ident("P09", "301"),               // two ids: the last read wins
            ident("P10", "400"), ident("P9", "400"),                // text order: P10 owns
            ident("P11", "500"),                                     // discontinued AND virtual: discontinued
            ident("P12", "600"),
    ] + (1..110).collect { ident("F${String.format('%03d', it)}".toString(), "F${it}".toString()) }

    static class Server {
        final List<Map> calls = Collections.synchronizedList(new ArrayList<Map>())

        Closure sender() {
            return { String url, Map headers, String body ->
                Map req = (Map) new JsonSlurper().parseText(body)
                Map vars = (Map) (req.variables ?: [:])
                String op = ((req.query as String) =~ /query\s+(\w+)/).with { it.find() ? it.group(1) : "?" }
                calls << [op: op, q: vars.q, hasQ: vars.containsKey("q"), first: vars.first]
                String root
                List<Map> rows
                if (op == "OmsProductsSweep") { root = "products"; rows = PRODUCTS }
                else if (op == "OmsNetsuiteIdsSweep") {
                    root = "goodIdentifications"
                    assertEquals("goodIdentificationTypeId:NETSUITE_PRODUCT_ID", vars.q)
                    rows = IDENTIFICATIONS.collect { it.findAll { k, v -> k != "goodIdentificationTypeId" } }
                } else throw new IllegalStateException("unexpected query ${op}")
                int first = (vars.first as Number).intValue()
                int after = vars.after ? (vars.after as String).substring(1) as int : 0
                List<Map> page = rows.drop(after).take(first)
                boolean more = after + first < rows.size()
                return [statusCode: 200, body: JsonOutput.toJson([data: [(root): [edges: page.collect { [node: it] },
                        pageInfo: [hasNextPage: more, endCursor: more ? "c${after + first}".toString() : null]]], errors: []])]
            }
        }
    }

    static Map definitionRow() {
        File seed = ["data/HotWaxDataSourceDefinitionsSeedData.xml", "runtime/component/darpan-hotwax/data/HotWaxDataSourceDefinitionsSeedData.xml"]
                .collect { new File(it) }.find { it.exists() }
        def doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(seed)
        def rows = doc.getElementsByTagName("darpan.datasource.DataSourceDefinition")
        def row = (0..<rows.length).collect { rows.item(it) as org.w3c.dom.Element }.find { it.getAttribute("dataSourceDefinitionId") == "OMS_PRODUCTS_GQL_V1" }
        return [protocol: row.getAttribute("protocol"), statusId: row.getAttribute("statusId"),
                json: row.getElementsByTagName("definitionJson").item(0).textContent]
    }

    static Map definition() {
        Map row = definitionRow()
        return DataSourceDefinitionSupport.parse(row.protocol as String, row.json as String, CONNECTION_FIELDS)
    }

    static Map coded(Server server, List rawFilters = null) {
        File out = File.createTempFile("coded-products-", ".json")
        try {
            Map result = OmsGqlProductsPageSource.extractToFile(new OmsGqlClient(CONFIG, server.sender()), out, rawFilters)
            assertEquals([], result.errors)
            Map file = (Map) new JsonSlurper().parse(out)
            return [records: file.records, metadata: file.metadata]
        } finally {
            out.delete()
        }
    }

    static Map data(Server server, List rawFilters = null) {
        List<Map> records = []
        Map d = definition()
        Map r = new DataSourceRunner(d, new GraphQlTransport(d, CONFIG, server.sender()))
                .run([mode: "all", filters: SourceFilterSupport.parseRules(rawFilters)]) { List<Map> batch -> records.addAll(batch) }
        return r + [records: new JsonSlurper().parseText(JsonOutput.toJson(records))]
    }

    static List<String> canonical(List records) {
        return records.collect { Map r -> JsonOutput.toJson(new TreeMap(r.findAll { k, v -> v != null })) }.sort()
    }

    private static final List<String> COUNTS = ["productsRead", "sellable", "withoutNetsuiteId", "netsuiteIdsOnSeveralProducts", "notSellableWithNetsuiteId"]

    @Test
    void theDefinitionIsValidAndSweepsWithTheCodedDocuments() {
        Map row = definitionRow()
        assertEquals("DRAFT", row.statusId)
        Map d = definition()
        assertEquals(true, d.wholeSet)
        Map<String, Map> q = ((List<Map>) d.steps).findAll { it.kind == "query" }.collectEntries { [(it.id): it] }
        assertEquals(OmsGqlQueries.productsSweepDocument(), q.products.document)
        assertEquals(OmsGqlQueries.netsuiteIdsSweepDocument(), q.netsuiteIds.document)
        assertEquals(OmsGqlQueries.NETSUITE_PRODUCT_ID_FILTER, ((Map) ((Map) q.netsuiteIds.bind).all).variables.q)
        [q.products, q.netsuiteIds].each { assertEquals(OmsGqlQueries.PRODUCT_PAGE_SIZE, ((Map) it.paging).pageSize) }
    }

    @Test
    void theWholeCatalogueGivesTheSameRecordsAndCounts() {
        Server cs = new Server(), ds = new Server()
        Map c = coded(cs)
        Map d = data(ds)
        assertEquals(canonical((List) c.records), canonical((List) d.records))
        COUNTS.each { assertEquals(((Map) c.metadata).get(it), d.counts.get(it) as int, it) }
        assertEquals(true, d.metadata.windowIgnored)
        // Both sweeps paged past one page; the products sweep sends no search, as the coded one sends null.
        assertTrue(ds.calls.count { it.op == "OmsProductsSweep" } >= 2)
        assertEquals([false], ds.calls.findAll { it.op == "OmsProductsSweep" }*.hasQ.unique())

        Map<String, Map> byId = ((List<Map>) d.records).collectEntries { [(it.productId): it] }
        assertEquals("100", byId.P01.nsKey)
        assertEquals([true, "P03", "OWN"], [byId.P01.duplicateNetsuiteId, byId.P01.sharedWith, byId.P01.netsuiteIdState])
        assertEquals(["DUPLICATE:100:P03", "SHARED", "P01"], [byId.P03.nsKey, byId.P03.netsuiteIdState, byId.P03.sharedWith])
        assertEquals("HOTWAX-ONLY:P04", byId.P04.nsKey)
        assertEquals("HOTWAX-ONLY:P02", byId.P02.nsKey)
        assertEquals(["200", "N", "virtual"], [byId.P05.nsKey, byId.P05.sellable, byId.P05.notSellableReason])
        assertTrue(!byId.containsKey("P06") && !byId.containsKey("P07") && !byId.containsKey("P08"))
        assertEquals("301", byId.P09.nsKey)
        assertEquals(["400", "DUPLICATE:400:P9"], [byId.P10.nsKey, byId.P9.nsKey])
        assertEquals("discontinued", byId.P11.notSellableReason)
        assertEquals("Y", byId.P12.sellable)
    }

    @Test
    void theSellableRuleExcludesAndCountsTheSameProducts() {
        List raw = [[fieldExpression: "sellable", operator: "EXCLUDE_IN", filterValues: "N", sequenceNum: 1],
                    [fieldExpression: "netsuiteIdState", operator: "EXCLUDE_IN", filterValues: "SHARED", sequenceNum: 2]]
        Map c = coded(new Server(), raw)
        Map d = data(new Server(), raw)
        assertEquals(canonical((List) c.records), canonical((List) d.records))
        List<Map> configured = (List<Map>) ((Map) ((Map) c.metadata).filters).configuredExclusions
        configured.each { Map rule ->
            int seq = rule.sequenceNum as int
            assertEquals(rule.excludedCount, (d.excludedByRule.get(seq) ?: 0), "excluded by rule ${seq}")
        }
        assertTrue(configured.sum { it.excludedCount } >= 3, configured.toString())
    }
}
