# HotWax OMS REST Order Extraction

Component: `darpan-hotwax`

The component owns HotWax/OMS integration artifacts that do not belong in the core `darpan` component. The first contract is tenant-scoped order extraction for reconciliation automation.

## Source Config

Entity: `darpan.hotwax.HotWaxOmsRestSourceConfig`

Facade services:

- `facade.HotWaxOmsFacadeServices.list#HotWaxOmsRestSourceConfigs`
- `facade.HotWaxOmsFacadeServices.save#HotWaxOmsRestSourceConfig`

Configs are scoped by `companyUserGroupId`. Save operations require active-tenant write access and preserve existing encrypted secrets when blank secret inputs are sent on update. List/save responses return safe metadata only: secret fields are represented as boolean flags and are not returned in clear text. `canReadOrders` controls whether the orders endpoint is advertised to the UI for the selected HotWax config; it defaults to enabled for existing configs.

Required fields:

- `omsRestSourceConfigId`
- `baseUrl`, for example `https://dev-maarg.hotwax.io`. If an OMS base path or full orders endpoint is entered, the extractor avoids duplicating the configured orders path.
- `ordersPath`, default `/rest/s1/oms/orders`
- `timeZone`, default `UTC`, used by setup and source metadata displays for HotWax date-window interpretation
- `canReadOrders`, default `Y`

The dev OMS Swagger document describes the orders API at `/rest/s1/oms/orders`, with `GET /` used to list `OrderHeader` records and Basic or `api_key` header authentication available. Darpan uses that Swagger contract for setup metadata and keeps `/rest/s1/oms/orders` as the default orders path. At runtime the extractor retries the list route with a trailing slash if the first request returns `404`, matching Swagger implementations that require `/rest/s1/oms/orders/`.

Supported auth modes are `NONE`, `BASIC`, `BEARER`, and `API_KEY`. `API_KEY` stores the token in the encrypted API token field and sends it in the Swagger-documented `api_key` header.

## Extractor

Service: `reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsOrders`

The extractor calls:

```text
GET {baseUrl}{ordersPath}?orderDate_from=<startEpochMillis>&orderDate_thru=<endEpochMillis>&pageSize=<n>&pageIndex=<n>
```

`windowStart` and `windowEnd` accept `Timestamp`, `Date`, ISO-8601 text, SQL timestamp text emitted by Moqui service serialization, or epoch milliseconds. Both parameters are always sent as epoch milliseconds.

The extractor paginates order reads before writing the normalized source file. It first uses `pageIndex`/`pageSize`, then falls back to `viewIndex`/`viewSize`, and then `offset`/`limit` if an OMS environment exposes a different list contract. Pagination stops when the next page is empty, repeats the previous page, or is shorter than the previous page. The default requested page size is 500, with a high safety ceiling to avoid runaway loops while still allowing month-scale reconciliation runs.

After pagination completes, the extractor keeps only HotWax orders with `orderTypeId` equal to `SALES_ORDER` and excludes orders that contain an order item association with `orderItemAssocTypeId` equal to `EXCHANGE`. Non-sales orders and exchange orders are not written to the normalized source file and are therefore not compared against Shopify orders. Output metadata includes `filters.excludedNonSalesOrderCount` and `filters.excludedExchangeOrderCount` so the run can distinguish fetched HotWax records from comparison-eligible records.

The output is normalized JSON:

```json
{
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
  },
  "records": []
}
```

The default output folder is `runtime://datamanager/reconciliation-runs/{automationExecutionId}/{timestamp}/`. When `automationExecutionId` is omitted, the config id is used as the run folder token.

The service returns `fileLocation`, `fileName`, `recordCount`, `requestMetadata`, `warnings`, and `errors`. Request metadata excludes credentials and authorization headers.

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
