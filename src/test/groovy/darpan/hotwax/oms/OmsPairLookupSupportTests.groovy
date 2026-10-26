package darpan.hotwax.oms

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import static org.junit.jupiter.api.Assertions.*

/** The exchange pair verify's lookup, over GraphQL since DAR-BE-129. */
class OmsPairLookupSupportTests {
    @AfterEach void reset() { OmsPairLookupSupport.setClientFactory(null) }

    private static final Map CONFIG = [omsRestSourceConfigId: "TEST_OMS", baseUrl: "https://oms.example.com",
                                       authType: "BEARER", apiToken: "secret-token"]

    private static class ScriptedClient extends OmsGqlClient {
        List<Map> queued
        List<Map> calls = []
        ScriptedClient(Map config, List<Map> queued) {
            super(config, { String u, Map h, String b -> [statusCode: 200, body: "{}"] })
            this.queued = queued
        }
        @Override
        Map execute(String document, Map variables, int reservation) {
            calls.add([document: document, variables: variables])
            if (!queued) throw new IllegalStateException("unexpected extra call: ${variables}")
            Map next = queued.remove(0)
            if (next.thrown) throw (RuntimeException) next.thrown
            return [data: next, errors: [], extensions: [:]]
        }
    }

    private static Map connection(String root, List nodes) {
        return [(root): [edges: nodes.collect { [node: it] }, pageInfo: [hasNextPage: false, endCursor: null]]]
    }

    private static ScriptedClient scripted(List<Map> answers) {
        ScriptedClient client = new ScriptedClient(CONFIG, answers)
        OmsPairLookupSupport.setClientFactory { Map config -> client }
        return client
    }

    @Test
    void returnsPairSummariesGroupedByExternalIdInOneCallNotOnePerId() {
        ScriptedClient client = scripted([
                connection("orders", [
                        [orderId: "M686331", externalId: "6941645013123", orderName: "#GOR196990495", orderTypeId: "SALES_ORDER",
                         statusId: "ORDER_COMPLETED", grandTotal: "186.59", orderDate: "2026-07-16T19:25:20Z"],
                        [orderId: "M750653", externalId: "6941645013123", orderName: "EXC-#GOR196990495-1", orderTypeId: "SALES_ORDER",
                         statusId: "ORDER_COMPLETED", grandTotal: "50.00", orderDate: "2026-07-28T17:46:22Z"]]),
                connection("orderItemAssocs", [[orderId: "M750653", orderItemSeqId: "01", toOrderId: "M686331",
                                                toOrderItemSeqId: "01", orderItemAssocTypeId: "EXCHANGE"]])])

        Map result = OmsPairLookupSupport.lookupOrdersByExternalId(CONFIG, ["6941645013123", "7000000000001"])

        assertTrue(result.ok as boolean, result.errors.toString())
        assertEquals("externalId:6941645013123,7000000000001", ((Map) client.calls[0].variables).q)
        assertEquals(2, client.calls.size(), "one orders call for both ids, one exchange pass")
        List pair = (List) result.ordersByExternalId["6941645013123"]
        assertEquals(["M686331", "M750653"], pair*.omsOrderId)
        assertEquals([false, true], pair*.hasExchangeAssoc)
        assertEquals(new BigDecimal("186.59"), pair[0].grandTotal)
        assertEquals(java.time.Instant.parse("2026-07-16T19:25:20Z").toEpochMilli(), pair[0].orderDate)
        assertEquals([], result.ordersByExternalId["7000000000001"], "an id with no order is answered empty, not left out")
    }

    @Test
    void theConfigKeepsItsSecretsForTheClient() {
        Map seen = null
        OmsPairLookupSupport.setClientFactory { Map config -> seen = config; new ScriptedClient(config, [connection("orders", [])]) }
        assertTrue(OmsPairLookupSupport.lookupOrdersByExternalId(CONFIG, ["6941645013123"]).ok as boolean)
        assertEquals("secret-token", seen?.apiToken)
    }

    @Test
    void anyFailedCallMakesTheWholeLookupNotOk() {
        scripted([[thrown: new OmsGqlException("THROTTLED", "bucket shut")]])
        Map result = OmsPairLookupSupport.lookupOrdersByExternalId(CONFIG, ["X1"])
        assertFalse(result.ok as boolean)
        assertTrue(result.errors.first().toString().contains("bucket shut"))
    }

    @Test
    void rejectsRequestsAboveTheIdCap() {
        Map result = OmsPairLookupSupport.lookupOrdersByExternalId(CONFIG, (1..101).collect { "E$it".toString() })
        assertFalse(result.ok as boolean)
        assertTrue(result.errors.first().toString().contains("cap"))
    }

    @Test
    void anIdThatWouldCorruptTheSearchIsRefused() {
        Map result = OmsPairLookupSupport.lookupOrdersByExternalId(CONFIG, ["6941645013123 orderTypeId:X"])
        assertFalse(result.ok as boolean)
    }

    /** An endpoint that ignored the filter would pair against arbitrary orders: fail rather than trust it. */
    @Test
    void anAnswerWhereNoOrderEchoesTheAskedIdFailsTheLookup() {
        scripted([connection("orders", [[orderId: "M1", externalId: "WRONG-ID-1"], [orderId: "M2", externalId: "WRONG-ID-2"]])])
        Map result = OmsPairLookupSupport.lookupOrdersByExternalId(CONFIG, ["6941645013123"])
        assertFalse(result.ok as boolean)
        assertTrue(result.errors.first().toString().contains("filter"))
        assertTrue((result.ordersByExternalId as Map).isEmpty())
    }

    @Test
    void aStrayOrderBesideAnEchoedOneIsDropped() {
        scripted([connection("orders", [[orderId: "M686331", externalId: "6941645013123"], [orderId: "M999999", externalId: "SOME-OTHER-ORDER"]]),
                  connection("orderItemAssocs", [])])
        Map result = OmsPairLookupSupport.lookupOrdersByExternalId(CONFIG, ["6941645013123"])
        assertTrue(result.ok as boolean, result.errors.toString())
        assertEquals(["M686331"], ((List) result.ordersByExternalId["6941645013123"])*.omsOrderId)
    }
}
