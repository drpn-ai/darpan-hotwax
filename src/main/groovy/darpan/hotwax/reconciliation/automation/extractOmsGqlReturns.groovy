import darpan.hotwax.oms.OmsGqlClient
import darpan.hotwax.oms.OmsGqlReturnsPageSource
import darpan.hotwax.oms.OmsRestSourceSupport
import darpan.hotwax.oms.OmsReturnsSourceSupport
import darpan.hotwax.oms.OmsScriptServiceSupport

// Service edge for OMS returns over GraphQL (connector OMS_RETURNS_GQL, DAR-BE-040 fix 3).
//
// The same edge as extractOmsReturns.groovy (all in OmsScriptServiceSupport.runExtract) and the same
// REST returns pipeline; only the page source differs. Everything returns-specific lives in
// OmsReturnsSourceSupport, not here.
OmsScriptServiceSupport.runExtract(ec, context, [
        requiredSystemEnumId: "OMS_RETURNS_GQL",
        workFilePrefix      : "oms-gql-returns-extract-",
        safeFileName        : { Object name -> OmsReturnsSourceSupport.safeReturnsFileName(name) },
        extract             : { Map a ->
            // DAR-BE-040 fix 3: the REST returns pipeline (filters, join-key fallback, projection, sink, metadata)
            // fed by GraphQL pages, which still carry the returns reconciliationReturns stopped serving.
            OmsReturnsSourceSupport.extractReturnsToFile(a.sourceConfig, windowStart, windowEnd,
                    a.workFile, a.keepRecordFields, a.pageProgressListener, a.sourceFilters,
                    [pageFetcher: OmsGqlReturnsPageSource.fetcher(new OmsGqlClient(OmsRestSourceSupport.toPlainMap(a.sourceConfig)),
                            [includeReturnsWithoutShopifyRef: includeReturnsWithoutShopifyRef == true,
                             onlyCancelledReturns: onlyCancelledReturns == true])])
        },
])
