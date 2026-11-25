package darpan.hotwax.oms

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.junit.jupiter.api.Test

import java.time.Instant

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-356. An audit's recent HotWax read windows on orderDate, so an order placed Nov 3 and completed Nov 8 was never
 * read completed (gorjana M1214814). changedOrderIds finds the window's orders whose items changed status since the last
 * read, from the status rows' transaction stamp. GraphQL is injected; nothing here opens a socket.
 */
class OmsChangedOrdersTests {

    private static final Map CONFIG = [omsRestSourceConfigId: "TEST_CFG", baseUrl: "https://oms.example.com",
                                       apiKey: "test-key", isActive: "Y"]
    private static final long NOV_1 = Instant.parse("2026-11-01T07:00:00Z").toEpochMilli()
    private static final long NOV_8 = Instant.parse("2026-11-08T00:00:00Z").toEpochMilli()
    private static final long NOV_10 = Instant.parse("2026-11-10T00:00:00Z").toEpochMilli()

    private List<Map> requests = []

    /** rowsByStatus: statusId -> pages, each page a list of [orderId, orderDate] status rows. */
    private OmsGqlClient client(Map<String, List<List<Map>>> rowsByStatus) {
        OmsGqlClient c = new OmsGqlClient(CONFIG, { String url, Map headers, String body ->
            Map payload = (Map) new JsonSlurper().parseText(body)
            requests.add(payload)
            Map vars = (Map) payload.variables
            String statusId = ((vars.q as String) =~ /statusId:(\S+)/)[0][1]
            List<List<Map>> pages = rowsByStatus[statusId] ?: [[]]
            int page = vars.after ? (vars.after as String).toInteger() : 0
            boolean more = page + 1 < pages.size()
            Map connection = [edges   : pages[page].collect { [node: [orderId: it.orderId, order: [orderDate: it.orderDate]]] },
                              pageInfo: [hasNextPage: more, endCursor: more ? String.valueOf(page + 1) : null]]
            return [statusCode: 200, body: JsonOutput.toJson([data: [orderStatusTimeline: connection], errors: []])]
        })
        c.sleeper = { long ms -> }
        return c
    }

    @Test
    void anOrderPlacedInTheWindowAndChangedSinceIsReadOnceWhateverItsStatuses() {
        OmsGqlClient c = client([
                ITEM_COMPLETED: [[[orderId: "M1214814", orderDate: "2026-11-03T16:27:24Z"],
                                  [orderId: "M1214814", orderDate: "2026-11-03T16:27:24Z"]],
                                 [[orderId: "M1220751", orderDate: "2026-11-05T10:00:00Z"]]],
                ITEM_CANCELLED: [[[orderId: "M1214814", orderDate: "2026-11-03T16:27:24Z"]]],
        ])

        List<String> ids = OmsGqlOrderPageSource.changedOrderIds(c, NOV_8, NOV_10, NOV_1, [:])

        assertEquals(["M1214814", "M1220751"], ids, "every page is followed and an order is read once")
        assertEquals(["ITEM_APPROVED", "ITEM_CANCELLED", "ITEM_COMPLETED"] as Set,
                requests.collect { ((it.variables.q as String) =~ /statusId:(\S+)/)[0][1] } as Set)
        assertTrue(requests.every { (it.variables.q as String).contains("lastUpdatedTxStamp:>=2026-11-08T00:00:00Z lastUpdatedTxStamp:<2026-11-10T00:00:00Z") },
                "the change window is the status rows' transaction stamp")
    }

    @Test
    void anOrderPlacedBeforeTheWindowIsLeftOutSoTheReadNeverWidensPastTheCheck() {
        OmsGqlClient c = client([ITEM_COMPLETED: [[
                [orderId: "M850040", orderDate: "2026-08-14T12:00:00Z"],
                [orderId: "M1247557", orderDate: "2026-11-11T09:00:00Z"],
                [orderId: "M1231905", orderDate: "2026-11-07T09:00:00Z"],
                [orderId: "NO_DATE", orderDate: null]]]])

        assertEquals(["M1231905"], OmsGqlOrderPageSource.changedOrderIds(c, NOV_8, NOV_10, NOV_1, [:]),
                "placed in August, placed after the window, or with no date: not this check's order")
    }

    @Test
    void anEmptyChangeWindowAsksNothing() {
        OmsGqlClient c = client([:])
        assertEquals([], OmsGqlOrderPageSource.changedOrderIds(c, NOV_10, NOV_10, NOV_1, [:]))
        assertEquals(0, requests.size())
    }

    @Test
    void aReadByChangeAndByKeyAtOnceIsRefused() {
        Map out = OmsGqlOrderPageSource.fetchAll(client([:]), [fromMillis: NOV_1, thruMillis: NOV_10, changedSince: NOV_8,
                                                              lookupKeys: ["M1"], pageConsumer: { Map b -> }])
        assertEquals(["The OMS order extract reads by key or by change, not both."], out.errors)
    }

    @Test
    void noChangedOrderMeansNoRecordsAndNoOrderRead() {
        List<Map> pages = []
        Map out = OmsGqlOrderPageSource.fetchAll(client([:]), [fromMillis: NOV_1, thruMillis: NOV_10, changedSince: NOV_8,
                                                              pageConsumer: { Map b -> pages.add(b) }])
        assertEquals([], out.errors)
        assertEquals(0, out.pagination.changedOrderCount)
        assertEquals([], pages)
        assertTrue(requests.every { it.query.toString().contains("orderStatusTimeline") }, "only the status timeline was asked")
    }
}
