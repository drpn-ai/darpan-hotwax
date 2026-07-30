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
}
