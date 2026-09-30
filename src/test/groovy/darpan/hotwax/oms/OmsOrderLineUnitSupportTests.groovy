package darpan.hotwax.oms

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-050 Task 2. Every interesting case in this feature is a document-shape case, which is
 * why the flattener is a pure function and these tests need neither Moqui nor Spark nor a socket.
 */
class OmsOrderLineUnitSupportTests {

    private static Map orderWith(List items, String externalId = "6678687481987") {
        return [orderId: "M153320", orderName: "#GOR196507337", externalId: externalId,
                shipGroups: [[items: items]]]
    }

    private static Map item(String seqId, String lineId, Object quantity = 1,
                            String statusId = "ITEM_COMPLETED", String productId = "11454") {
        return [orderItemSeqId: seqId, externalId: lineId, quantity: quantity,
                statusId: statusId, productId: productId]
    }

    @Test
    void oneSingleUnitItemBecomesOneUnitRecord() {
        Map result = OmsOrderLineUnitSupport.flattenOrderToUnits(
                orderWith([item("01", "15210699161731")]))

        assertEquals(1, ((List) result.units).size())
        Map unit = (Map) ((List) result.units)[0]
        assertEquals("6678687481987", unit.shopifyOrderId)
        assertEquals("15210699161731", unit.shopifyLineId)
        assertEquals(1, unit.unitOrdinal)
        assertEquals("M153320", unit.omsOrderId)
        assertEquals("01", unit.orderItemSeqId)
        assertEquals("ITEM_COMPLETED", unit.statusId)
    }

    @Test
    void threeItemsSharingOneShopifyLineBecomeOrdinalsOneToThree() {
        // 5.33% of real (order, line) keys carry 2+ units; the longest measured carries 40.
        Map result = OmsOrderLineUnitSupport.flattenOrderToUnits(orderWith([
                item("03", "15210699161731"), item("01", "15210699161731"),
                item("02", "15210699161731")]))

        List units = (List) result.units
        assertEquals([1, 2, 3], units.collect { ((Map) it).unitOrdinal })
        // Ordinals follow orderItemSeqId order, NOT array order: a re-run of the same window whose
        // items arrive shuffled must produce byte-identical keys.
        assertEquals(["01", "02", "03"], units.collect { ((Map) it).orderItemSeqId })
    }

    @Test
    void arrayOrderDoesNotChangeTheKeys() {
        Map forward = OmsOrderLineUnitSupport.flattenOrderToUnits(orderWith([
                item("01", "15210699161731"), item("02", "15210699161731")]))
        Map reversed = OmsOrderLineUnitSupport.flattenOrderToUnits(orderWith([
                item("02", "15210699161731"), item("01", "15210699161731")]))

        assertEquals(forward.units, reversed.units)
    }

    @Test
    void separateShopifyLinesEachRestartAtOrdinalOne() {
        Map result = OmsOrderLineUnitSupport.flattenOrderToUnits(orderWith([
                item("01", "15210699161731"), item("02", "15210699194499")]))

        List units = (List) result.units
        assertEquals([1, 1], units.collect { ((Map) it).unitOrdinal })
        assertEquals(["15210699161731", "15210699194499"].toSet(),
                units.collect { ((Map) it).shopifyLineId }.toSet())
    }

    @Test
    void itemsAcrossSeveralShipGroupsAreOneLinePopulation() {
        // A Shopify line split across ship groups must still number 1..N, not 1..n per group.
        Map order = [orderId: "M153320", orderName: "#GOR196507337", externalId: "6678687481987",
                     shipGroups: [[items: [item("01", "15210699161731")]],
                                  [items: [item("02", "15210699161731")]]]]

        Map result = OmsOrderLineUnitSupport.flattenOrderToUnits(order)

        assertEquals([1, 2], ((List) result.units).collect { ((Map) it).unitOrdinal })
    }

    @Test
    void anItemWithNoLineIdIsDroppedAndCounted() {
        // concat_ws SKIPS nulls instead of nulling the key, so a null part would silently collide
        // two different units. Dropping up front is what prevents that - but never silently.
        Map result = OmsOrderLineUnitSupport.flattenOrderToUnits(orderWith([
                item("01", "15210699161731"), item("02", null)]))

        assertEquals(1, ((List) result.units).size())
        assertEquals(1, result.droppedNullLineId)
    }

    @Test
    void anItemCarryingQuantityGreaterThanOneEmitsThatManyUnits() {
        // quantity was 1 on 258,932 of 258,932 measured rows. If OMS ever stops exploding, the
        // count must still be right, and the anomaly must be visible rather than assumed away.
        Map result = OmsOrderLineUnitSupport.flattenOrderToUnits(orderWith([
                item("01", "15210699161731", 3)]))

        assertEquals([1, 2, 3], ((List) result.units).collect { ((Map) it).unitOrdinal })
        assertEquals(1, result.nonUnitQuantityCount)
    }

    @Test
    void aCancelledUnitIsStillPresent() {
        // DAR-BE-050 D4: the predicate is EXISTENCE, not state. A cancelled OMS item is still a
        // row (ITEM_CANCELLED on 1,012 of 258,932 measured), so it must still emit a unit -
        // otherwise every cancellation reads as missing-in-OMS. This is the test that stops
        // someone "helpfully" filtering by statusId.
        Map result = OmsOrderLineUnitSupport.flattenOrderToUnits(orderWith([
                item("01", "15210699161731", 1, "ITEM_CANCELLED")]))

        assertEquals(1, ((List) result.units).size())
        assertEquals("ITEM_CANCELLED", ((Map) ((List) result.units)[0]).statusId)
    }

    @Test
    void anOrderWithNoExternalIdYieldsNoUnits() {
        Map result = OmsOrderLineUnitSupport.flattenOrderToUnits(
                orderWith([item("01", "15210699161731")], null))

        assertTrue(((List) result.units).isEmpty())
        assertEquals(1, result.droppedNullLineId)
    }

    // ---- Tri-system order runs (spec 2026-09-30, D2/D4/D5). Fixtures are REAL gorjana prod
    // documents trimmed to the fields the flattener reads; contactMechs, roles and notes stripped.

    /** #GOR197209182: two cancelled units on ONE Shopify line, parked in PICKUP_REJECTED. */
    private static Map gor197209182() {
        return [orderId: "M952314", orderName: "#GOR197209182", externalId: "7160857329795",
                statusId: "ORDER_CANCELLED", salesChannelEnumId: "WEB_SALES_CHANNEL",
                identifications: [
                        [orderIdentificationTypeId: "NETSUITE_ORDER_ID", idValue: "79270892"],
                        [orderIdentificationTypeId: "NETSUITE_ORDER_NAME", idValue: "SO7126268"],
                        [orderIdentificationTypeId: "SHOPIFY_ORD_ID", idValue: "7160857329795"]],
                shipGroups: [
                        [facilityId: "68", shipGroupSeqId: "00001"],
                        [facilityId: "PICKUP_REJECTED", shipGroupSeqId: "00002", items: [
                                [orderItemSeqId: "01", externalId: "16451984851075", productId: "12172",
                                 statusId: "ITEM_CANCELLED", quantity: 1],
                                [orderItemSeqId: "02", externalId: "16451984851075", productId: "12172",
                                 statusId: "ITEM_CANCELLED", quantity: 1]]]]]
    }

    /** #GOR197205176: two completed lines shipped from WH, one cancelled line on hold. */
    private static Map gor197205176() {
        return [orderId: "M946677", orderName: "#GOR197205176", externalId: "7158980083843",
                statusId: "ORDER_COMPLETED", salesChannelEnumId: "POS_SALES_CHANNEL",
                identifications: [
                        [orderIdentificationTypeId: "NETSUITE_ORDER_ID", idValue: "79234536"],
                        [orderIdentificationTypeId: "NETSUITE_ORDER_NAME", idValue: "SO7121914"]],
                shipGroups: [
                        [facilityId: "_NA_", shipGroupSeqId: "00001"],
                        [facilityId: "WH", shipGroupSeqId: "00003", items: [
                                [orderItemSeqId: "02", externalId: "16448517537923", productId: "12584",
                                 statusId: "ITEM_COMPLETED", quantity: 1],
                                [orderItemSeqId: "03", externalId: "16448517570691", productId: "12432",
                                 statusId: "ITEM_COMPLETED", quantity: 1]]],
                        [facilityId: "UNF_HOLD_PARKING", shipGroupSeqId: "00004", items: [
                                [orderItemSeqId: "01", externalId: "16448517505155", productId: "M101056",
                                 statusId: "ITEM_CANCELLED", quantity: 1]]]]]
    }

    /** #GOR197260520: completed, but NetSuite never handed back an internal id. */
    private static Map gor197260520() {
        return [orderId: "M1013527", orderName: "#GOR197260520", externalId: "7183741845635",
                statusId: "ORDER_COMPLETED", salesChannelEnumId: "WEB_SALES_CHANNEL",
                identifications: [
                        [orderIdentificationTypeId: "NETSUITE_ORDER_NAME", idValue: "SO7184442"],
                        [orderIdentificationTypeId: "SHOPIFY_ORD_ID", idValue: "7183741845635"]],
                shipGroups: [
                        [facilityId: "_NA_", shipGroupSeqId: "00001"],
                        [facilityId: "WH", shipGroupSeqId: "00002", items: [
                                [orderItemSeqId: "01", externalId: "16494709440643", productId: "13198",
                                 statusId: "ITEM_COMPLETED", quantity: 1]]]]]
    }

    private static Map unitFor(Map result, String orderItemSeqId) {
        return (Map) ((List) result.units).find { ((Map) it).orderItemSeqId == orderItemSeqId }
    }

    @Test
    void everyUnitCarriesItsStateNetsuiteIdOrderStatusAndFacility() {
        Map result = OmsOrderLineUnitSupport.flattenOrderToUnits(gor197205176())

        Map completed = unitFor(result, "02")
        assertEquals("COMPLETED", completed.unitState)
        assertEquals("79234536", completed.netsuiteOrderId)
        assertEquals("ORDER_COMPLETED", completed.omsOrderStatusId)
        assertEquals("WH", completed.facilityId)
        Map cancelled = unitFor(result, "01")
        assertEquals("CANCELLED", cancelled.unitState)
        assertEquals("UNF_HOLD_PARKING", cancelled.facilityId)
        // Existing fields are untouched: statusId is still the ITEM status, not the order's.
        assertEquals("ITEM_CANCELLED", cancelled.statusId)
    }

    @Test
    void stateOrdinalCountsWithinLineAndState() {
        // D2: two cancelled units on one line are CANCELLED 1 and CANCELLED 2, beside the
        // unchanged unitOrdinal 1 and 2.
        Map result = OmsOrderLineUnitSupport.flattenOrderToUnits(gor197209182())

        List units = (List) result.units
        assertEquals([1, 2], units.collect { ((Map) it).unitOrdinal })
        assertEquals([1, 2], units.collect { ((Map) it).stateOrdinal })
        assertEquals(["CANCELLED", "CANCELLED"], units.collect { ((Map) it).unitState })
        assertEquals(["79270892", "79270892"], units.collect { ((Map) it).netsuiteOrderId })
        assertEquals(["PICKUP_REJECTED", "PICKUP_REJECTED"], units.collect { ((Map) it).facilityId })
    }

    @Test
    void stateOrdinalRestartsPerStateOnAMixedLine() {
        Map result = OmsOrderLineUnitSupport.flattenOrderToUnits(orderWith([
                item("01", "L1", 1, "ITEM_COMPLETED"), item("02", "L1", 1, "ITEM_CANCELLED"),
                item("03", "L1", 1, "ITEM_COMPLETED"), item("04", "L1", 1, "ITEM_APPROVED"),
                item("05", "L1", 1, "ITEM_CREATED"), item("06", "L1", 1, "ITEM_REJECTED")]))

        List units = (List) result.units
        assertEquals([1, 2, 3, 4, 5, 6], units.collect { ((Map) it).unitOrdinal })
        assertEquals(["COMPLETED", "CANCELLED", "COMPLETED", "OPEN", "OPEN", "OTHER"],
                units.collect { ((Map) it).unitState })
        assertEquals([1, 1, 2, 1, 2, 1], units.collect { ((Map) it).stateOrdinal })
    }

    @Test
    void aMissingNetsuiteIdIsTheNoneSentinelNotNull() {
        // D5: composite keys refuse a blank part and fail the whole run.
        Map result = OmsOrderLineUnitSupport.flattenOrderToUnits(gor197260520())
        assertEquals("NONE", unitFor(result, "01").netsuiteOrderId)

        Map blank = gor197260520()
        ((List) blank.identifications).add([orderIdentificationTypeId: "NETSUITE_ORDER_ID", idValue: "  "])
        assertEquals("NONE", unitFor(OmsOrderLineUnitSupport.flattenOrderToUnits(blank), "01").netsuiteOrderId)

        Map noIdentifications = gor197260520()
        noIdentifications.remove("identifications")
        assertEquals("NONE",
                unitFor(OmsOrderLineUnitSupport.flattenOrderToUnits(noIdentifications), "01").netsuiteOrderId)
    }

    @Test
    void theExistingSignatureStillDropsUnkeyedItems() {
        Map result = OmsOrderLineUnitSupport.flattenOrderToUnits(orderWith([item("01", null)]), null)

        assertTrue(((List) result.units).isEmpty())
        assertEquals(1, result.droppedNullLineId)
        assertEquals(0, result.keptNullShopifyIdCount)
    }

    @Test
    void withoutRequiringALineIdUnkeyedItemsAreKeptAndCountedApart() {
        // D4: the NetSuite hops key on the OMS order, not the Shopify line, so an item OMS never
        // tied to a Shopify line is still a real unit there.
        Map result = OmsOrderLineUnitSupport.flattenOrderToUnits(orderWith([
                item("01", "L1"), item("03", null, 2, "ITEM_CANCELLED"), item("02", null)]),
                [requireShopifyLineId: false])

        List units = (List) result.units
        assertEquals(4, units.size())
        assertEquals(0, result.droppedNullLineId)
        assertEquals(2, result.keptNullShopifyIdCount, "counted per ITEM, like droppedNullLineId")
        // Keyed lines first (unchanged order), then unkeyed items by orderItemSeqId, each item its
        // own (omsOrderId, orderItemSeqId) group.
        assertEquals(["01", "02", "03", "03"], units.collect { ((Map) it).orderItemSeqId })
        assertEquals([1, 1, 1, 2], units.collect { ((Map) it).unitOrdinal })
        assertEquals([1, 1, 1, 2], units.collect { ((Map) it).stateOrdinal })
        assertEquals([null, null, null], units.drop(1).collect { ((Map) it).shopifyLineId })
    }

    @Test
    void withoutRequiringALineIdAnOrderWithNoExternalIdStillEmits() {
        Map result = OmsOrderLineUnitSupport.flattenOrderToUnits(
                orderWith([item("01", "L1")], null), [requireShopifyLineId: false])

        List units = (List) result.units
        assertEquals(1, units.size())
        assertEquals(null, ((Map) units[0]).shopifyOrderId)
        assertEquals(1, result.keptNullShopifyIdCount)
    }

    // DAR-UI-044: a conclusion rule can only read what the unit carries, and "POS order never reached
    // NetSuite" is decided by the order's sales channel.
    @Test
    void everyUnitCarriesItsOrdersSalesChannel() {
        Map order = gor197205176() + [salesChannelEnumId: "POS_SALES_CHANNEL"]
        Map result = OmsOrderLineUnitSupport.flattenOrderToUnits(order)
        assertEquals(["POS_SALES_CHANNEL"], ((List<Map>) result.units)*.salesChannelEnumId.unique())
    }
}
