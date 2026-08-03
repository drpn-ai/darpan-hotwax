# OMS Reconciliation Export — Requirements

> **SUPERSEDED 2026-08-03** by `2026-08-03-oms-recon-data-requirements.md` in this
> folder. Two decisions changed: scope now includes returns (header and line-item
> level), and the async export-job-plus-file transport is withdrawn in favour of a
> faster synchronous endpoint. The join key, field set, filter rules, exclusion
> counts, auth modes, and open questions OQ-3/OQ-6/OQ-7 are carried forward intact
> into the new document. Do not hand this version to the OMS team.

| | |
|---|---|
| Date | 2026-07-24 |
| Status | SUPERSEDED — see banner above. Was DRAFT, never signed off. |
| Consumer | Darpan reconciliation (component `darpan-hotwax` is the integration edge) |
| Implementer | HotWax OMS ("maarg", Moqui-based) team — their architect owns all design |
| Supersedes | Nothing. The current integration is documented in `rest-order-extraction.md` (same folder) and remains in service (RQ-19). |

This is a requirements document. It states WHO / WHAT / WHEN and the externally
observable contract Darpan depends on. HOW the OMS implements it — entities,
services, job frameworks, storage — is entirely the OMS architect's design
freedom and is deliberately absent here.

Two shape decisions were made by the product owner before authoring and are
**decided scope, not open for re-litigation in this document**:

1. **Purpose-built reconciliation export**, not a general bulk-order API. The
   server does the filtering and field selection; Darpan stops over-fetching.
2. **Async export job + file delivery**: create a job for a date window, poll
   its status, download a completed gzipped NDJSON file. Chosen over
   streaming/pagination because reconciliation windows can be month-scale
   (Darpan's automation splits windows at 28 days) and a static file is
   resumable and retryable.

## 1. Problem statement (measured evidence)

The current integration (`GET {baseUrl}/rest/s1/oms/orders?orderDate_from=<epochMs>&orderDate_thru=<epochMs>&pageSize&pageIndex`,
documented in `rest-order-extraction.md`) ships full untrimmed order documents
and leaves all reconciliation filtering to the client. Measured on run 100000
(saved run RS_GORJANA_PROD; one day-scale window; local Darpan against a
prod-like OMS on EC2 us-east-1; measurements supplied by the Darpan product
owner 2026-07-24 — not independently re-run during authoring):

- **57 MB received for 4,154 comparison-eligible orders** — ~14 KB per order on
  the wire (derived: 57 MB ÷ 4,154), full order documents, fields untrimmed.
- **272 s extract wall-clock** (measured transfer throughput ~260 KB/s). The
  same logical dataset from Shopify (file 1 of the same run) took **22.5 s**.
- Some OMS deployments **silently cap pages at 50 records** regardless of the
  requested `pageSize=500` (observed and coded around in
  `OmsRestSourceSupport.groovy`, constant `OMS_DEFAULT_SERVER_PAGE_SIZE` and
  its comment) — the reference window therefore cost **~84 sequential
  round-trips** (derived: 4,154 ÷ 50).
- Darpan then **discards data client-side**: it keeps only
  `orderTypeId=SALES_ORDER` and excludes orders carrying an `EXCHANGE`
  order-item association (the `filters` metadata block in
  `rest-order-extraction.md`). The server transferred orders that were thrown
  away on arrival.
- Of each ~14 KB order document, reconciliation compares only a handful of
  fields — roughly a **50× over-fetch**.

Business goal: an OMS-side export whose delivered file contains exactly the
comparison-eligible orders and exactly the compared fields, so that a
reconciliation window costs seconds and megabytes instead of minutes and tens
of megabytes, on both scheduled and human-triggered runs.

## 2. Actors

| Actor | Kind | Role |
|---|---|---|
| **Darpan automation executor** | System (machine) | Scheduled reconciliation runs. Splits windows at ≤28 days, retries transient failures (retry + dead-letter queue) — so it WILL re-submit the same window (see RQ-5). Unattended; needs machine-readable states and errors. |
| **Darpan interactive run** | System driven by a human operator | Human-triggered run that waits minutes, not hours. Darpan's run observability surfaces per-stage heartbeats to the operator, so it needs a progress signal while an export is running (RQ-7). |
| **OMS operations** | People (HotWax) | Own the OMS deployment: retention windows, concurrency limits, credentials, and diagnosing export jobs a Darpan operator reports as stuck or failed (RQ-18). |

The export capability under specification is provided by the HotWax OMS and is
referred to as "OMS" below; its internals are unnamed by design.

## 3. Requirements

### Scope and content

- **RQ-1 (Comparison-eligible orders only).** The delivered file contains only
  orders that are comparison-eligible for Shopify reconciliation: orders with
  `orderTypeId=SALES_ORDER`, excluding any order that has an order-item
  association of type `EXCHANGE`. This filtering happens on the OMS side; the
  file never contains an order Darpan would discard. (These are the same rules
  Darpan applies client-side today — `rest-order-extraction.md`, extractor
  section.)
- **RQ-2 (Deterministic per-order field set).** Each order record carries a
  documented, fixed field set — nothing more:
  - a stable order identity usable as the reconciliation join key against
    Shopify: `externalId` (the OMS-stored Shopify order reference — this IS
    the production join key, see below), plus `orderId` and `orderName` for
    operator display and OMS-side traceability — **RESOLVED (was OQ-1)**: the
    production rule set `RS_GORJANA_PROD` joins Shopify `$.records[*].id` ↔
    OMS `$.records[*].externalId`, single-field on both sides (read from the
    live tenant rule set via `list#SavedRuns`, 2026-07-24);
  - the business fields `grandTotal`, `orderDate`, `statusId` — **RESOLVED
    (was OQ-2), with a finding**: the production rule set currently defines
    **zero field-comparison rules** — reconciliation today is presence-only
    (order exists in both systems). These fields are still required in the
    export: they populate the diff output operators read, and they are the
    anticipated first field-comparison rules; shipping them now avoids a
    contract change the day a tenant adds an amount check;
  - item-level reconciliation is **not configured** for the reference tenant
    today — item identity/quantity is explicitly OUT of the v1 field set
    (adding it later is a contract extension, see §4).
- **RQ-3 (Exclusion visibility).** Job metadata reports the delivered record
  count and the counts of orders excluded by each rule in RQ-1. Darpan's run
  metadata today records `excludedNonSalesOrderCount` and
  `excludedExchangeOrderCount` (`rest-order-extraction.md`); the new contract
  must preserve that visibility so a run can still distinguish "fetched" from
  "comparison-eligible".

### Export job lifecycle

- **RQ-4 (Create).** Darpan creates an export job by POSTing a date window as
  epoch milliseconds UTC with **half-open semantics `[from, thru)`** — an
  order whose filter timestamp equals `thru` is NOT included. The window
  filters on the same order timestamp field the legacy endpoint filters on
  (`orderDate_from`/`orderDate_thru`). Legacy boundary behavior must be
  confirmed so counts can match exactly — **OPEN (OQ-3)**. Creation returns a
  job identifier Darpan stores with the run.
- **RQ-5 (Idempotent creation).** Re-POSTing an equivalent request (same
  tenant/credential scope, same window) while a prior job for it is pending,
  running, or ready does not start duplicate work: the OMS either returns the
  existing job or otherwise deduplicates. Rationale: the Darpan automation
  executor retries transient failures, so double-submission is a certainty,
  not an edge case.
- **RQ-6 (Poll).** Darpan can poll a job and distinguish at least these
  situations (state names are the OMS designer's choice): queued
  (PENDING), in progress (RUNNING), file ready (READY), failed (FAILED — with
  a human-actionable error message), and expired (EXPIRED — file no longer
  retained, see RQ-15). Terminal states are stable: a job does not move out of
  READY/FAILED except READY→EXPIRED.
- **RQ-7 (Progress signal).** While a job is running, polling returns a
  progress indication — at minimum records processed so far; percent-complete
  is welcome but not required. Darpan relays this to operators via its
  per-stage run heartbeats.
- **RQ-8 (Download).** A READY job's file is downloadable as **gzipped NDJSON,
  one order per line**, with the RQ-2 field set. Job metadata states the
  record count (RQ-3), which must equal the file's line count.
- **RQ-9 (Deterministic file).** Record ordering within the file is
  deterministic; downloading the same completed job twice yields the same
  records in the same order. Download is repeatable any number of times until
  expiry (Darpan retries failed downloads rather than re-running the export).
- **RQ-10 (Rejection clarity).** Invalid creation requests (malformed window,
  `from >= thru`, window exceeding the maximum — RQ-17) are rejected at
  creation time with a machine-distinguishable, human-actionable error, not
  accepted and later failed.

### Performance

- **RQ-11 (Reference-window target).** A window equivalent to the measured
  reference (4,154 comparison-eligible orders — 272 s and 57 MB today)
  completes create→READY→downloaded in **well under 60 s end-to-end**, with a
  gzipped payload on the order of **1–2 MB, not 57 MB**.
- **RQ-12 (Month-scale windows).** A 28-day window — Darpan's maximum
  automation split — at reference-tenant volume completes successfully. The
  async shape exists precisely so no HTTP request timeout bounds the export
  itself; only the create/poll/download calls are interactive.

### Authentication

- **RQ-13 (Auth parity).** All three interactions — create, poll, download —
  are authenticated with the same modes the current integration supports:
  `BASIC`, `BEARER`, and `API_KEY` via the Swagger-documented `api_key`
  header (`rest-order-extraction.md`, Source Config section). The file
  download itself is authenticated the same way; an unauthenticated
  pre-signed URL is acceptable only if OMS operations explicitly accepts that
  posture — **OPEN (OQ-4)**.
- **RQ-14 (Tenant scoping).** A credential can create, see, and download only
  jobs and files for order data that credential is entitled to today; job
  identifiers of other tenants' jobs are not guessable into a download.

### Operational

- **RQ-15 (Retention).** Completed files are retained for a defined window,
  after which the job reports EXPIRED and the file is gone. Retention value
  **OPEN (OQ-5)** — proposed default: 7 days, comfortably covering Darpan's
  retry + DLQ redrive horizon.
- **RQ-16 (Concurrency limits).** The OMS may bound concurrent export jobs
  per tenant/credential; over-limit creation is rejected with a clear,
  machine-distinguishable "retry later" error (not silent queuing without
  acknowledgment, not a generic failure). Limit value **OPEN (OQ-6)**.
- **RQ-17 (Maximum window).** The OMS may cap the requestable window size. The
  recommended behavior is **reject with a clear error stating the cap** —
  Darpan already splits windows and will adapt; silent truncation or
  auto-splitting on the OMS side would corrupt count parity. The cap must be
  ≥ 28 days (RQ-12). Cap value **OPEN (OQ-7)**.
- **RQ-18 (OMS-side observability).** OMS operations can query export job
  records — requester, window, state, counts, timestamps — well enough to
  answer "what happened to job X?" when a Darpan operator reports a stuck or
  failed export. (How is their design.)

### Compatibility and migration

- **RQ-19 (Legacy endpoint untouched).** The existing `/rest/s1/oms/orders`
  contract is not changed by this work. Darpan migrates by adding a new
  connector configuration — its SourceSystemConnector registry makes the
  extract service data-driven, so the new path is a registry row plus one
  extract service on the Darpan side, no core changes.
- **RQ-20 (Parallel operation).** Both paths must be usable against the same
  deployment during migration, because the parity acceptance test (AC-2)
  compares them on the same frozen window.

## 4. Explicit non-requirements

Recorded so nobody builds them by accident; any of these changing is a new
conversation, not a hidden feature.

- No general-purpose bulk-order API; this export serves reconciliation only.
- No streaming or paginated delivery shape (decided scope: job + file).
- Orders only. No inventory, shipment, or return reconciliation export.
- No change to the legacy orders endpoint's behavior, paging, or field set.
- No Darpan-side requirement for webhook/push completion notification —
  polling is sufficient for both Darpan actors.

## 5. Acceptance criteria

- **AC-1 (Reference window).** The reference window (the exact date window of
  measured run 100000 / saved run RS_GORJANA_PROD — read it from that run's
  recorded query params; 4,154 sales orders) exports create→download in
  ≤ 60 s end-to-end;
  file ≤ 2 MB gzipped; delivered record count matches the current endpoint's
  post-filter count exactly, and the excluded counts (RQ-3) match Darpan's
  legacy-path `excludedNonSalesOrderCount` / `excludedExchangeOrderCount` for
  the same window.
- **AC-2 (Reconciliation parity).** A Darpan reconciliation run using the new
  export on a frozen window produces an identical diff result (same matched,
  missing, and mismatched orders) to a run on the legacy path over the same
  frozen window.
- **AC-3 (Idempotency).** Two equivalent create requests submitted in quick
  succession result in one export's worth of work, and both callers can reach
  the same completed file.
- **AC-4 (Failure clarity).** An induced export failure yields FAILED with an
  error message an operator can act on without reading OMS server logs.
- **AC-5 (Month window).** A 28-day window at reference-tenant volume reaches
  READY and downloads successfully.
- **AC-6 (Auth).** Unauthenticated or wrong-tenant create, poll, and download
  attempts are all rejected (subject to OQ-4's ruling on pre-signed URLs).
- **AC-7 (Expiry).** After the retention window, polling the job reports
  EXPIRED and download is refused distinguishably from "not found".

## 6. Open questions

Each is phrased for the business expert to answer; the answer slots directly
into the requirement cited.

| # | Question | Feeds | Owner |
|---|---|---|---|
| OQ-1 | ~~Authoritative Shopify-side order reference / production join key?~~ **RESOLVED 2026-07-24**: OMS `externalId` ↔ Shopify `id`, single-field both sides — read from live rule set `RS_GORJANA_PROD` / compare scope `CS_RS_GORJANA_PROD_COMPARE_SCOPE` via `facade.ReconciliationFacadeServices.list#SavedRuns`. | RQ-2 | — (was: Aditi + HotWax OMS team) |
| OQ-2 | ~~Compared business fields per tenant rule set? Item-level configured?~~ **RESOLVED 2026-07-24**: production rule set defines **zero field-comparison rules** — presence-only reconciliation today; item-level NOT configured. `grandTotal`/`orderDate`/`statusId` retained in the field set for diff display + anticipated rules (see RQ-2). | RQ-2 | — (was: Aditi) |
| OQ-3 | Is the legacy endpoint's `orderDate_thru` inclusive or exclusive? The new contract is `[from, thru)`; AC-1's exact-count parity needs the legacy semantics stated. | RQ-4, AC-1 | HotWax OMS team |
| OQ-4 | Is an unauthenticated pre-signed download URL acceptable, or must the download carry the same credentials as create/poll? Default assumed: same credentials. | RQ-13 | HotWax OMS operations |
| OQ-5 | File retention window? Proposed default: 7 days. | RQ-15 | HotWax OMS operations |
| OQ-6 | Concurrent-export-job limit per tenant/credential? | RQ-16 | HotWax OMS operations |
| OQ-7 | Maximum requestable window size (must be ≥ 28 days; recommend reject-with-clear-error over auto-split)? | RQ-17, RQ-12 | HotWax OMS operations |

## 7. Evidence and provenance

| Claim | Source |
|---|---|
| Current request shape, auth modes, client-side filter rules, excluded-count metadata, pagination fallback | `darpan-backend/runtime/component/darpan-hotwax/docs/oms/rest-order-extraction.md` (read 2026-07-24) |
| Silent 50-record server page cap | `darpan-hotwax` `src/main/groovy/darpan/hotwax/oms/OmsRestSourceSupport.groovy`, constant `OMS_DEFAULT_SERVER_PAGE_SIZE` and its comment |
| 57 MB / 4,154 orders / 272 s / ~260 KB/s / Shopify 22.5 s | Run 100000, saved run RS_GORJANA_PROD, measured 2026-07-24; figures supplied by the Darpan product owner, not independently re-run during authoring |
| ~14 KB/order, ~84 round-trips, ~50× over-fetch | Derived from the above (÷ 4,154; ÷ 50; compared-field payload vs document size) |
| Join key (`externalId` ↔ Shopify `id`), zero field-comparison rules (presence-only), item-level not configured | Live local DB read 2026-07-24: `facade.AuthFacadeServices.login#Session` + `facade.ReconciliationFacadeServices.list#SavedRuns` against rule set `RS_GORJANA_PROD` (tenant GORJANA); `rules` list confirmed empty at source (`ReconciliationSavedRunSupport.collectRuleRows`) |

## 8. Sign-off

Not signed off. Pending review by Aditi Patel (Darpan product owner / Expert
User for Darpan's needs) and the HotWax OMS team (Expert User for OMS
operational constraints — OQ-3..OQ-7). Any READY/implementation-start verdict
downstream must disclose this unsigned state.
