package darpan.hotwax.oms

import groovy.xml.XmlSlurper
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-318 plan 3b-1 (Task 2b's minor M1, ruling P3b1-9). Each HotWax connector row declares, in this component's own data
 * (data/HotWaxConnectorSeedData.xml, excludedRecordsSidecar), whether the records Keep sets aside are written beside its
 * extract; the conclude pass reads a filtered side with no side file as UNKNOWN. This ties the declarations to what the HotWax
 * extracts really do.
 *
 * Since plan 3b-2b (Task 17: the OMS orders and order-items reads; Task 18: the returns read; Task 19: the products read) no
 * HotWax extract takes its connector's filter parameter (sourceFilters). Each keeps in Spark, and darpan writes the side file
 * exactly when the row says Y. So a script must not ask OmsScriptServiceSupport.runExtract for one (two writers would meet),
 * and each row keeps what its read wrote before, so a run's conclusions read the side as they did. An extract that took the
 * parameter again would filter at its read and fail here until it is tied.
 */
class ExcludedRecordsSidecarTieTests {

    private static final File SCRIPTS = new File(System.getProperty("user.dir"),
            "src/main/groovy/darpan/hotwax/reconciliation/automation")
    private static final File DECLARATIONS = new File(System.getProperty("user.dir"), "data/HotWaxConnectorSeedData.xml")

    /** Each HotWax connector row's excludedRecordsSidecar, as this component declares it. */
    private static Map<String, String> declared() {
        assertTrue(DECLARATIONS.isFile(), "missing ${DECLARATIONS}")
        return new XmlSlurper().parse(DECLARATIONS).'**'.findAll { it.name() == "darpan.reconciliation.SourceSystemConnector" }
                .collectEntries { [(it.@systemEnumId.toString()): it.@excludedRecordsSidecar.toString()] }
    }

    private static final File SERVICES = new File(System.getProperty("user.dir"), "service/reconciliation/HotWaxOmsExtractionServices.xml")

    /** Each extract script's endpoint -> whether it asks runExtract for the excluded-records side file. */
    private static Map<String, Boolean> sidecarSpecs() {
        assertTrue(SCRIPTS.isDirectory(), "missing ${SCRIPTS}")
        Map<String, Boolean> specs = [:]
        scriptsByEndpoint().each { String endpoint, File f ->
            specs.put(endpoint, (f.getText("UTF-8") =~ /excludedRecordsSidecar\s*:\s*true/).find())
        }
        return specs
    }

    private static Map<String, File> scriptsByEndpoint() {
        Map<String, File> out = [:]
        SCRIPTS.listFiles().findAll { it.name.startsWith("extract") && it.name.endsWith(".groovy") }.each { File f ->
            def endpoint = f.getText("UTF-8") =~ /requiredSystemEnumId\s*:\s*"([A-Z_]+)"/
            assertTrue(endpoint.find(), "${f.name} names no endpoint for runExtract")
            out.put(endpoint.group(1), f)
        }
        return out
    }

    /**
     * Whether the endpoint's extract service takes sourceFilters (its connector's filterParameterName, darpan's
     * SourceSystemConnectorSeedData.xml), on itself or through the interface it implements, as Moqui merges them.
     */
    private static boolean takesSourceFilters(String endpoint) {
        String script = scriptsByEndpoint().get(endpoint).name
        def services = new XmlSlurper().parse(SERVICES).'**'.findAll { it.name() == "service" }
        def service = services.find { it.@location.toString().endsWith("/" + script) }
        assertTrue(service != null && service.size() > 0, "no service runs ${script}")
        Closure declares = { def svc -> svc.'in-parameters'.parameter.any { it.@name == "sourceFilters" } }
        if (declares(service)) return true
        String implemented = service.implements.@service.toString()
        def contract = services.find { "reconciliation.HotWaxOmsExtractionServices.${it.@verb}#${it.@noun}".toString() == implemented }
        return contract != null && contract.size() > 0 && declares(contract)
    }

    /**
     * What each extract wrote before it kept in Spark (Tasks 17, 18 and 19): kept, so conclusions read those sides as before.
     * OMS_RETURNS_GQL never wrote one (its pipeline counted what its filters dropped and kept none of it); the products read
     * did (its filters' collector), and so did the unit-grain read.
     */
    private static final Map<String, String> SPARK_SIDE_FILE_AS_BEFORE = [OMS_GQL: "N", OMS_ORDER_ITEMS_GQL: "Y", OMS_RETURNS_GQL: "N",
                                                                           OMS_PRODUCTS_GQL: "Y"]

    /**
     * DAR-BE-318 (Tasks 17, 18 and 19): every HotWax read keeps in Spark, and the unit-grain and products side files now come
     * from Spark.
     */
    @Test
    void everyHotWaxReadKeepsInSparkAndOnlySparkWritesItsSideFile() {
        Map<String, Boolean> specs = sidecarSpecs()
        Map<String, String> rows = declared()
        assertEquals(SPARK_SIDE_FILE_AS_BEFORE.keySet(), specs.keySet(), "an extract script was added or removed: tie it here")
        Set<String> spark = specs.keySet().findAll { !takesSourceFilters(it) } as Set<String>
        assertEquals(SPARK_SIDE_FILE_AS_BEFORE.keySet(), spark, "an extract started filtering at its read again: tie it here")
        spark.each { String endpoint ->
            assertFalse(specs.get(endpoint), "${endpoint} keeps in Spark, so its script must not ask runExtract for a side file too")
            assertEquals(SPARK_SIDE_FILE_AS_BEFORE.get(endpoint), rows.get(endpoint), "${endpoint}: the side file as its read wrote it")
        }
        // the unit-grain read used to feed the collector what its filters dropped; it drops nothing now
        Map order = [orderId: "O1", orderTypeId: "SALES_ORDER", externalId: "6678687481987", salesChannelEnumId: "POS_SALES_CHANNEL",
                     shipGroups: [[items: [[orderItemSeqId: "01", externalId: "152", quantity: 1, statusId: "ITEM_COMPLETED", productId: "1"]]]]]
        Map bundle = OmsExtractSupport.shapePageRecords([order],
                [emitGrain: "ORDER_LINE_UNIT", applyExchangeExclusion: false, requireShopifyLineId: false], ["salesChannelEnumId"], null)
        assertEquals(1, bundle.outputCount, "the unit is written; Spark sets it aside")
        assertFalse(bundle.containsKey("excludedCollector"), "the shaper keeps no side file of its own")
    }
}
