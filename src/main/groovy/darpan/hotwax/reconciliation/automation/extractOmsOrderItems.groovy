import darpan.hotwax.oms.OmsRestSourceSupport
import darpan.hotwax.oms.OmsScriptServiceSupport

// Service edge for OMS order items at UNIT grain for the OMS-to-NetSuite hops (tri-system D4). The
// shared gate, work file, progress heartbeat, final move and excluded-records sidecar live in
// OmsScriptServiceSupport.runExtract.
OmsScriptServiceSupport.runExtract(ec, context, [
        requiredSystemEnumId  : "OMS_ORDER_ITEMS",
        workFilePrefix        : "oms-order-items-extract-",
        excludedRecordsSidecar: true,
        extract               : { Map a ->
            // Tri-system D4. Same flattener and unit record as extract#HotWaxOmsOrderLineUnits, with the
            // two DAR-BE-050 guards that only exist for the Shopify side turned OFF:
            //  - applyExchangeExclusion false: an EXC- order is a real OMS order that syncs to NetSuite the
            //    same day. The exclusion exists only for Shopify's creation-window asymmetry.
            //  - requireShopifyLineId false: the NetSuite hops key on the OMS order, so an item with no
            //    Shopify line id is still a real unit; it is emitted with that id null, and counted.
            // No keepRecordFields, for the same reason as the sibling: the unit record IS the projection.
            Map extractOptions = [
                    emitGrain             : "ORDER_LINE_UNIT",
                    windowFieldName       : windowFieldName?.toString()?.trim() ?: null,
                    applyExchangeExclusion: false,
                    requireShopifyLineId  : false,
                    orderStatusIds        : (orderStatusIds instanceof List) ? (List) orderStatusIds : null,
            ]
            OmsRestSourceSupport.extractOrdersToFile(a.sourceConfig, windowStart, windowEnd, a.workFile,
                    null, a.pageProgressListener, a.sourceFilters, extractOptions)
        },
        afterExtract          : { ->
            // Nothing is dropped for want of a Shopify id here, but the count is still surfaced: an item
            // OMS never tied to a Shopify line is worth knowing about even when it is compared.
            int keptNullShopifyIdCount = (requestMetadata?.keptNullShopifyIdCount ?: 0) as int
            if (keptNullShopifyIdCount > 0) {
                warnings = warnings + ["${keptNullShopifyIdCount} order item(s) had no Shopify order or line id; they were compared with it left empty.".toString()]
            }
        },
])
