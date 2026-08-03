package darpan.hotwax.oms

import groovy.json.JsonOutput
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
                ordersPageSize        : 2,
                // Pin sequential mode: this test asserts the exact request cadence.
                ordersFetchConcurrency: 1,
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

    @Test
    void sendsBase64BasicAuthHeaderWithoutLeakingCredentials() {
        Map capturedRequest = [:]
        OmsRestSourceSupport.setHttpClient { Map request ->
            capturedRequest = request
            return [statusCode: 200, body: '{"orders":[{"orderId":"O100","orderTypeId":"SALES_ORDER"}]}']
        }

        Map result = OmsRestSourceSupport.extractOrders(baseConfig([
                authType: "BASIC",
                username: "krewe-user",
                password: "krewe-pass",
        ]), "2026-05-01T00:00:00Z", "2026-05-01T01:00:00Z")

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        String expected = "Basic " + "krewe-user:krewe-pass".getBytes("UTF-8").encodeBase64().toString()
        assertEquals(expected, capturedRequest.headers.Authorization)
        assertFalse(result.requestMetadata.toString().contains("krewe-pass"))
        assertFalse(result.outputText.contains("krewe-pass"))
    }

    @Test
    void returnsErrorWhenBasicAuthCredentialsMissing() {
        OmsRestSourceSupport.setHttpClient { Map ignored -> [statusCode: 200, body: '{"orders":[]}'] }

        Map result = OmsRestSourceSupport.extractOrders(baseConfig([
                authType: "BASIC",
                username: "",
                password: "",
        ]), "2026-05-01T00:00:00Z", "2026-05-01T01:00:00Z")

        assertTrue(result.errors.any { it.contains("Username and password are required for BASIC auth.") }, result.errors.toString())
        assertNull(result.outputText)
    }

    @Test
    void dropsBlockedHeadersFromOutboundRequestAndMetadata() {
        Map capturedRequest = [:]
        OmsRestSourceSupport.setHttpClient { Map request ->
            capturedRequest = request
            return [statusCode: 200, body: '{"orders":[{"orderId":"O100","orderTypeId":"SALES_ORDER"}]}']
        }

        Map result = OmsRestSourceSupport.extractOrders(baseConfig([
                authType   : "BEARER",
                apiToken   : "real-token",
                headersJson: '{"Authorization":"Bearer attacker","AUTHORIZATION":"Bearer attacker2","Cookie":"x=1","Host":"evil.example","X-Forwarded-For":"10.0.0.1","X-Tenant":"KREWE"}',
        ]), "2026-05-01T00:00:00Z", "2026-05-01T01:00:00Z")

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        Map headers = capturedRequest.headers as Map
        assertEquals("Bearer real-token", headers.Authorization)
        assertFalse(headers.containsKey("Cookie"))
        assertFalse(headers.containsKey("Host"))
        assertFalse(headers.containsKey("X-Forwarded-For"))
        assertEquals("KREWE", headers["X-Tenant"])

        List headerNames = result.requestMetadata.headerNames as List
        assertFalse(headerNames.contains("Authorization"))
        assertFalse(headerNames.contains("Cookie"))
        assertTrue(headerNames.contains("X-Tenant"))
    }

    @Test
    void rejectsControlCharacterHeaderValue() {
        OmsRestSourceSupport.setHttpClient { Map ignored -> [statusCode: 200, body: '{"orders":[]}'] }
        String headersJson = JsonOutput.toJson(["X-Inject": "abc\r\nX-Evil: smuggled"])

        Map result = OmsRestSourceSupport.extractOrders(baseConfig([headersJson: headersJson]),
                "2026-05-01T00:00:00Z", "2026-05-01T01:00:00Z")

        assertTrue(result.errors.any { it.contains("control characters") }, result.errors.toString())
        assertNull(result.outputText)
    }

    @Test
    void rejectsOverlongHeaderValue() {
        OmsRestSourceSupport.setHttpClient { Map ignored -> [statusCode: 200, body: '{"orders":[]}'] }
        String headersJson = JsonOutput.toJson(["X-Big": "a" * 5000])

        Map result = OmsRestSourceSupport.extractOrders(baseConfig([headersJson: headersJson]),
                "2026-05-01T00:00:00Z", "2026-05-01T01:00:00Z")

        assertTrue(result.errors.any { it.contains("exceeds 4096 chars") }, result.errors.toString())
        assertNull(result.outputText)
    }

    @Test
    void rejectsInvalidAndNonObjectHeadersJson() {
        OmsRestSourceSupport.setHttpClient { Map ignored -> [statusCode: 200, body: '{"orders":[]}'] }

        Map invalid = OmsRestSourceSupport.extractOrders(baseConfig([headersJson: '{not-json']),
                "2026-05-01T00:00:00Z", "2026-05-01T01:00:00Z")
        assertTrue(invalid.errors.any { it.contains("Headers JSON is invalid") }, invalid.errors.toString())
        assertNull(invalid.outputText)

        Map nonObject = OmsRestSourceSupport.extractOrders(baseConfig([headersJson: '["a","b"]']),
                "2026-05-01T00:00:00Z", "2026-05-01T01:00:00Z")
        assertTrue(nonObject.errors.any { it.contains("Headers JSON must be a JSON object.") }, nonObject.errors.toString())
        assertNull(nonObject.outputText)
    }

    @Test
    void reportsWarningsForEmptyAndOddlyShapedResponses() {
        OmsRestSourceSupport.setHttpClient { Map ignored -> [statusCode: 200, body: ""] }
        Map emptyBody = OmsRestSourceSupport.extractOrders(baseConfig(), "2026-05-01T00:00:00Z", "2026-05-01T01:00:00Z")
        assertTrue(emptyBody.errors.isEmpty(), emptyBody.errors.toString())
        assertEquals(0, emptyBody.recordCount)
        assertFalse(emptyBody.dataAvailable as boolean)
        assertTrue(emptyBody.warnings.any { it.contains("response body was empty") }, emptyBody.warnings.toString())

        OmsRestSourceSupport.setHttpClient { Map ignored -> [statusCode: 200, body: '{"foo":"bar"}'] }
        Map noList = OmsRestSourceSupport.extractOrders(baseConfig(), "2026-05-01T00:00:00Z", "2026-05-01T01:00:00Z")
        assertEquals(0, noList.recordCount)
        assertTrue(noList.warnings.any { it.contains("did not contain an order list") }, noList.warnings.toString())

        OmsRestSourceSupport.setHttpClient { Map ignored -> [statusCode: 200, body: '42'] }
        Map scalarRoot = OmsRestSourceSupport.extractOrders(baseConfig(), "2026-05-01T00:00:00Z", "2026-05-01T01:00:00Z")
        assertEquals(0, scalarRoot.recordCount)
        assertTrue(scalarRoot.warnings.any { it.contains("root was not an object or array") }, scalarRoot.warnings.toString())
    }

    @Test
    void returnsValidationErrorsForBadWindows() {
        Map missing = OmsRestSourceSupport.extractOrders(baseConfig(), null, "2026-05-01T00:00:00Z")
        assertTrue(missing.errors.any { it.contains("windowStart is required.") }, missing.errors.toString())
        assertNull(missing.outputText)

        Map reversed = OmsRestSourceSupport.extractOrders(baseConfig(), "2026-05-02T00:00:00Z", "2026-05-01T00:00:00Z")
        assertTrue(reversed.errors.any { it.contains("windowStart must be before or equal to windowEnd.") }, reversed.errors.toString())
        assertNull(reversed.outputText)

        Map overflow = OmsRestSourceSupport.extractOrders(baseConfig(), "99999999999999999999999999", "2026-05-01T00:00:00Z")
        assertTrue(overflow.errors.any { it.contains("must be a Timestamp, Date, ISO-8601 value, or epoch milliseconds.") }, overflow.errors.toString())
        assertNull(overflow.outputText)
    }

    @Test
    void stopsPaginationAtMaxOrdersPageCount() {
        OmsRestSourceSupport.setHttpClient { Map request ->
            int pageIndex = queryInt(request.url as String, "pageIndex")
            List orders = [
                    [externalId: "P${pageIndex}A", orderTypeId: "SALES_ORDER"],
                    [externalId: "P${pageIndex}B", orderTypeId: "SALES_ORDER"],
            ]
            return [statusCode: 200, body: JsonOutput.toJson([orders: orders])]
        }

        Map result = OmsRestSourceSupport.extractOrders(baseConfig([
                ordersPageSize    : 2,
                maxOrdersPageCount: 3,
        ]), "2026-05-01T00:00:00Z", "2026-05-01T01:00:00Z")

        assertTrue(result.errors.any { it.contains("exceeded 3 pages") }, result.errors.toString())
        assertTrue(result.requestMetadata.pagination.truncated as boolean)
        assertNull(result.outputText)
    }

    @Test
    void defaultClientRejectsLoopbackAndPlaintextUrls() {
        OmsRestSourceSupport.resetHttpClient()

        Map loopback = OmsRestSourceSupport.extractOrders(baseConfig([
                baseUrl: "https://127.0.0.1",
        ]), "2026-05-01T00:00:00Z", "2026-05-01T01:00:00Z")
        assertTrue(loopback.errors.any { it.contains("blocked by outbound policy") }, loopback.errors.toString())
        assertNull(loopback.outputText)

        Map plaintext = OmsRestSourceSupport.extractOrders(baseConfig([
                baseUrl: "http://oms.example.com",
        ]), "2026-05-01T00:00:00Z", "2026-05-01T01:00:00Z")
        assertTrue(plaintext.errors.any { it.contains("blocked by outbound policy") }, plaintext.errors.toString())
        assertNull(plaintext.outputText)
    }

    @Test
    void stripsUserInfoCredentialsFromRequestUrl() {
        List<String> requestedUrls = []
        OmsRestSourceSupport.setHttpClient { Map request ->
            requestedUrls.add(request.url as String)
            return [statusCode: 200, body: '{"orders":[{"orderId":"O100","orderTypeId":"SALES_ORDER"}]}']
        }

        Map result = OmsRestSourceSupport.extractOrders(baseConfig([
                baseUrl: "https://oms-user:oms-pass@dev-maarg.hotwax.io",
        ]), "2026-05-01T00:00:00Z", "2026-05-01T01:00:00Z")

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertEquals(1, requestedUrls.size())
        assertFalse(requestedUrls[0].contains("oms-user"))
        assertFalse(requestedUrls[0].contains("oms-pass"))
        assertFalse(result.requestMetadata.toString().contains("oms-pass"))
    }

    @Test
    void buildsExchangeManifestEntriesForExcludedExchangeOrders() {
        Map topLevelAssoc = [orderId: "M750653", externalId: "6941645013123", orderName: "EXC-#GOR196990495-1",
                orderTypeId: "SALES_ORDER", statusId: "ORDER_COMPLETED", grandTotal: 50.0, orderDate: 1785260782199L,
                itemAssocs: [[orderItemAssocTypeId: "EXCHANGE", toOrderId: "M686331", orderItemSeqId: "01", toOrderItemSeqId: "_NA_"]]]
        Map nestedAssocOnly = [orderId: "M900001", externalId: "7000000000001", orderName: "EXC-#GOR2-1",
                orderTypeId: "SALES_ORDER", statusId: "ORDER_CANCELLED", grandTotal: 10.0, orderDate: 1785260782199L,
                shipGroups: [[items: [[assocs: [[orderItemAssocTypeId: "exchange", toOrderId: "M900000"]]]]]]]
        Map keptOrder = [orderId: "M686331", externalId: "6941645013123", orderTypeId: "SALES_ORDER"]

        Map result = OmsRestSourceSupport.filterComparableOrderRecords([topLevelAssoc, nestedAssocOnly, keptOrder])

        assertEquals(["M686331"], ((List) result.records).collect { it.orderId })
        assertEquals(2, result.excludedExchangeOrderCount)
        List manifest = (List) result.excludedExchangeOrders
        assertEquals(2, manifest.size())
        assertEquals("M750653", manifest[0].omsOrderId)
        assertEquals("6941645013123", manifest[0].externalId)
        assertEquals("EXC-#GOR196990495-1", manifest[0].orderName)
        assertEquals("M686331", manifest[0].toOrderId)          // from top-level itemAssocs
        assertEquals("ORDER_COMPLETED", manifest[0].statusId)
        assertEquals(50.0, manifest[0].grandTotal)
        assertEquals("M900000", manifest[1].toOrderId)          // from nested shipGroups.items.assocs, case-insensitive type
    }

    @Test
    void exchangeManifestFlowsThroughExtractionWithCapAndTruncation() {
        List orders = []
        501.times { int i ->
            orders.add([orderId: "MEX${i}".toString(), externalId: "EXT${i}".toString(), orderTypeId: "SALES_ORDER",
                    itemAssocs: [[orderItemAssocTypeId: "EXCHANGE", toOrderId: "MOR${i}".toString()]]])
        }
        orders.add([orderId: "MKEEP", externalId: "EKEEP", orderTypeId: "SALES_ORDER"])
        OmsRestSourceSupport.setHttpClient { Map ignored ->
            [statusCode: 200, body: groovy.json.JsonOutput.toJson([orders: orders])]
        }

        Map result = OmsRestSourceSupport.extractOrders(baseConfig(), "2026-05-01T00:00:00Z", "2026-05-01T01:00:00Z")

        assertEquals(1, result.recordCount)
        assertEquals(501, result.requestMetadata.filters.excludedExchangeOrderCount)
        assertEquals(500, ((List) result.exchangeManifest).size())
        assertTrue(result.exchangeManifestTruncated as boolean)
        assertTrue(result.requestMetadata.filters.exchangeManifestTruncated as boolean)
    }

    @Test
    void exchangeManifestFileNameSwapsJsonSuffixCaseInsensitively() {
        assertEquals("oms-orders-1-2.exchange-manifest.json", OmsRestSourceSupport.exchangeManifestFileName("oms-orders-1-2.json"))
        assertEquals("weird.exchange-manifest.json", OmsRestSourceSupport.exchangeManifestFileName("weird.JSON"))
        assertEquals("noext.exchange-manifest.json", OmsRestSourceSupport.exchangeManifestFileName("noext"))
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

    private static Map salesOrder(String orderId, String channel) {
        return [orderId: orderId, orderTypeId: "SALES_ORDER", salesChannelEnumId: channel, grandTotal: 10]
    }

    private static List<Map<String, Object>> posChannelRule() {
        return darpan.reconciliation.source.SourceFilterSupport.parseRules([[
                sequenceNum    : 1,
                fieldExpression: "salesChannelEnumId",
                filterValues   : "POS_SALES_CHANNEL",
        ]])
    }

    @Test
    void noExclusionRulesLeavesFilteringExactlyAsBefore() {
        List records = [
                salesOrder("1", "WEB_SALES_CHANNEL"),
                salesOrder("2", "POS_SALES_CHANNEL"),
                [orderId: "3", orderTypeId: "PURCHASE_ORDER"],
        ]

        Map result = OmsRestSourceSupport.filterComparableOrderRecords(records)

        assertEquals(2, (result.records as List).size())
        assertEquals(1, result.excludedNonSalesOrderCount)
        assertEquals(0, result.excludedExchangeOrderCount)
        assertEquals([:], result.excludedByRuleCounts)
    }

    @Test
    void configuredRuleExcludesMatchingRecordsAndCountsThemByRule() {
        List records = [
                salesOrder("1", "WEB_SALES_CHANNEL"),
                salesOrder("2", "POS_SALES_CHANNEL"),
                salesOrder("3", "pos_sales_channel"),
        ]

        Map result = OmsRestSourceSupport.filterComparableOrderRecords(records, posChannelRule())

        assertEquals(1, (result.records as List).size())
        assertEquals("1", (result.records as List)[0].orderId)
        assertEquals([(1): 2], result.excludedByRuleCounts)
    }

    @Test
    void builtInExclusionsKeepPriorityOverConfiguredOnes() {
        // A non-sales order that also matches a configured rule must stay attributed to the built-in
        // counter, so existing excludedNonSalesOrderCount values never shift when a filter is added.
        List records = [[orderId: "1", orderTypeId: "PURCHASE_ORDER", salesChannelEnumId: "POS_SALES_CHANNEL"]]

        Map result = OmsRestSourceSupport.filterComparableOrderRecords(records, posChannelRule())

        assertEquals(1, result.excludedNonSalesOrderCount)
        assertEquals([:], result.excludedByRuleCounts)
        assertEquals(0, (result.records as List).size())
    }

    @Test
    void exchangeExclusionKeepsPriorityOverConfiguredRules() {
        List records = [[
                orderId           : "1",
                orderTypeId       : "SALES_ORDER",
                salesChannelEnumId: "POS_SALES_CHANNEL",
                items             : [[orderItemAssocTypeId: "EXCHANGE", toOrderId: "9"]],
        ]]

        Map result = OmsRestSourceSupport.filterComparableOrderRecords(records, posChannelRule())

        assertEquals(1, result.excludedExchangeOrderCount)
        assertEquals([:], result.excludedByRuleCounts)
        // Must actually fall into the exchange branch, not merely skip the rule branch: the record
        // is dropped from the kept set and shows up on the exchange manifest, not silently kept.
        assertEquals(0, (result.records as List).size())
        List excludedExchangeOrders = result.excludedExchangeOrders as List
        assertEquals(1, excludedExchangeOrders.size())
        assertEquals("1", (excludedExchangeOrders[0] as Map).omsOrderId)
        assertEquals("9", (excludedExchangeOrders[0] as Map).toOrderId)
    }

    @Test
    void recordMissingTheFilteredFieldIsKept() {
        List records = [[orderId: "1", orderTypeId: "SALES_ORDER"]]

        Map result = OmsRestSourceSupport.filterComparableOrderRecords(records, posChannelRule())

        assertEquals(1, (result.records as List).size())
        assertEquals([:], result.excludedByRuleCounts)
    }

    @Test
    void configuredExclusionsAppearInRequestMetadataWithCounts() {
        Map<String, Object> filters = OmsRestSourceSupport.buildFilterMetadata(
                4, 2, false, posChannelRule(), [(1): 7])

        assertEquals("SALES_ORDER", filters.requiredOrderTypeId)
        assertEquals(4, filters.excludedNonSalesOrderCount)
        assertEquals(2, filters.excludedExchangeOrderCount)

        List configured = filters.configuredExclusions as List
        assertEquals(1, configured.size())
        assertEquals(1, configured[0].sequenceNum)
        assertEquals("salesChannelEnumId", configured[0].fieldExpression)
        assertEquals("EXCLUDE_IN", configured[0].operator)
        assertEquals(["POS_SALES_CHANNEL"], configured[0].values)
        assertEquals(7, configured[0].excludedCount)
    }

    @Test
    void metadataOmitsConfiguredExclusionsWhenNoRulesAreSet() {
        Map<String, Object> filters = OmsRestSourceSupport.buildFilterMetadata(1, 0, false, [], [:])

        assertFalse(filters.containsKey("configuredExclusions"))
    }

    @Test
    void ruleThatMatchedNothingStillReportsAZeroCount() {
        // An operator who configured an exclusion needs to see it ran and matched nothing, rather
        // than see the rule vanish from metadata and wonder whether it was applied at all.
        Map<String, Object> filters = OmsRestSourceSupport.buildFilterMetadata(0, 0, false, posChannelRule(), [:])

        assertEquals(0, (filters.configuredExclusions as List)[0].excludedCount)
    }

    @Test
    void configuredExclusionsApplyToPagesFetchedFromTheConcurrentPool() {
        // filterComparableOrderRecords and buildFilterMetadata are proven directly above, but nothing
        // else exercises the five hops that carry excludeRules from extractOrdersToFile down into
        // prepareOrdersPage. Page size 2 forces pages 0+1 to be fetched synchronously as pagination
        // probes, then pages 2+ stream through the ExecutorService (default ordersFetchConcurrency=2)
        // — the rule match sits on page 2 specifically so this guards that submit() call, not just
        // the two synchronous probes.
        OmsRestSourceSupport.setHttpClient { Map request ->
            int pageIndex = queryInt(request.url as String, "pageIndex")
            List<List> pages = [
                    [salesOrder("O1", "WEB_SALES_CHANNEL"), salesOrder("O2", "WEB_SALES_CHANNEL")],
                    [salesOrder("O3", "WEB_SALES_CHANNEL"), salesOrder("O4", "WEB_SALES_CHANNEL")],
                    [salesOrder("O5", "WEB_SALES_CHANNEL"), salesOrder("O6", "POS_SALES_CHANNEL")],
            ]
            List orders = pageIndex < pages.size() ? pages[pageIndex] : []
            return [statusCode: 200, body: JsonOutput.toJson([orders: orders])]
        }

        File target = File.createTempFile("oms-orders-exclusion-pool-", ".json")
        try {
            Map result = OmsRestSourceSupport.extractOrdersToFile(
                    baseConfig([ordersPageSize: 2, ordersFetchConcurrency: 2]),
                    "2026-05-01T00:00:00Z", "2026-05-01T01:00:00Z", target, null, null,
                    posChannelRule())

            assertTrue((result.errors as List).isEmpty(), result.errors.toString())
            assertEquals(5, result.recordCount)

            String written = target.getText("UTF-8")
            assertFalse(written.contains("O6"), "excluded record O6 leaked into the extract file: ${written}")

            List configured = result.requestMetadata.filters.configuredExclusions as List
            assertEquals(1, (configured[0] as Map).excludedCount)
        } finally {
            target.delete()
        }
    }

    @Test
    void malformedExcludeFilterFailsBeforeAnyHttpRequestIsIssued() {
        // Rules are parsed once, before the fetch pool starts (see extractOrdersInternal): a bad
        // rule must surface as one clean pre-flight error, not a thrown exception and not N
        // mid-window failures from worker threads that never should have started fetching.
        boolean httpCalled = false
        OmsRestSourceSupport.setHttpClient { Map ignored ->
            httpCalled = true
            [statusCode: 200, body: '{"orders":[]}']
        }

        Map result = OmsRestSourceSupport.extractOrders(baseConfig(), "2026-05-01T00:00:00Z", "2026-05-01T01:00:00Z",
                null, null, [[fieldExpression: "salesChannelEnumId"]])

        assertFalse(httpCalled, "a malformed exclusion rule must fail before any HTTP request is issued")
        assertTrue((result.errors as List).any { it.toString().contains("has no values to exclude") },
                result.errors.toString())
        assertEquals(0, result.recordCount)
    }
}
