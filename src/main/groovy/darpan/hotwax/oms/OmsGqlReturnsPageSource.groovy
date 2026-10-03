package darpan.hotwax.oms

import java.time.Instant

/**
 * The GraphQL page source for the OMS RETURNS extract (DAR-BE-040 fix 3, connector OMS_RETURNS_GQL).
 *
 * Why it exists: /rest/s1/oms/reconciliationReturns serves REFUNDED returns only. Live on gorjana
 * 2026-10-03 it returned none of the 268 RETURN_REQUESTED returns since Oct 1, so every pending return
 * Shopify reported read as missing in HotWax. GraphQL `returns` has no such exclusion.
 *
 * Per returns page: classify each node (OmsGqlReturnAssembler.classify — the exclusions REST applied
 * server-side), then flat, id-keyed lookups for the kept ones only — returnItems, the orders and order
 * items that carry the Shopify ids, and products for sku — then build REST-shaped records and hand them
 * to the extractor's pageConsumer. Filtering, the join-key fallback, projection and writing stay in
 * OmsReturnsSourceSupport, shared with REST.
 *
 * Every lookup follows its own cursor: a truncated items page would understate returnTotal, and a
 * truncated order page would emit null Shopify ids that read as real differences. Any GraphQL error
 * fails the whole extract — THROTTLED in particular arrives as HTTP 200 with no data, and an empty
 * extract would publish every Shopify return as missing.
 */
class OmsGqlReturnsPageSource {

    static Closure fetcher(OmsGqlClient client) {
        return { Map ctx -> fetchAll(client, ctx) }
    }

    static Map fetchAll(OmsGqlClient client, Map ctx) {
        Map serverCounts = [returnsCount: 0, excludedNoShopifyRefCount: 0, excludedCancelledCount: 0]
        Map pagination = [strategy: "GRAPHQL_CURSOR", pageCount: 0, lookupCalls: 0]
        Closure failed = { String message ->
            [errors: [message], warnings: [], serverCounts: serverCounts, pagination: pagination, transport: "GRAPHQL"]
        }
        if (ctx.fromMillis == null || ctx.thruMillis == null) {
            return failed("The OMS GraphQL returns extract requires a date window.")
        }
        String filter = OmsGqlQueries.returnsWindowFilter(iso((Long) ctx.fromMillis), iso((Long) ctx.thruMillis))
        Closure pageConsumer = (Closure) ctx.pageConsumer
        int maxPages = maxPageCount((Map) ctx.config)
        try {
            String cursor = null
            Set<String> seenCursors = [] as Set
            while (true) {
                if ((pagination.pageCount as int) >= maxPages) {
                    throw new OmsGqlException("MALFORMED", "hit the page ceiling of ${maxPages} with more pages still reported; " +
                            "refusing to return a truncated extract")
                }
                Map vars = [q: filter, first: OmsGqlQueries.RETURNS_PAGE_SIZE]
                if (cursor) vars.after = cursor
                Map connection = OmsGqlOrderPageSource.requireConnection(
                        client.execute(OmsGqlQueries.returnsDocument(), vars, OmsGqlQueries.RETURNS_RESERVATION), "returns")
                pagination.pageCount = (pagination.pageCount as int) + 1
                List<Map> nodes = OmsGqlOrderGrainAssembler.edgeNodes(connection)
                serverCounts.returnsCount = (serverCounts.returnsCount as int) + nodes.size()

                List<Map> kept = []
                nodes.each { Map n ->
                    String verdict = OmsGqlReturnAssembler.classify(n)
                    if (verdict == OmsGqlReturnAssembler.CANCELLED) serverCounts.excludedCancelledCount = (serverCounts.excludedCancelledCount as int) + 1
                    else if (verdict == OmsGqlReturnAssembler.NO_SHOPIFY_REF) serverCounts.excludedNoShopifyRefCount = (serverCounts.excludedNoShopifyRefCount as int) + 1
                    else kept.add(n)
                }
                if (kept) pageConsumer.call(buildPage(client, kept, pagination))

                cursor = OmsGqlOrderPageSource.nextCursor(connection, seenCursors, "returns")
                if (cursor == null) break
            }
        } catch (OmsGqlException e) {
            return failed("OMS GraphQL returns extract failed [${e.code}]: ${e.message}".toString())
        }
        return [errors: [], warnings: [], serverCounts: serverCounts, pagination: pagination, transport: "GRAPHQL"]
    }

    /** All lookups for one page, then the records. Throws on any lookup failure — the page is never half-built. */
    private static List<Map> buildPage(OmsGqlClient client, List<Map> kept, Map pagination) {
        List<String> returnIds = kept.collect { it.returnId as String }
        List<Map> items = fetchByIds(client, OmsGqlQueries.returnItemsDocument(), "returnItems", "returnId", returnIds,
                OmsGqlQueries.RETURN_ITEMS_ID_CHUNK, OmsGqlQueries.RETURN_ITEMS_PAGE_SIZE, OmsGqlQueries.RETURN_ITEMS_RESERVATION, pagination)
        Map<String, List<Map>> itemsByReturn = items.groupBy { it.returnId as String }

        List<String> orderIds = items.collect { it.orderId as String }.findAll { it }.unique()
        Map<String, String> orderExternalIds = [:]
        Map<String, String> orderItemExternalIds = [:]
        if (orderIds) {
            fetchByIds(client, OmsGqlQueries.orderExternalIdsDocument(), "orders", "orderId", orderIds,
                    OmsGqlQueries.ORDERS_ID_CHUNK, OmsGqlQueries.ORDERS_ID_CHUNK, OmsGqlQueries.ORDERS_RESERVATION, pagination)
                    .each { Map n -> orderExternalIds[n.orderId as String] = n.externalId as String }
            fetchByIds(client, OmsGqlQueries.orderItemExternalIdsDocument(), "orderItems", "orderId", orderIds,
                    OmsGqlQueries.ORDER_ITEMS_ID_CHUNK, OmsGqlQueries.ORDER_ITEMS_PAGE_SIZE, OmsGqlQueries.ORDER_ITEMS_RESERVATION, pagination)
                    .each { Map n -> orderItemExternalIds["${n.orderId}|${n.orderItemSeqId}".toString()] = n.externalId as String }
        }
        List<String> productIds = items.collect { it.productId as String }.findAll { it }.unique()
        Map<String, String> productNames = [:]
        if (productIds) {
            fetchByIds(client, OmsGqlQueries.productNamesDocument(), "products", "productId", productIds,
                    OmsGqlQueries.PRODUCTS_ID_CHUNK, OmsGqlQueries.PRODUCTS_ID_CHUNK, OmsGqlQueries.PRODUCTS_RESERVATION, pagination)
                    .each { Map n -> productNames[n.productId as String] = n.internalName as String }
        }
        Map lookups = [orderExternalIds: orderExternalIds, orderItemExternalIds: orderItemExternalIds, productNames: productNames]
        return kept.collect { Map n -> OmsGqlReturnAssembler.build(n, itemsByReturn[n.returnId as String] ?: [], lookups) }
    }

    /** Every node for `ids`, chunked and paged to exhaustion. */
    private static List<Map> fetchByIds(OmsGqlClient client, String document, String root, String key, List<String> ids,
                                        int chunkSize, int pageSize, int reservation, Map pagination) {
        List<Map> all = []
        ids.collate(chunkSize).each { List<String> chunk ->
            String cursor = null
            Set<String> seenCursors = [] as Set
            while (true) {
                Map connection = OmsGqlOrderPageSource.requireConnection(
                        client.execute(document, OmsGqlQueries.inVariables(key, chunk, pageSize, cursor), reservation), root)
                pagination.lookupCalls = (pagination.lookupCalls as int) + 1
                all.addAll(OmsGqlOrderGrainAssembler.edgeNodes(connection))
                cursor = OmsGqlOrderPageSource.nextCursor(connection, seenCursors, root)
                if (cursor == null) break
            }
        }
        return all
    }

    private static int maxPageCount(Map config) {
        Object raw = config?.maxReturnsPageCount
        try {
            int value = raw == null ? OmsReturnsSourceSupport.MAX_RETURNS_PAGE_COUNT : (raw.toString().trim() as int)
            return value > 0 ? value : OmsReturnsSourceSupport.MAX_RETURNS_PAGE_COUNT
        } catch (Exception ignored) {
            return OmsReturnsSourceSupport.MAX_RETURNS_PAGE_COUNT
        }
    }

    private static String iso(Long millis) {
        return Instant.ofEpochMilli(millis).toString()
    }
}
