import darpan.hotwax.oms.OmsReturnsSourceSupport
import darpan.hotwax.oms.OmsScriptServiceSupport

// Service edge for /rest/s1/oms/reconciliationReturns (DAR-BE-018).
//
// The same edge as the order extracts — same tenant gate, output location, atomic .partial move and
// progress heartbeat (all in OmsScriptServiceSupport.runExtract) — and differs only in calling the
// returns support class and writing no exchange-manifest sidecar (returns carry no exchange
// association). Everything returns-specific lives in OmsReturnsSourceSupport, not here.
OmsScriptServiceSupport.runExtract(ec, context, [
        requiredSystemEnumId: "OMS_RETURNS",
        workFilePrefix      : "oms-returns-extract-",
        safeFileName        : { Object name -> OmsReturnsSourceSupport.safeReturnsFileName(name) },
        extract             : { Map a ->
            OmsReturnsSourceSupport.extractReturnsToFile(a.sourceConfig, windowStart, windowEnd,
                    a.workFile, a.keepRecordFields, a.pageProgressListener, a.sourceFilters, [:])
        },
])
