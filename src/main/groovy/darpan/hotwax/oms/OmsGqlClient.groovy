package darpan.hotwax.oms

import darpan.facade.common.OutboundHttpPolicy
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

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

    /** THROTTLED retries after the first attempt. Each waits for the bucket to refill. */
    static final int MAX_THROTTLE_RETRIES = 3

    private final Map config
    private final Closure sender
    private final Map<String, String> headers
    final OmsGqlThrottleGovernor governor = new OmsGqlThrottleGovernor()
    int callCount = 0
    Closure sleeper = { long ms -> Thread.sleep(ms) }

    OmsGqlClient(Map config, Closure sender = null) {
        this.config = config ?: [:]
        this.headers = new LinkedHashMap<String, String>(OmsRestSourceSupport.buildHeaders(this.config))
        this.headers.put("Content-Type", "application/json")
        this.sender = sender ?: defaultSender(this.config)
    }

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

        for (int attempt = 0; ; attempt++) {
            long wait = governor.sleepMillisFor(reservation)
            if (wait > 0L) sleeper.call(wait)
            callCount++
            try {
                return send(json)
            } catch (OmsGqlException e) {
                if (e.code != "THROTTLED" || attempt >= MAX_THROTTLE_RETRIES) throw e
                // observe() already recorded the drained bucket, so the next sleepMillisFor waits.
            }
        }
    }

    private Map send(String json) {
        Map response = (Map) sender.call(getUrl(), headers, json)
        int status = (response?.statusCode ?: 0) as int
        String raw = response?.body as String

        Object parsed = null
        try { parsed = raw ? new JsonSlurper().parseText(raw) : null } catch (ignored) { }
        if (!(parsed instanceof Map)) {
            throw new OmsGqlException("UNPARSEABLE", "HTTP ${status}: body was not JSON: ${raw?.take(200)}")
        }
        Map body = (Map) parsed
        if (body.extensions instanceof Map) governor.observe((Map) body.extensions)

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
