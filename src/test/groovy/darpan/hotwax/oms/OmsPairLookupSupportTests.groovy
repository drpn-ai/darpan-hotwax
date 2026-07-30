package darpan.hotwax.oms

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import static org.junit.jupiter.api.Assertions.*

class OmsPairLookupSupportTests {
    @AfterEach void reset() { OmsRestSourceSupport.setHttpClient(null) }

    private static Map baseConfig() {
        [omsRestSourceConfigId: "TEST_OMS", baseUrl: "https://oms.example.com", ordersPath: "/rest/s1/oms/orders",
         authType: "NONE", timeZone: "UTC"]
    }

    @Test
    void returnsPairSummariesGroupedByExternalId() {
        List requestedUrls = []
        OmsRestSourceSupport.setHttpClient { Map request ->
            requestedUrls.add(request.url as String)
            [statusCode: 200, body: groovy.json.JsonOutput.toJson([orders: [
                [orderId: "M686331", externalId: "6941645013123", orderName: "#GOR196990495",
                 orderTypeId: "SALES_ORDER", statusId: "ORDER_COMPLETED", grandTotal: 186.59, orderDate: 1784227520000L],
                [orderId: "M750653", externalId: "6941645013123", orderName: "EXC-#GOR196990495-1",
                 orderTypeId: "SALES_ORDER", statusId: "ORDER_COMPLETED", grandTotal: 50.0, orderDate: 1785260782199L,
                 itemAssocs: [[orderItemAssocTypeId: "EXCHANGE", toOrderId: "M686331"]]],
            ]])]
        }

        Map result = OmsRestSourceSupport.lookupOrdersByExternalId(baseConfig(), ["6941645013123"], 1770000000000L, 1790000000000L)

        assertTrue(result.ok as boolean, result.errors.toString())
        assertTrue(requestedUrls[0].contains("externalId=6941645013123"))
        assertTrue(requestedUrls[0].contains("orderDate_from=1770000000000"))
        List pair = (List) result.ordersByExternalId["6941645013123"]
        assertEquals(2, pair.size())
        assertEquals([false, true], pair.collect { it.hasExchangeAssoc })
        assertEquals(186.59, pair[0].grandTotal)
    }

    @Test
    void anyHttpFailureMakesTheWholeLookupNotOk() {
        OmsRestSourceSupport.setHttpClient { Map ignored -> [statusCode: 500, body: "boom"] }
        Map result = OmsRestSourceSupport.lookupOrdersByExternalId(baseConfig(), ["X1"], 1L, 2L)
        assertFalse(result.ok as boolean)
        assertTrue(result.errors.first().toString().contains("HTTP 500"))
    }

    @Test
    void rejectsRequestsAboveTheIdCap() {
        Map result = OmsRestSourceSupport.lookupOrdersByExternalId(baseConfig(), (1..101).collect { "E$it".toString() }, 1L, 2L)
        assertFalse(result.ok as boolean)
        assertTrue(result.errors.first().toString().contains("cap"))
    }

    @Test
    void nonEchoingResponseFailsTheLookup() {
        // Finding: lookupOrdersByExternalId stored whatever records the OMS returned under the
        // requested externalId key without checking they actually carry that externalId. A
        // tenant-configured endpoint that ignores the externalId= query param would return arbitrary
        // window orders, producing false "original present" evidence and garbage pair sums. If every
        // returned record carries a DIFFERENT externalId than requested, the filter is evidently
        // unsupported and the whole lookup must fail rather than silently trust the response.
        OmsRestSourceSupport.setHttpClient { Map request ->
            [statusCode: 200, body: groovy.json.JsonOutput.toJson([orders: [
                [orderId: "M1", externalId: "WRONG-ID-1", orderName: "#A1",
                 orderTypeId: "SALES_ORDER", statusId: "ORDER_COMPLETED", grandTotal: 10.0, orderDate: 1L],
                [orderId: "M2", externalId: "WRONG-ID-2", orderName: "#A2",
                 orderTypeId: "SALES_ORDER", statusId: "ORDER_COMPLETED", grandTotal: 20.0, orderDate: 2L],
            ]])]
        }

        Map result = OmsRestSourceSupport.lookupOrdersByExternalId(baseConfig(), ["6941645013123"], 1770000000000L, 1790000000000L)

        assertFalse(result.ok as boolean)
        assertTrue(result.errors.first().toString().contains("filter"))
        assertTrue((result.ordersByExternalId as Map).isEmpty())
    }

    @Test
    void mixedEchoResponseKeepsOnlyTheMatchingRecordAndDropsTheStray() {
        // Mixed responses (one record echoing the requested externalId, one from window noise that
        // does not) mean the filter IS honored server-side — keep the matching pair member, drop the
        // stray, and the lookup stays ok:true.
        OmsRestSourceSupport.setHttpClient { Map request ->
            [statusCode: 200, body: groovy.json.JsonOutput.toJson([orders: [
                [orderId: "M686331", externalId: "6941645013123", orderName: "#GOR196990495",
                 orderTypeId: "SALES_ORDER", statusId: "ORDER_COMPLETED", grandTotal: 186.59, orderDate: 1784227520000L],
                [orderId: "M999999", externalId: "SOME-OTHER-ORDER", orderName: "#WINDOW-NOISE",
                 orderTypeId: "SALES_ORDER", statusId: "ORDER_COMPLETED", grandTotal: 999.0, orderDate: 1784227520000L],
            ]])]
        }

        Map result = OmsRestSourceSupport.lookupOrdersByExternalId(baseConfig(), ["6941645013123"], 1770000000000L, 1790000000000L)

        assertTrue(result.ok as boolean, result.errors.toString())
        List pair = (List) result.ordersByExternalId["6941645013123"]
        assertEquals(1, pair.size())   // the stray "SOME-OTHER-ORDER" record was dropped
        assertEquals("M686331", pair[0].omsOrderId)
        assertEquals("6941645013123", pair[0].externalId)
    }
}
