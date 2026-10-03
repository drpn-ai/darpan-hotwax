package darpan.hotwax.oms

import java.time.Instant

/**
 * The GraphQL page source for the ORDER-grain OMS extract (DAR-BE-064 spec D1-D4).
 *
 * Per orders page: one order-grain query, then one (paged) orderItemAssocs EXCHANGE query for that
 * page's ids, then the assembler, then the REST shaper — and the bundle goes to REST's pageConsumer.
 * Nothing about counting, manifests, filters, projection or writing is re-implemented here.
 *
 * Non-sales orders are filtered SERVER-side (orderTypeId:SALES_ORDER in the search string), so
 * excludedNonSalesOrderCount reads 0 where REST counts them client-side; pagination records
 * orderTypeFilteredServerSide so the diff gate compares like with like.
 */
class OmsGqlOrderPageSource {

    static Closure fetcher(OmsGqlClient client) {
        return { Map ctx -> fetchAll(client, ctx) }
    }

    static Map fetchAll(OmsGqlClient client, Map ctx) {
        Map extractOptions = (Map) (ctx.extractOptions ?: [:])
        Map pagination = [strategy: "GRAPHQL_CURSOR", pageCount: 0, totalFetched: 0, orderTypeFilteredServerSide: true,
                          paymentPreferenceSlotsFullCount: 0, exchangeAssocCalls: 0]
        Closure failed = { String message ->
            [errors: [message], statusCode: null, attemptCount: client.callCount, pagination: pagination, transport: "GRAPHQL"]
        }

        // Refuse what this transport cannot honour; silently ignoring any of these widens the extract.
        if (ctx.fromMillis == null || ctx.thruMillis == null) {
            return failed("The OMS GraphQL order extract requires a date window; status-only extracts stay on REST.")
        }
        if (extractOptions.orderStatusIds) {
            return failed("orderStatusIds is not supported by the OMS GraphQL order extract; keep this rule set on REST.")
        }
        String windowField = (extractOptions.windowFieldName as String)?.trim()
        if (windowField && windowField != "orderDate") {
            return failed("The OMS GraphQL order extract windows on orderDate only, not '${windowField}'.")
        }

        Map plan
        try {
            plan = OmsGqlQueries.orderGrainPagePlan(
                    OmsGqlQueries.requiredFields((Set<String>) ctx.keepFieldSet, (List<Map>) ctx.excludeRules))
        } catch (IllegalArgumentException e) {
            return failed(e.message)
        }
        pagination.pageSize = plan.pageSize

        String filter = OmsGqlQueries.windowFilter(iso((Long) ctx.fromMillis), iso((Long) ctx.thruMillis))
        Closure pageConsumer = (Closure) ctx.pageConsumer
        int maxPages = maxPageCount((Map) ctx.config)
        try {
            String cursor = null
            Set<String> seenCursors = [] as Set
            while (true) {
                if ((pagination.pageCount as int) >= maxPages) {
                    throw new OmsGqlException("MALFORMED", "hit the page ceiling of ${maxPages} with more pages still " +
                            "reported (maxOrdersPageCount); refusing to return a truncated extract")
                }
                Map page = client.execute((String) plan.document,
                        OmsGqlQueries.pageVariables(filter, (int) plan.pageSize, cursor), (int) plan.reservation)
                Map connection = requireConnection(page, "orders")
                List<Map> nodes = OmsGqlOrderGrainAssembler.edgeNodes(connection)
                pagination.pageCount = (pagination.pageCount as int) + 1

                if (nodes) {
                    List<String> ids = nodes.collect { it.orderId as String }.findAll { it }
                    List<Map> assocs = fetchExchangeAssocs(client, ids, pagination)
                    List<Map> assembled = OmsGqlOrderGrainAssembler.assemble(nodes, assocs)
                    pagination.totalFetched = (pagination.totalFetched as int) + assembled.size()
                    pagination.paymentPreferenceSlotsFullCount = (pagination.paymentPreferenceSlotsFullCount as int) +
                            assembled.count { it.paymentPreferenceSlotsFull == true }
                    Map bundle = OmsRestSourceSupport.shapePageRecords(assembled, extractOptions,
                            (List<Map<String, Object>>) ctx.excludeRules, (Set<String>) ctx.keepFieldSet) +
                            [rawCount: assembled.size()]
                    pageConsumer.call(bundle)
                }

                cursor = nextCursor(connection, seenCursors, "orders")
                if (cursor == null) break
            }
        } catch (OmsGqlException e) {
            // Never an empty extract: THROTTLED in particular arrives as HTTP 200 with no data.
            return failed("OMS GraphQL extract failed [${e.code}]: ${e.message}".toString())
        }
        return [errors: [], statusCode: 200, attemptCount: client.callCount, pagination: pagination, transport: "GRAPHQL"]
    }

    private static List<Map> fetchExchangeAssocs(OmsGqlClient client, List<String> orderIds, Map pagination) {
        List<Map> all = []
        String cursor = null
        Set<String> seenCursors = [] as Set
        while (true) {
            Map page = client.execute(OmsGqlQueries.exchangeAssocsDocument(),
                    OmsGqlQueries.exchangeAssocsVariables(orderIds, cursor), OmsGqlQueries.ASSOC_RESERVATION)
            pagination.exchangeAssocCalls = (pagination.exchangeAssocCalls as int) + 1
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

    private static int maxPageCount(Map config) {
        Object raw = config?.maxOrdersPageCount
        try {
            int value = raw == null ? OmsRestSourceSupport.MAX_ORDERS_PAGE_COUNT : (raw.toString().trim() as int)
            return Math.max(1, value)
        } catch (Exception ignored) {
            return OmsRestSourceSupport.MAX_ORDERS_PAGE_COUNT
        }
    }

    private static String iso(Long millis) {
        return Instant.ofEpochMilli(millis).toString()
    }
}
