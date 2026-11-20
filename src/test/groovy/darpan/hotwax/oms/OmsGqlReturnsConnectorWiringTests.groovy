package darpan.hotwax.oms

import groovy.xml.XmlSlurper
import org.junit.jupiter.api.Test

import java.nio.file.Files
import java.nio.file.Path

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
        File f = new File(relativeToHotwax).absolute ? new File(relativeToHotwax) : new File(System.getProperty("user.dir"), relativeToHotwax)
        assertTrue(f.exists(), "missing artefact: " + f.absolutePath)
        return f
    }

    private static final String SEED = "../darpan/data/SourceSystemConnectorSeedData.xml"
    private static final String FIELDS = "../darpan/data/SourceSystemConnectorSeedDataFields.xml"
    private static final String ENUMS = "../darpan/data/DarpanSystemSourceSeedData.xml"
    private static final String UPGRADE = "../darpan/data/upgrade-data.xml"
    private static final String SERVICES = "service/reconciliation/HotWaxOmsExtractionServices.xml"
    private static final String SCRIPT = "src/main/groovy/darpan/hotwax/reconciliation/automation/extractOmsGqlReturns.groovy"

    /**
     * Release packs in version order (ignoring pre-reset), then the current upgrade-data.xml, which since 3.3.0
     * holds only that release's delta. A row's shipped state is the one in the newest pack that carries it.
     */
    private static List<File> shippedPacks() {
        File data = file(UPGRADE).parentFile
        List<File> packs = Files.list(new File(data, "releases").toPath())
                .filter { Path d -> d.fileName.toString() ==~ /\d+(\.\d+)*/ && Files.exists(d.resolve("upgrade-data.xml")) }
                .sorted({ Path a, Path b -> versionOf(a) <=> versionOf(b) } as Comparator<Path>)
                .collect { it.resolve("upgrade-data.xml").toFile() }
        packs << file(UPGRADE)
        return packs
    }

    private static String versionOf(Path releaseDir) {
        return releaseDir.fileName.toString().tokenize(".").collect { (it as int).toString().padLeft(6, "0") }.join(".")
    }

    /** The connector row from the newest pack carrying it, or null. */
    private static Map<String, String> shippedConnector(String systemEnumId) {
        Map<String, String> found = null
        shippedPacks().each { File pack ->
            def row = new XmlSlurper().parse(pack).'**'.find {
                it.name() == "darpan.reconciliation.SourceSystemConnector" && it.@systemEnumId == systemEnumId
            }
            if (row != null && row.size() == 1) {
                found = row.attributes().collectEntries { k, v -> [(k.toString()): v.toString()] }
            }
        }
        return found
    }

    /** Each field row (by fieldPath) from the newest pack carrying it. */
    private static Map<String, Map<String, String>> shippedFields(String systemEnumId) {
        Map<String, Map<String, String>> found = [:]
        shippedPacks().each { File pack ->
            found.putAll(fields(pack.path, systemEnumId))
        }
        return found
    }

    /** Field names of the current SourceSystemConnector entity definition. */
    private static Set<String> connectorEntityFields() {
        def entity = new XmlSlurper().parse(file("../darpan/entity/ReconciliationEntities.xml")).'**'.find {
            it.name() == "entity" && it.@"entity-name" == "SourceSystemConnector" && it.@package == "darpan.reconciliation"
        }
        assertTrue(entity != null, "no SourceSystemConnector entity")
        return entity.field.collect { it.@name.toString() } as Set
    }

    private static boolean shippedEnum(String enumId) {
        return shippedPacks().any { File pack ->
            new XmlSlurper().parse(pack).'**'.find { it.name() == "moqui.basic.Enumeration" && it.@enumId == enumId }?.size() == 1
        }
    }

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

    /** Pills reached through the return's origin order (DAR-BE-068). */
    private static final Set<String> GQL_ONLY_FIELDS = ["\$.records[*].orderProductStoreId", "\$.records[*].orderNetsuiteId", "\$.records[*].fromPartyId", "\$.records[*].daysBeforeEntry"] as Set

    @Test
    void itOffersTheReturnFieldsAndTheOriginOrderPills() {
        Set<String> gql = fields(FIELDS, "OMS_RETURNS_GQL").keySet()
        assertTrue(gql.containsAll(GQL_ONLY_FIELDS + ["\$.records[*].returnId", "\$.records[*].externalId",
                "\$.records[*].shopifyReturnId", "\$.records[*].orderExternalId"]), gql.toString())
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
                "\$.records[*].externalId"     : "Shopify refund ID",
        ]
        [FIELDS: fields(FIELDS, "OMS_RETURNS_GQL"), SHIPPED: shippedFields("OMS_RETURNS_GQL")].each { String path, Map<String, Map<String, String>> rows ->
            ["OMS_RETURNS_GQL"].each { String system ->
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
        // joining keepFieldsBase.
        Map<String, String> pill = fields(FIELDS, "OMS_RETURNS_GQL")["\$.records[*].orderProductStoreId"]
        assertTrue(pill != null, "no orderProductStoreId pill on OMS_RETURNS_GQL")
        assertEquals("N", pill.isPrimaryIdCandidate ?: "N")
        assertEquals("10", pill.sequenceNum)
        assertFalse(connector(SEED, "OMS_RETURNS_GQL").keepFieldsBase.split(",").contains("orderProductStoreId"))
        assertEquals(pill, shippedFields("OMS_RETURNS_GQL")["\$.records[*].orderProductStoreId"], "newest shipped pack carries it verbatim")
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
        // Since 3.3.0 upgrade-data.xml is only the release delta, so each row is read from the newest pack carrying it.
        // Attributes of columns the entity no longer has (e.g. lookupMaxIds, removed in DAR-BE-320) are harmless in an old pack, so only entity fields are compared.
        Set<String> entityFields = connectorEntityFields()
        assertEquals(connector(SEED, "OMS_RETURNS_GQL").findAll { entityFields.contains(it.key) },
                shippedConnector("OMS_RETURNS_GQL").findAll { entityFields.contains(it.key) })
        assertEquals(fields(FIELDS, "OMS_RETURNS_GQL").keySet(), shippedFields("OMS_RETURNS_GQL").keySet())
        assertTrue(shippedEnum("OMS_RETURNS_GQL"))
    }

    @Test
    void theServiceAndScriptAreWiredToTheGraphqlReturnsPageSource() {
        String services = file(SERVICES).getText("UTF-8")
        assertTrue(services.contains('noun="HotWaxOmsGqlReturns"'))
        assertTrue(services.contains('extractOmsGqlReturns.groovy'))
        String script = file(SCRIPT).getText("UTF-8")
        assertTrue(script.contains("OmsGqlReturnsPageSource.fetcher("))
        assertTrue(script.contains('"OMS_RETURNS_GQL"'), "the endpoint gate must check this connector")
    }

    @Test
    void theExtractScriptCompilesAgainstTheRealClasses() {
        // Moqui loads service scripts at runtime, so Gradle never compiles them.
        new GroovyShell().parse(file(SCRIPT).getText("UTF-8"), "extractOmsGqlReturns.groovy")
    }
}
