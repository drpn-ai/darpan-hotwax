import darpan.hotwax.oms.OmsGqlClient
import darpan.hotwax.oms.OmsGqlProductsPageSource
import darpan.hotwax.oms.OmsExtractSupport
import darpan.hotwax.oms.OmsScriptServiceSupport

// Service edge for HotWax products over GraphQL (DAR-BE-157): the whole sellable catalogue keyed by NETSUITE_PRODUCT_ID.
// The window is received and deliberately not applied (connector windowed N; requestMetadata.windowIgnored): presence is
// a whole-catalogue question. The shared gate, work file and final move live in OmsScriptServiceSupport.runExtract.
OmsScriptServiceSupport.runExtract(ec, context, [
        requiredSystemEnumId: "OMS_PRODUCTS_GQL",
        workFilePrefix      : "oms-gql-products-extract-",
        excludedRecordsSidecar: true,
        extract             : { Map a ->
            OmsGqlClient client = new OmsGqlClient(OmsExtractSupport.toPlainMap(a.sourceConfig))
            // DAR-BE-186: the conclusion brain asks for named products only (lookupKeys = productIds), never the catalogue.
            (lookupKeys instanceof List) ? OmsGqlProductsPageSource.lookupToFile(client, (File) a.workFile, (List) lookupKeys)
                    : OmsGqlProductsPageSource.extractToFile(client, (File) a.workFile, (List) a.sourceFilters)
        },
])
