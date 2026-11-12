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

    // DAR-BE-190. gorjana 2026-11-03 (live): M1160420 shipped from NetSuite (IF7451658, line 01) and item 01 is still
    // ITEM_APPROVED in HotWax; GR1638399's shipped line 00101 is ITEM_COMPLETED while 00102 waits, so the ORDER reads
    // ORDER_APPROVED although the fulfillment was imported. Only the item's status says which.
    private static final List<Map> ITEMS = [
            [orderId: "M1160420", orderItemSeqId: "01", statusId: "ITEM_APPROVED"],
            [orderId: "GR1638399", orderItemSeqId: "00101", statusId: "ITEM_COMPLETED"],
            [orderId: "GR1638399", orderItemSeqId: "00102", statusId: "ITEM_APPROVED"],
            [orderId: "NOT_ASKED", orderItemSeqId: "01", statusId: "ITEM_COMPLETED"],
    ]

    private void omsItemsAnswer(int pageSize = 2) {
        OmsOrderStatusLookupSupport.setClientFactory { Map config ->
            OmsGqlClient c = new OmsGqlClient(config, { String url, Map headers, String body ->
                Map payload = (Map) new JsonSlurper().parseText(body)
                requests.add(payload)
                Map vars = (Map) payload.variables
                String q = vars.q as String
                Set<String> ids = q.substring(q.indexOf(':') + 1).split(",") as Set
                // An ignored filter would answer with every item; NOT_ASKED stands for that. Paged by cursor.
                List<Map> all = ITEMS.findAll { ids.contains(it.orderId) || it.orderId == "NOT_ASKED" }
                int from = vars.after ? (vars.after as int) : 0
                List<Map> page = all.drop(from).take(pageSize)
                boolean more = from + pageSize < all.size()
                return [statusCode: 200, body: JsonOutput.toJson([data: [orderItems: [
                        edges: page.collect { [node: it] }, pageInfo: [hasNextPage: more, endCursor: more ? "${from + pageSize}" : null]]],
                        errors: []])]
            })
            c.sleeper = { long ms -> }
            return c
        }
    }

    @Test
    void eachItemComesBackWithItsStatusKeyedByOrderAndSeqAndAnOrderWithNoItemsIsMissing() {
        omsItemsAnswer()

        Map result = OmsOrderStatusLookupSupport.lookupOrderItemStatuses(CONFIG, ["M1160420", "GR1638399", "M999999"])

        assertTrue(result.ok as Boolean)
        assertEquals(["M1160420|01": "ITEM_APPROVED", "GR1638399|00101": "ITEM_COMPLETED", "GR1638399|00102": "ITEM_APPROVED"],
                result.itemStatusByKey, "every page is read, and an item of an order not asked for is dropped")
        assertEquals(["M999999"], result.missingIds, "an order HotWax returns no item for is reported, never guessed")
        assertTrue(requests[0].query.toString().contains("orderItems"))
        assertTrue(requests[0].query.toString().contains("orderItemSeqId"))
        assertTrue(((Map) requests[0].variables).q.toString().startsWith("orderId:"))
    }

    @Test
    void itemsAreAskedInChunksOfOrdersAndAFailedCallClassifiesNothing() {
        omsItemsAnswer(100)
        List<String> ids = (1..(OmsOrderStatusLookupSupport.ITEMS_ID_CHUNK + 1)).collect { "M${100000 + it}".toString() }
        Map result = OmsOrderStatusLookupSupport.lookupOrderItemStatuses(CONFIG, ids)
        assertEquals(2, requests.size())
        assertEquals(ids.size(), (result.missingIds as List).size())

        OmsOrderStatusLookupSupport.setClientFactory { Map config ->
            OmsGqlClient c = new OmsGqlClient(config, { String url, Map headers, String body -> [statusCode: 500, body: "boom"] })
            c.sleeper = { long ms -> }
            return c
        }
        Map failed = OmsOrderStatusLookupSupport.lookupOrderItemStatuses(CONFIG, ["M1160420"])
        assertFalse(failed.ok as Boolean)
        assertEquals([:], failed.itemStatusByKey)
        assertEquals([], failed.missingIds)
        assertFalse(OmsOrderStatusLookupSupport.lookupOrderItemStatuses(CONFIG, ["M1,M2"]).ok as Boolean)
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

    // DAR-BE-245. RAILS #8777CAN (150738), live 2026-11-12: NetSuite shipped it in one fulfillment (IF387146 from
    // location 77) and HotWax holds its five completed items in TWO ship groups at CAN WH (100102, externalId 77).
    @Test
    void itemsCarryTheirShipGroupAndShipGroupsComeBackWithWhereTheyShipFrom() {
        List<Map> items = [[orderId: "150738", orderItemSeqId: "01", statusId: "ITEM_COMPLETED", shipGroupSeqId: "00004"],
                           [orderId: "150738", orderItemSeqId: "02", statusId: "ITEM_COMPLETED", shipGroupSeqId: "00003"]]
        List<Map> orders = [[orderId: "150738", shipGroups: [edges: [
                [node: [shipGroupSeqId: "00003", facilityId: "100102", facility: [externalId: "77"]]],
                [node: [shipGroupSeqId: "00004", facilityId: "100102", facility: [externalId: "77"]]],
                [node: [shipGroupSeqId: "00002", facilityId: "_NA_", facility: null]]]]],
                            [orderId: "NOT_ASKED", shipGroups: [edges: [[node: [shipGroupSeqId: "00001", facilityId: "100000"]]]]]]
        OmsOrderStatusLookupSupport.setClientFactory { Map config ->
            OmsGqlClient c = new OmsGqlClient(config, { String url, Map headers, String body ->
                Map payload = (Map) new JsonSlurper().parseText(body)
                requests.add(payload)
                boolean itemsAsked = payload.query.toString().contains("orderItems")
                Map data = itemsAsked ? [orderItems: [edges: items.collect { [node: it] }, pageInfo: [hasNextPage: false]]] :
                        [orders: [edges: orders.collect { [node: it] }, pageInfo: [hasNextPage: false]]]
                return [statusCode: 200, body: JsonOutput.toJson([data: data, errors: []])]
            })
            c.sleeper = { long ms -> }
            return c
        }

        Map looked = OmsOrderStatusLookupSupport.lookupOrderItemStatuses(CONFIG, ["150738"])
        assertEquals(["150738|01": "00004", "150738|02": "00003"], looked.itemShipGroupByKey)

        Map groups = OmsOrderStatusLookupSupport.lookupShipGroupFacilities(CONFIG, ["150738", "999999"])
        assertTrue(groups.ok as Boolean)
        assertEquals(["150738|00003": ["100102", "77"], "150738|00004": ["100102", "77"], "150738|00002": ["_NA_", null]],
                groups.shipGroupFacilityByKey, "a ship group with no facility record keeps a null externalId; an order not asked is dropped")
        assertEquals(["999999"], groups.missingIds)
        assertTrue(requests.last().query.toString().contains("facility { externalId }"))
        assertFalse(OmsOrderStatusLookupSupport.lookupShipGroupFacilities(CONFIG, ["1,2"]).ok as Boolean)
    }
}
