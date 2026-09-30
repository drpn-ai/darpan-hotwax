package darpan.hotwax.oms

/**
 * DAR-BE-050. Flattens one OMS order document into one record per UNIT.
 *
 * OMS explodes a Shopify line of quantity N into N single-unit OrderItems for routing - measured
 * at quantity 1 on 258,932 of 258,932 captured gorjana rows, with 5.33% of (order, line) keys
 * carrying two or more units and the longest carrying 40. The compare core is a left_anti join
 * followed by .distinct(), i.e. set semantics, so without a per-unit ordinal in the key a line
 * OMS imported SHORT matches a complete Shopify line and the pair reports clean. See the design
 * doc D1 and OrderLineUnitKeyBlindnessTests.
 *
 * Pure: no entity access, no I/O, no ExecutionContext. That is deliberate - every interesting
 * edge case in this feature is a document-shape case, and they are all testable here.
 */
class OmsOrderLineUnitSupport {

    /** D5 of the tri-system design: composite keys refuse a blank part, so absence is a value. */
    static final String NO_NETSUITE_ORDER_ID = "NONE"
    static final String NETSUITE_ORDER_ID_TYPE = "NETSUITE_ORDER_ID"

    /**
     * @param options optional. {@code requireShopifyLineId} (default true) keeps DAR-BE-050's
     *        behaviour of dropping an item that has no Shopify line id or whose order has no Shopify
     *        order id. False (the OMS_ORDER_ITEMS connector, tri-system D4) EMITS those items with
     *        whichever Shopify id is null left null, because the NetSuite hops key on the OMS order.
     * @return [units: List&lt;Map&gt;, droppedNullLineId: int, nonUnitQuantityCount: int,
     *         keptNullShopifyIdCount: int]
     *         Dropped units are COUNTED, never silently discarded: a clean run whose metadata
     *         says 400 units were dropped is not a clean run. Kept-but-unkeyed items are counted
     *         apart, per ITEM like droppedNullLineId, so neither number is mistaken for the other.
     */
    static Map flattenOrderToUnits(Map order, Map options = null) {
        List<Map> units = []
        int droppedNullLineId = 0
        int nonUnitQuantityCount = 0
        int keptNullShopifyIdCount = 0
        if (order == null) {
            return [units: units, droppedNullLineId: 0, nonUnitQuantityCount: 0, keptNullShopifyIdCount: 0]
        }
        boolean requireShopifyLineId = options?.get("requireShopifyLineId") != false

        String shopifyOrderId = normalize(order.get("externalId"))
        String omsOrderId = normalize(order.get("orderId"))
        String omsOrderName = normalize(order.get("orderName"))
        String omsOrderStatusId = normalize(order.get("statusId"))
        // DAR-UI-044: conclusion rules read the unit row alone, and the channel decides "POS order".
        String salesChannelEnumId = normalize(order.get("salesChannelEnumId"))
        String netsuiteOrderId = netsuiteOrderIdOf(order)

        // Collect across ALL ship groups before numbering: a Shopify line split across two ship
        // groups is still one line, and numbering per group would restart the ordinal mid-line.
        // The ship group's facilityId is remembered per item (identity, not equality: two items can
        // be equal maps) because the item itself does not carry it.
        List<Map> items = []
        Map<Map, String> facilityByItem = new IdentityHashMap<Map, String>()
        for (Object shipGroup : asList(order.get("shipGroups"))) {
            if (!(shipGroup instanceof Map)) continue
            String facilityId = normalize(((Map) shipGroup).get("facilityId"))
            for (Object item : asList(((Map) shipGroup).get("items"))) {
                if (!(item instanceof Map)) continue
                items.add((Map) item)
                facilityByItem.put((Map) item, facilityId)
            }
        }

        Map<String, List<Map>> itemsByLine = [:]
        // Items kept without a usable Shopify key. Each is its OWN ordinal group, i.e. the group is
        // (omsOrderId, orderItemSeqId): there is no Shopify line to pool them under, and pooling
        // them all into one "null line" would number unrelated items against each other.
        List<Map> unkeyedItems = []
        for (Map item : items) {
            String lineId = normalize(item.get("externalId"))
            // A null order externalId is just as fatal to the key as a null line id, and is counted
            // the same way so the metadata total matches the number of units actually lost.
            if (!lineId || !shopifyOrderId) {
                if (requireShopifyLineId) {
                    droppedNullLineId++
                } else {
                    keptNullShopifyIdCount++
                    unkeyedItems.add(item)
                }
                continue
            }
            if (!itemsByLine.containsKey(lineId)) itemsByLine.put(lineId, new ArrayList<Map>())
            itemsByLine.get(lineId).add(item)
        }

        // Keyed lines first, in the unchanged DAR-BE-050 order; unkeyed items after, by
        // orderItemSeqId, so a re-run of the same window still yields byte-identical output.
        List<List<Map>> groups = []
        for (String lineId : itemsByLine.keySet().sort()) {
            groups.add(itemsByLine.get(lineId).sort { Map item -> normalize(item.get("orderItemSeqId")) ?: "" })
        }
        for (Map item : unkeyedItems.sort { Map unkeyed -> normalize(unkeyed.get("orderItemSeqId")) ?: "" }) {
            groups.add([item])
        }

        for (List<Map> lineItems : groups) {
            int ordinal = 0
            // D2: stateOrdinal is 1..k within (order, line, state), assigned in unitOrdinal order.
            Map<String, Integer> stateOrdinals = [:]
            for (Map item : lineItems) {
                int quantity = toPositiveInt(item.get("quantity"))
                if (quantity != 1) nonUnitQuantityCount++
                String statusId = normalize(item.get("statusId"))
                String unitState = unitStateOf(statusId)
                for (int copy = 0; copy < quantity; copy++) {
                    ordinal++
                    int stateOrdinal = (stateOrdinals.get(unitState) ?: 0) + 1
                    stateOrdinals.put(unitState, stateOrdinal)
                    units.add([
                            shopifyOrderId  : shopifyOrderId,
                            shopifyLineId   : normalize(item.get("externalId")),
                            unitOrdinal     : ordinal,
                            omsOrderId      : omsOrderId,
                            omsOrderName    : omsOrderName,
                            orderItemSeqId  : normalize(item.get("orderItemSeqId")),
                            productId       : normalize(item.get("productId")),
                            statusId        : statusId,
                            unitState       : unitState,
                            stateOrdinal    : stateOrdinal,
                            netsuiteOrderId : netsuiteOrderId,
                            omsOrderStatusId: omsOrderStatusId,
                            facilityId      : facilityByItem.get(item),
                            salesChannelEnumId: salesChannelEnumId,
                    ])
                }
            }
        }
        return [units                 : units,
                droppedNullLineId     : droppedNullLineId,
                nonUnitQuantityCount  : nonUnitQuantityCount,
                keptNullShopifyIdCount: keptNullShopifyIdCount]
    }

    /**
     * D2. OPEN = created or approved, COMPLETED, CANCELLED; anything else (rejected, a status OMS
     * adds later) is OTHER rather than being guessed into a state a run counts.
     */
    static String unitStateOf(String itemStatusId) {
        switch (itemStatusId) {
            case "ITEM_CREATED":
            case "ITEM_APPROVED":
                return "OPEN"
            case "ITEM_COMPLETED":
                return "COMPLETED"
            case "ITEM_CANCELLED":
                return "CANCELLED"
            default:
                return "OTHER"
        }
    }

    /** First non-blank NETSUITE_ORDER_ID identification, else the {@link #NO_NETSUITE_ORDER_ID} sentinel. */
    private static String netsuiteOrderIdOf(Map order) {
        for (Object identification : asList(order.get("identifications"))) {
            if (!(identification instanceof Map)) continue
            Map row = (Map) identification
            if (normalize(row.get("orderIdentificationTypeId")) != NETSUITE_ORDER_ID_TYPE) continue
            String idValue = normalize(row.get("idValue"))
            if (idValue) return idValue
        }
        return NO_NETSUITE_ORDER_ID
    }

    private static List asList(Object value) {
        return value instanceof List ? (List) value : []
    }

    private static String normalize(Object value) {
        String text = value?.toString()?.trim()
        return text ? text : null
    }

    /** A zero, negative, or unparseable quantity emits no unit rather than a negative loop. */
    private static int toPositiveInt(Object value) {
        if (value instanceof Number) return Math.max(0, ((Number) value).intValue())
        try {
            return Math.max(0, Integer.parseInt(value?.toString()?.trim() ?: "1"))
        } catch (NumberFormatException ignored) {
            return 0
        }
    }
}
