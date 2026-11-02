package darpan.hotwax.oms

import groovy.xml.XmlSlurper
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-116. OMS_ORDER_ITEMS_GQL, the order-items unit extract over GraphQL, is seed wiring no runtime proves before a
 * live run: the registry resolves a connector by extract service and expectedSourceConfigType, and the endpoint gate
 * treats an endpoint with no DarpanSystemSource row as DISABLED. salesChannelEnumId and the NetSuite id are not unit
 * fields the GraphQL read carries, so no pill offers them.
 */
class OmsGqlOrderItemsConnectorWiringTests {

    private static File file(String relativeToHotwax) {
        File f = new File(System.getProperty("user.dir"), relativeToHotwax)
        assertTrue(f.exists(), "missing artefact: " + f.absolutePath)
        return f
    }

    private static final String SEED = "../darpan/data/SourceSystemConnectorSeedData.xml"
    private static final String FIELDS = "../darpan/data/SourceSystemConnectorFieldSeedData.xml"
    private static final String ENUMS = "../darpan/data/DarpanSystemSourceSeedData.xml"
    private static final String UPGRADE = "../darpan/data/upgrade-data.xml"
    private static final String SERVICES = "service/reconciliation/HotWaxOmsExtractionServices.xml"
    private static final String SCRIPT = "src/main/groovy/darpan/hotwax/reconciliation/automation/extractOmsGqlOrderItems.groovy"

    private static Map<String, String> connector(String path, String systemEnumId) {
        def row = new XmlSlurper().parse(file(path)).'**'.find {
            it.name() == "darpan.reconciliation.SourceSystemConnector" && it.@systemEnumId == systemEnumId
        }
        assertTrue(row != null && row.size() == 1, "no ${systemEnumId} connector in ${path}")
        return row.attributes().collectEntries { k, v -> [(k.toString()): v.toString()] }
    }

    private static Set<String> fieldPaths(String path, String systemEnumId) {
        return new XmlSlurper().parse(file(path)).'**'.findAll {
            it.name() == "darpan.reconciliation.SourceSystemConnectorField" && it.@systemEnumId == systemEnumId
        }.collect { it.@fieldPath.toString() } as Set<String>
    }

    @Test
    void theGraphqlOrderItemsRowHasItsOwnTypeServiceAndEndpoint() {
        Map r = connector(SEED, "OMS_ORDER_ITEMS_GQL")
        assertEquals("HOTWAX_OMS_GQL_ORDER_ITEMS", r.expectedSourceConfigType)
        assertEquals("reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsGqlOrderItems", r.extractServiceName)
        assertEquals("{baseUrl}/rest/s1/graphql", r.sendUrlTemplate)
        assertEquals("Y", r.enabled)
    }

    @Test
    void itOffersEveryUnitFieldButTheSalesChannelAndTheNetsuiteId() {
        Set<String> gql = fieldPaths(FIELDS, "OMS_ORDER_ITEMS_GQL")
        assertTrue(gql.containsAll(['$.records[*].omsOrderId', '$.records[*].shopifyLineId', '$.records[*].unitOrdinal']), gql.toString())
        assertFalse(gql.contains('$.records[*].salesChannelEnumId') || gql.contains('$.records[*].netsuiteOrderId'), gql.toString())
        assertEquals(gql, fieldPaths(UPGRADE, "OMS_ORDER_ITEMS_GQL"), "upgrade data carries the same pills")
    }

    @Test
    void theEndpointHasAnEnumAndTheUpgradeCarriesTheConnector() {
        assertTrue(new XmlSlurper().parse(file(ENUMS)).'**'.any { it.name() == "moqui.basic.Enumeration" && it.@enumId == "OMS_ORDER_ITEMS_GQL" })
        assertTrue(new XmlSlurper().parse(file(UPGRADE)).'**'.any { it.name() == "moqui.basic.Enumeration" && it.@enumId == "OMS_ORDER_ITEMS_GQL" })
        assertEquals(connector(SEED, "OMS_ORDER_ITEMS_GQL"), connector(UPGRADE, "OMS_ORDER_ITEMS_GQL"))
    }

    @Test
    void theServiceImplementsTheUnitContractAndRunsTheGraphqlScript() {
        def svc = new XmlSlurper().parse(file(SERVICES)).'**'.find { it.name() == "service" && it.@noun == "HotWaxOmsGqlOrderItems" }
        assertTrue(svc != null && svc.size() == 1, "no extract#HotWaxOmsGqlOrderItems")
        assertEquals("reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsOrderUnitsContract", svc.implements.@service.toString())
        assertTrue(svc.@location.toString().endsWith("extractOmsGqlOrderItems.groovy"))
        String script = file(SCRIPT).text
        assertTrue(script.contains('requiredSystemEnumId  : "OMS_ORDER_ITEMS_GQL"'))
        assertTrue(script.contains("OmsGqlOrderPageSource.fetcher"), "the pages come from GraphQL")
        assertTrue(script.contains('emitGrain             : "ORDER_LINE_UNIT"'))
    }
}
