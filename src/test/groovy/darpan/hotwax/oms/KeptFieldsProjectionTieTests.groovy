package darpan.hotwax.oms

import groovy.xml.XmlSlurper
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-318 plan 3b-1 (ruling P3b1-8). darpan's KeptFieldsProjection trims HotWax records for the extractors (they
 * delegate to it) and for a kept-copy slice, which serves a side from a copy read with the union of every check's fields
 * only when OmsKeptFieldsProjection.servingProblem says the copy record stands in for the side's own live read. That rests on
 * claims about how the HotWax order read selects fields; each is pinned here against the real code, so a change to the
 * read that breaks one fails here, not in a served run.
 */
class KeptFieldsProjectionTieTests {

    private static final OmsKeptFieldsProjection.Spec ORDERS = OmsKeptFieldsProjection.OMS_ORDERS
    /** Fields that shape no other field: header scalars, nested lists and derived fields outside the spec's shapers. */
    private static final List<String> PLAIN = (OmsGqlQueries.HEADER_FIELDS + OmsGqlQueries.NESTED_FIELDS.keySet() +
            OmsGqlQueries.DERIVED_FIELDS).findAll { !ORDERS.readShapers.contains(it) }.sort()

    /** The OMS orders and returns rows name the projection service, and it implements darpan's contract. */
    @Test
    void theOrdersAndReturnsRowsNameTheProjectionService() {
        String service = "reconciliation.HotWaxOmsExtractionServices.project#HotWaxOmsKeptRecords"
        def rows = new XmlSlurper().parse(new File(System.getProperty("user.dir"), "data/HotWaxConnectorSeedData.xml")).'**'
                .findAll { it.name() == "darpan.reconciliation.SourceSystemConnector" }
        assertEquals(["OMS_GQL", "OMS_RETURNS_GQL"] as Set, rows.findAll { it.@servingProjectionServiceName.toString() }
                .collect { it.@systemEnumId.toString() } as Set)
        assertTrue(rows.findAll { it.@servingProjectionServiceName.toString() }.every { it.@servingProjectionServiceName == service })
        assertEquals(OmsKeptFieldsProjection.SPECS.keySet(), ["OMS_GQL", "OMS_RETURNS_GQL"] as Set, "a spec for each row that names it")
        def svc = new XmlSlurper().parse(new File(System.getProperty("user.dir"), "service/reconciliation/HotWaxOmsExtractionServices.xml"))
                .'**'.find { it.name() == "service" && it.@verb == "project" && it.@noun == "HotWaxOmsKeptRecords" }
        assertEquals("dataset.DatasetServices.project#KeptRecords", svc.implements.@service.toString())
    }

    @Test
    void theExtractorsProjectWithTheSpecsDarpanServesBy() {
        assertTrue(OmsKeptFieldsProjection.specFor("OMS_GQL").is(ORDERS))
        assertTrue(OmsKeptFieldsProjection.specFor("OMS_RETURNS_GQL").is(OmsKeptFieldsProjection.OMS_RETURNS))
        // the companion the order read keeps with the raw payment rows
        assertEquals([(OmsGqlQueries.RAW_PAYMENTS_FIELD): ["paymentPreferenceSlotsFull"] as Set], ORDERS.companions)
        assertEquals(["orderId", "paymentPreferences", "paymentPreferenceSlotsFull"] as Set,
                OmsGqlQueries.keepWithCompanions(["orderId", "paymentPreferences"] as Set))
        // the exchange manifest a live order read lists at most
        assertEquals(OmsExtractSupport.EXCHANGE_MANIFEST_MAX_ENTRIES, ORDERS.exchangeManifestMax)
        // DAR-BE-318 (Task 17): the field the order read writes on every record after its projection, and never asks for
        assertEquals([OmsExtractSupport.IS_EXCHANGE_ORDER_FIELD] as Set, ORDERS.appended)
        Map shaped = OmsExtractSupport.shapePageRecords([[orderId: "O1", orderTypeId: "SALES_ORDER", statusId: "X"]], null, null,
                ["statusId"] as Set)
        assertEquals('{"statusId":"X","isExchangeOrder":"N"}', shaped.serializedRecords, "kept whatever is asked, last")
        assertTrue(!OmsGqlQueries.orderFieldSelectable(OmsExtractSupport.IS_EXCHANGE_ORDER_FIELD), "never selected from the OMS")
        // the returns read keeps items[] whatever it is asked, and projects on any list it is sent
        Map ret = [returnId: "R1", items: [[returnItemSeqId: "1"]], statusId: "RETURN_COMPLETED"]
        assertEquals([returnId: "R1", items: ret.items], OmsReturnsSourceSupport.projectRecord(ret, ["returnId"]))
        assertEquals([items: ret.items], OmsReturnsSourceSupport.projectRecord(ret, [" "]))
        assertTrue(OmsReturnsSourceSupport.projectRecord(ret, null).is(ret))
        // the order read trims on names, keeping the record's order
        assertEquals(["statusId", "orderId"], OmsExtractSupport.trimRecord([statusId: "A", orderId: "O", x: 1], ["orderId", "statusId"] as Set).keySet() as List)
    }

    /** How the payments are read (slots, slot fields) depends on which readShapers are asked, and on nothing else. */
    @Test
    void thePaymentReadDependsOnlyOnTheShapersAsked() {
        List<String> shapers = ORDERS.readShapers as List<String>
        List<List<String>> others = [[], PLAIN.take(3), PLAIN.drop(3), ["currencyUomId", "notes", "itemQuantity", "salesChannelEnumId"]]
        (0..<(1 << shapers.size())).each { int mask ->
            List<String> asked = (0..<shapers.size()).findAll { int i -> ((mask >> i) & 1) == 1 }.collect { shapers[it] }
            List<Map> plans = others.collect { List<String> other -> paymentRead(OmsGqlQueries.orderGrainPagePlan((asked + other) as Set<String>)) }
            assertEquals(1, plans.unique().size(), "asking ${asked} reads the payments one way whatever else is asked: ${plans}")
        }
    }

    /** Reading the payments otherwise changes only the spec's shapedFields. */
    @Test
    void readingThePaymentsOtherwiseChangesOnlyTheShapedFields() {
        List<Map> full = [[paymentMethodTypeId: "CREDIT_CARD", statusId: "PAYMENT_SETTLED", maxAmount: 10, manualRefNum: "t1"],
                          [paymentMethodTypeId: "GIFT_CARD", statusId: "PAYMENT_SETTLED", maxAmount: 5, manualRefNum: "t2"],
                          [paymentMethodTypeId: "EXT_SHOP", statusId: "PAYMENT_SETTLED", maxAmount: 1, manualRefNum: "t3"]]
        Closure<Map> order = { List<Map> opps -> [orderId: "O1", statusId: "ORDER_COMPLETED", orderDate: "2026-11-10T10:00:00Z", grandTotal: "16",
                                                  currencyUomId: "USD", paymentPreferences: opps] }
        List<Map> variants = [
                OmsGqlOrderGrainAssembler.assemble([order(full.take(2).collect { it.findAll { k, v -> k != "manualRefNum" } })], [], 2, false)[0],
                OmsGqlOrderGrainAssembler.assemble([order(full)], [], 5, true)[0],
                OmsGqlOrderGrainAssembler.assemble([order(full.collect { [manualRefNum: it.manualRefNum] })], [], 5, true)[0],
        ]
        Set<String> changed = [] as Set
        variants.each { Map a -> variants.each { Map b ->
            (a.keySet() + b.keySet()).each { k -> if (a.get(k) != b.get(k) || a.containsKey(k) != b.containsKey(k)) changed.add(k as String) }
        } }
        assertTrue(!changed.isEmpty(), "the variants differ")
        assertTrue(ORDERS.shapedFields.containsAll(changed), "fields read otherwise outside shapedFields: ${changed - ORDERS.shapedFields}")
    }

    /**
     * A whole order read (no projection, no filter, no readFields) uses the shared plan; every field its records carry that is
     * not a shaper keeps a read on the shared plan, so a side asking only such fields reads exactly what the copy did.
     */
    @Test
    void everyNonShaperFieldOfAWholeReadKeepsTheSharedPlan() {
        Map plan = OmsGqlQueries.sharedOrdersPlan()
        assertEquals(paymentRead(plan), paymentRead(OmsGqlQueries.orderGrainPagePlan(null)), "a whole read reads the payments as the shared plan does")
        Map node = [orderId: "O1", orderName: "#1", externalId: "E1", statusId: "ORDER_COMPLETED", orderTypeId: "SALES_ORDER",
                    orderDate: "2026-11-10T10:00:00Z", grandTotal: "1", currencyUomId: "USD", billToCustomer: [firstName: "A"],
                    shipGroups: [edges: []], paymentPreferences: [[paymentMethodTypeId: "CREDIT_CARD", statusId: "S", maxAmount: 1]]]
        Set<String> held = OmsGqlOrderGrainAssembler.assemble([node], [], (int) plan.paymentSlots, false)[0].keySet() as Set<String>
        assertTrue(held.containsAll(OmsGqlQueries.SHARED_ORDER_FIELDS))
        held.findAll { !ORDERS.readShapers.contains(it) }.each { String f ->
            assertTrue(OmsGqlQueries.sharedOrdersCover([f] as Set), "${f} is carried by a whole read but takes a read off the shared plan")
        }
    }

    /**
     * A record lists the header fields beyond those always read in the order the read asked them, and the shared plan asks
     * at most one: so a copy asked for a side's fields in the side's own order lists them as the side's read would.
     */
    @Test
    void aRecordListsItsAskedHeaderFieldsInTheOrderAsked() {
        Set<String> sharedExtras = OmsGqlQueries.SHARED_ORDER_FIELDS.findAll {
            OmsGqlQueries.HEADER_FIELDS.contains(it) && !OmsGqlQueries.ALWAYS_FIELDS.contains(it) } as Set<String>
        assertTrue(sharedExtras.size() <= 1, "the shared plan asks several header fields beyond the always-read ones: ${sharedExtras}")
        String forward = selection(OmsGqlQueries.orderGrainPagePlan(["salesChannelEnumId", "currencyUomId", "productStoreId"] as LinkedHashSet<String>))
        String backward = selection(OmsGqlQueries.orderGrainPagePlan(["productStoreId", "currencyUomId", "salesChannelEnumId"] as LinkedHashSet<String>))
        String always = OmsGqlQueries.ALWAYS_FIELDS.join(" ")
        assertTrue(forward.startsWith(always + " salesChannelEnumId currencyUomId productStoreId"), forward)
        assertTrue(backward.startsWith(always + " productStoreId currencyUomId salesChannelEnumId"), backward)
    }

    private static Map paymentRead(Map plan) {
        def m = (plan.document as String) =~ /paymentPreferences\(first: (\d+)\) \{ ([^}]*) \}/
        assertTrue(m.find(), plan.document as String)
        return [slots: plan.paymentSlots, refNums: plan.paymentRefNums, selection: m.group(0)]
    }

    private static String selection(Map plan) {
        def m = (plan.document as String) =~ /edges \{ node \{\s*([^\n]*)/
        assertTrue(m.find(), plan.document as String)
        return m.group(1).trim()
    }
}
