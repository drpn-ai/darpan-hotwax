import darpan.hotwax.oms.OmsRestSourceSupport
import darpan.hotwax.oms.OmsScriptServiceSupport

// Service edge for OMS transfer orders. The shared gate, work file, progress heartbeat and final move
// live in OmsScriptServiceSupport.runExtract.
OmsScriptServiceSupport.runExtract(ec, context, [
        requiredSystemEnumId: "OMS_TRANSFER_ORDERS",
        workFilePrefix      : "oms-transfer-orders-extract-",
        extract             : { Map a ->
            Map extractOptions = [
                    orderTypeId    : "TRANSFER_ORDER",
                    windowFieldName: windowFieldName?.toString()?.trim() ?: null,
                    orderStatusIds : (orderStatusIds instanceof List) ? (List) orderStatusIds : null,
            ]
            OmsRestSourceSupport.extractOrdersToFile(a.sourceConfig, windowStart, windowEnd, a.workFile,
                    a.keepRecordFields, a.pageProgressListener, a.sourceFilters, extractOptions)
        },
])
