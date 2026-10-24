import darpan.hotwax.oms.OmsGqlClient
import darpan.hotwax.oms.OmsGqlOrderPageSource
import darpan.hotwax.oms.OmsRestSourceSupport
import darpan.hotwax.oms.OmsScriptServiceSupport

// Service edge for OMS order items at UNIT grain over GraphQL (DAR-BE-116): the GraphQL twin of
// extractOmsOrderItems.groovy. Same extract options, same REST flattener and unit record; the pages come from
// OmsGqlOrderPageSource (orders with their ship groups, items by orderId in
// batches). The shared gate, work file, progress heartbeat, final move and excluded-records sidecar live in
// OmsScriptServiceSupport.runExtract.
OmsScriptServiceSupport.runExtract(ec, context, [
        requiredSystemEnumId  : "OMS_ORDER_ITEMS_GQL",
        workFilePrefix        : "oms-gql-order-items-extract-",
        excludedRecordsSidecar: true,
        extract               : { Map a ->
            // As extractOmsOrderItems: exchange exclusion OFF and items without a Shopify id KEPT, because the
            // NetSuite hops key on the OMS order. orderStatusIds and other window fields are refused by the
            // GraphQL page source rather than ignored.
            Map extractOptions = [
                    emitGrain             : "ORDER_LINE_UNIT",
                    windowFieldName       : windowFieldName?.toString()?.trim() ?: null,
                    applyExchangeExclusion: false,
                    requireShopifyLineId  : false,
                    orderStatusIds        : (orderStatusIds instanceof List) ? (List) orderStatusIds : null,
            ]
            OmsRestSourceSupport.extractOrdersToFile(a.sourceConfig, windowStart, windowEnd, a.workFile,
                    null, a.pageProgressListener, a.sourceFilters, extractOptions,
                    OmsGqlOrderPageSource.fetcher(new OmsGqlClient(OmsRestSourceSupport.toPlainMap(a.sourceConfig)),
                            // DAR-BE-119: an audit re-checking older open findings reads their orders by orderId.
                            [lookupKeys: (lookupKeys instanceof List) ? (List) lookupKeys : null, lookupKeyField: lookupKeyField]))
        },
        afterExtract          : { ->
            int keptNullShopifyIdCount = (requestMetadata?.keptNullShopifyIdCount ?: 0) as int
            if (keptNullShopifyIdCount > 0) {
                warnings = warnings + ["${keptNullShopifyIdCount} order item(s) had no Shopify order or line id; they were compared with it left empty.".toString()]
            }
        },
])
