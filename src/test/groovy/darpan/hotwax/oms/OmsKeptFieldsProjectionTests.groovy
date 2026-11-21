package darpan.hotwax.oms

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-318 plan 3b-1 (rulings 3b1-7, P3b1-9). Pure: what the HotWax extractors trim each record to, and when a copy record
 * may stand in for a side's live read. KeptFieldsProjectionTieTests pins the specs against the extractors.
 */
class OmsKeptFieldsProjectionTests {

    private static final OmsKeptFieldsProjection.Spec ORDERS = OmsKeptFieldsProjection.OMS_ORDERS
    private static final OmsKeptFieldsProjection.Spec RETURNS = OmsKeptFieldsProjection.OMS_RETURNS
    private static final List<String> BASE = ["orderId", "orderName", "statusId", "orderDate", "currencyUomId"]

    @Test
    void eachExtractorTrimsToWhatItWasAskedPlusItsOwnFields() {
        // DAR-BE-318: the order read writes isExchangeOrder after its projection, so every projection keeps it, last
        assertEquals(["orderId", "statusId", "isExchangeOrder"], OmsKeptFieldsProjection.keptBy(ORDERS, [" orderId", "statusId", "", "orderId"]) as List)
        assertEquals(["orderId", "paymentPreferences", "paymentPreferenceSlotsFull", "isExchangeOrder"] as Set,
                OmsKeptFieldsProjection.keptBy(ORDERS, ["orderId", "paymentPreferences"]), "raw payment rows bring their slots-full flag")
        assertNull(OmsKeptFieldsProjection.keptBy(ORDERS, ["  "]), "an order read sent only blanks keeps whole records")
        assertNull(OmsKeptFieldsProjection.keptBy(ORDERS, null))
        // DAR-BE-318 (Task 18): and a returns window read writes omsReturnClass, so every returns projection keeps it
        assertEquals(["returnId", "items", "omsReturnClass"], OmsKeptFieldsProjection.keptBy(RETURNS, ["returnId"]) as List)
        assertEquals(["items", "omsReturnClass"] as Set, OmsKeptFieldsProjection.keptBy(RETURNS, ["  "]), "a returns read projects on any list it is sent")
        assertNull(OmsKeptFieldsProjection.keptBy(RETURNS, []))
        Map record = [statusId: "A", orderId: "O1", other: 1]
        assertEquals([statusId: "A", orderId: "O1"], OmsKeptFieldsProjection.project(record, ["orderId", "statusId"] as Set))
        assertEquals(["statusId", "orderId"], OmsKeptFieldsProjection.project(record, ["orderId", "statusId"] as Set).keySet() as List,
                "the record's own order, as the extractor writes it")
        assertEquals([:], OmsKeptFieldsProjection.project("not a record", ["orderId"] as Set))
    }

    /**
     * DAR-BE-318 (Task 17): a Spark side's Keep reads isExchangeOrder, which joins its keep, filter and read fields. No read asks
     * for it (the extract writes it), so no copy is asked to hold it: a copy read before it existed holds no exchange order.
     */
    @Test
    void theExchangeFlagIsNeverAskedOfACopy() {
        Map side = [keepFields: BASE + ["isExchangeOrder"], filterFields: ["isExchangeOrder"], readFields: ["isExchangeOrder"],
                    windowField: "orderDate"]
        assertNull(problem([keptFields: BASE], side), "a projected copy that never kept it")
        assertNull(problem([keptFields: null, heldFields: BASE], side + [keepFields: null]), "a whole copy whose records lack it")
        Map lacking = problem([keptFields: BASE], side + [filterFields: ["isExchangeOrder", "productStoreId"]])
        assertEquals(["productStoreId"] as Set, lacking.missing, "only fields a read asks for are missing")
    }

    /**
     * DAR-BE-318 (Task 18): the same for a returns side's omsReturnClass. A copy read before it existed holds no cancelled or
     * no-reference return, so its records, lacking the class, are all kept by the Keep lines that read it.
     */
    @Test
    void theReturnClassIsNeverAskedOfACopy() {
        Map side = [keepFields: ["returnId", "omsReturnClass"], filterFields: ["omsReturnClass"], readFields: [], windowField: "entryDate"]
        assertNull(OmsKeptFieldsProjection.servingProblem(RETURNS, [keptFields: ["returnId", "entryDate"]], side), "a projected copy")
        assertNull(OmsKeptFieldsProjection.servingProblem(RETURNS, [keptFields: null, heldFields: null], side), "a whole copy")
        Map lacking = OmsKeptFieldsProjection.servingProblem(RETURNS, [keptFields: ["returnId", "entryDate"]],
                side + [filterFields: ["omsReturnClass", "orderProductStoreId"]])
        assertEquals(["orderProductStoreId"] as Set, lacking.missing, "only fields a read asks for are missing")
    }

    @Test
    void aCopyKeepingMoreFieldsServesASideThatKeepsFewer() {
        Map copy = [keptFields: BASE + ["productStoreId"]]
        assertNull(problem(copy, side(BASE, ["statusId"])))
    }

    /** Review Focus 3: a side that keeps, filters on or windows on a field the copy lacks is not served, and says which. */
    @Test
    void aSideNeedingAFieldTheCopyLacksIsNotServed() {
        Map copy = [keptFields: BASE]
        Map keeps = problem(copy, side(BASE + ["salesChannelEnumId"]))
        assertEquals(["salesChannelEnumId"] as Set, keeps.missing)
        assertTrue((keeps.reason as String).contains("salesChannelEnumId"))
        assertEquals(["productStoreId"] as Set, problem(copy, side(BASE, ["productStoreId"])).missing, "a filter field")
        assertEquals(["orderDate"] as Set, problem([keptFields: BASE - ["orderDate"]], side(BASE - ["orderDate"])).missing,
                "the window field")
        Map whole = problem(copy, side(null))
        assertNull(whole.missing)
        assertTrue((whole.reason as String).contains("whole records"))
    }

    /** A side keeping a payment field is served only from a copy whose read asked the payments the same way. */
    @Test
    void aPaymentFieldIsServedOnlyFromACopyThatReadThePaymentsTheSameWay() {
        Map wide = [keptFields: BASE + ["paymentMethodTypeIds", "paymentRefNums"]]
        Map differ = problem(wide, side(BASE + ["paymentMethodTypeIds"]))
        assertNull(differ.missing)
        assertTrue((differ.reason as String).contains("paymentRefNums"), differ.reason as String)
        assertNull(problem(wide, side(BASE + ["paymentMethodTypeIds"], [], ["paymentRefNums"])), "its readFields ask the same")
        assertNull(problem(wide, side(BASE)), "a side keeping no payment field reads them any way")
        assertNull(problem(wide, side(BASE + ["paymentMethodTypeIds", "paymentRefNums"])))
    }

    /**
     * I1: a side that only FILTERS on a payment field (keeps none) is served only from a copy whose read asked the payments
     * the same way: read with five slots, an order's paymentMethodTypeIds can differ, and the filter keeps or drops it otherwise.
     */
    @Test
    void aFilterOnAPaymentFieldIsServedOnlyFromACopyThatReadThePaymentsTheSameWay() {
        Map wide = [keptFields: BASE + ["paymentMethodTypeIds", "paymentRefNums"]]
        Map differ = problem(wide, side(BASE, ["paymentMethodTypeIds"]))
        assertNull(differ.missing)
        assertTrue((differ.reason as String).contains("paymentRefNums"), differ.reason as String)
        // the copy's read fields shape its read too: asked for paymentRefNums without keeping it
        Map readWide = [keptFields: BASE + ["paymentMethodTypeIds"], readFields: ["paymentRefNums"]]
        assertTrue((problem(readWide, side(BASE, ["paymentMethodTypeIds"])).reason as String).contains("paymentRefNums"))
        assertNull(problem(readWide, side(BASE, ["paymentMethodTypeIds"], ["paymentRefNums"])), "asked the same way")
        assertNull(problem([keptFields: BASE + ["paymentMethodTypeIds"]], side(BASE, ["paymentMethodTypeIds"])), "both two slots")
    }

    @Test
    void aCopyAskedForTheSidesFieldsInAnotherOrderDoesNotServeIt() {
        Map copy = [keptFields: ["orderId", "currencyUomId", "statusId", "orderDate"]]
        assertTrue((problem(copy, side(["orderId", "statusId", "currencyUomId", "orderDate"])).reason as String).contains("order"))
        assertNull(problem(copy, side(["orderId", "currencyUomId", "orderDate"])))
    }

    /** A whole orders copy holds only what its records carry, and its read asked none of the payment shapers. */
    @Test
    void aWholeOrdersCopyServesOnlyFieldsItsRecordsCarry() {
        Map whole = [keptFields: null, heldFields: BASE + ["paymentPreferences", "paymentMethodTypeIds", "paymentPreferenceSlotsFull"]]
        assertNull(problem(whole, side(BASE)))
        assertNull(problem(whole, side(null, ["statusId"])), "a whole side filtering on a field the records carry")
        assertEquals(["paymentRefNums"] as Set, problem(whole, side(BASE + ["paymentRefNums"])).missing)
        assertEquals(["itemQuantity"] as Set, problem(whole, side(null, [], ["itemQuantity"])).missing,
                "a whole side writes what it asks to read")
        Map shaping = problem(whole, side(BASE + ["paymentPreferences"]))
        assertNull(shaping.missing)
        assertTrue((shaping.reason as String).contains("paymentPreferences"))
        assertEquals(BASE as Set, problem([keptFields: null, heldFields: null], side(BASE)).missing,
                "a copy that recorded no fields holds none")
    }

    @Test
    void aWholeReturnsCopyServesAnySide() {
        Map whole = [keptFields: null, heldFields: null]
        assertNull(OmsKeptFieldsProjection.servingProblem(RETURNS, whole, [keepFields: ["returnId"], windowField: "entryDate"]))
        assertNull(OmsKeptFieldsProjection.servingProblem(RETURNS, whole, [keepFields: null, filterFields: ["returnChannelEnumId"],
                                                                        windowField: "entryDate"]))
        Map lacking = OmsKeptFieldsProjection.servingProblem(RETURNS, [keptFields: ["returnId", "entryDate"]],
                [keepFields: ["returnId", "returnTotal"], windowField: "entryDate"])
        assertEquals(["returnTotal"] as Set, lacking.missing)
    }

    private static Map problem(Map copy, Map side) {
        return OmsKeptFieldsProjection.servingProblem(ORDERS, copy, side)
    }

    private static Map side(List<String> keep, List<String> filters = [], List<String> readFields = []) {
        return [keepFields: keep, filterFields: filters, readFields: readFields, windowField: "orderDate"]
    }
}
