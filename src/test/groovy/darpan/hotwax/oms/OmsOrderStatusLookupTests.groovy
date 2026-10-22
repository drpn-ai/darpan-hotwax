package darpan.hotwax.oms

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-111: what HotWax says of each order NetSuite never shipped or billed, by OMS order id.
 *
 * Fixtures are RAILS orders probed live 2026-10-22: 145059 completed (SO8432201, still Pending Fulfillment in
 * NetSuite), 144314 cancelled (SO8431481, Closed in NetSuite), 155939 approved. GraphQL is injected; nothing here
 * opens a socket.
 */
class OmsOrderStatusLookupTests {

    private static final Map CONFIG = [omsRestSourceConfigId: "TEST_CFG", baseUrl: "https://oms.example.com",
                                       apiKey: "test-key", isActive: "Y"]

    private static final List<Map> ORDERS = [
            [orderId: "145059", statusId: "ORDER_COMPLETED", orderDate: "2026-10-02T18:57:57Z"],
            [orderId: "144314", statusId: "ORDER_CANCELLED", orderDate: "2026-10-01T17:52:33Z"],
            [orderId: "155939", statusId: "ORDER_APPROVED", orderDate: "2026-10-18T09:12:00Z"],
    ]

    private List<Map> requests = []

    @AfterEach
    void reset() {
        OmsOrderStatusLookupSupport.resetClientFactory()
    }

    private void omsAnswers(Closure<List<Map>> answer = { Set<String> ids -> ORDERS.findAll { ids.contains(it.orderId) } }) {
        OmsOrderStatusLookupSupport.setClientFactory { Map config ->
            OmsGqlClient c = new OmsGqlClient(config, { String url, Map headers, String body ->
                Map payload = (Map) new JsonSlurper().parseText(body)
                requests.add(payload)
                String q = ((Map) payload.variables).q as String
                Set<String> ids = q.substring(q.indexOf(':') + 1).split(",") as Set
                return [statusCode: 200, body: JsonOutput.toJson([data: [orders: [
                        edges: answer.call(ids).collect { [node: it] }, pageInfo: [hasNextPage: false]]], errors: []])]
            })
            c.sleeper = { long ms -> }
            return c
        }
    }

    @Test
    void eachOrderComesBackWithItsStatusAndAnUnknownIdIsMissing() {
        omsAnswers()

        Map result = OmsOrderStatusLookupSupport.lookupOrderStatuses(CONFIG, ["145059", "144314", "155939", "999999"])

        assertTrue(result.ok as Boolean)
        assertEquals(["145059": "ORDER_COMPLETED", "144314": "ORDER_CANCELLED", "155939": "ORDER_APPROVED"], result.statusByOrderId)
        assertEquals(["999999"], result.missingIds, "an HC Order Id OMS does not have is reported, never guessed")
        assertTrue(((Map) requests[0].variables).q.toString().startsWith("orderId:"))
        assertTrue(requests[0].query.toString().contains("statusId"))
    }

    @Test
    void ordersAnIgnoredFilterAnswersWithAreDropped() {
        // An ignored filter answers HTTP 200 with arbitrary orders; only ids that were asked for count.
        omsAnswers({ Set<String> ids -> ORDERS })

        Map result = OmsOrderStatusLookupSupport.lookupOrderStatuses(CONFIG, ["145059"])

        assertEquals(["145059"], ((Map) result.statusByOrderId).keySet() as List)
        assertEquals([], result.missingIds)
    }

    @Test
    void idsAreAskedInChunks() {
        omsAnswers({ Set<String> ids -> [] })
        List<String> ids = (1..(OmsOrderStatusLookupSupport.ID_CHUNK * 2 + 1)).collect { "${100000 + it}".toString() }

        Map result = OmsOrderStatusLookupSupport.lookupOrderStatuses(CONFIG, ids)

        assertEquals(3, requests.size())
        assertEquals(ids.size(), (result.missingIds as List).size())
    }

    @Test
    void anIdThatWouldCorruptTheSearchIsRefusedAndAFailedCallClassifiesNothing() {
        Map refused = OmsOrderStatusLookupSupport.lookupOrderStatuses(CONFIG, ["145059,144314"])
        assertFalse(refused.ok as Boolean)

        OmsOrderStatusLookupSupport.setClientFactory { Map config ->
            OmsGqlClient c = new OmsGqlClient(config, { String url, Map headers, String body -> [statusCode: 500, body: "boom"] })
            c.sleeper = { long ms -> }
            return c
        }
        Map failed = OmsOrderStatusLookupSupport.lookupOrderStatuses(CONFIG, ["145059"])
        assertFalse(failed.ok as Boolean)
        assertEquals([:], failed.statusByOrderId)
        assertEquals([], failed.missingIds, "a failed lookup never calls an order missing")
    }
}
