package darpan.hotwax.oms

/**
 * GraphQL order nodes (+ the page's EXCHANGE assocs) -> documents the REST shaper accepts unchanged.
 *
 * THE EXCHANGE GRAFT IS THE POINT. OmsRestSourceSupport.containsExchangeOrderAssociation scans the
 * whole document for orderItemAssocTypeId == EXCHANGE, and a GraphQL order node carries no
 * association data, so without the graft every exchange order would re-enter the presence compare
 * as "in OMS, missing from Shopify". Grafting puts the rows where the existing scan finds them, so
 * the exclusion and its manifest stay ONE copy.
 *
 * The payment fields are derived here, before filtering and projection, so a tenant filter and the
 * presence rule can both read them. Pure: no Moqui, no socket.
 */
class OmsGqlOrderGrainAssembler {

    static final String EXCHANGE = "EXCHANGE"

    static List<Map> edgeNodes(Object connection) {
        List edges = (((connection ?: [:]) as Map).edges ?: []) as List
        return edges.collect { ((it as Map)?.node) as Map }.findAll { it != null }
    }

    private static Object toEpochMillis(Object value) {
        if (!(value instanceof CharSequence)) return value
        try { return java.time.Instant.parse(value.toString()).toEpochMilli() } catch (Exception ignored) { return value }
    }

    private static Object toDecimal(Object value) {
        if (!(value instanceof CharSequence)) return value
        try { return new BigDecimal(value.toString().trim()) } catch (Exception ignored) { return value }
    }

    static List<Map> assemble(List<Map> orderNodes, List<Map> assocNodes) {
        Map<String, List<Map>> exchangeByOrder = [:]
        for (Map row : (assocNodes ?: [])) {
            // Enforced client-side too: if the server ignored the type term, a non-exchange assoc must
            // not exclude an order.
            if (!EXCHANGE.equalsIgnoreCase(row?.get("orderItemAssocTypeId") as String)) continue
            String orderId = row.get("orderId") as String
            if (orderId) exchangeByOrder.computeIfAbsent(orderId, { [] }).add(new LinkedHashMap(row))
        }

        List<Map> assembled = []
        for (Map node : (orderNodes ?: [])) {
            Map order = new LinkedHashMap(node)
            String orderId = order.get("orderId") as String

            List<Map> opps = ((order.get("paymentPreferences") ?: []) as List<Map>)
            order.put("hasPaymentPreference", opps ? "Y" : "N")
            order.put("paymentMethodTypeIds", opps.collect { it?.get("paymentMethodTypeId") as String }
                    .findAll { it }.unique().sort().join(","))
            order.put("paymentPreferenceSlotsFull", opps.size() >= OmsGqlQueries.PAYMENT_PREF_SLOTS)

            if (order.containsKey("shipGroups")) order.put("shipGroups", edgeNodes(order.get("shipGroups")))

            // REST's value types (review I5): orderDate as epoch millis, grandTotal as a number. A value
            // that does not parse is kept as it came rather than dropped.
            order.put("orderDate", toEpochMillis(order.get("orderDate")))
            order.put("grandTotal", toDecimal(order.get("grandTotal")))

            List<Map> grafts = exchangeByOrder.get(orderId)
            if (grafts) order.put("orderItemAssocs", grafts)
            assembled.add(order)
        }
        return assembled
    }
}
