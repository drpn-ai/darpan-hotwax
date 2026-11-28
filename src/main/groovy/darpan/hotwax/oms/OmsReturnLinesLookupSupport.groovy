package darpan.hotwax.oms

import java.util.regex.Pattern

import static darpan.common.ValueSupport.normalize

/**
 * Which order lines OMS booked a return against, keyed by the originating Shopify order id
 * (DAR-BE-085, DAR-BE-086). GraphQL only.
 *
 * The returns pair joins on the Shopify refund/return id, and two populations can never pair that way:
 *   - AfterShip warranty returns. OMS keys them WARRANTY-<uuid>[-REFUND] in externalId and in both
 *     Shopify identification types, so no field carries the Shopify refund id (M1019398 / refund
 *     1113004081283, 2026-10-19).
 *   - Returns on Shopify orders older than 60 days. Without read_all_orders Shopify hides the order, so
 *     the extract rebuilds the event from return_created with no refund or return id at all.
 * What both sides still share is the order and the line: Shopify's line item id is OMS
 * OrderItem.externalId. This lookup answers that join so the verify stage can pair by it.
 *
 * Four flat, id-keyed passes, the same documents the GraphQL returns extract already pays for:
 * orders(externalId:) -> returnItems(orderId:) -> orderItems(orderId:) + returns(returnId:). Every pass
 * pages to exhaustion and is echo-checked (an answer naming no asked id is an ignored filter: OmsIdLookup); any failure
 * fails the whole lookup (ok=false), which the verify stage reads as "pair nothing".
 */
class OmsReturnLinesLookupSupport {

    static final String CANCELLED_RETURN_STATUS = "RETURN_CANCELLED"
    private static final Pattern ALL_DIGITS = Pattern.compile('^\\d+$')

    private static final Closure DEFAULT_CLIENT_FACTORY = { Map config -> new OmsGqlClient(config) }
    private static Closure clientFactory = DEFAULT_CLIENT_FACTORY

    /** Test seam: config -> OmsGqlClient. */
    static void setClientFactory(Closure factory) {
        clientFactory = factory ?: DEFAULT_CLIENT_FACTORY
    }

    static void resetClientFactory() {
        clientFactory = DEFAULT_CLIENT_FACTORY
    }

    /**
     * Returns [ok, returnLinesByExternalId, errors]. Every requested id gets an entry, an empty list
     * when OMS booked no return on it. Each line:
     *   [returnId, returnItemSeqId, lineId, returnStatusId, returnExternalId, returnChannelEnumId,
     *    entryDate, shopifyKeyed]
     * shopifyKeyed is true when the return carries a numeric Shopify id anywhere the id join reads,
     * i.e. a return the ordinary id join could already have matched. Cancelled returns are left out:
     * they account for nothing.
     */
    static Map<String, Object> lookupReturnLinesByOrderExternalId(Object rawConfig, Collection externalIds) {
        List<String> ids = OmsIdLookup.ids(externalIds)
        Map empty = [returnLinesByExternalId: [:]]
        if (!ids) return OmsIdLookup.failure(empty, "No Shopify order ids provided for the OMS return-lines lookup.")
        if (!OmsIdLookup.askable(ids)) return OmsIdLookup.failure(empty, "Shopify order ids must be plain identifiers.")
        return OmsIdLookup.run(rawConfig, clientFactory, empty, "OMS return-lines lookup") { OmsGqlClient client ->
            returnLines(client, ids)
        }
    }

    private static Map<String, Object> returnLines(OmsGqlClient client, List<String> ids) {
        Map<String, List<Map>> linesByExternalId = [:]
        ids.each { linesByExternalId.put(it, []) }
        Map pagination = [lookupCalls: 0]
        Set<String> requested = new HashSet<String>(ids)
        // An externalId can name several OMS orders (EXC- exchange orders reuse the original's).
        Map<String, String> shopifyIdByOrderId = [:]
        OmsIdLookup.requireEcho(OmsGqlReturnsPageSource.fetchByIds(client, OmsGqlQueries.orderExternalIdsDocument(), "orders", "externalId",
                ids, OmsGqlQueries.ORDERS_ID_CHUNK, OmsGqlQueries.ORDERS_ID_CHUNK, OmsGqlQueries.ORDERS_RESERVATION, pagination),
                requested, "externalId", "orders")
                .each { Map n ->
                    String externalId = normalize(n.externalId)
                    // Echo check: a filter OMS silently ignored would answer with arbitrary orders.
                    if (externalId && requested.contains(externalId) && normalize(n.orderId)) {
                        shopifyIdByOrderId.put(normalize(n.orderId), externalId)
                    }
                }
        if (shopifyIdByOrderId.isEmpty()) return [ok: true, returnLinesByExternalId: linesByExternalId, errors: []]

        List<Map> items = OmsIdLookup.requireEcho(OmsGqlReturnsPageSource.fetchByIds(client, OmsGqlQueries.returnItemsDocument(),
                "returnItems", "orderId", new ArrayList<String>(shopifyIdByOrderId.keySet()), OmsGqlQueries.RETURN_ITEMS_ID_CHUNK,
                OmsGqlQueries.RETURN_ITEMS_PAGE_SIZE, OmsGqlQueries.RETURN_ITEMS_RESERVATION, pagination),
                shopifyIdByOrderId.keySet(), "orderId", "return items")
                .findAll { Map n -> shopifyIdByOrderId.containsKey(normalize(n.orderId)) && normalize(n.returnId) }
        if (items.isEmpty()) return [ok: true, returnLinesByExternalId: linesByExternalId, errors: []]

        List<String> itemOrderIds = items.collect { normalize(it.orderId) }.unique()
        Map<String, String> lineIdByOrderItem = [:]
        OmsIdLookup.requireEcho(OmsGqlReturnsPageSource.fetchByIds(client, OmsGqlQueries.orderItemExternalIdsDocument(), "orderItems",
                "orderId", itemOrderIds, OmsGqlQueries.ORDER_ITEMS_ID_CHUNK, OmsGqlQueries.ORDER_ITEMS_PAGE_SIZE,
                OmsGqlQueries.ORDER_ITEMS_RESERVATION, pagination), itemOrderIds, "orderId", "order items")
                .each { Map n -> lineIdByOrderItem.put("${normalize(n.orderId)}|${normalize(n.orderItemSeqId)}".toString(), normalize(n.externalId)) }

        List<String> returnIds = items.collect { normalize(it.returnId) }.unique()
        Map<String, Map> returnsById = [:]
        OmsGqlReturnsPageSource.fetchByIds(client, OmsGqlQueries.returnsDocument(), "returns", "returnId", returnIds,
                OmsGqlQueries.RETURNS_PAGE_SIZE, OmsGqlQueries.RETURNS_PAGE_SIZE, OmsGqlQueries.RETURNS_RESERVATION, pagination)
                .each { Map n -> if (normalize(n.returnId)) returnsById.put(normalize(n.returnId), n) }

        items.each { Map item ->
            Map header = returnsById.get(normalize(item.returnId))
            // A return item whose header did not come back is unknowable, not absent: fail closed.
            if (header == null) throw new OmsGqlException("MALFORMED", "return ${item.returnId} named by a return item was not returned")
            if (normalize(header.statusId) == CANCELLED_RETURN_STATUS) return
            String orderId = normalize(item.orderId)
            linesByExternalId.get(shopifyIdByOrderId.get(orderId)).add([
                    returnId           : normalize(item.returnId),
                    returnItemSeqId    : normalize(item.returnItemSeqId),
                    lineId             : lineIdByOrderItem.get("${orderId}|${normalize(item.orderItemSeqId)}".toString()),
                    returnStatusId     : normalize(header.statusId),
                    returnExternalId   : normalize(header.externalId),
                    returnChannelEnumId: normalize(header.returnChannelEnumId),
                    entryDate          : normalize(header.entryDate),
                    shopifyKeyed       : isShopifyKeyed(header),
            ] as Map)
        }
        return [ok: true, returnLinesByExternalId: linesByExternalId, errors: []]
    }

    /** A numeric Shopify id in externalId or an active Shopify identification: the id join could match it. */
    static boolean isShopifyKeyed(Map header) {
        if (isDigits(header?.externalId)) return true
        List identifications = (header?.identifications instanceof List) ? (List) header.identifications : []
        return identifications.any { Object raw ->
            raw instanceof Map &&
                    OmsGqlQueries.SHOPIFY_RETURN_IDENTIFICATION_TYPES.contains(normalize(((Map) raw).returnIdentificationTypeId)) &&
                    !normalize(((Map) raw).thruDate) &&
                    isDigits(((Map) raw).idValue)
        }
    }

    private static boolean isDigits(Object raw) {
        String value = normalize(raw)
        return value != null && ALL_DIGITS.matcher(value).matches()
    }
}
