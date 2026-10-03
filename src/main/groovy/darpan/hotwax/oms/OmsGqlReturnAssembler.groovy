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

    /**
     * The Shopify return reference REST exposes: an active identification of a Shopify type, the older
     * type first. A Shopify id is numeric — SHOPIFY_RETURN_ID also holds "WARRANTY-<hex>" values on gorjana,
     * which name no Shopify object.
     */
    static String shopifyReturnId(Map node) {
        List idents = (node?.identifications instanceof List) ? (List) node.identifications : []
        long now = System.currentTimeMillis()
        for (String type : SHOPIFY_TYPE_PREFERENCE) {
            for (Object raw : idents) {
                if (!(raw instanceof Map)) continue
                Map ident = (Map) raw
                if (normalize(ident.returnIdentificationTypeId) != type) continue
                String value = normalize(ident.idValue)
                if (!value || !(value ==~ /\d+/)) continue
                String thru = normalize(ident.thruDate)
                if (thru && Instant.parse(thru).toEpochMilli() <= now) continue
                return value
            }
        }
        return null
    }

    /**
     * One REST-shaped record. `lookups` carries orderExternalIds (orderId -> Shopify order id),
     * orderItemExternalIds ("orderId|orderItemSeqId" -> Shopify line id) and productNames
     * (productId -> internalName, which is REST's sku). A lookup miss leaves the field null — never a guess.
     */
    static Map<String, Object> build(Map node, List<Map> items, Map lookups) {
        Map orderExternalIds = (Map) (lookups?.orderExternalIds ?: [:])
        Map orderItemExternalIds = (Map) (lookups?.orderItemExternalIds ?: [:])
        Map productNames = (Map) (lookups?.productNames ?: [:])
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
            return out
        }

        Map<String, Object> record = new LinkedHashMap<>()
        record.returnId = node.returnId
        record.shopifyReturnId = shopifyReturnId(node)
        record.externalId = normalize(node.externalId)
        // REST omits the key for a return with no items rather than sending null.
        if (sorted) record.orderExternalId = orderExternalIds.get(sorted.first().orderId as String)
        record.statusId = node.statusId
        String entry = normalize(node.entryDate)
        record.entryDate = entry ? Instant.parse(entry).toEpochMilli() : null
        record.returnTotal = number(total)
        record.currencyUomId = node.currencyUomId
        record.returnChannelEnumId = node.returnChannelEnumId
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
