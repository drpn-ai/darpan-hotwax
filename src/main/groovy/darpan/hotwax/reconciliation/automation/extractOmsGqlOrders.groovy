import darpan.hotwax.oms.OmsGqlClient
import darpan.hotwax.oms.OmsGqlOrderPageSource
import darpan.hotwax.oms.OmsExtractSupport
import darpan.hotwax.oms.OmsScriptServiceSupport

// Service edge for OMS sales orders over GraphQL (DAR-BE-064). The shared gate, work file, progress
// heartbeat, final move and sidecars live in OmsScriptServiceSupport.runExtract.
OmsScriptServiceSupport.runExtract(ec, context, [
        requiredSystemEnumId   : "OMS_GQL",
        workFilePrefix         : "oms-gql-orders-extract-",
        exchangeManifestSidecar: true,
        extract                : { Map a ->
            // DAR-BE-318: the window is orderDate and nothing else. Absent or orderDate reads as today; any other field is
            // refused before a call, never ignored.
            String windowField = windowFieldName?.toString()?.trim()
            if (windowField && windowField != "orderDate") {
                return [errors: ["The OMS GraphQL order extract windows on orderDate only, not '${windowField}'.".toString()],
                        warnings: [], requestMetadata: [:], recordCount: 0, dataAvailable: false]
            }
            // DAR-BE-064: same REST pipeline (sales orders, exchange scan, projection, sink, manifest), GraphQL pages.
            // DAR-BE-318: no source filters; the fields the side's Keep reads (keepRuleFields) are read, and Keep runs in
            // Spark over the file. DAR-BE-205: a conclusion lookup answers for every order it names, exchanges included.
            OmsExtractSupport.extractOrdersToFile(a.sourceConfig, windowStart, windowEnd, a.workFile,
                    a.keepRecordFields, a.pageProgressListener, (keepRuleFields instanceof List) ? (List) keepRuleFields : null,
                    OmsExtractSupport.lookupExtractOptions(context.lookupKeys, context.conclusionLookup),
                    OmsGqlOrderPageSource.fetcher(new OmsGqlClient(OmsExtractSupport.toPlainMap(a.sourceConfig)),
                            // DAR-BE-120: read by key for a conclusion lookup.
                            [lookupKeys: (lookupKeys instanceof List) ? (List) lookupKeys : null, lookupKeyField: lookupKeyField,
                             // DAR-BE-239: the fields the run's rules read, selected even without a projection.
                             readFields: (readFields instanceof List) ? (List) readFields : null]),
                    // DAR-BE-318: a dataset refresh lists every exchange order of its 60 days; runs keep the default
                    (exchangeManifestMaxEntries as Integer) ?: OmsExtractSupport.EXCHANGE_MANIFEST_MAX_ENTRIES)
        },
])
