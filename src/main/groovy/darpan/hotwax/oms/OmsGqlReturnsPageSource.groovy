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
 * server-side), then flat, id-keyed lookups for the ones the read writes — returnItems, the orders and order
 * items that carry the Shopify ids, and products for sku — then build REST-shaped records and hand them
 * to the extractor's pageConsumer. The join-key fallback, projection and writing stay in
 * OmsReturnsSourceSupport.
 *
 * DAR-BE-318 (plan 3b-2b, Ruling 3b2b-12): a WINDOW read writes every return it reads, cancelled and without a Shopify
 * reference included, each with its classification in omsReturnClass (KEEP, CANCELLED or NO_SHOPIFY_REF). darpan's Spark
 * Keep sets the cancelled and no-reference ones aside through the OMS_RETURNS_GQL row's declared Keep lines
 * (data/HotWaxConnectorSeedData.xml), as this read used to leave them out. The server counts are counted as before. A read
 * BY returnId (lookupKeys) is not Keep's business: it leaves them out itself, as before, and writes the class of each return
 * it keeps (fix round 1: the field is selectable, and a side read by the other side's keys still runs Keep, so a tenant rule
 * on it must find it). The read by replacement order is the conclusion brain's lookup and writes no class.
 * onlyCancelledReturns still asks the OMS for RETURN_CANCELLED only, and a return of any other status it answers is still
 * left out: that is the read's scope, not Keep.
 *
 * Every lookup follows its own cursor: a truncated items page would understate returnTotal, and a
 * truncated order page would emit null Shopify ids that read as real differences. Any GraphQL error
 * fails the whole extract — THROTTLED in particular arrives as HTTP 200 with no data, and an empty
 * extract would publish every Shopify return as missing.
 */
class OmsGqlReturnsPageSource {

    /** DAR-BE-116: kept returns per lookup pass; the lookups then run in chunks of 100 ids, not one pass per page. */
    static final int RETURNS_BATCH = 200

    /**
     * DAR-BE-318: a return's OmsGqlReturnAssembler.classify verdict, written by a window read on every return and by a read
     * by returnId on every return it keeps; the read by replacement order writes it on none. The OMS_RETURNS_GQL row's
     * keepLinesJson reads it; never selected from the OMS (OmsKeptFieldsProjection.OMS_RETURNS appends it).
     */
    static final String RETURN_CLASS_FIELD = "omsReturnClass"

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
        // the answers an onlyCancelledReturns read leaves out (any status but RETURN_CANCELLED), counted, never dropped silently
        if (onlyCancelled) serverCounts.excludedNotCancelledCount = 0
        Map pagination = [strategy: "GRAPHQL_CURSOR", pageCount: 0, lookupCalls: 0]
        Closure failed = { String message ->
            [errors: [message], warnings: [], serverCounts: serverCounts, pagination: pagination, transport: "GRAPHQL"]
        }
        // OC166: a key asked twice (across chunks) would be written twice, a false duplicate key downstream
        List<String> keys = ((List<String>) ctx.lookupKeys)?.unique(false)
        if (!keys && (ctx.fromMillis == null || ctx.thruMillis == null)) {
            return failed("The OMS GraphQL returns extract requires a date window.")
        }
        if (keys && !(ctx.lookupKeyField in [null, "returnId", REPLACEMENT_KEY, ORDER_KEY])) {
            return failed("The OMS GraphQL returns extract reads by returnId, ${REPLACEMENT_KEY} or ${ORDER_KEY} only, not '${ctx.lookupKeyField}'.")
        }
        boolean byOrder = keys && ctx.lookupKeyField == ORDER_KEY
        // DAR-BE-192: asked by replacement order, every return naming one is kept (a warranty claim has no Shopify reference)
        // and carries its credit, for the conclusion brain's held-by-return causes.
        boolean byReplacement = keys && ctx.lookupKeyField == REPLACEMENT_KEY
        if (byReplacement) keepNoShopifyRef = true
        if (keepNoShopifyRef) serverCounts.keptNoShopifyRefCount = 0
        Closure consumer = (Closure) ctx.pageConsumer
        Map shared = [pageSize: new AtomicInteger(OmsGqlQueries.RETURNS_PAGE_SIZE), pages: new AtomicInteger(0),
                      maxPages: OmsReturnsSourceSupport.MAX_RETURNS_PAGE_COUNT, pagination: pagination, serverCounts: serverCounts,
                      onlyCancelled: onlyCancelled, keepNoShopifyRef: keepNoShopifyRef,
                      // DAR-BE-318: a window read writes every return with its class, for Spark Keep; a read by key keeps
                      // its own scope, and writes the class of what it keeps unless it is the brain's replacement lookup.
                      writeEveryClass: !keys, writeClass: !byReplacement,
                      // DAR-BE-192: the credit HotWax gives each return (returnCredit), only when a run asks for it.
                      withReturnCredit: ctx.withReturnCredit == true || byReplacement,
                      pageConsumer: { List<Map> records -> synchronized (pagination) { consumer.call(records) } }]
        Closure<String> scoped = { String filter -> onlyCancelled ? "statusId:RETURN_CANCELLED ${filter}".toString() : filter }
        try {
            if (byReplacement) {
                // DAR-BE-192: each replacement's return, found from the order (no search names a return by its replacement),
                // then read like any return by id and tagged with the replacement it was found for.
                Map<String, String> replacementOf = replacementReturns(client, keys, pagination)
                Closure inner = (Closure) shared.pageConsumer
                shared.pageConsumer = { List<Map> records ->
                    inner.call(records.collect { Map r -> r + [replacementOrderId: replacementOf[r.returnId as String]] })
                }
                OmsGqlOrderPageSource.runEach((replacementOf.keySet() as List<String>).collate(OmsGqlQueries.LOOKUP_KEYS_CHUNK),
                        OmsGqlOrderPageSource.CONCURRENCY) { List<String> chunk ->
                    fetchSlice(client, shared, OmsGqlQueries.keysFilter("returnId", chunk))
                }
            } else if (byOrder) {
                // DAR-BE-352: an older open finding keyed on its Shopify order id (Malbon R6), whatever its date. Only the
                // returns whose record names an asked order are the findings'; one whose first item is on another order is not.
                Set<String> asked = keys as Set<String>
                Closure inner = (Closure) shared.pageConsumer
                shared.pageConsumer = { List<Map> records -> inner.call(records.findAll { asked.contains(it.orderExternalId as String) }) }
                OmsGqlOrderPageSource.runEach(orderReturns(client, keys, pagination).collate(OmsGqlQueries.LOOKUP_KEYS_CHUNK),
                        OmsGqlOrderPageSource.CONCURRENCY) { List<String> chunk ->
                    fetchSlice(client, shared, scoped(OmsGqlQueries.keysFilter("returnId", chunk)))
                }
            } else if (keys) {
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
     * DAR-BE-116: the returns read through OmsGqlSharedRead. The producer builds every return and tags each with its
     * classification. Each caller counts by its OWN includeReturnsWithoutShopifyRef, so R8 and R11 read the month once, and
     * writes every return with its class (DAR-BE-318): Spark Keep sets aside what each caller's options leave out.
     */
    private static void readReturnsShared(OmsGqlClient client, Map shared, List<List<Long>> slices, Map config) {
        Map pagination = (Map) shared.pagination
        Map serverCounts = (Map) shared.serverCounts
        boolean keepNoShopifyRef = shared.keepNoShopifyRef == true
        // DAR-BE-192: a read with credits builds different records, so it never shares a read without them.
        String key = "returns|${client.readScope}|${slices.first()[0]}|${slices.last()[1]}${shared.withReturnCredit ? '|credit' : ''}".toString()
        List<Map> batch = []
        // DAR-BE-129: followed, so the returns reach the extract's consumer (and the run's progress) as they are read.
        Map read = OmsGqlSharedRead.follow(key, { Closure emit ->
            Map readPagination = new LinkedHashMap(pagination)
            Map producerShared = new LinkedHashMap(shared) + [pagination: readPagination, keepNoShopifyRef: true, onlyCancelled: false,
                    serverCounts: [returnsCount: 0, excludedNoShopifyRefCount: 0, excludedCancelledCount: 0, keptNoShopifyRefCount: 0],
                    sink: emit]
            OmsGqlOrderPageSource.runSlices(slices, OmsGqlOrderPageSource.CONCURRENCY) { long from, long thru ->
                fetchSlice(client, producerShared, OmsGqlQueries.returnsWindowFilter(iso(from), iso(thru)))
            }
            readPagination.pageCount = ((AtomicInteger) producerShared.pages).get()
            return [pagination: readPagination]
        }) { Map record ->
            String verdict = record.remove(SHARED_VERDICT) as String
            serverCounts.returnsCount = (serverCounts.returnsCount as int) + 1
            countVerdict(serverCounts, verdict, keepNoShopifyRef)
            record.put(RETURN_CLASS_FIELD, verdict)
            batch.add(record)
            if (batch.size() >= RETURNS_BATCH) { ((Closure) shared.pageConsumer).call(batch); batch = [] }
        }
        if (batch) ((Closure) shared.pageConsumer).call(batch)
        pagination.putAll((Map) ((Map) read.stats).pagination)
        pagination.sharedRead = true
    }

    /** The classification a shared read's raw return carries, removed before the record reaches a caller. */
    static final String SHARED_VERDICT = "_sharedVerdict"

    /**
     * What the returns read counts of one return, by its classification: a cancelled one, and one with no Shopify reference
     * as kept or excluded by the caller's includeReturnsWithoutShopifyRef. Counted whether or not the return is written
     * (DAR-BE-318: a window read writes them all, for Spark Keep), so the counts are what they were.
     */
    private static void countVerdict(Map serverCounts, String verdict, boolean keepNoShopifyRef) {
        if (verdict == OmsGqlReturnAssembler.CANCELLED) {
            serverCounts.excludedCancelledCount = (serverCounts.excludedCancelledCount as int) + 1
        } else if (verdict == OmsGqlReturnAssembler.NO_SHOPIFY_REF) {
            String count = keepNoShopifyRef ? "keptNoShopifyRefCount" : "excludedNoShopifyRefCount"
            serverCounts.put(count, (serverCounts.get(count) as int) + 1)
        }
    }

    /**
     * One slice's returns: the ones this read writes are built every RETURNS_BATCH by one lookup pass. A window read
     * (writeEveryClass) writes every return; a read by key leaves out what its scope excludes, as before. Each written return
     * carries its class unless the read is the replacement lookup (writeClass false).
     */
    private static void fetchSlice(OmsGqlClient client, Map shared, String filter) {
        Map pagination = (Map) shared.pagination
        Map serverCounts = (Map) shared.serverCounts
        boolean onlyCancelled = shared.onlyCancelled == true
        boolean keepNoShopifyRef = shared.keepNoShopifyRef == true
        boolean writeEveryClass = shared.writeEveryClass == true
        boolean writeClass = shared.writeClass == true
        Closure sink = (Closure) shared.sink
        Closure<List<Map>> keep = { List<Map> nodes ->
            List<Map> kept = []
            synchronized (pagination) {
                serverCounts.returnsCount = (serverCounts.returnsCount as int) + nodes.size()
                nodes.each { Map n ->
                    String verdict = OmsGqlReturnAssembler.classify(n)
                    n.put(SHARED_VERDICT, verdict)
                    // A shared read: every return is built and tagged; each caller counts by its own options.
                    if (sink != null) { kept.add(n); return }
                    if (onlyCancelled) {
                        // the OMS was asked for RETURN_CANCELLED only: a status it was not asked for is not R16's, Keep or not
                        if (verdict == OmsGqlReturnAssembler.CANCELLED) kept.add(n)
                        else serverCounts.excludedNotCancelledCount = (serverCounts.excludedNotCancelledCount as int) + 1
                        return
                    }
                    countVerdict(serverCounts, verdict, keepNoShopifyRef)
                    boolean scoped = verdict == OmsGqlReturnAssembler.KEEP || (verdict == OmsGqlReturnAssembler.NO_SHOPIFY_REF && keepNoShopifyRef)
                    if (writeEveryClass || scoped) kept.add(n)
                }
            }
            return kept
        }
        OmsGqlOrderPageSource.pageSlice(client, shared, OmsGqlQueries.returnsDocument(), OmsGqlQueries.RETURNS_RESERVATION, "returns",
                filter, RETURNS_BATCH, keep) { List<Map> batch ->
            List<Map> built = buildPage(client, batch, pagination, shared.withReturnCredit == true)
            if (sink != null) built.eachWithIndex { Map r, int i -> sink.call(r + [(SHARED_VERDICT): batch[i].get(SHARED_VERDICT)]) }
            else {
                if (writeClass) built.eachWithIndex { Map r, int i -> r.put(RETURN_CLASS_FIELD, batch[i].get(SHARED_VERDICT)) }
                ((Closure) shared.pageConsumer).call(built)
            }
        }
    }

    /** All lookups for one page, then the records. Throws on any lookup failure — the page is never half-built. */
    private static List<Map> buildPage(OmsGqlClient client, List<Map> kept, Map pagination, boolean withReturnCredit = false) {
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
        if (withReturnCredit) lookups.returnCredits = returnCredits(client, returnIds, pagination)
        return kept.collect { Map n -> OmsGqlReturnAssembler.build(n, itemsByReturn[n.returnId as String] ?: [], lookups) }
    }

    /**
     * Aditi 2026-10-24 (R8 F-100860, M263376): an exchange return's NetSuite records wait while another return of the same
     * Shopify order is still open (there, an AfterShip warranty requested since August). NetSuite takes them in order: the
     * return's RMA, its credit memo, then the exchange order's sales order (Aditi 2026-11-10, DAR-UI-092), so a missing
     * exchange sales order means the RMA is missing too.
     * returnId -> [exchangeOrderId, exchangeOrderName, exchangeOrderNetsuiteId, openReturnOnOrderId, openReturnOnOrderStatus].
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

    /** DAR-BE-192: the lookup key that reads the returns naming these replacement orders. */
    static final String REPLACEMENT_KEY = "replacementOrderId"
    private static final String ORDER_NAME = /[A-Za-z0-9_\-#]{1,60}/

    /**
     * DAR-BE-192. returnId -> the replacement it names, for these replacement orders. Only a return's responses name its
     * replacement and no search reaches them by it, so the returns are found through the orders they are on: every order
     * sharing the replacement's Shopify id (an exchange keeps the original's, so EXC-2's return on EXC-1 is found too), and
     * the orders its name descends from (gorjana 2026-11-04: WTY-#GOR195459209-1 replaces #GOR195459209, WTY-#GOR196813520-1-1
     * replaces WTY-#GOR196813520-1). A return whose items name no order cannot be found this way; R2 names that cause.
     */
    private static Map<String, String> replacementReturns(OmsGqlClient client, List<String> replacementIds, Map pagination) {
        Set<String> wanted = replacementIds as Set<String>
        Map<String, Object> replacements = ordersById(client, replacementIds, pagination)
        Set<String> candidates = new LinkedHashSet<String>()
        List<String> shopifyIds = replacements.values().collect { ((Map) it).externalId as String }.findAll { it }.unique()
        if (shopifyIds) fetchByIds(client, OmsGqlQueries.orderExternalIdsDocument(), "orders", "externalId", shopifyIds,
                OmsGqlQueries.ORDERS_ID_CHUNK, OmsGqlQueries.ORDERS_ID_CHUNK, OmsGqlQueries.ORDERS_RESERVATION, pagination)
                .each { candidates.add(it.orderId as String) }
        List<String> names = replacements.values().collectMany { originalNames(((Map) it).orderName as String) }.unique()
        // HotWax allows only eq on orderName (a comma `in` is refused: OPERATOR_NOT_ALLOWED), so one name a query.
        if (names) fetchByIds(client, OmsGqlQueries.orderExternalIdsDocument(), "orders", "orderName", names,
                1, OmsGqlQueries.ORDERS_ID_CHUNK, OmsGqlQueries.ORDERS_RESERVATION, pagination)
                .each { candidates.add(it.orderId as String) }
        candidates.removeAll(wanted)
        if (!candidates) return [:]
        List<String> returnIds = fetchByIds(client, OmsGqlQueries.returnItemsDocument(), "returnItems", "orderId", candidates as List<String>,
                OmsGqlQueries.RETURN_ITEMS_ID_CHUNK, OmsGqlQueries.RETURN_ITEMS_PAGE_SIZE, OmsGqlQueries.RETURN_ITEMS_RESERVATION, pagination)
                .collect { it.returnId as String }.findAll { it }.unique()
        Map<String, String> out = [:]
        if (returnIds) fetchByIds(client, OmsGqlQueries.returnResponsesDocument(), "returns", "returnId", returnIds,
                OmsGqlQueries.RETURN_RESPONSES_CHUNK, OmsGqlQueries.RETURN_RESPONSES_CHUNK, OmsGqlQueries.RETURN_RESPONSES_RESERVATION, pagination)
                .each { Map n ->
                    String named = ((n.responses ?: []) as List<Map>).collect { it?.replacementOrderId as String }.find { wanted.contains(it) }
                    if (named) out.putIfAbsent(n.returnId as String, named)
                }
        return out
    }

    /** DAR-BE-352: the lookup key that reads the returns on the orders carrying these Shopify order ids. */
    static final String ORDER_KEY = "orderExternalId"

    /**
     * DAR-BE-352. The ids of the returns on every order carrying these Shopify order ids. No returns search names an order,
     * so they are found as replacementReturns finds its candidates: the orders by externalId (every type, as the window
     * read names a return's order whatever its type: an exchange keeps the original's id), then those orders' return items.
     */
    private static List<String> orderReturns(OmsGqlClient client, List<String> shopifyOrderIds, Map pagination) {
        OmsGqlQueries.requireIdKeys(shopifyOrderIds)
        List<String> orderIds = fetchByIds(client, OmsGqlQueries.orderExternalIdsDocument(), "orders", "externalId", shopifyOrderIds,
                OmsGqlQueries.ORDERS_ID_CHUNK, OmsGqlQueries.ORDERS_ID_CHUNK, OmsGqlQueries.ORDERS_RESERVATION, pagination)
                .collect { it.orderId as String }.findAll { it }.unique()
        if (!orderIds) return []
        return fetchByIds(client, OmsGqlQueries.returnItemsDocument(), "returnItems", "orderId", orderIds,
                OmsGqlQueries.RETURN_ITEMS_ID_CHUNK, OmsGqlQueries.RETURN_ITEMS_PAGE_SIZE, OmsGqlQueries.RETURN_ITEMS_RESERVATION, pagination)
                .collect { it.returnId as String }.findAll { it }.unique()
    }

    /** DAR-BE-192. The names a replacement descends from: its name less its last -N, and that less WTY- / EXC-. */
    static List<String> originalNames(String name) {
        def m = (name ?: "") =~ /^(.+)-\d+$/
        if (!m.matches()) return []
        String base = m.group(1)
        return [base, base.replaceFirst(/^(WTY|EXC)-/, "")].unique().findAll { it ==~ ORDER_NAME && it != name }
    }

    /** DAR-BE-192: returnId -> the credit HotWax gives the return (OmsGqlReturnAssembler.creditOf its responses). */
    private static Map<String, BigDecimal> returnCredits(OmsGqlClient client, List<String> returnIds, Map pagination) {
        if (!returnIds) return [:]
        return fetchByIds(client, OmsGqlQueries.returnCreditsDocument(), "returns", "returnId", returnIds,
                OmsGqlQueries.RETURN_CREDITS_CHUNK, OmsGqlQueries.RETURN_CREDITS_CHUNK, OmsGqlQueries.RETURN_CREDITS_RESERVATION, pagination)
                .collectEntries { Map n -> [(n.returnId as String): OmsGqlReturnAssembler.creditOf((List<Map>) n.responses)] }
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
                List<Map> nodes = OmsGqlOrderGrainAssembler.edgeNodes(connection)
                requireEcho(nodes, key, chunk, root)
                all.addAll(nodes)
                cursor = OmsGqlOrderPageSource.nextCursor(connection, seenCursors, root)
                if (cursor == null) break
            }
        }
        return all
    }


    /**
     * DAR-OPS-029 S99-026: rows that carry the filter field and name none of the asked ids mean the OMS ignored the filter, and
     * the asked ids would read as missing. A partial answer is an answer; rows that do not carry the field cannot be judged.
     * The field is the key's last term (NETSUITE_PRODUCT_ID_KEY filters on a type, then productId).
     */
    private static void requireEcho(List<Map> nodes, String key, List<String> asked, String root) {
        String field = key.tokenize(' ').last()
        List<Map> carried = nodes.findAll { it.containsKey(field) }
        if (!carried) return
        Set<String> wanted = asked.collect { it as String } as Set<String>
        if (!carried.any { wanted.contains(it.get(field)?.toString()) }) {
            throw new OmsGqlException("FILTER_IGNORED", "${root} by ${field} did not honour its id filter: no row names an asked id".toString())
        }
    }

    private static String netsuiteOrderId(Map order) {
        return ((order?.identifications ?: []) as List<Map>).find { it?.orderIdentificationTypeId == "NETSUITE_ORDER_ID" }?.idValue as String
    }

    private static String iso(Long millis) {
        return Instant.ofEpochMilli(millis).toString()
    }
}
