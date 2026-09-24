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

    /**
     * @return [units: List&lt;Map&gt;, droppedNullLineId: int, nonUnitQuantityCount: int]
     *         Dropped units are COUNTED, never silently discarded: a clean run whose metadata
     *         says 400 units were dropped is not a clean run.
     */
    static Map flattenOrderToUnits(Map order) {
        List<Map> units = []
        int droppedNullLineId = 0
        int nonUnitQuantityCount = 0
        if (order == null) return [units: units, droppedNullLineId: 0, nonUnitQuantityCount: 0]

        String shopifyOrderId = normalize(order.get("externalId"))
        String omsOrderId = normalize(order.get("orderId"))
        String omsOrderName = normalize(order.get("orderName"))

        // Collect across ALL ship groups before numbering: a Shopify line split across two ship
        // groups is still one line, and numbering per group would restart the ordinal mid-line.
        List<Map> items = []
        for (Object shipGroup : asList(order.get("shipGroups"))) {
            if (!(shipGroup instanceof Map)) continue
            for (Object item : asList(((Map) shipGroup).get("items"))) {
                if (item instanceof Map) items.add((Map) item)
            }
        }

        Map<String, List<Map>> itemsByLine = [:]
        for (Map item : items) {
            String lineId = normalize(item.get("externalId"))
            // A null order externalId is just as fatal to the key as a null line id, and is counted
            // the same way so the metadata total matches the number of units actually lost.
            if (!lineId || !shopifyOrderId) {
                droppedNullLineId++
                continue
            }
            if (!itemsByLine.containsKey(lineId)) itemsByLine.put(lineId, new ArrayList<Map>())
            itemsByLine.get(lineId).add(item)
        }

        for (String lineId : itemsByLine.keySet().sort()) {
            List<Map> lineItems = itemsByLine.get(lineId)
                    .sort { Map item -> normalize(item.get("orderItemSeqId")) ?: "" }
            int ordinal = 0
            for (Map item : lineItems) {
                int quantity = toPositiveInt(item.get("quantity"))
                if (quantity != 1) nonUnitQuantityCount++
                for (int copy = 0; copy < quantity; copy++) {
                    ordinal++
                    units.add([
                            shopifyOrderId: shopifyOrderId,
                            shopifyLineId : lineId,
                            unitOrdinal   : ordinal,
                            omsOrderId    : omsOrderId,
                            omsOrderName  : omsOrderName,
                            orderItemSeqId: normalize(item.get("orderItemSeqId")),
                            productId     : normalize(item.get("productId")),
                            statusId      : normalize(item.get("statusId")),
                    ])
                }
            }
        }
        return [units               : units,
                droppedNullLineId   : droppedNullLineId,
                nonUnitQuantityCount: nonUnitQuantityCount]
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
