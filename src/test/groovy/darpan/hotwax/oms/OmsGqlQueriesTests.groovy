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
    void theBasePresenceShapeFitsTheBudgetAtFiftyOrders() {
        Map plan = OmsGqlQueries.orderGrainPagePlan(PRESENCE_KEEP)
        assertEquals(50, plan.pageSize)
        assertTrue((plan.reservation as int) <= 1000)
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
        assertEquals(50, plan.pageSize)
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
}
