package darpan.hotwax.oms

import groovy.json.JsonSlurper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

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
    void safeRowsKeepSecretsOut() {
        Map<String, Object> safeRow = OmsRestSourceSupport.safeConfigMap([
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
        ])

        assertEquals("KREWE_OMS", safeRow.omsRestSourceConfigId)
        assertEquals("https://example.hotwax.io", safeRow.baseUrl)
        assertTrue(safeRow.hasApiToken as boolean)
        assertTrue(safeRow.hasPassword as boolean)
        assertEquals("America/Chicago", safeRow.timeZone)
        assertFalse(safeRow.canReadOrders as boolean)
        assertFalse(safeRow.containsKey("apiToken"))
        assertFalse(safeRow.containsKey("password"))
        assertFalse(safeRow.customHeaderNames.contains("Authorization"))
    }

    @Test
    void safeRowsPreserveEntityBackedTimezoneAndReadFlag() {
        Map<String, Object> safeRow = OmsRestSourceSupport.safeConfigMap(new EntityLikeRecord(fields: [
                omsRestSourceConfigId: "GORJANA_OMS",
                companyUserGroupId   : "GORJANA",
                baseUrl              : "https://gorjana.hotwax.io",
                ordersPath           : "/rest/s1/oms/orders",
                timeZone             : "America/Chicago",
                canReadOrders        : "N",
        ]))

        assertEquals("America/Chicago", safeRow.timeZone)
        assertFalse(safeRow.canReadOrders as boolean)
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
            return [statusCode: 200, body: '{"orders":[{"orderId":"O100","orderTypeId":"SALES_ORDER"},{"orderId":"O200","orderTypeId":"SALES_ORDER"}]}']
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
        assertTrue((capturedRequest.url as String).contains("pageSize=500"))
        assertTrue((capturedRequest.url as String).contains("pageIndex=0"))

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
    void keepsOnlySalesOrdersAndExcludesExchangeOrdersBeforeWritingOutput() {
        OmsRestSourceSupport.setHttpClient { Map ignored ->
            return [statusCode: 200, body: groovy.json.JsonOutput.toJson([
                    orders: [
                            [externalId: "O100", orderTypeId: "SALES_ORDER", orderItemAssocs: [[orderItemAssocTypeId: "PRODUCT_REPLACEMENT"]]],
                            [externalId: "O200", orderTypeId: "SALES_ORDER", orderItemAssocs: [[orderItemAssocTypeId: "EXCHANGE"]]],
                            [externalId: "O300", orderTypeId: "sales_order", orderItems: [[associations: [[orderItemAssocTypeId: "exchange"]]]]],
                            [externalId: "O400", orderTypeId: "RETURN_ORDER"],
                            [externalId: "O500"],
                    ],
            ])]
        }

        Map result = OmsRestSourceSupport.extractOrders(baseConfig(), "2026-05-01T00:00:00Z", "2026-05-01T01:00:00Z")

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertEquals(1, result.recordCount)
        assertEquals(["O100"], ((List) result.records).collect { it.externalId })
        assertEquals("SALES_ORDER", result.requestMetadata.filters.requiredOrderTypeId)
        assertEquals(2, result.requestMetadata.filters.excludedNonSalesOrderCount)
        assertEquals(2, result.requestMetadata.filters.excludedExchangeOrderCount)
        assertEquals(["EXCHANGE"], result.requestMetadata.filters.excludedOrderItemAssocTypeIds)

        Map output = JSON_SLURPER.parseText(result.outputText as String) as Map
        assertEquals(1, output.metadata.extractedRecordCount)
        assertEquals(2, output.metadata.filters.excludedNonSalesOrderCount)
        assertEquals(2, output.metadata.filters.excludedExchangeOrderCount)
        assertEquals(["O100"], ((List) output.records).collect { it.externalId })
        assertFalse(result.outputText.contains("O200"))
        assertFalse(result.outputText.contains("O300"))
        assertFalse(result.outputText.contains("O400"))
        assertFalse(result.outputText.contains("O500"))
    }

    @Test
    void paginatesOrdersUntilPageShrinks() {
        List<String> requestedUrls = []
        OmsRestSourceSupport.setHttpClient { Map request ->
            String url = request.url as String
            requestedUrls.add(url)
            int pageIndex = queryInt(url, "pageIndex")
            assertEquals(2, queryInt(url, "pageSize"))
            List orders = [
                    [[externalId: "O100", orderTypeId: "SALES_ORDER"], [externalId: "O200", orderTypeId: "SALES_ORDER"]],
                    [[externalId: "O300", orderTypeId: "SALES_ORDER"], [externalId: "O400", orderTypeId: "SALES_ORDER"]],
                    [[externalId: "O500", orderTypeId: "SALES_ORDER"]],
            ][pageIndex] ?: []
            return [statusCode: 200, body: groovy.json.JsonOutput.toJson([orders: orders])]
        }

        Map result = OmsRestSourceSupport.extractOrders(baseConfig([
                ordersPageSize: 2,
        ]), "2026-05-01T00:00:00Z", "2026-05-01T01:00:00Z")

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertEquals(5, result.recordCount)
        assertEquals(3, requestedUrls.size())
        assertEquals("pageIndexPageSize", result.requestMetadata.pagination.strategy)
        assertEquals(2, result.requestMetadata.pagination.pageSize)
        assertEquals(3, result.requestMetadata.pagination.pageCount)
        assertEquals(5, result.requestMetadata.pagination.totalFetched)

        Map output = JSON_SLURPER.parseText(result.outputText as String) as Map
        assertEquals(5, output.metadata.extractedRecordCount)
        assertEquals(["O100", "O200", "O300", "O400", "O500"], ((List) output.records).collect { it.externalId })
    }

    @Test
    void fallsBackToViewIndexWhenPageIndexDoesNotAdvance() {
        List<String> requestedUrls = []
        OmsRestSourceSupport.setHttpClient { Map request ->
            String url = request.url as String
            requestedUrls.add(url)
            if (url.contains("pageIndex=")) {
                return [statusCode: 200, body: '{"orders":[{"externalId":"O100","orderTypeId":"SALES_ORDER"},{"externalId":"O200","orderTypeId":"SALES_ORDER"}]}']
            }

            int viewIndex = queryInt(url, "viewIndex")
            assertEquals(2, queryInt(url, "viewSize"))
            List orders = viewIndex == 0 ?
                    [[externalId: "O100", orderTypeId: "SALES_ORDER"], [externalId: "O200", orderTypeId: "SALES_ORDER"]] :
                    viewIndex == 1 ? [[externalId: "O300", orderTypeId: "SALES_ORDER"]] : []
            return [statusCode: 200, body: groovy.json.JsonOutput.toJson([orders: orders])]
        }

        Map result = OmsRestSourceSupport.extractOrders(baseConfig([
                ordersPageSize: 2,
        ]), "2026-05-01T00:00:00Z", "2026-05-01T01:00:00Z")

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertEquals(3, result.recordCount)
        assertEquals("viewIndexViewSize", result.requestMetadata.pagination.strategy)
        assertTrue(result.warnings.any { it.contains("pageIndexPageSize") })
        assertEquals(2, requestedUrls.count { it.contains("pageIndex=") })
        assertEquals(2, requestedUrls.count { it.contains("viewIndex=") })
    }

    @Test
    void doesNotDuplicateOrdersPathWhenBaseUrlAlreadyIncludesEndpoint() {
        Map capturedRequest = [:]
        OmsRestSourceSupport.setHttpClient { Map request ->
            capturedRequest = request
            return [statusCode: 200, body: '{"orders":[{"orderId":"O100","orderTypeId":"SALES_ORDER"}]}']
        }

        Map result = OmsRestSourceSupport.extractOrders(baseConfig([
                baseUrl: "https://dev-maarg.hotwax.io/rest/s1/oms/orders",
        ]), "2026-05-01T00:00:00Z", "2026-05-01T01:00:00Z")

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertTrue((capturedRequest.url as String).startsWith("https://dev-maarg.hotwax.io/rest/s1/oms/orders?"))
        assertFalse((capturedRequest.url as String).contains("/rest/s1/oms/orders/rest/s1/oms/orders"))
    }

    @Test
    void encodesAdditionalQueryParameters() {
        String url = OmsRestSourceSupport.buildOrdersUrl(
                "https://dev-maarg.hotwax.io/rest/s1/oms/orders",
                1777593600000L,
                1777597200000L,
                ["custom field": "needs encoding"]
        )

        assertTrue(url.contains("custom+field=needs+encoding"))
    }

    @Test
    void retriesSwaggerRootOrdersRouteWithTrailingSlashAfter404() {
        List<String> requestedUrls = []
        OmsRestSourceSupport.setHttpClient { Map request ->
            requestedUrls.add(request.url as String)
            return requestedUrls.size() == 1 ?
                    [statusCode: 404, body: '{"error":"not found"}'] :
                    [statusCode: 200, body: '{"orders":[{"orderId":"O100","orderTypeId":"SALES_ORDER"}]}']
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
            return [statusCode: 200, body: '{"orders":[{"orderId":"O300","orderTypeId":"SALES_ORDER"}]}']
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

    private static int queryInt(String url, String key) {
        String query = new URI(url).rawQuery ?: ""
        String value = query.split("&").collect { it.split("=", 2) }
                .find { it[0] == key }?.getAt(1)
        return Integer.parseInt(value)
    }

    private static class EntityLikeRecord {
        Map<String, Object> fields = [:]

        Object get(String fieldName) {
            return fields[fieldName]
        }
    }
}
