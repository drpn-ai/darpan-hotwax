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

    static final int MAX_PAGE_SIZE = 50
    static final int COST_BUDGET = 950               // page cost ceiling, under the 1000 hard max
    static final int HARD_MAX_COST = 1000
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

    static Set<String> requiredFields(Set<String> keepFieldSet, List<Map> excludeRules) {
        if (keepFieldSet == null) return null
        Set<String> required = new LinkedHashSet<String>(keepFieldSet)
        (excludeRules ?: []).each { Map rule ->
            String field = (rule?.get("fieldExpression") as String)?.trim()
            if (field) required.add(field)
        }
        return required
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
        int pageSize = Math.max(1, Math.min(MAX_PAGE_SIZE, (int) Math.floor((COST_BUDGET - PAGE_OVERHEAD) / perOrder)))
        int reservation = Math.min(HARD_MAX_COST, estimatedPageCost(pageSize, PAYMENT_PREF_SLOTS, extraScalars, nested) + 50)

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
}
