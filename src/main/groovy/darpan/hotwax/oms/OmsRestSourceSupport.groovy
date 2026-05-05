package darpan.hotwax.oms

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import java.nio.charset.StandardCharsets
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

class OmsRestSourceSupport {
    static final String DEFAULT_ORDERS_PATH = "/rest/s1/oms/orders"
    static final String DEFAULT_FILE_NAME_PREFIX = "oms-orders"
    static final String DEFAULT_API_KEY_HEADER_NAME = "api_key"
    static final String DEFAULT_TIME_ZONE = "UTC"
    static final String SALES_ORDER_TYPE_ID = "SALES_ORDER"
    static final String EXCHANGE_ORDER_ASSOC_TYPE_ID = "EXCHANGE"
    static final String ORDER_TYPE_ID_FIELD = "orderTypeId"
    static final String ORDER_ITEM_ASSOC_TYPE_ID_FIELD = "orderItemAssocTypeId"
    static final int DEFAULT_ORDERS_PAGE_SIZE = 500
    static final int MAX_ORDERS_PAGE_COUNT = 20000

    private static final JsonSlurper JSON_SLURPER = new JsonSlurper()
    private static final Closure DEFAULT_HTTP_CLIENT = { Map request -> executeHttpRequest(request) }
    private static Closure httpClient = DEFAULT_HTTP_CLIENT

    static void setHttpClient(Closure client) {
        httpClient = client ?: DEFAULT_HTTP_CLIENT
    }

    static void resetHttpClient() {
        httpClient = DEFAULT_HTTP_CLIENT
    }

    static Map<String, Object> extractOrders(Object rawConfig, Object windowStart, Object windowEnd) {
        Map config = toPlainMap(rawConfig)
        List<String> warnings = []
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

        Map extraction = extractAllOrderPages(endpointUrl, fromMillis, thruMillis, headers, config, warnings)
        requestMetadata.statusCode = extraction.statusCode
        requestMetadata.attemptCount = extraction.attemptCount ?: 0
        if (extraction.retriedWithTrailingSlash) requestMetadata.retriedWithTrailingSlash = true
        requestMetadata.pagination = extraction.pagination ?: requestMetadata.pagination
        if (extraction.errors) {
            errors.addAll((List) extraction.errors)
            return baseResult
        }

        List fetchedRecords = extraction.records ?: []
        List salesOrderRecords = retainSalesOrders(fetchedRecords)
        List records = excludeExchangeOrders(salesOrderRecords)
        int excludedNonSalesOrderCount = fetchedRecords.size() - salesOrderRecords.size()
        int excludedExchangeOrderCount = salesOrderRecords.size() - records.size()
        requestMetadata.filters = [
                requiredOrderTypeId          : SALES_ORDER_TYPE_ID,
                excludedNonSalesOrderCount   : excludedNonSalesOrderCount,
                excludedOrderItemAssocTypeIds: [EXCHANGE_ORDER_ASSOC_TYPE_ID],
                excludedExchangeOrderCount   : excludedExchangeOrderCount,
        ]
        Map outputDocument = [
                metadata: requestMetadata + [
                        sourceType              : "HOTWAX_OMS_REST_ORDERS",
                        omsRestSourceConfigId    : normalize(config?.omsRestSourceConfigId),
                        windowStartEpochMillis   : fromMillis,
                        windowEndEpochMillis     : thruMillis,
                        extractedRecordCount     : records.size(),
                ],
                records : records,
        ]

        String outputText = JsonOutput.prettyPrint(JsonOutput.toJson(outputDocument))
        return baseResult + [
                dataAvailable: records.size() > 0,
                recordCount  : records.size(),
                records      : records,
                outputText   : outputText,
                warnings     : warnings,
                errors       : errors,
        ]
    }

    static Long toEpochMillis(Object rawValue) {
        List<String> errors = []
        Long millis = parseWindowMillis(rawValue, "value", errors)
        if (errors) throw new IllegalArgumentException(errors.join(" "))
        return millis
    }

    static Map<String, Object> safeConfigMap(def cfg) {
        Map config = toPlainMap(cfg)
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

    static List<Map<String, Object>> filterConfigsForTenant(Collection configs, String activeTenantUserGroupId) {
        String tenantId = normalize(activeTenantUserGroupId)
        if (!tenantId) return []
        return (configs ?: [])
                .collect { toPlainMap(it) }
                .findAll { normalize(it.companyUserGroupId) == tenantId }
                .collect { safeConfigMap(it) }
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
        if (value ==~ /-?\d+/) return Long.parseLong(value)

        List<Closure<Long>> parsers = [
                { String text -> Instant.parse(text).toEpochMilli() },
                { String text -> OffsetDateTime.parse(text).toInstant().toEpochMilli() },
                { String text -> ZonedDateTime.parse(text).toInstant().toEpochMilli() },
                { String text -> Timestamp.valueOf(text).time },
                { String text -> LocalDateTime.parse(text).toInstant(ZoneOffset.UTC).toEpochMilli() },
                { String text -> LocalDate.parse(text).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() },
        ]
        for (Closure<Long> parser : parsers) {
            try {
                return parser.call(value)
            } catch (Exception ignored) {
            }
        }

        errors.add("${label} must be a Timestamp, Date, ISO-8601 value, or epoch milliseconds.")
        return null
    }

    protected static String buildOrdersUrl(String baseUrl, String ordersPath, Long fromMillis, Long thruMillis) {
        return buildOrdersUrl(buildOrdersEndpointUrl(baseUrl, ordersPath), fromMillis, thruMillis, [:])
    }

    protected static String buildOrdersUrl(String endpointUrl, Long fromMillis, Long thruMillis,
                                           Map<String, Object> extraQueryParams) {
        String separator = endpointUrl.contains("?") ? "&" : "?"
        Map<String, Object> queryParams = new LinkedHashMap<>()
        queryParams.orderDate_from = fromMillis
        queryParams.orderDate_thru = thruMillis
        queryParams.putAll(extraQueryParams ?: [:])
        return "${endpointUrl}${separator}${queryParams.collect { key, value -> "${key}=${value}" }.join("&")}"
    }

    protected static String buildOrdersEndpointUrl(String baseUrl, String ordersPath) {
        String normalizedBase = normalize(baseUrl)?.replaceAll(/\/+$/, "")
        String normalizedPath = normalizeOrdersPath(ordersPath)
        if (!normalizedBase) return normalizedPath

        try {
            URI uri = new URI(normalizedBase)
            if (uri.scheme && uri.host) {
                String joinedPath = joinPathWithOverlap(uri.path, normalizedPath)
                return new URI(uri.scheme, uri.userInfo, uri.host, uri.port, joinedPath, uri.query, uri.fragment).toString()
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

    protected static Map<String, Object> callOmsEndpoint(String requestUrl, Map<String, String> headers, Map config) {
        return (httpClient.call([
                method               : "GET",
                url                  : requestUrl,
                headers              : headers,
                connectTimeoutSeconds: normalizeInt(config?.connectTimeoutSeconds, 30),
                readTimeoutSeconds   : normalizeInt(config?.readTimeoutSeconds, 60),
        ]) ?: [:]) as Map<String, Object>
    }

    protected static Map<String, Object> extractAllOrderPages(String endpointUrl, Long fromMillis, Long thruMillis,
                                                              Map<String, String> headers, Map config,
                                                              List<String> warnings) {
        int pageSize = resolveOrdersPageSize(config)
        int maxPageCount = Math.max(1, normalizeInt(config?.maxOrdersPageCount, MAX_ORDERS_PAGE_COUNT))
        Map<String, Object> fallbackPage = null

        for (Map<String, Object> strategy : paginationStrategies()) {
            Map<String, Object> firstPage = fetchOrdersPage(endpointUrl, fromMillis, thruMillis,
                    pageQueryParams(strategy, 0, pageSize), headers, config, warnings)
            if (!firstPage.success) {
                fallbackPage = firstPage
                if (isRecoverablePaginationFailure(firstPage.statusCode)) continue
                return failedPageResult(firstPage)
            }

            List firstRecords = firstPage.records ?: []
            if (firstRecords.isEmpty()) return successfulPageResult(strategy, pageSize, [firstPage], [])
            if (!shouldProbeSecondPage(firstRecords, pageSize)) {
                return successfulPageResult(strategy, pageSize, [firstPage], firstRecords)
            }

            Map<String, Object> secondPage = fetchOrdersPage(endpointUrl, fromMillis, thruMillis,
                    pageQueryParams(strategy, 1, pageSize), headers, config, warnings)
            if (!secondPage.success) {
                fallbackPage = secondPage
                if (isRecoverablePaginationFailure(secondPage.statusCode)) continue
                return failedPageResult(secondPage, [firstPage])
            }

            List secondRecords = secondPage.records ?: []
            if (secondRecords.isEmpty()) return successfulPageResult(strategy, pageSize, [firstPage, secondPage], firstRecords)
            if (samePageRecords(firstRecords, secondRecords)) {
                warnings.add("OMS REST pagination strategy ${strategy.name} did not advance beyond the first page.")
                fallbackPage = firstPage
                continue
            }

            List<Map<String, Object>> pages = [firstPage, secondPage]
            List records = []
            records.addAll(firstRecords)
            records.addAll(secondRecords)
            List previousRecords = secondRecords
            int pageIndex = 2
            while (pageIndex < maxPageCount) {
                Map<String, Object> page = fetchOrdersPage(endpointUrl, fromMillis, thruMillis,
                        pageQueryParams(strategy, pageIndex, pageSize), headers, config, warnings)
                pages.add(page)
                if (!page.success) return failedPageResult(page, pages)

                List pageRecords = page.records ?: []
                if (pageRecords.isEmpty()) break
                if (samePageRecords(previousRecords, pageRecords)) {
                    warnings.add("OMS REST pagination strategy ${strategy.name} stopped because page ${pageIndex} repeated the previous page.")
                    break
                }

                records.addAll(pageRecords)
                if (pageRecords.size() < previousRecords.size()) break
                previousRecords = pageRecords
                pageIndex++
            }

            if (pageIndex >= maxPageCount) {
                return [
                        errors    : ["OMS REST pagination exceeded ${maxPageCount} pages for the selected time period."],
                        records   : records,
                        statusCode: latestStatusCode(pages),
                        attemptCount: totalAttemptCount(pages),
                        retriedWithTrailingSlash: anyTrailingSlashRetry(pages),
                        pagination: paginationMetadata(strategy, pageSize, pages, records.size(), true),
                ]
            }

            return successfulPageResult(strategy, pageSize, pages, records)
        }

        Map<String, Object> unpaginatedPage = fetchOrdersPage(endpointUrl, fromMillis, thruMillis, [:], headers, config, warnings)
        if (!unpaginatedPage.success) return failedPageResult(unpaginatedPage ?: fallbackPage)
        warnings.add("OMS REST pagination parameters did not advance; extracted the first unpaginated response only.")
        return successfulPageResult([name: "unpaginated"], pageSize, [unpaginatedPage], unpaginatedPage.records ?: [])
    }

    protected static Map<String, Object> fetchOrdersPage(String endpointUrl, Long fromMillis, Long thruMillis,
                                                         Map<String, Object> pageParams,
                                                         Map<String, String> headers, Map config,
                                                         List<String> warnings) {
        String requestUrl = buildOrdersUrl(endpointUrl, fromMillis, thruMillis, pageParams)
        Map response
        boolean retriedWithTrailingSlash = false
        int attemptCount = 1
        try {
            response = callOmsEndpoint(requestUrl, headers, config)
            Integer firstStatusCode = normalizeInt(response.statusCode, 0)
            if (firstStatusCode == 404) {
                String retryUrl = trailingSlashBeforeQuery(requestUrl)
                if (retryUrl && retryUrl != requestUrl) {
                    response = callOmsEndpoint(retryUrl, headers, config)
                    attemptCount = 2
                    retriedWithTrailingSlash = true
                }
            }
        } catch (Exception e) {
            return [
                    success   : false,
                    statusCode: 0,
                    attemptCount: attemptCount,
                    retriedWithTrailingSlash: retriedWithTrailingSlash,
                    errors    : ["OMS REST request failed: ${e.message}"],
            ]
        }

        Integer statusCode = normalizeInt(response.statusCode, 0)
        if (statusCode < 200 || statusCode >= 300) {
            return [
                    success   : false,
                    statusCode: statusCode,
                    attemptCount: attemptCount,
                    retriedWithTrailingSlash: retriedWithTrailingSlash,
                    errors    : ["OMS REST request failed with status ${statusCode}."],
            ]
        }

        Object parsed
        String body = response.body?.toString()
        if (!body) {
            parsed = [orders: []]
            warnings.add("OMS REST response body was empty.")
        } else {
            try {
                parsed = JSON_SLURPER.parseText(body)
            } catch (Exception e) {
                return [
                        success   : false,
                        statusCode: statusCode,
                        attemptCount: attemptCount,
                        retriedWithTrailingSlash: retriedWithTrailingSlash,
                        errors    : ["OMS REST response was not valid JSON: ${e.message}"],
                ]
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

    protected static List<Map<String, Object>> paginationStrategies() {
        return [
                [name: "pageIndexPageSize", indexParam: "pageIndex", sizeParam: "pageSize", offset: false],
                [name: "viewIndexViewSize", indexParam: "viewIndex", sizeParam: "viewSize", offset: false],
                [name: "offsetLimit", indexParam: "offset", sizeParam: "limit", offset: true],
        ]
    }

    protected static Map<String, Object> pageQueryParams(Map<String, Object> strategy, int pageIndex, int pageSize) {
        Map<String, Object> params = new LinkedHashMap<>()
        params[(String) strategy.sizeParam] = pageSize
        params[(String) strategy.indexParam] = strategy.offset ? pageIndex * pageSize : pageIndex
        return params
    }

    protected static boolean shouldProbeSecondPage(List records, int pageSize) {
        return records.size() >= pageSize || records.size() == 50
    }

    protected static boolean samePageRecords(List left, List right) {
        if ((left?.size() ?: 0) != (right?.size() ?: 0)) return false
        return pageFingerprint(left) == pageFingerprint(right)
    }

    protected static List<String> pageFingerprint(List records) {
        return (records ?: []).collect { Object record ->
            record instanceof Map ? JsonOutput.toJson(new TreeMap((Map) record)) : normalize(record)
        }
    }

    protected static boolean isRecoverablePaginationFailure(Object statusCode) {
        int status = normalizeInt(statusCode, 0)
        return [400, 404, 405, 422].contains(status)
    }

    protected static Map<String, Object> successfulPageResult(Map<String, Object> strategy, int pageSize,
                                                             List<Map<String, Object>> pages, List records) {
        return [
                errors    : [],
                records   : records ?: [],
                statusCode: latestStatusCode(pages),
                attemptCount: totalAttemptCount(pages),
                retriedWithTrailingSlash: anyTrailingSlashRetry(pages),
                pagination: paginationMetadata(strategy, pageSize, pages, (records ?: []).size(), false),
        ]
    }

    protected static Map<String, Object> failedPageResult(Map<String, Object> failedPage,
                                                         List<Map<String, Object>> pages = []) {
        List<Map<String, Object>> allPages = []
        allPages.addAll(pages ?: [])
        if (failedPage) allPages.add(failedPage)
        return [
                errors    : (failedPage?.errors ?: ["OMS REST request failed."]) as List,
                records   : [],
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
        return Math.max(1, Math.min(1000, normalizeInt(config?.ordersPageSize ?: config?.pageSize, DEFAULT_ORDERS_PAGE_SIZE)))
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
            if (headerName && headerValue) headers.put(headerName, headerValue)
        }
        return headers
    }

    protected static List<String> safeHeaderNames(Map<String, String> headers) {
        return (headers ?: [:]).keySet()
                .findAll { String headerName -> !headerName.equalsIgnoreCase("Authorization") }
                .sort()
    }

    protected static List extractOrderRecords(Object parsed, List<String> warnings) {
        if (parsed == null) return []
        if (parsed instanceof List) return (List) parsed
        if (parsed instanceof Map) {
            List<String> candidateKeys = ["orders", "order", "data", "items", "records", "results"]
            String key = candidateKeys.find { String candidate -> ((Map) parsed).get(candidate) instanceof List }
            if (key) return ((Map) parsed).get(key) as List
            warnings.add("OMS REST response JSON object did not contain an order list.")
            return []
        }

        warnings.add("OMS REST response JSON root was not an object or array.")
        return []
    }

    protected static List excludeExchangeOrders(Collection records) {
        return (records ?: []).findAll { Object record ->
            !containsExchangeOrderAssociation(record)
        }
    }

    protected static List retainSalesOrders(Collection records) {
        return (records ?: []).findAll { Object record ->
            isSalesOrder(record)
        }
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
        HttpURLConnection connection = (HttpURLConnection) new URL(request.url as String).openConnection()
        connection.requestMethod = request.method as String
        connection.connectTimeout = normalizeInt(request.connectTimeoutSeconds, 30) * 1000
        connection.readTimeout = normalizeInt(request.readTimeoutSeconds, 60) * 1000
        ((Map<String, String>) (request.headers ?: [:])).each { String name, String value ->
            connection.setRequestProperty(name, value)
        }

        int statusCode = connection.responseCode
        InputStream stream = statusCode >= 400 ? connection.errorStream : connection.inputStream
        String body = stream != null ? stream.getText(StandardCharsets.UTF_8.name()) : ""
        return [statusCode: statusCode, body: body, headers: connection.headerFields]
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

    protected static Map toPlainMap(def record) {
        if (record == null) return [:]
        if (record instanceof Map) return new LinkedHashMap(record as Map)
        Map copy = [:]
        [
                "omsRestSourceConfigId",
                "description",
                "companyUserGroupId",
                "createdByUserId",
                "baseUrl",
                "ordersPath",
                "authType",
                "username",
                "password",
                "apiToken",
                "headersJson",
                "connectTimeoutSeconds",
                "readTimeoutSeconds",
                "isActive",
                "createdDate",
                "lastUpdatedDate",
        ].each { String fieldName ->
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

    protected static String normalize(Object value) {
        return value?.toString()?.trim()
    }

    protected static Integer normalizeInt(Object value, Integer defaultValue) {
        if (value == null) return defaultValue
        if (value instanceof Number) return ((Number) value).intValue()
        String raw = normalize(value)
        if (!raw) return defaultValue
        try {
            return Integer.parseInt(raw)
        } catch (Exception ignored) {
            return defaultValue
        }
    }

    protected static boolean normalizeBool(Object value, boolean defaultValue) {
        if (value == null) return defaultValue
        if (value instanceof Boolean) return value as boolean
        String raw = normalize(value)?.toLowerCase()
        if (["y", "yes", "true", "1", "on"].contains(raw)) return true
        if (["n", "no", "false", "0", "off"].contains(raw)) return false
        return defaultValue
    }
}
