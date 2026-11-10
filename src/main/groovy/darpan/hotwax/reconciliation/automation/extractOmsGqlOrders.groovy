import darpan.hotwax.oms.OmsGqlClient
import darpan.hotwax.oms.OmsGqlOrderPageSource
import darpan.hotwax.oms.OmsExtractSupport
import darpan.hotwax.oms.OmsScriptServiceSupport

// Service edge for OMS sales orders over GraphQL (DAR-BE-064). The shared gate, work file, progress
// heartbeat, final move and sidecars live in OmsScriptServiceSupport.runExtract.
OmsScriptServiceSupport.runExtract(ec, context, [
        requiredSystemEnumId   : "OMS_GQL",
        workFilePrefix         : "oms-gql-orders-extract-",
        excludedRecordsSidecar : true,
        exchangeManifestSidecar: true,
        extract                : { Map a ->
            // DAR-BE-064: same REST pipeline (exclusions, filters, projection, sink, manifest), GraphQL pages.
            // DAR-BE-205: a conclusion lookup answers for every order it names, exchanges included.
            OmsExtractSupport.extractOrdersToFile(a.sourceConfig, windowStart, windowEnd, a.workFile,
                    a.keepRecordFields, a.pageProgressListener, a.sourceFilters,
                    OmsExtractSupport.lookupExtractOptions(context.lookupKeys, context.conclusionLookup),
                    OmsGqlOrderPageSource.fetcher(new OmsGqlClient(OmsExtractSupport.toPlainMap(a.sourceConfig)),
                            // DAR-BE-120: read by key for a conclusion lookup.
                            [lookupKeys: (lookupKeys instanceof List) ? (List) lookupKeys : null, lookupKeyField: lookupKeyField]))
        },
])
