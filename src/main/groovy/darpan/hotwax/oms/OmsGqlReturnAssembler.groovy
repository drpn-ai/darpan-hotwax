package darpan.hotwax.oms

import java.time.Instant

/**
 * GraphQL return nodes -> the record /rest/s1/oms/reconciliationReturns emits (DAR-BE-040 fix 3).
 *
 * Every rule here is MEASURED, not chosen. On gorjana prod 2026-10-03, 110 settled returns built this way
 * matched REST on every field, items included, with zero mismatches
 * (tools/live-probes/OmsReturnsGqlParityProbe). The point of the GraphQL path is the returns REST no
 * longer serves — every return without a refund, RETURN_REQUESTED above all — so for those, this is the
 * record REST would have produced.
 *
 * Pure: no I/O. OmsGqlReturnsPageSource fetches the nodes and the lookup maps.
 */
class OmsGqlReturnAssembler {

    static final String KEEP = "KEEP"
    /** Neither a refund id nor a Shopify return identification: Shopify never emits these. */
    static final String NO_SHOPIFY_REF = "NO_SHOPIFY_REF"
    static final String CANCELLED = "CANCELLED"

    /** REST prefers the older enum when a return carries both (67/67 on the live sample). */
    static final List<String> SHOPIFY_TYPE_PREFERENCE = ["SHOPIFY_RTN_ID", "SHOPIFY_RETURN_ID"]

    /**
     * Which returns REST would serve, and which it excluded server-side. RETURN_CANCELLED is excluded by
     * default: REST never served one, and the Shopify side suppresses closed unrefunded returns
     * (DAR-BE-027), so emitting them would turn every cancelled return into a missing-in-Shopify row.
     */
    static String classify(Map node) {
        if (normalize(node?.statusId) == "RETURN_CANCELLED") return CANCELLED
        if (!normalize(node?.externalId) && !shopifyReturnId(node)) return NO_SHOPIFY_REF
        return KEEP
    }

    /** The value of `node`'s identification of `type` that has not ended (and matches `pattern`), else null. */
    static String activeIdentification(Map node, String type, String pattern = /.+/) {
        long now = System.currentTimeMillis()
        Map ident = ((node?.identifications instanceof List) ? (List<Map>) node.identifications : []).find { Object raw ->
            raw instanceof Map && normalize(raw.returnIdentificationTypeId) == type && normalize(raw.idValue) ==~ pattern &&
                    !(normalize(raw.thruDate) && Instant.parse(normalize(raw.thruDate)).toEpochMilli() <= now)
        } as Map
        return ident ? normalize(ident.idValue) : null
    }

    /**
     * The Shopify return reference REST exposes: an active identification of a Shopify type, the older
     * type first. A Shopify id is numeric — SHOPIFY_RETURN_ID also holds "WARRANTY-<hex>" values on gorjana,
     * which name no Shopify object.
     */
    static String shopifyReturnId(Map node) {
        return SHOPIFY_TYPE_PREFERENCE.findResult { String type -> activeIdentification(node, type, /\d+/) }
    }

    /**
     * One REST-shaped record. `lookups` carries orderExternalIds (orderId -> Shopify order id),
     * orderProductStoreIds (orderId -> the origin order's productStoreId), orderItemExternalIds
     * ("orderId|orderItemSeqId" -> Shopify line id) and productNames (productId -> internalName, which is
     * REST's sku). A lookup miss leaves the field null — never a guess.
     */
    static Map<String, Object> build(Map node, List<Map> items, Map lookups) {
        Map orderExternalIds = (Map) (lookups?.orderExternalIds ?: [:])
        Map orderNames = (Map) (lookups?.orderNames ?: [:])
        Map orderDates = (Map) (lookups?.orderDates ?: [:])
        Map orderCustomers = (Map) (lookups?.orderCustomers ?: [:])
        Map orderProductStoreIds = (Map) (lookups?.orderProductStoreIds ?: [:])
        Map orderNetsuiteIds = (Map) (lookups?.orderNetsuiteIds ?: [:])
        Map orderGrandTotals = (Map) (lookups?.orderGrandTotals ?: [:])
        Map orderItemExternalIds = (Map) (lookups?.orderItemExternalIds ?: [:])
        Map productNames = (Map) (lookups?.productNames ?: [:])
        Map productNetsuiteIds = (Map) (lookups?.productNetsuiteIds ?: [:])
        List<Map> sorted = (items ?: []).findAll { it != null }.sort(false) { normalize(it.returnItemSeqId) ?: "" }

        BigDecimal total = BigDecimal.ZERO
        List<Map> builtItems = sorted.collect { Map i ->
            BigDecimal line = decimal(i.returnPrice) * decimal(i.returnQuantity)
            total = total + line
            Map<String, Object> out = new LinkedHashMap<>()
            out.returnItemSeqId = i.returnItemSeqId
            out.orderItemExternalId = orderItemExternalIds.get("${i.orderId}|${i.orderItemSeqId}".toString())
            out.productId = i.productId
            out.sku = productNames.get(i.productId as String)
            out.returnQuantity = number(i.returnQuantity)
            out.receivedQuantity = number(i.receivedQuantity)
            out.returnPrice = number(i.returnPrice)
            out.lineAmount = number(line)
            out.returnReasonId = i.returnReasonId
            out.returnTypeId = i.returnTypeId
            out.returnItemStatusId = i.statusId
            // DAR-BE-116 R8 (F-104602): set only when the product has one, so the REST-parity item shape is unchanged.
            String productNetsuiteId = productNetsuiteIds.get(i.productId as String)
            if (productNetsuiteId) out.productNetsuiteId = productNetsuiteId
            return out
        }

        Map<String, Object> record = new LinkedHashMap<>()
        record.returnId = node.returnId
        record.shopifyReturnId = shopifyReturnId(node)
        record.externalId = normalize(node.externalId)
        // REST omits the key for a return with no items rather than sending null.
        if (sorted) record.orderExternalId = orderExternalIds.get(sorted.first().orderId as String)
        // DAR-UI-080 / DAR-UI-081: the origin order's name (#GOR...), date and customer, for the audit finding. Set only when known.
        String orderName = sorted ? orderNames.get(sorted.first().orderId as String) : null
        if (orderName) record.orderName = orderName
        String orderDate = sorted ? orderDates.get(sorted.first().orderId as String) : null
        if (orderDate) record.orderDate = orderDate
        String customerName = sorted ? orderCustomers.get(sorted.first().orderId as String) : null
        if (customerName) record.customerName = customerName
        // DAR-BE-068: the ONE key REST lacks. Return.productStoreId is null on gorjana, so a return's store is
        // its origin order's (the order orderExternalId names). Filterable only: not in keepFieldsBase, so
        // projection strips it before the compare and the compared record stays identical to REST's.
        if (sorted) record.orderProductStoreId = orderProductStoreIds.get(sorted.first().orderId as String)
        // DAR-BE-116 R8: the origin order's NETSUITE_ORDER_ID, i.e. whether that order reached NetSuite at all; a return
        // whose order never did cannot either. Filterable only, like orderProductStoreId.
        if (sorted) record.orderNetsuiteId = orderNetsuiteIds.get(sorted.first().orderId as String)
        // DAR-BE-118: and its total, 0 when its payment was taken back (gorjana "partial payment"), which is why it never did.
        if (sorted) record.orderGrandTotal = number(orderGrandTotals.get(sorted.first().orderId as String))
        record.statusId = node.statusId
        String entry = normalize(node.entryDate)
        record.entryDate = entry ? Instant.parse(entry).toEpochMilli() : null
        // DAR-BE-116 R8: the return's own date and how many whole days before its OMS entry it was made. A 2022 return
        // OMS records today (gorjana M263611) is old, not new, and was never meant to reach NetSuite. GraphQL only.
        String made = normalize(node.returnDate)
        record.returnDate = made ? Instant.parse(made).toEpochMilli() : null
        record.daysBeforeEntry = record.entryDate != null && record.returnDate != null ?
                Math.floorDiv(((long) record.entryDate) - ((long) record.returnDate), 86_400_000L) : null
        // Aditi 2026-10-24 (R8 F-100860): an exchange's order, and an open return holding it; set only when present.
        ((Map) ((Map) (lookups?.exchangeChains ?: [:])).get(node.returnId as String) ?: [:]).each { k, v -> if (v != null) record.put(k as String, v) }
        // DAR-BE-116 R8: the credit memo HotWax wrote to NetSuite. A lost-warranty return reaches NetSuite as only that,
        // never an RMA. Set only when present, so the REST-parity record shape is unchanged.
        String creditMemoId = activeIdentification(node, "NETSUITE_CM_ID")
        if (creditMemoId) record.netsuiteCreditMemoId = creditMemoId
        // DAR-UI-080: the AfterShip warranty the return was made under, for the audit finding. Set only when present.
        String warrantyId = activeIdentification(node, "AFTSHIP_WTY_RMA_ID")
        if (warrantyId) record.warrantyId = warrantyId
        record.returnTotal = number(total)
        record.currencyUomId = node.currencyUomId
        record.returnChannelEnumId = node.returnChannelEnumId
        // DAR-BE-094 R8: who the return is from (DISCONTINUED on gorjana's discontinued-stock returns). Filterable only,
        // like orderProductStoreId: not in keepFieldsBase, so the compared record stays REST's.
        record.fromPartyId = normalize(node.fromPartyId)
        record.items = builtItems
        return record
    }

    private static BigDecimal decimal(Object v) {
        if (v == null || !v.toString().trim()) return BigDecimal.ZERO
        return new BigDecimal(v.toString().trim())
    }

    /** Value-equal to REST and printable: BigDecimal("140").stripTrailingZeros() alone prints 1.4E+2. */
    private static BigDecimal number(Object v) {
        if (v == null) return null
        return new BigDecimal(decimal(v).stripTrailingZeros().toPlainString())
    }

    private static String normalize(Object v) {
        String s = v?.toString()?.trim()
        return s ?: null
    }
}
