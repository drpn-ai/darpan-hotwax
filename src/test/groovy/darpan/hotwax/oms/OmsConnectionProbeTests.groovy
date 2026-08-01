package darpan.hotwax.oms

import groovy.json.JsonOutput
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * OMS connection diagnostics must separate "we cannot read our own stored secret" from "the remote
 * system rejected it" from "the orders path is wrong". The first case is the live production
 * failure — an apiToken that will not decrypt — and it must be caught before a socket is opened,
 * because a probe that starts at reachability reports it as a network problem.
 */
class OmsConnectionProbeTests {

    @AfterEach
    void restoreHttpClient() {
        OmsRestSourceSupport.resetHttpClient()
    }

    private static Map<String, Object> config(Map overrides = [:]) {
        return ([
                baseUrl   : "https://oms.example.com",
                ordersPath: "/rest/s1/oms/orders",
                authType  : "BEARER",
                apiToken  : "oms_secret_token",
        ] + overrides) as Map<String, Object>
    }

    private static Map<String, Object> response(int statusCode, String body = "") {
        return [statusCode: statusCode, body: body] as Map<String, Object>
    }

    private static String ordersBody(int count) {
        return JsonOutput.toJson([orders: (1..<(count + 1)).collect { [orderId: "M${it}".toString()] }])
    }

    private static Map<String, Object> checkFor(Map<String, Object> result, String key) {
        Map<String, Object> row = ((List<Map<String, Object>>) result.checks).find { it.key == key }
        assertNotNull(row, "expected a '${key}' check row, got ${((List) result.checks)*.key}")
        return row
    }

    private static void assertStatus(Map<String, Object> result, String key, String expected) {
        assertEquals(expected, checkFor(result, key).status,
                "check '${key}' detail=${checkFor(result, key).detail}")
    }

    @Test
    void healthyConnectionPassesEveryCheck() {
        List<Map> requests = []
        OmsRestSourceSupport.setHttpClient { Map request ->
            requests.add(request)
            return response(200, ordersBody(1))
        }

        Map<String, Object> result = OmsRestSourceSupport.probeConnection(config())

        assertStatus(result, "credential", "PASS")
        assertStatus(result, "reachable", "PASS")
        assertStatus(result, "auth", "PASS")
        assertStatus(result, "ordersRead", "PASS")

        assertEquals(1, requests.size(), "the probe must issue exactly one request")
        String url = requests[0].url as String
        // Reuses the extractor's own URL construction, and asks for a single record.
        assertTrue(url.startsWith("https://oms.example.com/rest/s1/oms/orders?"), url)
        assertTrue(url.contains("pageSize=1"), url)
        assertEquals("Bearer oms_secret_token", ((Map) requests[0].headers)["Authorization"])
        // Operator-facing timeouts, not the extractor's bulk defaults.
        assertEquals(OmsRestSourceSupport.PROBE_CONNECT_TIMEOUT_SECONDS, requests[0].connectTimeoutSeconds)
        assertEquals(OmsRestSourceSupport.PROBE_READ_TIMEOUT_SECONDS, requests[0].readTimeoutSeconds)
    }

    @Test
    void undecryptableTokenFailsBeforeAnyNetworkCall() {
        // The live production failure: entity_ds_crypt_pass cannot decrypt the stored apiToken, so
        // buildHeaders never produces an Authorization header and no socket is ever opened.
        boolean called = false
        OmsRestSourceSupport.setHttpClient { Map request -> called = true; return response(200, ordersBody(1)) }

        Map<String, Object> result = OmsRestSourceSupport.probeConnection(
                config([apiToken: null, credentialError: "The stored credentials could not be decrypted."]))

        assertFalse(called, "an unreadable credential must not produce an HTTP call")
        assertStatus(result, "credential", "FAIL")
        assertTrue((checkFor(result, "credential").detail as String).contains("decrypted"))
        // The rest is untested, not broken — reporting these as FAIL would misdirect the operator.
        assertStatus(result, "reachable", "SKIP")
        assertStatus(result, "auth", "SKIP")
        assertStatus(result, "ordersRead", "SKIP")
    }

    @Test
    void missingTokenForBearerAuthIsACredentialFailure() {
        boolean called = false
        OmsRestSourceSupport.setHttpClient { Map request -> called = true; return response(200, ordersBody(1)) }

        Map<String, Object> result = OmsRestSourceSupport.probeConnection(config([apiToken: null]))

        assertFalse(called)
        assertStatus(result, "credential", "FAIL")
        assertStatus(result, "reachable", "SKIP")
    }

    @Test
    void rejectedCredentialsKeepReachabilityPassing() {
        OmsRestSourceSupport.setHttpClient { Map request -> response(401) }

        Map<String, Object> result = OmsRestSourceSupport.probeConnection(config())

        // The host answered, so it is reachable — the credential is what failed.
        assertStatus(result, "reachable", "PASS")
        assertStatus(result, "auth", "FAIL")
        assertStatus(result, "ordersRead", "SKIP")
        assertTrue((checkFor(result, "auth").detail as String).contains("401"))
    }

    @Test
    void wrongOrdersPathFailsOnlyTheOrdersCheck() {
        OmsRestSourceSupport.setHttpClient { Map request -> response(404) }

        Map<String, Object> result = OmsRestSourceSupport.probeConnection(config([ordersPath: "/rest/s1/oms/nope"]))

        assertStatus(result, "reachable", "PASS")
        assertStatus(result, "auth", "PASS")
        assertStatus(result, "ordersRead", "FAIL")
        assertTrue((checkFor(result, "ordersRead").detail as String).contains("orders path"))
    }

    @Test
    void htmlErrorPageWithHttp200IsNotHealthy() {
        // A login page or proxy interstitial answering 200 is the classic false-green.
        OmsRestSourceSupport.setHttpClient { Map request -> response(200, "<html><body>Sign in</body></html>") }

        Map<String, Object> result = OmsRestSourceSupport.probeConnection(config())

        assertStatus(result, "auth", "PASS")
        assertStatus(result, "ordersRead", "FAIL")
        assertTrue((checkFor(result, "ordersRead").detail as String).contains("JSON"))
    }

    @Test
    void unreachableHostSkipsAuthAndOrders() {
        OmsRestSourceSupport.setHttpClient { Map request -> throw new java.net.UnknownHostException("oms.example.com") }

        Map<String, Object> result = OmsRestSourceSupport.probeConnection(config())

        assertStatus(result, "credential", "PASS")
        assertStatus(result, "reachable", "FAIL")
        assertStatus(result, "auth", "SKIP")
        assertStatus(result, "ordersRead", "SKIP")
    }

    @Test
    void missingBaseUrlNeverReachesTheNetwork() {
        boolean called = false
        OmsRestSourceSupport.setHttpClient { Map request -> called = true; return response(200, ordersBody(1)) }

        Map<String, Object> result = OmsRestSourceSupport.probeConnection(
                config([baseUrl: null, authType: "NONE", apiToken: null]))

        assertFalse(called)
        assertStatus(result, "reachable", "FAIL")
    }

    @Test
    void emptyResultSetStillPasses() {
        // An empty window is a healthy connection, not a broken one.
        OmsRestSourceSupport.setHttpClient { Map request -> response(200, JsonOutput.toJson([orders: []])) }

        Map<String, Object> result = OmsRestSourceSupport.probeConnection(config())

        assertStatus(result, "ordersRead", "PASS")
        assertEquals("no orders in range", checkFor(result, "ordersRead").detail)
    }

    @Test
    void noCheckDetailEverEchoesAStoredSecret() {
        String secret = "oms_super_secret_token"
        List<Map<String, Object>> results = []
        [401, 404, 500].each { int statusCode ->
            OmsRestSourceSupport.setHttpClient { Map request -> response(statusCode, "token ${secret} rejected") }
            results.add(OmsRestSourceSupport.probeConnection(config([apiToken: secret])))
        }
        OmsRestSourceSupport.setHttpClient { Map request -> response(200, "not json ${secret}") }
        results.add(OmsRestSourceSupport.probeConnection(config([apiToken: secret])))

        results.each { Map<String, Object> result ->
            String rendered = ((List<Map<String, Object>>) result.checks)*.detail.join(" | ")
            assertFalse(rendered.contains(secret), "a check detail leaked the stored token: ${rendered}")
        }
    }
}
