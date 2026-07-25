package darpan.hotwax.oms

import groovy.json.JsonOutput
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * Transport-level behavior of the OMS extract client: gzip response decoding and the
 * bounded concurrent page-fetch window (in-order consumption regardless of completion order).
 */
class OmsRestTransportTests {

    @AfterEach
    void resetClient() {
        OmsRestSourceSupport.resetHttpClient()
    }

    private static byte[] gzipBytes(String text) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream()
        new GZIPOutputStream(buffer).withStream { it.write(text.getBytes(StandardCharsets.UTF_8)) }
        return buffer.toByteArray()
    }

    @Test
    void decodesGzipResponseBodies() {
        String payload = '{"orders":[{"orderId":"O1"}]}'
        InputStream gzipped = new ByteArrayInputStream(gzipBytes(payload))
        assertEquals(payload, OmsRestSourceSupport.readResponseBody(gzipped, "gzip"))
    }

    @Test
    void passesPlainResponseBodiesThroughUnchanged() {
        String payload = '{"orders":[]}'
        InputStream plain = new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8))
        assertEquals(payload, OmsRestSourceSupport.readResponseBody(plain, null))
        assertEquals("", OmsRestSourceSupport.readResponseBody(null, "gzip"))
    }

    @Test
    void fetchesPagesConcurrentlyAfterStrategyCommitButWritesInPageOrder() {
        // Page 2's response is held back until page 3 has been REQUESTED — only possible
        // when the window fetches >1 page in flight. The output must still be in page order.
        CountDownLatch page3Requested = new CountDownLatch(1)
        ConcurrentLinkedQueue<Integer> requestedOrder = new ConcurrentLinkedQueue<>()
        OmsRestSourceSupport.setHttpClient { Map request ->
            String url = request.url as String
            int pageIndex = Integer.parseInt((url =~ /pageIndex=(\d+)/)[0][1] as String)
            requestedOrder.add(pageIndex)
            List<List> pages = [
                    [[externalId: "O1", orderTypeId: "SALES_ORDER"], [externalId: "O2", orderTypeId: "SALES_ORDER"]],
                    [[externalId: "O3", orderTypeId: "SALES_ORDER"], [externalId: "O4", orderTypeId: "SALES_ORDER"]],
                    [[externalId: "O5", orderTypeId: "SALES_ORDER"], [externalId: "O6", orderTypeId: "SALES_ORDER"]],
                    [[externalId: "O7", orderTypeId: "SALES_ORDER"]],
            ]
            if (pageIndex == 2) {
                assertTrue(page3Requested.await(10, TimeUnit.SECONDS),
                        "page 3 was never requested while page 2 was in flight — pages are not fetched concurrently")
            }
            if (pageIndex == 3) page3Requested.countDown()
            List orders = pageIndex < pages.size() ? pages[pageIndex] : []
            return [statusCode: 200, body: JsonOutput.toJson([orders: orders])]
        }

        Map result = OmsRestSourceSupport.extractOrders([
                omsRestSourceConfigId : "KREWE_OMS",
                companyUserGroupId    : "KREWE",
                baseUrl               : "https://dev-maarg.hotwax.io",
                authType              : "NONE",
                ordersPageSize        : 2,
                ordersFetchConcurrency: 3,
        ], "2026-05-01T00:00:00Z", "2026-05-01T01:00:00Z")

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertEquals(7, result.recordCount)
        assertEquals(["O1", "O2", "O3", "O4", "O5", "O6", "O7"], ((List) result.records).collect { it.externalId })
        assertEquals(3, result.requestMetadata.pagination.fetchConcurrency)
    }

    @Test
    void concurrencyOfOneKeepsStrictSequentialFetching() {
        List<Integer> requestedOrder = Collections.synchronizedList([])
        OmsRestSourceSupport.setHttpClient { Map request ->
            String url = request.url as String
            int pageIndex = Integer.parseInt((url =~ /pageIndex=(\d+)/)[0][1] as String)
            requestedOrder.add(pageIndex)
            List orders = pageIndex < 2 ?
                    [[externalId: "A${pageIndex}", orderTypeId: "SALES_ORDER"], [externalId: "B${pageIndex}", orderTypeId: "SALES_ORDER"]] :
                    pageIndex == 2 ? [[externalId: "A2", orderTypeId: "SALES_ORDER"]] : []
            return [statusCode: 200, body: JsonOutput.toJson([orders: orders])]
        }

        Map result = OmsRestSourceSupport.extractOrders([
                omsRestSourceConfigId : "KREWE_OMS",
                companyUserGroupId    : "KREWE",
                baseUrl               : "https://dev-maarg.hotwax.io",
                authType              : "NONE",
                ordersPageSize        : 2,
                ordersFetchConcurrency: 1,
        ], "2026-05-01T00:00:00Z", "2026-05-01T01:00:00Z")

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertEquals(5, result.recordCount)
        assertEquals([0, 1, 2], requestedOrder)
    }

    @Test
    void retriesTimedOutPageOnceWithoutCompression() {
        // A gzip-buffering proxy can hold the entire response until page generation completes,
        // pushing first-byte past the read timeout. The client must retry the page once with
        // compression disabled (streamed responses trickle bytes and keep the read alive).
        List<Map> requests = Collections.synchronizedList([])
        OmsRestSourceSupport.setHttpClient { Map request ->
            requests.add(request)
            if (requests.size() == 1) throw new SocketTimeoutException("Read timed out")
            return [statusCode: 200, body: '{"orders":[{"orderId":"O1","orderTypeId":"SALES_ORDER"}]}']
        }

        Map result = OmsRestSourceSupport.extractOrders([
                omsRestSourceConfigId: "KREWE_OMS",
                companyUserGroupId   : "KREWE",
                baseUrl              : "https://dev-maarg.hotwax.io",
                authType             : "NONE",
        ], "2026-05-01T00:00:00Z", "2026-05-01T01:00:00Z")

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertEquals(1, result.recordCount)
        assertEquals(2, requests.size())
        assertTrue(requests[0].acceptGzip != false, "first attempt should allow gzip")
        assertEquals(false, requests[1].acceptGzip)
        assertTrue(result.warnings.any { it.contains("timed out") && it.contains("compression") }, result.warnings.toString())
    }

    @Test
    void secondTimeoutStillFailsThePage() {
        OmsRestSourceSupport.setHttpClient { Map ignored -> throw new SocketTimeoutException("Read timed out") }

        Map result = OmsRestSourceSupport.extractOrders([
                omsRestSourceConfigId: "KREWE_OMS",
                companyUserGroupId   : "KREWE",
                baseUrl              : "https://dev-maarg.hotwax.io",
                authType             : "NONE",
        ], "2026-05-01T00:00:00Z", "2026-05-01T01:00:00Z")

        assertTrue(result.errors.any { it.contains("Read timed out") }, result.errors.toString())
        assertEquals(0, result.recordCount)
    }

    @Test
    void reportsCumulativeRawCountAfterEachConsumedPage() {
        OmsRestSourceSupport.setHttpClient { Map request ->
            int pageIndex = Integer.parseInt((request.url =~ /pageIndex=(\d+)/)[0][1] as String)
            List<List> pages = [
                    [[externalId: "O1", orderTypeId: "SALES_ORDER"], [externalId: "O2", orderTypeId: "SALES_ORDER"]],
                    [[externalId: "O3", orderTypeId: "SALES_ORDER"], [externalId: "O4", orderTypeId: "SALES_ORDER"]],
                    [[externalId: "O5", orderTypeId: "SALES_ORDER"]],
            ]
            List orders = pageIndex < pages.size() ? pages[pageIndex] : []
            return [statusCode: 200, body: JsonOutput.toJson([orders: orders])]
        }

        List<Integer> reported = Collections.synchronizedList([])
        Map result = OmsRestSourceSupport.extractOrders([
                omsRestSourceConfigId : "KREWE_OMS",
                companyUserGroupId    : "KREWE",
                baseUrl               : "https://dev-maarg.hotwax.io",
                authType              : "NONE",
                ordersPageSize        : 2,
                ordersFetchConcurrency: 1,
        ], "2026-05-01T00:00:00Z", "2026-05-01T01:00:00Z", null, { Object cumulativeRawCount ->
            reported.add(cumulativeRawCount as Integer)
        })

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertEquals([2, 4, 5], reported)
    }

    @Test
    void progressListenerFailuresNeverFailTheExtraction() {
        OmsRestSourceSupport.setHttpClient { Map ignored ->
            [statusCode: 200, body: '{"orders":[{"externalId":"O1","orderTypeId":"SALES_ORDER"}]}']
        }

        Map result = OmsRestSourceSupport.extractOrders([
                omsRestSourceConfigId: "KREWE_OMS",
                companyUserGroupId   : "KREWE",
                baseUrl              : "https://dev-maarg.hotwax.io",
                authType             : "NONE",
        ], "2026-05-01T00:00:00Z", "2026-05-01T01:00:00Z", null, { Object ignored ->
            throw new IllegalStateException("listener blew up")
        })

        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertEquals(1, result.recordCount)
    }

    @Test
    void midWindowFailureStillFailsTheExtractionUnderConcurrency() {
        OmsRestSourceSupport.setHttpClient { Map request ->
            String url = request.url as String
            int pageIndex = Integer.parseInt((url =~ /pageIndex=(\d+)/)[0][1] as String)
            if (pageIndex == 2) return [statusCode: 500, body: '{"error":"boom"}']
            List orders = [[externalId: "P${pageIndex}A", orderTypeId: "SALES_ORDER"], [externalId: "P${pageIndex}B", orderTypeId: "SALES_ORDER"]]
            return [statusCode: 200, body: JsonOutput.toJson([orders: orders])]
        }

        Map result = OmsRestSourceSupport.extractOrders([
                omsRestSourceConfigId : "KREWE_OMS",
                companyUserGroupId    : "KREWE",
                baseUrl               : "https://dev-maarg.hotwax.io",
                authType              : "NONE",
                ordersPageSize        : 2,
                maxOrdersPageCount    : 6,
                ordersFetchConcurrency: 4,
        ], "2026-05-01T00:00:00Z", "2026-05-01T01:00:00Z")

        assertTrue(result.errors.any { it.contains("status 500") }, result.errors.toString())
        assertEquals(0, result.recordCount)
    }
}
