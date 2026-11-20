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
    static final int ITEMS_ID_CHUNK = OmsGqlQueries.ORDER_ITEMS_ID_CHUNK
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
        Map<String, Object> config = OmsExtractSupport.toPlainMap(rawConfig)
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

    /** Key of one item in itemStatusByKey: the OMS order id and the item's seq id. */
    static String itemKey(Object orderId, Object orderItemSeqId) {
        return "${normalize(orderId)}|${normalize(orderItemSeqId)}".toString()
    }

    /**
     * DAR-BE-190. Returns [ok, itemStatusByKey: itemKey(orderId, seq) -> statusId, missingIds (orders HotWax returned no
     * item for), errors]. Same guarantees as lookupOrderStatuses: only asked orders count, and a failed call classifies
     * nothing.
     */
    static Map<String, Object> lookupOrderItemStatuses(Object rawConfig, Collection orderIds) {
        List<String> ids = (orderIds ?: []).collect { normalize(it) }.findAll { it }.unique()
        if (!ids) return [ok: true, itemStatusByKey: [:], missingIds: [], errors: []]
        if (!ids.every { ASKABLE_ID.matcher(it).matches() }) {
            return [ok: false, itemStatusByKey: [:], missingIds: [], errors: ["OMS order ids must be plain identifiers."]]
        }
        Map<String, Object> config = OmsExtractSupport.toPlainMap(rawConfig)
        if (!config) return [ok: false, itemStatusByKey: [:], missingIds: [], errors: ["OMS source config could not be read."]]
        try {
            OmsGqlClient client = (OmsGqlClient) clientFactory.call(config)
            Set<String> requested = new HashSet<String>(ids)
            Map<String, String> itemStatusByKey = [:]
            Map<String, String> itemShipGroupByKey = [:]
            Set<String> answered = new HashSet<String>()
            OmsGqlReturnsPageSource.fetchByIds(client, OmsGqlQueries.orderItemStatusesDocument(), "orderItems", "orderId", ids,
                    ITEMS_ID_CHUNK, OmsGqlQueries.ORDER_ITEMS_PAGE_SIZE, OmsGqlQueries.ORDER_ITEMS_RESERVATION, [lookupCalls: 0])
                    .each { Map n ->
                        String orderId = normalize(n.orderId)
                        if (!orderId || !requested.contains(orderId) || !normalize(n.orderItemSeqId)) return
                        answered.add(orderId)
                        itemStatusByKey.put(itemKey(orderId, n.orderItemSeqId), normalize(n.statusId))
                        if (normalize(n.shipGroupSeqId)) itemShipGroupByKey.put(itemKey(orderId, n.orderItemSeqId), normalize(n.shipGroupSeqId))
                    }
            return [ok: true, itemStatusByKey: itemStatusByKey, itemShipGroupByKey: itemShipGroupByKey,
                    missingIds: ids.findAll { !answered.contains(it) }, errors: []]
        } catch (Exception e) {
            return [ok: false, itemStatusByKey: [:], missingIds: [], errors: ["OMS order item status lookup failed: ${e.message}".toString()]]
        }
    }

    /**
     * DAR-BE-329. Each asked order's sales shipments: shipmentsByOrderId orderId -> [[shipmentId, facilityId, location,
     * statusId], ...], location being the origin facility's externalId (the NetSuite location id; null when HotWax holds
     * none). missingIds are the orders HotWax does not have at all (read from the orders, since an order with no
     * shipment is not missing). Aditi 2026-11-20 (Malbon): NetSuite fulfillments are counted against HotWax shipments,
     * not ship groups: Malbon puts x-redo, a digital good, in a ship group of its own that ships nothing. Same guarantees
     * as the other lookups: only asked orders count, and a failed call classifies nothing.
     */
    static Map<String, Object> lookupOrderShipments(Object rawConfig, Collection orderIds) {
        List<String> ids = (orderIds ?: []).collect { normalize(it) }.findAll { it }.unique()
        if (!ids) return [ok: true, shipmentsByOrderId: [:], missingIds: [], errors: []]
        if (!ids.every { ASKABLE_ID.matcher(it).matches() }) {
            return [ok: false, shipmentsByOrderId: [:], missingIds: [], errors: ["OMS order ids must be plain identifiers."]]
        }
        Map<String, Object> config = OmsExtractSupport.toPlainMap(rawConfig)
        if (!config) return [ok: false, shipmentsByOrderId: [:], missingIds: [], errors: ["OMS source config could not be read."]]
        try {
            OmsGqlClient client = (OmsGqlClient) clientFactory.call(config)
            Set<String> requested = new HashSet<String>(ids)
            Set<String> known = OmsGqlReturnsPageSource.fetchByIds(client, OmsGqlQueries.orderStatusesDocument(), "orders", "orderId",
                    ids, ID_CHUNK, ID_CHUNK, OmsGqlQueries.ORDER_STATUSES_RESERVATION, [lookupCalls: 0])
                    .collect { Map n -> normalize(n.orderId) }.findAll { requested.contains(it) } as Set<String>
            List<Map> shipments = OmsGqlReturnsPageSource.fetchByIds(client, OmsGqlQueries.orderShipmentsDocument(), "shipments",
                    "primaryOrderId", ids, OmsGqlQueries.SHIPMENTS_ID_CHUNK, OmsGqlQueries.SHIPMENTS_PAGE_SIZE,
                    OmsGqlQueries.SHIPMENTS_RESERVATION, [lookupCalls: 0])
                    .findAll { Map n -> requested.contains(normalize(n.primaryOrderId)) && normalize(n.shipmentTypeId) == "SALES_SHIPMENT" }
            List<String> facilityIds = shipments.collect { normalize(it.originFacilityId) }
                    .findAll { it && ASKABLE_ID.matcher(it).matches() }.unique()
            Map<String, String> locationByFacility = [:]
            if (facilityIds) {
                OmsGqlReturnsPageSource.fetchByIds(client, OmsGqlQueries.facilityExternalIdsDocument(), "facilities", "facilityId",
                        facilityIds, OmsGqlQueries.FACILITIES_ID_CHUNK, OmsGqlQueries.FACILITIES_ID_CHUNK,
                        OmsGqlQueries.FACILITIES_RESERVATION, [lookupCalls: 0])
                        .each { Map f -> if (normalize(f.facilityId)) locationByFacility.put(normalize(f.facilityId), normalize(f.externalId)) }
            }
            Map<String, List<Map>> byOrder = [:]
            shipments.each { Map n ->
                String facilityId = normalize(n.originFacilityId)
                ((List) byOrder.computeIfAbsent(normalize(n.primaryOrderId)) { [] }) << [shipmentId: normalize(n.shipmentId),
                        facilityId: facilityId, location: facilityId ? locationByFacility.get(facilityId) : null, statusId: normalize(n.statusId)]
            }
            return [ok: true, shipmentsByOrderId: byOrder, missingIds: ids.findAll { !known.contains(it) }, errors: []]
        } catch (Exception e) {
            return [ok: false, shipmentsByOrderId: [:], missingIds: [], errors: ["OMS shipment lookup failed: ${e.message}".toString()]]
        }
    }

    /**
     * DAR-BE-245. Where each asked order's ship groups ship from: itemKey(orderId, shipGroupSeqId) -> [facilityId,
     * externalId, shipmentMethodTypeId] (the facility's externalId is the NetSuite location id on RAILS; DAR-BE-294 adds
     * how the group ships). Asked only of the orders that have an approved open line (DAR-BE-294), so the facility read
     * stays small. Same guarantees as
     * the other lookups: only asked orders count, and a failed call classifies nothing.
     */
    static Map<String, Object> lookupShipGroupFacilities(Object rawConfig, Collection orderIds) {
        List<String> ids = (orderIds ?: []).collect { normalize(it) }.findAll { it }.unique()
        if (!ids) return [ok: true, shipGroupFacilityByKey: [:], missingIds: [], errors: []]
        if (!ids.every { ASKABLE_ID.matcher(it).matches() }) {
            return [ok: false, shipGroupFacilityByKey: [:], missingIds: [], errors: ["OMS order ids must be plain identifiers."]]
        }
        Map<String, Object> config = OmsExtractSupport.toPlainMap(rawConfig)
        if (!config) return [ok: false, shipGroupFacilityByKey: [:], missingIds: [], errors: ["OMS source config could not be read."]]
        try {
            OmsGqlClient client = (OmsGqlClient) clientFactory.call(config)
            Set<String> requested = new HashSet<String>(ids)
            Set<String> answered = new HashSet<String>()
            Map<String, List<String>> byKey = [:]
            OmsGqlReturnsPageSource.fetchByIds(client, OmsGqlQueries.shipGroupFacilitiesDocument(), "orders", "orderId", ids,
                    OmsGqlQueries.SHIP_GROUP_FACILITIES_ID_CHUNK, OmsGqlQueries.SHIP_GROUP_FACILITIES_ID_CHUNK,
                    OmsGqlQueries.SHIP_GROUP_FACILITIES_RESERVATION, [lookupCalls: 0])
                    .each { Map n ->
                        String orderId = normalize(n.orderId)
                        if (!orderId || !requested.contains(orderId)) return
                        answered.add(orderId)
                        OmsGqlOrderGrainAssembler.edgeNodes((Map) n.shipGroups).each { Map g ->
                            String seq = normalize(g.shipGroupSeqId)
                            if (seq) byKey.put(itemKey(orderId, seq), [normalize(g.facilityId), normalize(((Map) g.facility)?.externalId),
                                    normalize(g.shipmentMethodTypeId)])
                        }
                    }
            return [ok: true, shipGroupFacilityByKey: byKey, missingIds: ids.findAll { !answered.contains(it) }, errors: []]
        } catch (Exception e) {
            return [ok: false, shipGroupFacilityByKey: [:], missingIds: [], errors: ["OMS ship group lookup failed: ${e.message}".toString()]]
        }
    }
}
