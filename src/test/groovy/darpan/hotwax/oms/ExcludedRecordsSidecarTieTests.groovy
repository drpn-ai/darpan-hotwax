package darpan.hotwax.oms

import darpan.reconciliation.conclusion.ExcludedRecordsSidecar
import darpan.reconciliation.source.SourceFilterSupport
import groovy.json.JsonOutput
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-318 plan 3b-1 (Task 2b's minor M1). darpan's ExcludedRecordsSidecar.WRITTEN_BY / NOT_WRITTEN_BY decide whether a
 * kept-copy slice writes the excluded-records side file (ruling P3b1-7), and the conclude pass reads a filtered side with no
 * side file as UNKNOWN. This ties those two sets to what the HotWax extractors really do, so an extractor that starts or
 * stops writing one fails here until darpan's sets follow.
 *
 * An extractor writes the side file when its script asks OmsScriptServiceSupport.runExtract for it (excludedRecordsSidecar:
 * true) AND its pipeline feeds the collector the records its filters drop. Both are read from the code: the script's spec
 * from its text, the feeding by running the pipeline on a record a filter drops.
 */
class ExcludedRecordsSidecarTieTests {

    private static final File SCRIPTS = new File(System.getProperty("user.dir"),
            "src/main/groovy/darpan/hotwax/reconciliation/automation")
    /** A rule as the shaper is handed it: parsed, as the extract parses its sourceFilters once. */
    private static final List<Map<String, Object>> DROP_POS = SourceFilterSupport.parseRules([[sequenceNum: 1,
            fieldExpression: "salesChannelEnumId", operator: "EXCLUDE_IN", filterValues: "POS_SALES_CHANNEL"]])

    /** Each extract script's endpoint -> whether it asks runExtract for the excluded-records side file. */
    private static Map<String, Boolean> sidecarSpecs() {
        assertTrue(SCRIPTS.isDirectory(), "missing ${SCRIPTS}")
        Map<String, Boolean> specs = [:]
        SCRIPTS.listFiles().findAll { it.name.startsWith("extract") && it.name.endsWith(".groovy") }.each { File f ->
            String text = f.getText("UTF-8")
            def endpoint = text =~ /requiredSystemEnumId\s*:\s*"([A-Z_]+)"/
            assertTrue(endpoint.find(), "${f.name} names no endpoint for runExtract")
            specs.put(endpoint.group(1), (text =~ /excludedRecordsSidecar\s*:\s*true/).find())
        }
        return specs
    }

    /** Whether the endpoint's pipeline hands the records its filters drop to the side file's collector. */
    private static boolean collectsDrops(String endpoint) {
        switch (endpoint) {
            case "OMS_GQL":
                // order grain: the shared shaper
                Map order = [orderId: "O1", orderTypeId: "SALES_ORDER", statusId: "ORDER_COMPLETED", salesChannelEnumId: "POS_SALES_CHANNEL"]
                Map bundle = OmsExtractSupport.shapePageRecords([order], [:], DROP_POS, null)
                assertEquals(0, bundle.outputCount, "the filter drops the order")
                return ((bundle.excludedCollector as Map).total ?: 0) as int > 0
            case "OMS_ORDER_ITEMS_GQL":
                // unit grain, as extractOmsGqlOrderItems asks for it
                Map order = [orderId: "O1", orderTypeId: "SALES_ORDER", externalId: "6678687481987", salesChannelEnumId: "POS_SALES_CHANNEL",
                             shipGroups: [[items: [[orderItemSeqId: "01", externalId: "152", quantity: 1, statusId: "ITEM_COMPLETED", productId: "1"]]]]]
                Map bundle = OmsExtractSupport.shapePageRecords([order],
                        [emitGrain: "ORDER_LINE_UNIT", applyExchangeExclusion: false, requireShopifyLineId: false], DROP_POS, null)
                assertEquals(0, bundle.outputCount, "the filter drops the unit")
                return ((bundle.excludedCollector as Map).total ?: 0) as int > 0
            case "OMS_PRODUCTS_GQL":
                OmsGqlClient c = new OmsGqlClient([baseUrl: "https://tie.hotwax.io", authType: "BEARER", apiToken: "t"],
                        { String url, Map headers, String body ->
                            String root = body.contains("goodIdentifications(") ? "goodIdentifications" : "products"
                            List<Map> nodes = root == "products" ? [[productId: "P1", productTypeId: "FINISHED_GOOD", isVirtual: "N"]]
                                    : [[productId: "P1", idValue: "26161"]]
                            return [statusCode: 200, body: JsonOutput.toJson([data: [(root): [edges: nodes.collect { [node: it] },
                                                                                               pageInfo: [hasNextPage: false]]]])]
                        })
                c.sleeper = { long ms -> }
                File work = File.createTempFile("sidecar-tie-", ".json")
                try {
                    Map extraction = OmsGqlProductsPageSource.extractToFile(c, work,
                            [[sequenceNum: 1, fieldExpression: "productId", operator: "EXCLUDE_IN", filterValues: "P1"]])
                    assertEquals(0, extraction.recordCount, "the filter drops the product")
                    boolean collected = ((extraction.excludedCollector as Map)?.total ?: 0) as int > 0
                    ExcludedRecordsSidecar.discard((Map) extraction.excludedCollector)
                    return collected
                } finally {
                    work.delete()
                }
            case "OMS_RETURNS_GQL":
                // the returns pipeline counts what its filters drop and keeps none of it
                return false
            default:
                throw new AssertionError("A HotWax extract for ${endpoint} is not tied to darpan's side-file sets yet: say here " +
                        "whether its pipeline collects what its filters drop, and list it in ExcludedRecordsSidecar")
        }
    }

    @Test
    void everyHotWaxExtractorWritesTheSideFileExactlyWhenDarpanSaysItDoes() {
        Map<String, Boolean> specs = sidecarSpecs()
        assertEquals(["OMS_GQL", "OMS_ORDER_ITEMS_GQL", "OMS_PRODUCTS_GQL", "OMS_RETURNS_GQL"] as Set, specs.keySet(),
                "an extract script was added or removed: tie it here")
        specs.each { String endpoint, Boolean asked ->
            boolean writes = asked && collectsDrops(endpoint)
            if (writes) {
                assertTrue(endpoint in ExcludedRecordsSidecar.WRITTEN_BY, "${endpoint} writes the side file; darpan must list it in WRITTEN_BY")
                assertFalse(endpoint in ExcludedRecordsSidecar.NOT_WRITTEN_BY, "${endpoint} writes the side file; not in NOT_WRITTEN_BY")
            } else {
                assertTrue(endpoint in ExcludedRecordsSidecar.NOT_WRITTEN_BY, "${endpoint} writes no side file; darpan must list it in NOT_WRITTEN_BY")
                assertFalse(endpoint in ExcludedRecordsSidecar.WRITTEN_BY, "${endpoint} writes no side file; not in WRITTEN_BY")
            }
        }
    }

    /** The order-grain read asks runExtract for the side file and never fills it: what NOT_WRITTEN_BY says of OMS_GQL. */
    @Test
    void theOrderGrainReadAsksForTheSideFileButNeverFillsIt() {
        assertTrue(sidecarSpecs().OMS_GQL)
        assertFalse(collectsDrops("OMS_GQL"))
        assertTrue(collectsDrops("OMS_ORDER_ITEMS_GQL"))
    }
}
