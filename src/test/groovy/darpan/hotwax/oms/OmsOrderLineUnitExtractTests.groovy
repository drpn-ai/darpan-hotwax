package darpan.hotwax.oms

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-050 Task 3. Drives the REAL extract path with an injected HTTP client rather than a
 * test-only shim, so these assertions cover page consumption, the write gate, the record count
 * and requestMetadata - the seams where a unit-grain page differs from an order-grain one.
 */
class OmsOrderLineUnitExtractTests {
    private static final JsonSlurper JSON_SLURPER = new JsonSlurper()

    @TempDir
    File tempDir

    @AfterEach
    void resetClient() {
        OmsRestSourceSupport.resetHttpClient()
    }

    private static Map item(String seqId, String lineId, Object quantity = 1,
                            String statusId = "ITEM_COMPLETED") {
        return [orderItemSeqId: seqId, externalId: lineId, quantity: quantity,
                statusId: statusId, productId: "11454"]
    }

    private static Map order(String omsOrderId, String externalId, List items, Map extra = [:]) {
        return [orderId: omsOrderId, orderName: "#GOR" + omsOrderId, externalId: externalId,
                orderTypeId: "SALES_ORDER", shipGroups: [[items: items]]] + extra
    }

    private static void serve(List orders) {
        OmsRestSourceSupport.setHttpClient { Map ignored ->
            [statusCode: 200, body: JsonOutput.toJson([orders: orders])]
        }
    }

    private static Map<String, Object> baseConfig(Map<String, Object> overrides = [:]) {
        return [
                omsRestSourceConfigId: "KREWE_OMS",
                companyUserGroupId   : "KREWE",
                baseUrl              : "https://dev-maarg.hotwax.io",
                ordersPath           : "/rest/s1/oms/orders",
                authType             : "NONE",
                connectTimeoutSeconds: 5,
                readTimeoutSeconds   : 10,
                isActive             : "Y",
                canReadOrders        : "Y",
        ] + overrides
    }

    private Map extractUnits(List orders, Map extraOptions = [:], List sourceFilters = null) {
        serve(orders)
        File target = new File(tempDir, "units-${System.nanoTime()}.json")
        Map result = OmsRestSourceSupport.extractOrdersToFile(baseConfig(),
                "2026-05-01T00:00:00Z", "2026-05-01T01:00:00Z", target, null, null, sourceFilters,
                [emitGrain: "ORDER_LINE_UNIT"] + extraOptions)
        result.parsedOutput = target.exists() ? JSON_SLURPER.parseText(target.getText("UTF-8")) : null
        return result
    }

    @Test
    void unitGrainWritesOneRecordPerUnit() {
        Map result = extractUnits([order("M153320", "6678687481987",
                [item("01", "15210699161731"), item("02", "15210699161731")])])

        assertTrue((result.errors as List).isEmpty(), result.errors.toString())
        List records = (List) ((Map) result.parsedOutput).records
        assertEquals(2, records.size())
        assertEquals([1, 2], records.collect { ((Map) it).unitOrdinal })
        assertEquals("6678687481987", ((Map) records[0]).shopifyOrderId)
        assertEquals("15210699161731", ((Map) records[0]).shopifyLineId)
    }

    @Test
    void recordCountCountsUnitsNotOrders() {
        // The page consumer uses the SERIALIZED record count as both the write gate and the
        // extract's recordCount. At order grain those are the same number; at unit grain they are
        // not, and reporting orders while the file holds units makes every downstream count wrong
        // - including the run's own "records extracted" figure.
        Map result = extractUnits([
                order("M1", "6678687481987", [item("01", "15210699161731"),
                                              item("02", "15210699161731")]),
                order("M2", "6678687481988", [item("01", "15210699194499")])])

        assertEquals(3, result.recordCount, "three UNITS across two orders")
        assertEquals(3, ((List) ((Map) result.parsedOutput).records).size())
        assertEquals(3, ((Map) ((Map) result.parsedOutput).metadata).extractedRecordCount)
    }

    @Test
    void theExchangeExclusionStillAppliesAtUnitGrain() {
        // DAR-BE-050 D2. The exchange line sits on an ORIGINAL Shopify order that may be months
        // old, so a creation-windowed Shopify sweep never sees it while OMS lands a new EXC- order
        // inside the window. Including exchange orders makes every exchange a false
        // missing-in-Shopify, about 1 order in 9. The exclusion is KEPT here, not inverted.
        Map result = extractUnits([
                order("M153320", "6678687481987", [item("01", "15210699161731")]),
                order("M153427", "6678687481987", [item("01", "15210793599107")],
                        [orderItemAssocs: [[orderItemAssocTypeId: "EXCHANGE"]]])])

        List records = (List) ((Map) result.parsedOutput).records
        assertEquals(1, records.size(), "only the original order's unit survives")
        assertEquals("15210699161731", ((Map) records[0]).shopifyLineId)
        assertEquals(1, ((Map) ((Map) result.requestMetadata).filters).excludedExchangeOrderCount)
        assertFalse(((Map) result.parsedOutput).toString().contains("15210793599107"))
    }

    @Test
    void droppedUnitsAreCountedInRequestMetadata() {
        // A clean run whose metadata says units were dropped is not a clean run. Without this the
        // counts die on the page result and an operator never learns the extract lost records.
        Map result = extractUnits([order("M153320", "6678687481987",
                [item("01", "15210699161731"), item("02", null)])])

        assertEquals(1, ((List) ((Map) result.parsedOutput).records).size())
        assertEquals(1, ((Map) result.requestMetadata).droppedNullLineIdCount)
        assertEquals(1, ((Map) ((Map) result.parsedOutput).metadata).droppedNullLineIdCount)
    }

    @Test
    void nonUnitQuantitiesAreCountedAsAnAnomaly() {
        Map result = extractUnits([order("M153320", "6678687481987",
                [item("01", "15210699161731", 3)])])

        assertEquals(3, ((List) ((Map) result.parsedOutput).records).size())
        assertEquals(1, ((Map) result.requestMetadata).nonUnitQuantityCount)
    }

    @Test
    void anOrderWhoseUnitsAreAllDroppedWritesAValidEmptyDocument() {
        // The write gate keys on the number of SERIALIZED records. An order that survives
        // filtering but yields no units must not write an empty page with a non-zero count.
        Map result = extractUnits([order("M153320", "6678687481987", [item("01", null)])])

        assertTrue((result.errors as List).isEmpty(), result.errors.toString())
        assertEquals(0, result.recordCount)
        assertEquals([], (List) ((Map) result.parsedOutput).records)
        assertEquals(1, ((Map) result.requestMetadata).droppedNullLineIdCount)
    }

    @Test
    void orderGrainIsUnchangedWhenTheOptionIsAbsent() {
        serve([order("M153320", "6678687481987", [item("01", "15210699161731"),
                                                  item("02", "15210699161731")])])
        File target = new File(tempDir, "order-grain.json")
        Map result = OmsRestSourceSupport.extractOrdersToFile(baseConfig(),
                "2026-05-01T00:00:00Z", "2026-05-01T01:00:00Z", target)

        Map output = JSON_SLURPER.parseText(target.getText("UTF-8")) as Map
        List records = (List) output.records
        assertEquals(1, records.size(), "no emitGrain means one record per ORDER, as today")
        assertEquals("M153320", ((Map) records[0]).orderId)
        assertEquals(1, result.recordCount)
    }

    // ---- Tri-system order runs, D3: at unit grain a source filter sees the UNIT, not the order.

    private static List mixedOrders() {
        return [order("M1", "6678687481987", [item("01", "L1", 1, "ITEM_COMPLETED"),
                                              item("02", "L1", 1, "ITEM_CANCELLED"),
                                              item("03", "L2", 1, "ITEM_COMPLETED")],
                        [salesChannelEnumId: "WEB_SALES_CHANNEL", statusId: "ORDER_COMPLETED", productStoreId: "STORE"]),
                order("M2", "6678687481988", [item("01", "L3", 1, "ITEM_COMPLETED")],
                        [salesChannelEnumId: "POS_SALES_CHANNEL", statusId: "ORDER_COMPLETED", productStoreId: "STORE"])]
    }

    private static List records(Map result) {
        return (List) ((Map) result.parsedOutput).records
    }

    private static Map ruleMetadata(Map result, int sequenceNum) {
        List rules = (List) ((Map) ((Map) result.requestMetadata).filters).configuredExclusions
        return (Map) rules.find { ((Map) it).sequenceNum == sequenceNum }
    }

    @Test
    void aUnitStateIncludeRuleKeepsOnlyThoseUnits() {
        Map result = extractUnits(mixedOrders(), [:],
                [[sequenceNum: 1, fieldExpression: "unitState", operator: "INCLUDE_IN", filterValues: "COMPLETED"]])

        assertTrue((result.errors as List).isEmpty(), result.errors.toString())
        assertEquals(["COMPLETED", "COMPLETED", "COMPLETED"], records(result).collect { ((Map) it).unitState })
        assertEquals(3, result.recordCount)
        // Counts are UNITS at unit grain, and still reach requestMetadata.
        assertEquals(1, ruleMetadata(result, 1).excludedCount)
        assertEquals(0, ruleMetadata(result, 1).fieldAbsentCount)
    }

    @Test
    void theUnitStatusWinsOverTheOrderStatusOnCollision() {
        // Both the order header and the unit define statusId; the unit's item status wins.
        Map result = extractUnits(mixedOrders(), [:],
                [[sequenceNum: 1, fieldExpression: "statusId", operator: "EXCLUDE_IN", filterValues: "ITEM_CANCELLED"]])

        assertEquals(3, records(result).size())
        assertFalse(records(result).any { ((Map) it).statusId == "ITEM_CANCELLED" })
        assertEquals(1, ruleMetadata(result, 1).excludedCount)
    }

    @Test
    void anOrderLevelRuleStillDropsEveryUnitOfThatOrder() {
        Map result = extractUnits(mixedOrders(), [:],
                [[sequenceNum: 1, fieldExpression: "salesChannelEnumId", operator: "EXCLUDE_IN",
                  filterValues: "POS_SALES_CHANNEL"]])

        assertEquals(["M1", "M1", "M1"], records(result).collect { ((Map) it).omsOrderId })
        // One order excluded, but it had one unit: the count is in units now.
        assertEquals(1, ruleMetadata(result, 1).excludedCount)
        // The order header field is used for the decision only; it is not written into the unit.
        // salesChannelEnumId IS on the unit since DAR-UI-044 (conclusion rules read it); an order-only
        // header field still decides without being written.
        assertFalse(((Map) records(result)[0]).containsKey("productStoreId"))
    }

    @Test
    void anIncludeRuleOnAFieldNoUnitOrOrderHasCountsAsFieldAbsent() {
        Map result = extractUnits(mixedOrders(), [:],
                [[sequenceNum: 1, fieldExpression: "noSuchField", operator: "INCLUDE_IN", filterValues: "X"]])

        assertEquals(0, records(result).size())
        assertEquals(4, ruleMetadata(result, 1).fieldAbsentCount)
        assertEquals(0, ruleMetadata(result, 1).excludedCount)
    }

    @Test
    void noRulesLeavesUnitGrainOutputUnchanged() {
        Map withNull = extractUnits(mixedOrders())
        Map withEmpty = extractUnits(mixedOrders(), [:], [])

        assertEquals(4, records(withNull).size())
        assertEquals(records(withNull), records(withEmpty))
    }

    @Test
    void orderGrainRulesStillEvaluateAgainstTheOrderDocument() {
        serve(mixedOrders())
        File target = new File(tempDir, "order-grain-rules.json")
        Map result = OmsRestSourceSupport.extractOrdersToFile(baseConfig(),
                "2026-05-01T00:00:00Z", "2026-05-01T01:00:00Z", target, null, null,
                [[sequenceNum: 1, fieldExpression: "salesChannelEnumId", operator: "EXCLUDE_IN",
                  filterValues: "POS_SALES_CHANNEL"]])

        List orders = (List) (JSON_SLURPER.parseText(target.getText("UTF-8")) as Map).records
        assertEquals(["M1"], orders.collect { ((Map) it).orderId })
        assertEquals(1, ruleMetadata(result, 1).excludedCount)
    }

    // ---- Tri-system D4: the OMS_ORDER_ITEMS options.

    @Test
    void notRequiringALineIdKeepsUnkeyedItemsAndCountsThemApart() {
        Map result = extractUnits([order("M153320", "6678687481987",
                [item("01", "15210699161731"), item("02", null)])], [requireShopifyLineId: false])

        assertEquals(2, records(result).size())
        assertEquals(0, ((Map) result.requestMetadata).droppedNullLineIdCount)
        assertEquals(1, ((Map) result.requestMetadata).keptNullShopifyIdCount)
        assertEquals(1, ((Map) ((Map) result.parsedOutput).metadata).keptNullShopifyIdCount)
    }

    @Test
    void theLineUnitExtractMetadataGainsNoNewKey() {
        // DAR-BE-050's shipped pair must not see a new requestMetadata key.
        Map result = extractUnits([order("M153320", "6678687481987", [item("01", "15210699161731")])])

        assertFalse(((Map) result.requestMetadata).containsKey("keptNullShopifyIdCount"))
    }

    @Test
    void theExchangeExclusionCanBeTurnedOffAtUnitGrain() {
        Map result = extractUnits([
                order("M153320", "6678687481987", [item("01", "15210699161731")]),
                order("M153427", "6678687481987", [item("01", "15210793599107")],
                        [orderItemAssocs: [[orderItemAssocTypeId: "EXCHANGE"]]])],
                [applyExchangeExclusion: false, requireShopifyLineId: false])

        assertEquals(2, records(result).size())
        assertEquals(0, ((Map) ((Map) result.requestMetadata).filters).excludedExchangeOrderCount)
    }

    // DAR-UI-044: what a rule drops is kept for the conclude pass. The UNIT row is collected, not the
    // merged order+unit view the rule was tested against, so the sidecar has the kept extract's shape.
    @Test
    void unitGrainCollectsTheRejectedUnitRowNotTheMergedView() {
        Map result = extractUnits([order("M1", "6678687481987",
                [item("01", "15210699161731", 1, "ITEM_COMPLETED"), item("02", "15210699161732", 1, "ITEM_CANCELLED")],
                [salesChannelEnumId: "WEB_SALES_CHANNEL", productStoreId: "STORE"])],
                [:], [[sequenceNum: 1, fieldExpression: "unitState", operator: "INCLUDE_IN", filterValues: "COMPLETED"]])
        Map c = (Map) result.excludedCollector
        assertEquals(1, c.total)
        Map dropped = (Map) ((List) c.records)[0]
        assertEquals("CANCELLED", dropped.unitState)
        assertFalse(dropped.containsKey("productStoreId"), "order-only header fields decide, they are not written")
        assertEquals("1:unitState", dropped._excludedBy)
    }

    @Test
    void noRulesMeansAnEmptyCollector() {
        Map result = extractUnits([order("M1", "6678687481987", [item("01", "15210699161731")])])
        assertEquals(0, ((Map) result.excludedCollector).total)
    }
}
