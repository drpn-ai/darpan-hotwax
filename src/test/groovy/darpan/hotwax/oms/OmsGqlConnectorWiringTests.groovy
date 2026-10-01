package darpan.hotwax.oms

import groovy.xml.XmlSlurper
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-064 Task 7. The registry resolves a connector by extract service name and by
 * expectedSourceConfigType (first enabled match), and the endpoint gate treats an endpoint with no
 * DarpanSystemSource enum row as DISABLED. All three are seed-artefact facts no runtime proves earlier.
 */
class OmsGqlConnectorWiringTests {

    private static String read(String relativeToHotwax) {
        File f = new File(System.getProperty("user.dir"), relativeToHotwax)
        assertTrue(f.exists(), "missing artefact: " + f.absolutePath)
        return f.getText("UTF-8")
    }

    private static final String SEED = "../darpan/data/SourceSystemConnectorSeedData.xml"
    private static final String ENUMS = "../darpan/data/DarpanSystemSourceSeedData.xml"
    private static final String SERVICES = "service/reconciliation/HotWaxOmsExtractionServices.xml"

    private static String row(String xml, String marker) {
        int i = xml.indexOf(marker)
        assertTrue(i >= 0, "no row with ${marker}")
        int start = xml.lastIndexOf("<", i)
        return xml.substring(start, xml.indexOf("/>", i))
    }

    @Test
    void theGraphqlRowHasItsOwnTypeServiceAndEndpoint() {
        String r = row(read(SEED), 'systemEnumId="OMS_GQL"')
        assertTrue(r.contains('expectedSourceConfigType="HOTWAX_OMS_GQL"'))
        assertTrue(r.contains('extract#HotWaxOmsGqlOrders"'))
        assertTrue(r.contains('sendUrlTemplate="{baseUrl}/rest/s1/graphql"'))
        assertTrue(r.contains('configEntityName="darpan.hotwax.HotWaxOmsRestSourceConfig"'))
        assertTrue(r.contains('configParameterName="omsRestSourceConfigId"'))
        assertTrue(r.contains('filterParameterName="sourceFilters"'))
        assertTrue(r.contains('keepFieldsParameterName="keepRecordFields"'))
    }

    @Test
    void itKeepsTheRestProjectionBaseSoBothExtractsEmitTheSameShape() {
        String seed = read(SEED)
        String base = 'keepFieldsBase="orderId,orderName,externalId,grandTotal,orderDate,statusId"'
        assertTrue(row(seed, 'systemEnumId="OMS"').contains(base))
        assertTrue(row(seed, 'systemEnumId="OMS_GQL"').contains(base))
    }

    @Test
    void theRestRowStaysEnabledUntilTheDiffGatePasses() {
        assertTrue(row(read(SEED), 'systemEnumId="OMS"').contains('enabled="Y"'))
    }

    @Test
    void theEndpointEnumRowExistsUnderOms() {
        String r = row(read(ENUMS), 'enumId="OMS_GQL"')
        assertTrue(r.contains('enumTypeId="DarpanSystemSource"'))
        assertTrue(r.contains('parentEnumId="OMS"'))
    }

    @Test
    void theServiceAndScriptAreWiredToTheGraphqlPageSource() {
        assertTrue(read(SERVICES).contains('noun="HotWaxOmsGqlOrders"'))
        assertTrue(read(SERVICES).contains('extractOmsGqlOrders.groovy'))
        String script = read("src/main/groovy/darpan/hotwax/reconciliation/automation/extractOmsGqlOrders.groovy")
        assertTrue(script.contains("OmsGqlOrderPageSource.fetcher("))
        assertTrue(script.contains('"OMS_GQL"'))
    }

    @Test
    void theExtractScriptCompilesAgainstTheRealClasses() {
        // Moqui loads service scripts at runtime, so Gradle never compiles them: a typo or a bad import
        // would surface only in a live run. Parsing resolves every import against the test classpath.
        String script = read("src/main/groovy/darpan/hotwax/reconciliation/automation/extractOmsGqlOrders.groovy")
        new GroovyShell().parse(script, "extractOmsGqlOrders.groovy")
        assertTrue(script.contains("ExcludedRecordsSidecar.writeBeside("))
    }

    private static final String FIELDS = "../darpan/data/SourceSystemConnectorFieldSeedData.xml"

    @Test
    void omsGqlFieldPillsOfferOnlyFieldsTheDocumentCarries() {
        // Review I1: the rules board offers API-side fields only from these rows. Every OMS_GQL pill must
        // be a field the GraphQL document can supply — a pill on salesChannelEnumId would build a
        // filter the extract then refuses.
        def doc = new XmlSlurper().parseText(read(FIELDS))
        List<String> paths = doc.'**'.findAll {
            it.name() == 'darpan.reconciliation.SourceSystemConnectorField' && it.@systemEnumId == 'OMS_GQL'
        }.collect { it.@fieldPath.toString().replace('$.records[*].', '') }
        assertTrue(paths.containsAll(["orderId", "externalId", "hasPaymentPreference"]), paths.toString())
        Set<String> suppliable = OmsGqlQueries.HEADER_FIELDS + OmsGqlQueries.DERIVED_FIELDS
        assertTrue(paths.every { suppliable.contains(it) }, "unsuppliable pill(s): ${paths.findAll { !suppliable.contains(it) }}")
    }

    @Test
    void shopifyOffersTheHasPaymentTransactionPill() {
        assertTrue(read(FIELDS).contains('systemEnumId="SHOPIFY" fieldPath="$.records[*].hasPaymentTransaction"'))
    }
}
