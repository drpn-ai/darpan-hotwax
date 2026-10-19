# HotWax OMS REST Return Extraction

Component: `darpan-hotwax` (OMS side), with `shopify-darpan` (Shopify side) and `darpan`
(connector registry, compare, and the return presence filter) as the other two legs of returns
reconciliation (DAR-BE-018).

This page covers the REST returns connector, `OMS_RETURNS`. Its GraphQL twin, `OMS_RETURNS_GQL`,
builds the same record from a different transport and is described in `graphql-connector.md`.
Orders are in `rest-order-extraction.md`.

## Read this first: REST serves refunded returns only

`/rest/s1/oms/reconciliationReturns` returns **refunded** returns only. A return that exists in
OMS but has no refund yet (`RETURN_REQUESTED` above all) is not in the response. Measured on the
gorjana OMS on 2026-10-03: none of the 268 `RETURN_REQUESTED` returns created since 2026-10-01
came back from the endpoint, while GraphQL `returns` carried all of them.

Consequences:

- A returns run on `OMS_RETURNS` reports every pending return that Shopify has as "missing in
  HotWax". The connector's `endpointLabel` is "Refunded returns" for that reason.
- To compare pending returns, point the rule set at `OMS_RETURNS_GQL` (source config type
  `HOTWAX_OMS_REST_RETURNS` -> `HOTWAX_OMS_GQL_RETURNS`). Same config row, same record shape, no
  rule change. It needs an OMS with the GraphQL upgrade.
- The by-id lookup (below) runs a GraphQL pass for the ids REST cannot find, for the same reason.

## Source Config

Returns reuse the order extractor's config entity, `darpan.hotwax.HotWaxOmsRestSourceConfig` —
same `baseUrl`, same credentials, same auth modes (`NONE`, `BASIC`, `BEARER`, `API_KEY`). There is
no `canReadReturns` flag analogous to `canReadOrders`; a config usable for orders is usable for
returns.

One column is returns-specific: `returnsPageSize` (default 500), the rows requested per page.
`resolveReturnsPageSize` takes an explicit caller option first, then this column, then the
default, and clamps the result to 1..1000. Lower it when OMS cannot build a full page inside its
gateway timeout: a page that times out at the gateway is not fixed by retrying, only by asking for
less. The page ceiling (`maxReturnsPageCount`) is read by the code but is not a column, so it is
always 20,000 pages (`MAX_RETURNS_PAGE_COUNT`).

## Extractor

Service: `reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsReturns`
(`darpan-hotwax/service/reconciliation/HotWaxOmsExtractionServices.xml`)
Support class: `darpan.hotwax.oms.OmsReturnsSourceSupport`
Edge script: `darpan/hotwax/reconciliation/automation/extractOmsReturns.groovy`, which hands off
to the shared `OmsScriptServiceSupport.runExtract` (tenant gate, work file, progress heartbeat,
final move).

```text
GET {baseUrl}/rest/s1/oms/reconciliationReturns?returnDateFrom=<startEpochMillis>&returnDateThru=<endEpochMillis>&pageIndex=<n>&pageSize=<n>
```

The path is **pinned by the extractor** (`DEFAULT_RETURNS_PATH`), not read from the config's
`ordersPath` column — same pattern as the `/reconciliationOrders` endpoint. `buildReturnsUrl`
appends the window and paging params; header/auth construction and the HTTP call are reused from
`OmsRestSourceSupport`.

**Window.** `returnDateFrom`/`returnDateThru` are half-open `[from, thru)` on the OMS
`ReturnHeader.entryDate` — the return's own creation timestamp, not the linked order's date. A
live-probed exchange showed the order placed 07-16, the return initiated 07-23 and the warehouse
processing it 07-28, so windowing on the order date would put a return outside every window its
order appears in.

**Termination.** The endpoint reports `hasMore`, so none of the legacy orders-path heuristics
apply — no shrinking-page probe, no repeated-page detection. The loop stops when `hasMore` is
`false`, capped at 20,000 pages as a runaway guard.

**Response key.** Records arrive under `body.returns`. `body.returnsCount` and
`body.excludedNoShopifyRefCount` are read from the same top-level object.

**Credentials.** Config parsing uses `OmsRestSourceSupport.toPlainMap`, not the UI-safe
`safeConfigMap`, which redacts secrets into `hasUsername`/`hasPassword`/`hasApiToken` booleans.
The extractor's output never includes credentials or authorization header values — only header
names.

### Page retry: 429, 502, 503, 504

Each page is retried in place for the statuses a retry can fix (`requestReturnsPage`,
`RETRYABLE_STATUS_CODES`):

- `429`, `502`, `503`, `504` — up to 3 attempts per page, including the first, with a linear
  backoff of 2 s x attempt number. Each retry adds a warning to the extract.
- `500` and every other non-2xx status fail at once. A 500 is a deterministic server fault, so
  repeating it only adds load and delay.

The retry is page-level on purpose. A failure that escapes the page aborts the sink and deletes
the part-written file, and the automation's own retry re-fetches the window from page 0. The
failure message names the page, the attempts spent and the server's own error detail: page 0
failing says the window is too wide for the endpoint; a deep page failing points at offset decay.

The orders REST path has no equivalent retry (see `rest-order-extraction.md`).

### Join-key fallback

`externalId` is the join key to Shopify, and OMS leaves it **blank until a refund exists**
(67 of 772 rows in the 2026-08-20 live run). `applyJoinKeyFallback` copies `shopifyReturnId` into
a blank `externalId` before filtering and projection. This tracks Shopify's own grain: Shopify
emits a RETURN row while a return is unrefunded and switches to the REFUND row once a refund
exists, so an unrefunded OMS return is named on the Shopify side by its return id.

Do not invert this into "prefer `shopifyReturnId`". For a refunded return the two fields hold
different Shopify objects (refund id vs return id), and Shopify stops emitting the RETURN row once
refunded, so preferring `shopifyReturnId` would break every refunded match.

### Nested `items[]` contract

Return line items arrive nested inside their header as `items[]` and are written through nested,
never flattened. `projectRecord` always keeps `items` regardless of `keepRecordFields`, so a
projection naming only header fields cannot strip line data.

### Configurable record exclusion

There is no built-in `SALES_ORDER`/`EXCHANGE`-style client-side filter for returns. The endpoint
excludes returns with no Shopify reference **server-side** (`excludedNoShopifyRefCount`).
Everything Darpan configures on top is a **client-side** per-field rule, run through the same
connector-agnostic `darpan.reconciliation.source.SourceFilterSupport` (component `darpan`) that
orders use. The expected rule excludes returns whose `returnChannelEnumId` never has a Shopify
counterpart.

Semantics match the orders contract:

- Field names are matched case-sensitively against top-level keys; values case-insensitively.
- `EXCLUDE_IN` keeps a record that lacks the field. `INCLUDE_IN` drops it and counts it as
  field-absent.
- `SourceFilterSupport.parseRules` runs before any HTTP call; a malformed rule fails pre-flight
  with no request sent.

**Which fields are offered.** The rules-board pills for `OMS_RETURNS` are its
`SourceSystemConnectorField` rows (`darpan/data/SourceSystemConnectorFieldSeedData.xml`):
`returnId`, `shopifyReturnId`, `externalId`, `orderExternalId`, `statusId`, `entryDate`,
`returnTotal`, `currencyUomId`, `returnChannelEnumId`. `returnChannelEnumId` is offered because
the endpoint's server-side projection includes it, so a rule drawn on it sees a value. `items[]`
is not offered: it is a nested array, and `SourceFilterSupport` matches only top-level keys.

The `OMS_RETURNS` connector row declares `filterParameterName="sourceFilters"` — **required**,
because exclusion dispatch keys on this attribute; without it the rules board hides the
exclusion control.

### Count semantics

`excludedNoShopifyRefCount` is server-side — those returns never reach Darpan's filter chain.
Configured exclusions are client-side and run only on the population that survives the server
filter, so the two counts cannot overlap.

```json
{
  "records": [],
  "metadata": {
    "filters": {
      "excludedNoShopifyRefCount": 4,
      "serverReportedReturnsCount": 7,
      "configuredExclusions": [
        {
          "sequenceNum": 1,
          "fieldExpression": "returnChannelEnumId",
          "operator": "EXCLUDE_IN",
          "values": ["POS_RETURN_CHANNEL"],
          "excludedCount": 1,
          "fieldAbsentCount": 0
        }
      ]
    }
  }
}
```

(`OmsReturnsSourceSupport.buildMetadata`.)

- Every configured rule appears, including one that matched nothing (`excludedCount: 0`).
- `configuredExclusions` is absent entirely, not `[]`, when no rules are configured.
- `excludedCancelledCount` and `transport` appear only on the GraphQL transport.
- All counts are diagnostic run metadata only and are not shown in the UI.

### Failure handling

The extractor streams to a `.partial` work file and never leaves a well-formed-looking but
truncated document: an HTTP or JSON-parse error aborts the sink and the file-mode caller deletes
the partial file. `extractReturnsToFile`'s result omits the `records` key on every path, success
or failure — matching `extractOrdersToFile`.

## By-id lookup

Service: `reconciliation.HotWaxOmsExtractionServices.lookup#HotWaxOmsReturnsByExternalId`
(`OmsReturnsSourceSupport.lookupReturnsByExternalId`). The saved-run verification pass calls it to
confirm or refute "missing in OMS" differences. Output: `ok`, `foundIds`, `missingIds`,
`unresolvedIds`, `errors`.

- **Escapes the run window.** It queries a 10-year window. OMS windows on `entryDate` (return
  created) while Shopify windows on the event's `createdAt` (refunded), so a return opened weeks
  before its refund is in one window and outside the other.
- **Two REST passes, both id fields.** Ids go in chunks of 20. Each chunk is queried by
  `externalId`; the ids that did not resolve are queried again by `shopifyReturnId`. `externalId`
  alone misses every return with no refund yet (0 of 6 live `RETURN_REQUESTED` rows resolved by
  `externalId`, 6 of 6 by `shopifyReturnId`).
- **Echo check.** Every returned record is checked against the ids actually requested, so a filter
  the endpoint silently ignores cannot read as a match.
- **GraphQL pass.** Ids neither REST pass found go to GraphQL `returnIdentifications(idValue:)`,
  accepting only the Shopify return identification types (`SHOPIFY_RETURN_ID`,
  `SHOPIFY_RTN_ID`). Ids GraphQL cannot check — a failed call, an ignored filter, a truncated page,
  or an id with characters the search string cannot carry — come back in `unresolvedIds`:
  unchecked, never confirmed missing.
- **Transient retry (DAR-BE-070).** A REST lookup request that gets `429`, `502`, `503` or `504`
  waits 60 s and asks again, up to 3 retries. Any other failure is immediate.
- **Fails closed.** A REST transport error, non-2xx, unparseable body or ignored filter returns
  `ok=false` with both id lists empty. It never classifies an id as missing because it could not
  ask.

## Automation Integration

Connector row: `OMS_RETURNS` (`darpan/data/SourceSystemConnectorSeedData.xml`)

```text
systemEnumId=OMS_RETURNS
extractServiceName=reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsReturns
expectedSourceConfigType=HOTWAX_OMS_REST_RETURNS
configEntityName=darpan.hotwax.HotWaxOmsRestSourceConfig
endpointLabel=Refunded returns
keepFieldsBase=returnId,shopifyReturnId,externalId,orderExternalId,statusId,entryDate,returnTotal,currencyUomId,returnChannelEnumId
filterParameterName=sourceFilters
lookupServiceName=reconciliation.HotWaxOmsExtractionServices.lookup#HotWaxOmsReturnsByExternalId
orderStateLookupServiceName=reconciliation.HotWaxOmsExtractionServices.lookup#HotWaxOmsOrdersByExternalId
```

`shopifyReturnId` must stay in `keepFieldsBase`. It is a primary-id candidate on the rules board,
and projection keeps only `keepFieldsBase` plus the rule set's own fields, so leaving it out of the
base strips it from the extract file whenever a rule set does not name it.

`sendUrlTemplate` documents the endpoint for the registry and remote label; it does **not** route
the extractor, which pins `DEFAULT_RETURNS_PATH`. `remoteId` is shared with the OMS order family
because the credentials and `baseUrl` are the same config row.

## The Shopify side

Connector `SHOPIFY_RETURN_REFS`, served by
`reconciliation.ShopifyOrderExtractionServices.extract#ShopifyOrderReturnRefs` (component
`shopify-darpan`, support class `shopify.reconciliation.automation.ShopifyReturnRefsSupport`).
The authoritative description lives with that code; the parts this side depends on:

- **One record per event.** Every in-window refund and every in-window return emits its own row:
  `refundOrReturnId`, `refundOrReturnType` (`REFUND` or `RETURN`), the order id, `createdAt`, and
  `orderCancelledAt`. A return that has been refunded is not emitted separately; its refund row
  stands for it.
- **Bare ids.** GID tails are stripped (`gid://shopify/Refund/123` -> `123`), so a bare OMS
  `externalId` joins to either id space.
- **Windowed on the event, not the order.** The Shopify search is a wide lower-bound net; the
  authoritative `[windowStart - lookback, windowEnd)` filter runs on each event's own `createdAt`.
  The 3-hour lookback covers the measured ~38-minute Shopify -> OMS sync lag.
- **Cursor pagination, not bulk.** `refunds` and `returns` are connections, which bulk queries
  reject.

## Match rule

Both sides now sit at the same grain (one row per return event), so the ordinary rule-set compare
joins OMS `externalId` to Shopify `refundOrReturnId` and finds missing-in-OMS and
missing-in-Shopify rows itself.

`darpan.facade.reconciliation.ReturnPresenceVerificationSupport` (component `darpan`) then runs as
a post-compare filter over the written diff, removing rows that are not real gaps:

- **Grace.** A one-sided event younger than `graceHours` (default 3) is pending, not missing.
  Graded on the event's own date, never the order's.
- **Window-start gate.** A missing-in-OMS row whose event predates the run's window start is not
  reported; it is in the extract only because of the Shopify lookback.
- **Cancelled-order refunds.** A missing-in-OMS REFUND row on a cancelled order is suppressed:
  OMS books a cancellation, not a return. REFUND rows only, missing-in-OMS only.
- **Superseded return drafts.** A missing-in-OMS RETURN row on an order that already has a booked
  OMS return is suppressed: Shopify keeps every draft Return, OMS books one return per settled
  outcome. RETURN rows only.

Its audit note starts with `Return presence check: `, which the chat notification classifies as a
note rather than a warning (`TenantNotificationSupport.partitionAuditNotes`).

Separately, the verification pass (`MissingDiffVerificationSupport`, component `darpan`) calls the
by-id lookup above to confirm or refute missing-in-OMS rows.

## Groovy Justification

- `src/main/groovy/darpan/hotwax/oms/OmsReturnsSourceSupport.groovy`: separate from
  `OmsRestSourceSupport` on purpose — that file carries the sales-order contract
  (`SALES_ORDER`/`EXCHANGE` filtering, exchange manifest, pagination-strategy fallbacks) that
  returns do not share. HTTP execution, auth headers, URL building and window parsing are reused
  from it as statics. It also holds the record pipeline both returns transports feed.
- `src/main/groovy/darpan/hotwax/reconciliation/automation/extractOmsReturns.groovy`: the service
  edge; everything shared with the other OMS extracts lives in `OmsScriptServiceSupport`.

## Status

- REST returns extraction, the join-key fallback, the both-field lookup and the presence filter
  have run against live gorjana data since 2026-08-20; the measurements quoted above come from
  those runs.
- The REST endpoint's refunded-only scope is a property of the OMS endpoint, not of Darpan. Use
  `OMS_RETURNS_GQL` where pending returns matter.
- `returnChannelEnumId`'s value domain is the OMS return-channel enum, which is not in this repo;
  tests use a placeholder value (`POS_RETURN_CHANNEL`).
