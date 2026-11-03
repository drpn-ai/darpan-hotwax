package darpan.hotwax.oms

import darpan.facade.common.OutboundHttpPolicy
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * One POST to {baseUrl}/rest/s1/graphql, with the cost bucket honoured.
 *
 * Headers are OmsExtractSupport.buildHeaders(config) (BEARER, BASIC, API_KEY with its configured header,
 * headersJson). The `sender` is injected for tests; the default re-validates the URL against
 * OutboundHttpPolicy at request time (audit 2026-06-11 #15).
 */
class OmsGqlClient {

    /**
     * DAR-BE-116: THROTTLED is retried until this much time has been spent waiting, not a fixed count. Parallel
     * slices and other extracts share one bucket, so a large page can lose to cheaper calls many times in a row
     * (live: 10 in 15 s with the bucket hovering near 500); only a bucket that stays shut fails the call.
     */
    static final long MAX_THROTTLE_WAIT_MILLIS = 300_000L
    /**
     * DAR-BE-129: a throttled retry waits what the governor says the reported deficit needs, and at least this plus up
     * to a second of jitter, so a bucket that other spenders keep empty is not asked again at once.
     */
    static final long MIN_THROTTLE_BACKOFF_MILLIS = 1_000L
    /**
     * DAR-BE-142 (2026-10-28): a page that times out, cannot connect, or answers 429/502/503/504 is asked again (config
     * keys maxPageAttempts / retryBackoffMillis, linear backoff), so one slow HotWax answer does not fail a month's read.
     * Every document is a read-only query, so asking again is safe. The last failure fails closed (TRANSIENT).
     *
     * 500 is deliberately absent: it signals a deterministic server-side fault, so retrying only multiplies the load.
     */
    static final Set<Integer> RETRYABLE_STATUS_CODES = [429, 502, 503, 504] as Set
    static final int DEFAULT_MAX_PAGE_ATTEMPTS = 5
    static final int DEFAULT_PAGE_RETRY_BACKOFF_MILLIS = 5000
    static final Set<String> TRANSIENT_CODES = RETRYABLE_STATUS_CODES.collect { "HTTP_${it}".toString() } as Set<String>

    private static final Logger logger = LoggerFactory.getLogger(OmsGqlClient.class)

    private final Map config
    private final Closure sender
    private final Map<String, String> headers
    final OmsGqlThrottleGovernor governor
    private final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger()
    Closure sleeper = { long ms -> Thread.sleep(ms) }

    OmsGqlClient(Map config, Closure sender = null) {
        this.config = config ?: [:]
        this.headers = new LinkedHashMap<String, String>(OmsExtractSupport.buildHeaders(this.config))
        this.headers.put("Content-Type", "application/json")
        this.sender = sender ?: httpSender(this.config)
        // A real sender spends the endpoint's one shared bucket (DAR-BE-116); an injected one (tests) keeps its own.
        this.governor = sender == null ? OmsGqlThrottleGovernor.forUrl(getUrl()) : new OmsGqlThrottleGovernor()
        this.realEndpoint = sender == null
    }

    /** True when this client talks to the configured OMS (no injected sender): only then are reads shared. */
    boolean realEndpoint

    /**
     * Whose read this is: the tenant and the OMS source config (its credential). Part of every shared-read key, so two
     * tenants or two configs on one OMS URL never share a read.
     */
    String getReadScope() {
        return "${config.companyUserGroupId ?: ''}|${config.omsRestSourceConfigId ?: ''}|${getUrl()}".toString()
    }

    /** Calls made so far. Parallel slices share a client, so this is counted atomically. */
    int getCallCount() { return calls.get() }

    String getUrl() {
        String base = (config.baseUrl as String)?.replaceAll('/+$', '')
        return "${base}/rest/s1/graphql"
    }

    /**
     * Execute one document. Throws on ANY error; there is deliberately no "returned no data" path.
     * THROTTLED is retried after waiting for the bucket, because a second extract sharing the same OMS budget can
     * drain it between our observation and our call; a transient failure (DAR-BE-142) is retried a bounded number of times.
     */
    Map execute(String document, Map variables, int reservation) {
        Map payload = [query: document]
        if (variables) payload.variables = variables
        String json = JsonOutput.toJson(payload)
        String operation = (document =~ /query\s+(\w+)/).with { it.find() ? it.group(1) : "query" }

        long throttledWait = 0L
        int maxAttempts = Math.max(1, intConfig("maxPageAttempts", DEFAULT_MAX_PAGE_ATTEMPTS))
        long retryBackoff = Math.max(0, intConfig("retryBackoffMillis", DEFAULT_PAGE_RETRY_BACKOFF_MILLIS))
        int transientFailures = 0
        for (int attempt = 0; ; attempt++) {
            long wait = governor.sleepMillisFor(reservation)
            if (wait > 0L) sleeper.call(wait)
            int n = calls.incrementAndGet()
            long started = System.currentTimeMillis()
            Map extensions = [:]
            String outcome = "error"
            long backoff = 0L
            governor.acquireCall()
            try {
                Map result = send(json, extensions)
                outcome = "ok"
                return result
            } catch (OmsGqlException e) {
                outcome = e.code
                if (TRANSIENT_CODES.contains(e.code)) {
                    backoff = transientBackoff(++transientFailures, maxAttempts, retryBackoff, operation, e.message)
                } else {
                    if (e.code != "THROTTLED" || throttledWait >= MAX_THROTTLE_WAIT_MILLIS) throw e
                    // The answer's reported balance is now the governor's, so the next sleepMillisFor waits the deficit.
                    backoff = MIN_THROTTLE_BACKOFF_MILLIS + (long) (Math.random() * 1000L)
                }
            } catch (IOException e) {
                // A timeout (HttpTimeoutException), a refused or reset connection: the request may not have reached OMS.
                outcome = "IO"
                backoff = transientBackoff(++transientFailures, maxAttempts, retryBackoff, operation, e.toString())
            } finally {
                governor.settle(reservation, extensions)
                governor.releaseCall()
                // One line per call: where an extract's time goes (our wait vs OMS's answer) and the
                // bucket exactly as OMS reports it, so a raised limit is visible without a probe.
                logger.info("oms gql call op={} n={} attempt={} outcome={} waitMs={} reserved={} ms={} cost={}",
                        operation, n, attempt, outcome, wait, reservation,
                        System.currentTimeMillis() - started, JsonOutput.toJson(extensions.cost ?: null))
            }
            // Slept AFTER the in-flight permit is back, so a starved call does not hold a slot while it waits.
            sleeper.call(backoff)
            throttledWait += backoff + wait
        }
    }

    /** The wait before the next attempt of a transient failure, or a fail-closed throw once the attempts are spent. */
    private static long transientBackoff(int failures, int maxAttempts, long retryBackoff, String operation, String cause) {
        if (failures >= maxAttempts) {
            throw new OmsGqlException("TRANSIENT", "OMS GraphQL ${operation} failed ${failures} attempts; last: ${cause}".toString())
        }
        return retryBackoff * failures
    }

    private int intConfig(String key, int fallback) {
        Object v = config?.get(key)
        try { return v == null || v.toString().trim().isEmpty() ? fallback : (v.toString().trim() as int) } catch (Exception ignored) { return fallback }
    }

    private Map send(String json, Map extensionsOut = [:]) {
        Map response = (Map) sender.call(getUrl(), headers, json)
        int status = (response?.statusCode ?: 0) as int
        String raw = response?.body as String

        Object parsed = null
        try { parsed = raw ? new JsonSlurper().parseText(raw) : null } catch (ignored) { }
        if (!(parsed instanceof Map)) {
            // A gateway or overload answer often has no JSON body: transient, not unparseable. A JSON THROTTLED answer
            // still reaches the governor below, whatever its status.
            if (RETRYABLE_STATUS_CODES.contains(status)) {
                throw new OmsGqlException("HTTP_${status}".toString(), "HTTP ${status}: ${raw?.take(200)}".toString())
            }
            throw new OmsGqlException("UNPARSEABLE", "HTTP ${status}: body was not JSON: ${raw?.take(200)}")
        }
        Map body = (Map) parsed
        // The caller settles the governor with these (DAR-BE-129), whatever the answer was.
        if (body.extensions instanceof Map) extensionsOut.putAll((Map) body.extensions)

        // Two error shapes, NOT interchangeable. GraphQL answers `errors` as a List of objects; Moqui's
        // REST layer answers it as a plain String beside `errorCode`. Never coerce with `as List` —
        // Groovy turns a String into a list of its CHARACTERS.
        if (body.errors instanceof List && ((List) body.errors).size() > 0) {
            Map first = (Map) ((List) body.errors)[0]
            String code = (first.extensions instanceof Map) ? ((Map) first.extensions).code as String : null
            throw new OmsGqlException(code ?: "GRAPHQL_ERROR", first.message as String)
        }
        // An EMPTY list is success: moqui-gql answers every successful query with "errors": [].
        if (body.errors != null && !(body.errors instanceof List)) {
            throw new OmsGqlException("REST_REJECTED", "HTTP ${status}: ${body.errors}")
        }
        if (status < 200 || status >= 300) throw new OmsGqlException("HTTP_${status}", "HTTP ${status} with no error body")
        return [data: body.data, errors: [], extensions: body.extensions]
    }

    /**
     * One call, no governor and no retry: the connection probe, where an operator is watching and a retry or a
     * throttle wait would only hide the answer.
     */
    Map executeOnce(String document, Map variables = null) {
        Map payload = [query: document]
        if (variables) payload.variables = variables
        calls.incrementAndGet()
        return send(JsonOutput.toJson(payload))
    }

    /** Final review B3: an answer longer than the bounded sender's cap; the read stopped there. */
    static class BodyTooLargeException extends IllegalStateException {
        final long maxBytes
        BodyTooLargeException(long maxBytes) {
            super("The OMS answer is larger than ${maxBytes} bytes; the read was stopped.".toString())
            this.maxBytes = maxBytes
        }
    }

    /**
     * Final review B3: the body as UTF-8 text, read at most {@code maxBytes} bytes; one byte more closes the stream
     * and throws {@link BodyTooLargeException}, so an over-size answer is never held whole in memory.
     */
    static String readBounded(InputStream input, long maxBytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(maxBytes + 1, 64L * 1024))
        byte[] buffer = new byte[16 * 1024]
        long total = 0
        try {
            while (true) {
                int n = input.read(buffer, 0, (int) Math.min((long) buffer.length, maxBytes + 1 - total))
                if (n < 0) break
                total += n
                if (total > maxBytes) throw new BodyTooLargeException(maxBytes)
                out.write(buffer, 0, n)
            }
        } finally {
            input.close()
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8)
    }

    /** The real HTTP sender: (url, headers, body) -> [statusCode, body]. */
    static Closure httpSender(Map config) { return httpSender(config, 0L) }

    /**
     * The real HTTP sender; with {@code maxBodyBytes} above 0 the answer is read bounded ({@link #readBounded}). The
     * agent's fresh-query path uses the bound; extraction paths keep the plain sender.
     */
    static Closure httpSender(Map config, long maxBodyBytes) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(
                Duration.ofSeconds(((config?.connectTimeoutSeconds ?: 30) as Number).intValue())).build()
        int readTimeout = ((config?.readTimeoutSeconds ?: 120) as Number).intValue()
        return { String url, Map headers, String body ->
            def check = OutboundHttpPolicy.validate(url)
            if (!check.ok) throw new IllegalStateException("OMS endpoint URL blocked by outbound policy: ${check.error}")
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(readTimeout))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            headers.each { k, v -> b.header(k as String, v as String) }
            if (maxBodyBytes > 0) {
                HttpResponse<InputStream> streamed = http.send(b.build(), HttpResponse.BodyHandlers.ofInputStream())
                return [statusCode: streamed.statusCode(), body: readBounded(streamed.body(), maxBodyBytes)]
            }
            HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
            return [statusCode: resp.statusCode(), body: resp.body()]
        }
    }
}
