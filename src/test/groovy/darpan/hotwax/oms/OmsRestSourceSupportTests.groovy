package darpan.hotwax.oms

import groovy.json.JsonSlurper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

import java.sql.Timestamp
import java.time.Instant

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

class OmsRestSourceSupportTests {
    private static final JsonSlurper JSON_SLURPER = new JsonSlurper()

    @AfterEach
    void resetClient() {
        OmsRestSourceSupport.resetHttpClient()
    }

    @Test
    void convertsAutomationWindowValuesToEpochMillis() {
        long expected = Instant.parse("2026-05-01T00:00:00Z").toEpochMilli()

        assertEquals(expected, OmsRestSourceSupport.toEpochMillis("2026-05-01T00:00:00Z"))
        assertEquals(expected, OmsRestSourceSupport.toEpochMillis(Timestamp.from(Instant.parse("2026-05-01T00:00:00Z"))))
        assertEquals(expected, OmsRestSourceSupport.toEpochMillis(expected.toString()))
        assertEquals(Timestamp.valueOf("2026-05-01 00:00:00").time, OmsRestSourceSupport.toEpochMillis("2026-05-01 00:00:00"))
        assertEquals(Timestamp.valueOf("2026-05-01 00:00:00").time, OmsRestSourceSupport.toEpochMillis("2026-05-01 00:00:00.0"))
    }

    @Test
    void filtersTenantConfigsAndKeepsSecretsOutOfSafeRows() {
        List<Map<String, Object>> configs = [
                [
                        omsRestSourceConfigId: "KREWE_OMS",
                        companyUserGroupId   : "KREWE",
                        baseUrl              : "https://token@example.hotwax.io",
                        ordersPath           : "/rest/s1/oms/orders",
                        authType             : "BEARER",
                        timeZone             : "America/Chicago",
                        apiToken             : "secret-token",
                        password             : "secret-password",
                        headersJson          : '{"X-Tenant":"KREWE","Authorization":"Bearer hidden"}',
                        canReadOrders        : "N",
                ],
                [
                        omsRestSourceConfigId: "GORJANA_OMS",
                        companyUserGroupId   : "GORJANA",
                        baseUrl              : "https://gorjana.hotwax.io",
                ],
        ]

        List<Map<String, Object>> filtered = OmsRestSourceSupport.filterConfigsForTenant(configs, "KREWE")

        assertEquals(1, filtered.size())
        assertEquals("KREWE_OMS", filtered[0].omsRestSourceConfigId)
        assertEquals("https://example.hotwax.io", filtered[0].baseUrl)
        assertTrue(filtered[0].hasApiToken as boolean)
        assertTrue(filtered[0].hasPassword as boolean)
        assertEquals("America/Chicago", filtered[0].timeZone)
        assertFalse(filtered[0].canReadOrders as boolean)
        assertFalse(filtered[0].containsKey("apiToken"))
        assertFalse(filtered[0].containsKey("password"))
        assertFalse(filtered[0].customHeaderNames.contains("Authorization"))
    }

    @Test
    void rejectsCrossTenantAndReadOnlyWrites() {
        IllegalArgumentException crossTenant = assertThrows(IllegalArgumentException) {
            OmsRestSourceSupport.requireWritableTenantConfig([companyUserGroupId: "GORJANA"], "KREWE", true)
        }
        assertTrue(crossTenant.message.contains("not available in your active tenant"))

        IllegalArgumentException readOnly = assertThrows(IllegalArgumentException) {
            OmsRestSourceSupport.requireWritableTenantConfig(null, "KREWE", false)
        }
        assertTrue(readOnly.message.contains("read-only"))
    }

    @Test
    void extractsOrdersWithSafeRequestMetadata() {
        Map capturedRequest = [:]
        OmsRestSourceSupport.setHttpClient { Map request ->
            capturedRequest = request
            return [statusCode: 200, body: '{"orders":[{"orderId":"O100"},{"orderId":"O200"}]}']
        }

        Map result = OmsRestSourceSupport.extractOrders(baseConfig([
                authType   : "BEARER",
                apiToken   : "secret-token",
                headersJson: '{"X-Tenant":"KREWE"}',
        ]), "2026-05-01T00:00:00Z", "2026-05-01T01:00:00Z")

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertEquals(2, result.recordCount)
        assertTrue(result.dataAvailable as boolean)
        assertEquals("GET", capturedRequest.method)
        assertEquals("Bearer secret-token", capturedRequest.headers.Authorization)
        assertTrue((capturedRequest.url as String).contains("orderDate_from=1777593600000"))
        assertTrue((capturedRequest.url as String).contains("orderDate_thru=1777597200000"))

        String safeMetadata = result.requestMetadata.toString()
        assertFalse(safeMetadata.contains("secret-token"))
        assertFalse(safeMetadata.contains("Authorization"))
        assertFalse(result.outputText.contains("secret-token"))

        Map output = JSON_SLURPER.parseText(result.outputText as String) as Map
        assertEquals("HOTWAX_OMS_REST_ORDERS", output.metadata.sourceType)
        assertEquals("KREWE_OMS", output.metadata.omsRestSourceConfigId)
        assertEquals(2, output.metadata.extractedRecordCount)
        assertEquals(2, ((List) output.records).size())
    }

    @Test
    void doesNotDuplicateOrdersPathWhenBaseUrlAlreadyIncludesEndpoint() {
        Map capturedRequest = [:]
        OmsRestSourceSupport.setHttpClient { Map request ->
            capturedRequest = request
            return [statusCode: 200, body: '{"orders":[{"orderId":"O100"}]}']
        }

        Map result = OmsRestSourceSupport.extractOrders(baseConfig([
                baseUrl: "https://dev-maarg.hotwax.io/rest/s1/oms/orders",
        ]), "2026-05-01T00:00:00Z", "2026-05-01T01:00:00Z")

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertTrue((capturedRequest.url as String).startsWith("https://dev-maarg.hotwax.io/rest/s1/oms/orders?"))
        assertFalse((capturedRequest.url as String).contains("/rest/s1/oms/orders/rest/s1/oms/orders"))
    }

    @Test
    void retriesSwaggerRootOrdersRouteWithTrailingSlashAfter404() {
        List<String> requestedUrls = []
        OmsRestSourceSupport.setHttpClient { Map request ->
            requestedUrls.add(request.url as String)
            return requestedUrls.size() == 1 ?
                    [statusCode: 404, body: '{"error":"not found"}'] :
                    [statusCode: 200, body: '{"orders":[{"orderId":"O100"}]}']
        }

        Map result = OmsRestSourceSupport.extractOrders(baseConfig(), "2026-05-01T00:00:00Z", "2026-05-01T01:00:00Z")

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertEquals(1, result.recordCount)
        assertEquals(2, requestedUrls.size())
        assertTrue(requestedUrls[0].startsWith("https://dev-maarg.hotwax.io/rest/s1/oms/orders?"))
        assertTrue(requestedUrls[1].startsWith("https://dev-maarg.hotwax.io/rest/s1/oms/orders/?"))
        assertEquals(2, result.requestMetadata.attemptCount)
        assertEquals(200, result.requestMetadata.statusCode)
    }

    @Test
    void sendsSwaggerDocumentedApiKeyHeader() {
        Map capturedRequest = [:]
        OmsRestSourceSupport.setHttpClient { Map request ->
            capturedRequest = request
            return [statusCode: 200, body: '{"orders":[{"orderId":"O300"}]}']
        }

        Map result = OmsRestSourceSupport.extractOrders(baseConfig([
                authType: "API_KEY",
                apiToken: "secret-api-key",
        ]), "2026-05-01T00:00:00Z", "2026-05-01T01:00:00Z")

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertEquals("secret-api-key", capturedRequest.headers.api_key)
        assertFalse(capturedRequest.headers.containsKey("Authorization"))
        assertFalse(result.requestMetadata.toString().contains("secret-api-key"))
        assertTrue(result.requestMetadata.headerNames.contains("api_key"))
    }

    @Test
    void handlesEmptyOrderResultsAsSuccessfulNoData() {
        OmsRestSourceSupport.setHttpClient { Map ignored ->
            [statusCode: 200, body: '{"orders":[]}']
        }

        Map result = OmsRestSourceSupport.extractOrders(baseConfig(), "2026-05-01T00:00:00Z", "2026-05-01T01:00:00Z")

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertEquals(0, result.recordCount)
        assertFalse(result.dataAvailable as boolean)
        assertNotNull(result.outputText)
    }

    @Test
    void returnsErrorsForNon200ResponseWithoutOutput() {
        OmsRestSourceSupport.setHttpClient { Map ignored ->
            [statusCode: 503, body: '{"error":"unavailable"}']
        }

        Map result = OmsRestSourceSupport.extractOrders(baseConfig(), "2026-05-01T00:00:00Z", "2026-05-01T01:00:00Z")

        assertEquals(0, result.recordCount)
        assertFalse(result.errors.isEmpty())
        assertTrue(result.errors[0].contains("status 503"))
        assertNull(result.outputText)
    }

    @Test
    void returnsErrorsForMalformedJsonWithoutOutput() {
        OmsRestSourceSupport.setHttpClient { Map ignored ->
            [statusCode: 200, body: '{not-json']
        }

        Map result = OmsRestSourceSupport.extractOrders(baseConfig(), "2026-05-01T00:00:00Z", "2026-05-01T01:00:00Z")

        assertEquals(0, result.recordCount)
        assertFalse(result.errors.isEmpty())
        assertTrue(result.errors[0].contains("not valid JSON"))
        assertNull(result.outputText)
    }

    private static Map<String, Object> baseConfig(Map<String, Object> overrides = [:]) {
        return [
                omsRestSourceConfigId  : "KREWE_OMS",
                companyUserGroupId     : "KREWE",
                baseUrl                : "https://dev-maarg.hotwax.io",
                ordersPath             : "/rest/s1/oms/orders",
                authType               : "NONE",
                connectTimeoutSeconds  : 5,
                readTimeoutSeconds     : 10,
                isActive               : "Y",
                canReadOrders          : "Y",
        ] + overrides
    }
}
