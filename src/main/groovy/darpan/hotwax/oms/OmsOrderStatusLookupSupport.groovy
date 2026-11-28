package darpan.hotwax.oms

import static darpan.common.ValueSupport.normalize

/**
 * DAR-BE-111. What HotWax says of each order NetSuite never shipped or billed: its statusId, by OMS order id (the
 * NetSuite HC Order Id). GraphQL only, flat and id-keyed: orders(orderId:a,b,...) in chunks, every answer echo-checked
 * against what was asked. RAILS 2026-10-22: 350 ids in 7 calls, 3.5s; gorjana 1,227 in 25 calls, 14s.
 *
 * A failed call fails the whole lookup (ok=false, nothing classified): an order is never called missing because
 * HotWax could not be asked. Nor because HotWax ignored the filter: an answer naming no asked id fails too (OmsIdLookup).
 */
class OmsOrderStatusLookupSupport {

    static final int ID_CHUNK = OmsGqlQueries.ORDER_STATUSES_ID_CHUNK
    static final int ITEMS_ID_CHUNK = OmsGqlQueries.ORDER_ITEMS_ID_CHUNK
    static final int SHIP_GROUPS_PER_ORDER = OmsGqlQueries.SHIP_GROUPS_PER_ORDER

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
        List<String> ids = OmsIdLookup.ids(orderIds)
        Map empty = [statusByOrderId: [:], missingIds: []]
        if (!ids) return [ok: true] + empty + [errors: []]
        if (!OmsIdLookup.askable(ids)) return OmsIdLookup.failure(empty, "OMS order ids must be plain identifiers.")
        return OmsIdLookup.run(rawConfig, clientFactory, empty, "OMS order status lookup") { OmsGqlClient client ->
            Set<String> requested = new HashSet<String>(ids)
            Map<String, String> statusByOrderId = [:]
            orderRows(client, ids, requested).each { Map n ->
                String orderId = normalize(n.orderId)
                if (orderId && requested.contains(orderId)) statusByOrderId.put(orderId, normalize(n.statusId))
            }
            return [ok: true, statusByOrderId: statusByOrderId, missingIds: ids.findAll { !statusByOrderId.containsKey(it) }, errors: []]
        }
    }

    /** The asked orders' [orderId, statusId, orderDate] rows, echo-checked. */
    private static List<Map> orderRows(OmsGqlClient client, List<String> ids, Set<String> requested) {
        return OmsIdLookup.requireEcho(OmsGqlReturnsPageSource.fetchByIds(client, OmsGqlQueries.orderStatusesDocument(), "orders",
                "orderId", ids, ID_CHUNK, ID_CHUNK, OmsGqlQueries.ORDER_STATUSES_RESERVATION, [lookupCalls: 0]),
                requested, "orderId", "orders")
    }

    /** DAR-BE-320. The OMS order status timeline's widest window (moqui-gql orderStatusTimeline: at most 31 days). */
    static final long TIMELINE_MAX_MILLIS = 31L * 86_400_000L

    /**
     * DAR-BE-320. The HotWax orders an item status in statusIds was written for in [since, thru), any order date (a NetSuite
     * read bounds them by its own window): [ok, orderIds, calls, errors]. Asked from the status timeline (DAR-BE-356), in
     * slices of at most 31 days. A failed call fails the whole read (ok=false, no ids): never a read that left some out.
     */
    static Map<String, Object> changedOrderIds(Object rawConfig, long since, long thru, List<String> statusIds) {
        List<String> statuses = (statusIds ?: []).collect { normalize(it)?.toUpperCase() }.findAll { it }.unique()
        if (!statuses) return [ok: false, orderIds: [], calls: 0, errors: ["Name the HotWax item statuses to read changed orders by."]]
        if (!statuses.every { it ==~ /ITEM_[A-Z_]{1,40}/ }) {
            return [ok: false, orderIds: [], calls: 0, errors: ["HotWax item statuses are ITEM_..., got ${statuses}".toString()]]
        }
        Map<String, Object> config = OmsExtractSupport.toPlainMap(rawConfig)
        if (!config) return [ok: false, orderIds: [], calls: 0, errors: ["OMS source config could not be read."]]
        Map pagination = [lookupCalls: 0]
        try {
            OmsGqlClient client = (OmsGqlClient) clientFactory.call(config)
            LinkedHashSet<String> ids = new LinkedHashSet<String>()
            for (long from = since; from < thru; from += TIMELINE_MAX_MILLIS) {
                ids.addAll(OmsGqlOrderPageSource.changedOrderIds(client, from, Math.min(thru, from + TIMELINE_MAX_MILLIS), null,
                        pagination, statuses))
            }
            return [ok: true, orderIds: ids as List<String>, calls: pagination.lookupCalls, errors: []]
        } catch (Exception e) {
            return [ok: false, orderIds: [], calls: pagination.lookupCalls,
                    errors: ["OMS changed orders read failed: ${e.message}".toString()]]
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
        List<String> ids = OmsIdLookup.ids(orderIds)
        Map empty = [itemStatusByKey: [:], missingIds: []]
        if (!ids) return [ok: true] + empty + [errors: []]
        if (!OmsIdLookup.askable(ids)) return OmsIdLookup.failure(empty, "OMS order ids must be plain identifiers.")
        return OmsIdLookup.run(rawConfig, clientFactory, empty, "OMS order item status lookup") { OmsGqlClient client ->
            Set<String> requested = new HashSet<String>(ids)
            Map<String, String> itemStatusByKey = [:]
            Map<String, String> itemShipGroupByKey = [:]
            Set<String> answered = new HashSet<String>()
            OmsIdLookup.requireEcho(OmsGqlReturnsPageSource.fetchByIds(client, OmsGqlQueries.orderItemStatusesDocument(), "orderItems",
                    "orderId", ids, ITEMS_ID_CHUNK, OmsGqlQueries.ORDER_ITEMS_PAGE_SIZE, OmsGqlQueries.ORDER_ITEMS_RESERVATION,
                    [lookupCalls: 0]), requested, "orderId", "order items")
                    .each { Map n ->
                        String orderId = normalize(n.orderId)
                        if (!orderId || !requested.contains(orderId) || !normalize(n.orderItemSeqId)) return
                        answered.add(orderId)
                        itemStatusByKey.put(itemKey(orderId, n.orderItemSeqId), normalize(n.statusId))
                        if (normalize(n.shipGroupSeqId)) itemShipGroupByKey.put(itemKey(orderId, n.orderItemSeqId), normalize(n.shipGroupSeqId))
                    }
            return [ok: true, itemStatusByKey: itemStatusByKey, itemShipGroupByKey: itemShipGroupByKey,
                    missingIds: ids.findAll { !answered.contains(it) }, errors: []]
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
        List<String> ids = OmsIdLookup.ids(orderIds)
        Map empty = [shipmentsByOrderId: [:], missingIds: []]
        if (!ids) return [ok: true] + empty + [errors: []]
        if (!OmsIdLookup.askable(ids)) return OmsIdLookup.failure(empty, "OMS order ids must be plain identifiers.")
        return OmsIdLookup.run(rawConfig, clientFactory, empty, "OMS shipment lookup") { OmsGqlClient client ->
            Set<String> requested = new HashSet<String>(ids)
            Set<String> known = orderRows(client, ids, requested)
                    .collect { Map n -> normalize(n.orderId) }.findAll { requested.contains(it) } as Set<String>
            List<Map> shipments = OmsIdLookup.requireEcho(OmsGqlReturnsPageSource.fetchByIds(client, OmsGqlQueries.orderShipmentsDocument(),
                    "shipments", "primaryOrderId", ids, OmsGqlQueries.SHIPMENTS_ID_CHUNK, OmsGqlQueries.SHIPMENTS_PAGE_SIZE,
                    OmsGqlQueries.SHIPMENTS_RESERVATION, [lookupCalls: 0]), requested, "primaryOrderId", "shipments")
                    .findAll { Map n -> requested.contains(normalize(n.primaryOrderId)) && normalize(n.shipmentTypeId) == "SALES_SHIPMENT" }
            List<String> facilityIds = shipments.collect { normalize(it.originFacilityId) }
                    .findAll { it && OmsIdLookup.ASKABLE_ID.matcher(it).matches() }.unique()
            Map<String, String> locationByFacility = [:]
            if (facilityIds) {
                OmsIdLookup.requireEcho(OmsGqlReturnsPageSource.fetchByIds(client, OmsGqlQueries.facilityExternalIdsDocument(), "facilities",
                        "facilityId", facilityIds, OmsGqlQueries.FACILITIES_ID_CHUNK, OmsGqlQueries.FACILITIES_ID_CHUNK,
                        OmsGqlQueries.FACILITIES_RESERVATION, [lookupCalls: 0]), facilityIds, "facilityId", "facilities")
                        .each { Map f -> if (normalize(f.facilityId)) locationByFacility.put(normalize(f.facilityId), normalize(f.externalId)) }
            }
            Map<String, List<Map>> byOrder = [:]
            shipments.each { Map n ->
                String facilityId = normalize(n.originFacilityId)
                ((List) byOrder.computeIfAbsent(normalize(n.primaryOrderId)) { [] }) << [shipmentId: normalize(n.shipmentId),
                        facilityId: facilityId, location: facilityId ? locationByFacility.get(facilityId) : null, statusId: normalize(n.statusId)]
            }
            return [ok: true, shipmentsByOrderId: byOrder, missingIds: ids.findAll { !known.contains(it) }, errors: []]
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
        List<String> ids = OmsIdLookup.ids(orderIds)
        Map empty = [shipGroupFacilityByKey: [:], missingIds: []]
        if (!ids) return [ok: true] + empty + [errors: []]
        if (!OmsIdLookup.askable(ids)) return OmsIdLookup.failure(empty, "OMS order ids must be plain identifiers.")
        return OmsIdLookup.run(rawConfig, clientFactory, empty, "OMS ship group lookup") { OmsGqlClient client ->
            Set<String> requested = new HashSet<String>(ids)
            Set<String> answered = new HashSet<String>()
            Map<String, List<String>> byKey = [:]
            OmsIdLookup.requireEcho(OmsGqlReturnsPageSource.fetchByIds(client, OmsGqlQueries.shipGroupFacilitiesDocument(), "orders",
                    "orderId", ids, OmsGqlQueries.SHIP_GROUP_FACILITIES_ID_CHUNK, OmsGqlQueries.SHIP_GROUP_FACILITIES_ID_CHUNK,
                    OmsGqlQueries.SHIP_GROUP_FACILITIES_RESERVATION, [lookupCalls: 0]), requested, "orderId", "orders")
                    .each { Map n ->
                        String orderId = normalize(n.orderId)
                        if (!orderId || !requested.contains(orderId)) return
                        answered.add(orderId)
                        List<Map> groups = OmsGqlOrderGrainAssembler.edgeNodes((Map) n.shipGroups)
                        // DAR-OPS-029 S40b (OC145): a full list may have more groups past it; fail closed, naming the order.
                        if (groups.size() >= SHIP_GROUPS_PER_ORDER) {
                            throw new OmsGqlException("CUT_SHORT", ("order ${orderId} has ${groups.size()} ship groups, the most " +
                                    "one read holds, so where every group ships from is not known").toString())
                        }
                        groups.each { Map g ->
                            String seq = normalize(g.shipGroupSeqId)
                            if (seq) byKey.put(itemKey(orderId, seq), [normalize(g.facilityId), normalize(((Map) g.facility)?.externalId),
                                    normalize(g.shipmentMethodTypeId)])
                        }
                    }
            return [ok: true, shipGroupFacilityByKey: byKey, missingIds: ids.findAll { !answered.contains(it) }, errors: []]
        }
    }
}
