package darpan.hotwax.oms

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-BE-064 / DAR-BE-052. The sender is injected, so every case runs without a socket. */
class OmsGqlClientTests {

    private static final Map CONFIG = [baseUrl: "https://gorjana-maarg.hotwax.io",
                                       authType: "BEARER", apiToken: "tok-123"]

    private static Map okBody(Map data, int available = 1000) {
        return [data: data,
                extensions: [cost: [actualQueryCost: 100,
                                    throttleStatus: [maximumAvailable: 1000, currentlyAvailable: available,
                                                     restoreRate: 50]]]]
    }

    private static final Map THROTTLED_BODY = [data: null,
            errors: [[message: "throttled", extensions: [code: "THROTTLED"]]],
            extensions: [cost: [throttleStatus: [currentlyAvailable: 0, restoreRate: 50, maximumAvailable: 1000]]]]

    /** Replays the queued bodies in order, recording every request. */
    private static Closure sender(List<Map> responses, List captured = null) {
        List<Map> queue = new ArrayList<>(responses)
        return { String url, Map headers, String body ->
            captured?.add([url: url, headers: headers, body: body])
            Map next = queue.size() > 1 ? queue.remove(0) : queue[0]
            return [statusCode: (next.statusCode ?: 200) as int, body: JsonOutput.toJson(next.body)]
        }
    }

    private static OmsGqlClient client(List<Map> responses, List captured = null, Map config = CONFIG) {
        OmsGqlClient c = new OmsGqlClient(config, sender(responses, captured))
        c.sleeper = { long ms -> }          // never really sleep in a test
        return c
    }

    @Test
    void aSuccessfulCallReturnsDataAndRecordsTheBucket() {
        OmsGqlClient c = client([[body: okBody([orders: [edges: []]], 880)]])
        Map result = c.execute("query { orders { edges { node { orderId } } } }", null, 400)
        assertEquals([edges: []], ((Map) result.data).orders)
        assertEquals(880, c.governor.getAvailable())
    }

    @Test
    void headersComeFromTheRestHeaderBuilderSoEveryAuthTypeWorks() {
        List captured = []
        client([[body: okBody([:])]], captured).execute("query { __typename }", null, 100)
        assertEquals("https://gorjana-maarg.hotwax.io/rest/s1/graphql", (captured[0] as Map).url)
        assertEquals("Bearer tok-123", ((Map) (captured[0] as Map).headers).get("Authorization"))

        List basic = []
        client([[body: okBody([:])]], basic, [baseUrl: "https://x.test", authType: "BASIC",
                                              username: "u", password: "p"]).execute("query { __typename }", null, 100)
        assertTrue(((Map) (basic[0] as Map).headers).get("Authorization").toString().startsWith("Basic "))
    }

    @Test
    void variablesTravelInTheBodyBesideTheQueryAndNotInsideIt() {
        List captured = []
        client([[body: okBody([:])]], captured)
                .execute('query Q($q: String) { orders(query: $q) { edges { node { orderId } } } }',
                         [q: "orderDate:<2026-09-01T00:00:00Z"], 100)
        Map sent = new JsonSlurper().parseText(((Map) captured[0]).body as String) as Map
        assertEquals("orderDate:<2026-09-01T00:00:00Z", ((Map) sent.variables).q)
        assertTrue(!((String) sent.query).contains("<"))
    }

    @Test
    void aThrottledResponseIsRetriedAfterARefillWaitAndThenSucceeds() {
        List captured = []
        List<Long> slept = []
        OmsGqlClient c = new OmsGqlClient(CONFIG, sender([[body: THROTTLED_BODY], [body: okBody([ok: true])]], captured))
        c.sleeper = { long ms -> slept << ms }
        Map result = c.execute("query { orders { edges { node { orderId } } } }", null, 400)
        assertEquals(true, ((Map) result.data).ok)
        assertEquals(2, captured.size())
        assertTrue(slept.any { it > 0L }, "a retry must wait for the bucket, not hammer it")
    }

    @Test
    void aPersistentlyThrottledCallIsAHardErrorNeverAnEmptyResult() {
        List captured = []
        OmsGqlException thrown = assertThrows(OmsGqlException) {
            client([[body: THROTTLED_BODY]], captured).execute("query { orders { edges { node { orderId } } } }", null, 400)
        }
        assertEquals("THROTTLED", thrown.code)
        assertEquals(1 + OmsGqlClient.MAX_THROTTLE_RETRIES, captured.size())
    }

    @Test
    void aGraphqlErrorListIsRaisedWithItsCode() {
        Map failed = [data: null, errors: [[message: "query cost 1075 exceeds max 1000",
                                            extensions: [code: "COST_EXCEEDED"]]]]
        OmsGqlException thrown = assertThrows(OmsGqlException) {
            client([[body: failed]]).execute("query { orders }", null, 400)
        }
        assertEquals("COST_EXCEEDED", thrown.code)
    }

    @Test
    void aMoquiRestErrorIsAStringNotAListAndMustNotBecomeOneErrorPerCharacter() {
        Map restRejection = [errorCode: 400, errors: "HTML not allowed including less-than (<), greater-than (>)"]
        OmsGqlException thrown = assertThrows(OmsGqlException) {
            client([[statusCode: 400, body: restRejection]]).execute("query { orders }", null, 400)
        }
        assertTrue(thrown.message.contains("HTML not allowed"))
        assertEquals("REST_REJECTED", thrown.code)
    }

    @Test
    void aNonJsonBodyIsReportedRatherThanSwallowed() {
        OmsGqlClient c = new OmsGqlClient(CONFIG, { String u, Map h, String b -> [statusCode: 502, body: "<html>bad gateway</html>"] })
        OmsGqlException thrown = assertThrows(OmsGqlException) { c.execute("query { orders }", null, 400) }
        assertTrue(thrown.message.contains("502"))
    }

    @Test
    void aSuccessfulResponseCarryingAnEmptyErrorsListIsNotAnError() {
        // Found by the live diff gate (2026-10-01): gorjana answers every successful query with
        // "errors": []. Reading any non-null `errors` as a REST rejection failed every page.
        Map body = okBody([orders: [edges: []]]) + [errors: []]
        Map result = client([[body: body]]).execute("query { orders { edges { node { orderId } } } }", null, 400)
        assertEquals([edges: []], ((Map) result.data).orders)
    }
}
