# HotWax OMS over GraphQL

Component: `darpan-hotwax`. Connector rows and rules-board pills: component `darpan` (`data/`);
the HotWax-specific connector settings: this component's `data/HotWaxConnectorSeedData.xml`.

Every HotWax OMS read goes over the OMS GraphQL endpoint, `{baseUrl}/rest/s1/graphql`: the
window extracts and the by-id lookups alike. The REST transport and its extracts
(`extract#HotWaxOmsOrders`, `extract#HotWaxOmsReconciliationOrders`, `extract#HotWaxOmsReturns`,
connector rows `OMS`, `OMS_RECON_ORDERS`, `OMS_RETURNS`) were retired by DAR-BE-052
(2026-11-02); the one-time migration `OMS_REST_RETIRED` (`migration/OmsRestRetirement.groovy`)
moved every stored source to its GraphQL endpoint and deleted the REST connector rows, pills and
endpoint grants.

This page covers the transport and what every OMS read shares. Orders, order lines and products:
[order-extraction.md](order-extraction.md). Returns and their lookups:
[return-extraction.md](return-extraction.md).

## Connectors

| Connector (`systemEnumId`) | Endpoint label | Extract service | Source config type |
|---|---|---|---|
| `OMS_GQL` | Orders | `extract#HotWaxOmsGqlOrders` | `HOTWAX_OMS_GQL` |
| `OMS_ORDER_ITEMS_GQL` | Order lines | `extract#HotWaxOmsGqlOrderItems` | `HOTWAX_OMS_GQL_ORDER_ITEMS` |
| `OMS_RETURNS_GQL` | Returns | `extract#HotWaxOmsGqlReturns` | `HOTWAX_OMS_GQL_RETURNS` |
| `OMS_PRODUCTS_GQL` | Products | `extract#HotWaxOmsGqlProducts` | `HOTWAX_OMS_GQL_PRODUCTS` |

All four services are in `service/reconciliation/HotWaxOmsExtractionServices.xml` and implement
an interface that fixes their in/out parameters: `extract#HotWaxOmsOrdersContract` (orders,
products), `extract#HotWaxOmsOrderUnitsContract` (order lines), `extract#HotWaxOmsReturnsContract`
(returns). The service names are distinct because the connector registry resolves a row by its
extract service name.

## Lookups

Point reads by id, over the same client. None is narrowed by a run window.

| Service | Called by | Answers |
|---|---|---|
| `lookup#HotWaxOmsOrdersByExternalId` | exchange pair verify; `OMS_GQL` `pairLookupServiceName`, `OMS_RETURNS_GQL` `orderStateLookupServiceName` | every OMS order carrying each Shopify `externalId`, any date or type, with whether it is an exchange order (`OmsPairLookupSupport`) |
| `lookup#HotWaxOmsReturnsByExternalId` | returns verify; `OMS_RETURNS_GQL` `lookupServiceName` | which return ids OMS has: see [return-extraction.md](return-extraction.md#by-id-lookup) |
| `lookup#HotWaxOmsReturnLinesByOrderExternalId` | returns verify | the order lines OMS booked a return against, by Shopify order id (`OmsReturnLinesLookupSupport`) |
| `lookup#HotWaxOmsOrderStatuses` | NetSuite audit checks | each order's `statusId` by OMS order id, or its item statuses, ship-group facilities or sales shipments (`OmsOrderStatusLookupSupport`) |
| `read#HotWaxOmsChangedOrderIds` | NetSuite audit reads by change | the orders an item status was written for since `changedSince`, from the status timeline |

What every lookup shares (`OmsIdLookup`): plain-identifier keys, ids asked in chunks, every page
read to exhaustion, and two guarantees. A failed call classifies nothing (`ok=false` with the
lookup's empty shape), so no record is called missing because HotWax could not be asked. An answer
naming none of the asked ids is an ignored filter, never "HotWax has none of them".

## Config and auth

Every connector uses the tenant's `darpan.hotwax.HotWaxOmsRestSourceConfig` row
(`configParameterName=omsRestSourceConfigId`; the REST name stays for the stored rows and the
contract) and the same health check, `facade.HotWaxOmsFacadeServices.probe#HotWaxOmsConnection`.
There is one credential per OMS. The entity and its facade services are described in
[order-extraction.md](order-extraction.md#source-config).

Before a read, the service edge (`OmsScriptServiceSupport`) checks that the tenant may use the
config and that the config is enabled for the connector's endpoint
(`SourceEndpointAccessSupport.isEndpointEnabled`). An endpoint with no explicit setting counts as
enabled.

## OmsGqlClient

`darpan.hotwax.oms.OmsGqlClient` sends one POST per document (`{query, variables}` JSON) through
darpan's `OutboundHttpClient`.

- **Headers** come from `OmsExtractSupport.buildHeaders(config)`: `BEARER`, `BASIC`, `API_KEY`
  (default header `api_key`) and the config's `headersJson`.
- **Outbound policy.** The URL is re-validated against `OutboundHttpPolicy` at request time.
- **Timeouts.** `connectTimeoutSeconds` and `readTimeoutSeconds` from the config row (column
  defaults 30 and 60).
- **Errors.** Every failure throws `OmsGqlException` carrying a stable code. There is no "returned
  no data" success path:
  - a GraphQL `errors` list with entries: the first entry's `extensions.code` (for example
    `THROTTLED`, `COST_EXCEEDED`), or `GRAPHQL_ERROR`;
  - an `errors` value that is a plain string (Moqui's REST layer rejecting the call):
    `REST_REJECTED`;
  - a body that is not JSON: `UNPARSEABLE`;
  - transient failures that outlast their retries: `TRANSIENT`.
  An empty `errors: []` is success; moqui-gql sends it on every successful query.

## Cost, throttle and retry

moqui-gql charges every query against a cost bucket and reports it in
`extensions.cost.throttleStatus` (`currentlyAvailable`, `restoreRate`). Cost is charged on every
**requested** list slot, so a page costs the same whether or not it fills, and a single query may
not exceed 1000. An exhausted bucket is answered with HTTP 200, no data and a `THROTTLED` code,
which, unhandled, is indistinguishable from "this window had no records".

1. **Page sizes come from measured cost** (`OmsGqlQueries`). For orders: about 100 per page plus,
   per order, 8 for the always-selected fields, 1.5 per extra scalar and 4 per payment-preference
   slot; the page is the largest that fits a 950 budget (`QUERY_COST_BUDGET`), capped at 100 (the
   server's `first` ceiling). Returns and every follow-up use fixed, measured sizes (for example
   43 returns per page, `returnItems` 85, `orders` 60 ids, `orderItems` 100), with flat id-keyed
   follow-ups instead of nested lists, so cost stays linear. A page the server still refuses with
   `COST_EXCEEDED` is halved and asked again.
2. **`OmsGqlThrottleGovernor`**, one per OMS URL and shared by every read in the JVM, is a token
   bucket that may go into debt: each call takes its reservation when it is let go, so callers
   queue behind one another, and each answer resets the balance to what the server reports less
   the reservations still out (the server charges the actual cost, about 70% of the reservation).
   It waits the deficit off at the restore rate plus a 500 ms margin, and lets at most 6 calls be
   in flight at once.
3. **`OmsGqlTenantPacer`** (DAR-BE-328) sits in front of the governor during a tenant's peak
   period and keeps that tenant inside its share of the URL's bucket, so a paced tenant never
   slows the others.
4. **Retry.** A `THROTTLED` answer is retried after the governor's wait plus jitter, until 300 s
   have been spent waiting. A timeout, refused connection or 429/502/503/504 is retried up to 5
   attempts (`maxPageAttempts`) with a linear 5 s backoff. Every other error fails the read at
   once.

Two reuse layers keep repeated reads off the bucket:

- **`OmsGqlSharedRead`** (DAR-BE-116): checks that want the same raw records (same endpoint, kind,
  window and query shape) share one read, spilled to an NDJSON file; each caller then applies its
  own filters, projection and counts. A read that throws fails every waiting caller and is
  dropped, and the short reuse window keeps a re-run from seeing a previous run's data.
- **`OmsGqlLookupCache`** (DAR-BE-129): by-id answers (product names, facility names, NetSuite
  ids) are cached per tenant, config and endpoint for a while, absent ids included, instead of
  being asked on every page.

## Failure rules (every read)

- Any `OmsGqlException` fails the whole extract with its code in the message. It never produces
  an empty or partial file.
- A response without the expected connection and `edges` list is `MALFORMED`, never an empty page.
- `hasNextPage` with no `endCursor`, or a cursor seen before, is `MALFORMED` (no silent
  truncation, no loops).
- Hitting the page ceiling (20,000 pages, `MAX_ORDERS_PAGE_COUNT` / `MAX_RETURNS_PAGE_COUNT`) with
  more pages reported fails rather than truncates.
- Every follow-up lookup pages to exhaustion. A truncated items page would understate
  `returnTotal`; a truncated order page would emit null Shopify ids that read as real differences.
- Extracts stream into a `.partial` work file and move it to its final name only after the whole
  window succeeds, so a failure never leaves a partial file where reconciliation could read it.

## Keep: the reads do not filter

Since DAR-BE-318 no OMS extract applies a tenant's exclusion rules. A window read writes every
record it reads, stamped where a built-in rule needs it (`isExchangeOrder` on orders,
`omsReturnClass` on returns), and darpan's Spark Keep sets records aside over the written file:
first the built-in lines the connector row declares (`keepLinesJson`), then the tenant's rules.
The set-aside rows are kept for the conclusions and counted in darpan's Keep summary. A read by
key (`lookupKeys`) still leaves exchange orders and excluded returns out itself, so a re-check
answers as the window read's Keep would.

Per-connector settings in `data/HotWaxConnectorSeedData.xml`, set on darpan's connector rows by
primary key: `excludedRecordsSidecar` (`N` for orders and returns, `Y` for order lines and
products), `keepLinesJson`, `extractOptionsJson` (`OMS_GQL`: `keepExchangeOrders`,
`withOrderItems`) and `servingProjectionServiceName`
(`project#HotWaxOmsKeptRecords`, which trims a kept-copy slice with the same code the extracts
trim with, `OmsKeptFieldsProjection`).

## Tests

`src/test/groovy/darpan/hotwax/oms/`: `OmsGqlClientTests`, `OmsGqlThrottleGovernorTests`,
`OmsGqlTenantPacerTests`, `OmsGqlSharedReadTests`, `OmsGqlLookupCacheTests`, `OmsGqlQueriesTests`,
`OmsGqlOrderPageSourceTests`, `OmsGqlOrderGrainAssemblerTests`, `OmsGqlConnectorWiringTests`,
`OmsOrderLineUnitSupportTests`, `OmsGqlProductsPageSourceTests`, `OmsGqlReturnsPageSourceTests`,
`OmsGqlReturnAssemblerTests`, `OmsGqlReturnsConnectorWiringTests`, `OmsReturnsGqlLookupTests`.
The REST retirement migration: `OmsRestRetirementTests`.
