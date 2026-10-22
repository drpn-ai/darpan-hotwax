package darpan.hotwax.oms

import java.util.regex.Pattern

import static darpan.common.ValueSupport.normalize

/**
 * DAR-BE-111. What HotWax says of each order NetSuite never shipped or billed: its statusId, by OMS order id (the
 * NetSuite HC Order Id). GraphQL only, flat and id-keyed: orders(orderId:a,b,...) in chunks, every answer echo-checked
 * against what was asked. RAILS 2026-10-22: 350 ids in 7 calls, 3.5s; gorjana 1,227 in 25 calls, 14s.
 *
 * A failed call fails the whole lookup (ok=false, nothing classified): an order is never called missing because
 * HotWax could not be asked.
 */
class OmsOrderStatusLookupSupport {

    static final int ID_CHUNK = OmsGqlQueries.ORDER_STATUSES_ID_CHUNK
    private static final Pattern ASKABLE_ID = Pattern.compile('^[A-Za-z0-9_\\-]+$')

    private static final Closure DEFAULT_CLIENT_FACTORY = { Map config -> new OmsGqlClient(config) }
    private static Closure clientFactory = DEFAULT_CLIENT_FACTORY

    /** Test seam: config -> OmsGqlClient. */
    static void setClientFactory(Closure factory) {
        clientFactory = factory ?: DEFAULT_CLIENT_FACTORY
    }

    static void resetClientFactory() {
        clientFactory = DEFAULT_CLIENT_FACTORY
    }

    /** Returns [ok, statusByOrderId: orderId -> statusId, missingIds, errors]. */
    static Map<String, Object> lookupOrderStatuses(Object rawConfig, Collection orderIds) {
        List<String> ids = (orderIds ?: []).collect { normalize(it) }.findAll { it }.unique()
        if (!ids) return [ok: true, statusByOrderId: [:], missingIds: [], errors: []]
        if (!ids.every { ASKABLE_ID.matcher(it).matches() }) {
            // The search string is `orderId:a,b`; an id carrying a separator would corrupt it.
            return [ok: false, statusByOrderId: [:], missingIds: [], errors: ["OMS order ids must be plain identifiers."]]
        }
        Map<String, Object> config = OmsRestSourceSupport.toPlainMap(rawConfig)
        if (!config) return [ok: false, statusByOrderId: [:], missingIds: [], errors: ["OMS source config could not be read."]]
        try {
            OmsGqlClient client = (OmsGqlClient) clientFactory.call(config)
            Set<String> requested = new HashSet<String>(ids)
            Map<String, String> statusByOrderId = [:]
            OmsGqlReturnsPageSource.fetchByIds(client, OmsGqlQueries.orderStatusesDocument(), "orders", "orderId", ids,
                    ID_CHUNK, ID_CHUNK, OmsGqlQueries.ORDER_STATUSES_RESERVATION, [lookupCalls: 0])
                    .each { Map n ->
                        String orderId = normalize(n.orderId)
                        if (orderId && requested.contains(orderId)) statusByOrderId.put(orderId, normalize(n.statusId))
                    }
            return [ok: true, statusByOrderId: statusByOrderId, missingIds: ids.findAll { !statusByOrderId.containsKey(it) }, errors: []]
        } catch (Exception e) {
            return [ok: false, statusByOrderId: [:], missingIds: [], errors: ["OMS order status lookup failed: ${e.message}".toString()]]
        }
    }
}
