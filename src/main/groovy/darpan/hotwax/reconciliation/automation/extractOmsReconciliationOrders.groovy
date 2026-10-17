import darpan.hotwax.oms.OmsRestSourceSupport
import darpan.hotwax.oms.OmsScriptServiceSupport

// Service edge for /rest/s1/oms/reconciliationOrders (DAR-BE-018).
//
// The same edge as extractOmsOrders.groovy — same tenant gate, output location, atomic .partial move,
// progress heartbeat and exchange-manifest sidecar (all in OmsScriptServiceSupport.runExtract) — and
// differs only by passing extractOptions.reconEndpoint. Everything that varies between the two
// endpoints lives in OmsRestSourceSupport, so the automation edge, the on-disk {records, metadata}
// contract and the sidecar consumers stay identical across both connectors.
OmsScriptServiceSupport.runExtract(ec, context, [
        requiredSystemEnumId   : "OMS_RECON_ORDERS",
        workFilePrefix         : "oms-recon-orders-extract-",
        exchangeManifestSidecar: true,
        extract                : { Map a ->
            // The single switch that selects the reconciliationOrders contract: its own path, half-open
            // orderDateFrom/orderDateThru bounds, hasMore-driven termination, no client-side SALES_ORDER
            // re-filter, and inline exchange orders dropped into the sidecar instead of scanned for.
            Map extractOptions = [reconEndpoint: true]
            OmsRestSourceSupport.extractOrdersToFile(a.sourceConfig, windowStart, windowEnd, a.workFile,
                    a.keepRecordFields, a.pageProgressListener, a.sourceFilters, extractOptions)
        },
])
