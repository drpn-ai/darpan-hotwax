package darpan.hotwax.oms

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * OMS connection diagnostics must separate "we cannot read our own stored secret" from "the remote system rejected it"
 * from "the orders read is wrong". The first case is the live production failure — an apiToken that will not
 * decrypt — and it must be caught before a socket is opened, because a probe that starts at reachability reports it as
 * a network problem. The probe is one GraphQL `orders(first: 1)` query (DAR-BE-052: OMS is GraphQL only).
 */
class OmsConnectionProbeTests {

    @AfterEach
    void restoreSender() {
        OmsExtractSupport.probeSender = null
    }

    private static Map<String, Object> config(Map overrides = [:]) {
        return ([
                baseUrl : "https://oms.example.com",
                authType: "BEARER",
                apiToken: "oms_secret_token",
        ] + overrides) as Map<String, Object>
    }

    private static Map<String, Object> response(int statusCode, String body = "") {
        return [statusCode: statusCode, body: body] as Map<String, Object>
    }

    private static String ordersBody(int count) {
        return JsonOutput.toJson([data: [orders: [edges: (1..<(count + 1)).collect { [node: [orderId: "M${it}".toString()]] }]],
                                  errors: []])
    }

    private static Map<String, Object> checkFor(Map<String, Object> result, String key) {
        Map<String, Object> row = ((List<Map<String, Object>>) result.checks).find { it.key == key }
        assertNotNull(row, "expected a '${key}' check row, got ${((List) result.checks)*.key}")
        return row
    }

    private static void assertStatus(Map<String, Object> result, String key, String expected) {
        assertEquals(expected, checkFor(result, key).status, "check '${key}' detail=${checkFor(result, key).detail}")
    }

    @Test
    void healthyConnectionPassesEveryCheck() {
        List<Map> requests = []
        OmsExtractSupport.probeSender = { String url, Map headers, String body ->
            requests.add([url: url, headers: headers, body: body])
            return response(200, ordersBody(1))
        }

        Map<String, Object> result = OmsExtractSupport.probeConnection(config())

        assertStatus(result, "credential", "PASS")
        assertStatus(result, "reachable", "PASS")
        assertStatus(result, "auth", "PASS")
        assertStatus(result, "ordersRead", "PASS")
        assertEquals(1, requests.size(), "the probe must issue exactly one request")
        assertEquals("https://oms.example.com/rest/s1/graphql", requests[0].url)
        assertEquals("Bearer oms_secret_token", ((Map) requests[0].headers)["Authorization"])
        assertEquals(OmsExtractSupport.PROBE_DOCUMENT, new JsonSlurper().parseText((String) requests[0].body).query)
    }

    @Test
    void undecryptableTokenFailsBeforeAnyNetworkCall() {
        boolean called = false
        OmsExtractSupport.probeSender = { String url, Map headers, String body -> called = true; response(200, ordersBody(1)) }

        Map<String, Object> result = OmsExtractSupport.probeConnection(
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
        OmsExtractSupport.probeSender = { String url, Map headers, String body -> called = true; response(200, ordersBody(1)) }

        Map<String, Object> result = OmsExtractSupport.probeConnection(config([apiToken: null]))

        assertFalse(called)
        assertStatus(result, "credential", "FAIL")
        assertStatus(result, "reachable", "SKIP")
    }

    @Test
    void rejectedCredentialsKeepReachabilityPassing() {
        OmsExtractSupport.probeSender = { String url, Map headers, String body ->
            response(401, JsonOutput.toJson([errorCode: 401, errors: "User [No User] is not authorized"]))
        }

        Map<String, Object> result = OmsExtractSupport.probeConnection(config())

        assertStatus(result, "reachable", "PASS")
        assertStatus(result, "auth", "FAIL")
        assertStatus(result, "ordersRead", "SKIP")
        assertTrue((checkFor(result, "auth").detail as String).contains("401"))
    }

    @Test
    void aMissingGraphQlEndpointFailsOnlyTheOrdersCheck() {
        OmsExtractSupport.probeSender = { String url, Map headers, String body -> response(404) }

        Map<String, Object> result = OmsExtractSupport.probeConnection(config())

        assertStatus(result, "reachable", "PASS")
        assertStatus(result, "auth", "PASS")
        assertStatus(result, "ordersRead", "FAIL")
        assertTrue((checkFor(result, "ordersRead").detail as String).contains("GraphQL endpoint"))
    }

    @Test
    void aGraphQlRefusalFailsTheOrdersCheckWithItsCode() {
        OmsExtractSupport.probeSender = { String url, Map headers, String body ->
            response(200, JsonOutput.toJson([data: null, errors: [[message: "nope", extensions: [code: "FORBIDDEN"]]]]))
        }

        Map<String, Object> result = OmsExtractSupport.probeConnection(config())

        assertStatus(result, "auth", "PASS")
        assertStatus(result, "ordersRead", "FAIL")
        assertTrue((checkFor(result, "ordersRead").detail as String).contains("FORBIDDEN"))
    }

    @Test
    void htmlErrorPageWithHttp200IsNotHealthy() {
        // A login page or proxy interstitial answering 200 is the classic false-green.
        OmsExtractSupport.probeSender = { String url, Map headers, String body -> response(200, "<html><body>Sign in</body></html>") }

        Map<String, Object> result = OmsExtractSupport.probeConnection(config())

        assertStatus(result, "auth", "PASS")
        assertStatus(result, "ordersRead", "FAIL")
        assertTrue((checkFor(result, "ordersRead").detail as String).contains("JSON"))
    }

    @Test
    void unreachableHostSkipsAuthAndOrders() {
        OmsExtractSupport.probeSender = { String url, Map headers, String body -> throw new java.net.UnknownHostException("oms.example.com") }

        Map<String, Object> result = OmsExtractSupport.probeConnection(config())

        assertStatus(result, "credential", "PASS")
        assertStatus(result, "reachable", "FAIL")
        assertStatus(result, "auth", "SKIP")
        assertStatus(result, "ordersRead", "SKIP")
    }

    @Test
    void missingBaseUrlNeverReachesTheNetwork() {
        boolean called = false
        OmsExtractSupport.probeSender = { String url, Map headers, String body -> called = true; response(200, ordersBody(1)) }

        Map<String, Object> result = OmsExtractSupport.probeConnection(config([baseUrl: null, authType: "NONE", apiToken: null]))

        assertFalse(called)
        assertStatus(result, "reachable", "FAIL")
    }

    @Test
    void noOrdersYetStillPasses() {
        OmsExtractSupport.probeSender = { String url, Map headers, String body -> response(200, ordersBody(0)) }

        Map<String, Object> result = OmsExtractSupport.probeConnection(config())

        assertStatus(result, "ordersRead", "PASS")
        assertEquals("no orders yet", checkFor(result, "ordersRead").detail)
    }

    @Test
    void noCheckDetailEverEchoesAStoredSecret() {
        String secret = "oms_super_secret_token"
        List<Map<String, Object>> results = []
        [401, 404, 500].each { int statusCode ->
            OmsExtractSupport.probeSender = { String url, Map headers, String body -> response(statusCode, "token ${secret} rejected") }
            results.add(OmsExtractSupport.probeConnection(config([apiToken: secret])))
        }
        OmsExtractSupport.probeSender = { String url, Map headers, String body -> response(200, "not json ${secret}") }
        results.add(OmsExtractSupport.probeConnection(config([apiToken: secret])))

        results.each { Map<String, Object> result ->
            String rendered = ((List<Map<String, Object>>) result.checks)*.detail.join(" | ")
            assertFalse(rendered.contains(secret), "a check detail leaked the stored token: ${rendered}")
        }
    }

    @Test
    void aThrottledAnswerIsNeverWaitedOnOrRetried() {
        int calls = 0
        OmsExtractSupport.probeSender = { String url, Map headers, String body ->
            calls++
            response(200, JsonOutput.toJson([data: null, errors: [[message: "throttled", extensions: [code: "THROTTLED"]]]]))
        }

        Map<String, Object> result = OmsExtractSupport.probeConnection(config())

        assertEquals(1, calls, "an operator is watching: one call, no throttle wait")
        assertStatus(result, "ordersRead", "FAIL")
    }

    @Test
    void stagesRunOneAtATimeAndChainViaNextStage() {
        int calls = 0
        OmsExtractSupport.probeSender = { String url, Map headers, String body -> calls++; response(200, ordersBody(1)) }

        Map<String, Object> one = OmsExtractSupport.probeConnectionStage(config(), OmsExtractSupport.PROBE_STAGE_FIRST)
        assertEquals(["credential"], ((List<Map>) one.checks)*.key)
        assertEquals("connect", one.nextStage)
        assertEquals(0, calls, "the credential stage must not touch the network")

        Map<String, Object> two = OmsExtractSupport.probeConnectionStage(config(), (String) one.nextStage)
        assertEquals(["reachable", "auth", "ordersRead"], ((List<Map>) two.checks)*.key)
        assertNull(two.nextStage)
        assertEquals(1, calls)
    }

    @Test
    void anUnreadableCredentialEndsTheWalkAtStageOne() {
        boolean called = false
        OmsExtractSupport.probeSender = { String url, Map headers, String body -> called = true; response(200, ordersBody(1)) }

        Map<String, Object> result = OmsExtractSupport.probeConnectionStage(
                config([apiToken: null, credentialError: "The stored credentials could not be decrypted."]),
                OmsExtractSupport.PROBE_STAGE_FIRST)

        assertFalse(called)
        assertNull(result.nextStage, "no further stage can be meaningful")
        assertEquals(["credential", "reachable", "auth", "ordersRead"], ((List<Map>) result.checks)*.key)
        assertStatus(result, "credential", "FAIL")
    }

    @Test
    void walkingTheStagesMatchesRunningThemAllAtOnce() {
        OmsExtractSupport.probeSender = { String url, Map headers, String body -> response(200, ordersBody(1)) }
        Map<String, Object> oneShot = OmsExtractSupport.probeConnection(config())

        List<Map> walked = []
        String stage = OmsExtractSupport.PROBE_STAGE_FIRST
        while (stage) {
            Map<String, Object> step = OmsExtractSupport.probeConnectionStage(config(), stage)
            walked.addAll((List<Map>) step.checks)
            stage = (String) step.nextStage
        }

        assertEquals(((List<Map>) oneShot.checks)*.key, walked*.key)
        assertEquals(((List<Map>) oneShot.checks)*.status, walked*.status)
    }
}
