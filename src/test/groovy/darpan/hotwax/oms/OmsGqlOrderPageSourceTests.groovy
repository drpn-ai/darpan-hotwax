package darpan.hotwax.oms

import groovy.json.JsonSlurper
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-BE-064 Task 6. Driven end to end through OmsRestSourceSupport.extractOrdersToFile with a scripted client. */
class OmsGqlOrderPageSourceTests {

    private static final Map CONFIG = [baseUrl: "https://x.test", authType: "NONE", omsRestSourceConfigId: "T1"]
    private static final String FROM = "2026-09-24T16:00:00Z"
    private static final String THRU = "2026-09-24T20:00:00Z"
    private static final List KEEP = ["orderId", "orderName", "externalId", "grandTotal", "orderDate", "statusId", "hasPaymentPreference"]

    private static class ScriptedClient extends OmsGqlClient {
        List<Map> queued
        List<Map> calls = []
        ScriptedClient(List<Map> queued) {
            super(CONFIG, { String u, Map h, String b -> [statusCode: 200, body: "{}"] })
            this.queued = queued
        }
        @Override
        Map execute(String document, Map variables, int reservation) {
            calls.add([document: document, variables: variables, reservation: reservation])
            if (!queued) throw new IllegalStateException("unexpected extra call: ${variables}")
            Map next = queued.remove(0)
            if (next.thrown) throw (RuntimeException) next.thrown
            return [data: next.data, errors: [], extensions: [:]]
        }
    }

    private static Map order(String id, List opps = [[paymentMethodTypeId: "CREDIT_CARD", statusId: "PAYMENT_SETTLED"]]) {
        return [orderId: id, orderName: "#" + id, externalId: "SH" + id, statusId: "ORDER_COMPLETED",
                orderTypeId: "SALES_ORDER", orderDate: "2026-09-24T17:00:00Z", grandTotal: 10, paymentPreferences: opps]
    }

    private static Map ordersPage(List nodes, boolean hasNext, String endCursor = null) {
        return [data: [orders: [edges: nodes.collect { [node: it] }, pageInfo: [hasNextPage: hasNext, endCursor: endCursor]]]]
    }

    private static Map assocsPage(List nodes, boolean hasNext = false, String endCursor = null) {
        return [data: [orderItemAssocs: [edges: nodes.collect { [node: it] }, pageInfo: [hasNextPage: hasNext, endCursor: endCursor]]]]
    }

    private static Map run(ScriptedClient client, File out, List keep = KEEP, List filters = null, Map options = null) {
        return OmsRestSourceSupport.extractOrdersToFile(CONFIG, FROM, THRU, out, keep, null, filters, options,
                OmsGqlOrderPageSource.fetcher(client))
    }

    @Test
    void onePageProducesPresenceRecordsWithThePaymentField() {
        ScriptedClient client = new ScriptedClient([ordersPage([order("M1"), order("M2", [])], false), assocsPage([])])
        File out = File.createTempFile("gql-", ".json")
        try {
            Map result = run(client, out)
            assertEquals(2, result.recordCount)
            List<Map> recs = (List<Map>) (new JsonSlurper().parse(out) as Map).records
            assertEquals(["Y", "N"], recs*.hasPaymentPreference)
            assertFalse(recs[0].containsKey("paymentPreferences"), "the raw list is projected away")
        } finally { out.delete() }
    }

    @Test
    void cursorsAdvanceAndEveryOrdersPageGetsItsOwnExchangePass() {
        ScriptedClient client = new ScriptedClient([
                ordersPage([order("M1")], true, "C1"), assocsPage([]),
                ordersPage([order("M2")], false), assocsPage([])])
        File out = File.createTempFile("gql-", ".json")
        try {
            assertEquals(2, run(client, out).recordCount)
            assertEquals("C1", ((Map) client.calls[2].variables).after)
            assertEquals("orderId:M2 orderItemAssocTypeId:EXCHANGE", ((Map) client.calls[3].variables).q)
        } finally { out.delete() }
    }

    @Test
    void exchangeOrdersAreCountedAndManifestedLikeRest() {
        ScriptedClient client = new ScriptedClient([
                ordersPage([order("M1"), order("M2")], false),
                assocsPage([[orderId: "M2", orderItemSeqId: "00101", toOrderId: "M0", orderItemAssocTypeId: "EXCHANGE"]])])
        File out = File.createTempFile("gql-", ".json")
        try {
            Map result = run(client, out)
            assertEquals(1, result.recordCount)
            assertEquals("M0", ((Map) ((List) result.exchangeManifest)[0]).toOrderId)
            assertEquals(1, ((Map) ((Map) (new JsonSlurper().parse(out) as Map).metadata).filters).excludedExchangeOrderCount)
        } finally { out.delete() }
    }

    @Test
    void theExchangePassPagesToExhaustion() {
        ScriptedClient client = new ScriptedClient([
                ordersPage([order("M1"), order("M2")], false),
                assocsPage([[orderId: "M1", toOrderId: "M0", orderItemAssocTypeId: "EXCHANGE"]], true, "A1"),
                assocsPage([[orderId: "M2", toOrderId: "M0", orderItemAssocTypeId: "EXCHANGE"]], false)])
        File out = File.createTempFile("gql-", ".json")
        try {
            assertEquals(0, run(client, out).recordCount)
            assertEquals("A1", ((Map) client.calls[2].variables).after)
        } finally { out.delete() }
    }

    @Test
    void aThrottledPageMidWindowFailsTheExtractAndWritesNoFile() {
        ScriptedClient client = new ScriptedClient([
                ordersPage([order("M1")], true, "C1"), assocsPage([]),
                [thrown: new OmsGqlException("THROTTLED", "throttled")]])
        File out = File.createTempFile("gql-", ".json")
        out.delete()
        Map result = run(client, out)
        assertTrue(((List) result.errors)[0].toString().contains("THROTTLED"))
        assertEquals(false, result.dataAvailable)
        assertFalse(out.exists())
    }

    @Test
    void anUnsupportedFilterFieldFailsBeforeAnyCall() {
        ScriptedClient client = new ScriptedClient([])
        File out = File.createTempFile("gql-", ".json")
        out.delete()
        Map result = run(client, out, KEEP, [[fieldExpression: "salesChannelEnumId", operator: "EXCLUDE_IN",
                                              filterValues: "POS_SALES_CHANNEL", sequenceNum: 1]])
        assertTrue(((List) result.errors)[0].toString().contains("salesChannelEnumId"))
        assertEquals(0, client.calls.size())
    }

    @Test
    void unsupportedOptionsAreRefusedNotIgnored() {
        [[orderStatusIds: ["ORDER_APPROVED"]], [windowFieldName: "entryDate"]].each { Map options ->
            ScriptedClient client = new ScriptedClient([])
            File out = File.createTempFile("gql-", ".json")
            out.delete()
            Map result = run(client, out, KEEP, null, options)
            assertTrue(((List) result.errors).size() > 0, "options ${options} must be refused")
            assertEquals(0, client.calls.size())
        }
    }

    @Test
    void anEmptyWindowIsAnEmptyExtractWithNoErrorAndNoExchangeCall() {
        ScriptedClient client = new ScriptedClient([ordersPage([], false)])
        File out = File.createTempFile("gql-", ".json")
        try {
            Map result = run(client, out)
            assertEquals(0, result.recordCount)
            assertEquals([], result.errors)
            assertEquals(1, client.calls.size())
        } finally { out.delete() }
    }

    @Test
    void theWindowTravelsAsAVariableAndPaginationRecordsTheSlotCap() {
        ScriptedClient client = new ScriptedClient([
                ordersPage([order("M1", (1..OmsGqlQueries.PAYMENT_PREF_SLOTS).collect { [paymentMethodTypeId: "GIFT_CARD"] })], false),
                assocsPage([])])
        File out = File.createTempFile("gql-", ".json")
        try {
            run(client, out)
            Map first = (Map) client.calls[0]
            assertTrue(((Map) first.variables).q.toString().contains("orderDate:<2026-09-24T20:00:00Z"))
            assertFalse((first.document as String).contains("<"))
            Map pagination = (Map) ((Map) (new JsonSlurper().parse(out) as Map).metadata).pagination
            assertEquals(1, pagination.paymentPreferenceSlotsFullCount)
            assertEquals(true, pagination.orderTypeFilteredServerSide)
        } finally { out.delete() }
    }
}
