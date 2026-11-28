# HotWax OMS Order Extraction

Component: `darpan-hotwax`

The tenant's HotWax OMS connection, and the three extracts that read orders and products from it
over GraphQL: `OMS_GQL` (orders), `OMS_ORDER_ITEMS_GQL` (order lines at unit grain) and
`OMS_PRODUCTS_GQL` (products). The transport, cost pacing, lookups and failure rules every OMS
read shares are in [graphql-connector.md](graphql-connector.md); returns are in
[return-extraction.md](return-extraction.md).

The REST order extracts (`extract#HotWaxOmsOrders` on `/rest/s1/oms/orders` and
`extract#HotWaxOmsReconciliationOrders` on `/reconciliationOrders`) were retired by DAR-BE-052
(2026-11-02).

## Source Config

Entity: `darpan.hotwax.HotWaxOmsRestSourceConfig` (`entity/HotWaxOmsConfigEntities.xml`). A
tenant's HotWax OMS connection, read over GraphQL only; the REST name stays for its stored rows and
contract. One row serves every OMS connector.

Facade services (`service/facade/HotWaxOmsFacadeServices.xml`):

- `list#HotWaxOmsRestSourceConfigs`, `save#HotWaxOmsRestSourceConfig`,
  `delete#HotWaxOmsRestSourceConfig`: tenant-scoped by `companyUserGroupId`. Save and delete need
  active-tenant write access and check the stored owner. Save keeps an encrypted secret when its
  input is blank on update; list and save return secrets as flags only (`hasPassword`,
  `hasApiToken`), never in clear text.
- `probe#HotWaxOmsConnection`: connection diagnostics, one `orders(first: 1)` GraphQL query, run
  stage by stage. It is also every OMS connector's health check.
- `run#OmsGraphqlAgentQuery` and `get#OmsGraphqlSchema` (DAR-CLI-003): one fresh, read-only query
  a tenant user's agent wrote, and the schema it may ask about.

Columns: `omsRestSourceConfigId`, `baseUrl` (for example `https://dev-maarg.hotwax.io`),
`timeZone` (default `UTC`, used by setup and source metadata displays), `authType` (`NONE`,
`BASIC`, `BEARER`, `API_KEY`; `API_KEY` sends the encrypted token in the `api_key` header),
`username`, `password` (encrypted), `apiToken` (encrypted), `headersJson`,
`connectTimeoutSeconds` (default 30), `readTimeoutSeconds` (default 60), `isActive`,
`description`, `canReadOrders`.

- `ordersPath` and `returnsPageSize` are REST-era columns: nothing reads them, and the facade
  stopped taking them in contract version 4 (DAR-BE-052).
- `canReadOrders` is still saved and listed; its retirement waits for the `ENDPOINT_ACCESS`
  migration to be applied everywhere. Which endpoints a config serves is decided by the endpoint
  grants (`SourceEndpointAccessSupport`), not by this flag.

## extract#HotWaxOmsGqlOrders (`OMS_GQL`)

Edge: `reconciliation/automation/extractOmsGqlOrders.groovy`. Page source:
`OmsGqlOrderPageSource`. Document builder: `OmsGqlQueries`. Pipeline: `OmsExtractSupport`
(`extractOrdersToFile`).

**Window.** `orderDate:>=<from> orderDate:<<thru>` (half-open), with `orderTypeId:SALES_ORDER` in
the search string, so non-sales orders are filtered by the server (`excludedNonSalesOrderCount`
reads 0; pagination records `orderTypeFilteredServerSide`). `windowStart` / `windowEnd` accept
`Timestamp`, `Date`, ISO-8601 text, SQL timestamp text or epoch milliseconds. A multi-day window
runs as parallel day slices, 4 at a time, on the endpoint's one cost bucket.

**Per batch.** Order pages are buffered; every 1000 orders (and at the end of a slice) one paged
`orderItemAssocs` query fetches their `EXCHANGE` associations. `OmsGqlOrderGrainAssembler` grafts
those onto each order, so the exchange scan in `OmsExtractSupport` finds them (a GraphQL order node
carries no association data), derives the payment fields, and converts `orderDate` to epoch millis
and `grandTotal` to a number.

**Fields.** Always selected: `orderId`, `orderName`, `externalId`, `statusId`, `orderTypeId`,
`orderDate`, `grandTotal`, plus the payment preferences. Optional scalars: `statusFlowId`,
`currencyUomId`, `productStoreId`, `salesChannelEnumId` (since the 2026-10-28 moqui-gql upgrade),
`orderItemCount`, `lastUpdatedTxStamp`. Optional nested: `identifications`, `shipGroups`.
Derived: `hasPaymentPreference` (`Y`/`N`), `paymentMethodTypeIds` (sorted, comma-joined),
`paymentPreferenceSlotsFull`, `isCompleted`, `paymentRefNums`, `itemQuantity`, `orderItems`.

The document selects only what the run needs: the projection (`keepRecordFields`), the fields the
side's Keep reads (`keepRuleFields`) and the fields its rules read (`readFields`). A field outside
the lists above fails the extract before any request, because a rule on a field the document never
selected would set nothing aside.

**Refused inputs.** A `windowFieldName` other than `orderDate` is refused rather than ignored.

**Other read modes.**

- `lookupKeys` (+ `lookupKeyField` `orderId` or `externalId`): read only these orders, whatever
  their date. Exchange orders are left out unless `conclusionLookup` is set.
- `changedSince` (DAR-BE-356): the orders in the window with an item whose status changed at or
  after this instant, so an audit compares an older order that changed again.
- `withOrderItems` (DAR-BE-368, an `OMS_GQL` extract option): also read each order's items
  (Shopify line id, SKU, quantity, status) so an audit finding can list them.
- `keepExchangeOrders` (DAR-BE-345, an extract option): the side compares exchange orders; a
  re-read by key then writes them flagged instead of leaving them out.

**Exchange orders.** A window read writes every sales order, flagged `isExchangeOrder` `Y` when the
order carries an `EXCHANGE` item association, else `N`. The `OMS_GQL` row's built-in Keep line sets
the `Y` orders aside unless the side keeps exchange orders. The exchange orders are also listed in
a sidecar `<extract-name>.exchange-manifest.json` (ids and amounts only: `omsOrderId`,
`externalId`, `orderName`, `toOrderId`, `grandTotal`, `orderDate`, `statusId`), which the exchange
pair verify stage reads to check them against their original order. It lists at most 500 entries
by default (`exchangeManifestMaxEntries`; a 60-day dataset read lists up to 50,000), and
`filters.exchangeManifestTruncated` marks a window that hit the cap.

**Output.** Records are appended page by page to a `.partial` work file, then `metadata` is
written once counts are known and the file is moved to its final name
(`oms-orders-{fromMillis}-{thruMillis}.json` by default) in
`runtime://datamanager/reconciliation-runs/{automationExecutionId}/{timestamp}/`.
`metadata.sourceType` is `HOTWAX_OMS_ORDERS`; `requestMetadata.filters` carries
`excludedNonSalesOrderCount`, `excludedExchangeOrderCount`, `flaggedExchangeOrderCount` and
`exchangeManifestTruncated`. Credentials and header values are never written, only header names.
The service returns `fileLocation`, `fileName`, `exchangeManifestFileLocation`, `recordCount`,
`requestMetadata`, `warnings` and `errors`.

**Pills and lookups.** The rules-board pills are the connector's `SourceSystemConnectorField` rows
(darpan `data/SourceSystemConnectorSeedDataFields.xml`, plus "Exchange order" from this component's
`HotWaxConnectorSeedData.xml`); a rule can only name a top-level key of the order record. The pair
lookup is `lookup#HotWaxOmsOrdersByExternalId` (see [graphql-connector.md](graphql-connector.md#lookups)).

## extract#HotWaxOmsGqlOrderItems (`OMS_ORDER_ITEMS_GQL`)

Edge: `reconciliation/automation/extractOmsGqlOrderItems.groovy`. Flattener:
`OmsOrderLineUnitSupport`.

One record per order-line **unit** (DAR-BE-116), the OMS half of the Shopify unit presence pair
(`extract#ShopifyOrderLineUnits` in `shopify-darpan`). OMS explodes a Shopify line of quantity N
into N single-unit items, and the compare is a set anti-join, so the key is
`(shopifyOrderId, shopifyLineId, unitOrdinal)`: at line grain a short import would still match.
Orders are read with their ship groups and the items by `orderId` in batches, each placed under its
ship group; a multi-day window runs as parallel day slices.

Two deliberate differences from the Shopify side: the exchange exclusion is off, and items without
a Shopify id are kept (`keptNullShopifyIdCount`). `recordCount` counts units. `requestMetadata`
also carries `droppedNullLineIdCount` and `nonUnitQuantityCount`, units the extract could not key.

Refused: `orderStatusIds` and any `windowFieldName` other than `orderDate`, and a Keep field on
`salesChannelEnumId`, which this read does not carry. Other read modes: `lookupKeys`
(`omsOrderId`) and `changedSince`, as on the orders extract.

## extract#HotWaxOmsGqlProducts (`OMS_PRODUCTS_GQL`)

Edge: `reconciliation/automation/extractOmsGqlProducts.groovy`. Page source:
`OmsGqlProductsPageSource`.

HotWax's side of the NetSuite to HotWax product reconciliation (DAR-BE-157). Every product and
every `NETSUITE_PRODUCT_ID` identification are swept whole (no window: a product missing since
August never falls inside a day's window), joined into one record per sellable product: not
virtual, `FINISHED_GOOD` or `MARKETING_PKG_PICK`, no `salesDiscontinuationDate`.

The compare key, `nsKey`, is the NetSuite item internal id. A product without one keys
`HOTWAX-ONLY:<productId>`, so it surfaces as missing in NetSuite. A NetSuite id on several products
keys the lowest `productId`; every other product gets `DUPLICATE:<id>:<productId>`, because
duplicate compare keys collapse to a clean match. `lookupKeys` (`productId`, DAR-BE-186) reads
named products whatever their state.

## Groovy Justification

Service XML owns the public contracts. Groovy is kept where XML actions would hide non-trivial
branching or duplicate integration logic.

- `oms/OmsExtractSupport.groovy`: the order pipeline (sales-order limit, exchange scan and flag,
  unit flattening hand-off, projection, counts, exchange manifest, streaming sink) and the config
  helpers (header construction, safe config maps).
- `oms/OmsGqlOrderPageSource.groovy`, `OmsGqlOrderGrainAssembler.groovy`, `OmsGqlQueries.groovy`,
  `OmsGqlProductsPageSource.groovy`, `OmsOrderLineUnitSupport.groovy`: GraphQL documents, paging,
  cost-derived page sizes and record assembly.
- `oms/OmsScriptServiceSupport.groovy`: the service edge every extract and lookup script shares
  (tenant gate, endpoint gate, work file, progress heartbeat, final move, manifest sidecar), so each
  connector keeps only a thin script.
- `facade/settings/saveOmsRestSourceConfig.groovy`: validation that depends on stored encrypted
  secrets, auth-mode requirements, timezone, outbound-URL (SSRF) policy, `headersJson` validation
  (invalid or non-object JSON, oversized values, control characters), tenant writability, secret
  preservation on blank updates, and safe response shaping.
- `facade/settings/listOmsRestSourceConfigs.groovy`: safe-row projection, credential redaction,
  tenant filtering, multi-field search and bounded pagination.
- `facade/settings/deleteOmsRestSourceConfig.groovy`: resolves the active tenant, checks write
  access against the stored owner, and removes the config's endpoint grants.
- `facade/settings/probeOmsRestSourceConnection.groovy`: the staged connection probe.

## Automation Integration

An API-range automation source (`sourceTypeEnumId=AUT_SRC_API`) or a manual API-backed saved run
reaches these extracts through darpan's connector registry: the source's config type names the
connector row (`HOTWAX_OMS_GQL`, `HOTWAX_OMS_GQL_ORDER_ITEMS`, `HOTWAX_OMS_GQL_PRODUCTS`), the row
names the extract service, and the source names the config:

```json
{
  "extractServiceName": "reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsGqlOrders",
  "parameters": {
    "omsRestSourceConfigId": "GORJANA_OMS"
  }
}
```

darpan sends `windowStart` / `windowEnd`, the keep and rule fields, and the side's extract
options; the extract returns the file location and counts `execute#Automation` records. Tenant
exclusion rules are never sent: darpan's Spark Keep applies them over the written file (see
[graphql-connector.md](graphql-connector.md#keep-the-reads-do-not-filter)).
