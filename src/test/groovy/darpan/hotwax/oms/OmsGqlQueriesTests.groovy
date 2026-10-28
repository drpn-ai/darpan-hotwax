package darpan.hotwax.oms

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-064 Task 3. The document is built from what the run READS, because moqui-gql charges cost
 * on every requested list slot. A field the document cannot carry must fail loudly: a tenant
 * exclusion filter on an absent field excludes nothing and returns MORE rows.
 */
class OmsGqlQueriesTests {

    private static final Set<String> PRESENCE_KEEP =
            ["orderId", "orderName", "externalId", "grandTotal", "orderDate", "statusId", "hasPaymentPreference"] as Set

    @Test
    void theBasePresenceShapeFillsThePageUnderTheOneQueryCap() {
        // DAR-BE-116, live gorjana 2026-10-23: the BUCKET is 20000 at 2000/s, but one query is still capped
        // at 1000 (60 returns -> 1260 COST_EXCEEDED). So the page is sized to the query cap, and the speed
        // comes from parallel slices spending the bigger bucket.
        Map plan = OmsGqlQueries.orderGrainPagePlan(PRESENCE_KEEP)
        assertTrue((plan.pageSize as int) >= 50, "${plan.pageSize}")
        assertTrue(OmsGqlQueries.estimatedPageCost((int) plan.pageSize, OmsGqlQueries.PAYMENT_PREF_SLOTS, 0, []) <= 1000)
    }

    @Test
    void theDocumentAlwaysCarriesWhatTheBuiltInFiltersAndTheRuleNeed() {
        String doc = OmsGqlQueries.orderGrainPagePlan(PRESENCE_KEEP).document as String
        ["orderId", "externalId", "orderTypeId", "orderDate", "statusId",
         "paymentPreferences(first: ${OmsGqlQueries.PAYMENT_PREF_SLOTS})".toString()].each {
            assertTrue(doc.contains(it), "missing ${it}")
        }
    }

    @Test
    void nestedListsAreOnlyRequestedWhenSomethingReadsThem() {
        String doc = OmsGqlQueries.orderGrainPagePlan(PRESENCE_KEEP).document as String
        assertFalse(doc.contains("identifications"))
        assertFalse(doc.contains("shipGroups"))
    }

    @Test
    void askingForIdentificationsShrinksThePageToStayUnderBudget() {
        Map plan = OmsGqlQueries.orderGrainPagePlan(PRESENCE_KEEP + (["identifications"] as Set))
        assertTrue((plan.document as String).contains("identifications(first: 5)"))
        assertTrue((plan.pageSize as int) < 50)
        assertTrue((plan.reservation as int) <= 1000)
    }

    @Test
    void requiredFieldsRejectsAnUnknownFilterField() {
        // REST calls it currencyUom; GraphQL calls it currencyUomId. A filter written against the REST
        // name must not quietly become a no-op on GraphQL.
        IllegalArgumentException e = assertThrows(IllegalArgumentException) {
            OmsGqlQueries.orderGrainPagePlan(PRESENCE_KEEP + (["currencyUom"] as Set))
        }
        assertTrue(e.message.contains("currencyUom"))
    }

    @Test
    void requiredFieldsUnionsKeepFieldsWithFilterFields() {
        Set<String> got = OmsGqlQueries.requiredFields(["orderId"] as Set,
                [[fieldExpression: "productStoreId", operator: "EXCLUDE_IN", sequenceNum: 1]])
        assertEquals(["orderId", "productStoreId"] as Set, got)
    }

    @Test
    void noProjectionMeansTheDefaultShapeNotEveryNestedList() {
        assertNull(OmsGqlQueries.requiredFields(null, []))
        Map plan = OmsGqlQueries.orderGrainPagePlan(null)
        assertEquals(OmsGqlQueries.orderGrainPagePlan(PRESENCE_KEEP).pageSize, plan.pageSize)
        assertFalse((plan.document as String).contains("identifications"))
    }

    @Test
    void theWindowFilterIsAVariableValueAndScopesToSalesOrders() {
        String f = OmsGqlQueries.windowFilter("2026-09-01T00:00:00Z", "2026-09-02T00:00:00Z")
        assertEquals("orderDate:>=2026-09-01T00:00:00Z orderDate:<2026-09-02T00:00:00Z orderTypeId:SALES_ORDER", f)
        assertFalse((OmsGqlQueries.orderGrainPagePlan(PRESENCE_KEEP).document as String).contains("<"))
    }

    @Test
    void theExchangePassAndsTheOrderIdsWithTheAssocType() {
        assertEquals("orderId:M1,M2 orderItemAssocTypeId:EXCHANGE",
                OmsGqlQueries.exchangeAssocsVariables(["M1", "M2"], null).q)
        assertTrue(OmsGqlQueries.exchangeAssocsDocument().contains("orderItemAssocs("))
    }

    @Test
    void theCostModelReproducesTheFourShapesMeasuredLiveOnGorjana() {
        // P0, 2026-10-01: requested == actual cost, so the model must hit these exactly.
        // 50 x k3 = 1100 is the shape that came back COST_EXCEEDED.
        assertEquals(700, OmsGqlQueries.estimatedPageCost(50, 1, 0, []))
        assertEquals(1100, OmsGqlQueries.estimatedPageCost(50, 3, 0, []))
        assertEquals(600, OmsGqlQueries.estimatedPageCost(25, 3, 0, []))
        assertEquals(400, OmsGqlQueries.estimatedPageCost(25, 1, 0, []))
    }

    @Test
    void requiredFieldsKeepsFilterFieldsWhenThereIsNoProjection() {
        // Review C1: with no projection the filter fields were discarded, so a filter on a field the
        // document never selected excluded nothing.
        assertEquals(["productStoreId"] as Set, OmsGqlQueries.requiredFields(null,
                [[fieldExpression: "productStoreId", operator: "EXCLUDE_IN", sequenceNum: 1]]))
    }

    /** DAR-BE-129: an order read the shared orders read covers joins it; one needing a field it lacks reads alone. */
    @Test
    void theSharedOrdersReadCoversAReadOfItsFieldsAndTheDerivedOnes() {
        assertTrue(OmsGqlQueries.sharedOrdersCover(null))
        assertTrue(OmsGqlQueries.sharedOrdersCover(["orderId", "externalId", "hasPaymentPreference", "isCompleted"] as Set))
        assertTrue(OmsGqlQueries.sharedOrdersCover(OmsGqlQueries.SHARED_ORDER_FIELDS))
        assertFalse(OmsGqlQueries.sharedOrdersCover(["orderId", "productStoreId"] as Set))
        assertFalse(OmsGqlQueries.sharedOrdersCover(["orderId", "identifications"] as Set))
    }

    @Test
    void theReturnsOriginOrderLookupSelectsTheStoreWithinItsReservation() {
        // DAR-BE-068: one more scalar on a lookup already made, so the store costs no extra call.
        String doc = OmsGqlQueries.orderExternalIdsDocument()
        assertTrue(doc.contains("productStoreId"), doc)
        // DAR-UI-080: and the order's date, for the return's audit finding (live: 680 of 950 reserved before it).
        assertTrue(doc.contains("orderDate"), doc)
        assertTrue(doc.contains("billToCustomer { firstName lastName }"), doc)
        // DAR-BE-129: the unit read reads its orders through the shared orders document.
        assertTrue((OmsGqlQueries.sharedOrdersPlan().document as String).contains("billToCustomer { firstName lastName }"))
        assertTrue(100 + OmsGqlQueries.ORDERS_ID_CHUNK * 3 <= OmsGqlQueries.ORDERS_RESERVATION,
                "100 per page + 3 scalars x ${OmsGqlQueries.ORDERS_ID_CHUNK} rows must fit the reservation")
    }

    /** Aditi 2026-10-24 (R1 F-100794): "this is a duplicate order": HotWax marks it with an order note, read only when asked. */
    @Test
    void anOrdersNotesAreReadOnlyWhenAsked() {
        assertTrue(OmsGqlQueries.orderGrainPagePlan(["orderId", "notes"] as Set).document.contains("notes(first: 5) { note { noteInfo } }"))
        assertFalse(OmsGqlQueries.orderGrainPagePlan(["orderId"] as Set).document.contains("notes("))
        assertTrue(OmsGqlQueries.orderGrainPagePlan(["orderId", "statuses"] as Set).document.contains("statuses(first: 12) { statusId statusDatetime orderItemSeqId }"))
    }

    /** DAR-BE-153: a rule on the OPPs' Shopify transaction ids reads manualRefNum from six slots, under the query cap. */
    @Test
    void askingForPaymentRefNumsReadsManualRefNumFromSixSlotsUnderTheQueryCap() {
        Map plan = OmsGqlQueries.orderGrainPagePlan(PRESENCE_KEEP + ["paymentRefNums"] as Set)
        assertEquals(OmsGqlQueries.REFNUM_PAYMENT_PREF_SLOTS, plan.paymentSlots)
        assertEquals(true, plan.paymentRefNums)
        assertTrue((plan.document as String).contains(
                "paymentPreferences(first: ${OmsGqlQueries.REFNUM_PAYMENT_PREF_SLOTS}) { paymentMethodTypeId statusId maxAmount manualRefNum }".toString()))
        // Live gorjana 2026-10-28: 25 orders x 6 slots of 7 OPP fields was COST_EXCEEDED, 15 x 4 was not; a slot costs 1 + its fields.
        assertTrue(OmsGqlQueries.estimatedPageCost((int) plan.pageSize, OmsGqlQueries.REFNUM_PAYMENT_PREF_SLOTS, 0, [], 5.0d) <= OmsGqlQueries.QUERY_COST_BUDGET)
        assertTrue((plan.reservation as int) <= OmsGqlQueries.QUERY_COST_BUDGET + 50)

        Map lean = OmsGqlQueries.orderGrainPagePlan(PRESENCE_KEEP)
        assertEquals(OmsGqlQueries.PAYMENT_PREF_SLOTS, lean.paymentSlots)
        assertFalse((lean.document as String).contains("manualRefNum"))
    }

    @Test
    void theSharedOrdersReadDoesNotCoverPaymentRefNums() {
        // It reads two slots without manualRefNum, so a run that needs the ids reads its own.
        assertFalse(OmsGqlQueries.sharedOrdersCover(["orderId", "externalId", "paymentRefNums"] as Set))
    }
}
