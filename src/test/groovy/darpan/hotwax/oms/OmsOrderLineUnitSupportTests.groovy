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
}
