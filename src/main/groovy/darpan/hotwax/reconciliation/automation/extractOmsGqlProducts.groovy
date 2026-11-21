import darpan.hotwax.oms.OmsGqlClient
import darpan.hotwax.oms.OmsGqlProductsPageSource
import darpan.hotwax.oms.OmsExtractSupport
import darpan.hotwax.oms.OmsScriptServiceSupport

// Service edge for HotWax products over GraphQL (DAR-BE-157): the whole sellable catalogue keyed by NETSUITE_PRODUCT_ID.
// The window is received and deliberately not applied (connector windowed N; requestMetadata.windowIgnored): presence is
// a whole-catalogue question. The shared gate, work file and final move live in OmsScriptServiceSupport.runExtract.
// DAR-BE-318 (plan 3b-2b, Task 19): no source filters and no side file of its own: darpan's Spark Keep applies the tenant's
// rules to the file and writes the side file (the connector row's excludedRecordsSidecar).
OmsScriptServiceSupport.runExtract(ec, context, [
        requiredSystemEnumId: "OMS_PRODUCTS_GQL",
        workFilePrefix      : "oms-gql-products-extract-",
        extract             : { Map a ->
            OmsGqlClient client = new OmsGqlClient(OmsExtractSupport.toPlainMap(a.sourceConfig))
            // DAR-BE-186: the conclusion brain asks for named products only (lookupKeys = productIds), never the catalogue.
            (lookupKeys instanceof List) ? OmsGqlProductsPageSource.lookupToFile(client, (File) a.workFile, (List) lookupKeys)
                    : OmsGqlProductsPageSource.extractToFile(client, (File) a.workFile)
        },
])
