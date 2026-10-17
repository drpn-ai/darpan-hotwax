import darpan.hotwax.oms.OmsRestSourceSupport
import darpan.hotwax.oms.OmsScriptServiceSupport

// Service edge for OMS order lines at UNIT grain (DAR-BE-050). The shared gate, work file, progress
// heartbeat, final move and excluded-records sidecar live in OmsScriptServiceSupport.runExtract.
OmsScriptServiceSupport.runExtract(ec, context, [
        requiredSystemEnumId  : "OMS_ORDER_LINE_UNITS",
        workFilePrefix        : "oms-order-line-units-extract-",
        excludedRecordsSidecar: true,
        extract               : { Map a ->
            // No keepRecordFields is passed, and that is deliberate rather than an omission: the unit
            // record IS the projection. An order-shaped keepRecordFields would strip every field a unit
            // record has, leaving a file of empty objects that still counts as a successful extract.
            //
            // applyExchangeExclusion is stated explicitly even though it already defaults to true for
            // SALES_ORDER, because at item grain the temptation is to turn it OFF (see the connector seed
            // comment, and DAR-BE-050 D2): an exchange line sits on an ORIGINAL Shopify order that may be
            // months old, so a creation-windowed Shopify sweep never sees it while OMS lands a new EXC-
            // order inside the window. Inverting this makes every exchange a false missing-in-Shopify.
            Map extractOptions = [
                    emitGrain             : "ORDER_LINE_UNIT",
                    windowFieldName       : windowFieldName?.toString()?.trim() ?: null,
                    applyExchangeExclusion: true,
                    orderStatusIds        : (orderStatusIds instanceof List) ? (List) orderStatusIds : null,
            ]
            OmsRestSourceSupport.extractOrdersToFile(a.sourceConfig, windowStart, windowEnd, a.workFile,
                    null, a.pageProgressListener, a.sourceFilters, extractOptions)
        },
        afterExtract          : { ->
            // Units the extract could not key are LOST, not excluded, so they are surfaced as a warning
            // rather than left for whoever thinks to open requestMetadata. A clean run that silently
            // dropped units is the failure this whole pair exists to prevent.
            int droppedNullLineIdCount = (requestMetadata?.droppedNullLineIdCount ?: 0) as int
            if (droppedNullLineIdCount > 0) {
                warnings = warnings + ["${droppedNullLineIdCount} order item(s) had no Shopify line id and were not compared.".toString()]
            }
        },
])
