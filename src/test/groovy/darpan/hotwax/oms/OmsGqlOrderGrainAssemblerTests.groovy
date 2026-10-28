package darpan.hotwax.oms

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-064 Task 4. A SHAPE contract: the output is fed to the same shapePageRecords the REST path
 * uses, so the decisive tests run the REAL REST shaper over the assembled documents.
 */
class OmsGqlOrderGrainAssemblerTests {

    private static Map order(String id, List opps = []) {
        return [orderId: id, orderName: "#G" + id, externalId: "SH" + id, statusId: "ORDER_COMPLETED",
                orderTypeId: "SALES_ORDER", orderDate: "2026-09-24T17:00:00Z", grandTotal: 10, paymentPreferences: opps]
    }

    private static Map opp(String method, String status = "PAYMENT_SETTLED") {
        return [paymentMethodTypeId: method, statusId: status, maxAmount: 10]
    }

    private static Map exch(String fromOrderId, String toOrderId) {
        return [orderId: fromOrderId, orderItemSeqId: "00101", toOrderId: toOrderId, toOrderItemSeqId: "_NA_",
                orderItemAssocTypeId: "EXCHANGE"]
    }

    @Test
    void anOrderWithAnOppReadsY() {
        Map o = OmsGqlOrderGrainAssembler.assemble([order("M1", [opp("CREDIT_CARD")])], [])[0]
        assertEquals("Y", o.hasPaymentPreference)
        assertEquals("CREDIT_CARD", o.paymentMethodTypeIds)
    }

    @Test
    void anOrderWithNoOppReadsN() {
        Map o = OmsGqlOrderGrainAssembler.assemble([order("M1", [])], [])[0]
        assertEquals("N", o.hasPaymentPreference)
        assertEquals("", o.paymentMethodTypeIds)
    }

    @Test
    void onlyACompletedOrderReadsCompleted() {
        // DAR-BE-125 (R20): compared against Shopify isFulfilled, so both sides emit "Y"/"N".
        Map completed = OmsGqlOrderGrainAssembler.assemble([order("M1")], [])[0]
        Map approved = OmsGqlOrderGrainAssembler.assemble([order("M2") + [statusId: "ORDER_APPROVED"]], [])[0]
        assertEquals("Y", completed.isCompleted)
        assertEquals("N", approved.isCompleted)
    }

    @Test
    void aCancelledOppStillCountsAsPresentInV1() {
        // Spec D4: "missing" means zero rows; a cancelled OPP is a different finding, sized by P0.
        Map o = OmsGqlOrderGrainAssembler.assemble([order("M1", [opp("CREDIT_CARD", "PAYMENT_CANCELLED")])], [])[0]
        assertEquals("Y", o.hasPaymentPreference)
    }

    @Test
    void anOrderAtTheSlotCapStillReadsAsHavingAnOpp() {
        List opps = (1..OmsGqlQueries.PAYMENT_PREF_SLOTS).collect { opp("GIFT_CARD") }
        Map o = OmsGqlOrderGrainAssembler.assemble([order("M1", opps)], [])[0]
        assertEquals("Y", o.hasPaymentPreference)
        assertEquals(true, o.paymentPreferenceSlotsFull)
    }

    @Test
    void methodsAreDistinctAndSorted() {
        Map o = OmsGqlOrderGrainAssembler.assemble([order("M1", [opp("GIFT_CARD"), opp("CREDIT_CARD"), opp("GIFT_CARD")])], [])[0]
        assertEquals("CREDIT_CARD,GIFT_CARD", o.paymentMethodTypeIds)
    }

    @Test
    void theRealExchangeExclusionDropsAGraftedOrder() {
        List assembled = OmsGqlOrderGrainAssembler.assemble(
                [order("M1", [opp("CREDIT_CARD")]), order("M2", [opp("CREDIT_CARD")])], [exch("M2", "M0")])
        Map shaped = OmsRestSourceSupport.shapePageRecords(assembled, null, null, null)
        assertEquals(1, shaped.excludedExchangeOrderCount)
        assertEquals("M0", ((Map) ((List) shaped.excludedExchangeOrders)[0]).toOrderId)
        assertTrue((shaped.serializedRecords as String).contains("\"orderId\":\"M1\""))
        assertTrue(!(shaped.serializedRecords as String).contains("\"orderId\":\"M2\""))
    }

    @Test
    void aNonExchangeAssocOrOneForAnotherOrderIsNeverGrafted() {
        // If the two-term query does not AND server-side, the type is still enforced here.
        List assembled = OmsGqlOrderGrainAssembler.assemble([order("M1")],
                [exch("M1", "M0") + [orderItemAssocTypeId: "REPLACEMENT"], exch("M9", "M0")])
        Map shaped = OmsRestSourceSupport.shapePageRecords(assembled, null, null, null)
        assertEquals(0, shaped.excludedExchangeOrderCount)
    }

    @Test
    void theDerivedFieldsSurviveTheRealProjection() {
        List assembled = OmsGqlOrderGrainAssembler.assemble([order("M1", [])], [])
        Map shaped = OmsRestSourceSupport.shapePageRecords(assembled, null, null,
                ["orderId", "externalId", "hasPaymentPreference"] as Set)
        assertEquals('{"orderId":"M1","externalId":"SHM1","hasPaymentPreference":"N"}', shaped.serializedRecords)
    }

    @Test
    void shipGroupsBecomeAPlainListWhenSelected() {
        Map node = order("M1") + [shipGroups: [edges: [[node: [shipGroupSeqId: "00001", facilityId: "99"]]]]]
        Map o = OmsGqlOrderGrainAssembler.assemble([node], [])[0]
        assertTrue(o.shipGroups instanceof List)
        assertEquals("99", ((Map) ((List) o.shipGroups)[0]).facilityId)
    }

    @Test
    void orderDateAndGrandTotalTakeTheRestTypes() {
        // Review I5: REST emits orderDate as epoch millis and grandTotal as a number; GraphQL sends an
        // ISO instant and a decimal string. The extract promises REST's record shape.
        Map o = OmsGqlOrderGrainAssembler.assemble([order("M1") + [orderDate: "2026-09-24T17:00:00Z", grandTotal: "96.98"]], [])[0]
        assertEquals(java.time.Instant.parse("2026-09-24T17:00:00Z").toEpochMilli(), o.orderDate)
        assertEquals(new BigDecimal("96.98"), o.grandTotal)
    }

    @Test
    void valuesThatDoNotParseAreLeftAsTheyCame() {
        Map o = OmsGqlOrderGrainAssembler.assemble([order("M1") + [orderDate: "not-a-date", grandTotal: null]], [])[0]
        assertEquals("not-a-date", o.orderDate)
        assertEquals(null, o.grandTotal)
    }

    // DAR-BE-153. Each case is a gorjana prod order's OPPs (probe 2026-10-28): manualRefNum is the Shopify transaction id.
    private static Map ref(String method, String status, String refNum) {
        return [paymentMethodTypeId: method, statusId: status, maxAmount: 1, manualRefNum: refNum]
    }

    @Test
    void paymentRefNumsAreTheOppsShopifyTransactionIdsSortedAndDistinct() {
        // #GOR197298413's original order M1058338: the card capture, an exchange top-up taken a month later, and the
        // exchange tenders that moved both to EXC-#GOR197298413-1 (one with an EPRA- id, one with none).
        List opps = [ref("EXT_SHOP_PAYMENTS", "PAYMENT_SETTLED", "20766330257539"), ref("EXCHANGE_CREDIT", "PAYMENT_REFUNDED", null),
                     ref("EXCHANGE_PAYMENT", "PAYMENT_REFUNDED", "EPRA-20853219360899"), ref("EXT_SHOP_PAYMENTS", "PAYMENT_SETTLED", "20853219360899"),
                     ref("EXT_SHOP_PAYMENTS", "PAYMENT_SETTLED", "20766330257539")]
        Map o = OmsGqlOrderGrainAssembler.assemble([order("M1058338", opps)], [], OmsGqlQueries.REFNUM_PAYMENT_PREF_SLOTS, true)[0]
        assertEquals("20766330257539,20853219360899,EPRA-20853219360899", o.paymentRefNums)
        assertEquals(false, o.paymentPreferenceSlotsFull)
    }

    @Test
    void anOrderWithNoOppOrNoIdsHasNoPaymentRefNums() {
        // EXC-#GOR197405957-1 (M1188468): paid only by exchange credit, which names no Shopify transaction.
        assertEquals("", OmsGqlOrderGrainAssembler.assemble([order("M1188468", [ref("EXCHANGE_CREDIT", "PAYMENT_SETTLED", "")])], [],
                OmsGqlQueries.REFNUM_PAYMENT_PREF_SLOTS, true)[0].paymentRefNums)
        assertEquals("", OmsGqlOrderGrainAssembler.assemble([order("M1")], [], OmsGqlQueries.REFNUM_PAYMENT_PREF_SLOTS, true)[0].paymentRefNums)
    }

    @Test
    void aReadThatDidNotAskForTheIdsCarriesNoPaymentRefNums() {
        assertFalse(OmsGqlOrderGrainAssembler.assemble([order("M1", [opp("CREDIT_CARD")])], [])[0].containsKey("paymentRefNums"))
    }

    @Test
    void aReadThatFilledEverySlotLeavesPaymentRefNumsUnread() {
        // A seventh OPP may hold the id the rule looks for, so the list is not claimed: null, not a short list.
        List opps = (1..OmsGqlQueries.REFNUM_PAYMENT_PREF_SLOTS).collect { ref("EXT_SHOP_PAYMENTS", "PAYMENT_SETTLED", "${100 + it}") }
        Map o = OmsGqlOrderGrainAssembler.assemble([order("M1", opps)], [], OmsGqlQueries.REFNUM_PAYMENT_PREF_SLOTS, true)[0]
        assertEquals(true, o.paymentPreferenceSlotsFull)
        assertNull(o.paymentRefNums)
        assertTrue(o.containsKey("paymentRefNums"))
    }
}
