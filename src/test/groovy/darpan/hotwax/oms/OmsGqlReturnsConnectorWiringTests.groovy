package darpan.hotwax.oms

import groovy.xml.XmlSlurper
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-040 fix 3. OMS_RETURNS_GQL is a seed-artefact fact no runtime proves before a live run: the
 * registry resolves a connector by extract service and expectedSourceConfigType, the endpoint gate treats an
 * endpoint with no DarpanSystemSource row as DISABLED, and a rule set moves between OMS_RETURNS and
 * OMS_RETURNS_GQL only if both expose the same fields, the same projection base and the same lookups.
 */
class OmsGqlReturnsConnectorWiringTests {

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
    private static final String SCRIPT = "src/main/groovy/darpan/hotwax/reconciliation/automation/extractOmsGqlReturns.groovy"

    private static Map<String, String> connector(String path, String systemEnumId) {
        def row = new XmlSlurper().parse(file(path)).'**'.find {
            it.name() == "darpan.reconciliation.SourceSystemConnector" && it.@systemEnumId == systemEnumId
        }
        assertTrue(row != null && row.size() == 1, "no ${systemEnumId} connector in ${path}")
        return row.attributes().collectEntries { k, v -> [(k.toString()): v.toString()] }
    }

    private static Map<String, Map<String, String>> fields(String path, String systemEnumId) {
        return new XmlSlurper().parse(file(path)).'**'.findAll {
            it.name() == "darpan.reconciliation.SourceSystemConnectorField" && it.@systemEnumId == systemEnumId
        }.collectEntries { [(it.@fieldPath.toString()): it.attributes().collectEntries { k, v -> [(k.toString()): v.toString()] }] }
    }

    @Test
    void theGraphqlReturnsRowHasItsOwnTypeServiceAndEndpoint() {
        Map r = connector(SEED, "OMS_RETURNS_GQL")
        assertEquals("HOTWAX_OMS_GQL_RETURNS", r.expectedSourceConfigType)
        assertEquals("reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsGqlReturns", r.extractServiceName)
        assertEquals("{baseUrl}/rest/s1/graphql", r.sendUrlTemplate)
        assertEquals("Y", r.enabled)
    }

    @Test
    void itMirrorsTheRestRowSoARuleSetMovesWithNoRuleChange() {
        Map rest = connector(SEED, "OMS_RETURNS")
        Map gql = connector(SEED, "OMS_RETURNS_GQL")
        ["configEntityName", "configParameterName", "remoteId", "dateFromParameterName", "dateToParameterName",
         "keepFieldsParameterName", "keepFieldsBase", "filterParameterName", "preserveWindowInstants",
         "healthCheckServiceName", "orderStateLookupServiceName", "returnLinesLookupServiceName", "lookupServiceName", "lookupIdsParameterName",
         "lookupMaxIds"].each { String k ->
            assertEquals(rest[k], gql[k], "${k} must match OMS_RETURNS")
        }
        assertEquals("Y", rest.enabled, "the REST row stays enabled; tenants without the GraphQL upgrade still need it")
    }

    /** Pills only the GraphQL record can carry (DAR-BE-068): REST's record has no orderId to reach the order by. */
    private static final Set<String> GQL_ONLY_FIELDS = ["\$.records[*].orderProductStoreId"] as Set

    @Test
    void itExposesTheSameFieldsAsTheRestRow() {
        Map<String, Map<String, String>> rest = fields(FIELDS, "OMS_RETURNS")
        Map<String, Map<String, String>> gql = fields(FIELDS, "OMS_RETURNS_GQL")
        assertEquals(rest.keySet(), gql.keySet() - GQL_ONLY_FIELDS, "every REST pill, plus only the GraphQL-only ones")
        rest.each { String path, Map<String, String> r ->
            ["label", "fieldType", "isPrimaryIdCandidate", "sequenceNum"].each { String k ->
                assertEquals(r[k], gql[path][k], "${path}.${k}")
            }
        }
    }

    /**
     * DAR-BE-071. The ID card offers exactly the two Shopify ids a return can carry, named as the Shopify
     * side names them: the return id (in-progress and completed) and refund-or-return (refund id once one
     * exists, else the return id). Return ID is HotWax-only and Shopify order ID repeats across an order's
     * returns, so neither is offered as the key. Explicit "N", not omitted: a data load never clears a column.
     */
    @Test
    void theIdCardOffersOnlyTheTwoShopifyReturnIds() {
        Map<String, String> expected = [
                "\$.records[*].shopifyReturnId": "Shopify return ID",
                "\$.records[*].externalId"     : "Refund or return ID",
        ]
        [FIELDS, UPGRADE].each { String path ->
            ["OMS_RETURNS", "OMS_RETURNS_GQL"].each { String system ->
                Map<String, Map<String, String>> rows = fields(path, system)
                Map<String, String> candidates = rows.findAll { it.value.isPrimaryIdCandidate == "Y" }
                        .collectEntries { [(it.key): it.value.label] }
                assertEquals(expected, candidates, "${system} ID candidates in ${path}")
                ["\$.records[*].returnId", "\$.records[*].orderExternalId"].each { String demoted ->
                    assertEquals("N", rows[demoted]?.isPrimaryIdCandidate, "${system} ${demoted} in ${path}")
                }
            }
        }
    }

    @Test
    void theOriginOrderStoreIsAFilterPillNotAProjectedField() {
        // DAR-BE-068, the DAR-BE-017 shape: filters run before projection, so the pill works without
        // joining keepFieldsBase, and keepFieldsBase must stay equal to REST's (itMirrorsTheRestRow...).
        Map<String, String> pill = fields(FIELDS, "OMS_RETURNS_GQL")["\$.records[*].orderProductStoreId"]
        assertTrue(pill != null, "no orderProductStoreId pill on OMS_RETURNS_GQL")
        assertEquals("N", pill.isPrimaryIdCandidate ?: "N")
        assertEquals("10", pill.sequenceNum)
        assertFalse(connector(SEED, "OMS_RETURNS_GQL").keepFieldsBase.split(",").contains("orderProductStoreId"))
        assertEquals(pill, fields(UPGRADE, "OMS_RETURNS_GQL")["\$.records[*].orderProductStoreId"], "upgrade pack carries it verbatim")
    }

    @Test
    void theEndpointEnumRowExistsUnderOms() {
        def row = new XmlSlurper().parse(file(ENUMS)).'**'.find { it.name() == "moqui.basic.Enumeration" && it.@enumId == "OMS_RETURNS_GQL" }
        assertTrue(row != null && row.size() == 1, "no OMS_RETURNS_GQL enum")
        assertEquals("DarpanSystemSource", row.@enumTypeId.toString())
        assertEquals("OMS", row.@parentEnumId.toString())
    }

    @Test
    void theUpgradePackCarriesTheEnumConnectorAndFields() {
        // A seed-only row never reaches a deployed instance (the entrypoint loads by type; upgrade data is the record).
        assertEquals(connector(SEED, "OMS_RETURNS_GQL"), connector(UPGRADE, "OMS_RETURNS_GQL"))
        assertEquals(fields(FIELDS, "OMS_RETURNS_GQL").keySet(), fields(UPGRADE, "OMS_RETURNS_GQL").keySet())
        def doc = new XmlSlurper().parse(file(UPGRADE))
        assertTrue(doc.'**'.find { it.name() == "moqui.basic.Enumeration" && it.@enumId == "OMS_RETURNS_GQL" }?.size() == 1)
    }

    @Test
    void theServiceAndScriptAreWiredToTheGraphqlReturnsPageSource() {
        String services = file(SERVICES).getText("UTF-8")
        assertTrue(services.contains('noun="HotWaxOmsGqlReturns"'))
        assertTrue(services.contains('extractOmsGqlReturns.groovy'))
        String script = file(SCRIPT).getText("UTF-8")
        assertTrue(script.contains("OmsGqlReturnsPageSource.fetcher("))
        assertTrue(script.contains('"OMS_RETURNS_GQL"'), "the endpoint gate must check this connector, not OMS_RETURNS")
    }

    @Test
    void theExtractScriptCompilesAgainstTheRealClasses() {
        // Moqui loads service scripts at runtime, so Gradle never compiles them.
        new GroovyShell().parse(file(SCRIPT).getText("UTF-8"), "extractOmsGqlReturns.groovy")
    }
}
