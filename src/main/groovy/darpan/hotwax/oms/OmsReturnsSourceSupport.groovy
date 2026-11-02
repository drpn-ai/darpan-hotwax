package darpan.hotwax.oms

import groovy.json.JsonOutput

import darpan.reconciliation.source.SourceFilterSupport

import static darpan.common.ValueSupport.normalize
import static darpan.common.ValueSupport.normalizeInt

/**
 * The OMS returns extract pipeline (DAR-BE-018, DAR-BE-040 fix 3) and the by-id returns lookup, both over GraphQL.
 *
 * A page source (OmsGqlReturnsPageSource) fetches and builds the return records; this class runs the configured
 * filters, the join-key fallback, the projection and the streaming sink over them, and writes the metadata.
 */
class OmsReturnsSourceSupport {

    static final String DEFAULT_FILE_NAME_PREFIX = "oms-returns"
    static final String RETURN_CHANNEL_FIELD = "returnChannelEnumId"
    static final int MAX_RETURNS_PAGE_COUNT = 20000

    private static final Closure DEFAULT_GQL_CLIENT_FACTORY = { Map config -> new OmsGqlClient(config) }
    private static Closure gqlClientFactory = DEFAULT_GQL_CLIENT_FACTORY

    /** Test seam for the by-id lookup's GraphQL pass: config -> OmsGqlClient. */
    static void setGqlClientFactory(Closure factory) {
        gqlClientFactory = factory ?: DEFAULT_GQL_CLIENT_FACTORY
    }

    static void resetGqlClientFactory() {
        gqlClientFactory = DEFAULT_GQL_CLIENT_FACTORY
    }

    /**
     * Delegates to OmsExtractSupport.safeFileName: one sanitization seam. The fallback is returns-specific
     * ("oms-returns.json") so a caller-supplied blank fileName still lands on a returns-shaped
     * default rather than the orders extractor's "oms-orders.json".
     */
    static String safeReturnsFileName(Object rawName) {
        return OmsExtractSupport.safeFileName(rawName, "${DEFAULT_FILE_NAME_PREFIX}.json")
    }

    static Map<String, Object> extractReturns(Object rawConfig, Object windowStart, Object windowEnd,
                                              List keepRecordFields, Closure pageProgressListener,
                                              List sourceFilters, Map options = [:]) {
        return extractReturnsInternal(rawConfig, windowStart, windowEnd, null, keepRecordFields,
                pageProgressListener, sourceFilters, options ?: [:], true)
    }

    static Map<String, Object> extractReturnsToFile(Object rawConfig, Object windowStart, Object windowEnd,
                                                    File targetFile, List keepRecordFields,
                                                    Closure pageProgressListener, List sourceFilters,
                                                    Map options = [:]) {
        return extractReturnsInternal(rawConfig, windowStart, windowEnd, targetFile, keepRecordFields,
                pageProgressListener, sourceFilters, options ?: [:], false)
    }

    private static Map<String, Object> extractReturnsInternal(Object rawConfig, Object windowStart,
                                                              Object windowEnd, File targetFile,
                                                              List keepRecordFields, Closure pageProgressListener,
                                                              List sourceFilters, Map options,
                                                              boolean retainRecords) {
        List<String> errors = []
        List<String> warnings = []

        // toPlainMap, NOT safeConfigMap: the page source's client needs the real credentials, which safeConfigMap
        // redacts. toPlainMap(null) is an empty (falsy) Map, so the null-config guard below still works.
        Map<String, Object> config = OmsExtractSupport.toPlainMap(rawConfig)
        if (!config) {
            return failure(["OMS source config could not be read."], [], retainRecords)
        }
        Closure pageFetcher = (options?.pageFetcher instanceof Closure) ? (Closure) options.pageFetcher : null
        if (pageFetcher == null) return failure(["An OMS returns extract needs its GraphQL page source."], [], retainRecords)

        Long fromMillis = OmsExtractSupport.parseWindowMillis(windowStart, "windowStart", errors)
        Long thruMillis = OmsExtractSupport.parseWindowMillis(windowEnd, "windowEnd", errors)
        if (errors) return failure(errors, [], retainRecords)

        // Parsed once, before any page is fetched, so a malformed rule fails pre-flight rather than
        // mid-window on some later page (matches the orders contract). parseRules returns the rule
        // list directly (List<Map<String,Object>>), not a wrapping Map.
        List<Map<String, Object>> parsedFilters
        try {
            parsedFilters = SourceFilterSupport.parseRules(sourceFilters)
        } catch (Exception e) {
            return failure([normalize(e.message) ?: "Configured exclusion rules are invalid."], [], retainRecords)
        }

        List<Map<String, Object>> collected = []
        OutputSink sink = new OutputSink(targetFile)
        Map<String, Object> serverCounts = [:]
        Map<String, Object> exclusionCounts = [:]
        Map<String, Object> fieldAbsentCounts = [:]
        long cumulativeRaw = 0L

        // One record pipeline: client filters, the join-key fallback, projection, the sink.
        Closure consumePage = { List pageReturns ->
            pageReturns.each { Object raw ->
                if (!(raw instanceof Map)) return
                Map<String, Object> record = (Map<String, Object>) raw
                // Configured exclusions run here, after the page source's own exclusions: a return it
                // excluded (excludedNoShopifyRefCount) never arrives, so nothing is counted twice.
                Map<String, Object> verdict = SourceFilterSupport.evaluate(record, parsedFilters)
                if (verdict != null) {
                    String key = String.valueOf(((Map) verdict.get("rule")).get("sequenceNum"))
                    // Two buckets, one rejection: an include rule that drops a whole row class
                    // for want of the field must not read as "the rule matched many values".
                    Map<String, Object> bucket = SourceFilterSupport.REASON_FIELD_ABSENT == verdict.get("reason")
                            ? fieldAbsentCounts
                            : exclusionCounts
                    bucket.put(key, normalizeInt(bucket.get(key), 0) + 1)
                    return
                }
                applyJoinKeyFallback(record)
                Map<String, Object> projected = projectRecord(record, keepRecordFields)
                sink.write(projected)
                if (retainRecords) collected.add(projected)
            }
        }

        String transport = null
        try {
            Map fetched = (Map) pageFetcher.call([config: config, fromMillis: fromMillis, thruMillis: thruMillis,
                    pageConsumer: { List pageReturns ->
                        cumulativeRaw += pageReturns.size()
                        consumePage(pageReturns)
                        if (pageProgressListener != null) pageProgressListener.call(cumulativeRaw)
                    }])
            errors.addAll((List<String>) (fetched?.errors ?: []))
            warnings.addAll((List<String>) (fetched?.warnings ?: []))
            serverCounts.putAll((Map) (fetched?.serverCounts ?: [:]))
            transport = fetched?.transport as String
            if (errors) {
                sink.abort()
            } else {
                sink.finish(withTransport(buildMetadata(serverCounts, exclusionCounts, fieldAbsentCounts, parsedFilters), transport))
            }
        } catch (Exception e) {
            sink.abort()
            errors.add("OMS returns extraction failed: ${e.message}".toString())
        } finally {
            sink.close()
        }
        if (errors) {
            if (targetFile != null) targetFile.delete()
            return failure(errors, warnings, retainRecords)
        }
        int fetchedCount = sink.writtenCount
        Map<String, Object> fetchedResult = [
                recordCount    : fetchedCount,
                dataAvailable  : fetchedCount > 0,
                requestMetadata: withTransport(buildMetadata(serverCounts, exclusionCounts, fieldAbsentCounts, parsedFilters), transport),
                warnings       : warnings,
                errors         : [],
                fileName       : OmsExtractSupport.buildDefaultFileName(fromMillis, thruMillis, DEFAULT_FILE_NAME_PREFIX),
        ]
        if (retainRecords) fetchedResult.put("records", collected)
        return fetchedResult
    }

    /**
     * JOIN-KEY FALLBACK (2026-08-20). `externalId` is the returns-pair join key, and OMS leaves it
     * BLANK until a refund exists — 67 of 772 rows in the 2026-08-20 live run. Those rows reached the
     * compare with no key at all and could only ever land as false "missing in Shopify" differences.
     *
     * The fallback is not a guess: it tracks Shopify's own grain exactly. Shopify emits a RETURN row
     * while a return is unrefunded and switches to the REFUND row once a refund exists (the
     * refunded-return narrowing in ShopifyReturnRefsSupport). So an OMS return with no refund yet is
     * named on the Shopify side by its RETURN id — which is precisely `shopifyReturnId`.
     *
     * Measured against the full 772-row run: 283 rows matched a Shopify event via externalId
     * (268 REFUND / 15 RETURN) and 65 via this fallback (65 RETURN / **0 REFUND**) — the fallback
     * never once engaged for a refunded event. Zero key collisions, zero rows left keyless.
     *
     * Do NOT invert this into "prefer shopifyReturnId": the two fields hold DIFFERENT Shopify objects
     * for a refunded return (32 of 45 sampled differed — externalId the refund id, shopifyReturnId the
     * return id), and Shopify suppresses the RETURN row once refunded, so preferring shopifyReturnId
     * would break every refunded match.
     */
    protected static void applyJoinKeyFallback(Map<String, Object> record) {
        if (record == null) return
        if (normalize(record.get("externalId"))) return
        String fallback = normalize(record.get("shopifyReturnId"))
        if (fallback) record.put("externalId", fallback)
    }

    /**
     * Point-existence check for OMS return ids, the missing-in-OMS mirror of Shopify's
     * lookup#ShopifyRefundOrReturnIds. Returns [ok, foundIds, missingIds, errors] — the contract
     * MissingDiffVerificationSupport consumes.
     *
     * Never narrowed by the run window: OMS dates a return when it was created, Shopify when it was refunded.
     */
    static Map<String, Object> lookupReturnsByExternalId(Object rawConfig, Collection externalIds) {
        List<String> ids = (externalIds ?: []).collect { normalize(it) }.findAll { it }.unique()
        if (!ids) return [ok: false, foundIds: [], missingIds: [], errors: ["No externalIds provided for OMS returns lookup."]]

        Map<String, Object> config = OmsExtractSupport.toPlainMap(rawConfig)
        if (!config) return [ok: false, foundIds: [], missingIds: [], errors: ["OMS REST source config could not be read."]]

        // DAR-BE-116 (2026-10-23): GraphQL only. Every OMS read goes through GraphQL (Aditi); the REST passes
        // asked 20 ids at a time over a 10-year window, one or two calls per chunk, and a returns run's
        // VERIFY step spent half an hour on them. The three passes below answer the same three id kinds
        // the lookup has always been handed: the OMS returnId (a returnId-keyed compare, e.g. OMS returns
        // vs NetSuite RMAs), the Shopify refund id (Return.externalId), and the Shopify return id
        // (returnIdentifications). An id is MISSING only when every pass ran and none found it; a pass that
        // could not run leaves its ids UNRESOLVED, so a degraded OMS over-reports instead of hiding rows.
        Set<String> found = new LinkedHashSet<String>()
        Set<String> unresolved = new LinkedHashSet<String>()
        List<String> errors = []
        List<String> askable = ids.findAll { it ==~ /[A-Za-z0-9_\-]+/ }
        unresolved.addAll(ids.findAll { !askable.contains(it) })

        OmsGqlClient client
        try {
            client = (OmsGqlClient) gqlClientFactory.call(config)
        } catch (Exception e) {
            return [ok: false, foundIds: [], missingIds: [], unresolvedIds: ids,
                    errors: ["OMS GraphQL returns lookup unavailable: ${e.message}".toString()]]
        }
        ["returnId", "externalId"].each { String field ->
            List<String> remaining = askable.findAll { !found.contains(it) }
            if (!remaining) return
            Map pass = lookupReturnsByField(client, field, remaining)
            found.addAll((Collection<String>) pass.found)
            unresolved.addAll((Collection<String>) pass.unresolved)
            errors.addAll((Collection<String>) pass.errors)
        }
        List<String> remaining = askable.findAll { !found.contains(it) }
        if (remaining) {
            Map gql = lookupShopifyReturnIdentifications(client, remaining)
            found.addAll((Collection<String>) gql.found)
            unresolved.addAll((Collection<String>) gql.unresolved)
            errors.addAll((Collection<String>) gql.errors)
        }
        unresolved.removeAll(found)
        return [ok: true, foundIds: ids.findAll { found.contains(it) },
                missingIds: ids.findAll { !found.contains(it) && !unresolved.contains(it) },
                unresolvedIds: ids.findAll { unresolved.contains(it) },
                errors: errors]
    }

    /**
     * returns(query: "<field>:a,b,...") in chunks, each paged to exhaustion and echo-checked on the field
     * it filtered by. [found, unresolved, errors]: a failed chunk, or one whose answer echoed none of the
     * ids it asked for (the server ignored the filter), leaves that chunk unresolved, never missing.
     */
    private static Map lookupReturnsByField(OmsGqlClient client, String field, List<String> ids) {
        Set<String> found = new LinkedHashSet<String>()
        Set<String> unresolved = new LinkedHashSet<String>()
        List<String> errors = []
        List<List<String>> chunks = ids.collate(OmsGqlQueries.RETURN_LOOKUP_CHUNK_SIZE)
        for (int i = 0; i < chunks.size(); i++) {
            List<String> chunk = chunks[i]
            try {
                List<Map> nodes = OmsGqlReturnsPageSource.fetchByIds(client, OmsGqlQueries.returnIdsDocument(), "returns", field, chunk,
                        chunk.size(), OmsGqlQueries.RETURN_LOOKUP_PAGE_SIZE, OmsGqlQueries.RETURN_LOOKUP_RESERVATION, [lookupCalls: 0])
                Set<String> echoed = nodes.collect { normalize(it.get(field)) }.findAll { it && chunk.contains(it) } as Set<String>
                if (nodes && !echoed) throw new OmsGqlException("FILTER_IGNORED", "returns ${field} filter answered rows for none of the ids asked")
                found.addAll(echoed)
            } catch (Exception e) {
                // An error the OMS ANSWERED is the answer to the document every chunk sends, so the rest of
                // this pass is not sent (as in the identifications pass); a transport failure is per chunk.
                List<String> skipped = (e instanceof OmsGqlException) ? chunks.drop(i).flatten() as List<String> : chunk
                unresolved.addAll(skipped)
                errors.add("OMS GraphQL returns lookup by ${field} failed for ${skipped.size()} id(s): ${e.message ?: e.class.simpleName}".toString())
                if (e instanceof OmsGqlException) break
            }
        }
        return [found: found, unresolved: unresolved, errors: errors.unique()]
    }

    /**
     * GraphQL returnIdentifications(idValue:) over every identification type, accepting only the
     * Shopify return types — a NetSuite RMA id with the same digits is not the Shopify return.
     * Returns [found: Set, unresolved: Set, errors: List]. A failed call, an ignored filter (no node
     * echoes a requested id) or a truncated page leaves the affected ids unresolved.
     */
    private static Map lookupShopifyReturnIdentifications(OmsGqlClient client, List<String> askable) {
        Set<String> found = new LinkedHashSet<String>()
        Set<String> unresolved = new LinkedHashSet<String>()
        List<String> errors = []
        // Failures are counted per message and reported once each: gorjana result 101474 (DAR-BE-067)
        // carried the same OMS 400 six times, once per chunk.
        Map<String, Integer> failedCounts = new LinkedHashMap<String, Integer>()
        List<List<String>> chunks = askable.collate(OmsGqlQueries.RETURN_LOOKUP_CHUNK_SIZE)
        for (int i = 0; i < chunks.size(); i++) {
            List<String> chunk = chunks[i]
            Map data
            try {
                Map response = client.execute(OmsGqlQueries.returnIdentificationsDocument(),
                        OmsGqlQueries.returnIdentificationsVariables(chunk), OmsGqlQueries.RETURN_LOOKUP_RESERVATION)
                data = (response?.data instanceof Map) ? (Map) response.data : [:]
            } catch (Exception e) {
                // An error the OMS ANSWERED (a Moqui REST rejection, a GraphQL error) is the answer to the
                // document, and every chunk sends the same document, so the rest are not sent. A transport
                // failure says nothing about the next chunk, so the pass carries on.
                List<String> skipped = (e instanceof OmsGqlException) ? chunks.drop(i).flatten() as List<String> : chunk
                unresolved.addAll(skipped)
                String message = e.message ?: e.class.simpleName
                failedCounts.put(message, (failedCounts.get(message) ?: 0) + skipped.size())
                if (e instanceof OmsGqlException) break
                continue
            }
            Map connection = (data.returnIdentifications instanceof Map) ? (Map) data.returnIdentifications : [:]
            List edges = (connection.edges instanceof List) ? (List) connection.edges : []
            Set<String> requested = new HashSet<String>(chunk)
            Set<String> echoed = new HashSet<String>()
            Set<String> matched = new HashSet<String>()
            edges.each { Object edge ->
                Map node = (edge instanceof Map && ((Map) edge).node instanceof Map) ? (Map) ((Map) edge).node : null
                String value = normalize(node?.idValue)
                if (!value || !requested.contains(value)) return
                echoed.add(value)
                if (OmsGqlQueries.SHOPIFY_RETURN_IDENTIFICATION_TYPES.contains(normalize(node.returnIdentificationTypeId))) matched.add(value)
            }
            if (!edges.isEmpty() && echoed.isEmpty()) {
                unresolved.addAll(chunk)
                errors.add("OMS GraphQL returns lookup did not honor the idValue filter (no identification echoed a requested id).")
                continue
            }
            found.addAll(matched)
            boolean truncated = (connection.pageInfo instanceof Map) && ((Map) connection.pageInfo).hasNextPage == true
            if (truncated) unresolved.addAll(chunk.findAll { !matched.contains(it) })
        }
        failedCounts.each { String message, Integer count ->
            errors.add("OMS GraphQL returns lookup failed for ${count} id(s): ${message}".toString())
        }
        return [found: found, unresolved: unresolved, errors: errors]
    }


    /**
     * Keeps items[] nested (RQ-21). A projection naming only top-level fields must never strip
     * items — phase 2's composite key addresses it by JSON path.
     */
    protected static Map<String, Object> projectRecord(Map<String, Object> record, List keepRecordFields) {
        if (!keepRecordFields) return record
        Set<String> keep = keepRecordFields.collect { normalize(it) }.findAll { it } as Set
        keep.add("items")
        Map<String, Object> projected = [:]
        record.each { Object key, Object value ->
            if (keep.contains(String.valueOf(key))) projected.put(String.valueOf(key), value)
        }
        return projected
    }

    protected static Map<String, Object> buildMetadata(Map serverCounts, Map exclusionCounts,
                                                        Map fieldAbsentCounts,
                                                        List<Map<String, Object>> parsedFilters) {
        Map<String, Object> filters = [:]
        filters.put("excludedNoShopifyRefCount", normalizeInt(serverCounts.get("excludedNoShopifyRefCount"), 0))
        filters.put("serverReportedReturnsCount", normalizeInt(serverCounts.get("returnsCount"), 0))
        if (serverCounts.containsKey("excludedCancelledCount")) {
            filters.put("excludedCancelledCount", normalizeInt(serverCounts.get("excludedCancelledCount"), 0))
        }

        // parseRules returns the rule list directly — no "rules" wrapper key to unwrap.
        List<Map<String, Object>> rules = parsedFilters ?: []
        if (rules) {
            // Every configured rule appears, including one that matched nothing (excludedCount 0) —
            // a missing entry would read as "not applied". Absent entirely when no rules configured.
            filters.put("configuredExclusions", rules.collect { Map<String, Object> rule ->
                String key = String.valueOf(rule.get("sequenceNum"))
                return [
                        sequenceNum    : rule.get("sequenceNum"),
                        fieldExpression: rule.get("fieldExpression"),
                        operator       : rule.get("operator"),
                        values          : rule.get("values"),
                        excludedCount   : normalizeInt(exclusionCounts.get(key), 0),
                        // Present on every rule including EXCLUDE_IN ones, where it is structurally
                        // zero: a reader should not need to know which keys apply to which mode.
                        fieldAbsentCount: normalizeInt(fieldAbsentCounts?.get(key), 0),
                ]
            })
        }
        return [filters: filters]
    }

    /** The page source names its transport. */
    private static Map<String, Object> withTransport(Map<String, Object> metadata, String transport) {
        if (transport) metadata.put("transport", transport)
        return metadata
    }

    // retainRecords, NOT unconditional: extractReturnsToFile's contract is "same Map minus records"
    // on EVERY path, not just success. A failure() that always carried `records: []` would leave
    // the key present (if empty) on every extractReturnsToFile error, contradicting that contract
    // and the sibling's extractOrdersToFile, which strips `records` unconditionally.
    private static Map<String, Object> failure(List<String> errors, List<String> warnings = [],
                                                boolean retainRecords = true) {
        Map<String, Object> result = [recordCount: 0, dataAvailable: false, requestMetadata: [:],
                warnings: warnings ?: [], errors: errors ?: [], fileName: null]
        if (retainRecords) result.put("records", [])
        return result
    }

    /**
     * Streams {records:[...],metadata:{...}} so a month-scale window never holds more than a page.
     *
     * Mirrors OmsExtractSupport.OrdersDocumentSink's cleanup contract exactly: the writer opens
     * LAZILY on the first actual write (never in advance), so a request that fails before a single
     * record is known good never creates a file at all; and abort() closes without writing the
     * closing bracket/metadata, so a mid-window failure leaves either no file (nothing was ever
     * written) or a deliberately-unterminated fragment that the caller then deletes — never a
     * well-formed-looking but silently partial document.
     */
    protected static class OutputSink {
        private final File targetFile
        private Writer writer
        private boolean first = true
        int writtenCount = 0

        OutputSink(File targetFile) { this.targetFile = targetFile }

        private void ensureOpen() {
            if (writer != null || targetFile == null) return
            targetFile.getParentFile()?.mkdirs()
            writer = new OutputStreamWriter(new FileOutputStream(targetFile), "UTF-8")
            writer.write('{"records":[')
        }

        void write(Map<String, Object> record) {
            writtenCount++
            if (targetFile == null) return
            ensureOpen()
            if (!first) writer.write(",")
            writer.write(JsonOutput.toJson(record))
            first = false
        }

        void finish(Map<String, Object> metadata) {
            if (targetFile == null) return
            ensureOpen()
            writer.write('],"metadata":')
            writer.write(JsonOutput.toJson(metadata ?: [:]))
            writer.write('}')
            writer.flush()
        }

        /** Closes without writing the closing bracket/metadata — never call finish() after this. */
        void abort() {
            if (writer != null) {
                try { writer.close() } catch (Exception ignored) { }
                writer = null
            }
        }

        void close() {
            try { writer?.close() } catch (Exception ignored) { }
        }
    }
}
