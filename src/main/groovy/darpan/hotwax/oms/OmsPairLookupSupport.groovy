package darpan.hotwax.oms

import static darpan.common.ValueSupport.normalize

/**
 * The exchange pair verify's point lookup: every OMS order carrying each Shopify externalId, whatever its date or type,
 * with whether it is an exchange order. DAR-BE-129: over GraphQL, `orders(externalId:a,b,...)` in chunks and one
 * EXCHANGE assoc pass, where REST asked once per id (200 calls on the 2026-10-26 Oct 1-25 audit).
 *
 * Echo-checked: a returned order whose externalId was not asked for is dropped, and an answer where none echoes means
 * the filter was not applied, so the whole lookup fails rather than pair against arbitrary orders. A failed call fails
 * the whole lookup: an original is never called missing because HotWax could not be asked.
 */
class OmsPairLookupSupport {

    /** Ids per lookup call (the exchange verify sends up to this many). */
    static final int PAIR_LOOKUP_MAX_IDS = 100

    private static final Closure DEFAULT_CLIENT_FACTORY = { Map config -> new OmsGqlClient(config) }
    private static Closure clientFactory = DEFAULT_CLIENT_FACTORY

    /** Test seam: config -> OmsGqlClient. */
    static void setClientFactory(Closure factory) {
        clientFactory = factory ?: DEFAULT_CLIENT_FACTORY
    }

    /** Returns [ok, ordersByExternalId: externalId -> [order summary], errors]. */
    static Map<String, Object> lookupOrdersByExternalId(Object rawConfig, Collection externalIds) {
        List<String> ids = OmsIdLookup.ids(externalIds)
        if (!ids) return failure("No externalIds provided for OMS pair lookup.")
        if (ids.size() > PAIR_LOOKUP_MAX_IDS) return failure("OMS pair lookup requested ${ids.size()} ids, above the ${PAIR_LOOKUP_MAX_IDS}-id cap.")
        if (!OmsIdLookup.askable(ids)) return failure("Shopify order ids must be plain identifiers.")
        return OmsIdLookup.run(rawConfig, clientFactory, [ordersByExternalId: [:]], "OMS pair lookup") { OmsGqlClient client ->
            Map pagination = [lookupCalls: 0, exchangeAssocCalls: 0]
            Set<String> requested = new HashSet<String>(ids)
            List<Map> echoed = OmsIdLookup.requireEcho(OmsGqlReturnsPageSource.fetchByIds(client, OmsGqlQueries.pairOrdersDocument(),
                    "orders", "externalId", ids, OmsGqlQueries.ORDERS_ID_CHUNK, OmsGqlQueries.ORDERS_ID_CHUNK,
                    OmsGqlQueries.PAIR_ORDERS_RESERVATION, pagination), requested, "externalId", "orders")
                    .findAll { requested.contains(normalize(it.externalId)) }
            List<String> orderIds = echoed.collect { it.orderId as String }.findAll { it }
            List<Map> assocs = orderIds ? OmsGqlOrderPageSource.fetchExchangeAssocs(client, orderIds, pagination) : []
            Map<String, Object> ordersByExternalId = ids.collectEntries { [(it): []] }
            OmsGqlOrderGrainAssembler.assemble(echoed, assocs).each { Map o ->
                ((List) ordersByExternalId[normalize(o.externalId)]).add([
                        omsOrderId      : normalize(o.orderId),
                        externalId      : normalize(o.externalId),
                        orderName       : normalize(o.orderName),
                        orderTypeId     : normalize(o.orderTypeId),
                        statusId        : normalize(o.statusId),
                        grandTotal      : o.grandTotal,
                        orderDate       : o.orderDate,
                        hasExchangeAssoc: o.orderItemAssocs as boolean,
                ])
            }
            return [ok: true, ordersByExternalId: ordersByExternalId, errors: []]
        }
    }

    private static Map<String, Object> failure(String message) {
        return OmsIdLookup.failure([ordersByExternalId: [:]], message)
    }
}
