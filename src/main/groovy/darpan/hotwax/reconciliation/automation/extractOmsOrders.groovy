import darpan.hotwax.oms.OmsRestSourceSupport
import darpan.hotwax.oms.OmsScriptServiceSupport

// Service edge for /rest/s1/oms/orders. The shared gate, work file, progress heartbeat, final move and
// sidecars live in OmsScriptServiceSupport.runExtract; this script supplies only what is its own.
OmsScriptServiceSupport.runExtract(ec, context, [
        requiredSystemEnumId   : "OMS",
        workFilePrefix         : "oms-orders-extract-",
        exchangeManifestSidecar: true,
        extract                : { Map a ->
            OmsRestSourceSupport.extractOrdersToFile(a.sourceConfig, windowStart, windowEnd, a.workFile,
                    a.keepRecordFields, a.pageProgressListener, a.sourceFilters)
        },
])
