package darpan.hotwax.oms

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import darpan.facade.common.SharedConfigAccessSupport
import darpan.reconciliation.automation.SourceEndpointAccessSupport

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

/**
 * The OMS order extract pipeline and the OMS config helpers. HotWax OMS is read over GraphQL only (DAR-BE-052): a page
 * source (OmsGqlOrderPageSource) fetches, and everything here — the sales-order limit, the exchange scan, the unit
 * flattener, projection, counts, the exchange manifest and the streaming file sink — shapes what it hands over.
 *
 * DAR-BE-318 (plan 3b-2b, Task 17): the extract no longer filters. A tenant's source filters and the exchange exclusion are
 * Keep, which darpan runs in Spark over the written file: a window read writes every sales order, an exchange order flagged
 * isExchangeOrder = Y (the connector row declares the Keep line that sets it aside, data/HotWaxConnectorSeedData.xml), and
 * a unit carries copies of the order fields its Keep reads (keepRuleFields). A read by key still leaves exchanges out
 * itself, so a lookup or a re-check answers exactly as before.
 */
class OmsExtractSupport {
    static final String DEFAULT_FILE_NAME_PREFIX = "oms-orders"
    static final String DEFAULT_API_KEY_HEADER_NAME = "api_key"
    static final String DEFAULT_TIME_ZONE = "UTC"
    static final String SALES_ORDER_TYPE_ID = "SALES_ORDER"
    static final String EXCHANGE_ORDER_ASSOC_TYPE_ID = "EXCHANGE"
    static final int EXCHANGE_MANIFEST_MAX_ENTRIES = 500
    static final String ORDER_TYPE_ID_FIELD = "orderTypeId"
    static final String ORDER_ITEM_ASSOC_TYPE_ID_FIELD = "orderItemAssocTypeId"
    /**
     * DAR-BE-318 (Ruling 3b2b-12): Y on an exchange order, N on every other sales order, written by every read that runs the
     * exchange scan (the exchange exclusion on). A window read writes both and leaves the Y orders to Keep; a read by key and
     * unit grain leave exchange orders out themselves, so they write N on all they keep. A conclusion lookup (exclusion off)
     * runs no scan and writes it on nothing. Written after the projection, so whatever the read keeps; never selected from
     * the OMS.
     */
    static final String IS_EXCHANGE_ORDER_FIELD = "isExchangeOrder"
    static final int MAX_ORDERS_PAGE_COUNT = 20000

    private static final JsonSlurper JSON_SLURPER = new JsonSlurper()
    private static final List<String> CONFIG_FIELD_NAMES = [
            "omsRestSourceConfigId",
            "description",
            "companyUserGroupId",
            "createdByUserId",
            "baseUrl",
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

    /**
     * Extract the window's orders through {@code pageFetcher} (OmsGqlOrderPageSource.fetcher) into {@code targetFile} as
     * {"records":[...],"metadata":{...}}. The fetcher is required: there is no other transport.
     *
     * @param keepRuleFields the record fields the side's Keep reads (darpan's keepRuleFields): selected like a projection's,
     *        and at unit grain copied onto each unit from its order when the unit lacks them
     */
    static Map<String, Object> extractOrdersToFile(Object rawConfig, Object windowStart, Object windowEnd,
                                                   File targetFile, List keepRecordFields,
                                                   Closure pageProgressListener,
                                                   List keepRuleFields, Map extractOptions,
                                                   Closure pageFetcher,
                                                   int exchangeManifestMaxEntries = EXCHANGE_MANIFEST_MAX_ENTRIES) {
        OrdersDocumentSink sink = new OrdersDocumentSink({ ->
            targetFile.getParentFile()?.mkdirs()
            return new BufferedWriter(new OutputStreamWriter(new FileOutputStream(targetFile), StandardCharsets.UTF_8))
        })
        Map<String, Object> result
        try {
            result = extractOrdersInternal(rawConfig, windowStart, windowEnd, sink, keepRecordFields,
                    pageProgressListener, keepRuleFields, extractOptions, pageFetcher, exchangeManifestMaxEntries)
        } catch (Exception e) {
            sink.abort()
            targetFile.delete()
            throw e
        }
        if (result.errors) {
            sink.abort()
            targetFile.delete()
        }
        return result
    }

    /** In-memory variant for tests: the same pipeline written to a buffer, with the parsed records added. */
    static Map<String, Object> extractOrders(Object rawConfig, Object windowStart, Object windowEnd,
                                             List keepRecordFields, Closure pageProgressListener,
                                             List keepRuleFields, Map extractOptions, Closure pageFetcher) {
        StringWriter buffer = new StringWriter()
        Map<String, Object> result = extractOrdersInternal(rawConfig, windowStart, windowEnd,
                new OrdersDocumentSink({ -> buffer }), keepRecordFields, pageProgressListener, keepRuleFields,
                extractOptions, pageFetcher)
        if (!(result.errors as List)) {
            result.records = ((JSON_SLURPER.parseText(buffer.toString()) as Map).records as List) ?: []
        }
        return result
    }

    private static Map<String, Object> extractOrdersInternal(Object rawConfig, Object windowStart, Object windowEnd,
                                                             OrdersDocumentSink sink, List keepRecordFields,
                                                             Closure pageProgressListener,
                                                             List keepRuleFields, Map extractOptions,
                                                             Closure pageFetcher,
                                                             int exchangeManifestMaxEntries = EXCHANGE_MANIFEST_MAX_ENTRIES) {
        Set<String> keepFieldSet = normalizeKeepFields(keepRecordFields)
        Set<String> keepRuleFieldSet = normalizeKeepFields(keepRuleFields)
        Map config = toPlainMap(rawConfig)
        // Synchronized: the page source shapes pages on its slice threads.
        List<String> warnings = Collections.synchronizedList(new ArrayList<String>())
        List<String> errors = []
        if (pageFetcher == null) errors.add("An OMS extract needs its GraphQL page source.")

        // parseWindowMillis returns null both for "nothing supplied" and for "would not parse" (it adds the error),
        // so a missing bound is reported here and a malformed one there, never as each other.
        Long fromMillis = windowStart != null ? parseWindowMillis(windowStart, "windowStart", errors) : null
        Long thruMillis = windowEnd != null ? parseWindowMillis(windowEnd, "windowEnd", errors) : null
        if (windowStart == null || windowEnd == null) errors.add("windowStart and windowEnd are both required.")
        if (fromMillis != null && thruMillis != null && fromMillis > thruMillis) {
            errors.add("windowStart must be before or equal to windowEnd.")
        }

        String baseUrl = normalize(config?.baseUrl)
        if (!baseUrl) errors.add("Base URL is required.")
        Map<String, String> headers = [:]
        if (!errors) {
            try {
                headers = buildHeaders(config)
            } catch (IllegalArgumentException e) {
                errors.add(e.message)
            }
        }

        Map<String, Object> requestMetadata = [
                method     : "POST",
                transport  : "GRAPHQL",
                baseUrl    : sanitizeBaseUrl(baseUrl),
                authType   : normalize(config?.authType)?.toUpperCase() ?: "NONE",
                timeZone   : normalize(config?.timeZone) ?: DEFAULT_TIME_ZONE,
                headerNames: safeHeaderNames(headers),
                pagination : [pageSize: null, pageCount: 0, strategy: null, totalFetched: 0],
        ]
        Map<String, Object> baseResult = [
                dataAvailable  : false,
                recordCount    : 0,
                requestMetadata: requestMetadata,
                warnings       : warnings,
                errors         : errors,
                fromMillis     : fromMillis,
                thruMillis     : thruMillis,
                fileName       : buildDefaultFileName(fromMillis, thruMillis),
        ]
        if (errors) return baseResult

        Map<String, Object> options = normalizeExtractOptions(extractOptions)
        int excludedNonSalesOrderCount = 0
        int excludedExchangeOrderCount = 0
        int flaggedExchangeOrderCount = 0
        // DAR-BE-050. Totals across every page, reported even when zero, so "the key is absent" never has to be told
        // apart from "nothing was dropped".
        int droppedNullLineIdCount = 0
        int nonUnitQuantityCount = 0
        int keptNullShopifyIdCount = 0
        int extractedRecordCount = 0
        int consumedRawCount = 0
        List exchangeManifest = []
        boolean exchangeManifestTruncated = false
        Closure pageConsumer = { Map<String, Object> pageBundle ->
            // Pages arrive pre-shaped and pre-serialized, so consuming one is an append plus counter bumps.
            excludedNonSalesOrderCount += (int) pageBundle.excludedNonSalesOrderCount
            excludedExchangeOrderCount += (int) pageBundle.excludedExchangeOrderCount
            flaggedExchangeOrderCount += (int) (pageBundle.flaggedExchangeOrderCount ?: 0)
            for (Object entry : (List) (pageBundle.excludedExchangeOrders ?: [])) {
                if (exchangeManifest.size() >= exchangeManifestMaxEntries) { exchangeManifestTruncated = true; break }
                exchangeManifest.add(entry)
            }
            // DAR-BE-050: outputCount, not filteredCount. At unit grain one filtered ORDER serializes as many UNITS.
            int writtenCount = (int) (pageBundle.outputCount != null ? pageBundle.outputCount : pageBundle.filteredCount)
            if (writtenCount > 0) sink.writeSerializedPage((String) pageBundle.serializedRecords, writtenCount)
            extractedRecordCount += writtenCount
            droppedNullLineIdCount += (int) (pageBundle.droppedNullLineIdCount ?: 0)
            nonUnitQuantityCount += (int) (pageBundle.nonUnitQuantityCount ?: 0)
            keptNullShopifyIdCount += (int) (pageBundle.keptNullShopifyIdCount ?: 0)
            consumedRawCount += (int) pageBundle.rawCount
            if (pageProgressListener != null) {
                // Progress is advisory: a listener failure must never fail the extraction.
                try {
                    pageProgressListener.call(consumedRawCount)
                } catch (Exception ignored) {
                }
            }
        }

        if (keepFieldSet) requestMetadata.projection = [keepRecordFields: keepFieldSet.sort()]

        Map extraction
        try {
            // extractOptions, not options: the page source and shapePageRecords each normalize their own copy.
            extraction = (Map<String, Object>) pageFetcher.call([config      : config, headers: headers,
                                                                fromMillis  : fromMillis, thruMillis: thruMillis,
                                                                keepFieldSet: keepFieldSet, keepRuleFields: keepRuleFieldSet,
                                                                extractOptions: extractOptions, warnings: warnings,
                                                                pageConsumer: pageConsumer])
        } catch (IOException e) {
            sink.abort()
            errors.add("Failed writing OMS extract output: ${e.message}".toString())
            return baseResult
        }
        requestMetadata.statusCode = extraction.statusCode
        requestMetadata.attemptCount = extraction.attemptCount ?: 0
        requestMetadata.pagination = extraction.pagination ?: requestMetadata.pagination
        if (extraction.transport) requestMetadata.transport = extraction.transport
        if (extraction.errors) {
            sink.abort()
            errors.addAll((List) extraction.errors)
            return baseResult
        }

        // Unit-grain drop counters ride beside the filter counts rather than inside them: those name records left out on
        // purpose, while these record units the extract could not key and therefore LOST.
        if (options.get("emitGrain") == "ORDER_LINE_UNIT") {
            requestMetadata.droppedNullLineIdCount = droppedNullLineIdCount
            requestMetadata.nonUnitQuantityCount = nonUnitQuantityCount
            if (options.get("requireShopifyLineId") == false) {
                requestMetadata.keptNullShopifyIdCount = keptNullShopifyIdCount
            }
        }
        requestMetadata.filters = buildFilterMetadata(excludedNonSalesOrderCount, excludedExchangeOrderCount,
                exchangeManifestTruncated, flaggedExchangeOrderCount)
        Map documentMetadata = requestMetadata + [
                sourceType            : "HOTWAX_OMS_ORDERS",
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
                dataAvailable            : extractedRecordCount > 0,
                recordCount              : extractedRecordCount,
                warnings                 : warnings,
                errors                   : errors,
                exchangeManifest         : exchangeManifest,
                exchangeManifestTruncated: exchangeManifestTruncated,
        ]
    }

    /**
     * Streams the extract document as {"records":[...],"metadata":{...}} — records first so pages can be appended as
     * they arrive and the metadata written once at the end. The writer is opened lazily so rejected requests never
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
                omsRestSourceConfigId: config.omsRestSourceConfigId,
                description          : config.description,
                companyUserGroupId   : config.companyUserGroupId,
                baseUrl              : sanitizeBaseUrl(config.baseUrl),
                timeZone             : normalize(config.timeZone) ?: DEFAULT_TIME_ZONE,
                authType             : normalize(config.authType)?.toUpperCase() ?: "NONE",
                hasUsername          : !!normalize(config.username),
                hasPassword          : !!normalize(config.password),
                hasApiToken          : !!normalize(config.apiToken),
                customHeaderNames    : safeHeaderNames(parseHeadersJson(config.headersJson)),
                connectTimeoutSeconds: normalizeInt(config.connectTimeoutSeconds, 30),
                readTimeoutSeconds   : normalizeInt(config.readTimeoutSeconds, 60),
                isActive             : normalizeBool(config.isActive, true) ? "Y" : "N",
                canReadOrders        : normalizeBool(config.canReadOrders, true),
                createdDate          : config.createdDate,
                lastUpdatedDate      : config.lastUpdatedDate,
        ]
    }

    /**
     * DAR-BE-005: {@code allowSharedPeer} defaults to strict owner-only. The save path passes the caller's peer
     * standing (from the ConfigTenantAccess grant table) so a member tenant may edit a shared config; the delete path
     * never passes it, and never forwards a foreign existingConfig here at all (see deleteOmsRestSourceConfig.groovy),
     * so this method's mismatch message cannot become a cross-tenant existence oracle there.
     */
    static void requireWritableTenantConfig(Map existingConfig, String activeTenantUserGroupId, boolean canWrite,
                                            boolean allowSharedPeer = false) {
        String tenantId = normalize(activeTenantUserGroupId)
        if (!tenantId) throw new IllegalArgumentException("An active tenant is required for tenant-scoped writes.")
        if (!canWrite) throw new IllegalArgumentException("Your active tenant is read-only for this action.")
        if (existingConfig && normalize(existingConfig.companyUserGroupId) != tenantId && !allowSharedPeer) {
            throw new IllegalArgumentException("Requested OMS source config was not found.")
        }
    }

    /**
     * DAR-BE-005 B1 — runtime credential *use* gate for every extract and lookup script that consumes an OMS config's
     * credentials. An automation execution (automationTenantUserGroupId present, server-derived from the gated
     * automation record) is checked against that tenant; an interactive caller against the session's active tenant.
     * Both collapse to the SAME "not found" text on denial (also for a null config), so a caller with no standing
     * cannot tell a missing config from a foreign one.
     *
     * @param requiredSystemEnumId endpoint the caller is about to read, e.g. OMS_RETURNS_GQL. Null means "no endpoint
     *        check". The compiler cannot catch a caller naming the WRONG endpoint, which is why each seam has its own
     *        gate test.
     */
    static void requireUsableOmsConfig(def ec, def sourceConfig, String configId,
                                       String automationTenantUserGroupId,
                                       String requiredSystemEnumId = null) {
        String trustedTenant = normalize(automationTenantUserGroupId)
        boolean usable = trustedTenant
                ? SharedConfigAccessSupport.canTenantUseConfig(ec,
                        SharedConfigAccessSupport.CONFIG_TYPE_HOTWAX_OMS, sourceConfig, trustedTenant)
                : SharedConfigAccessSupport.canActiveTenantUseConfig(ec,
                        SharedConfigAccessSupport.CONFIG_TYPE_HOTWAX_OMS, sourceConfig)
        if (!usable) {
            ec.message.addError("OMS source config ${configId} not found.".toString())
            return
        }

        String requiredEndpoint = normalize(requiredSystemEnumId)
        if (requiredEndpoint && !SourceEndpointAccessSupport.isEndpointEnabled(ec,
                SharedConfigAccessSupport.CONFIG_TYPE_HOTWAX_OMS, configId, requiredEndpoint)) {
            ec.message.addError("OMS source config ${configId} is not enabled for ${requiredEndpoint}.".toString())
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

    static String buildDefaultFileName(Long fromMillis, Long thruMillis, String filePrefix = null) {
        String fromToken = fromMillis != null ? fromMillis.toString() : "start"
        String thruToken = thruMillis != null ? thruMillis.toString() : "end"
        String prefix = normalize(filePrefix) ?: DEFAULT_FILE_NAME_PREFIX
        return "${prefix}-${fromToken}-${thruToken}.json"
    }

    protected static Long parseWindowMillis(Object rawValue, String label, List<String> errors) {
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

    // ---------------------------------------------------------------------------------------------
    // Connection diagnostics
    // ---------------------------------------------------------------------------------------------

    /** An operator is watching a popup: fail fast, single attempt, never retry. */
    static final int PROBE_CONNECT_TIMEOUT_SECONDS = 5
    static final int PROBE_READ_TIMEOUT_SECONDS = 10
    /** The cheapest real read: one order id. Proves reachability, the credential and the orders root together. */
    static final String PROBE_DOCUMENT = 'query ConnectionProbe { orders(first: 1) { edges { node { orderId } } } }'
    static final String PROBE_STAGE_CREDENTIAL = "credential"
    static final String PROBE_STAGE_CONNECT = "connect"
    static final List<String> PROBE_STAGES = [PROBE_STAGE_CREDENTIAL, PROBE_STAGE_CONNECT].asImmutable()
    /** Mirrors SourceConnectionDiagnosticsSupport.STAGE_FIRST — "whichever stage you run first". */
    static final String PROBE_STAGE_FIRST = "@first"
    /** Test seam: the probe's sender, (url, headers, body) -> [statusCode, body]. Null is the real endpoint. */
    static Closure probeSender = null

    /**
     * Operator-initiated connection probe for a saved OMS config, over the GraphQL endpoint every OMS read uses.
     * Never writes to ec.message and never throws: every outcome is a check row. Pass config.credentialError when the
     * caller could not decrypt a stored secret.
     *
     * @return [checks: List] in the shared diagnostics check contract
     */
    static Map<String, Object> probeConnection(Map config) {
        List<Map<String, Object>> checks = []
        String stage = PROBE_STAGES[0]
        while (stage) {
            Map<String, Object> result = probeConnectionStage(config, stage)
            checks.addAll((List<Map<String, Object>>) result.checks)
            stage = normalize(result.nextStage)
        }
        return [checks: checks, nextStage: null]
    }

    /** One stage, returning its rows plus the next stage to run (null when finished). */
    static Map<String, Object> probeConnectionStage(Map config, String stage) {
        switch (normalize(stage)) {
            case PROBE_STAGE_FIRST:
            case PROBE_STAGE_CREDENTIAL: return probeCredentialStage(config)
            case PROBE_STAGE_CONNECT: return probeConnectStage(config)
            default: return [checks: [], nextStage: null]
        }
    }

    /**
     * Entirely local. password and apiToken are encrypt="true", so an unreadable secret fails HERE, before a socket
     * is opened — reporting it as a network fault is the misdiagnosis this ordering exists to prevent.
     */
    protected static Map<String, Object> probeCredentialStage(Map config) {
        String failure = normalize(config?.credentialError)
        if (!failure) {
            try {
                buildHeaders(config)
            } catch (IllegalArgumentException e) {
                // Our own fixed validation text ("API token is required for BEARER auth."), safe to show.
                failure = e.message
            } catch (Throwable ignored) {
                failure = "The stored credentials could not be read."
            }
        }

        if (failure) {
            return [checks   : [diagnosticsCheck("credential", "Credential readable", "FAIL", failure),
                                probeSkipped("reachable", "Base URL reachable"),
                                probeSkipped("auth", "Credentials accepted"),
                                probeSkipped("ordersRead", "Orders readable")],
                    nextStage: null]
        }

        String authType = normalize(config?.authType)?.toUpperCase() ?: "NONE"
        return [checks   : [diagnosticsCheck("credential", "Credential readable", "PASS",
                                    authType == "NONE" ? "No credentials configured (auth type NONE)." : null)],
                nextStage: PROBE_STAGE_CONNECT]
    }

    /**
     * One `orders(first: 1)` query through OmsGqlClient (its headers, its outbound policy, its error reading), sent
     * once: no governor wait and no retry. The HTTP status the sender saw decides reachability and auth; the answer
     * decides the orders read. A status- or GraphQL-error detail never echoes the response body.
     */
    protected static Map<String, Object> probeConnectStage(Map config) {
        String baseUrl = normalize(config?.baseUrl)
        if (!baseUrl || !(baseUrl.toLowerCase(Locale.ROOT) ==~ /^https?:\/\/.+/)) {
            return [checks   : [diagnosticsCheck("reachable", "Base URL reachable", "FAIL",
                                        "No usable base URL is configured."),
                                probeSkipped("auth", "Credentials accepted"),
                                probeSkipped("ordersRead", "Orders readable")],
                    nextStage: null]
        }
        Map<String, Object> probeConfig = new LinkedHashMap<>(config ?: [:])
        probeConfig.put("connectTimeoutSeconds", PROBE_CONNECT_TIMEOUT_SECONDS)
        probeConfig.put("readTimeoutSeconds", PROBE_READ_TIMEOUT_SECONDS)

        Integer statusCode = null
        Closure base = probeSender ?: OmsGqlClient.httpSender(probeConfig)
        Closure sender = { String url, Map headers, String body ->
            Map response = (Map) base.call(url, headers, body)
            statusCode = normalizeInt(response?.statusCode, 0)
            return response
        }
        long startedAt = System.currentTimeMillis()
        Map answer = null
        String failureCode = null
        try {
            answer = new OmsGqlClient(probeConfig, sender).executeOnce(PROBE_DOCUMENT)
        } catch (OmsGqlException e) {
            failureCode = e.code
        } catch (Throwable ignored) {
            // A refused or timed-out connection, an unknown host, or a URL the outbound policy blocks.
        }
        long elapsedMillis = System.currentTimeMillis() - startedAt

        if (statusCode == null || statusCode <= 0) {
            return [checks   : [diagnosticsCheck("reachable", "Base URL reachable", "FAIL",
                                        "The base URL could not be reached.", elapsedMillis),
                                probeSkipped("auth", "Credentials accepted"),
                                probeSkipped("ordersRead", "Orders readable")],
                    nextStage: null]
        }
        // Any HTTP status at all proves the host answered.
        List<Map<String, Object>> checks = [diagnosticsCheck("reachable", "Base URL reachable", "PASS", null, elapsedMillis)]
        // Moqui answers 401 to any credential it cannot verify (bad signature, wrong algorithm, expired, unknown key)
        // and 403 to a caller it knows but will not serve. With no credentials at all it also answers 403.
        boolean credentialsSent = (normalize(config?.authType)?.toUpperCase() ?: "NONE") != "NONE"
        if (statusCode == 401 || (statusCode == 403 && !credentialsSent)) {
            checks.add(diagnosticsCheck("auth", "Credentials accepted", "FAIL", statusCode == 401
                    ? "The stored credentials were rejected (HTTP 401): invalid, expired, or issued by another OMS."
                    : "The OMS needs credentials (HTTP 403); none are configured."))
            checks.add(probeSkipped("ordersRead", "Orders readable"))
            return [checks: checks, nextStage: null]
        }
        checks.add(diagnosticsCheck("auth", "Credentials accepted", "PASS"))

        if (statusCode == 403) {
            checks.add(diagnosticsCheck("ordersRead", "Orders readable", "FAIL",
                    "The OMS user behind these credentials may not read orders through GraphQL (HTTP 403). " +
                            "Ask HotWax to grant that user API access."))
            return [checks: checks, nextStage: null]
        }

        if (statusCode == 404) {
            checks.add(diagnosticsCheck("ordersRead", "Orders readable", "FAIL",
                    "The OMS GraphQL endpoint was not found (HTTP 404). Check the base URL."))
            return [checks: checks, nextStage: null]
        }
        if (statusCode < 200 || statusCode >= 300) {
            checks.add(diagnosticsCheck("ordersRead", "Orders readable", "FAIL",
                    "The orders query failed with HTTP ${statusCode}.".toString()))
            return [checks: checks, nextStage: null]
        }
        if (failureCode == "UNPARSEABLE") {
            // The classic misconfiguration: a login page or proxy error page answering with HTTP 200.
            checks.add(diagnosticsCheck("ordersRead", "Orders readable", "FAIL", "The orders response was not valid JSON."))
            return [checks: checks, nextStage: null]
        }
        if (failureCode != null || answer == null) {
            checks.add(diagnosticsCheck("ordersRead", "Orders readable", "FAIL",
                    "The orders query was refused (${failureCode ?: 'no answer'}).".toString()))
            return [checks: checks, nextStage: null]
        }
        List edges = (List) ((Map) ((Map) answer.data)?.get("orders"))?.get("edges")
        checks.add(diagnosticsCheck("ordersRead", "Orders readable", "PASS",
                edges ? "1 order returned" : "no orders yet"))
        return [checks: checks, nextStage: null]
    }

    protected static Map<String, Object> probeSkipped(String key, String label) {
        return diagnosticsCheck(key, label, "SKIP", "Not attempted.")
    }

    /** Local builder for the shared check contract — keeps darpan-hotwax free of a core-class import. */
    protected static Map<String, Object> diagnosticsCheck(String key, String label, String status,
                                                          String detail = null, Long durationMillis = null) {
        return [
                key           : key,
                label         : label,
                status        : status,
                detail        : normalize(detail),
                durationMillis: durationMillis,
        ] as Map<String, Object>
    }

    /**
     * Sales orders -> exchange scan -> (flatten) -> project -> serialize for one page of assembled order documents.
     *
     * Order of operations is load-bearing: the EXCHANGE scan needs the FULL record, so it precedes both flattening and
     * projection.
     *
     * DAR-BE-318 (plan 3b-2b, Task 17): nothing here applies a tenant's source filters any more; darpan's Spark Keep does,
     * over the written file. What the shaper keeps for Keep:
     * <ul>
     * <li>Order grain, a read that leaves the exchange to Keep (a window read with the exchange exclusion on): every sales
     *     order is written, with isExchangeOrder Y or N written after the projection; an exchange order is still listed on the
     *     exchange manifest. A read by key, or unit grain, still leaves exchange orders out here (exchangeExclusionAtRead),
     *     and writes isExchangeOrder N on every order or unit it keeps (fix round 1, I1: the field is selectable, and a side
     *     read by key still runs Keep). With the exchange exclusion off (a conclusion lookup) the scan never runs, so no
     *     record says either way.</li>
     * <li>Unit grain (Ruling 3b2b-10): the old filters saw the order's top-level scalar fields overlaid by the unit row. Each
     *     unit now carries a copy of every order scalar its Keep reads (keepRuleFields) that the unit itself lacks, so Spark
     *     sees that same view; the unit's own field wins, and no field outside keepRuleFields is added.</li>
     * </ul>
     */
    static Map<String, Object> shapePageRecords(List rawRecords, Map extractOptions,
                                                Collection keepRuleFields = null,
                                                Set<String> keepFieldSet = null) {
        Map<String, Object> options = normalizeExtractOptions(extractOptions)
        boolean unitGrain = options.get("emitGrain") == "ORDER_LINE_UNIT"
        Map<String, Object> pageFilter = filterComparableOrderRecords(rawRecords, extractOptions)
        List filtered = (List) pageFilter.records
        Set exchangeOrders = (Set) pageFilter.flaggedExchangeOrders
        // the scan ran, so every record kept here can say whether it is an exchange order
        boolean flagExchanges = options.applyExchangeExclusion as boolean

        List outputRecords
        int droppedNullLineIdCount = 0
        int nonUnitQuantityCount = 0
        int keptNullShopifyIdCount = 0
        if (unitGrain) {
            // Unit grain does NOT take keepFieldSet: the unit record IS the projection.
            Map flattenOptions = [requireShopifyLineId: options.get("requireShopifyLineId")]
            Set<String> overlay = normalizeKeepFields(keepRuleFields as List) ?: ([] as Set<String>)
            outputRecords = []
            for (Object record : filtered) {
                if (!(record instanceof Map)) continue
                Map flattened = OmsOrderLineUnitSupport.flattenOrderToUnits((Map) record, flattenOptions)
                droppedNullLineIdCount += (flattened.droppedNullLineId as int)
                nonUnitQuantityCount += (flattened.nonUnitQuantityCount as int)
                keptNullShopifyIdCount += (flattened.keptNullShopifyIdCount as int)
                Map<String, Object> orderScalars = overlay ? topLevelScalars((Map) record) : null
                for (Object unit : (List) flattened.units) {
                    // The flattener builds each unit afresh, so the copies never reach the order or another unit.
                    if (orderScalars) carryOrderScalars((Map) unit, orderScalars, overlay)
                    // unit grain leaves exchange orders out itself, so a unit of a scanned read is never one
                    if (flagExchanges) ((Map) unit).put(IS_EXCHANGE_ORDER_FIELD, "N")
                    outputRecords.add(unit)
                }
            }
        } else {
            // Projection happens after the exchange scan (it needs the full record) and before serialization; the flag is
            // written after the projection, on a copy, so a shared read's record is never changed.
            outputRecords = filtered.collect { Object record ->
                Map out = keepFieldSet ? trimRecord(record, keepFieldSet) : (flagExchanges ? new LinkedHashMap((Map) record) : (Map) record)
                if (flagExchanges) out.put(IS_EXCHANGE_ORDER_FIELD, exchangeOrders.contains(record) ? "Y" : "N")
                return out
            }
        }

        StringBuilder serialized = new StringBuilder(Math.max(16, outputRecords.size() * 512))
        boolean firstRecord = true
        for (Object record : outputRecords) {
            if (!firstRecord) serialized.append(',')
            serialized.append(JsonOutput.toJson(record))
            firstRecord = false
        }
        return [
                filteredCount             : filtered.size(),
                outputCount               : outputRecords.size(),
                excludedNonSalesOrderCount: pageFilter.excludedNonSalesOrderCount,
                excludedExchangeOrderCount: pageFilter.excludedExchangeOrderCount,
                flaggedExchangeOrderCount : exchangeOrders.size(),
                excludedExchangeOrders    : pageFilter.excludedExchangeOrders,
                droppedNullLineIdCount    : droppedNullLineIdCount,
                nonUnitQuantityCount      : nonUnitQuantityCount,
                keptNullShopifyIdCount    : keptNullShopifyIdCount,
                serializedRecords         : serialized.toString(),
        ]
    }

    /**
     * Ruling 3b2b-10: onto one unit, the order scalars named in {@code fields} that the unit has no key for. A key the unit
     * holds (null included) is its own and is never overwritten, exactly as the unit row won in the old filter view.
     */
    protected static void carryOrderScalars(Map unit, Map<String, Object> orderScalars, Set<String> fields) {
        for (String field : fields) {
            if (!unit.containsKey(field) && orderScalars.containsKey(field)) unit.put(field, orderScalars.get(field))
        }
    }

    /** Top-level fields of an order document that are not nested lists or maps. */
    private static Map<String, Object> topLevelScalars(Map record) {
        Map<String, Object> scalars = new LinkedHashMap<String, Object>()
        record.each { Object key, Object value ->
            if (!(value instanceof Map) && !(value instanceof Collection)) scalars.put(key?.toString(), value)
        }
        return scalars
    }

    /** DAR-BE-318 (ruling P3b1-9): the one projection the kept-copy projection service also trims with (OmsKeptFieldsProjection). */
    protected static Set<String> normalizeKeepFields(List keepRecordFields) {
        return OmsKeptFieldsProjection.keepSet(keepRecordFields)
    }

    protected static Map trimRecord(Object record, Set<String> keepFieldSet) {
        return OmsKeptFieldsProjection.project(record, keepFieldSet)
    }

    static Map<String, String> buildHeaders(Map config) {
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

    // Audit M5.3 — block tenant-controlled headers that would override the auth handshake or smuggle routing.
    // Header *values* with control characters are rejected to defeat HTTP-header splitting on permissive proxies.
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
            // Blocked names are silently dropped rather than failing the save: legacy configs may carry them.
            if (BLOCKED_HEADER_NAMES.contains(headerName.toLowerCase(Locale.ROOT))) return
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

    /**
     * The sales orders of a page, and its exchange orders. A non-sales order is always left out (a read-side limit, not Keep:
     * the GraphQL read already asks for sales orders only). With the exchange exclusion on, every exchange order is counted
     * and listed for the manifest; it is left out here only when the read keeps the exclusion itself
     * (exchangeExclusionAtRead), and otherwise kept and returned in flaggedExchangeOrders (an identity set) for the
     * isExchangeOrder flag.
     */
    protected static Map<String, Object> filterComparableOrderRecords(Collection records, Map<String, Object> extractOptions = null) {
        Map<String, Object> options = normalizeExtractOptions(extractOptions)
        boolean applyExchangeExclusion = options.applyExchangeExclusion as boolean
        boolean exchangeLeftToKeep = applyExchangeExclusion && !(options.exchangeExclusionAtRead as boolean)

        List filteredRecords = []
        List excludedExchangeOrders = []
        Set flaggedExchangeOrders = Collections.newSetFromMap(new IdentityHashMap())
        int excludedNonSalesOrderCount = 0
        int excludedExchangeOrderCount = 0
        (records ?: []).each { Object record ->
            if (!isSalesOrder(record)) {
                excludedNonSalesOrderCount++
            } else if (applyExchangeExclusion && containsExchangeOrderAssociation(record)) {
                excludedExchangeOrderCount++
                excludedExchangeOrders.add(exchangeManifestEntry((Map) record))
                if (exchangeLeftToKeep) {
                    flaggedExchangeOrders.add(record)
                    filteredRecords.add(record)
                }
            } else {
                filteredRecords.add(record)
            }
        }
        return [
                records                   : filteredRecords,
                excludedNonSalesOrderCount: excludedNonSalesOrderCount,
                excludedExchangeOrderCount: excludedExchangeOrderCount,
                excludedExchangeOrders    : excludedExchangeOrders,
                flaggedExchangeOrders     : flaggedExchangeOrders,
                exchangeLeftToKeep        : exchangeLeftToKeep,
        ]
    }

    /**
     * Request-metadata filter block. excludedExchangeOrderCount is every exchange order the read found and listed on the
     * manifest: left out here on a read by key, or written flagged isExchangeOrder = Y for Keep to set aside
     * (flaggedExchangeOrderCount). No configuredExclusions: the read applies no tenant filter, so darpan reads the side as
     * not filtered at the read (KeepPlanSupport.landedFilteredAtRead) and its Keep summary is what counts.
     */
    protected static Map<String, Object> buildFilterMetadata(int excludedNonSalesOrderCount,
                                                             int excludedExchangeOrderCount,
                                                             boolean exchangeManifestTruncated,
                                                             int flaggedExchangeOrderCount = 0) {
        return [
                requiredOrderTypeId          : SALES_ORDER_TYPE_ID,
                excludedNonSalesOrderCount   : excludedNonSalesOrderCount,
                excludedOrderItemAssocTypeIds: [EXCHANGE_ORDER_ASSOC_TYPE_ID],
                excludedExchangeOrderCount   : excludedExchangeOrderCount,
                flaggedExchangeOrderCount    : flaggedExchangeOrderCount,
                exchangeManifestTruncated    : exchangeManifestTruncated,
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

    /**
     * Caller-supplied extract options as a complete, defaulted Map. The page source refuses windowFieldName other
     * than orderDate and orderStatusIds itself, so neither reaches here.
     */
    static Map<String, Object> normalizeExtractOptions(Map rawOptions) {
        Map raw = rawOptions ?: [:]
        // The EXCHANGE order-item-association scan keeps exchange sales orders out of the sales-order comparison.
        boolean applyExchangeExclusion = raw.containsKey("applyExchangeExclusion")
                ? normalizeBool(raw.get("applyExchangeExclusion"))
                : true
        // DAR-BE-050. ORDER_LINE_UNIT is the only non-default grain. Anything else - a typo included - falls back to
        // order grain rather than emitting NOTHING, which would read downstream as "the source is empty".
        boolean unitGrain = "ORDER_LINE_UNIT".equalsIgnoreCase(normalize(raw.get("emitGrain")) ?: "")
        // DAR-BE-318: who leaves exchange orders out. A window read at order grain writes them flagged for Keep; a read by
        // key (lookupExtractOptions) and unit grain, always (no connector declares the Keep line there, and a unit carries
        // no flag), leave them out themselves.
        boolean exchangeExclusionAtRead = unitGrain || normalizeBool(raw.get("exchangeExclusionAtRead"), false)
        return [
                applyExchangeExclusion: applyExchangeExclusion,
                exchangeExclusionAtRead: exchangeExclusionAtRead,
                emitGrain             : unitGrain ? "ORDER_LINE_UNIT" : null,
                // Tri-system D4. Unit grain only; defaults to true (DAR-BE-050). Only an explicit false turns it off.
                requireShopifyLineId  : !(raw.get("requireShopifyLineId") == Boolean.FALSE
                        || (normalize(raw.get("requireShopifyLineId"))?.toLowerCase(Locale.ROOT) in ["false", "n"])),
        ]
    }

    /**
     * DAR-BE-205. The extract options of an orders read. A conclusion lookup asks for orders by id and must answer for
     * every id it names, exchange orders included: the exchange exclusion keeps exchanges out of a sales-order
     * COMPARISON, and a lookup compares nothing. Any other read by key (an audit re-check, a side read by the other side's
     * keys) leaves exchange orders out itself, as it always has (DAR-BE-318: keyed reads answer exactly as before). A window
     * read keeps the default: its exchange orders are written flagged for Keep.
     */
    static Map<String, Object> lookupExtractOptions(Object lookupKeys, Object conclusionLookup) {
        boolean keyed = lookupKeys instanceof Collection && !((Collection) lookupKeys).isEmpty()
        if (!keyed) return null
        return (normalizeBool(conclusionLookup) ? [applyExchangeExclusion: false] : [exchangeExclusionAtRead: true]) as Map<String, Object>
    }

    protected static boolean isSalesOrder(Object record) {
        if (!(record instanceof Map)) return false
        Object recordOrderTypeId = ((Map) record).find { key, ignored ->
            normalize(key) == ORDER_TYPE_ID_FIELD
        }?.value
        return normalize(recordOrderTypeId)?.equalsIgnoreCase(SALES_ORDER_TYPE_ID)
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
