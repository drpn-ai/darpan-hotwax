import darpan.hotwax.oms.OmsGqlClient
import darpan.hotwax.oms.OmsGqlProductsPageSource
import darpan.hotwax.oms.OmsRestSourceSupport
import darpan.hotwax.oms.OmsScriptServiceSupport

// Service edge for HotWax products over GraphQL (DAR-BE-157): the whole sellable catalogue keyed by NETSUITE_PRODUCT_ID.
// The window is received and deliberately not applied (connector windowed N; requestMetadata.windowIgnored): presence is
// a whole-catalogue question. The shared gate, work file and final move live in OmsScriptServiceSupport.runExtract.
OmsScriptServiceSupport.runExtract(ec, context, [
        requiredSystemEnumId: "OMS_PRODUCTS_GQL",
        workFilePrefix      : "oms-gql-products-extract-",
        excludedRecordsSidecar: true,
        extract             : { Map a ->
            OmsGqlProductsPageSource.extractToFile(new OmsGqlClient(OmsRestSourceSupport.toPlainMap(a.sourceConfig)),
                    (File) a.workFile, (List) a.sourceFilters)
        },
])
