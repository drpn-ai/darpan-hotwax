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
 * Headers are OmsRestSourceSupport.buildHeaders(config) — the same auth handling as the REST
 * connector (BEARER, BASIC, API_KEY with its configured header, headersJson), so a config that works
 * for REST works here. The `sender` is injected for tests; the default re-validates the URL against
 * OutboundHttpPolicy at request time exactly as the REST path does (audit 2026-06-11 #15).
 */
class OmsGqlClient {

    /**
     * DAR-BE-116: THROTTLED is retried until this much time has been spent waiting, not a fixed count. Parallel
     * slices and other extracts share one bucket, so a large page can lose to cheaper calls many times in a row
     * (live: 10 in 15 s with the bucket hovering near 500); only a bucket that stays shut fails the call.
     */
    static final long MAX_THROTTLE_WAIT_MILLIS = 300_000L
    /** Each throttled retry backs off 1s, 2s, 4s ... capped here, plus up to a second of jitter. */
    static final long MAX_THROTTLE_BACKOFF_MILLIS = 15_000L

    private static final Logger logger = LoggerFactory.getLogger(OmsGqlClient.class)

    private final Map config
    private final Closure sender
    private final Map<String, String> headers
    final OmsGqlThrottleGovernor governor
    private final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger()
    Closure sleeper = { long ms -> Thread.sleep(ms) }

    OmsGqlClient(Map config, Closure sender = null) {
        this.config = config ?: [:]
        this.headers = new LinkedHashMap<String, String>(OmsRestSourceSupport.buildHeaders(this.config))
        this.headers.put("Content-Type", "application/json")
        this.sender = sender ?: defaultSender(this.config)
        // A real sender spends the endpoint's one shared bucket (DAR-BE-116); an injected one (tests) keeps its own.
        this.governor = sender == null ? OmsGqlThrottleGovernor.forUrl(getUrl()) : new OmsGqlThrottleGovernor()
        this.realEndpoint = sender == null
    }

    /** True when this client talks to the configured OMS (no injected sender): only then may reads be shared. */
    final boolean realEndpoint

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
     * THROTTLED alone is retried, after waiting for the bucket, because a second extract sharing the
     * same OMS budget can drain it between our observation and our call.
     */
    Map execute(String document, Map variables, int reservation) {
        Map payload = [query: document]
        if (variables) payload.variables = variables
        String json = JsonOutput.toJson(payload)
        String operation = (document =~ /query\s+(\w+)/).with { it.find() ? it.group(1) : "query" }

        long throttledWait = 0L
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
                if (e.code != "THROTTLED" || throttledWait >= MAX_THROTTLE_WAIT_MILLIS) throw e
                // observe() already recorded the drained bucket; back off on top of it so a starved call
                // stops competing every second with the calls that keep winning.
                backoff = Math.min(MAX_THROTTLE_BACKOFF_MILLIS, 1000L << Math.min(attempt, 4)) +
                        (long) (Math.random() * 1000L)
            } finally {
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

    private Map send(String json, Map extensionsOut = [:]) {
        Map response = (Map) sender.call(getUrl(), headers, json)
        int status = (response?.statusCode ?: 0) as int
        String raw = response?.body as String

        Object parsed = null
        try { parsed = raw ? new JsonSlurper().parseText(raw) : null } catch (ignored) { }
        if (!(parsed instanceof Map)) {
            throw new OmsGqlException("UNPARSEABLE", "HTTP ${status}: body was not JSON: ${raw?.take(200)}")
        }
        Map body = (Map) parsed
        if (body.extensions instanceof Map) {
            governor.observe((Map) body.extensions)
            extensionsOut.putAll((Map) body.extensions)
        }

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

    private static Closure defaultSender(Map config) {
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
            HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
            return [statusCode: resp.statusCode(), body: resp.body()]
        }
    }
}
