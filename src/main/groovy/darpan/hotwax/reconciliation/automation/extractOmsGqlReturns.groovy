import darpan.hotwax.oms.OmsGqlClient
import darpan.hotwax.oms.OmsGqlReturnsPageSource
import darpan.hotwax.oms.OmsExtractSupport
import darpan.hotwax.oms.OmsReturnsSourceSupport
import darpan.hotwax.oms.OmsScriptServiceSupport

// Service edge for OMS returns over GraphQL (connector OMS_RETURNS_GQL, DAR-BE-040 fix 3).
//
// The shared edge (OmsScriptServiceSupport.runExtract) over the returns pipeline the retired REST read used
// (DAR-BE-052); only the page source is GraphQL. Everything returns-specific lives in OmsReturnsSourceSupport.
OmsScriptServiceSupport.runExtract(ec, context, [
        requiredSystemEnumId: "OMS_RETURNS_GQL",
        workFilePrefix      : "oms-gql-returns-extract-",
        safeFileName        : { Object name -> OmsReturnsSourceSupport.safeReturnsFileName(name) },
        extract             : { Map a ->
            // DAR-BE-318: the window is entryDate and nothing else. Absent or entryDate reads as today; any other
            // field is refused before a call, never ignored.
            String windowField = windowFieldName?.toString()?.trim()
            if (windowField && windowField != "entryDate") {
                return [errors: ["The OMS GraphQL returns extract windows on entryDate only, not '${windowField}'.".toString()],
                        warnings: [], requestMetadata: [:], recordCount: 0, dataAvailable: false]
            }
            // DAR-BE-040 fix 3: the REST returns pipeline (join-key fallback, projection, sink, metadata) fed by GraphQL
            // pages, which still carry the returns reconciliationReturns stopped serving. DAR-BE-318 (plan 3b-2b): no
            // source filters; a window read writes every return with its omsReturnClass, and darpan keeps in Spark.
            OmsReturnsSourceSupport.extractReturnsToFile(a.sourceConfig, windowStart, windowEnd,
                    a.workFile, a.keepRecordFields, a.pageProgressListener,
                    [pageFetcher: OmsGqlReturnsPageSource.fetcher(new OmsGqlClient(OmsExtractSupport.toPlainMap(a.sourceConfig)),
                            [includeReturnsWithoutShopifyRef: includeReturnsWithoutShopifyRef == true,
                             onlyCancelledReturns: onlyCancelledReturns == true,
                             // DAR-BE-192: each return's credit (returnCredit), for a check against its credit memo.
                             withReturnCredit: withReturnCredit == true,
                             // DAR-BE-119: an audit re-checking older open findings reads their returns by id.
                             lookupKeys: (lookupKeys instanceof List) ? (List) lookupKeys : null, lookupKeyField: lookupKeyField])])
        },
])
