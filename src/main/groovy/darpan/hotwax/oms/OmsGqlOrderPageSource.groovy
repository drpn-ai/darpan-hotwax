package darpan.hotwax.oms

import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

/**
 * The GraphQL page source for the ORDER-grain OMS extract (DAR-BE-064 spec D1-D4).
 *
 * Orders pages are buffered; every ASSOC_BATCH_ORDER_IDS orders (and at the end of a slice) one (paged)
 * orderItemAssocs EXCHANGE query covers the buffer, then the assembler, then the REST shaper — and the
 * bundle goes to REST's pageConsumer. A multi-day window runs as parallel day slices (DAR-BE-116: one call
 * at a time, ~350 ms each, was the whole cost of a month-to-date audit read). Nothing about counting,
 * manifests, filters, projection or writing is re-implemented here.
 *
 * Non-sales orders are filtered SERVER-side (orderTypeId:SALES_ORDER in the search string), so
 * excludedNonSalesOrderCount reads 0 where REST counts them client-side; pagination records
 * orderTypeFilteredServerSide so the diff gate compares like with like.
 */
class OmsGqlOrderPageSource {

    /** DAR-BE-116: order ids per exchange pass. The pass is charged on its `first` slots, not on the ids. */
    static final int ASSOC_BATCH_ORDER_IDS = 1000
    static final long SLICE_MILLIS = 86400000L
    /** Parallel day slices per extract. */
    static final int CONCURRENCY = 4

    /** options.lookupKeys (DAR-BE-119): read these orders by orderId instead of the window. */
    static Closure fetcher(OmsGqlClient client, Map options = [:]) {
        return { Map ctx -> fetchAll(client, ctx + (options ?: [:])) }
    }

    static Map fetchAll(OmsGqlClient client, Map ctx) {
        Map extractOptions = (Map) (ctx.extractOptions ?: [:])
        Map pagination = [strategy: "GRAPHQL_CURSOR", pageCount: 0, totalFetched: 0, orderTypeFilteredServerSide: true,
                          paymentPreferenceSlotsFullCount: 0, exchangeAssocCalls: 0, lookupCalls: 0]
        Closure failed = { String message ->
            [errors: [message], statusCode: null, attemptCount: client.callCount, pagination: pagination, transport: "GRAPHQL"]
        }

        // Refuse what this transport cannot honour; silently ignoring any of these widens the extract.
        List<String> keys = (List<String>) ctx.lookupKeys
        if (!keys && (ctx.fromMillis == null || ctx.thruMillis == null)) {
            return failed("The OMS GraphQL order extract requires a date window; status-only extracts stay on REST.")
        }
        if (keys && !(ctx.lookupKeyField in [null, "orderId", "omsOrderId"])) {
            return failed("The OMS GraphQL order extract reads by orderId only, not '${ctx.lookupKeyField}'.")
        }
        if (extractOptions.orderStatusIds) {
            return failed("orderStatusIds is not supported by the OMS GraphQL order extract; keep this rule set on REST.")
        }
        String windowField = (extractOptions.windowFieldName as String)?.trim()
        if (windowField && windowField != "orderDate") {
            return failed("The OMS GraphQL order extract windows on orderDate only, not '${windowField}'.")
        }

        // DAR-BE-116: ORDER_LINE_UNIT grain (the order-items extract) reads the order header and its ship groups,
        // then the items in batches, and hands REST's flattener the same
        // order shape REST does. Filters there are on UNIT fields, checked against what a unit carries.
        boolean unitGrain = "ORDER_LINE_UNIT".equalsIgnoreCase((extractOptions.emitGrain as String)?.trim() ?: "")
        Object exclusionFlag = extractOptions.applyExchangeExclusion
        boolean exchangePass = !(exclusionFlag == false || (exclusionFlag as String)?.trim()?.toLowerCase() in ["false", "n"])
        Map plan
        Set<String> required = OmsGqlQueries.requiredFields((Set<String>) ctx.keepFieldSet, (List<Map>) ctx.excludeRules)
        try {
            if (unitGrain) {
                List<String> unknown = unitFilterFieldsNotCarried((List<Map>) ctx.excludeRules)
                if (unknown) {
                    return failed("The OMS GraphQL order-items extract cannot filter on ${unknown}: its units do not carry " +
                            "them (the shared orders read selects neither salesChannelEnumId nor netsuiteOrderId, to keep the page " +
                            "lean). Keep this rule set on REST.")
                }
                // DAR-BE-129: the shared orders read's document, so a unit read and an order read share one read.
                plan = OmsGqlQueries.sharedOrdersPlan()
            } else {
                plan = OmsGqlQueries.orderGrainPagePlan(required)
            }
        } catch (IllegalArgumentException e) {
            return failed(e.message)
        }
        pagination.pageSize = plan.pageSize
        Closure consumer = (Closure) ctx.pageConsumer
        Closure pageConsumer = { Map bundle -> synchronized (pagination) { consumer.call(bundle) } }
        Map shared = [plan: plan, pageSize: new AtomicInteger((int) plan.pageSize), pages: new AtomicInteger(0),
                      maxPages: OmsRestSourceSupport.MAX_ORDERS_PAGE_COUNT, pagination: pagination, pageConsumer: pageConsumer,
                      ctx: ctx, extractOptions: extractOptions, unitGrain: unitGrain, exchangePass: exchangePass]
        try {
            if (keys) {
                // DAR-BE-129: a conclusion lookup's 500 ids ran as five chunks in series (3.7 min on the Oct 1-25 run).
                runEach(keys.collate(OmsGqlQueries.LOOKUP_KEYS_CHUNK), CONCURRENCY) { List<String> chunk ->
                    fetchSlice(client, shared, OmsGqlQueries.keysFilter("orderId", chunk))
                }
            } else {
                List<List<Long>> slices = daySlices((Long) ctx.fromMillis, (Long) ctx.thruMillis)
                pagination.sliceCount = slices.size()
                if (unitGrain && client.realEndpoint) {
                    readUnitsShared(client, shared, slices)
                } else if (client.realEndpoint && OmsGqlQueries.sharedOrdersCover(required)) {
                    shapeShared(ordersKey(client, slices), ordersProducer(client, shared, slices), shared)
                } else {
                    runSlices(slices, CONCURRENCY) { long from, long thru -> fetchSlice(client, shared, window(from, thru)) }
                }
            }
        } catch (IllegalArgumentException e) {
            return failed(e.message)
        } catch (OmsGqlException e) {
            // Never an empty extract: THROTTLED in particular arrives as HTTP 200 with no data.
            return failed("OMS GraphQL extract failed [${e.code}]: ${e.message}".toString())
        }
        if (!pagination.sharedRead) {
            pagination.pageCount = ((AtomicInteger) shared.pages).get()
            pagination.pageSize = ((AtomicInteger) shared.pageSize).get()
        }
        return [errors: [], statusCode: 200, attemptCount: client.callCount, pagination: pagination, transport: "GRAPHQL"]
    }

    /** Batch of raw records a shared read hands each caller's own shaper at a time. */
    static final int SHARED_SHAPE_BATCH = 500

    /**
     * DAR-BE-129: the window's orders through OmsGqlSharedRead, in the shared orders document, with the exchange pass
     * always run. The order checks and the order-line checks read a month's orders once between them (the 2026-10-26
     * Oct 1-25 audit read them twice: OmsOrderGrain 1.20M and OmsUnitOrders 1.29M of 5.4M cost).
     */
    private static String ordersKey(OmsGqlClient client, List<List<Long>> slices) {
        return "orders|${client.readScope}|${slices.first()[0]}|${slices.last()[1]}".toString()
    }

    private static Closure ordersProducer(OmsGqlClient client, Map shared, List<List<Long>> slices) {
        return { Closure emit ->
            Map plan = OmsGqlQueries.sharedOrdersPlan()
            Map readPagination = new LinkedHashMap((Map) shared.pagination)
            Map producerShared = new LinkedHashMap(shared) + [plan: plan, pageSize: new AtomicInteger((int) plan.pageSize),
                    pages: new AtomicInteger(0), pagination: readPagination, unitGrain: false, exchangePass: true,
                    sink: { List<Map> assembled -> assembled.each { emit.call(it) } }]
            runSlices(slices, CONCURRENCY) { long from, long thru -> fetchSlice(client, producerShared, window(from, thru)) }
            readPagination.pageCount = ((AtomicInteger) producerShared.pages).get()
            readPagination.pageSize = ((AtomicInteger) producerShared.pageSize).get()
            return [pagination: readPagination]
        }
    }

    /**
     * DAR-BE-116: the unit read through OmsGqlSharedRead. The raw record is the assembled order (header, ship groups,
     * items); its orders come from the shared orders read and only the items are read here. DAR-BE-129: it follows the
     * orders read, reading each batch's items as its orders arrive (waiting for the whole month first added the items
     * read's 14 minutes after it on 2026-10-26). Each caller then shapes the file with its OWN filters.
     */
    private static void readUnitsShared(OmsGqlClient client, Map shared, List<List<Long>> slices) {
        String key = "units|${client.readScope}|${slices.first()[0]}|${slices.last()[1]}".toString()
        shapeShared(key, { Closure emit ->
            Map itemsPagination = [lookupCalls: 0]
            // CONCURRENCY batches in flight at a time: a month is ~92,000 orders, too many to hold at once.
            java.util.concurrent.Semaphore slots = new java.util.concurrent.Semaphore(CONCURRENCY)
            ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY)
            List<Future> futures = []
            Closure submit = { List<Map> page ->
                slots.acquire()
                futures.add(pool.submit({ ->
                    try {
                        attachItemsToShipGroups(client, page, page.collect { it.orderId as String }, itemsPagination)
                        page.each { emit.call(it) }
                    } finally {
                        slots.release()
                    }
                } as Callable))
            }
            try {
                List<Map> batch = []
                Map orders = OmsGqlSharedRead.follow(ordersKey(client, slices), ordersProducer(client, shared, slices)) { Map order ->
                    batch.add(order)
                    if (batch.size() >= ASSOC_BATCH_ORDER_IDS) { submit.call(batch); batch = [] }
                }
                if (batch) submit.call(batch)
                for (Future f : futures) {
                    try {
                        f.get()
                    } catch (ExecutionException e) {
                        throw (e.cause instanceof RuntimeException) ? (RuntimeException) e.cause : new RuntimeException(e.cause)
                    }
                }
                Map pagination = new LinkedHashMap((Map) ((Map) orders.stats).pagination)
                pagination.lookupCalls = (pagination.lookupCalls ?: 0) + itemsPagination.lookupCalls
                return [pagination: pagination]
            } finally {
                pool.shutdownNow()
            }
        }, shared)
    }

    /**
     * A shared read shaped by this caller's own filters and projection, SHARED_SHAPE_BATCH records at a time. DAR-BE-129:
     * it follows the read, so its records reach the extract's page consumer (and the run's progress heartbeat) while the
     * read runs, not all at its end.
     */
    private static void shapeShared(String key, Closure producer, Map shared) {
        Map pagination = (Map) shared.pagination
        Map ctx = (Map) shared.ctx
        int fetched = 0
        int slotsFull = 0
        List<Map> batch = []
        Closure shapeBatch = {
            Map bundle = OmsRestSourceSupport.shapePageRecords(batch, (Map) shared.extractOptions,
                    (List<Map<String, Object>>) ctx.excludeRules, (Set<String>) ctx.keepFieldSet) + [rawCount: batch.size()]
            fetched += batch.size()
            slotsFull += batch.count { it.paymentPreferenceSlotsFull == true }
            ((Closure) shared.pageConsumer).call(bundle)
            batch = []
        }
        Map read = OmsGqlSharedRead.follow(key, producer) { Map record ->
            // The shared read always grafts the exchange assocs; a caller without the exchange exclusion never asked.
            if (shared.exchangePass == false) record.remove("orderItemAssocs")
            batch.add(record)
            if (batch.size() >= SHARED_SHAPE_BATCH) shapeBatch.call()
        }
        if (batch) shapeBatch.call()
        pagination.putAll((Map) ((Map) read.stats).pagination)
        pagination.sharedRead = true
        pagination.totalFetched = fetched
        pagination.paymentPreferenceSlotsFullCount = slotsFull
    }

    /**
     * DAR-BE-116: a window longer than a day is cut into day slices fetched in parallel (one shared bucket,
     * OmsGqlThrottleGovernor.forUrl). Each slice is a half-open [from, thru) on orderDate, so slices meet
     * exactly and an order lands in one slice only.
     */
    static List<List<Long>> daySlices(long fromMillis, long thruMillis) {
        List<List<Long>> slices = []
        long cursor = fromMillis
        while (cursor < thruMillis) {
            long next = Math.min(thruMillis, cursor + SLICE_MILLIS)
            slices.add([cursor, next])
            cursor = next
        }
        return slices ?: [[fromMillis, thruMillis]]
    }

    /** Runs `work(from, thru)` for every slice, as runEach does. Shared by the orders and returns sources. */
    static void runSlices(List<List<Long>> slices, int concurrency, Closure work) {
        runEach(slices, concurrency) { List<Long> slice -> work.call(slice[0], slice[1]) }
    }

    /**
     * Runs `work(item)` for every item, `concurrency` at a time; the first failure cancels the rest and is rethrown as
     * itself, so an OmsGqlException still fails the extract closed.
     */
    static void runEach(List items, int concurrency, Closure work) {
        int threads = Math.min(items.size(), Math.max(1, concurrency))
        if (threads <= 1) {
            items.each { work.call(it) }
            return
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads)
        try {
            List<Future> futures = items.collect { Object item -> pool.submit({ -> work.call(item) } as Callable) }
            for (Future f : futures) {
                try {
                    f.get()
                } catch (ExecutionException e) {
                    futures.each { it.cancel(true) }
                    throw (e.cause instanceof RuntimeException) ? (RuntimeException) e.cause : new RuntimeException(e.cause)
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    private static String window(long fromMillis, long thruMillis) {
        return OmsGqlQueries.windowFilter(iso(fromMillis), iso(thruMillis))
    }

    /** One slice's orders, flushed every ASSOC_BATCH_ORDER_IDS orders through one exchange pass. */
    private static void fetchSlice(OmsGqlClient client, Map shared, String filter) {
        Map plan = (Map) shared.plan
        pageSlice(client, shared, (String) plan.document, (int) plan.reservation, "orders", filter, ASSOC_BATCH_ORDER_IDS,
                { List<Map> nodes -> nodes }, { List<Map> batch -> flush(client, shared, batch) })
    }

    /**
     * One slice's cursor loop, for the orders and returns sources: `keep(nodes)` answers a page's nodes to buffer, and
     * `flush(buffered)` takes them every `batch` and at the end. A server capping one query below the page's sizing has the
     * page halved and asked again; the shared page ceiling refuses a truncated extract.
     */
    static void pageSlice(OmsGqlClient client, Map shared, String document, int reservation, String root, String filter,
                          int batch, Closure<List<Map>> keep, Closure flush) {
        AtomicInteger pageSize = (AtomicInteger) shared.pageSize
        AtomicInteger pages = (AtomicInteger) shared.pages
        int maxPages = (int) shared.maxPages
        List<Map> buffered = []
        String cursor = null
        Set<String> seenCursors = [] as Set
        while (true) {
            if (Thread.currentThread().isInterrupted()) throw new OmsGqlException("CANCELLED", "another slice failed")
            if (pages.get() >= maxPages) {
                throw new OmsGqlException("MALFORMED", "hit the page ceiling of ${maxPages} with more pages still reported; " +
                        "refusing to return a truncated extract")
            }
            int size = pageSize.get()
            Map page
            try {
                page = client.execute(document, OmsGqlQueries.pageVariables(filter, size, cursor), reservation)
            } catch (OmsGqlException e) {
                if (e.code != "COST_EXCEEDED" || size <= 1) throw e
                pageSize.compareAndSet(size, Math.max(1, size.intdiv(2)))
                continue
            }
            Map connection = requireConnection(page, root)
            pages.incrementAndGet()
            buffered.addAll(keep.call(OmsGqlOrderGrainAssembler.edgeNodes(connection)))
            cursor = nextCursor(connection, seenCursors, root)
            if (buffered && (cursor == null || buffered.size() >= batch)) {
                flush.call(buffered)
                buffered = []
            }
            if (cursor == null) break
        }
    }

    private static void flush(OmsGqlClient client, Map shared, List<Map> nodes) {
        Map pagination = (Map) shared.pagination
        Map ctx = (Map) shared.ctx
        List<String> ids = nodes.collect { it.orderId as String }.findAll { it }
        List<Map> assocs = shared.exchangePass == false ? [] : fetchExchangeAssocs(client, ids, pagination)
        Map plan = (Map) shared.plan
        List<Map> assembled = OmsGqlOrderGrainAssembler.assemble(nodes, assocs,
                (plan.paymentSlots ?: OmsGqlQueries.PAYMENT_PREF_SLOTS) as int, plan.paymentRefNums == true)
        if (shared.unitGrain == true) attachItemsToShipGroups(client, assembled, ids, pagination)
        if (shared.sink != null) {
            // A shared read: the raw records go to the spill file, and each caller shapes them itself.
            ((Closure) shared.sink).call(assembled)
            return
        }
        Map bundle = OmsRestSourceSupport.shapePageRecords(assembled, (Map) shared.extractOptions,
                (List<Map<String, Object>>) ctx.excludeRules, (Set<String>) ctx.keepFieldSet) +
                [rawCount: assembled.size()]
        synchronized (pagination) {
            pagination.totalFetched = (pagination.totalFetched as int) + assembled.size()
            pagination.paymentPreferenceSlotsFullCount = (pagination.paymentPreferenceSlotsFullCount as int) +
                    assembled.count { it.paymentPreferenceSlotsFull == true }
        }
        ((Closure) shared.pageConsumer).call(bundle)
    }

    /** What a unit record carries: OmsOrderLineUnitSupport's unit fields plus the order header the filter view overlays. */
    static final Set<String> UNIT_FILTER_FIELDS = (["shopifyOrderId", "shopifyLineId", "unitOrdinal", "omsOrderId", "omsOrderName",
            "orderItemSeqId", "productId", "statusId", "unitState", "stateOrdinal", "omsOrderStatusId",
            "facilityId", "orderId", "orderName", "externalId", "orderTypeId", "orderDate"]) as Set<String>

    private static List<String> unitFilterFieldsNotCarried(List<Map> rules) {
        return (rules ?: []).collect { Map r -> ((r?.fieldExpression ?: "") as String).trim().replaceFirst(/^\$\.records\[\*\]\./, "") }
                .findAll { String f -> f && !UNIT_FILTER_FIELDS.contains(f) }.unique().sort()
    }

    /**
     * REST's order shape for the flattener: each item under the ship group it belongs to. An item naming a ship
     * group the order did not return gets one of its own with no facility, so the unit is kept, not dropped.
     * DAR-UI-081: each item also carries its SKU (its product's internalName, REST's sku) and its ship group's facility
     * name, each read by id once per page as the returns read does (nesting them is charged per order), for the audit
     * finding.
     */
    private static void attachItemsToShipGroups(OmsGqlClient client, List<Map> orders, List<String> orderIds, Map pagination) {
        List<Map> items = OmsGqlReturnsPageSource.fetchByIds(client, OmsGqlQueries.unitOrderItemsDocument(), "orderItems", "orderId",
                orderIds, OmsGqlQueries.ORDER_ITEMS_ID_CHUNK, OmsGqlQueries.ORDER_ITEMS_PAGE_SIZE, OmsGqlQueries.UNIT_ITEMS_RESERVATION, pagination)
        Map<String, Object> skus = OmsGqlReturnsPageSource.productNames(client, items.collect { it.productId as String }, pagination)
        List<String> facilityIds = orders.collectMany { Map o -> ((o.shipGroups instanceof List) ? (List<Map>) o.shipGroups : [])
                .collect { it?.facilityId as String } }
        Map<String, Object> facilityNames = OmsGqlLookupCache.lookup(client, OmsGqlLookupCache.FACILITY_NAME, facilityIds) { List<String> ids ->
            OmsGqlReturnsPageSource.fetchByIds(client, OmsGqlQueries.facilityNamesDocument(), "facilities", "facilityId", ids,
                    OmsGqlQueries.FACILITIES_ID_CHUNK, OmsGqlQueries.FACILITIES_ID_CHUNK, OmsGqlQueries.FACILITIES_RESERVATION, pagination)
                    .findAll { it.facilityName }.collectEntries { Map n -> [(n.facilityId as String): n.facilityName as String] }
        }
        Map<String, List<Map>> byOrder = items.groupBy { it.orderId as String }
        for (Map order : orders) {
            List<Map> groups = ((order.shipGroups instanceof List) ? (List<Map>) order.shipGroups : []).collect { new LinkedHashMap(it) as Map }
            Map<String, Map> groupBySeq = groups.collectEntries { [(it.shipGroupSeqId as String): it] }
            for (Map item : (byOrder[order.orderId as String] ?: [])) {
                String seq = item.shipGroupSeqId as String
                Map group = groupBySeq[seq]
                if (group == null) {
                    group = [shipGroupSeqId: seq, facilityId: null]
                    groups.add(group)
                    groupBySeq[seq] = group
                }
                Map withSku = new LinkedHashMap(item)
                // get, not [ ]: a null String key makes Groovy's subscript a property lookup, which throws.
                String sku = skus.get(item.productId) as String
                if (sku) withSku.sku = sku
                String facilityName = facilityNames.get(group.facilityId) as String
                if (facilityName) withSku.facilityName = facilityName
                ((List) group.computeIfAbsent("items", { [] })).add(withSku)
            }
            order.shipGroups = groups
        }
    }

    static List<Map> fetchExchangeAssocs(OmsGqlClient client, List<String> orderIds, Map pagination) {
        List<Map> all = []
        String cursor = null
        Set<String> seenCursors = [] as Set
        while (true) {
            Map page = client.execute(OmsGqlQueries.exchangeAssocsDocument(),
                    OmsGqlQueries.exchangeAssocsVariables(orderIds, cursor), OmsGqlQueries.ASSOC_RESERVATION)
            synchronized (pagination) { pagination.exchangeAssocCalls = (pagination.exchangeAssocCalls as int) + 1 }
            Map connection = requireConnection(page, "orderItemAssocs")
            all.addAll(OmsGqlOrderGrainAssembler.edgeNodes(connection))
            cursor = nextCursor(connection, seenCursors, "orderItemAssocs")
            if (cursor == null) break
        }
        return all
    }

    /**
     * The connection, or MALFORMED. A 200 whose body lacks it (data:null, a proxy's {}, orders:null)
     * must never read as an empty page — that is a clean, empty, wrong extract (review I2).
     */
    static Map requireConnection(Map page, String root) {
        Object data = page?.data
        Object connection = (data instanceof Map) ? ((Map) data).get(root) : null
        if (!(connection instanceof Map) || !(((Map) connection).get("edges") instanceof List)) {
            throw new OmsGqlException("MALFORMED", "response carried no ${root} connection with edges: ${String.valueOf(data).take(200)}")
        }
        return (Map) connection
    }

    /** The next cursor, null when done; MALFORMED on "more" without a cursor, or a cursor seen before. */
    static String nextCursor(Map connection, Set<String> seenCursors, String root) {
        Map pageInfo = (Map) (connection.pageInfo ?: [:])
        if (pageInfo.hasNextPage != true) return null
        String cursor = pageInfo.endCursor as String
        if (!cursor) throw new OmsGqlException("MALFORMED", "${root} reported hasNextPage with no endCursor; refusing to truncate")
        if (!seenCursors.add(cursor)) throw new OmsGqlException("MALFORMED", "${root} repeated cursor ${cursor}; refusing to loop")
        return cursor
    }


    private static String iso(Long millis) {
        return Instant.ofEpochMilli(millis).toString()
    }
}
