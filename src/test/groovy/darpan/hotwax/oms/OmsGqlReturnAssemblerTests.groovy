package darpan.hotwax.oms

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNotNull
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
                productNames: ["10203": "219-001-G"], orderProductStoreIds: [GR1: "STORE"], orderNetsuiteIds: [GR1: "NS9"]]
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

        // orderProductStoreId (DAR-BE-068) is the one GraphQL-only key: filterable, never in keepFieldsBase, so
        // projection strips it and the compared record stays identical to REST's.
        assertEquals(expected.keySet().toList(), built.keySet().toList() - ["orderProductStoreId", "orderNetsuiteId", "orderGrandTotal", "fromPartyId", "returnDate", "daysBeforeEntry"], "same keys, same order as REST")
        assertEquals(expected.toString(), normalized(built.findAll { !(it.key in ["orderProductStoreId", "orderNetsuiteId", "orderGrandTotal", "fromPartyId", "returnDate", "daysBeforeEntry"]) }).toString())
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
    void aReturnCarriesItsOriginOrdersProductStore() {
        // DAR-BE-068. Return.productStoreId was null on all 400 gorjana returns sampled 2026-10-16, so the
        // store a return belongs to is its ORIGIN ORDER's — the same order orderExternalId names.
        Map built = OmsGqlReturnAssembler.build(node(), [item()], lookups())
        assertEquals("STORE", built.orderProductStoreId)
    }

    /**
     * DAR-BE-116 R8, Aditi 2026-10-23: "this is an old return that is not expected to be synced to NS.. from 2022". OMS entered
     * gorjana's M263611 on 2026-10-21 for a return dated 2022-06-06; how long before its entry a return was made is what
     * tells an old return recorded late from a new one.
     */
    /**
     * DAR-BE-116 R8 (F-104607, gorjana M262072 on 2026-10-23): a lost-warranty return reaches NetSuite as a credit memo
     * only, never an RMA, so the record carries the NETSUITE_CM_ID HotWax wrote; absent when it has none, keeping REST's shape.
     */
    @Test
    void aReturnCarriesTheCreditMemoItReachedNetsuiteAs() {
        List idents = [[returnIdentificationTypeId: "AFTSHIP_WTY_RMA_ID", idValue: "4JF7BF7F", thruDate: null],
                       [returnIdentificationTypeId: "NETSUITE_CM_ID", idValue: "80963979", thruDate: null]]
        assertEquals("80963979", OmsGqlReturnAssembler.build(node(identifications: idents), [item()], lookups()).netsuiteCreditMemoId)
        List ended = [[returnIdentificationTypeId: "NETSUITE_CM_ID", idValue: "80963979", thruDate: "2026-10-19T17:05:10.315Z"]]
        assertFalse(OmsGqlReturnAssembler.build(node(identifications: ended), [item()], lookups()).containsKey("netsuiteCreditMemoId"))
        assertFalse(OmsGqlReturnAssembler.build(node(), [item()], lookups()).containsKey("netsuiteCreditMemoId"))
    }

    /** DAR-UI-080 (gorjana M263376 -> 6URJ7WK3): a warranty return carries its AfterShip warranty id; others have no key. */
    @Test
    void aWarrantyReturnCarriesItsWarrantyId() {
        List idents = [[returnIdentificationTypeId: "AFTSHIP_WTY_RMA_ID", idValue: "6URJ7WK3", thruDate: null],
                       [returnIdentificationTypeId: "SHOPIFY_RETURN_ID", idValue: "38318243971", thruDate: null]]
        assertEquals("6URJ7WK3", OmsGqlReturnAssembler.build(node(identifications: idents), [item()], lookups()).warrantyId)
        assertFalse(OmsGqlReturnAssembler.build(node(), [item()], lookups()).containsKey("warrantyId"))
    }

    /** DAR-UI-080: a return names its origin order by name and date; no key when the lookup did not answer. */
    @Test
    void aReturnCarriesItsOriginOrdersName() {
        Map built = OmsGqlReturnAssembler.build(node(), [item()], lookups() + [orderNames: [GR1: "#GOR195470263"], orderDates: [GR1: "2026-09-02T17:11:40Z"], orderCustomers: [GR1: "Ada Lovelace"]])
        assertEquals(["#GOR195470263", "2026-09-02T17:11:40Z", "Ada Lovelace"], [built.orderName, built.orderDate, built.customerName])
        assertFalse(OmsGqlReturnAssembler.build(node(), [item()], lookups()).containsKey("orderName"))
        assertFalse(OmsGqlReturnAssembler.build(node(), [item()], lookups()).containsKey("orderDate"))
    }

    @Test
    void aReturnCarriesItsOwnDateAndHowLongBeforeItsEntryItWasMade() {
        Map old = OmsGqlReturnAssembler.build(node(entryDate: "2026-10-21T21:31:59.301Z", returnDate: "2022-06-06T20:37:51Z"), [item()], lookups())
        assertEquals(java.time.Instant.parse("2022-06-06T20:37:51Z").toEpochMilli(), old.returnDate)
        assertEquals(1598L, old.daysBeforeEntry)
        Map fresh = OmsGqlReturnAssembler.build(node(entryDate: "2026-10-21T16:48:40.064Z", returnDate: "2026-10-21T16:32:59Z"), [item()], lookups())
        assertEquals(0L, fresh.daysBeforeEntry)
        assertNull(OmsGqlReturnAssembler.build(node(), [item()], lookups()).daysBeforeEntry, "no return date: unknown, not zero")
    }

    /** DAR-BE-116 R8, Aditi 2026-10-23: "the source order is not in NS... that is why this also is not in NS". */
    @Test
    void aReturnCarriesWhetherItsOriginOrderReachedNetSuite() {
        assertEquals("NS9", OmsGqlReturnAssembler.build(node(), [item()], lookups()).orderNetsuiteId)
        assertNull(OmsGqlReturnAssembler.build(node(), [item()], lookups() + [orderNetsuiteIds: [:]]).orderNetsuiteId,
                "an order HotWax holds no NetSuite id for")
    }

    /** DAR-BE-094 R8, Aditi 2026-10-23: "exclude returns with fromPartyId: DISCONTINUED". Filterable like the store. */
    @Test
    void aReturnCarriesWhoItIsFromSoAnExclusionCanDropIt() {
        assertTrue(OmsGqlQueries.returnsDocument().contains("fromPartyId"), "the returns query asks for it")
        Map discontinued = OmsGqlReturnAssembler.build(node(fromPartyId: "DISCONTINUED"), [item()], lookups())
        Map customer = OmsGqlReturnAssembler.build(node(fromPartyId: "10456"), [item()], lookups())
        assertEquals("DISCONTINUED", discontinued.fromPartyId)
        List rules = darpan.reconciliation.source.SourceFilterSupport.parseRules([[sequenceNum: 1, fieldExpression: "fromPartyId",
                operator: "EXCLUDE_IN", filterValues: "DISCONTINUED"]])
        assertNotNull(darpan.reconciliation.source.SourceFilterSupport.evaluate(discontinued, rules), "DISCONTINUED is excluded")
        assertNull(darpan.reconciliation.source.SourceFilterSupport.evaluate(customer, rules), "a customer's return is kept")
        assertNull(darpan.reconciliation.source.SourceFilterSupport.evaluate(OmsGqlReturnAssembler.build(node(), [item()], lookups()), rules),
                "no party: kept")
    }

    @Test
    void aReturnWithNoItemsHasNoOriginOrderProductStoreKey() {
        // No item, no origin order: absent, so an INCLUDE_IN store rule drops it as FIELD_ABSENT and an
        // EXCLUDE_IN rule keeps it — the filter's own semantics, not a guessed store.
        Map built = OmsGqlReturnAssembler.build(node(), [], lookups())
        assertFalse(built.containsKey("orderProductStoreId"))
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
