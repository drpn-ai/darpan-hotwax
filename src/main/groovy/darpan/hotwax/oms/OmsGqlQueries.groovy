package darpan.hotwax.oms

/**
 * The order-grain GraphQL documents and their variables.
 *
 * COST IS MEASURED, NOT CHOSEN. moqui-gql charges on every REQUESTED list slot, requested cost equals
 * actual cost, and the budget is 1000 hard. Measured on gorjana prod (DAR-BE-064 P0, 2026-10-01):
 *     cost = 100 per page + n x (8 for the always-fields + 4 per paymentPreferences slot)
 *     50 x k1 = 700, 50 x k3 = 1100 (COST_EXCEEDED), 25 x k3 = 600, 25 x k1 = 400
 * Nested lists (2026-09-30 probe, conservative): identifications(5) ~18, shipGroups(3) ~12.5 per order.
 * So the page size is DERIVED from what the run reads; the DAR-BE-052 pass-1 shape (identifications +
 * shipGroups at 50) is always rejected.
 *
 * Order-grain only. DAR-BE-052's line-unit documents are added here later, beside these.
 */
class OmsGqlQueries {

    /** moqui-gql's `first` ceiling (FIRST_REQUIRED, max 100). */
    static final int MAX_PAGE_SIZE = 100
    /**
     * DAR-BE-116: the per-query cost a page is sized to. Live gorjana 2026-10-23: the BUCKET was raised to
     * 20000 at 2000/s, but one query is still capped at 1000 (60 returns -> 1260 COST_EXCEEDED, 100
     * returnItems -> 1110). The page sources halve the page on COST_EXCEEDED, so a server with a higher cap
     * can be given a bigger budget with -Ddarpan.oms.gql.queryCostBudget=N and a lower one costs one call.
     */
    static final String QUERY_COST_BUDGET_PROPERTY = "darpan.oms.gql.queryCostBudget"
    static final int DEFAULT_QUERY_COST_BUDGET = 950

    static int queryCostBudget() {
        String raw = System.getProperty(QUERY_COST_BUDGET_PROPERTY)
        try { return raw ? Math.max(200, raw.trim() as int) : DEFAULT_QUERY_COST_BUDGET } catch (ignored) { return DEFAULT_QUERY_COST_BUDGET }
    }
    /** Two slots: 1-2 OPPs cover 97% of gorjana orders, and k=3 would force the page down to 40. */
    static final int PAYMENT_PREF_SLOTS = 2
    static final double PAGE_OVERHEAD = 100d
    static final double HEADER_COST = 8.0d           // the ALWAYS_FIELDS, per order
    static final double EXTRA_SCALAR_COST = 1.5d     // each further header scalar, per order (conservative)
    static final double PER_SLOT_COST = 4.0d
    static final int ASSOC_PAGE_SIZE = 50
    static final int ASSOC_RESERVATION = 350         // measured 300 at first: 50

    /** Top-level scalars proven to exist on the deployed Order type (OmsGraphQlLiveProbe, 2026-09-30). */
    static final Set<String> HEADER_FIELDS = ["orderId", "orderName", "externalId", "statusId", "statusFlowId",
            "orderTypeId", "orderDate", "grandTotal", "currencyUomId", "productStoreId", "orderItemCount",
            "lastUpdatedTxStamp"] as Set
    /** Always selected: the compare key, the built-in non-sales filter, and the REST keepFieldsBase. */
    static final List<String> ALWAYS_FIELDS = ["orderId", "orderName", "externalId", "statusId", "orderTypeId",
            "orderDate", "grandTotal"]
    /** Computed by OmsGqlOrderGrainAssembler from paymentPreferences, which is always selected. */
    static final Set<String> DERIVED_FIELDS = ["hasPaymentPreference", "paymentMethodTypeIds", "paymentPreferenceSlotsFull"] as Set
    /** Nested lists, selected only when something reads them, with their measured per-order cost. */
    static final Map<String, Map> NESTED_FIELDS = [
            identifications: [selection: "identifications(first: 5) { orderIdentificationTypeId idValue }", cost: 18.0d],
            shipGroups     : [selection: "shipGroups(first: 3) { edges { node { shipGroupSeqId facilityId shipmentMethodTypeId carrierPartyId } } }", cost: 12.5d],
    ]

    /**
     * The fields the run reads: the projection plus every source-filter field. Filter fields are added
     * EVEN WITHOUT a projection (review C1): a filter on a field the document never selected excludes
     * nothing. Null only when there is neither a projection nor a filter.
     */
    static Set<String> requiredFields(Set<String> keepFieldSet, List<Map> excludeRules) {
        Set<String> required = new LinkedHashSet<String>(keepFieldSet ?: [])
        (excludeRules ?: []).each { Map rule ->
            String field = (rule?.get("fieldExpression") as String)?.trim()
            if (field) required.add(field)
        }
        return (keepFieldSet == null && required.isEmpty()) ? null : required
    }

    static Map orderGrainPagePlan(Set<String> requiredFields) {
        Set<String> wanted = requiredFields ?: ([] as Set<String>)
        List<String> unknown = wanted.findAll { String f ->
            !HEADER_FIELDS.contains(f) && !DERIVED_FIELDS.contains(f) && !NESTED_FIELDS.containsKey(f)
        }.sort()
        if (unknown) {
            throw new IllegalArgumentException("The OMS GraphQL order extract cannot supply field(s) ${unknown}. " +
                    "A filter or projection on a field the document does not carry would silently not apply " +
                    "(an exclusion filter on a missing field excludes nothing). Known fields: " +
                    "${(HEADER_FIELDS + DERIVED_FIELDS + NESTED_FIELDS.keySet()).sort()}. " +
                    "Add the field to OmsGqlQueries or keep this rule set on the REST connector.")
        }
        LinkedHashSet<String> scalars = new LinkedHashSet<String>(ALWAYS_FIELDS)
        wanted.findAll { HEADER_FIELDS.contains(it) }.each { scalars.add(it) }
        List<String> nested = NESTED_FIELDS.keySet().findAll { wanted.contains(it) } as List<String>

        int extraScalars = scalars.size() - ALWAYS_FIELDS.size()
        double perOrder = perOrderCost(PAYMENT_PREF_SLOTS, extraScalars, nested)
        int budget = queryCostBudget()
        int pageSize = Math.max(1, Math.min(MAX_PAGE_SIZE, (int) Math.floor((budget - PAGE_OVERHEAD) / perOrder)))
        int reservation = Math.min(budget + 50, estimatedPageCost(pageSize, PAYMENT_PREF_SLOTS, extraScalars, nested) + 50)

        String selection = (scalars as List).join(" ") +
                "\n      paymentPreferences(first: ${PAYMENT_PREF_SLOTS}) { paymentMethodTypeId statusId maxAmount }" +
                nested.collect { "\n      " + NESTED_FIELDS[it].selection }.join("")
        String document = """query OmsOrderGrain(\$q: String, \$first: Int, \$after: String) {
  orders(query: \$q, sortKey: ORDER_DATE, reverse: false, first: \$first, after: \$after) {
    edges { node {
      ${selection}
    } }
    pageInfo { hasNextPage endCursor }
  }
}"""
        return [document: document, pageSize: pageSize, reservation: reservation]
    }

    /** Requested (= actual) cost of one orders page under the measured model. */
    static int estimatedPageCost(int pageSize, int slots, int extraScalars, List<String> nested) {
        return (int) Math.ceil(PAGE_OVERHEAD + pageSize * perOrderCost(slots, extraScalars, nested))
    }

    private static double perOrderCost(int slots, int extraScalars, List<String> nested) {
        return HEADER_COST + extraScalars * EXTRA_SCALAR_COST + slots * PER_SLOT_COST +
                ((nested ?: []).collect { (NESTED_FIELDS[it].cost as double) }.sum() ?: 0d)
    }

    /** The search string. It MAY contain `<`: it travels as a variable, never inside the document. */
    static String windowFilter(String fromIso, String thruIso) {
        return "orderDate:>=${fromIso} orderDate:<${thruIso} orderTypeId:SALES_ORDER".toString()
    }

    /** DAR-BE-119: keys per search when records are read by key (an older open finding's order or return). */
    static final int LOOKUP_KEYS_CHUNK = 100

    /**
     * DAR-BE-119. The search for records named by key, e.g. `returnId:M1,M2`; orders keep their type. A key travels inside
     * the search string, so it may hold only id characters: a space or a colon would add a condition of its own.
     */
    static String keysFilter(String field, List<String> keys) {
        keys.each { if (!(it ==~ /[A-Za-z0-9_\-]{1,40}/)) throw new IllegalArgumentException("An OMS key holds letters, digits, _ and - only, got: ${it}") }
        String filter = "${field}:${keys.join(',')}".toString()
        return field == "orderId" ? "${filter} orderTypeId:SALES_ORDER".toString() : filter
    }

    static Map pageVariables(String filter, int pageSize, String after) {
        Map vars = [q: filter, first: pageSize]
        if (after) vars.put("after", after)
        return vars
    }

    static String exchangeAssocsDocument() {
        return '''query OmsExchangeAssocs($q: String, $first: Int, $after: String) {
  orderItemAssocs(query: $q, first: $first, after: $after) {
    edges { node { orderId orderItemSeqId toOrderId toOrderItemSeqId orderItemAssocTypeId } }
    pageInfo { hasNextPage endCursor }
  }
}'''
    }

    static Map exchangeAssocsVariables(List<String> orderIds, String after) {
        Map vars = [q: "orderId:${orderIds.join(',')} orderItemAssocTypeId:EXCHANGE".toString(), first: ASSOC_PAGE_SIZE]
        if (after) vars.put("after", after)
        return vars
    }

    // --- DAR-BE-040: returns by-id lookup ------------------------------------------------------

    /**
     * Identification types that name the Shopify return. SHOPIFY_RETURN_ID is the enum going forward;
     * returns created before the switch keep SHOPIFY_RTN_ID. Both were live on gorjana 2026-10-03.
     */
    static final Set<String> SHOPIFY_RETURN_IDENTIFICATION_TYPES = ["SHOPIFY_RETURN_ID", "SHOPIFY_RTN_ID"] as Set
    static final int RETURN_LOOKUP_CHUNK_SIZE = 50
    /** Two slots per id: a return can carry the same id under both types. */
    static final int RETURN_LOOKUP_PAGE_SIZE = 100
    static final int RETURN_LOOKUP_RESERVATION = 450  // ~100 per page + 3 scalars x 100 slots, flat

    // DAR-BE-116: returns by returnId / externalId, the GraphQL replacement for the REST by-id passes.
    static final int RETURN_ID_LOOKUP_CHUNK = 50
    static final int RETURN_ID_LOOKUP_PAGE_SIZE = 100
    static final int RETURN_ID_LOOKUP_RESERVATION = 350  // 100 + 100 x 2 scalars

    static String returnIdsDocument() {
        return '''query OmsReturnIds($q: String, $first: Int, $after: String) {
  returns(query: $q, first: $first, after: $after) {
    edges { node { returnId externalId } }
    pageInfo { hasNextPage endCursor }
  }
}'''
    }

    static String returnIdentificationsDocument() {
        return '''query OmsReturnIdentifications($q: String, $first: Int) {
  returnIdentifications(query: $q, first: $first) {
    edges { node { returnId returnIdentificationTypeId idValue } }
    pageInfo { hasNextPage }
  }
}'''
    }

    /** `idValue:` takes a comma-separated `in` — 20 ids, 20 hits, one call (live 2026-10-03). */
    static Map returnIdentificationsVariables(List<String> idValues) {
        return [q: "idValue:${idValues.join(',')}".toString(), first: RETURN_LOOKUP_PAGE_SIZE]
    }

    // --- DAR-BE-040 fix 3: the returns EXTRACT over GraphQL ---------------------------------------
    // Costs measured on gorjana prod 2026-10-03: returns with identifications(4) ~27 per row
    // (first: 50 -> 1450, COST_EXCEEDED); returnItems at 14 fields x 100 -> 1500 (COST_EXCEEDED).
    // Flat passes keyed by id, never nested lists, so cost stays linear and a full page pages again.

    // Cost is charged on the REQUESTED page size and the bucket refills at 50/s, so a call costs time
    // whether or not it fills. Each lookup therefore covers a whole returns page in ONE call where it
    // fits under the 1000 cap (a return averages ~1 item, an order ~3), paging only on overflow:
    // ~2,200 per page of 25 returns, against ~4,400 with per-10-id chunks. Throttle-bound either way.
    // DAR-BE-116 (2026-10-23): sized to the 1000 one-query cap from costs logged live on gorjana (returns
    // 30 -> 690, 60 -> 1260; returnItems 50 -> 560, 100 -> 1110; the by-id lookups 100 -> 210-310). The
    // bigger bucket is spent by parallel slices, not by bigger pages.
    static final int RETURNS_PAGE_SIZE = 45
    static final int RETURNS_RESERVATION = 1000      // ~60 + 45 x 21
    static final int RETURN_ITEMS_ID_CHUNK = 85
    static final int RETURN_ITEMS_PAGE_SIZE = 85
    static final int RETURN_ITEMS_RESERVATION = 1000 // ~10 + 85 x 11
    static final int ORDERS_ID_CHUNK = 60
    static final int ORDERS_RESERVATION = 950        // 100 + 60 x ~14 (productStoreId, DAR-BE-068; identifications(3), DAR-BE-116)
    static final int ORDER_ITEMS_ID_CHUNK = 100
    static final int ORDER_ITEMS_PAGE_SIZE = 100
    static final int ORDER_ITEMS_RESERVATION = 450   // 100 + 100 x 3
    static final int ORDER_STATUSES_ID_CHUNK = 50
    static final int ORDER_STATUSES_RESERVATION = 150 // 100 + 50 x 1, three scalar fields (live RAILS 2026-10-22)
    static final int PRODUCTS_ID_CHUNK = 100
    static final int PRODUCTS_RESERVATION = 350      // 100 + 100 x 2

    /** Half-open on entryDate, like REST. It MAY contain `<`: it travels as a variable, never inside the document. */
    static String returnsWindowFilter(String fromIso, String thruIso) {
        return "entryDate:>=${fromIso} entryDate:<${thruIso}".toString()
    }

    static String returnsDocument() {
        return '''query OmsReturns($q: String, $first: Int, $after: String) {
  returns(query: $q, sortKey: RETURN_ID, first: $first, after: $after) {
    edges { node { returnId statusId entryDate returnDate externalId currencyUomId returnChannelEnumId fromPartyId
      identifications(first: 4) { returnIdentificationTypeId idValue thruDate } } }
    pageInfo { hasNextPage endCursor }
  }
}'''
    }

    /** Aditi 2026-10-24 (R8 F-100860): an exchange return's replacement order, from its responses; exchange returns only. */
    static String returnResponsesDocument() {
        return '''query OmsReturnResponses($q: String, $first: Int, $after: String) {
  returns(query: $q, first: $first, after: $after) {
    edges { node { returnId responses(first: 3) { replacementOrderId } } }
    pageInfo { hasNextPage endCursor }
  }
}'''
    }

    static final int RETURN_RESPONSES_CHUNK = 50
    static final int RETURN_RESPONSES_RESERVATION = 400

    static String returnItemsDocument() {
        return '''query OmsReturnItems($q: String, $first: Int, $after: String) {
  returnItems(query: $q, first: $first, after: $after) {
    edges { node { returnId returnItemSeqId orderId orderItemSeqId productId returnQuantity receivedQuantity
      returnPrice returnReasonId statusId returnTypeId } }
    pageInfo { hasNextPage endCursor }
  }
}'''
    }

    static String orderExternalIdsDocument() {
        return '''query OmsOrderExternalIds($q: String, $first: Int, $after: String) {
  orders(query: $q, first: $first, after: $after) {
    edges { node { orderId orderName externalId productStoreId grandTotal identifications(first: 3) { orderIdentificationTypeId idValue } } }
    pageInfo { hasNextPage endCursor }
  }
}'''
    }

    /** DAR-BE-111. An order's status by OMS order id: NetSuite's never-shipped, never-billed orders asked of HotWax. */
    static String orderStatusesDocument() {
        return '''query OmsOrderStatuses($q: String, $first: Int, $after: String) {
  orders(query: $q, first: $first, after: $after) {
    edges { node { orderId statusId orderDate } }
    pageInfo { hasNextPage endCursor }
  }
}'''
    }

    // DAR-BE-116: the ORDER_LINE_UNIT order page. Only what a unit reads from its order: the header scalars and the
    // ship groups (facilityId). No identifications or paymentPreferences: nested lists are charged per requested slot
    // per order, and they made a page 18 orders at 770 (live 2026-10-23). Header scalars cost ~1 each per order
    // (probe 2026-10-23: 100 orders x 4 scalars requested 510); two ship-group slots of two fields ~7.
    static final int UNIT_ORDERS_PAGE_SIZE = 60
    static final int UNIT_ORDERS_RESERVATION = 1000

    static String unitOrdersDocument() {
        return '''query OmsUnitOrders($q: String, $first: Int, $after: String) {
  orders(query: $q, sortKey: ORDER_DATE, reverse: false, first: $first, after: $after) {
    edges { node {
      orderId orderName externalId statusId orderTypeId orderDate grandTotal
      shipGroups(first: 2) { edges { node { shipGroupSeqId facilityId } } }
    } }
    pageInfo { hasNextPage endCursor }
  }
}'''
    }

    // DAR-BE-116: the items an ORDER_LINE_UNIT extract flattens, asked by orderId in batches (nesting
    // shipGroups.orderItems costs ~6400 a page, live 2026-09-30). Seven scalars x 100 + 100.
    static final int UNIT_ITEMS_RESERVATION = 850

    static String unitOrderItemsDocument() {
        return '''query OmsUnitOrderItems($q: String, $first: Int, $after: String) {
  orderItems(query: $q, first: $first, after: $after) {
    edges { node { orderId orderItemSeqId externalId productId quantity statusId shipGroupSeqId } }
    pageInfo { hasNextPage endCursor }
  }
}'''
    }

    static String orderItemExternalIdsDocument() {
        return '''query OmsOrderItemExternalIds($q: String, $first: Int, $after: String) {
  orderItems(query: $q, first: $first, after: $after) {
    edges { node { orderId orderItemSeqId externalId } }
    pageInfo { hasNextPage endCursor }
  }
}'''
    }

    static String productNamesDocument() {
        return '''query OmsProductNames($q: String, $first: Int, $after: String) {
  products(query: $q, first: $first, after: $after) {
    edges { node { productId internalName } }
    pageInfo { hasNextPage endCursor }
  }
}'''
    }

    /**
     * DAR-BE-116 R8: a product's NETSUITE_PRODUCT_ID. The type filter rides in front of the id key, so inVariables builds
     * `goodIdentificationTypeId:NETSUITE_PRODUCT_ID productId:a,b`; the server applies both (probed 2026-10-23, cost 170).
     */
    static final String NETSUITE_PRODUCT_ID_KEY = "goodIdentificationTypeId:NETSUITE_PRODUCT_ID productId"

    static String productNetsuiteIdsDocument() {
        return '''query OmsProductNetsuiteIds($q: String, $first: Int, $after: String) {
  goodIdentifications(query: $q, first: $first, after: $after) {
    edges { node { productId idValue thruDate } }
    pageInfo { hasNextPage endCursor }
  }
}'''
    }

    /** `key:a,b,c` — the comma `in` every flat lookup above relies on. */
    static Map inVariables(String key, List<String> ids, int first, String after) {
        Map vars = [q: "${key}:${ids.join(',')}".toString(), first: first]
        if (after) vars.put("after", after)
        return vars
    }
}
