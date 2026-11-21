# HotWax OMS REST Order Extraction

Component: `darpan-hotwax`

The component owns HotWax/OMS integration artifacts that do not belong in the core `darpan` component. The first contract is tenant-scoped order extraction for reconciliation automation.

This page covers the REST order connectors (`OMS`, `OMS_RECON_ORDERS`). Returns are in `rest-return-extraction.md`; the GraphQL connectors (`OMS_GQL`, `OMS_RETURNS_GQL`) are in `graphql-connector.md`.

## Source Config

Entity: `darpan.hotwax.HotWaxOmsRestSourceConfig`

Facade services:

- `facade.HotWaxOmsFacadeServices.list#HotWaxOmsRestSourceConfigs`
- `facade.HotWaxOmsFacadeServices.save#HotWaxOmsRestSourceConfig`
- `facade.HotWaxOmsFacadeServices.delete#HotWaxOmsRestSourceConfig`

Delete is allow-remote and authenticated, and like save it requires active-tenant write access and verifies the stored config's owner before removing it.

Configs are scoped by `companyUserGroupId`. Save operations require active-tenant write access and preserve existing encrypted secrets when blank secret inputs are sent on update. List/save responses return safe metadata only: secret fields are represented as boolean flags and are not returned in clear text. `canReadOrders` controls whether the orders endpoint is advertised to the UI for the selected HotWax config; it defaults to enabled for existing configs.

Required fields:

- `omsRestSourceConfigId`
- `baseUrl`, for example `https://dev-maarg.hotwax.io`. If an OMS base path or full orders endpoint is entered, the extractor avoids duplicating the configured orders path.
- `ordersPath`, default `/rest/s1/oms/orders`
- `timeZone`, default `UTC`, used by setup and source metadata displays for HotWax date-window interpretation
- `canReadOrders`, default `Y`

Other columns: `authType`, `username`, `password` (encrypted), `apiToken` (encrypted), `headersJson`, `connectTimeoutSeconds` (default 30), `readTimeoutSeconds` (default 60), `returnsPageSize` (default 500, returns only), `isActive`, `description`. The entity is `darpan-hotwax/entity/HotWaxOmsConfigEntities.xml`.

### Knobs the code reads but the entity does not have

`OmsRestSourceSupport` reads three tuning keys from the config map that are **not columns** on `HotWaxOmsRestSourceConfig`. A config row loaded from the database never carries them, so each always resolves to its code default and cannot be changed per source today:

| Key read by the code | Effective value | Where |
|---|---|---|
| `ordersPageSize` | 500 (bounded 1..1000) | `resolveOrdersPageSize`, `DEFAULT_ORDERS_PAGE_SIZE` |
| `ordersFetchConcurrency` | 2 (bounded 1..4) | `resolveOrdersFetchConcurrency`, `DEFAULT_ORDERS_FETCH_CONCURRENCY` |
| `maxOrdersPageCount` | 20,000 pages | `MAX_ORDERS_PAGE_COUNT` (also the page ceiling of the GraphQL order extract) |

The same applies to `maxReturnsPageCount` on the returns path (20,000, `MAX_RETURNS_PAGE_COUNT`). `returnsPageSize` is the only page-size setting that is a real column.

One consequence an operator can see: when `/reconciliationOrders` rejects a page as `PAGE_SIZE_TOO_LARGE`, the error text advises lowering `ordersPageSize` on the source config. There is no such field to lower; the page size stays 500.

The dev OMS Swagger document describes the orders API at `/rest/s1/oms/orders`, with `GET /` used to list `OrderHeader` records and Basic or `api_key` header authentication available. Darpan uses that Swagger contract for setup metadata and keeps `/rest/s1/oms/orders` as the default orders path. At runtime the extractor retries the list route with a trailing slash if the first request returns `404`, matching Swagger implementations that require `/rest/s1/oms/orders/`.

Supported auth modes are `NONE`, `BASIC`, `BEARER`, and `API_KEY`. `API_KEY` stores the token in the encrypted API token field and sends it in the Swagger-documented `api_key` header.

## Extractor

Service: `reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsOrders`

The extractor calls:

```text
GET {baseUrl}{ordersPath}?orderDate_from=<startEpochMillis>&orderDate_thru=<endEpochMillis>&pageSize=<n>&pageIndex=<n>
```

`windowStart` and `windowEnd` accept `Timestamp`, `Date`, ISO-8601 text, SQL timestamp text emitted by Moqui service serialization, or epoch milliseconds. Both parameters are always sent as epoch milliseconds.

The extractor paginates order reads before writing the normalized source file. It first uses `pageIndex`/`pageSize`, then falls back to `viewIndex`/`viewSize` for OMS environments that expose that list contract. Pagination stops when the next page is empty, repeats the previous page, or is shorter than the previous page. The requested page size is 500, with a ceiling of 20,000 pages to stop runaway loops while still allowing month-scale reconciliation runs (see the knobs table above: neither is configurable per source).

**Retries on the orders path are narrow.** A page that times out is retried once with compression disabled (a gzip-buffering proxy can hold the whole response past the read timeout), and a `404` is retried once with a trailing slash before the query string. Any other non-2xx status fails the page and the extract; there is no `429`/`502`/`503`/`504` page retry on the orders path. That retry exists on the returns path only (`rest-return-extraction.md`). When the automation layer retries a failed extract (`AutomationExecutionSupport.buildFailureFields`, component `darpan`), it re-fetches the window from page 0.

Requests advertise `Accept-Encoding: gzip` and decode compressed responses, which multiplies effective throughput on long-haul connections (the OMS itself needs no change — this is standard HTTP content negotiation). Once the pagination strategy is committed, remaining pages are fetched through a small concurrent window (two pages in flight; the code would honour an `ordersFetchConcurrency` key capped at 4, but it is not a config column, so the window is always 2) while output is written strictly in page order. Worker threads hand back pre-filtered, pre-serialized page text rather than parsed record graphs, so the window costs roughly one serialized page of memory per slot — sized for resource-constrained production hosts. A stop condition (empty/repeated/shorter page) may leave up to window-minus-one speculative page requests already issued; their responses are discarded.

When the caller passes `keepRecordFields` (the SourceSystemConnector registry declares the parameter and base field set; dispatch adds the rule set's join-key fields), each order is projected down to those top-level fields before writing. Full ~14 KB order documents become a few hundred bytes each — a 99k-order window writes tens of MB instead of ~1.4 GB, which also keeps the Spark compare read (unsplittable multiLine JSON) inside constrained heaps. Projection is skipped automatically for rule sets that define field-comparison rules until rule-field extraction is wired into the keep-list.

Each fetched page is appended to the output file on disk before the next page is requested; the extractor never holds more than roughly one page of orders in memory, so month-scale windows do not scale heap usage. The extractor keeps only HotWax orders with `orderTypeId` equal to `SALES_ORDER`. An order that contains an order item association with `orderItemAssocTypeId` equal to `EXCHANGE` is written flagged `isExchangeOrder` Y on a window read, and Spark Keep sets it aside (DAR-BE-318), so it is not compared against Shopify orders; a read by key leaves it out itself. Non-sales orders are not written. Output metadata includes `filters.excludedNonSalesOrderCount` and `filters.excludedExchangeOrderCount` so the run can distinguish fetched HotWax records from comparison-eligible records. Exchange orders are additionally captured into a sidecar `<extract-name>.exchange-manifest.json` file (`{manifest, truncated, sourceFileName}`, entries are ids/amounts only — `omsOrderId`, `externalId`, `orderName`, `toOrderId`, `grandTotal`, `orderDate`, `statusId` — capped at 500 entries), which the exchange pair verify stage consumes to check exchange orders against their linked original order rather than silently dropping them. `filters.exchangeManifestTruncated` marks windows where the cap was hit.

### Reconciliation Orders endpoint (opt-in)

Service: `reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsReconciliationOrders`
Connector row: `OMS_RECON_ORDERS` / `expectedSourceConfigType=HOTWAX_OMS_REST_RECON`

A second, **additive** OMS orders connector served by a purpose-built endpoint that does server-side
what the extractor above does client-side. The legacy connector is unchanged and remains the default;
a rule set adopts this one by pointing its source at `HOTWAX_OMS_REST_RECON`, against the **same**
`HotWaxOmsRestSourceConfig` row (same credentials, same `baseUrl`).

```text
GET {baseUrl}/rest/s1/oms/reconciliationOrders?orderDateFrom=<startEpochMillis>&orderDateThru=<endEpochMillis>&pageSize=<n>&pageIndex=<n>
```

The path is **fixed by the extractor**, not read from the config's `ordersPath` column — that column
holds the legacy `/rest/s1/oms/orders` on every existing tenant config, so honouring it here would
send recon-shaped requests to the legacy endpoint.

Five differences from the legacy path, all of them inside `OmsRestSourceSupport`; the service
contract, the on-disk `{records, metadata}` file and the sidecar shape are identical, so the
automation edge and the Spark compare stage are unchanged.

| | Legacy `/orders` | `/reconciliationOrders` |
|---|---|---|
| Window params | `orderDate_from` / `orderDate_thru` | `orderDateFrom` / `orderDateThru`, half-open `[from, thru)` |
| Sales-order filter | client-side, on `orderTypeId` | server-side; the client filter is skipped (`orderTypeId` is not in the projection, so re-running it would discard every record) |
| Exchange orders | excluded client-side by scanning order-item associations | delivered inline flagged `isExchange`; the client drops them and builds the manifest from inline `originalOrderId` / `originalExternalId` — **no pair-lookup round-trips** |
| Termination | inferred from empty / repeated / shorter pages, plus a 50-record truncation probe | `hasMore`; the probe and the strategy fallback are both skipped |
| Projection | client-side via `keepRecordFields` | server-side, fixed set |

Response counts are surfaced in `requestMetadata.filters`: the server's
`excludedNonSalesOrderCount` replaces the (structurally zero) client count under the same key,
`ordersCount` appears as `serverReportedOrdersCount`, and `missingExternalIdCount` — orders the
server dropped for an empty join key, which has no legacy equivalent — is reported alongside them.
`excludedExchangeOrderCount` stays the client's own count, because this client still does that drop.

An over-maximum `pageSize` is **rejected**, never silently capped: the extract fails with the
server's error code and stated limit rather than working around it.

**Gating — verify before enabling for a rule set.** The endpoint returns a fixed field set
(`orderId`, `externalId`, `orderName`, `grandTotal`, `orderDate`, `statusId`). Confirm the rule set's
join key and every field-comparison field fall inside it; anything wider must stay on
`extract#HotWaxOmsOrders`. The same limit narrows the rules-board pills: `salesChannelEnumId` and
`productStoreId` are offered on the legacy connector only, because an exclusion rule naming a field
the endpoint never returns would validate, persist, and then exclude nothing.

Configured exclusions (below) still run **client-side** on this connector — the endpoint has no
knowledge of tenant exclusion rules.

> **Status.** Shape verified against fixtures only. As of 2026-08-11 no live response from this
> endpoint has been captured, and count/diff parity against the legacy connector over a frozen
> window has not been run.

### Configurable record exclusion

Tenant exclusion rules are not applied by the extract (DAR-BE-318). `extract#HotWaxOmsOrders` takes no `sourceFilters`
parameter: it writes every sales order, each flagged `isExchangeOrder` `Y` or `N`, and darpan's Spark Keep (component
`darpan`, `KeepPlanSupport`) sets aside the exchange orders (the `OMS_GQL` row's `keepLinesJson`) and what the run's rules
reject, counts them in its Keep summary and writes the excluded-records sidecar. The rule semantics (case-sensitive field
names against top-level keys, case-insensitive values, `EXCLUDE_IN` keeping a record that lacks the field, `INCLUDE_IN`
dropping it as field-absent) live in darpan core, not here.

**The read still decides two things itself.** A non-`SALES_ORDER` order is never written (`excludedNonSalesOrderCount`), and
a read by key leaves exchange orders out itself (`excludedExchangeOrderCount`, captured into the exchange manifest).

**Projection keeps the Keep fields.** `keepRecordFields` trims each order after the page is read, and a run joins the
fields its Keep rules read to that list, so a rule can target a field such as `salesChannelEnumId` or `productStoreId`
even though it is not part of the connector's declared keep-field set. The `EXCHANGE`-association scan needs the full order
document, so it runs before the trim.

**Which fields the rules board offers.** The exclusion editor has no free-text field entry: a field is excludable only if it
has a `SourceSystemConnectorField` row for the connector's `systemEnumId`. A pill naming a field the order document does not
carry would persist a rule that matches nothing, so every entry must name a real top-level key on the OMS order document.
Nested paths do not work either: only the first segment after `[*]` is read.

**Metadata.** The read's `requestMetadata.filters` carries only its own counts (`excludedNonSalesOrderCount`,
`excludedExchangeOrderCount`, `flaggedExchangeOrderCount`, `exchangeManifestTruncated`). There is no `configuredExclusions`
there; darpan's Keep summary carries the per-rule counts, and reads the side as not filtered at the read.

**Lookups apply no exclusion.** `lookup#HotWaxOmsOrdersByExternalId` resolves specific orders by ID and applies no tenant
rule, so a `STAGE_VERIFY` point lookup can surface an order a run's Keep set aside.

Counts are diagnostic run metadata only and are not shown in the UI.

The extractor streams into a `.partial` work file in the run folder and moves it to its final name only after the whole window succeeds, so a mid-window failure never leaves a partial extract where reconciliation could read it.

The output is normalized compact JSON. `records` is written first (pages are appended as they arrive) and `metadata` last, once counts and pagination are known; consumers read the file with JSON parsers (Spark multiLine JSON, JSONPath), so object key order is not part of the contract:

```json
{
  "records": [],
  "metadata": {
    "sourceType": "HOTWAX_OMS_REST_ORDERS",
    "omsRestSourceConfigId": "KREWE_OMS",
    "ordersPath": "/rest/s1/oms/orders",
    "queryParams": {
      "orderDate_from": 1777573800000,
      "orderDate_thru": 1777577400000
    },
    "pagination": {
      "pageSize": 500,
      "pageCount": 1,
      "strategy": "pageIndexPageSize",
      "totalFetched": 2,
      "truncated": false
    },
    "filters": {
      "requiredOrderTypeId": "SALES_ORDER",
      "excludedNonSalesOrderCount": 0,
      "excludedOrderItemAssocTypeIds": ["EXCHANGE"],
      "excludedExchangeOrderCount": 0
    },
    "extractedRecordCount": 2
  }
}
```

The `metadata` block above is illustrative; the extractor always also emits the request shape it derived (`method`, `baseUrl`, `authType`, `timeZone`, `headerNames`, `statusCode`, `attemptCount`, and `windowStart/EndEpochMillis`). Credentials and authorization header values are never included — only header names.

The default output folder is `runtime://datamanager/reconciliation-runs/{automationExecutionId}/{timestamp}/`. When `automationExecutionId` is omitted, the config id is used as the run folder token.

The service returns `fileLocation`, `fileName`, `recordCount`, `requestMetadata`, `warnings`, and `errors`. Request metadata excludes credentials and authorization headers.

## Groovy Justification

Service XML owns the public contracts for this component. Groovy is retained only where XML actions would either hide non-trivial branching inside inline expressions or duplicate reusable integration logic.

- `src/main/groovy/darpan/hotwax/oms/OmsRestSourceSupport.groovy`: retained for HTTP execution, auth-header construction, URL/path overlap handling, query encoding, pagination fallback across documented OMS list conventions, date parsing, safe metadata shaping, JSON parsing, and sales/exchange-order filtering. This is integration and transformation logic, not service orchestration.
- `src/main/groovy/darpan/hotwax/reconciliation/automation/extractOmsOrders.groovy`: retained as the service edge that combines tenant-safe config access, extractor invocation, data-manager path resolution, safe file naming, and output writing. Keeping this in XML would push the same branching into dense action expressions while still depending on the Groovy extractor.
- `src/main/groovy/darpan/hotwax/facade/settings/saveOmsRestSourceConfig.groovy`: retained for validation that depends on existing encrypted secret state, auth-mode-specific requirements, timezone validation, outbound-URL (SSRF) policy checks, headers-JSON validation (rejecting invalid or non-object JSON, oversized header values, and control-character / header-smuggling attempts), tenant writability checks, secret preservation on blank updates, and safe response shaping.
- `src/main/groovy/darpan/hotwax/facade/settings/listOmsRestSourceConfigs.groovy`: retained for safe-row projection, credential redaction, tenant-scoped filtering, case-insensitive multi-field search, and bounded pagination. This keeps the XML service definition declarative and avoids repeating redaction logic in XML actions.
- `src/main/groovy/darpan/hotwax/facade/settings/deleteOmsRestSourceConfig.groovy`: retained because delete is not a pure entity delete; it resolves the active tenant, verifies write access against the stored owner, returns the shared facade envelope, and emits the deletion result. Converting it to XML would duplicate tenant-safety checks already centralized in support code.

## Automation Integration

The core automation executor can call this extractor from an `AUT_IN_API_RANGE` automation source when the source row includes extractor metadata.

Required source-row fields:

- `sourceTypeEnumId=AUT_SRC_API`
- `systemEnumId` matching the saved-run file side, usually `OMS`
- `safeMetadataJson.extractServiceName=reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsOrders`
- `safeMetadataJson.parameters.omsRestSourceConfigId=<config id>`
- `dateFromParameterName=windowStart`
- `dateToParameterName=windowEnd`

Example source metadata:

```json
{
  "extractServiceName": "reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsOrders",
  "parameters": {
    "omsRestSourceConfigId": "GORJANA_OMS"
  }
}
```

The extractor writes normalized JSON to the data-manager run folder and returns the file location/counts needed by `reconciliation.ReconciliationAutomationServices.execute#Automation`. Keep config secrets in encrypted fields only; the extractor response and metadata should include header names but not header values or credentials.

`sourceFilters` is never part of `safeMetadataJson` above. On the scheduled path, `AutomationExecutionSupport.callConfiguredSourceExtractor` (component `darpan`) loads the automation's own `ReconciliationAutomationSourceFilter` rows for the relevant `fileSide` and passes them as `sourceFilters` when it invokes this extractor. Those rows are a frozen snapshot copied from the rule set's `RuleSetCompareSourceFilter` rows once, at automation creation time — the automation wizard has no exclusion UI of its own, so an automation that carries exclusions got them by seeding, not by an operator setting them in the wizard. Later edits to the rule set's exclusions do not reach an already-created automation.
