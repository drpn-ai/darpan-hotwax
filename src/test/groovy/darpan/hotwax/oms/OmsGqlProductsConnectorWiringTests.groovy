package darpan.hotwax.oms

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.xml.XmlSlurper
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
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
    private static final String FIELDS = "../darpan/data/SourceSystemConnectorFieldSeedData.xml"
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
        assertEquals("Y", r.enabled)
        List<Map<String, String>> others = connectors().findAll { it.systemEnumId != "OMS_PRODUCTS_GQL" && it.enabled == "Y" }
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
        assertEquals(["nsKey", "netsuiteProductId", "productId", "internalName", "productTypeId", "createdDate", "duplicateNetsuiteId"]
                .collect { "\$.records[*].${it}".toString() } as Set, paths)
    }

    @Test
    void theServiceAndScriptAreWired() {
        String services = file(SERVICES).getText("UTF-8")
        assertTrue(services.contains('noun="HotWaxOmsGqlProducts"'))
        assertTrue(services.contains('extractOmsGqlProducts.groovy'))
        String script = file(SCRIPT).getText("UTF-8")
        assertTrue(script.contains('"OMS_PRODUCTS_GQL"'), "the endpoint gate must check this connector")
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
}
