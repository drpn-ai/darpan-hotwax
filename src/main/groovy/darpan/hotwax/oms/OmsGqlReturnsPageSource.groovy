package darpan.hotwax.oms

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

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

    /** DAR-BE-116: kept returns per lookup pass; the lookups then run in chunks of 100 ids, not one pass per page. */
    static final int RETURNS_BATCH = 200

    static Closure fetcher(OmsGqlClient client, Map options = [:]) {
        return { Map ctx -> fetchAll(client, ctx + (options ?: [:])) }
    }

    static Map fetchAll(OmsGqlClient client, Map ctx) {
        Map serverCounts = [returnsCount: 0, excludedNoShopifyRefCount: 0, excludedCancelledCount: 0]
        // DAR-BE-094 R8 ("every return reaches NetSuite"): a check about NetSuite, not Shopify, keeps the returns Shopify
        // never sees (the OMS's own Happiness Guarantee and sellable returns, numeric ids, no Shopify reference).
        boolean keepNoShopifyRef = ctx.includeReturnsWithoutShopifyRef == true
        // R16 ("a cancelled exchange leaves nothing open in NetSuite"): only the cancelled returns, asked of the OMS by
        // status, every one kept (a cancelled Happiness Guarantee return has no Shopify reference either).
        boolean onlyCancelled = ctx.onlyCancelledReturns == true
        if (onlyCancelled) keepNoShopifyRef = true
        if (keepNoShopifyRef) serverCounts.keptNoShopifyRefCount = 0
        Map pagination = [strategy: "GRAPHQL_CURSOR", pageCount: 0, lookupCalls: 0]
        Closure failed = { String message ->
            [errors: [message], warnings: [], serverCounts: serverCounts, pagination: pagination, transport: "GRAPHQL"]
        }
        List<String> keys = (List<String>) ctx.lookupKeys
        if (!keys && (ctx.fromMillis == null || ctx.thruMillis == null)) {
            return failed("The OMS GraphQL returns extract requires a date window.")
        }
        if (keys && !(ctx.lookupKeyField in [null, "returnId"])) {
            return failed("The OMS GraphQL returns extract reads by returnId only, not '${ctx.lookupKeyField}'.")
        }
        Closure consumer = (Closure) ctx.pageConsumer
        Map shared = [pageSize: new AtomicInteger(OmsGqlQueries.RETURNS_PAGE_SIZE), pages: new AtomicInteger(0),
                      maxPages: OmsReturnsSourceSupport.MAX_RETURNS_PAGE_COUNT, pagination: pagination, serverCounts: serverCounts,
                      onlyCancelled: onlyCancelled, keepNoShopifyRef: keepNoShopifyRef,
                      pageConsumer: { List<Map> records -> synchronized (pagination) { consumer.call(records) } }]
        Closure<String> scoped = { String filter -> onlyCancelled ? "statusId:RETURN_CANCELLED ${filter}".toString() : filter }
        try {
            if (keys) {
                // DAR-BE-119: an older open finding's returns, by id, whatever their date.
                OmsGqlOrderPageSource.runEach(keys.collate(OmsGqlQueries.LOOKUP_KEYS_CHUNK), OmsGqlOrderPageSource.CONCURRENCY) { List<String> chunk ->
                    fetchSlice(client, shared, scoped(OmsGqlQueries.keysFilter("returnId", chunk)))
                }
            } else {
                List<List<Long>> slices = OmsGqlOrderPageSource.daySlices((Long) ctx.fromMillis, (Long) ctx.thruMillis)
                pagination.sliceCount = slices.size()
                if (!onlyCancelled && client.realEndpoint) {
                    readReturnsShared(client, shared, slices, (Map) ctx.config)
                } else {
                    OmsGqlOrderPageSource.runSlices(slices, OmsGqlOrderPageSource.CONCURRENCY) { long from, long thru ->
                        fetchSlice(client, shared, scoped(OmsGqlQueries.returnsWindowFilter(iso(from), iso(thru))))
                    }
                }
            }
        } catch (IllegalArgumentException e) {
            return failed(e.message)
        } catch (OmsGqlException e) {
            return failed("OMS GraphQL returns extract failed [${e.code}]: ${e.message}".toString())
        }
        if (!pagination.sharedRead) pagination.pageCount = ((AtomicInteger) shared.pages).get()
        return [errors: [], warnings: [], serverCounts: serverCounts, pagination: pagination, transport: "GRAPHQL"]
    }

    /**
     * DAR-BE-116: the returns read through OmsGqlSharedRead. The producer builds every return either check could keep
     * (with and without a Shopify reference) and tags each with its classification; cancelled ones are only counted.
     * Each caller then keeps and counts by its OWN includeReturnsWithoutShopifyRef, so R8 and R11 read the month once.
     */
    private static void readReturnsShared(OmsGqlClient client, Map shared, List<List<Long>> slices, Map config) {
        Map pagination = (Map) shared.pagination
        Map serverCounts = (Map) shared.serverCounts
        boolean keepNoShopifyRef = shared.keepNoShopifyRef == true
        String key = "returns|${client.readScope}|${slices.first()[0]}|${slices.last()[1]}".toString()
        Map read = OmsGqlSharedRead.read(key) { Closure emit ->
            Map readPagination = new LinkedHashMap(pagination)
            Map producerShared = new LinkedHashMap(shared) + [pagination: readPagination, keepNoShopifyRef: true, onlyCancelled: false,
                    serverCounts: [returnsCount: 0, excludedNoShopifyRefCount: 0, excludedCancelledCount: 0, keptNoShopifyRefCount: 0],
                    sink: emit]
            OmsGqlOrderPageSource.runSlices(slices, OmsGqlOrderPageSource.CONCURRENCY) { long from, long thru ->
                fetchSlice(client, producerShared, OmsGqlQueries.returnsWindowFilter(iso(from), iso(thru)))
            }
            readPagination.pageCount = ((AtomicInteger) producerShared.pages).get()
            return [pagination: readPagination]
        }
        pagination.putAll((Map) ((Map) read.stats).pagination)
        pagination.sharedRead = true
        groovy.json.JsonSlurper slurper = new groovy.json.JsonSlurper()
        List<Map> batch = []
        ((File) read.file).eachLine("UTF-8") { String line ->
            if (!line) return
            Map record = (Map) slurper.parseText(line)
            String verdict = record.remove(SHARED_VERDICT) as String
            serverCounts.returnsCount = (serverCounts.returnsCount as int) + 1
            if (verdict == OmsGqlReturnAssembler.CANCELLED) {
                serverCounts.excludedCancelledCount = (serverCounts.excludedCancelledCount as int) + 1
                return
            }
            if (verdict == OmsGqlReturnAssembler.NO_SHOPIFY_REF) {
                if (!keepNoShopifyRef) {
                    serverCounts.excludedNoShopifyRefCount = (serverCounts.excludedNoShopifyRefCount as int) + 1
                    return
                }
                serverCounts.keptNoShopifyRefCount = (serverCounts.keptNoShopifyRefCount as int) + 1
            }
            batch.add(record)
            if (batch.size() >= RETURNS_BATCH) { ((Closure) shared.pageConsumer).call(batch); batch = [] }
        }
        if (batch) ((Closure) shared.pageConsumer).call(batch)
    }

    /** The classification a shared read's raw return carries, removed before the record reaches a caller. */
    static final String SHARED_VERDICT = "_sharedVerdict"

    /** One slice's returns: the ones this read keeps are built every RETURNS_BATCH by one lookup pass. */
    private static void fetchSlice(OmsGqlClient client, Map shared, String filter) {
        Map pagination = (Map) shared.pagination
        Map serverCounts = (Map) shared.serverCounts
        boolean onlyCancelled = shared.onlyCancelled == true
        boolean keepNoShopifyRef = shared.keepNoShopifyRef == true
        Closure sink = (Closure) shared.sink
        Closure<List<Map>> keep = { List<Map> nodes ->
            List<Map> kept = []
            synchronized (pagination) {
                serverCounts.returnsCount = (serverCounts.returnsCount as int) + nodes.size()
                nodes.each { Map n ->
                    String verdict = OmsGqlReturnAssembler.classify(n)
                    if (sink != null) {
                        // A shared read: cancelled returns are only counted by the callers; the rest are built and tagged.
                        if (verdict == OmsGqlReturnAssembler.CANCELLED) sink.call([(SHARED_VERDICT): verdict])
                        else { n.put(SHARED_VERDICT, verdict); kept.add(n) }
                        return
                    }
                    if (verdict == OmsGqlReturnAssembler.CANCELLED && onlyCancelled) kept.add(n)
                    else if (onlyCancelled) { /* the OMS answered a status it was not asked for: not R16's */ }
                    else if (verdict == OmsGqlReturnAssembler.CANCELLED) serverCounts.excludedCancelledCount = (serverCounts.excludedCancelledCount as int) + 1
                    else if (verdict == OmsGqlReturnAssembler.NO_SHOPIFY_REF && keepNoShopifyRef) {
                        serverCounts.keptNoShopifyRefCount = (serverCounts.keptNoShopifyRefCount as int) + 1
                        kept.add(n)
                    }
                    else if (verdict == OmsGqlReturnAssembler.NO_SHOPIFY_REF) serverCounts.excludedNoShopifyRefCount = (serverCounts.excludedNoShopifyRefCount as int) + 1
                    else kept.add(n)
                }
            }
            return kept
        }
        OmsGqlOrderPageSource.pageSlice(client, shared, OmsGqlQueries.returnsDocument(), OmsGqlQueries.RETURNS_RESERVATION, "returns",
                filter, RETURNS_BATCH, keep) { List<Map> batch ->
            List<Map> built = buildPage(client, batch, pagination)
            if (sink != null) built.eachWithIndex { Map r, int i -> sink.call(r + [(SHARED_VERDICT): batch[i].get(SHARED_VERDICT)]) }
            else ((Closure) shared.pageConsumer).call(built)
        }
    }

    /** All lookups for one page, then the records. Throws on any lookup failure — the page is never half-built. */
    private static List<Map> buildPage(OmsGqlClient client, List<Map> kept, Map pagination) {
        List<String> returnIds = kept.collect { it.returnId as String }
        List<Map> items = fetchByIds(client, OmsGqlQueries.returnItemsDocument(), "returnItems", "returnId", returnIds,
                OmsGqlQueries.RETURN_ITEMS_ID_CHUNK, OmsGqlQueries.RETURN_ITEMS_PAGE_SIZE, OmsGqlQueries.RETURN_ITEMS_RESERVATION, pagination)
        Map<String, List<Map>> itemsByReturn = items.groupBy { it.returnId as String }

        List<String> orderIds = items.collect { it.orderId as String }.findAll { it }.unique()
        Map<String, String> orderExternalIds = [:]
        Map<String, String> orderNames = [:]
        Map<String, String> orderDates = [:]
        Map<String, String> orderCustomers = [:]
        Map<String, String> orderProductStoreIds = [:]
        Map<String, String> orderNetsuiteIds = [:]
        Map<String, Object> orderGrandTotals = [:]
        Map<String, String> orderItemExternalIds = [:]
        if (orderIds) {
            ordersById(client, orderIds, pagination).each { String orderId, Object o ->
                Map n = (Map) o
                orderExternalIds[orderId] = n.externalId as String
                orderNames[orderId] = n.orderName as String
                orderDates[orderId] = n.orderDate as String
                orderCustomers[orderId] = OmsOrderLineUnitSupport.customerNameOf(n)
                orderProductStoreIds[orderId] = n.productStoreId as String
                orderGrandTotals[orderId] = n.grandTotal
                orderNetsuiteIds[orderId] = netsuiteOrderId(n)
            }
            OmsGqlLookupCache.lookup(client, OmsGqlLookupCache.ORDER_ITEMS, orderIds) { List<String> ids ->
                fetchByIds(client, OmsGqlQueries.orderItemExternalIdsDocument(), "orderItems", "orderId", ids,
                        OmsGqlQueries.ORDER_ITEMS_ID_CHUNK, OmsGqlQueries.ORDER_ITEMS_PAGE_SIZE, OmsGqlQueries.ORDER_ITEMS_RESERVATION, pagination)
                        .groupBy { it.orderId as String }
            }.each { String orderId, Object lines ->
                ((List<Map>) lines).each { Map n -> orderItemExternalIds["${orderId}|${n.orderItemSeqId}".toString()] = n.externalId as String }
            }
        }
        List<String> productIds = items.collect { it.productId as String }
        Map<String, Object> productNames = productNames(client, productIds, pagination)
        Map<String, Object> productNetsuiteIds = OmsGqlLookupCache.lookup(client, OmsGqlLookupCache.PRODUCT_NETSUITE_ID, productIds) { List<String> ids ->
            fetchByIds(client, OmsGqlQueries.productNetsuiteIdsDocument(), "goodIdentifications", OmsGqlQueries.NETSUITE_PRODUCT_ID_KEY,
                    ids, OmsGqlQueries.PRODUCTS_ID_CHUNK, OmsGqlQueries.PRODUCTS_ID_CHUNK, OmsGqlQueries.PRODUCTS_RESERVATION, pagination)
                    .findAll { !it.thruDate }.collectEntries { Map n -> [(n.productId as String): n.idValue as String] }
        }
        Map lookups = [orderExternalIds: orderExternalIds, orderNames: orderNames, orderDates: orderDates, orderCustomers: orderCustomers, orderProductStoreIds: orderProductStoreIds, orderNetsuiteIds: orderNetsuiteIds,
                       orderGrandTotals: orderGrandTotals, exchangeChains: exchangeChains(client, items, pagination),
                       orderItemExternalIds: orderItemExternalIds, productNames: productNames,
                       productNetsuiteIds: productNetsuiteIds]
        return kept.collect { Map n -> OmsGqlReturnAssembler.build(n, itemsByReturn[n.returnId as String] ?: [], lookups) }
    }

    /**
     * Aditi 2026-10-24 (R8 F-100860, M263376): an exchange return reaches NetSuite after its exchange order, and that order
     * waits while another return of the same Shopify order is still open (there, an AfterShip warranty requested since
     * August). returnId -> [exchangeOrderId, exchangeOrderName, exchangeOrderNetsuiteId, openReturnOnOrderId, openReturnOnOrderStatus].
     * Asked only for returns with an exchange item; the open return only for an exchange order not in NetSuite.
     */
    private static Map<String, Map> exchangeChains(OmsGqlClient client, List<Map> items, Map pagination) {
        List<String> exchangeReturnIds = items.findAll { it.returnTypeId == "RTN_EXCHANGE" }*.returnId.collect { it as String }.unique()
        if (!exchangeReturnIds) return [:]
        Map<String, String> exchangeOrderOf = [:]
        fetchByIds(client, OmsGqlQueries.returnResponsesDocument(), "returns", "returnId", exchangeReturnIds,
                OmsGqlQueries.RETURN_RESPONSES_CHUNK, OmsGqlQueries.RETURN_RESPONSES_CHUNK, OmsGqlQueries.RETURN_RESPONSES_RESERVATION, pagination)
                .each { Map n ->
                    String order = ((n.responses ?: []) as List<Map>).findResult { it?.replacementOrderId as String }
                    if (order) exchangeOrderOf[n.returnId as String] = order
                }
        if (!exchangeOrderOf) return [:]
        Map<String, Object> orders = ordersById(client, exchangeOrderOf.values().toList(), pagination)
        // Only an exchange order NetSuite does not have: the open return holding it, on the order(s) sharing its Shopify id.
        List<String> blockedShopifyIds = exchangeOrderOf.values().collect { orders[it] }.findAll { it && !netsuiteOrderId(it) && it.externalId }
                *.externalId.collect { it as String }.unique()
        Map<String, List<Map>> openByShopifyId = [:]
        if (blockedShopifyIds) {
            Map<String, String> shopifyIdOf = [:]
            fetchByIds(client, OmsGqlQueries.orderExternalIdsDocument(), "orders", "externalId", blockedShopifyIds,
                    OmsGqlQueries.ORDERS_ID_CHUNK, OmsGqlQueries.ORDERS_ID_CHUNK, OmsGqlQueries.ORDERS_RESERVATION, pagination)
                    .each { Map n -> shopifyIdOf[n.orderId as String] = n.externalId as String }
            fetchByIds(client, OmsGqlQueries.returnItemsDocument(), "returnItems", "orderId", shopifyIdOf.keySet().toList(),
                    OmsGqlQueries.RETURN_ITEMS_ID_CHUNK, OmsGqlQueries.RETURN_ITEMS_PAGE_SIZE, OmsGqlQueries.RETURN_ITEMS_RESERVATION, pagination)
                    .findAll { !(it.statusId in ["RETURN_COMPLETED", "RETURN_CANCELLED", "RETURN_REJECTED"]) }
                    .each { Map n -> openByShopifyId.computeIfAbsent(shopifyIdOf[n.orderId as String]) { [] }.add(n) }
        }
        return exchangeOrderOf.collectEntries { String returnId, String orderId ->
            Map o = orders[orderId]
            Map open = (openByShopifyId[o?.externalId as String] ?: []).find { it.returnId != returnId }
            [(returnId): [exchangeOrderId: orderId, exchangeOrderName: o?.orderName, exchangeOrderNetsuiteId: netsuiteOrderId(o),
                          openReturnOnOrderId: open?.returnId, openReturnOnOrderStatus: open?.statusId]]
        }
    }

    /** DAR-BE-129: product id -> internalName (the SKU), cached per read scope. Shared with the order-items read. */
    static Map<String, Object> productNames(OmsGqlClient client, List<String> productIds, Map pagination) {
        return OmsGqlLookupCache.lookup(client, OmsGqlLookupCache.PRODUCT_NAME, productIds) { List<String> ids ->
            fetchByIds(client, OmsGqlQueries.productNamesDocument(), "products", "productId", ids,
                    OmsGqlQueries.PRODUCTS_ID_CHUNK, OmsGqlQueries.PRODUCTS_ID_CHUNK, OmsGqlQueries.PRODUCTS_RESERVATION, pagination)
                    .findAll { it.internalName }.collectEntries { Map n -> [(n.productId as String): n.internalName as String] }
        }
    }

    /** DAR-BE-129: order id -> its orderExternalIdsDocument node, cached per read scope for a run's pages. */
    private static Map<String, Object> ordersById(OmsGqlClient client, List<String> orderIds, Map pagination) {
        return OmsGqlLookupCache.lookup(client, OmsGqlLookupCache.ORDER, orderIds) { List<String> ids ->
            fetchByIds(client, OmsGqlQueries.orderExternalIdsDocument(), "orders", "orderId", ids,
                    OmsGqlQueries.ORDERS_ID_CHUNK, OmsGqlQueries.ORDERS_ID_CHUNK, OmsGqlQueries.ORDERS_RESERVATION, pagination)
                    .collectEntries { Map n -> [(n.orderId as String): n] }
        }
    }

    /** Every node for `ids`, chunked and paged to exhaustion. Shared with OmsReturnLinesLookupSupport. */
    static List<Map> fetchByIds(OmsGqlClient client, String document, String root, String key, List<String> ids,
                                        int chunkSize, int pageSize, int reservation, Map pagination) {
        List<Map> all = []
        ids.collate(chunkSize).each { List<String> chunk ->
            String cursor = null
            Set<String> seenCursors = [] as Set
            int size = pageSize
            while (true) {
                Map answer
                try {
                    answer = client.execute(document, OmsGqlQueries.inVariables(key, chunk, size, cursor), reservation)
                } catch (OmsGqlException e) {
                    // DAR-BE-116: a lower per-query cap than the page was sized for; the cursor pages the rest.
                    if (e.code != "COST_EXCEEDED" || size <= 1) throw e
                    size = Math.max(1, size.intdiv(2))
                    continue
                }
                Map connection = OmsGqlOrderPageSource.requireConnection(answer, root)
                synchronized (pagination) { pagination.lookupCalls = (pagination.lookupCalls as int) + 1 }
                all.addAll(OmsGqlOrderGrainAssembler.edgeNodes(connection))
                cursor = OmsGqlOrderPageSource.nextCursor(connection, seenCursors, root)
                if (cursor == null) break
            }
        }
        return all
    }


    private static String netsuiteOrderId(Map order) {
        return ((order?.identifications ?: []) as List<Map>).find { it?.orderIdentificationTypeId == "NETSUITE_ORDER_ID" }?.idValue as String
    }

    private static String iso(Long millis) {
        return Instant.ofEpochMilli(millis).toString()
    }
}
