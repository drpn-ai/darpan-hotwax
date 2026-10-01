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
        try {
            String cursor = null
            while (true) {
                Map page = client.execute((String) plan.document,
                        OmsGqlQueries.pageVariables(filter, (int) plan.pageSize, cursor), (int) plan.reservation)
                Map connection = (Map) (((Map) (page.data ?: [:])).orders ?: [:])
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

                Map pageInfo = (Map) (connection.pageInfo ?: [:])
                cursor = pageInfo.endCursor as String
                if (pageInfo.hasNextPage != true || !cursor) break
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
        while (true) {
            Map page = client.execute(OmsGqlQueries.exchangeAssocsDocument(),
                    OmsGqlQueries.exchangeAssocsVariables(orderIds, cursor), OmsGqlQueries.ASSOC_RESERVATION)
            pagination.exchangeAssocCalls = (pagination.exchangeAssocCalls as int) + 1
            Map connection = (Map) (((Map) (page.data ?: [:])).orderItemAssocs ?: [:])
            all.addAll(OmsGqlOrderGrainAssembler.edgeNodes(connection))
            Map pageInfo = (Map) (connection.pageInfo ?: [:])
            cursor = pageInfo.endCursor as String
            if (pageInfo.hasNextPage != true || !cursor) break
        }
        return all
    }

    private static String iso(Long millis) {
        return Instant.ofEpochMilli(millis).toString()
    }
}
