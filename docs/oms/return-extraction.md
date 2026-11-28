# HotWax OMS Return Extraction

Component: `darpan-hotwax` (OMS side), with `shopify-darpan` (Shopify side) and `darpan`
(connector registry, compare, and the return presence filter) as the other two legs of returns
reconciliation (DAR-BE-018).

This page covers the returns connector, `OMS_RETURNS_GQL`, and the return lookups. The transport,
cost pacing and failure rules every OMS read shares are in
[graphql-connector.md](graphql-connector.md); the source config and orders are in
[order-extraction.md](order-extraction.md).

The REST returns extract (`extract#HotWaxOmsReturns` on `/rest/s1/oms/reconciliationReturns`,
connector `OMS_RETURNS`) was retired by DAR-BE-052 (2026-11-02). That endpoint served refunded
returns only, so every pending return Shopify had read as missing in HotWax; GraphQL `returns` has
no such exclusion.

## extract#HotWaxOmsGqlReturns

Service: `reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsGqlReturns` (implements
`extract#HotWaxOmsReturnsContract`). Edge: `reconciliation/automation/extractOmsGqlReturns.groovy`.
Page source: `OmsGqlReturnsPageSource`. Record builder: `OmsGqlReturnAssembler`. Pipeline (join-key
fallback, projection, streaming sink, metadata): `OmsReturnsSourceSupport`.

**Window.** `entryDate:>=<from> entryDate:<<thru>` (half-open) on the return's own creation
timestamp, not the linked order's date. A live-probed exchange showed the order placed 07-16, the
return initiated 07-23 and the warehouse processing it 07-28, so windowing on the order date would
put a return outside every window its order appears in. A `windowFieldName` other than `entryDate`
is refused rather than ignored.

**Per page** (43 returns per page, cost-derived):

1. `OmsGqlReturnAssembler.classify` sorts each return:
   - `RETURN_CANCELLED`: `omsReturnClass` `CANCELLED`, counted in `excludedCancelledCount`;
   - no `externalId` and no Shopify return identification: `NO_SHOPIFY_REF`, counted in
     `excludedNoShopifyRefCount`;
   - anything else: `KEEP`.
   A window read writes every return with its class (DAR-BE-318). The `OMS_RETURNS_GQL` row's
   built-in Keep line sets `CANCELLED` and `NO_SHOPIFY_REF` returns aside in darpan's Spark Keep,
   unless the side's options turn a line off.
2. For the returns the read writes, flat id-keyed lookups, batched per 200 returns and asked in
   chunks: `returnItems` by return id; `orders` (`externalId`, `productStoreId`, identifications)
   and `orderItems` (`externalId`) by origin order id; `products` (`internalName`, the `sku`) by
   product id. Every lookup follows its own cursor to the end.
3. `OmsGqlReturnAssembler.build` writes the record: `returnId`, `shopifyReturnId`, `externalId`,
   `orderExternalId`, `statusId`, `entryDate` (epoch millis), `returnTotal` (sum of
   `returnPrice x returnQuantity`), `currencyUomId`, `returnChannelEnumId`, `items[]`,
   `orderProductStoreId` and `omsReturnClass`. A lookup miss leaves a field null, never a guess.

`shopifyReturnId` is the active Shopify return identification, `SHOPIFY_RTN_ID` preferred over
`SHOPIFY_RETURN_ID`, and only when the value is numeric: `SHOPIFY_RETURN_ID` also holds
`WARRANTY-…` values that name no Shopify object.

**Options** (on the side):

- `includeReturnsWithoutShopifyRef` (DAR-BE-094): keep the `NO_SHOPIFY_REF` returns, counted as
  `keptNoShopifyRefCount`.
- `onlyCancelledReturns` (DAR-BE-094 R16): ask the OMS for `RETURN_CANCELLED` returns only; a
  return of any other status it answers is left out (the read's scope, not Keep).
- `withReturnCredit` (DAR-BE-192): also read the payments each return's responses refund and
  write the credit HotWax gives.
- `lookupKeys` (`returnId`, DAR-BE-119): read only these returns, whatever their date. A read by key
  leaves cancelled and no-reference returns out itself, as the window read's Keep would, and writes
  the class of each return it keeps.

### Join-key fallback

`externalId` is the join key to Shopify, and OMS leaves it **blank until a refund exists**.
`applyJoinKeyFallback` copies `shopifyReturnId` into a blank `externalId` before projection. This
tracks Shopify's own grain: Shopify emits a RETURN row while a return is unrefunded and switches to
the REFUND row once a refund exists, so an unrefunded OMS return is named on the Shopify side by
its return id.

Do not invert this into "prefer `shopifyReturnId`". For a refunded return the two fields hold
different Shopify objects (refund id vs return id), and Shopify stops emitting the RETURN row once
refunded, so preferring `shopifyReturnId` would break every refunded match.

### Nested `items[]`

Return lines arrive nested inside their return as `items[]` and are written nested, never
flattened. Projection always keeps `items`, whatever `keepRecordFields` names, so a projection of
header fields cannot strip line data. `items[]` is not offered on the rules board: rules match only
top-level keys.

### orderProductStoreId (DAR-BE-068)

`Return.productStoreId` is not populated on the OMS, so a return's store is taken from its origin
order (the order `orderExternalId` names). It is a Keep-rule field, not a compare field: it is not
in `keepFieldsBase`, and the read selects it for the side's Keep rules. It is absent on a return
with no items, so an `INCLUDE_IN` rule on it drops those returns.

### Metadata and failures

`requestMetadata.filters` carries `excludedNoShopifyRefCount`, `serverReportedReturnsCount` and
`excludedCancelledCount`; `transport` is `GRAPHQL`. There is no `configuredExclusions`: darpan's
Keep summary carries the per-rule counts. All counts are diagnostic run metadata and are not shown
in the UI.

The output (`oms-returns-{fromMillis}-{thruMillis}.json` by default) is streamed to a `.partial`
work file. Any GraphQL error, `THROTTLED` above all (HTTP 200 with no data), fails the whole extract
and the partial file is deleted: an empty extract would publish every Shopify return as missing.

## By-id lookup

Service: `reconciliation.HotWaxOmsExtractionServices.lookup#HotWaxOmsReturnsByExternalId`
(`OmsReturnsSourceSupport.lookupReturnsByExternalId`), the missing-in-OMS mirror of Shopify's
`lookup#ShopifyRefundOrReturnIds`. The saved-run verification pass (`MissingDiffVerificationSupport`,
component `darpan`) calls it to confirm or refute "missing in OMS" differences. Output: `ok`,
`foundIds`, `missingIds`, `unresolvedIds`, `errors`.

- **Not narrowed by the run window.** OMS dates a return when it was created, Shopify when it was
  refunded, so a return opened weeks before its refund sits inside one window and far outside the
  other.
- **Three GraphQL passes**, for the three id kinds the lookup is handed: `returns` by `returnId`
  (a returnId-keyed compare, for example OMS returns vs NetSuite RMAs), by `externalId` (the
  Shopify refund id), then `returnIdentifications(idValue:)` for the Shopify return id, accepting
  only `SHOPIFY_RETURN_ID` and `SHOPIFY_RTN_ID`. Ids are asked in chunks of 50, each paged to the
  end.
- **Echo check.** Every returned record is checked against the ids asked; an answer naming none of
  them is an ignored filter, and its chunk stays unresolved.
- **Unresolved, never missing.** An id is missing only when every pass ran and none found it. Ids a
  pass could not check (a failed call, an ignored filter, an id with characters the search string
  cannot carry) come back in `unresolvedIds`, so a degraded OMS over-reports instead of hiding rows.

## Return-lines lookup

Service: `lookup#HotWaxOmsReturnLinesByOrderExternalId` (`OmsReturnLinesLookupSupport`,
DAR-BE-085/086). The order lines OMS booked a return against, keyed by the originating Shopify
order id. The returns verify stage uses it to pair a missing-in-OMS Shopify event by order and line
when no shared id can exist: AfterShip warranty returns (keyed `WARRANTY-<uuid>` in OMS) and returns
on Shopify orders older than the 60-day read limit (rebuilt without a refund id). Four flat passes:
`orders(externalId:)`, `returnItems(orderId:)`, then `orderItems(orderId:)` and
`returns(returnId:)`. Cancelled returns are left out; any failure fails the whole lookup, which the
verify stage reads as "pair nothing".

## Connector row

`OMS_RETURNS_GQL` (darpan `data/SourceSystemConnectorSeedData.xml`, settings in this component's
`data/HotWaxConnectorSeedData.xml`): endpoint label "Returns", source config type
`HOTWAX_OMS_GQL_RETURNS`, config `darpan.hotwax.HotWaxOmsRestSourceConfig`,
`lookupServiceName=lookup#HotWaxOmsReturnsByExternalId`,
`orderStateLookupServiceName=lookup#HotWaxOmsOrdersByExternalId`, `excludedRecordsSidecar=N`.

`shopifyReturnId` must stay in `keepFieldsBase`. It is a primary-id candidate on the rules board,
and projection keeps only `keepFieldsBase` plus the rule set's own fields, so leaving it out strips
it from the extract file whenever a rule set does not name it.

## The Shopify side

Connector `SHOPIFY_RETURN_REFS`, served by
`reconciliation.ShopifyOrderExtractionServices.extract#ShopifyOrderReturnRefs` (component
`shopify-darpan`, `shopify.reconciliation.automation.ShopifyReturnRefsSupport`). The authoritative
description lives with that code; the parts this side depends on:

- **One record per event.** Every in-window refund and every in-window return emits its own row:
  `refundOrReturnId`, `refundOrReturnType` (`REFUND` or `RETURN`), the order id, `createdAt` and
  `orderCancelledAt`. A refunded return is not emitted separately; its refund row stands for it.
- **Bare ids.** GID tails are stripped (`gid://shopify/Refund/123` -> `123`), so a bare OMS
  `externalId` joins to either id space.
- **Windowed on the event, not the order**, with a 3-hour lookback for the Shopify to OMS sync lag.

## Match rule

Both sides sit at one row per return event, so the ordinary rule-set compare joins OMS
`externalId` to Shopify `refundOrReturnId` and finds missing-in-OMS and missing-in-Shopify rows
itself.

`darpan.facade.reconciliation.ReturnPresenceVerificationSupport` (component `darpan`) then filters
the written diff, removing rows that are not real gaps:

- **Grace.** A one-sided event younger than `graceHours` (default 3) is pending, not missing,
  graded on the event's own date.
- **Window-start gate.** A missing-in-OMS row whose event predates the run's window start is not
  reported; it is in the extract only because of the Shopify lookback.
- **Cancelled-order refunds.** A missing-in-OMS REFUND row on a cancelled order is suppressed: OMS
  books a cancellation, not a return.
- **Superseded return drafts.** A missing-in-OMS RETURN row on an order that already has a booked
  OMS return is suppressed: Shopify keeps every draft Return, OMS books one per settled outcome.

The verification pass also calls the by-id and return-lines lookups above to confirm or pair missing-in-OMS rows.

## Groovy Justification

- `oms/OmsReturnsSourceSupport.groovy`: the returns record pipeline (join-key fallback,
  projection, sink, metadata) and the by-id lookup, separate from `OmsExtractSupport` because that
  file carries the sales-order contract (exchange scan, manifest) that returns do not share.
- `oms/OmsGqlReturnsPageSource.groovy`, `OmsGqlReturnAssembler.groovy`: GraphQL paging, the
  id-keyed follow-ups and record assembly.
- `oms/OmsReturnLinesLookupSupport.groovy`: the four-pass return-lines lookup.
- `reconciliation/automation/extractOmsGqlReturns.groovy` and the `reconciliation/lookup/` scripts:
  thin service edges over `OmsScriptServiceSupport`.

## Status

- `returnChannelEnumId`'s value domain is the OMS return-channel enum, which is not in this repo;
  tests use a placeholder value (`POS_RETURN_CHANNEL`).
