package darpan.hotwax.oms

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.xml.XmlSlurper
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-157. OMS_PRODUCTS_GQL, HotWax's side of the NetSuite product reconciliation, is a seed-artefact fact no runtime
 * proves before a live run: its own service and expectedSourceConfigType (the two resolvers take the first enabled match),
 * an endpoint row under OMS (the gate treats an endpoint with none as DISABLED), windowed N (a whole-catalogue read an audit
 * must not narrow), and an extract that writes the usual {records, metadata} document whatever window it is handed.
 */
class OmsGqlProductsConnectorWiringTests {

    private static File file(String relativeToHotwax) {
        File f = new File(System.getProperty("user.dir"), relativeToHotwax)
        assertTrue(f.exists(), "missing artefact: " + f.absolutePath)
        return f
    }

    private static final String SEED = "../darpan/data/SourceSystemConnectorSeedData.xml"
    private static final String FIELDS = "../darpan/data/SourceSystemConnectorSeedDataFields.xml"
    private static final String ENUMS = "../darpan/data/DarpanSystemSourceSeedData.xml"
    private static final String SERVICES = "service/reconciliation/HotWaxOmsExtractionServices.xml"
    private static final String SCRIPT = "src/main/groovy/darpan/hotwax/reconciliation/automation/extractOmsGqlProducts.groovy"

    private static List<Map<String, String>> connectors() {
        return new XmlSlurper().parse(file(SEED)).'**'.findAll { it.name() == "darpan.reconciliation.SourceSystemConnector" }
                .collect { it.attributes().collectEntries { k, v -> [(k.toString()): v.toString()] } as Map<String, String> }
    }

    @Test
    void theProductsRowHasItsOwnServiceTypeAndIsUnwindowed() {
        Map<String, String> r = connectors().find { it.systemEnumId == "OMS_PRODUCTS_GQL" }
        assertTrue(r != null, "no OMS_PRODUCTS_GQL connector")
        assertEquals("reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsGqlProducts", r.extractServiceName)
        assertEquals("HOTWAX_OMS_GQL_PRODUCTS", r.expectedSourceConfigType)
        assertEquals("darpan.hotwax.HotWaxOmsRestSourceConfig", r.configEntityName)
        assertEquals("omsRestSourceConfigId", r.configParameterName)
        assertEquals("N", r.windowed)
        // DAR-BE-318 (3b2b-9): the endpoint takes Keep. R22 excludes sellable N on this side; since Task 19 Spark applies it.
        assertEquals("sourceFilters", r.filterParameterName, "R22 excludes sellable N on this side; a blank name would drop it")
        assertEquals("Y", r.enabled)
        List<Map<String, String>> others = connectors().findAll { it.systemEnumId != "OMS_PRODUCTS_GQL" && it.enabled == "Y" }
        assertFalse(others.isEmpty(), "the other shipped connectors were read")
        assertTrue(others.every { it.extractServiceName != r.extractServiceName && it.expectedSourceConfigType != r.expectedSourceConfigType },
                "a shared service or config type would shadow a shipped row")
    }

    @Test
    void theEndpointRowAndFieldsExist() {
        def row = new XmlSlurper().parse(file(ENUMS)).'**'.find { it.name() == "moqui.basic.Enumeration" && it.@enumId == "OMS_PRODUCTS_GQL" }
        assertTrue(row != null && row.size() == 1, "no OMS_PRODUCTS_GQL enum")
        assertEquals("OMS", row.@parentEnumId.toString())
        assertEquals("HotWax products", row.@description.toString())
        Set<String> paths = new XmlSlurper().parse(file(FIELDS)).'**'.findAll {
            it.name() == "darpan.reconciliation.SourceSystemConnectorField" && it.@systemEnumId == "OMS_PRODUCTS_GQL"
        }.collect { it.@fieldPath.toString() } as Set
        assertEquals(["nsKey", "netsuiteProductId", "productId", "internalName", "productTypeId", "createdDate", "duplicateNetsuiteId", "netsuiteIdState", "sharedWith", "sellable", "notSellableReason"]
                .collect { "\$.records[*].${it}".toString() } as Set, paths)
    }

    @Test
    void theServiceAndScriptAreWired() {
        String services = file(SERVICES).getText("UTF-8")
        assertTrue(services.contains('noun="HotWaxOmsGqlProducts"'))
        assertTrue(services.contains('extractOmsGqlProducts.groovy'))
        String script = file(SCRIPT).getText("UTF-8")
        assertTrue(script.contains('"OMS_PRODUCTS_GQL"'), "the endpoint gate must check this connector")
        assertFalse((script =~ /excludedRecordsSidecar\s*:\s*true|sourceFilters/).find(),
                "DAR-BE-318 (Task 19): the read takes no filters and writes no side file; Spark Keep does both")
        new GroovyShell().parse(script, "extractOmsGqlProducts.groovy")
    }

    @Test
    void theExtractWritesEveryProductWhateverTheWindow() {
        OmsGqlClient c = new OmsGqlClient([baseUrl: "https://gorjana-maarg.hotwax.io", authType: "BEARER", apiToken: "t"],
                { String url, Map headers, String body ->
                    String root = body.contains("goodIdentifications(") ? "goodIdentifications" : "products"
                    List<Map> nodes = root == "products"
                            ? [[productId: "10001", productTypeId: "FINISHED_GOOD", isVirtual: "N"],
                               [productId: "10056", productTypeId: "FINISHED_GOOD", isVirtual: "N"]]
                            : [[productId: "10001", idValue: "26161"]]
                    return [statusCode: 200, body: JsonOutput.toJson([data: [(root): [edges: nodes.collect { [node: it] },
                                                                                       pageInfo: [hasNextPage: false]]]])]
                })
        c.sleeper = { long ms -> }
        File work = File.createTempFile("oms-gql-products-test-", ".json")
        try {
            Map extraction = OmsGqlProductsPageSource.extractToFile(c, work)
            assertEquals(2, extraction.recordCount)
            assertEquals(true, extraction.dataAvailable)
            assertEquals(true, extraction.requestMetadata.windowIgnored)
            Map doc = (Map) new JsonSlurper().parse(work)
            assertEquals(["26161", "HOTWAX-ONLY:10056"], doc.records*.nsKey)
            assertEquals(2, doc.metadata.sellable)
        } finally {
            work.delete()
        }
    }

    // DAR-BE-186 (gorjana 2026-11-03): the brain asks whether an item's product is known to NetSuite. 10273 238-007-G-OLD has
    // no NETSUITE_PRODUCT_ID; 10278 238-007-G carries 28792. A read by id asks for those ids only, never the catalogue.
    @Test
    void aLookupReadsTheNamedProductsAndTheirNetsuiteIdsOnly() {
        List<String> queries = []
        OmsGqlClient c = new OmsGqlClient([baseUrl: "https://gorjana-maarg.hotwax.io", authType: "BEARER", apiToken: "t"],
                { String url, Map headers, String body ->
                    queries << ((Map) new JsonSlurper().parseText(body)).variables.q
                    String root = body.contains("goodIdentifications(") ? "goodIdentifications" : "products"
                    List<Map> nodes = root == "products"
                            ? [[productId: "10273", internalName: "238-007-G-OLD", productTypeId: "FINISHED_GOOD", isVirtual: "N"],
                               [productId: "10278", internalName: "238-007-G", productTypeId: "FINISHED_GOOD", isVirtual: "N"]]
                            : [[productId: "10278", idValue: "28792"]]
                    return [statusCode: 200, body: JsonOutput.toJson([data: [(root): [edges: nodes.collect { [node: it] },
                                                                                       pageInfo: [hasNextPage: false]]]])]
                })
        c.sleeper = { long ms -> }
        File work = File.createTempFile("oms-gql-products-lookup-test-", ".json")
        try {
            Map extraction = OmsGqlProductsPageSource.lookupToFile(c, work, ["10273", "10278", "10273", " "])
            assertEquals(["productId:10273,10278", "goodIdentificationTypeId:NETSUITE_PRODUCT_ID productId:10273,10278"], queries)
            assertEquals(2, extraction.recordCount)
            Map doc = (Map) new JsonSlurper().parse(work)
            assertEquals([[productId: "10273", internalName: "238-007-G-OLD", productTypeId: "FINISHED_GOOD", netsuiteProductId: null],
                          [productId: "10278", internalName: "238-007-G", productTypeId: "FINISHED_GOOD", netsuiteProductId: "28792"]], doc.records)
            assertEquals(2, doc.metadata.lookupKeys)
        } finally {
            work.delete()
        }
    }

    @Test
    void theServiceDeclaresLookupKeysSoTheBrainMayAskIt() {
        def svc = new XmlSlurper().parse(file(SERVICES)).'**'.find { it.name() == "service" && it.@noun == "HotWaxOmsGqlProducts" }
        assertTrue(svc.'**'.any { it.name() == "parameter" && it.@name == "lookupKeys" },
                "RunConclusionStep.fetchLookup refuses a connector whose extract does not declare lookupKeys")
    }

    // Live run 2026-10-28: R22's FILE_2 rule (sellable EXCLUDE_IN N) never reached the extract, so a discontinued gift card
    // read as a finding. DAR-BE-318 (Task 19): the rule is Spark Keep's now (OmsProductsKeepParityTests runs it). The read
    // writes the gift card, marked sellable N with why, so Keep can set it aside and a cause can still name it.
    @Test
    void theExtractWritesTheNotSellingProductR22SetsAside() {
        OmsGqlClient c = new OmsGqlClient([baseUrl: "https://gorjana-maarg.hotwax.io", authType: "BEARER", apiToken: "t"],
                { String url, Map headers, String body ->
                    String root = body.contains("goodIdentifications(") ? "goodIdentifications" : "products"
                    List<Map> nodes = root == "products"
                            ? [[productId: "10001", productTypeId: "FINISHED_GOOD", isVirtual: "N"],
                               [productId: "GIFT_CARD", productTypeId: "DIGITAL_GOOD", isVirtual: "N", salesDiscontinuationDate: "2025-01-01T00:00:00Z"]]
                            : [[productId: "10001", idValue: "26161"], [productId: "GIFT_CARD", idValue: "22677"]]
                    return [statusCode: 200, body: JsonOutput.toJson([data: [(root): [edges: nodes.collect { [node: it] },
                                                                                       pageInfo: [hasNextPage: false]]]])]
                })
        c.sleeper = { long ms -> }
        File work = File.createTempFile("oms-gql-products-test-", ".json")
        try {
            Map extraction = OmsGqlProductsPageSource.extractToFile(c, work)
            assertEquals([["10001", "Y", null], ["GIFT_CARD", "N", "discontinued"]],
                    ((Map) new JsonSlurper().parse(work)).records.collect { [it.productId, it.sellable, it.notSellableReason] })
            assertEquals(2, extraction.recordCount)
            assertFalse(((Map) extraction.requestMetadata).containsKey("filters"), "the read reports no rule: Keep's summary does")
        } finally {
            work.delete()
        }
    }
}
