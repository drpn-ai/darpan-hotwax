package darpan.hotwax.oms

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-040 fix 3. GraphQL nodes -> the record reconciliationReturns emits. Every rule below was measured
 * on gorjana prod 2026-10-03 (tools/live-probes/OmsReturnsGqlParityProbe): 110 settled returns built this
 * way matched REST on every field, items included, with zero mismatches.
 */
class OmsGqlReturnAssemblerTests {

    private static Map node(Map overrides = [:]) {
        return [returnId: "M247650", statusId: "RETURN_COMPLETED", entryDate: "2026-10-02T14:04:00.688Z",
                externalId: "1111455105155", currencyUomId: "USD", returnChannelEnumId: "ADMIN_RTN_CHANNEL",
                identifications: [[returnIdentificationTypeId: "SHOPIFY_RTN_ID", idValue: "1111455105155", thruDate: null]]] + overrides
    }

    private static Map item(Map overrides = [:]) {
        return [returnId: "M247650", returnItemSeqId: "01", orderId: "GR1", orderItemSeqId: "00101", productId: "10203",
                returnQuantity: 1, receivedQuantity: 0, returnPrice: 140.000, returnReasonId: "UNKNOWN",
                statusId: "RETURN_COMPLETED", returnTypeId: "RTN_REFUND"] + overrides
    }

    private static Map lookups() {
        return [orderExternalIds: [GR1: "7186636636291"], orderItemExternalIds: ["GR1|00101": "16501091238019"],
                productNames: ["10203": "219-001-G"]]
    }

    @Test
    void aSettledReturnBuildsTheRestRecordExactly() {
        // The REST record for M247650, as reconciliationReturns returned it.
        Map expected = [returnId: "M247650", shopifyReturnId: "1111455105155", externalId: "1111455105155",
                        orderExternalId: "7186636636291", statusId: "RETURN_COMPLETED", entryDate: 1790949840688L,
                        returnTotal: 140, currencyUomId: "USD", returnChannelEnumId: "ADMIN_RTN_CHANNEL",
                        items: [[returnItemSeqId: "01", orderItemExternalId: "16501091238019", productId: "10203",
                                 sku: "219-001-G", returnQuantity: 1, receivedQuantity: 0, returnPrice: 140, lineAmount: 140,
                                 returnReasonId: "UNKNOWN", returnTypeId: "RTN_REFUND", returnItemStatusId: "RETURN_COMPLETED"]]]

        Map built = OmsGqlReturnAssembler.build(node(), [item()], lookups())

        assertEquals(expected.keySet().toList(), built.keySet().toList(), "same keys, same order as REST")
        assertEquals(expected.toString(), normalized(built).toString())
    }

    @Test
    void theOldEnumWinsWhenBothShopifyTypesArePresent() {
        // 67/67 on the live sample: REST exposes SHOPIFY_RTN_ID over SHOPIFY_RETURN_ID.
        Map n = node(identifications: [[returnIdentificationTypeId: "SHOPIFY_RETURN_ID", idValue: "37501665411"],
                                       [returnIdentificationTypeId: "SHOPIFY_RTN_ID", idValue: "1111457071235"]])
        assertEquals("1111457071235", OmsGqlReturnAssembler.shopifyReturnId(n))
    }

    @Test
    void aPendingReturnCarriesItsShopifyReturnIdAndABlankExternalId() {
        Map n = node(statusId: "RETURN_REQUESTED", externalId: null,
                identifications: [[returnIdentificationTypeId: "SHOPIFY_RETURN_ID", idValue: "37447008387"],
                                  [returnIdentificationTypeId: "NETSUITE_RMA_ID", idValue: "80249409"]])
        Map built = OmsGqlReturnAssembler.build(n, [item()], lookups())
        assertEquals("37447008387", built.shopifyReturnId)
        assertTrue(built.containsKey("externalId"), "externalId stays a key so the join-key fallback can see it blank")
        assertNull(built.externalId)
        assertEquals(OmsGqlReturnAssembler.KEEP, OmsGqlReturnAssembler.classify(n))
    }

    @Test
    void aReturnWithNoItemsHasNoOrderExternalIdKeyAndAZeroTotal() {
        // REST omits the key (it does not send null) — and filters treat absent and null differently.
        Map built = OmsGqlReturnAssembler.build(node(), [], lookups())
        assertFalse(built.containsKey("orderExternalId"))
        assertEquals("0", built.returnTotal.toString())
        assertEquals([], built.items)
    }

    @Test
    void returnTotalIsPriceTimesQuantityOverTheItems() {
        Map built = OmsGqlReturnAssembler.build(node(), [item(returnQuantity: 2, returnPrice: 32.5),
                item(returnItemSeqId: "02", returnQuantity: 1, returnPrice: 10)], lookups())
        assertEquals("75", built.returnTotal.toString())
        assertEquals(["01", "02"], (built.items as List)*.returnItemSeqId)
        assertEquals("65", (built.items as List)[0].lineAmount.toString())
    }

    @Test
    void entryDateIsEpochMillisLikeRest() {
        assertEquals(1790949840688L, OmsGqlReturnAssembler.build(node(), [], lookups()).entryDate)
    }

    @Test
    void numbersNeverSerializeInScientificNotation() {
        // BigDecimal("140").stripTrailingZeros() prints 1.4E+2; the record must print 140.
        Map built = OmsGqlReturnAssembler.build(node(), [item(returnPrice: "140.0000")], lookups())
        assertEquals("140", built.returnTotal.toString())
        assertEquals("140", (built.items as List)[0].returnPrice.toString())
    }

    @Test
    void aReturnWithNoShopifyReferenceIsExcludedLikeRest() {
        // AfterShip warranty / NetSuite-only returns: Shopify never emits them, REST excluded them server-side.
        Map n = node(externalId: null, identifications: [[returnIdentificationTypeId: "AFTSHIP_WTY_RMA_ID", idValue: "W1"],
                                                         [returnIdentificationTypeId: "NETSUITE_RMA_ID", idValue: "80249409"]])
        assertEquals(OmsGqlReturnAssembler.NO_SHOPIFY_REF, OmsGqlReturnAssembler.classify(n))
    }

    @Test
    void aNonNumericShopifyTypedValueIsNotAShopifyReference() {
        // SHOPIFY_RETURN_ID also holds "WARRANTY-<hex>" on gorjana; a Shopify id is numeric.
        Map n = node(externalId: null, identifications: [[returnIdentificationTypeId: "SHOPIFY_RETURN_ID",
                                                          idValue: "WARRANTY-432e3062a53a4c599400760f57578ee9"]])
        assertNull(OmsGqlReturnAssembler.shopifyReturnId(n))
        assertEquals(OmsGqlReturnAssembler.NO_SHOPIFY_REF, OmsGqlReturnAssembler.classify(n))
    }

    @Test
    void anExpiredIdentificationIsIgnored() {
        Map n = node(identifications: [[returnIdentificationTypeId: "SHOPIFY_RTN_ID", idValue: "111", thruDate: "2020-01-01T00:00:00Z"],
                                       [returnIdentificationTypeId: "SHOPIFY_RETURN_ID", idValue: "222", thruDate: null]])
        assertEquals("222", OmsGqlReturnAssembler.shopifyReturnId(n))
    }

    @Test
    void aCancelledReturnIsExcludedByDefault() {
        // REST never served them, and the Shopify side suppresses closed unrefunded returns (DAR-BE-027).
        assertEquals(OmsGqlReturnAssembler.CANCELLED, OmsGqlReturnAssembler.classify(node(statusId: "RETURN_CANCELLED")))
    }

    @Test
    void aMissingLookupLeavesTheFieldNullRatherThanInventingIt() {
        Map built = OmsGqlReturnAssembler.build(node(), [item(productId: "999", orderItemSeqId: "00999")], lookups())
        Map i = (built.items as List)[0] as Map
        assertTrue(i.containsKey("sku")); assertNull(i.sku)
        assertTrue(i.containsKey("orderItemExternalId")); assertNull(i.orderItemExternalId)
    }

    /** Compare as REST does: numbers by value, not by BigDecimal scale or Integer-vs-Long type. */
    private static Object normalized(Object v) {
        if (v instanceof Map) return ((Map) v).collectEntries { k, x -> [(k): normalized(x)] }
        if (v instanceof List) return ((List) v).collect { normalized(it) }
        if (v instanceof Number && !(v instanceof Long && ((Long) v) > Integer.MAX_VALUE)) {
            BigDecimal d = new BigDecimal(v.toString())
            return d.stripTrailingZeros().scale() <= 0 ? d.toBigInteger() : d
        }
        return v
    }
}
