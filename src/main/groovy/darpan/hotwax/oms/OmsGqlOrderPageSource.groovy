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
        try {
            if (unitGrain) {
                List<String> unknown = unitFilterFieldsNotCarried((List<Map>) ctx.excludeRules)
                if (unknown) {
                    return failed("The OMS GraphQL order-items extract cannot filter on ${unknown}: its units do not carry " +
                            "them (salesChannelEnumId is absent from gorjana's Order type, SDL 2026-10-23; netsuiteOrderId is not " +
                            "read, to keep the page lean). Keep this rule set on REST.")
                }
                plan = [document: OmsGqlQueries.unitOrdersDocument(), pageSize: OmsGqlQueries.UNIT_ORDERS_PAGE_SIZE,
                        reservation: OmsGqlQueries.UNIT_ORDERS_RESERVATION]
            } else {
                plan = OmsGqlQueries.orderGrainPagePlan(
                        OmsGqlQueries.requiredFields((Set<String>) ctx.keepFieldSet, (List<Map>) ctx.excludeRules))
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
                keys.collate(OmsGqlQueries.LOOKUP_KEYS_CHUNK).each { List<String> chunk ->
                    fetchSlice(client, shared, OmsGqlQueries.keysFilter("orderId", chunk))
                }
            } else {
                List<List<Long>> slices = daySlices((Long) ctx.fromMillis, (Long) ctx.thruMillis)
                pagination.sliceCount = slices.size()
                if (unitGrain && client.realEndpoint) {
                    readUnitsShared(client, shared, slices, (Map) ctx.config)
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
     * DAR-BE-116: the unit read through OmsGqlSharedRead. The raw record is the assembled order (header, ship groups,
     * items), so it depends only on the endpoint, the window and whether the exchange pass ran; each caller then shapes
     * the file with its OWN filters. R1/R3 and R15 read the month's order items once between them.
     */
    private static void readUnitsShared(OmsGqlClient client, Map shared, List<List<Long>> slices, Map config) {
        Map pagination = (Map) shared.pagination
        Map ctx = (Map) shared.ctx
        String key = "units|${client.readScope}|${slices.first()[0]}|${slices.last()[1]}|exchange=${shared.exchangePass}".toString()
        Map read = OmsGqlSharedRead.read(key) { Closure emit ->
            Map readPagination = new LinkedHashMap(pagination)
            Map producerShared = new LinkedHashMap(shared) + [pagination: readPagination,
                    sink: { List<Map> assembled -> assembled.each { emit.call(it) } }]
            runSlices(slices, CONCURRENCY) { long from, long thru -> fetchSlice(client, producerShared, window(from, thru)) }
            readPagination.pageCount = ((AtomicInteger) producerShared.pages).get()
            readPagination.pageSize = ((AtomicInteger) producerShared.pageSize).get()
            return [pagination: readPagination]
        }
        pagination.putAll((Map) ((Map) read.stats).pagination)
        pagination.sharedRead = true
        pagination.totalFetched = 0
        groovy.json.JsonSlurper slurper = new groovy.json.JsonSlurper()
        List<Map> batch = []
        Closure shapeBatch = {
            Map bundle = OmsRestSourceSupport.shapePageRecords(batch, (Map) shared.extractOptions,
                    (List<Map<String, Object>>) ctx.excludeRules, (Set<String>) ctx.keepFieldSet) + [rawCount: batch.size()]
            pagination.totalFetched = (pagination.totalFetched as int) + batch.size()
            ((Closure) shared.pageConsumer).call(bundle)
            batch = []
        }
        ((File) read.file).eachLine("UTF-8") { String line ->
            if (!line) return
            batch.add((Map) slurper.parseText(line))
            if (batch.size() >= SHARED_SHAPE_BATCH) shapeBatch.call()
        }
        if (batch) shapeBatch.call()
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

    /**
     * Runs `work(from, thru)` for every slice, `concurrency` at a time; the first failure cancels the rest
     * and is rethrown as itself, so an OmsGqlException still fails the extract closed. Shared by the
     * orders and returns sources.
     */
    static void runSlices(List<List<Long>> slices, int concurrency, Closure work) {
        int threads = Math.min(slices.size(), Math.max(1, concurrency))
        if (threads <= 1) {
            slices.each { List<Long> slice -> work.call(slice[0], slice[1]) }
            return
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads)
        try {
            List<Future> futures = slices.collect { List<Long> slice -> pool.submit({ -> work.call(slice[0], slice[1]) } as Callable) }
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
        List<Map> assembled = OmsGqlOrderGrainAssembler.assemble(nodes, assocs)
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
     */
    private static void attachItemsToShipGroups(OmsGqlClient client, List<Map> orders, List<String> orderIds, Map pagination) {
        List<Map> items = OmsGqlReturnsPageSource.fetchByIds(client, OmsGqlQueries.unitOrderItemsDocument(), "orderItems", "orderId",
                orderIds, OmsGqlQueries.ORDER_ITEMS_ID_CHUNK, OmsGqlQueries.ORDER_ITEMS_PAGE_SIZE, OmsGqlQueries.UNIT_ITEMS_RESERVATION, pagination)
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
                ((List) group.computeIfAbsent("items", { [] })).add(new LinkedHashMap(item))
            }
            order.shipGroups = groups
        }
    }

    private static List<Map> fetchExchangeAssocs(OmsGqlClient client, List<String> orderIds, Map pagination) {
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
