package darpan.hotwax.oms

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import static darpan.common.ValueSupport.boundedInt
import static darpan.common.ValueSupport.normalize
import static darpan.common.ValueSupport.normalizeBool
import static darpan.common.ValueSupport.normalizeInt

import java.nio.charset.StandardCharsets
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.net.URLEncoder
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.zip.GZIPInputStream

class OmsRestSourceSupport {
    static final String DEFAULT_ORDERS_PATH = "/rest/s1/oms/orders"
    static final String DEFAULT_FILE_NAME_PREFIX = "oms-orders"
    static final String DEFAULT_API_KEY_HEADER_NAME = "api_key"
    static final String DEFAULT_TIME_ZONE = "UTC"
    static final String SALES_ORDER_TYPE_ID = "SALES_ORDER"
    static final String EXCHANGE_ORDER_ASSOC_TYPE_ID = "EXCHANGE"
    static final int EXCHANGE_MANIFEST_MAX_ENTRIES = 500
    static final String ORDER_TYPE_ID_FIELD = "orderTypeId"
    static final String ORDER_ITEM_ASSOC_TYPE_ID_FIELD = "orderItemAssocTypeId"
    static final int DEFAULT_ORDERS_PAGE_SIZE = 500
    static final int MAX_ORDERS_PAGE_COUNT = 20000
    // Default prefetch window after the pagination strategy commits. Two keeps one page in
    // flight while the previous is consumed — chosen for memory-constrained production hosts;
    // raise per config (ordersFetchConcurrency, capped at 4) where the box has headroom.
    static final int DEFAULT_ORDERS_FETCH_CONCURRENCY = 2
    // Some OMS list endpoints silently cap a page at 50 records regardless of the requested pageSize;
    // a 50-record first page is therefore treated as "maybe truncated" and triggers a second-page probe.
    static final int OMS_DEFAULT_SERVER_PAGE_SIZE = 50

    private static final JsonSlurper JSON_SLURPER = new JsonSlurper()
    private static final List<String> CONFIG_FIELD_NAMES = [
            "omsRestSourceConfigId",
            "description",
            "companyUserGroupId",
            "createdByUserId",
            "baseUrl",
            "ordersPath",
            "timeZone",
            "authType",
            "username",
            "password",
            "apiToken",
            "headersJson",
            "connectTimeoutSeconds",
            "readTimeoutSeconds",
            "isActive",
            "canReadOrders",
            "createdDate",
            "lastUpdatedDate",
    ]
    private static final List<Closure<Long>> WINDOW_MILLIS_PARSERS = [
            { String text -> Instant.parse(text).toEpochMilli() },
            { String text -> OffsetDateTime.parse(text).toInstant().toEpochMilli() },
            { String text -> ZonedDateTime.parse(text).toInstant().toEpochMilli() },
            { String text -> Timestamp.valueOf(text).time },
            { String text -> LocalDateTime.parse(text).toInstant(ZoneOffset.UTC).toEpochMilli() },
            { String text -> LocalDate.parse(text).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() },
    ]
    private static final List<Map<String, Object>> PAGINATION_STRATEGIES = [
            [name: "pageIndexPageSize", indexParam: "pageIndex", sizeParam: "pageSize"],
            [name: "viewIndexViewSize", indexParam: "viewIndex", sizeParam: "viewSize"],
    ]
    private static final Set<Integer> RECOVERABLE_PAGINATION_STATUS_CODES = [400, 404, 405, 422] as Set
    private static final List<String> ORDER_LIST_KEYS = ["orders", "order", "data", "items", "records", "results"]
    private static final Closure DEFAULT_HTTP_CLIENT = { Map request -> executeHttpRequest(request) }
    private static Closure httpClient = DEFAULT_HTTP_CLIENT

    static void setHttpClient(Closure client) {
        httpClient = client ?: DEFAULT_HTTP_CLIENT
    }

    static void resetHttpClient() {
        httpClient = DEFAULT_HTTP_CLIENT
    }

    static Map<String, Object> extractOrders(Object rawConfig, Object windowStart, Object windowEnd,
                                             List keepRecordFields = null, Closure pageProgressListener = null) {
        // In-memory variant kept for tests and small interactive windows. The automation path must
        // use extractOrdersToFile so month-scale windows never materialize whole in heap.
        StringWriter buffer = new StringWriter()
        Map<String, Object> result = extractOrdersInternal(rawConfig, windowStart, windowEnd,
                new OrdersDocumentSink({ -> buffer }), keepRecordFields, pageProgressListener)
        if (!(result.errors as List)) {
            String outputText = buffer.toString()
            result.outputText = outputText
            // Convenience for in-memory callers only; the streaming pipeline never retains records.
            result.records = ((JSON_SLURPER.parseText(outputText) as Map).records as List) ?: []
        }
        return result
    }

    static Map<String, Object> extractOrdersToFile(Object rawConfig, Object windowStart, Object windowEnd,
                                                   File targetFile, List keepRecordFields = null,
                                                   Closure pageProgressListener = null) {
        OrdersDocumentSink sink = new OrdersDocumentSink({ ->
            targetFile.getParentFile()?.mkdirs()
            return new BufferedWriter(new OutputStreamWriter(new FileOutputStream(targetFile), StandardCharsets.UTF_8))
        })
        Map<String, Object> result
        try {
            result = extractOrdersInternal(rawConfig, windowStart, windowEnd, sink, keepRecordFields, pageProgressListener)
        } catch (Exception e) {
            sink.abort()
            targetFile.delete()
            throw e
        }
        if (result.errors) {
            sink.abort()
            targetFile.delete()
        }
        result.remove("records")
        return result
    }

    private static Map<String, Object> extractOrdersInternal(Object rawConfig, Object windowStart, Object windowEnd,
                                                             OrdersDocumentSink sink, List keepRecordFields = null,
                                                             Closure pageProgressListener = null) {
        Set<String> keepFieldSet = normalizeKeepFields(keepRecordFields)
        Map config = toPlainMap(rawConfig)
        // Synchronized: page-preparation runs on fetch-pool worker threads under concurrency.
        List<String> warnings = Collections.synchronizedList(new ArrayList<String>())
        List<String> errors = []

        Long fromMillis = parseWindowMillis(windowStart, "windowStart", errors)
        Long thruMillis = parseWindowMillis(windowEnd, "windowEnd", errors)
        if (fromMillis != null && thruMillis != null && fromMillis > thruMillis) {
            errors.add("windowStart must be before or equal to windowEnd.")
        }

        String baseUrl = normalize(config?.baseUrl)
        String ordersPath = normalize(config?.ordersPath) ?: DEFAULT_ORDERS_PATH
        String timeZone = normalize(config?.timeZone) ?: DEFAULT_TIME_ZONE
        if (!baseUrl) errors.add("Base URL is required.")

        Map<String, String> headers = [:]
        if (!errors) {
            try {
                headers = buildHeaders(config)
            } catch (IllegalArgumentException e) {
                errors.add(e.message)
            }
        }

        String endpointUrl = null
        Map<String, Object> requestMetadata = [
                method     : "GET",
                baseUrl    : sanitizeBaseUrl(baseUrl),
                ordersPath : normalizeOrdersPath(ordersPath),
                queryParams: [
                        orderDate_from: fromMillis,
                        orderDate_thru: thruMillis,
                ],
                authType   : normalize(config?.authType)?.toUpperCase() ?: "NONE",
                timeZone   : timeZone,
                headerNames: safeHeaderNames(headers),
                pagination : [
                        pageSize    : resolveOrdersPageSize(config),
                        pageCount   : 0,
                        strategy    : null,
                        totalFetched: 0,
                ],
        ]

        if (!errors) {
            endpointUrl = buildOrdersEndpointUrl(baseUrl, ordersPath)
        }

        Map<String, Object> baseResult = [
                dataAvailable  : false,
                recordCount    : 0,
                records        : [],
                requestMetadata: requestMetadata,
                warnings       : warnings,
                errors         : errors,
                fromMillis     : fromMillis,
                thruMillis     : thruMillis,
                fileName       : buildDefaultFileName(fromMillis, thruMillis),
        ]
        if (errors) return baseResult

        int excludedNonSalesOrderCount = 0
        int excludedExchangeOrderCount = 0
        int extractedRecordCount = 0
        int consumedRawCount = 0
        List exchangeManifest = []
        boolean exchangeManifestTruncated = false
        Closure pageConsumer = { Map<String, Object> pageBundle ->
            // Pages arrive as pre-filtered, pre-serialized bundles (built on the fetch thread),
            // so consuming a page is an append plus counter bumps — no parsed graphs retained.
            excludedNonSalesOrderCount += (int) pageBundle.excludedNonSalesOrderCount
            excludedExchangeOrderCount += (int) pageBundle.excludedExchangeOrderCount
            for (Object entry : (List) (pageBundle.excludedExchangeOrders ?: [])) {
                if (exchangeManifest.size() >= EXCHANGE_MANIFEST_MAX_ENTRIES) { exchangeManifestTruncated = true; break }
                exchangeManifest.add(entry)
            }
            int filteredCount = (int) pageBundle.filteredCount
            if (filteredCount > 0) sink.writeSerializedPage((String) pageBundle.serializedRecords, filteredCount)
            extractedRecordCount += filteredCount
            consumedRawCount += (int) pageBundle.rawCount
            if (pageProgressListener != null) {
                // Progress is advisory: a listener failure must never fail the extraction.
                try {
                    pageProgressListener.call(consumedRawCount)
                } catch (Exception ignored) {
                }
            }
        }

        if (keepFieldSet) {
            requestMetadata.projection = [keepRecordFields: keepFieldSet.sort()]
        }

        Map extraction
        try {
            extraction = extractAllOrderPages(endpointUrl, fromMillis, thruMillis, headers, config, warnings,
                    pageConsumer, keepFieldSet)
        } catch (IOException e) {
            sink.abort()
            errors.add("Failed writing OMS extract output: ${e.message}".toString())
            return baseResult
        }
        requestMetadata.statusCode = extraction.statusCode
        requestMetadata.attemptCount = extraction.attemptCount ?: 0
        if (extraction.retriedWithTrailingSlash) requestMetadata.retriedWithTrailingSlash = true
        requestMetadata.pagination = extraction.pagination ?: requestMetadata.pagination
        if (requestMetadata.pagination instanceof Map) {
            ((Map) requestMetadata.pagination).fetchConcurrency = resolveOrdersFetchConcurrency(config)
        }
        if (extraction.errors) {
            sink.abort()
            errors.addAll((List) extraction.errors)
            return baseResult
        }

        requestMetadata.filters = [
                requiredOrderTypeId          : SALES_ORDER_TYPE_ID,
                excludedNonSalesOrderCount   : excludedNonSalesOrderCount,
                excludedOrderItemAssocTypeIds: [EXCHANGE_ORDER_ASSOC_TYPE_ID],
                excludedExchangeOrderCount   : excludedExchangeOrderCount,
                exchangeManifestTruncated    : exchangeManifestTruncated,
        ]
        Map documentMetadata = requestMetadata + [
                sourceType            : "HOTWAX_OMS_REST_ORDERS",
                omsRestSourceConfigId : normalize(config?.omsRestSourceConfigId),
                windowStartEpochMillis: fromMillis,
                windowEndEpochMillis  : thruMillis,
                extractedRecordCount  : extractedRecordCount,
        ]
        try {
            sink.finish(documentMetadata)
        } catch (IOException e) {
            sink.abort()
            errors.add("Failed writing OMS extract output: ${e.message}".toString())
            return baseResult
        }
        return baseResult + [
                dataAvailable: extractedRecordCount > 0,
                recordCount  : extractedRecordCount,
                warnings     : warnings,
                errors       : errors,
                exchangeManifest         : exchangeManifest,
                exchangeManifestTruncated: exchangeManifestTruncated,
        ]
    }

    /**
     * Streams the extract document as {"records":[...],"metadata":{...}} — records first so pages
     * can be appended as they arrive and the metadata (counts, pagination) written once at the
     * end. Key order is irrelevant to consumers: the file is read by JSON parsers (Spark multiLine
     * JSON + JSONPath), never positionally. The writer is opened lazily so rejected requests never
     * create a file, and flushed per page so committed pages live on disk, not in heap.
     */
    protected static class OrdersDocumentSink {
        private final Closure<Writer> writerFactory
        private Writer writer
        private int writtenRecordCount = 0

        OrdersDocumentSink(Closure<Writer> writerFactory) {
            this.writerFactory = writerFactory
        }

        private void ensureOpen() {
            if (writer == null) {
                writer = writerFactory.call()
                writer.write('{"records":[')
            }
        }

        void writeSerializedPage(String serializedRecords, int recordCount) {
            if (recordCount <= 0) return
            ensureOpen()
            if (writtenRecordCount > 0) writer.write(',')
            writer.write(serializedRecords)
            writtenRecordCount += recordCount
            writer.flush()
        }

        void finish(Map metadata) {
            ensureOpen()
            writer.write('],"metadata":')
            writer.write(JsonOutput.toJson(metadata))
            writer.write('}')
            writer.flush()
            writer.close()
            writer = null
        }

        void abort() {
            if (writer != null) {
                try {
                    writer.close()
                } catch (IOException ignored) {
                }
                writer = null
            }
        }
    }

    static Map<String, Object> safeConfigMap(def cfg) {
        return safeConfigMapFromPlain(toPlainMap(cfg))
    }

    private static Map<String, Object> safeConfigMapFromPlain(Map config) {
        return [
                omsRestSourceConfigId : config.omsRestSourceConfigId,
                description           : config.description,
                companyUserGroupId    : config.companyUserGroupId,
                baseUrl               : sanitizeBaseUrl(config.baseUrl),
                ordersPath            : normalize(config.ordersPath) ?: DEFAULT_ORDERS_PATH,
                timeZone              : normalize(config.timeZone) ?: DEFAULT_TIME_ZONE,
                authType              : normalize(config.authType)?.toUpperCase() ?: "NONE",
                hasUsername           : !!normalize(config.username),
                hasPassword           : !!normalize(config.password),
                hasApiToken           : !!normalize(config.apiToken),
                customHeaderNames     : safeHeaderNames(parseHeadersJson(config.headersJson)),
                connectTimeoutSeconds : normalizeInt(config.connectTimeoutSeconds, 30),
                readTimeoutSeconds    : normalizeInt(config.readTimeoutSeconds, 60),
                isActive              : normalizeBool(config.isActive, true) ? "Y" : "N",
                canReadOrders         : normalizeBool(config.canReadOrders, true),
                createdDate           : config.createdDate,
                lastUpdatedDate       : config.lastUpdatedDate,
        ]
    }

    static void requireWritableTenantConfig(Map existingConfig, String activeTenantUserGroupId, boolean canWrite) {
        String tenantId = normalize(activeTenantUserGroupId)
        if (!tenantId) throw new IllegalArgumentException("An active tenant is required for tenant-scoped writes.")
        if (!canWrite) throw new IllegalArgumentException("Your active tenant is read-only for this action.")
        if (existingConfig && normalize(existingConfig.companyUserGroupId) != tenantId) {
            throw new IllegalArgumentException("Requested OMS source config is not available in your active tenant.")
        }
    }

    static String safeFileName(Object rawName, String fallback = null) {
        String normalized = normalize(rawName)
        String fileName = normalized ? normalized.tokenize("/\\").last() : normalize(fallback)
        if (!fileName) fileName = buildDefaultFileName(null, null)
        fileName = fileName.replaceAll(/[^A-Za-z0-9._-]/, "_").replaceAll(/^\.+/, "")
        if (!fileName) fileName = buildDefaultFileName(null, null)
        return fileName.toLowerCase().endsWith(".json") ? fileName : "${fileName}.json"
    }

    static String buildDefaultFileName(Long fromMillis, Long thruMillis) {
        String fromToken = fromMillis != null ? fromMillis.toString() : "start"
        String thruToken = thruMillis != null ? thruMillis.toString() : "end"
        return "${DEFAULT_FILE_NAME_PREFIX}-${fromToken}-${thruToken}.json"
    }

    protected static Long parseWindowMillis(Object rawValue, String label, List<String> errors) {
        if (rawValue == null) {
            errors.add("${label} is required.")
            return null
        }
        if (rawValue instanceof Number) return ((Number) rawValue).longValue()
        if (rawValue instanceof Timestamp) return ((Timestamp) rawValue).time
        if (rawValue instanceof Date) return ((Date) rawValue).time
        if (rawValue instanceof Instant) return ((Instant) rawValue).toEpochMilli()
        if (rawValue instanceof OffsetDateTime) return ((OffsetDateTime) rawValue).toInstant().toEpochMilli()
        if (rawValue instanceof ZonedDateTime) return ((ZonedDateTime) rawValue).toInstant().toEpochMilli()
        if (rawValue instanceof LocalDateTime) return ((LocalDateTime) rawValue).toInstant(ZoneOffset.UTC).toEpochMilli()
        if (rawValue instanceof LocalDate) return ((LocalDate) rawValue).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()

        String value = normalize(rawValue)
        if (!value) {
            errors.add("${label} is required.")
            return null
        }
        if (value ==~ /-?\d+/) {
            try {
                return Long.parseLong(value)
            } catch (NumberFormatException ignored) {
                errors.add("${label} must be a Timestamp, Date, ISO-8601 value, or epoch milliseconds.")
                return null
            }
        }

        for (Closure<Long> parser : WINDOW_MILLIS_PARSERS) {
            try {
                return parser.call(value)
            } catch (Exception ignored) {
            }
        }

        errors.add("${label} must be a Timestamp, Date, ISO-8601 value, or epoch milliseconds.")
        return null
    }

    protected static String buildOrdersUrl(String endpointUrl, Long fromMillis, Long thruMillis,
                                           Map<String, Object> extraQueryParams) {
        String separator = endpointUrl.contains("?") ? "&" : "?"
        Map<String, Object> queryParams = new LinkedHashMap<>()
        queryParams.orderDate_from = fromMillis
        queryParams.orderDate_thru = thruMillis
        queryParams.putAll(extraQueryParams ?: [:])
        return "${endpointUrl}${separator}${queryParams.collect { key, value -> "${encodeQueryComponent(key)}=${encodeQueryComponent(value)}" }.join("&")}"
    }

    protected static String buildOrdersEndpointUrl(String baseUrl, String ordersPath) {
        String normalizedBase = normalize(baseUrl)?.replaceAll(/\/+$/, "")
        String normalizedPath = normalizeOrdersPath(ordersPath)
        if (!normalizedBase) return normalizedPath

        try {
            URI uri = new URI(normalizedBase)
            if (uri.scheme && uri.host) {
                String joinedPath = joinPathWithOverlap(uri.path, normalizedPath)
                // Drop any userInfo (basic-auth embedded in the URL) from the live request URL so
                // credentials cannot leak into request paths, logs, or connection-failure error text.
                // URL-embedded auth is not a supported mode here — use username/password/apiToken.
                return new URI(uri.scheme, null, uri.host, uri.port, joinedPath, uri.query, uri.fragment).toString()
            }
        } catch (Exception ignored) {
        }

        return "${normalizedBase}${suffixAfterPathOverlap(normalizedBase, normalizedPath)}"
    }

    protected static String normalizeOrdersPath(Object rawPath) {
        String path = normalize(rawPath) ?: DEFAULT_ORDERS_PATH
        path = path.replaceAll(/\\+/, "/")
        return path.startsWith("/") ? path : "/${path}"
    }

    protected static String normalizePath(Object rawPath) {
        String path = normalize(rawPath)?.replaceAll(/\\+/, "/")
        if (!path) return ""
        path = path.replaceAll(/\/+$/, "")
        if (!path) return "/"
        return path.startsWith("/") ? path : "/${path}"
    }

    protected static String joinPathWithOverlap(Object rawBasePath, String rawChildPath) {
        String basePath = normalizePath(rawBasePath)
        String childPath = normalizePath(rawChildPath)
        if (!basePath || basePath == "/") return childPath
        if (!childPath || childPath == "/") return basePath

        List<String> baseSegments = basePath.tokenize("/")
        List<String> childSegments = childPath.tokenize("/")
        int overlap = overlappingSegmentCount(baseSegments, childSegments)
        List<String> joinedSegments = []
        joinedSegments.addAll(baseSegments)
        joinedSegments.addAll(childSegments.drop(overlap))
        return "/" + joinedSegments.join("/")
    }

    protected static String suffixAfterPathOverlap(String normalizedBase, String normalizedPath) {
        try {
            URI uri = new URI(normalizedBase)
            return "/" + joinPathWithOverlap(uri.path, normalizedPath).tokenize("/").join("/")
        } catch (Exception ignored) {
        }

        List<String> baseSegments = normalizePath(normalizedBase).tokenize("/")
        List<String> pathSegments = normalizePath(normalizedPath).tokenize("/")
        int overlap = overlappingSegmentCount(baseSegments, pathSegments)
        List<String> suffixSegments = pathSegments.drop(overlap)
        return suffixSegments ? "/" + suffixSegments.join("/") : ""
    }

    protected static int overlappingSegmentCount(List<String> baseSegments, List<String> childSegments) {
        int maxOverlap = Math.min(baseSegments?.size() ?: 0, childSegments?.size() ?: 0)
        for (int overlap = maxOverlap; overlap > 0; overlap--) {
            List<String> baseSuffix = baseSegments.subList(baseSegments.size() - overlap, baseSegments.size())
                    .collect { String segment -> segment.toLowerCase(Locale.ROOT) }
            List<String> childPrefix = childSegments.subList(0, overlap)
                    .collect { String segment -> segment.toLowerCase(Locale.ROOT) }
            if (baseSuffix == childPrefix) return overlap
        }
        return 0
    }

    protected static String trailingSlashBeforeQuery(String rawUrl) {
        String url = normalize(rawUrl)
        if (!url) return url
        int queryIndex = url.indexOf("?")
        String pathPart = queryIndex >= 0 ? url.substring(0, queryIndex) : url
        if (pathPart.endsWith("/")) return url
        return pathPart + "/" + (queryIndex >= 0 ? url.substring(queryIndex) : "")
    }

    protected static Map<String, Object> callOmsEndpoint(String requestUrl, Map<String, String> headers, Map config,
                                                         boolean acceptGzip = true) {
        return (httpClient.call([
                method               : "GET",
                url                  : requestUrl,
                headers              : headers,
                acceptGzip           : acceptGzip,
                connectTimeoutSeconds: normalizeInt(config?.connectTimeoutSeconds, 30),
                readTimeoutSeconds   : normalizeInt(config?.readTimeoutSeconds, 60),
        ]) ?: [:]) as Map<String, Object>
    }

    protected static Map<String, Object> extractAllOrderPages(String endpointUrl, Long fromMillis, Long thruMillis,
                                                              Map<String, String> headers, Map config,
                                                              List<String> warnings, Closure pageConsumer,
                                                              Set<String> keepFieldSet = null) {
        int pageSize = resolveOrdersPageSize(config)
        int maxPageCount = Math.max(1, normalizeInt(config?.maxOrdersPageCount, MAX_ORDERS_PAGE_COUNT))
        int fetchConcurrency = resolveOrdersFetchConcurrency(config)

        for (Map<String, Object> strategy : PAGINATION_STRATEGIES) {
            Map<String, Object> firstPage = prepareOrdersPage(endpointUrl, fromMillis, thruMillis,
                    pageQueryParams(strategy, 0, pageSize), headers, config, warnings, keepFieldSet)
            if (!firstPage.success) {
                if (isRecoverablePaginationFailure(firstPage.statusCode)) continue
                return failedPageResult(firstPage)
            }

            List<Map<String, Object>> pageMetas = [pageMeta(firstPage)]
            if ((int) firstPage.rawCount == 0) return successfulPageResult(strategy, pageSize, pageMetas, 0)
            if (!shouldProbeSecondPage((int) firstPage.rawCount, pageSize)) {
                pageConsumer.call(firstPage)
                return successfulPageResult(strategy, pageSize, pageMetas, (int) firstPage.rawCount)
            }

            Map<String, Object> secondPage = prepareOrdersPage(endpointUrl, fromMillis, thruMillis,
                    pageQueryParams(strategy, 1, pageSize), headers, config, warnings, keepFieldSet)
            if (!secondPage.success) {
                if (isRecoverablePaginationFailure(secondPage.statusCode)) continue
                return failedPageResult(secondPage, pageMetas)
            }

            pageMetas.add(pageMeta(secondPage))
            if ((int) secondPage.rawCount == 0) {
                pageConsumer.call(firstPage)
                return successfulPageResult(strategy, pageSize, pageMetas, (int) firstPage.rawCount)
            }
            if (sameOrderPageBundles(firstPage, secondPage)) {
                warnings.add("OMS REST pagination strategy ${strategy.name} did not advance beyond the first page.")
                continue
            }

            // Strategy committed: consume the probe pages, then stream the remainder through a
            // small prefetch window. Worker threads carry fetch+parse+filter+serialize and hand
            // back only compact serialized text, so with window W the steady-state overhead is
            // ~W serialized pages — sized for resource-constrained production hosts.
            pageConsumer.call(firstPage)
            pageConsumer.call(secondPage)
            int rawFetchedCount = ((int) firstPage.rawCount) + ((int) secondPage.rawCount)
            boolean secondPageShrank = ((int) secondPage.rawCount) < ((int) firstPage.rawCount)
            Map<String, Object> previousPage = secondPage
            firstPage = null
            secondPage = null
            if (secondPageShrank) return successfulPageResult(strategy, pageSize, pageMetas, rawFetchedCount)

            ExecutorService fetchPool = fetchConcurrency > 1 ? Executors.newFixedThreadPool(fetchConcurrency) : null
            try {
                Map<Integer, Future<Map<String, Object>>> inFlightPages = new LinkedHashMap<>()
                int nextPageToRequest = 2
                int pageIndex = 2
                while (pageIndex < maxPageCount) {
                    while (fetchPool != null && nextPageToRequest < maxPageCount && inFlightPages.size() < fetchConcurrency) {
                        int requestIndex = nextPageToRequest++
                        inFlightPages.put(requestIndex, fetchPool.submit({ ->
                            prepareOrdersPage(endpointUrl, fromMillis, thruMillis,
                                    pageQueryParams(strategy, requestIndex, pageSize), headers, config, warnings, keepFieldSet)
                        } as Callable<Map<String, Object>>))
                    }

                    Map<String, Object> page = fetchPool != null ?
                            inFlightPages.remove(pageIndex).get() :
                            prepareOrdersPage(endpointUrl, fromMillis, thruMillis,
                                    pageQueryParams(strategy, pageIndex, pageSize), headers, config, warnings, keepFieldSet)
                    pageMetas.add(pageMeta(page))
                    if (!page.success) return failedPageResult(page, pageMetas)

                    if ((int) page.rawCount == 0) break
                    if (sameOrderPageBundles(previousPage, page)) {
                        warnings.add("OMS REST pagination strategy ${strategy.name} stopped because page ${pageIndex} repeated the previous page.")
                        break
                    }

                    pageConsumer.call(page)
                    rawFetchedCount += (int) page.rawCount
                    boolean pageShrank = ((int) page.rawCount) < ((int) previousPage.rawCount)
                    previousPage = page
                    if (pageShrank) break
                    pageIndex++
                }

                if (pageIndex >= maxPageCount) {
                    return [
                            errors    : ["OMS REST pagination exceeded ${maxPageCount} pages for the selected time period."],
                            statusCode: latestStatusCode(pageMetas),
                            attemptCount: totalAttemptCount(pageMetas),
                            retriedWithTrailingSlash: anyTrailingSlashRetry(pageMetas),
                            pagination: paginationMetadata(strategy, pageSize, pageMetas, rawFetchedCount, true),
                    ]
                }

                return successfulPageResult(strategy, pageSize, pageMetas, rawFetchedCount)
            } finally {
                // Ends speculative fetches on every exit path (success, failure, truncation).
                fetchPool?.shutdownNow()
            }
        }

        Map<String, Object> unpaginatedPage = prepareOrdersPage(endpointUrl, fromMillis, thruMillis, [:], headers, config, warnings, keepFieldSet)
        if (!unpaginatedPage.success) return failedPageResult(unpaginatedPage)
        warnings.add("OMS REST pagination parameters did not advance; extracted the first unpaginated response only.")
        List<Map<String, Object>> unpaginatedMetas = [pageMeta(unpaginatedPage)]
        int unpaginatedRawCount = (int) unpaginatedPage.rawCount
        if (unpaginatedRawCount > 0) pageConsumer.call(unpaginatedPage)
        return successfulPageResult([name: "unpaginated"], pageSize, unpaginatedMetas, unpaginatedRawCount)
    }

    /**
     * Fetches one page and converts it to a compact "page bundle" on the calling thread:
     * pre-filtered records serialized to comma-joined JSON text, plus counts and a raw-content
     * hash for the repeated-page guard. The parsed record graph never leaves this method, which
     * is what keeps concurrent prefetching cheap on memory: an in-flight page costs roughly its
     * serialized text, not a parsed object graph.
     */
    protected static Map<String, Object> prepareOrdersPage(String endpointUrl, Long fromMillis, Long thruMillis,
                                                           Map<String, Object> pageParams,
                                                           Map<String, String> headers, Map config,
                                                           List<String> warnings, Set<String> keepFieldSet = null) {
        Map<String, Object> page = fetchOrdersPage(endpointUrl, fromMillis, thruMillis, pageParams, headers, config, warnings)
        if (!page.success) return page
        List rawRecords = page.records ?: []
        Map<String, Object> pageFilter = filterComparableOrderRecords(rawRecords)
        List filtered = (List) pageFilter.records
        // Projection happens after filtering (the EXCHANGE scan needs the full record) and before
        // serialization, so a trimmed extract writes ~90x less than the full order documents.
        List outputRecords = keepFieldSet ? filtered.collect { Object record -> trimRecord(record, keepFieldSet) } : filtered
        StringBuilder serialized = new StringBuilder(Math.max(16, outputRecords.size() * 512))
        boolean firstRecord = true
        for (Object record : outputRecords) {
            if (!firstRecord) serialized.append(',')
            serialized.append(JsonOutput.toJson(record))
            firstRecord = false
        }
        return [
                success                   : true,
                statusCode                : page.statusCode,
                attemptCount              : page.attemptCount,
                retriedWithTrailingSlash  : page.retriedWithTrailingSlash,
                rawCount                  : rawRecords.size(),
                rawHash                   : rawRecords.hashCode(),
                filteredCount             : filtered.size(),
                excludedNonSalesOrderCount: pageFilter.excludedNonSalesOrderCount,
                excludedExchangeOrderCount: pageFilter.excludedExchangeOrderCount,
                excludedExchangeOrders    : pageFilter.excludedExchangeOrders,
                serializedRecords         : serialized.toString(),
        ]
    }

    protected static Set<String> normalizeKeepFields(List keepRecordFields) {
        if (!keepRecordFields) return null
        Set<String> keepFieldSet = new LinkedHashSet<String>()
        keepRecordFields.each { Object field ->
            String name = normalize(field)
            if (name) keepFieldSet.add(name)
        }
        return keepFieldSet ?: null
    }

    protected static Map trimRecord(Object record, Set<String> keepFieldSet) {
        if (!(record instanceof Map)) return [:]
        Map trimmed = new LinkedHashMap()
        ((Map) record).each { Object key, Object value ->
            if (keepFieldSet.contains(key?.toString()?.trim())) trimmed.put(key, value)
        }
        return trimmed
    }

    // Repeated-page detection without retaining parsed pages: raw count + deep hash of the raw
    // records + filtered serialization must all match. A true server repeat matches all three;
    // a false positive needs a raw-content hash collision on adjacent pages with identical
    // filtered text, which is negligible for this warning-and-stop heuristic.
    private static boolean sameOrderPageBundles(Map<String, Object> left, Map<String, Object> right) {
        if (left == null || right == null) return false
        return left.rawCount == right.rawCount &&
                left.rawHash == right.rawHash &&
                Objects.equals(left.serializedRecords, right.serializedRecords)
    }

    /** Per-page bookkeeping kept after a page's records are consumed — never the records themselves. */
    private static Map<String, Object> pageMeta(Map<String, Object> page) {
        return [
                statusCode              : page?.statusCode,
                attemptCount            : page?.attemptCount,
                retriedWithTrailingSlash: page?.retriedWithTrailingSlash,
        ]
    }

    protected static Map<String, Object> fetchOrdersPage(String endpointUrl, Long fromMillis, Long thruMillis,
                                                         Map<String, Object> pageParams,
                                                         Map<String, String> headers, Map config,
                                                         List<String> warnings) {
        String requestUrl = buildOrdersUrl(endpointUrl, fromMillis, thruMillis, pageParams)
        Map response
        boolean retriedWithTrailingSlash = false
        boolean retriedWithoutCompression = false
        int attemptCount = 1
        try {
            try {
                response = callOmsEndpoint(requestUrl, headers, config)
            } catch (SocketTimeoutException timeoutException) {
                // A gzip-buffering proxy may hold the entire response until page generation
                // completes, so first-byte can exceed the read timeout even though the server is
                // healthy. Retry the page once with compression disabled: a plain streamed
                // response trickles bytes continuously and keeps the read timer alive.
                warnings.add("OMS REST page request timed out (${timeoutException.message}); retrying once without compression.".toString())
                attemptCount = 2
                retriedWithoutCompression = true
                response = callOmsEndpoint(requestUrl, headers, config, false)
            }
            Integer firstStatusCode = normalizeInt(response.statusCode, 0)
            if (firstStatusCode == 404) {
                String retryUrl = trailingSlashBeforeQuery(requestUrl)
                if (retryUrl && retryUrl != requestUrl) {
                    response = callOmsEndpoint(retryUrl, headers, config, !retriedWithoutCompression)
                    attemptCount++
                    retriedWithTrailingSlash = true
                }
            }
        } catch (Exception e) {
            return pageFailure(0, attemptCount, retriedWithTrailingSlash, "OMS REST request failed: ${e.message}")
        }

        Integer statusCode = normalizeInt(response.statusCode, 0)
        if (statusCode < 200 || statusCode >= 300) {
            return pageFailure(statusCode, attemptCount, retriedWithTrailingSlash, "OMS REST request failed with status ${statusCode}.")
        }

        Object parsed
        String body = response.body?.toString()
        if (!body) {
            parsed = [orders: []]
            warnings.add("OMS REST response body was empty.")
        } else {
            try {
                // Fresh parser per call: pages parse on fetch-pool worker threads under concurrency.
                parsed = new JsonSlurper().parseText(body)
            } catch (Exception e) {
                return pageFailure(statusCode, attemptCount, retriedWithTrailingSlash, "OMS REST response was not valid JSON: ${e.message}")
            }
        }

        return [
                success   : true,
                statusCode: statusCode,
                attemptCount: attemptCount,
                retriedWithTrailingSlash: retriedWithTrailingSlash,
                records   : extractOrderRecords(parsed, warnings),
        ]
    }

    private static Map<String, Object> pageFailure(int statusCode, int attemptCount, boolean retriedWithTrailingSlash, String error) {
        return [
                success                 : false,
                statusCode              : statusCode,
                attemptCount            : attemptCount,
                retriedWithTrailingSlash: retriedWithTrailingSlash,
                errors                  : [error],
        ]
    }

    protected static Map<String, Object> pageQueryParams(Map<String, Object> strategy, int pageIndex, int pageSize) {
        Map<String, Object> params = new LinkedHashMap<>()
        params[(String) strategy.sizeParam] = pageSize
        params[(String) strategy.indexParam] = pageIndex
        return params
    }

    protected static boolean shouldProbeSecondPage(int rawCount, int pageSize) {
        return rawCount >= pageSize || rawCount == OMS_DEFAULT_SERVER_PAGE_SIZE
    }

    protected static boolean isRecoverablePaginationFailure(Object statusCode) {
        int status = normalizeInt(statusCode, 0)
        return RECOVERABLE_PAGINATION_STATUS_CODES.contains(status)
    }

    protected static Map<String, Object> successfulPageResult(Map<String, Object> strategy, int pageSize,
                                                             List<Map<String, Object>> pageMetas, int rawFetchedCount) {
        return [
                errors    : [],
                statusCode: latestStatusCode(pageMetas),
                attemptCount: totalAttemptCount(pageMetas),
                retriedWithTrailingSlash: anyTrailingSlashRetry(pageMetas),
                pagination: paginationMetadata(strategy, pageSize, pageMetas, rawFetchedCount, false),
        ]
    }

    protected static Map<String, Object> failedPageResult(Map<String, Object> failedPage,
                                                         List<Map<String, Object>> pages = []) {
        List<Map<String, Object>> allPages = []
        allPages.addAll(pages ?: [])
        if (failedPage) allPages.add(failedPage)
        return [
                errors    : (failedPage?.errors ?: ["OMS REST request failed."]) as List,
                statusCode: failedPage?.statusCode ?: latestStatusCode(allPages),
                attemptCount: totalAttemptCount(allPages),
                retriedWithTrailingSlash: anyTrailingSlashRetry(allPages),
                pagination: [
                        pageSize    : null,
                        pageCount   : allPages.size(),
                        strategy    : null,
                        totalFetched: 0,
                ],
        ]
    }

    protected static Map<String, Object> paginationMetadata(Map<String, Object> strategy, int pageSize,
                                                           List<Map<String, Object>> pages, int recordCount,
                                                           boolean truncated) {
        return [
                pageSize    : pageSize,
                pageCount   : pages?.size() ?: 0,
                strategy    : strategy?.name,
                totalFetched: recordCount,
                truncated   : truncated,
        ]
    }

    protected static int totalAttemptCount(List<Map<String, Object>> pages) {
        return (pages ?: []).sum { Map page -> normalizeInt(page?.attemptCount, 0) } as int
    }

    protected static Integer latestStatusCode(List<Map<String, Object>> pages) {
        return ((pages ?: []).reverse().find { it?.statusCode != null }?.statusCode ?: 0) as Integer
    }

    protected static boolean anyTrailingSlashRetry(List<Map<String, Object>> pages) {
        return (pages ?: []).any { Map page -> page?.retriedWithTrailingSlash == true }
    }

    protected static int resolveOrdersPageSize(Map config) {
        return boundedInt(config?.ordersPageSize, DEFAULT_ORDERS_PAGE_SIZE, 1, 1000)
    }

    // Bounded 1..4: production hosts are resource-constrained, and each additional in-flight
    // page costs one serialized page of heap plus one transient parse on a worker thread.
    protected static int resolveOrdersFetchConcurrency(Map config) {
        return boundedInt(config?.ordersFetchConcurrency, DEFAULT_ORDERS_FETCH_CONCURRENCY, 1, 4)
    }

    protected static Map<String, String> buildHeaders(Map config) {
        Map<String, String> headers = new LinkedHashMap<>()
        headers.put("Accept", "application/json")
        headers.putAll(parseHeadersJson(config?.headersJson))

        String authType = normalize(config?.authType)?.toUpperCase() ?: "NONE"
        if (authType == "NONE") return headers
        if (authType == "BASIC") {
            String username = normalize(config?.username)
            String password = normalize(config?.password)
            if (!username || !password) throw new IllegalArgumentException("Username and password are required for BASIC auth.")
            String token = "${username}:${password}".getBytes(StandardCharsets.UTF_8).encodeBase64().toString()
            headers.put("Authorization", "Basic " + token)
            return headers
        }
        if (authType == "BEARER") {
            String token = normalize(config?.apiToken) ?: normalize(config?.password)
            if (!token) throw new IllegalArgumentException("API token is required for BEARER auth.")
            headers.put("Authorization", "Bearer " + token)
            return headers
        }
        if (authType == "API_KEY") {
            String token = normalize(config?.apiToken) ?: normalize(config?.password)
            if (!token) throw new IllegalArgumentException("API key is required for API_KEY auth.")
            headers.put(DEFAULT_API_KEY_HEADER_NAME, token)
            return headers
        }

        throw new IllegalArgumentException("Auth Type must be NONE, BASIC, BEARER, or API_KEY.")
    }

    // Audit M5.3 — block tenant-controlled headers that would override the auth handshake or smuggle
    // routing. Authorization/Cookie/Host/Proxy-* are the classic SSRF-companion + credential-replay
    // surface; X-Forwarded-* let a tenant claim an internal source IP. Header *values* with CR/LF are
    // rejected to defeat HTTP-header splitting on permissive proxies.
    private static final Set<String> BLOCKED_HEADER_NAMES = ([
            "authorization", "cookie", "host", "proxy-authorization", "proxy-authenticate",
            "x-forwarded-for", "x-forwarded-host", "x-forwarded-proto", "forwarded",
    ] as Set<String>).asImmutable()

    private static final int MAX_HEADER_VALUE_LENGTH = 4096
    private static final java.util.regex.Pattern HEADER_CONTROL_CHARS = java.util.regex.Pattern.compile("[\\x00-\\x1F\\x7F]")

    static void validateHeadersJson(Object rawHeadersJson) {
        parseHeadersJson(rawHeadersJson)
    }

    protected static Map<String, String> parseHeadersJson(Object rawHeadersJson) {
        String headersJson = normalize(rawHeadersJson)
        if (!headersJson) return [:]
        Object parsed
        try {
            parsed = JSON_SLURPER.parseText(headersJson)
        } catch (Exception e) {
            throw new IllegalArgumentException("Headers JSON is invalid: ${e.message}")
        }
        if (!(parsed instanceof Map)) throw new IllegalArgumentException("Headers JSON must be a JSON object.")
        Map<String, String> headers = new LinkedHashMap<>()
        ((Map) parsed).each { key, value ->
            String headerName = normalize(key)
            String headerValue = normalize(value)
            if (!headerName || !headerValue) return
            String lower = headerName.toLowerCase(Locale.ROOT)
            // Blocked header names (Authorization / Cookie / Host / Proxy-* / X-Forwarded-*) are
            // silently dropped — the auth handshake sets Authorization on its own and any value the
            // tenant supplies here would override that. We don't fail the save because legacy configs
            // may already carry these names; we just refuse to send them upstream.
            if (BLOCKED_HEADER_NAMES.contains(lower)) return
            if (headerValue.length() > MAX_HEADER_VALUE_LENGTH) {
                throw new IllegalArgumentException("Header '${headerName}' value exceeds ${MAX_HEADER_VALUE_LENGTH} chars.")
            }
            if (HEADER_CONTROL_CHARS.matcher(headerValue).find()) {
                throw new IllegalArgumentException("Header '${headerName}' value contains control characters (potential header smuggling).")
            }
            headers.put(headerName, headerValue)
        }
        return headers
    }

    protected static List<String> safeHeaderNames(Map<String, String> headers) {
        return (headers ?: [:]).keySet()
                .findAll { String headerName -> !BLOCKED_HEADER_NAMES.contains(headerName.toLowerCase(Locale.ROOT)) }
                .sort()
    }

    protected static List extractOrderRecords(Object parsed, List<String> warnings) {
        if (parsed == null) return []
        if (parsed instanceof List) return (List) parsed
        if (parsed instanceof Map) {
            String key = ORDER_LIST_KEYS.find { String candidate -> ((Map) parsed).get(candidate) instanceof List }
            if (key) return ((Map) parsed).get(key) as List
            warnings.add("OMS REST response JSON object did not contain an order list.")
            return []
        }

        warnings.add("OMS REST response JSON root was not an object or array.")
        return []
    }

    protected static Map<String, Object> filterComparableOrderRecords(Collection records) {
        List filteredRecords = []
        List excludedExchangeOrders = []
        int excludedNonSalesOrderCount = 0
        int excludedExchangeOrderCount = 0
        (records ?: []).each { Object record ->
            if (!isSalesOrder(record)) {
                excludedNonSalesOrderCount++
            } else if (containsExchangeOrderAssociation(record)) {
                excludedExchangeOrderCount++
                excludedExchangeOrders.add(exchangeManifestEntry((Map) record))
            } else {
                filteredRecords.add(record)
            }
        }
        return [
                records                    : filteredRecords,
                excludedNonSalesOrderCount : excludedNonSalesOrderCount,
                excludedExchangeOrderCount : excludedExchangeOrderCount,
                excludedExchangeOrders     : excludedExchangeOrders,
        ]
    }

    /** Identity summary of an excluded exchange order — ids and amounts only, no customer fields. */
    protected static Map<String, Object> exchangeManifestEntry(Map record) {
        return [
                omsOrderId: normalize(record.get('orderId')),
                externalId: normalize(record.get('externalId')),
                orderName : normalize(record.get('orderName')),
                toOrderId : firstExchangeAssocToOrderId(record),
                grandTotal: record.get('grandTotal'),
                orderDate : record.get('orderDate'),
                statusId  : normalize(record.get('statusId')),
        ]
    }

    /** toOrderId of the first EXCHANGE assoc anywhere in the document (same scan shape as the exclusion filter). */
    protected static String firstExchangeAssocToOrderId(Object value) {
        if (value instanceof Map) {
            Map record = (Map) value
            Object assocTypeId = record.find { key, ignored -> normalize(key) == ORDER_ITEM_ASSOC_TYPE_ID_FIELD }?.value
            if (normalize(assocTypeId)?.equalsIgnoreCase(EXCHANGE_ORDER_ASSOC_TYPE_ID)) return normalize(record.get('toOrderId'))
            for (Object child : record.values()) {
                String hit = firstExchangeAssocToOrderId(child)
                if (hit) return hit
            }
            return null
        }
        if (value instanceof Collection) {
            for (Object child : (Collection) value) {
                String hit = firstExchangeAssocToOrderId(child)
                if (hit) return hit
            }
        }
        return null
    }

    static String exchangeManifestFileName(Object orderFileName) {
        String name = normalize(orderFileName) ?: "oms-orders.json"
        return name.replaceAll(/(?i)\.json$/, "") + ".exchange-manifest.json"
    }

    protected static String encodeQueryComponent(Object value) {
        return URLEncoder.encode(value == null ? "" : value.toString(), StandardCharsets.UTF_8.name())
    }

    protected static Map toPlainMap(def record) {
        if (record == null) return [:]
        if (record instanceof Map) return new LinkedHashMap(record as Map)
        Map copy = [:]
        CONFIG_FIELD_NAMES.each { String fieldName ->
            try {
                copy[fieldName] = record.get(fieldName)
            } catch (Exception ignored) {
                try {
                    copy[fieldName] = record."${fieldName}"
                } catch (Exception ignoredAgain) {
                }
            }
        }
        return copy
    }

    protected static boolean isSalesOrder(Object record) {
        if (!(record instanceof Map)) return false
        Object orderTypeId = ((Map) record).find { key, ignored ->
            normalize(key) == ORDER_TYPE_ID_FIELD
        }?.value
        return normalize(orderTypeId)?.equalsIgnoreCase(SALES_ORDER_TYPE_ID)
    }

    protected static boolean containsExchangeOrderAssociation(Object value) {
        if (value instanceof Map) {
            Map record = (Map) value
            Object assocTypeId = record.find { key, ignored ->
                normalize(key) == ORDER_ITEM_ASSOC_TYPE_ID_FIELD
            }?.value
            if (normalize(assocTypeId)?.equalsIgnoreCase(EXCHANGE_ORDER_ASSOC_TYPE_ID)) return true
            return record.values().any { Object child -> containsExchangeOrderAssociation(child) }
        }
        if (value instanceof Collection) {
            return ((Collection) value).any { Object child -> containsExchangeOrderAssociation(child) }
        }
        return false
    }

    protected static Map<String, Object> executeHttpRequest(Map request) {
        // Audit 2026-06-11 #15: re-validate the resolved endpoint at request time, not only at
        // config-save time. A baseUrl mutated out-of-band (direct DB write, data import, or a row
        // created before the save guard shipped) would otherwise reach the HTTP client unchecked and
        // could target loopback / link-local / RFC1918 / cloud-metadata addresses (SSRF). No host
        // allow-list — customers self-host OMS on arbitrary domains, matching the save-path decision.
        // Runs only on the real network path; tests inject their own client via setHttpClient.
        def __urlCheck = darpan.facade.common.OutboundHttpPolicy.validate(request.url as String)
        if (!__urlCheck.ok) throw new IllegalStateException("OMS endpoint URL blocked by outbound policy: ${__urlCheck.error}")
        HttpURLConnection connection = (HttpURLConnection) new URL(request.url as String).openConnection()
        connection.requestMethod = request.method as String
        connection.connectTimeout = normalizeInt(request.connectTimeoutSeconds, 30) * 1000
        connection.readTimeout = normalizeInt(request.readTimeoutSeconds, 60) * 1000
        ((Map<String, String>) (request.headers ?: [:])).each { String name, String value ->
            connection.setRequestProperty(name, value)
        }
        // Set after tenant headers so it cannot be overridden: we only know how to decode gzip.
        // JSON page bodies compress ~8-10x, which multiplies effective throughput on the
        // window-limited long-haul connections OMS extracts run over. Callers may disable it
        // (acceptGzip=false) when retrying a page whose compressed response starved the read
        // timeout behind a buffering proxy.
        if (request.acceptGzip != false) connection.setRequestProperty("Accept-Encoding", "gzip")

        int statusCode = connection.responseCode
        InputStream stream = statusCode >= 400 ? connection.errorStream : connection.inputStream
        String body = readResponseBody(stream, connection.getContentEncoding())
        return [statusCode: statusCode, body: body, headers: connection.headerFields]
    }

    protected static String readResponseBody(InputStream stream, String contentEncoding) {
        if (stream == null) return ""
        InputStream decoded = "gzip".equalsIgnoreCase(normalize(contentEncoding) ?: "") ? new GZIPInputStream(stream) : stream
        return decoded.getText(StandardCharsets.UTF_8.name())
    }

    protected static String sanitizeBaseUrl(Object rawBaseUrl) {
        String value = normalize(rawBaseUrl)
        if (!value) return value
        try {
            URI uri = new URI(value)
            if (uri.userInfo) {
                URI clean = new URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null)
                return clean.toString().replaceAll(/\/+$/, "")
            }
        } catch (Exception ignored) {
        }
        return value.replaceAll(/\/+$/, "")
    }
}
