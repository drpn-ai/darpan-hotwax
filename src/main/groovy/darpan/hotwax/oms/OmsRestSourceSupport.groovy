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
        if (!baseUrl) errors.add("Base URL is required.")

        Map<String, String> headers = [:]
        if (!errors) {
            try {
                headers = buildHeaders(config)
            } catch (IllegalArgumentException e) {
                errors.add(e.message)
            }
        }

        String requestUrl = null
        Map<String, Object> requestMetadata = [
                method     : "GET",
                baseUrl    : sanitizeBaseUrl(baseUrl),
                ordersPath : normalizeOrdersPath(ordersPath),
                queryParams: [
                        orderDate_from: fromMillis,
                        orderDate_thru: thruMillis,
                ],
                authType   : normalize(config?.authType)?.toUpperCase() ?: "NONE",
                headerNames: safeHeaderNames(headers),
        ]

        if (!errors) {
            requestUrl = buildOrdersUrl(baseUrl, ordersPath, fromMillis, thruMillis)
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

        Map response
        try {
            response = callOmsEndpoint(requestUrl, headers, config)
            Integer firstStatusCode = normalizeInt(response.statusCode, 0)
            if (firstStatusCode == 404) {
                String retryUrl = trailingSlashBeforeQuery(requestUrl)
                if (retryUrl && retryUrl != requestUrl) {
                    response = callOmsEndpoint(retryUrl, headers, config)
                    requestMetadata.attemptCount = 2
                    requestMetadata.retriedWithTrailingSlash = true
                }
            }
        } catch (Exception e) {
            errors.add("OMS REST request failed: ${e.message}")
            return baseResult
        }

        Integer statusCode = normalizeInt(response.statusCode, 0)
        requestMetadata.statusCode = statusCode
        requestMetadata.attemptCount = requestMetadata.attemptCount ?: 1
        if (statusCode < 200 || statusCode >= 300) {
            errors.add("OMS REST request failed with status ${statusCode}.")
            return baseResult
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
                errors.add("OMS REST response was not valid JSON: ${e.message}")
                return baseResult
            }
        }

        List records = extractOrderRecords(parsed, warnings)
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
        String endpointUrl = buildOrdersEndpointUrl(baseUrl, ordersPath)
        String separator = endpointUrl.contains("?") ? "&" : "?"
        return "${endpointUrl}${separator}orderDate_from=${fromMillis}&orderDate_thru=${thruMillis}"
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
