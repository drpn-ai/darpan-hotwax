# HotWax OMS GraphQL Connectors

Component: `darpan-hotwax`. Registry rows and seed data: component `darpan`.

Two extract connectors read HotWax OMS over its GraphQL endpoint, `{baseUrl}/rest/s1/graphql`,
instead of the REST list endpoints. Each sits **beside** a REST connector, not in place of it, and
writes the same `{records, metadata}` file, so a rule set moves between them by switching its
source, with no rule change.

| Connector (`systemEnumId`) | Extract service | REST twin | Source config type |
|---|---|---|---|
| `OMS_GQL` ("Orders and payments") | `extract#HotWaxOmsGqlOrders` | `OMS` / `extract#HotWaxOmsOrders` | `HOTWAX_OMS_GQL` |
| `OMS_RETURNS_GQL` ("Returns") | `extract#HotWaxOmsGqlReturns` | `OMS_RETURNS` / `extract#HotWaxOmsReturns` | `HOTWAX_OMS_GQL_RETURNS` |

Both services are in `service/reconciliation/HotWaxOmsExtractionServices.xml` and `implement` their
REST twin's service, so the in/out parameters are identical. The service names are distinct
because the connector registry resolves a row by its extract service name.

Status: released in darpan-hotwax v0.9.0 with darpan 2.0.0, whose `data/upgrade-data.xml` carries
the seed rows. The two tags deploy together: v0.8.0 lacks these extract services, and v0.9.0 calls
darpan 2.0 classes (`SourceFilterSupport.evaluate`, `ExcludedRecordsSidecar`). Backlog: DAR-BE-064 (orders),
DAR-BE-040 fix 3 (returns), DAR-BE-068 (origin-order store filter), DAR-BE-052 (the wider
REST-to-GraphQL migration).

## When to use GraphQL vs REST

| Need | Use |
|---|---|
| Returns that have no refund yet (`RETURN_REQUESTED`, anything pending) | `OMS_RETURNS_GQL`. REST `/reconciliationReturns` serves refunded returns only, so on `OMS_RETURNS` every pending return reads as missing in HotWax. |
| Filter returns by the store of the order they came from | `OMS_RETURNS_GQL`. `orderProductStoreId` exists on the GraphQL record only. |
| Order presence with payment-preference fields (`hasPaymentPreference`, `paymentMethodTypeIds`) | `OMS_GQL`. REST `/oms/orders` carries no payment preferences. |
| Order extract by status only (no date window), `orderStatusIds`, or a window field other than `orderDate` | REST `OMS`. The GraphQL order extract refuses these. |
| Filter or project on an order field the GraphQL document does not carry (for example `salesChannelEnumId`) | REST `OMS`. The GraphQL extract refuses the run rather than silently not filtering. |
| An OMS without the GraphQL upgrade | REST. On such an OMS the GraphQL extracts fail; they never return an empty file. |
| Speed | REST. GraphQL is throttle-bound: a 6-hour gorjana returns window took 258 s over GraphQL against 17 s over REST (2026-10-03). |

## Config and auth

Both connectors use the same `darpan.hotwax.HotWaxOmsRestSourceConfig` row as REST
(`configParameterName=omsRestSourceConfigId`), the same `remoteId` (`HOTWAX_ORDERS_API`) and the
same health check (`probe#HotWaxOmsConnection`). There is no separate GraphQL credential.

Before extracting, the service edge (`OmsScriptServiceSupport.runExtract`) checks that the tenant
may use the config and that the config is enabled for the connector's endpoint (`OMS_GQL` or
`OMS_RETURNS_GQL`, via `SourceEndpointAccessSupport`). An endpoint with no explicit setting counts
as enabled, so both are available on every active OMS config once their seed rows are loaded.

## OmsGqlClient

`darpan.hotwax.oms.OmsGqlClient` sends one POST per document to `{baseUrl}/rest/s1/graphql`
(`{query, variables}` JSON).

- **Headers** come from `OmsRestSourceSupport.buildHeaders(config)` — the same `BEARER`, `BASIC`,
  `API_KEY` (with its configured header) and `headersJson` handling as REST. A config that works
  for REST works here.
- **Outbound policy.** The URL is re-validated against `OutboundHttpPolicy` at request time, as on
  the REST path.
- **Timeouts.** `connectTimeoutSeconds` and `readTimeoutSeconds` from the config row (column
  defaults 30 and 60).
- **Errors.** Every failure throws `OmsGqlException` carrying a stable code. There is no "returned
  no data" success path:
  - a GraphQL `errors` list with entries -> the first entry's `extensions.code` (for example
    `THROTTLED`, `COST_EXCEEDED`), or `GRAPHQL_ERROR`;
  - an `errors` value that is a plain string (Moqui's REST layer rejecting the call) ->
    `REST_REJECTED`;
  - a body that is not JSON -> `UNPARSEABLE`;
  - a non-2xx status with no error body -> `HTTP_<status>`.
  An empty `errors: []` is success; moqui-gql sends it on every successful query.

## Cost, throttle and retry

moqui-gql charges every query against a cost bucket and reports the bucket back in
`extensions.cost.throttleStatus` (`currentlyAvailable`, `restoreRate`). Cost is charged on every
**requested** list slot, so a page costs the same whether or not it fills. A single query may not
exceed 1000. An exhausted bucket is answered with HTTP 200, no data and a `THROTTLED` error code —
which, unhandled, is indistinguishable from "this window had no records".

Three pieces keep the extract honest:

1. **Page sizes are derived from measured cost** (`OmsGqlQueries`). For orders, cost is about
   100 per page plus, per order, 8 for the always-selected fields, 1.5 per extra scalar, 4 per
   payment-preference slot, and about 18 for `identifications(5)` or 12.5 for `shipGroups(3)` when
   selected. The page size is the largest that fits under a 950 budget, capped at 50. Returns use
   fixed sizes measured the same way: 25 returns per page, with flat id-keyed follow-up queries
   (`returnItems`, `orders`, `orderItems`, `products`) instead of nested lists, so cost stays
   linear.
2. **`OmsGqlThrottleGovernor`** records the bucket from every response. Before each call the
   client reserves the expected cost; if the bucket does not cover it, the client sleeps for
   `deficit / restoreRate` seconds plus a 500 ms margin. Before the first response nothing is
   known, so the first call does not wait.
3. **Retry on `THROTTLED` only.** Another extract sharing the same OMS budget can drain the bucket
   between observation and call, so a `THROTTLED` answer is retried up to 3 times, each after the
   governor's wait. Every other error fails the extract at once.

## Failure rules (both extracts)

- Any `OmsGqlException` fails the whole extract with the error code in the message. It never
  produces an empty or partial file.
- A response without the expected connection and `edges` list is `MALFORMED`, never an empty page.
- `hasNextPage` with no `endCursor`, or a cursor seen before, is `MALFORMED` (no silent
  truncation, no loops).
- Hitting the page ceiling (20,000 pages) with more pages reported fails rather than truncates.
  The ceiling keys `maxOrdersPageCount` / `maxReturnsPageCount` are read but are not config
  columns, so the ceiling is always the default.
- Every follow-up lookup pages to exhaustion. A truncated items page would understate
  `returnTotal`; a truncated order page would emit null Shopify ids that read as real differences.

## extract#HotWaxOmsGqlOrders (`OMS_GQL`)

Edge: `reconciliation/automation/extractOmsGqlOrders.groovy`. Page source:
`OmsGqlOrderPageSource`. Document builder: `OmsGqlQueries.orderGrainPagePlan`.

The REST order pipeline runs unchanged; only the pages come from GraphQL. Exclusions, configured
filters, projection, the work file, the exchange manifest and the excluded-records sidecar are the
REST code (`OmsRestSourceSupport.extractOrdersToFile` with a GraphQL page fetcher).

Per page:

1. One `orders` query, windowed `orderDate:>=<from> orderDate:<<thru>` (half-open) with
   `orderTypeId:SALES_ORDER`, sorted by order date. Non-sales orders are filtered server-side, so
   `excludedNonSalesOrderCount` is 0 where REST counts them; pagination metadata records
   `orderTypeFilteredServerSide: true`.
2. One paged `orderItemAssocs` query for the page's order ids with type `EXCHANGE`.
3. `OmsGqlOrderGrainAssembler` grafts those associations onto each order so the REST exchange
   scan finds them (without it every exchange order would re-enter the compare), derives the
   payment fields, and converts `orderDate` to epoch millis and `grandTotal` to a number, as REST
   sends them.

**Fields.** Always selected: `orderId`, `orderName`, `externalId`, `statusId`, `orderTypeId`,
`orderDate`, `grandTotal`, plus `paymentPreferences(first: 2)`. Optional scalars:
`statusFlowId`, `currencyUomId`, `productStoreId`, `orderItemCount`, `lastUpdatedTxStamp`.
Optional nested: `identifications`, `shipGroups`. Derived: `hasPaymentPreference` (`Y`/`N`),
`paymentMethodTypeIds` (sorted, comma-joined), `paymentPreferenceSlotsFull` (true when both slots
were used, so there may be more preferences than were read).

The document selects only what the run needs: the projection plus every source-filter field. A
projection or filter naming a field outside the lists above fails the extract before any request,
because a filter on a field the document never selected would exclude nothing.

**Refused inputs** (the run fails with a message saying to stay on REST): no date window,
`orderStatusIds`, or a `windowFieldName` other than `orderDate`.

Registry row (`darpan/data/SourceSystemConnectorSeedData.xml`): `keepFieldsBase` and
`pairLookupServiceName` are the same as `OMS`, so the pair lookup still uses REST
(`lookup#HotWaxOmsOrdersByExternalId`). Rules-board pills
(`SourceSystemConnectorFieldSeedData.xml`): Order ID, Order name, Shopify order ID, Order total,
Order date, Order status, Store (`productStoreId`), Has payment, Payment methods. There is no
`salesChannelEnumId` pill: the deployed Order type does not expose it.

The REST `OMS` row stays enabled. A rule set migrates by changing its source config type from
`HOTWAX_OMS_REST` to `HOTWAX_OMS_GQL`, and reverts the same way.

## extract#HotWaxOmsGqlReturns (`OMS_RETURNS_GQL`)

Edge: `reconciliation/automation/extractOmsGqlReturns.groovy`. Page source:
`OmsGqlReturnsPageSource`. Record builder: `OmsGqlReturnAssembler`.

It feeds the REST returns pipeline (`OmsReturnsSourceSupport.extractReturnsToFile` with a GraphQL
page fetcher): configured filters, the `shopifyReturnId` join-key fallback, projection and the
file writer are shared with REST. The output file name defaults to the REST name,
`oms-returns-{fromMillis}-{thruMillis}.json`.

Per page of 25 returns, windowed `entryDate:>=<from> entryDate:<<thru>` (half-open):

1. `OmsGqlReturnAssembler.classify` applies the exclusions REST applies server-side:
   - `RETURN_CANCELLED` -> excluded, counted in `excludedCancelledCount`. REST never served a
     cancelled return, and the Shopify side suppresses closed unrefunded returns (DAR-BE-027).
   - no `externalId` and no Shopify return identification -> excluded, counted in
     `excludedNoShopifyRefCount`.
2. For the kept returns only, flat id-keyed lookups: `returnItems` by return id; `orders`
   (`externalId`, `productStoreId`) and `orderItems` (`externalId`) by origin order id; `products`
   (`internalName`, which is REST's `sku`) by product id.
3. `OmsGqlReturnAssembler.build` writes the REST record: `returnId`, `shopifyReturnId`,
   `externalId`, `orderExternalId`, `statusId`, `entryDate` (epoch millis), `returnTotal`
   (sum of `returnPrice x returnQuantity`), `currencyUomId`, `returnChannelEnumId`, `items[]`, and
   `orderProductStoreId`. A lookup miss leaves a field null, never a guess.

`shopifyReturnId` is the active Shopify return identification, `SHOPIFY_RTN_ID` preferred over
`SHOPIFY_RETURN_ID` (the newer type, live since 2026-09), and only when the value is numeric:
`SHOPIFY_RETURN_ID` also holds `WARRANTY-…` values that name no Shopify object.

Parity: on gorjana prod (2026-10-03), 110 settled returns built this way matched REST on every
field, items included.

Metadata adds `excludedCancelledCount` and `transport: "GRAPHQL"`; neither appears on REST.

### orderProductStoreId is GraphQL-only (DAR-BE-068)

`Return.productStoreId` is not populated on the OMS, so a return's store is taken from its origin
order (the order `orderExternalId` names). The REST returns record has no order id to reach it, so
this field exists on `OMS_RETURNS_GQL` only. It is a filter field, not a compare field: it is not in
`keepFieldsBase`, filters run before projection, so an include/exclude rule can use it and the
compared record stays identical to REST's. It is absent on a return with no items, so an
`INCLUDE_IN` rule on it drops those returns.

Pills: the nine `OMS_RETURNS` pills plus "Store" (`orderProductStoreId`). The registry row matches
`OMS_RETURNS` in everything but transport: same `keepFieldsBase`, filter parameter, by-id lookup
(`lookup#HotWaxOmsReturnsByExternalId`) and order-state lookup. A rule set migrates by changing its
source config type from `HOTWAX_OMS_REST_RETURNS` to `HOTWAX_OMS_GQL_RETURNS`.

## GraphQL in the returns by-id lookup

`lookup#HotWaxOmsReturnsByExternalId` is REST-first for every connector. Ids neither REST pass
resolves go to a GraphQL `returnIdentifications(idValue:)` query, 20 ids per call, accepting only
the Shopify return identification types. Ids it cannot check come back as `unresolvedIds`, never
as missing. Details: `rest-return-extraction.md`, "By-id lookup".

## Seeds

All in component `darpan`, `data/`:

| File | Rows |
|---|---|
| `DarpanSystemSourceSeedData.xml` | Enumerations `OMS_GQL` (code `HOTWAX_GQL`) and `OMS_RETURNS_GQL` (code `HOTWAX_GQL_RETURNS`), both under parent `OMS`. Required for the endpoint gate and the automation-source foreign key. |
| `SourceSystemConnectorSeedData.xml` | The two `SourceSystemConnector` rows (`sendUrlTemplate={baseUrl}/rest/s1/graphql`). |
| `SourceSystemConnectorFieldSeedData.xml` | Nine pills for `OMS_GQL`, ten for `OMS_RETURNS_GQL`. |
| `upgrade-data.xml` | The same rows, copied verbatim, in the 2.0.0 delta. Loading them is inert until a rule set points at one of the new source types. |

## Tests

`src/test/groovy/darpan/hotwax/oms/`: `OmsGqlClientTests`, `OmsGqlThrottleGovernorTests`,
`OmsGqlQueriesTests`, `OmsGqlOrderPageSourceTests`, `OmsGqlOrderGrainAssemblerTests`,
`OmsGqlConnectorWiringTests`, `OmsGqlReturnsPageSourceTests`, `OmsGqlReturnAssemblerTests`,
`OmsGqlReturnsConnectorWiringTests`, `OmsReturnsGqlLookupTests`. The paid-has-OPP rule is pinned in darpan
(`RuleDiffSupportTests`, `GorjanaMonthCloseChecksDataTests`).
