package darpan.hotwax.oms

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
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
}
