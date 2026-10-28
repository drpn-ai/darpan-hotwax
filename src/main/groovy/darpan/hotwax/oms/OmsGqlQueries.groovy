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
 */
class OmsGqlQueries {

    /** moqui-gql's `first` ceiling (FIRST_REQUIRED, max 100). */
    static final int MAX_PAGE_SIZE = 100
    /**
     * DAR-BE-116: the per-query cost a page is sized to. Live gorjana 2026-10-23: the BUCKET was raised to
     * 20000 at 2000/s, but one query is still capped at 1000 (60 returns -> 1260 COST_EXCEEDED, 100
     * returnItems -> 1110). The page sources halve the page on COST_EXCEEDED, so a lower cap costs one call.
     */
    static final int QUERY_COST_BUDGET = 950
    /** Two slots: 1-2 OPPs cover 97% of gorjana orders, and k=3 would force the page down to 40. */
    static final int PAYMENT_PREF_SLOTS = 2
    /**
     * DAR-BE-153: a rule on the OPPs' Shopify transaction ids reads five. An exchange adds OPPs to its ORIGINAL order (the
     * card capture, a top-up, and the exchange tenders that move them), so gorjana's #GOR197298413 holds four, the most
     * in the 300-order probe; a read that fills every slot cannot tell whether one more exists, so four would leave it
     * unchecked.
     */
    static final int REFNUM_PAYMENT_PREF_SLOTS = 5
    static final double PAGE_OVERHEAD = 100d
    static final double HEADER_COST = 8.0d           // the ALWAYS_FIELDS, per order
    static final double EXTRA_SCALAR_COST = 1.5d     // each further header scalar, per order (conservative)
    static final double PER_SLOT_COST = 4.0d
    /** The OPP fields the presence read has always asked. */
    static final List<String> PAYMENT_SLOT_FIELDS = ["paymentMethodTypeId", "statusId", "maxAmount"].asImmutable()
    static final int ASSOC_PAGE_SIZE = 50
    static final int ASSOC_RESERVATION = 350         // measured 300 at first: 50

    /** Top-level scalars proven to exist on the deployed Order type (OmsGraphQlLiveProbe, 2026-09-30). */
    static final Set<String> HEADER_FIELDS = ["orderId", "orderName", "externalId", "statusId", "statusFlowId",
            "orderTypeId", "orderDate", "grandTotal", "currencyUomId", "productStoreId", "orderItemCount",
            "lastUpdatedTxStamp"] as Set
    /** Always selected: the compare key, the built-in non-sales filter, and the REST keepFieldsBase. */
    static final List<String> ALWAYS_FIELDS = ["orderId", "orderName", "externalId", "statusId", "orderTypeId",
            "orderDate", "grandTotal"]
    /** Computed by OmsGqlOrderGrainAssembler from paymentPreferences and statusId, which are always selected. */
    static final Set<String> DERIVED_FIELDS = ["hasPaymentPreference", "paymentMethodTypeIds", "paymentPreferenceSlotsFull",
            "isCompleted", "paymentRefNums"] as Set
    /** Nested lists, selected only when something reads them, with their measured per-order cost. */
    static final Map<String, Map> NESTED_FIELDS = [
            identifications: [selection: "identifications(first: 5) { orderIdentificationTypeId idValue }", cost: 18.0d],
            shipGroups     : [selection: "shipGroups(first: 3) { edges { node { shipGroupSeqId facilityId shipmentMethodTypeId } } }", cost: 12.5d],
            // DAR-UI-081 (Aditi: "all findings should have basic order info"): the bill-to customer's name (gorjana
            // 2026-10-26: 60 orders 780 -> 910 requested, ~2.2 an order).
            billToCustomer : [selection: "billToCustomer { firstName lastName }", cost: 2.2d],
            // Aditi 2026-10-24 (R1 F-100794): HotWax marks a duplicate order with a note ("duplicate order"). Estimated.
            notes          : [selection: "notes(first: 5) { note { noteInfo } }", cost: 25.0d],
            // Aditi 2026-10-24: NetSuite bills a fulfilled item within 24 hours; the order's statuses say when it was fulfilled.
            statuses       : [selection: "statuses(first: 12) { statusId statusDatetime orderItemSeqId }", cost: 50.0d],
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
        boolean refNums = wanted.contains("paymentRefNums")
        int slots = refNums ? REFNUM_PAYMENT_PREF_SLOTS : PAYMENT_PREF_SLOTS
        // DAR-BE-153: HotWax bills every slot asked for, filled or not, so a read of the ids alone asks manualRefNum alone;
        // anything else that reads a payment row's fields keeps the full row.
        List<String> slotFields = refNums && !wanted.contains("paymentMethodTypeIds") ? ["manualRefNum"] :
                (PAYMENT_SLOT_FIELDS + (refNums ? ["manualRefNum"] : []))
        double slotCost = slotCost(slotFields)
        double perOrder = perOrderCost(slots, extraScalars, nested, slotCost)
        int budget = QUERY_COST_BUDGET
        int pageSize = Math.max(1, Math.min(MAX_PAGE_SIZE, (int) Math.floor((budget - PAGE_OVERHEAD) / perOrder)))
        int reservation = Math.min(budget + 50, estimatedPageCost(pageSize, slots, extraScalars, nested, slotCost) + 50)

        String selection = (scalars as List).join(" ") +
                "\n      paymentPreferences(first: ${slots}) { ${slotFields.join(' ')} }" +
                nested.collect { "\n      " + NESTED_FIELDS[it].selection }.join("")
        String document = """query OmsOrderGrain(\$q: String, \$first: Int, \$after: String) {
  orders(query: \$q, sortKey: ORDER_DATE, reverse: false, first: \$first, after: \$after) {
    edges { node {
      ${selection}
    } }
    pageInfo { hasNextPage endCursor }
  }
}"""
        return [document: document, pageSize: pageSize, reservation: reservation, paymentSlots: slots, paymentRefNums: refNums]
    }

    /**
     * What one requested payment slot costs: 1 + its fields. Measured: 3 fields -> 4 (DAR-BE-064); 7 fields, 25 orders x 6
     * slots COST_EXCEEDED and 15 x 4 not (gorjana 2026-10-28).
     */
    static double slotCost(List<String> fields) {
        return 1.0d + (fields ?: []).size()
    }

    /** Requested (= actual) cost of one orders page under the measured model. */
    static int estimatedPageCost(int pageSize, int slots, int extraScalars, List<String> nested, double slotCost = PER_SLOT_COST) {
        return (int) Math.ceil(PAGE_OVERHEAD + pageSize * perOrderCost(slots, extraScalars, nested, slotCost))
    }

    private static double perOrderCost(int slots, int extraScalars, List<String> nested, double slotCost = PER_SLOT_COST) {
        return HEADER_COST + extraScalars * EXTRA_SCALAR_COST + slots * slotCost +
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
        return pageVariables("orderId:${orderIds.join(',')} orderItemAssocTypeId:EXCHANGE".toString(), ASSOC_PAGE_SIZE, after)
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

    // Cost is charged on the REQUESTED page size, so each lookup covers a whole returns page in ONE call
    // where it fits under the 1000 cap (a return averages ~1 item, an order ~3), paging only on overflow.
    // DAR-BE-116 (2026-10-23): sized to the 1000 one-query cap from costs logged live on gorjana (returns
    // 30 -> 690, 60 -> 1260; returnItems 50 -> 560, 100 -> 1110; the by-id lookups 100 -> 210-310). The
    // bigger bucket is spent by parallel slices, not by bigger pages.
    // DAR-BE-129: 45 asked 1020 on gorjana 2026-10-26 (over the 1000 cap: each such page was refused, halved and asked again).
    static final int RETURNS_PAGE_SIZE = 43
    static final int RETURNS_RESERVATION = 1000      // ~60 + 43 x 21.3
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
    edges { node { orderId orderName externalId productStoreId grandTotal orderDate billToCustomer { firstName lastName } identifications(first: 3) { orderIdentificationTypeId idValue } } }
    pageInfo { hasNextPage endCursor }
  }
}'''
    }

    /** DAR-BE-129. The exchange pair verify's orders by Shopify externalId: every type, every date. */
    static final int PAIR_ORDERS_RESERVATION = 550   // 100 + 60 x 7 scalars

    static String pairOrdersDocument() {
        return '''query OmsPairOrders($q: String, $first: Int, $after: String) {
  orders(query: $q, first: $first, after: $after) {
    edges { node { orderId externalId orderName orderTypeId statusId grandTotal orderDate } }
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

    /**
     * DAR-BE-129: the orders read one audit run shares between its order checks and its order-line checks: the OMS_GQL
     * connector's keepFieldsBase, which already carries everything a unit reads from its order (header, bill-to customer,
     * ship groups with facility and method). An order read whose own plan is this one joins the shared read.
     */
    static final Set<String> SHARED_ORDER_FIELDS = ["orderId", "orderName", "externalId", "grandTotal", "orderDate", "statusId",
            "currencyUomId", "billToCustomer", "shipGroups"] as Set

    static Map sharedOrdersPlan() { return orderGrainPagePlan(SHARED_ORDER_FIELDS) }

    /**
     * True when the shared orders read carries every field an order read needs (null: no projection and no filter).
     * Such a read joins the shared one: a page of 26 full orders costs more than its own 53 lean ones, but in an audit
     * run the order-line check reads the shared one anyway (2026-10-26: gorjana's paid-has-OPP read the month again,
     * 1,948 calls, because it asked only for the always-read fields).
     */
    static boolean sharedOrdersCover(Set<String> requiredFields) {
        // DAR-BE-153: paymentRefNums needs manualRefNum from six slots, which the shared read does not ask for.
        return (requiredFields ?: []).every { it != "paymentRefNums" &&
                (ALWAYS_FIELDS.contains(it) || SHARED_ORDER_FIELDS.contains(it) || DERIVED_FIELDS.contains(it)) }
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

    // DAR-UI-081: the names of the facilities a page's ship groups name, by id (gorjana 2026-10-26: 100 asked, 210 requested).
    static final int FACILITIES_ID_CHUNK = 100
    static final int FACILITIES_RESERVATION = 350

    static String facilityNamesDocument() {
        return '''query OmsFacilityNames($q: String, $first: Int, $after: String) {
  facilities(query: $q, first: $first, after: $after) {
    edges { node { facilityId facilityName } }
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
        return pageVariables("${key}:${ids.join(',')}".toString(), first, after)
    }
}
