package darpan.hotwax.oms

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-BE-040 fix 3. The sender is injected, so every case runs without a socket. */
class OmsGqlReturnsPageSourceTests {

    private static final Map CONFIG = [baseUrl: "https://gorjana-maarg.hotwax.io", authType: "BEARER", apiToken: "t"]
    private static final long FROM = 1790812800000L   // 2026-10-01T00:00:00Z
    private static final long THRU = 1790899200000L   // 2026-10-02T00:00:00Z

    private List<Map> requests = Collections.synchronizedList([])

    /** Answers by GraphQL root field; each handler takes the variables and returns the connection map. */
    private OmsGqlClient client(Map<String, Closure> handlers) {
        OmsGqlClient c = new OmsGqlClient(CONFIG, { String url, Map headers, String body ->
            Map payload = (Map) new JsonSlurper().parseText(body)
            String root = (payload.query =~ /\{\s*(\w+)\(/)[0][1]
            requests.add([root: root, query: payload.query, variables: payload.variables])
            Closure h = handlers[root]
            if (h == null) throw new AssertionError("unexpected GraphQL root ${root}")
            Object answer = honourIdFilter(h.call(payload.variables), (String) ((Map) payload.variables)?.q)
            if (answer instanceof Map && ((Map) answer).errors) return [statusCode: 200, body: JsonOutput.toJson(answer)]
            return [statusCode: 200, body: JsonOutput.toJson([data: [(root): answer], errors: []])]
        })
        c.sleeper = { long ms -> }
        return c
    }

    /**
     * As the OMS does (S99-026): an id filter ("field:a,b", the query's last term) answers only the rows naming an asked id. A
     * window term (field:>=… / field:<…) is not an id filter, and a row that does not carry the field is left as it is.
     */
    private static Object honourIdFilter(Object answer, String q) {
        if (!(answer instanceof Map) || !(((Map) answer).edges instanceof List) || !q) return answer
        def m = q.trim().tokenize(' ').last() =~ /^(\w+):([^<>=].*)$/
        if (!m.matches()) return answer
        String field = m.group(1)
        Set<String> asked = m.group(2).tokenize(',') as Set<String>
        List edges = ((List) ((Map) answer).edges).findAll { Object e ->
            Map node = (Map) ((Map) e).node
            !node.containsKey(field) || asked.contains(node.get(field)?.toString())
        }
        return ((Map) answer) + [edges: edges]
    }

    private static Map conn(List nodes, String endCursor = null) {
        return [edges: nodes.collect { [node: it] }, pageInfo: [hasNextPage: endCursor != null, endCursor: endCursor]]
    }

    private static Map ret(String id, Map o = [:]) {
        return [returnId: id, statusId: "RETURN_REQUESTED", entryDate: "2026-10-01T10:00:00Z", externalId: null,
                currencyUomId: "USD", returnChannelEnumId: "ADMIN_RTN_CHANNEL",
                identifications: [[returnIdentificationTypeId: "SHOPIFY_RETURN_ID", idValue: "3744700${id.takeRight(4)}", thruDate: null]]] + o
    }

    private Map run(OmsGqlClient c, List<List<Map>> pages = []) {
        Map result = OmsGqlReturnsPageSource.fetchAll(c, [fromMillis: FROM, thruMillis: THRU, config: CONFIG,
                pageConsumer: { List<Map> records -> pages.add(records) }])
        return result
    }

    private Map<String, Closure> lookupsAnswering() {
        return [returnItems: { Map v -> conn([[returnId: "M0001", returnItemSeqId: "01", orderId: "GR1", orderItemSeqId: "00101",
                                               productId: "P1", returnQuantity: 1, receivedQuantity: 0, returnPrice: 65,
                                               returnReasonId: "UNKNOWN", statusId: "RETURN_REQUESTED", returnTypeId: "RTN_REFUND"]]) },
                orders     : { Map v -> conn([[orderId: "GR1", externalId: "7186636636291", productStoreId: "STORE", grandTotal: "0.00",
                                               identifications: [[orderIdentificationTypeId: "SHOPIFY_ORD_ID", idValue: "7186636636291"],
                                                                 [orderIdentificationTypeId: "NETSUITE_ORDER_ID", idValue: "81049165"]]]]) },
                orderItems : { Map v -> conn([[orderId: "GR1", orderItemSeqId: "00101", externalId: "16501091238019"]]) },
                products   : { Map v -> conn([[productId: "P1", internalName: "219-001-G"]]) },
                goodIdentifications: { Map v -> conn([[productId: "P1", goodIdentificationTypeId: "NETSUITE_PRODUCT_ID", idValue: "30345", thruDate: null]]) }]
    }

    /** S99-026: a lookup answer whose rows name none of the asked ids means the OMS ignored the filter: it fails, never "missing". */
    @Test
    void aLookupAnswerNamingNoAskedIdFailsInsteadOfReadingAsMissing() {
        Closure answering = { List<Map> nodes ->
            new OmsGqlClient(CONFIG, { String u, Map h, String b -> [statusCode: 200, body: "{}"] }) {
                @Override
                Map execute(String document, Map variables, int reservation) {
                    return [data: [returnItems: [edges: nodes.collect { [node: it] }, pageInfo: [hasNextPage: false]]]]
                }
            }
        }
        Closure lookup = { OmsGqlClient c, String key ->
            OmsGqlReturnsPageSource.fetchByIds(c, OmsGqlQueries.returnItemsDocument(), "returnItems", key, ["R1", "R2"], 50, 50, 1, [lookupCalls: 0])
        }
        OmsGqlException e = (OmsGqlException) org.junit.jupiter.api.Assertions.assertThrows(OmsGqlException) {
            lookup(answering([[returnId: "OTHER1"], [returnId: "OTHER2"]]), "returnId")
        }
        assertTrue(e.message.contains("did not honour"), e.message)
        assertEquals(["R1"], lookup(answering([[returnId: "R1"]]), "returnId")*.returnId, "a partial answer is an answer: R2 is absent")
        assertEquals([], lookup(answering([]), "returnId"), "nothing found is not an ignored filter")
        assertEquals(1, lookup(answering([[orderId: "O9"]]), "returnId").size(), "rows that do not carry the key cannot be judged")
    }

    @Test
    void pagesTheWindowAndHandsEveryReturnToTheConsumerWithItsClass() {
        int call = 0
        Map<String, Closure> h = lookupsAnswering() + [returns: { Map v ->
            call++
            return call == 1 ? conn([ret("M0001"), ret("M0002", [statusId: "RETURN_CANCELLED"])], "c1")
                             : conn([ret("M0003", [identifications: [[returnIdentificationTypeId: "NETSUITE_RMA_ID", idValue: "80249409"]]])])
        }]
        List<List<Map>> pages = []
        Map result = run(client(h), pages)

        assertEquals([], result.errors)
        assertEquals("GRAPHQL", result.transport)
        List<Map> records = pages.flatten() as List<Map>
        assertEquals([["M0001", "KEEP"], ["M0002", "CANCELLED"], ["M0003", "NO_SHOPIFY_REF"]], records.collect { [it.returnId, it.omsReturnClass] })
        Map r = records[0]
        assertEquals("37447000001", r.shopifyReturnId)
        assertEquals("7186636636291", r.orderExternalId)
        assertEquals("STORE", r.orderProductStoreId, "the origin order's store rides on the orders lookup already made")
        assertEquals("81049165", r.orderNetsuiteId, "and whether it reached NetSuite (its NETSUITE_ORDER_ID)")
        assertEquals("0", r.orderGrandTotal.toString(), "and its total: 0 when its payment was taken back (partial payment)")
        assertEquals("16501091238019", ((r.items as List)[0] as Map).orderItemExternalId)
        assertEquals("219-001-G", ((r.items as List)[0] as Map).sku)
        assertEquals("30345", ((r.items as List)[0] as Map).productNetsuiteId, "whether the item's product can reach NetSuite at all")
        assertEquals("goodIdentificationTypeId:NETSUITE_PRODUCT_ID productId:P1",
                requests.find { it.root == "goodIdentifications" }.variables.q, "only the NetSuite id type is read")
        assertEquals([returnsCount: 3, excludedNoShopifyRefCount: 1, excludedCancelledCount: 1], result.serverCounts)
        assertEquals("c1", requests.findAll { it.root == "returns" }[1].variables.after, "second page must follow the cursor")
    }

    // ------------------------------------------------------------------------------------------- DAR-BE-318 (Task 18)

    /** The window's returns of every class: kept, cancelled (with and without a Shopify reference), and no reference. */
    private static List<Map> everyClass() {
        return [ret("M0001"), ret("M0002", [statusId: "RETURN_CANCELLED"]),
                ret("628087", [identifications: [], returnChannelEnumId: "HAPPINESS_GUARANTEE", statusId: "RETURN_COMPLETED"]),
                ret("M0004", [identifications: [], statusId: "RETURN_CANCELLED"]),
                ret("M0005", [externalId: "990011", identifications: []])]
    }

    private static final Map<String, String> CLASS_OF = [M0001: "KEEP", M0002: "CANCELLED", "628087": "NO_SHOPIFY_REF",
                                                         M0004: "CANCELLED", M0005: "KEEP"]

    /** The lookups, with one item on order GR1 for each return asked. */
    private Map<String, Closure> everyClassLookups() {
        return lookupsAnswering() + [returns: { Map v -> conn(everyClass()) },
                returnItems: { Map v -> conn(((v.q as String) - "returnId:").split(",").collect { String id ->
                    [returnId: id, returnItemSeqId: "01", orderId: "GR1", orderItemSeqId: "00101", productId: "P1", returnQuantity: 1,
                     receivedQuantity: 0, returnPrice: 65, returnReasonId: "UNKNOWN", statusId: "RETURN_REQUESTED", returnTypeId: "RTN_REFUND"] }) }]
    }

    private Map read(Map options, List<Map> nodes = everyClass(), boolean shared = false) {
        OmsGqlSharedRead.reset()
        OmsGqlLookupCache.reset()
        requests.clear()
        OmsGqlClient c = client(everyClassLookups() + [returns: { Map v -> conn(nodes) }])
        c.realEndpoint = shared
        List<List<Map>> pages = []
        Map result = OmsGqlReturnsPageSource.fetcher(c, options).call([fromMillis: FROM, thruMillis: THRU, config: CONFIG,
                pageConsumer: { List<Map> records -> pages.add(records) }])
        OmsGqlSharedRead.reset()
        return result + [records: pages.flatten() as List<Map>]
    }

    /**
     * Ruling 3b2b-12: a window read writes the cancelled and no-reference returns it used to leave out, each built like any
     * return and marked with its class, for the OMS_RETURNS_GQL row's Keep lines; with or without the shared read.
     */
    @Test
    void aWindowReadWritesCancelledAndNoRefReturnsWithTheirClass() {
        [false, true].each { boolean shared ->
            Map out = read([:], everyClass(), shared)
            assertEquals([], out.errors)
            Map<String, Map> byId = ((List<Map>) out.records).collectEntries { [(it.returnId): it] }
            assertEquals(CLASS_OF.keySet(), byId.keySet(), "every return is written (shared ${shared})")
            assertEquals(CLASS_OF, byId.collectEntries { k, v -> [(k): v.omsReturnClass] }, "each with its class (shared ${shared})")
            assertEquals("7186636636291", byId.M0002.orderExternalId, "a cancelled return is built like any other")
            assertEquals(null, byId["628087"].shopifyReturnId)
            assertTrue(((List<Map>) out.records).every { !it.containsKey(OmsGqlReturnsPageSource.SHARED_VERDICT) })
            assertEquals("omsReturnClass", ((List<Map>) out.records)[0].keySet().last(), "written last, after the built record")
        }
        // onlyCancelledReturns still asks the OMS for the cancelled ones only, and leaves out a status it did not ask for
        Map cancelled = read([onlyCancelledReturns: true])
        assertTrue((requests.find { it.root == "returns" }.variables.q as String).startsWith("statusId:RETURN_CANCELLED "))
        assertEquals([["M0002", "CANCELLED"], ["M0004", "CANCELLED"]], ((List<Map>) cancelled.records).collect { [it.returnId, it.omsReturnClass] })
    }

    /**
     * DAR-BE-116 + DAR-BE-318: R8 (keeps returns with no Shopify reference) and R11 (does not) still read the month once; each
     * gets every return with its class, and its own counts.
     */
    @Test
    void theSharedReadServesBothOptionsFromOneRead() {
        OmsGqlSharedRead.reset()
        OmsGqlLookupCache.reset()
        java.util.concurrent.CountDownLatch both = new java.util.concurrent.CountDownLatch(2)
        Map<String, Closure> h = everyClassLookups() + [returns: { Map v ->
            both.await(5, java.util.concurrent.TimeUnit.SECONDS)
            return conn(everyClass())
        }]
        OmsGqlClient c = client(h)
        c.realEndpoint = true
        def pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            List<List<List<Map>>> pages = [Collections.synchronizedList([]), Collections.synchronizedList([])]
            List futures = [true, false].withIndex().collect { boolean keepNoRef, int i ->
                pool.submit({ ->
                    both.countDown()
                    OmsGqlReturnsPageSource.fetchAll(c, [fromMillis: FROM, thruMillis: THRU, config: CONFIG,
                            includeReturnsWithoutShopifyRef: keepNoRef, pageConsumer: { List<Map> records -> pages[i].add(records) }])
                } as java.util.concurrent.Callable)
            }
            List<Map> results = futures.collect { (Map) it.get(10, java.util.concurrent.TimeUnit.SECONDS) }
            results.each { assertEquals([], it.errors) }
            assertEquals(1, requests.count { it.root == "returns" }, "one returns page for both extracts")
            assertEquals(1, requests.count { it.root == "returnItems" }, "one lookup pass for both")
            [0, 1].each { int i ->
                assertEquals(CLASS_OF, (pages[i].flatten() as List<Map>).collectEntries { [(it.returnId): it.omsReturnClass] })
            }
            assertEquals([returnsCount: 5, excludedNoShopifyRefCount: 0, excludedCancelledCount: 2, keptNoShopifyRefCount: 1], results[0].serverCounts)
            assertEquals([returnsCount: 5, excludedNoShopifyRefCount: 1, excludedCancelledCount: 2], results[1].serverCounts)
            assertTrue((Map) results[0].pagination instanceof Map && results[0].pagination.sharedRead == true)
        } finally {
            pool.shutdownNow()
            OmsGqlSharedRead.reset()
            OmsGqlLookupCache.reset()
        }
    }

    /**
     * Ruling 3b2b-12: a read by key is not Keep's business. By returnId it leaves out the cancelled and no-reference returns
     * as before (onlyCancelledReturns: only the cancelled ones), and writes the class of each return it keeps (fix round 1:
     * a side read by the other side's keys still runs Keep, so a tenant rule on the class finds it).
     */
    @Test
    void aKeyedReadAnswersAsToday() {
        Map<String, Closure> h = everyClassLookups()
        List<List<Map>> pages = []
        Map result = OmsGqlReturnsPageSource.fetchAll(client(h), [config: CONFIG, lookupKeys: CLASS_OF.keySet() as List<String>,
                pageConsumer: { List<Map> records -> pages.add(records) }])
        assertEquals([], result.errors)
        List<Map> byKey = pages.flatten() as List<Map>
        assertEquals([["M0001", "KEEP"], ["M0005", "KEEP"]], byKey.collect { [it.returnId, it.omsReturnClass] }, "the class of what it keeps")
        assertEquals([returnsCount: 5, excludedNoShopifyRefCount: 1, excludedCancelledCount: 2], result.serverCounts)

        pages.clear()
        Map cancelled = OmsGqlReturnsPageSource.fetchAll(client(h), [config: CONFIG, lookupKeys: ["M0002", "M0004", "M0001"],
                onlyCancelledReturns: true, pageConsumer: { List<Map> records -> pages.add(records) }])
        assertEquals([], cancelled.errors)
        assertEquals([["M0002", "CANCELLED"], ["M0004", "CANCELLED"]], (pages.flatten() as List<Map>).collect { [it.returnId, it.omsReturnClass] })
        assertTrue((requests.findAll { it.root == "returns" }.last().variables.q as String).startsWith("statusId:RETURN_CANCELLED returnId:"))
    }

    /** DAR-BE-318: every option's server counts are what the read counted before it wrote every return. */
    @Test
    void theServerCountsAreUnchanged() {
        Map<Map, Map> expected = [
                ([:])                                    : [returnsCount: 5, excludedNoShopifyRefCount: 1, excludedCancelledCount: 2],
                ([includeReturnsWithoutShopifyRef: true]): [returnsCount: 5, excludedNoShopifyRefCount: 0, excludedCancelledCount: 2,
                                                            keptNoShopifyRefCount: 1],
                // OC: the 3 answers that are not cancelled are counted where they were dropped uncounted
                ([onlyCancelledReturns: true])           : [returnsCount: 5, excludedNoShopifyRefCount: 0, excludedCancelledCount: 0,
                                                            keptNoShopifyRefCount: 0, excludedNotCancelledCount: 3],
        ]
        expected.each { Map options, Map counts ->
            assertEquals(counts, read(options).serverCounts, "unshared ${options}")
            if (!options.onlyCancelledReturns) assertEquals(counts, read(options, everyClass(), true).serverCounts, "shared ${options}")
        }
        // and they reach the file's metadata as before, with no configuredExclusions: the read applies no tenant rule
        Map extract = OmsReturnsSourceSupport.extractReturns(CONFIG, "2026-10-01T00:00:00Z", "2026-10-02T00:00:00Z", null, null,
                [pageFetcher: OmsGqlReturnsPageSource.fetcher(client(everyClassLookups()))])
        assertEquals([excludedNoShopifyRefCount: 1, serverReportedReturnsCount: 5, excludedCancelledCount: 2],
                ((Map) extract.requestMetadata).filters)
        assertEquals(5, extract.recordCount)
    }

    /** DAR-BE-116 R8 (F-104602): a product with no NETSUITE_PRODUCT_ID, or only an expired one, leaves the item without the field. */
    @Test
    void anItemWhoseProductHasNoLiveNetsuiteIdCarriesNone() {
        Map<String, Closure> h = lookupsAnswering() + [returns: { Map v -> conn([ret("M0001")]) },
                goodIdentifications: { Map v -> conn([[productId: "P1", goodIdentificationTypeId: "NETSUITE_PRODUCT_ID", idValue: "1", thruDate: "2025-01-01T00:00:00Z"]]) }]
        List<List<Map>> pages = []
        run(client(h), pages)
        Map item = (((pages.flatten() as List<Map>)[0].items as List)[0] as Map)
        assertFalse(item.containsKey("productNetsuiteId"), "absent, so the REST-parity item shape is unchanged")
    }

    /**
     * Aditi 2026-10-24 on R8 F-100860 (gorjana M263376): "the same item has a return and a warranty... the warranty is still
     * open... because of this the exchange order is unable to sync to NS". An exchange return syncs after its exchange order,
     * and that order waits while another return of the same Shopify order is open: the record names both, only for an
     * exchange whose order has not reached NetSuite.
     */
    @Test
    void anExchangeReturnNamesItsExchangeOrderAndTheOpenReturnHoldingIt() {
        Map<String, Closure> h = lookupsAnswering() + [
                returns    : { Map v ->
                    if ((v.q as String).startsWith("returnId:")) return conn([[returnId: "M0001", responses: [[replacementOrderId: "GR9"]]]])
                    return conn([ret("M0001")]) },
                returnItems: { Map v ->
                    if ((v.q as String).startsWith("orderId:")) return conn([
                            [returnId: "M0001", returnItemSeqId: "01", orderId: "GR1", orderItemSeqId: "00101", statusId: "RETURN_COMPLETED"],
                            [returnId: "M2006", returnItemSeqId: "01", orderId: "GR1", orderItemSeqId: "00101", statusId: "RETURN_REQUESTED"]])
                    return conn([[returnId: "M0001", returnItemSeqId: "01", orderId: null, orderItemSeqId: null, productId: "P1",
                                  returnQuantity: 1, receivedQuantity: 0, returnPrice: 65, statusId: "RETURN_COMPLETED", returnTypeId: "RTN_EXCHANGE"]]) },
                orders     : { Map v ->
                    if ((v.q as String).startsWith("externalId:")) return conn([[orderId: "GR1", externalId: "634"], [orderId: "GR9", externalId: "634"]])
                    return conn([[orderId: "GR9", orderName: "EXC-#GOR1-1", externalId: "634", identifications: []]]) }]
        List<List<Map>> pages = []
        Map result = run(client(h), pages)
        assertEquals([], result.errors)
        Map r = (pages.flatten() as List<Map>)[0]
        assertEquals(["GR9", "EXC-#GOR1-1", null, "M2006", "RETURN_REQUESTED"],
                [r.exchangeOrderId, r.exchangeOrderName, r.exchangeOrderNetsuiteId, r.openReturnOnOrderId, r.openReturnOnOrderStatus])
    }

    /** DAR-BE-192: withReturnCredit asks each kept return's refunded payments once and writes returnCredit; off, it asks nothing. */
    @Test
    void withReturnCreditTheRecordCarriesTheCreditHotWaxGave() {
        Map<String, Closure> h = lookupsAnswering() + [
                returns: { Map v ->
                    if ((v.q as String).startsWith("returnId:")) return conn([[returnId: "M0001", responses: [
                            [orderPaymentPreferenceId: "OPP1", paymentPreference: [statusId: "PAYMENT_REFUNDED", maxAmount: 60.37]],
                            [orderPaymentPreferenceId: "OPP1", paymentPreference: [statusId: "PAYMENT_REFUNDED", maxAmount: 60.37]]]]])
                    return conn([ret("M0001")]) }]
        List<List<Map>> pages = []
        Map result = OmsGqlReturnsPageSource.fetchAll(client(h), [fromMillis: FROM, thruMillis: THRU, config: CONFIG, withReturnCredit: true,
                pageConsumer: { List<Map> records -> pages.add(records) }])
        assertEquals([], result.errors)
        assertEquals(new BigDecimal("60.37"), (pages.flatten() as List<Map>)[0].returnCredit)
        assertEquals(1, requests.count { (it.query as String).contains("OmsReturnCredits") })
        assertEquals("returnId:M0001", requests.find { (it.query as String).contains("OmsReturnCredits") }.variables.q)

        requests.clear(); pages.clear()
        Map without = run(client(lookupsAnswering() + [returns: { Map v -> conn([ret("M0001")]) }]), pages)
        assertEquals([], without.errors)
        assertFalse((pages.flatten() as List<Map>)[0].containsKey("returnCredit"))
        assertEquals(0, requests.count { (it.query as String).contains("OmsReturnCredits") })
    }

    /**
     * DAR-BE-192. Asked by replacement order, the read finds each replacement's return from the order: EXC-#GOR7-2 (M2) shares
     * its original's Shopify id, and its return R1 sits on round 1's order (M1, as gorjana M1154107's); WTY-#GOR8-1 (W1) has its
     * own Shopify id and descends from #GOR8 (O8) by name. A return naming another order is not its. Every record carries the
     * replacement it was found for and its credit; a warranty claim with no Shopify reference is kept.
     */
    @Test
    void askedByReplacementOrderEachReplacementGetsTheReturnThatNamesIt() {
        Map<String, Closure> h = lookupsAnswering() + [
                orders: { Map v ->
                    String q = v.q as String
                    if (q.startsWith("orderId:")) return conn([[orderId: "M2", orderName: "EXC-#GOR7-2", externalId: "700", identifications: []],
                                                              [orderId: "W1", orderName: "WTY-#GOR8-1", externalId: "900", identifications: []]])
                    if (q.startsWith("externalId:")) return conn([[orderId: "O7", externalId: "700"], [orderId: "M1", externalId: "700"], [orderId: "M2", externalId: "700"]])
                    if (q.startsWith("orderName:")) {
                        // HotWax allows only eq on orderName (gorjana 2026-11-04: "comparator 'in' not allowed"): one name a query.
                        assert !q.contains(",") : q
                        return conn(q == "orderName:#GOR8" ? [[orderId: "O8", orderName: "#GOR8", externalId: "800"]] : [])
                    }
                    return conn([[orderId: "GR1", externalId: "7186636636291", identifications: []]]) },
                returnItems: { Map v ->
                    String q = v.q as String
                    if (q.startsWith("orderId:")) {
                        assert !q.contains("M2") && !q.contains("W1") : q
                        return conn([[returnId: "R1", orderId: "M1"], [returnId: "R0", orderId: "O7"], [returnId: "R8", orderId: "O8"]])
                    }
                    return conn([[returnId: (q - "returnId:").split(",")[0], returnItemSeqId: "01", orderId: "GR1", orderItemSeqId: "00101", productId: "P1",
                                  returnQuantity: 1, receivedQuantity: 1, returnPrice: 60, statusId: "RETURN_COMPLETED", returnTypeId: "RTN_REPLACE_EXACT"]]) },
                returns: { Map v ->
                    String q = v.q as String
                    List<String> ids = (q - "returnId:").split(",") as List<String>
                    return conn(ids.collect { String id ->
                        Map n = ret(id, [statusId: "RETURN_COMPLETED", identifications: [[returnIdentificationTypeId: "AFTSHIP_WTY_RMA_ID", idValue: "W-${id}", thruDate: null]]])
                        n + [responses: [[replacementOrderId: [R1: "M2", R0: "M9", R8: "W1"][id], orderPaymentPreferenceId: "P-${id}",
                                          paymentPreference: [statusId: "PAYMENT_REFUNDED", maxAmount: 60]]]] }) }]
        List<List<Map>> pages = []
        Map result = OmsGqlReturnsPageSource.fetchAll(client(h), [config: CONFIG, lookupKeys: ["M2", "W1"], lookupKeyField: "replacementOrderId",
                pageConsumer: { List<Map> records -> pages.add(records) }])
        assertEquals([], result.errors)
        List<Map> records = (pages.flatten() as List<Map>).sort { it.returnId }
        assertEquals([["R1", "M2"], ["R8", "W1"]], records.collect { [it.returnId, it.replacementOrderId] })
        assertTrue(records.every { !it.containsKey("omsReturnClass") }, "DAR-BE-318: the brain's replacement lookup writes no class")
        assertEquals([60, 60], records*.returnCredit.collect { it as int })
        assertEquals(["EXC-#GOR7", "#GOR7"], OmsGqlReturnsPageSource.originalNames("EXC-#GOR7-2"))
        assertEquals(["WTY-#GOR196813520-1", "#GOR196813520-1"], OmsGqlReturnsPageSource.originalNames("WTY-#GOR196813520-1-1"))
        assertEquals([], OmsGqlReturnsPageSource.originalNames("#GOR195459209"))
    }

    /** DAR-BE-119: an older open finding's return is read by its id, with no window. */
    @Test
    void returnsAreReadByIdWhenAskedByKey() {
        Map<String, Closure> h = lookupsAnswering() + [returns: { Map v -> conn([ret("M0001")]) }]
        List<List<Map>> pages = []
        Map result = OmsGqlReturnsPageSource.fetchAll(client(h), [config: CONFIG, lookupKeys: ["M0001"],
                pageConsumer: { List<Map> records -> pages.add(records) }])
        assertEquals([], result.errors)
        assertEquals(["M0001"], (pages.flatten() as List<Map>)*.returnId)
        assertEquals("returnId:M0001", requests.find { it.root == "returns" }.variables.q)
    }

    /**
     * DAR-BE-352 (Malbon R6 keys its returns on the Shopify order id): an older open finding is read by that id. No returns
     * search names an order, so the returns are found through every order carrying it (an exchange keeps the original's
     * Shopify id) and those orders' return items, then read by id like any return. A return whose record names another
     * order (its first item is on one) is not the finding's and is left out.
     */
    @Test
    void askedByShopifyOrderIdTheReturnsOnItsOrdersAreRead() {
        Map<String, String> shopifyIdOf = [O1: "111", O2: "111", O3: "222", O4: "333"]
        Map<String, String> firstOrderOf = [M1001: "O1", M1002: "O2", M1003: "O4"]
        Map<String, Closure> h = lookupsAnswering() + [
                orders: { Map v ->
                    String q = v.q as String
                    if (q.startsWith("externalId:")) {
                        assert !q.contains("orderTypeId") : "every order carrying the Shopify id, exchanges included: ${q}"
                        List<String> wanted = (q - "externalId:").split(",") as List<String>
                        return conn(shopifyIdOf.findAll { it.value in wanted }.collect { [orderId: it.key, externalId: it.value] })
                    }
                    List<String> ids = (q - "orderId:").split(",") as List<String>
                    return conn(ids.collect { [orderId: it, externalId: shopifyIdOf[it], identifications: []] }) },
                returnItems: { Map v ->
                    String q = v.q as String
                    if (q.startsWith("orderId:")) {
                        assertEquals(["O1", "O2", "O3"], ((q - "orderId:").split(",") as List<String>).sort())
                        return conn([[returnId: "M1001", orderId: "O1"], [returnId: "M1002", orderId: "O2"], [returnId: "M1003", orderId: "O3"]])
                    }
                    return conn(((q - "returnId:").split(",") as List<String>).collect { String id ->
                        [returnId: id, returnItemSeqId: "01", orderId: firstOrderOf[id], orderItemSeqId: "00101", productId: "P1",
                         returnQuantity: 1, receivedQuantity: 1, returnPrice: 60, statusId: "RETURN_COMPLETED", returnTypeId: "RTN_REFUND"] }) },
                returns: { Map v ->
                    List<String> ids = ((v.q as String) - "returnId:").split(",") as List<String>
                    return conn(ids.collect { ret(it, [statusId: "RETURN_COMPLETED"]) }) }]
        List<List<Map>> pages = []
        Map result = OmsGqlReturnsPageSource.fetchAll(client(h), [config: CONFIG, lookupKeys: ["111", "222"], lookupKeyField: "orderExternalId",
                pageConsumer: { List<Map> records -> pages.add(records) }])

        assertEquals([], result.errors)
        List<Map> records = (pages.flatten() as List<Map>).sort { it.returnId }
        assertEquals([["M1001", "111", "KEEP"], ["M1002", "111", "KEEP"]], records.collect { [it.returnId, it.orderExternalId, it.omsReturnClass] })
        assertEquals(["M1001", "M1002", "M1003"], ((requests.find { it.root == "returns" }.variables.q as String) - "returnId:").split(",").toList().sort())
    }

    /** DAR-BE-352: a Shopify order id travels inside the search string, so it holds id characters only. */
    @Test
    void aShopifyOrderIdKeyHoldsIdCharactersOnly() {
        Map result = OmsGqlReturnsPageSource.fetchAll(client([:]), [config: CONFIG, lookupKeys: ["111 statusId:X"], lookupKeyField: "orderExternalId",
                pageConsumer: { List<Map> records -> }])
        assertTrue((result.errors as List<String>).any { it.contains("letters, digits, _ and - only") }, "${result.errors}")
        assertTrue(requests.isEmpty())
    }

    @Test
    void anotherKeyIsStillRefused() {
        Map result = OmsGqlReturnsPageSource.fetchAll(client([:]), [config: CONFIG, lookupKeys: ["X1"], lookupKeyField: "shopifyReturnId",
                pageConsumer: { List<Map> records -> }])
        assertEquals(["The OMS GraphQL returns extract reads by returnId, replacementOrderId or orderExternalId only, not 'shopifyReturnId'."], result.errors)
    }

    /** DAR-BE-116: lookups run once per buffer of kept returns, not once per returns page. */
    @Test
    void oneLookupPassCoversTheBufferedReturnsPages() {
        int call = 0
        Map<String, Closure> h = lookupsAnswering() + [returns: { Map v ->
            call++
            return call == 1 ? conn([ret("M0001")], "c1") : conn([ret("M0002")])
        }]
        List<List<Map>> pages = []
        Map result = run(client(h), pages)
        assertEquals([], result.errors)
        assertEquals(["M0001", "M0002"], (pages.flatten() as List<Map>)*.returnId)
        List<Map> itemCalls = requests.findAll { it.root == "returnItems" }
        assertEquals(1, itemCalls.size(), "one returnItems call for both pages")
        assertEquals("returnId:M0001,M0002", ((Map) itemCalls[0].variables).q)
    }

    /** DAR-BE-116: a multi-day window runs as contiguous day slices, each return arriving once. */
    @Test
    void aMultiDayWindowIsFetchedAsContiguousDaySlices() {
        List<String> filters = Collections.synchronizedList([])
        Map<String, Closure> h = lookupsAnswering() + [returns: { Map v ->
            String q = v.q as String
            filters.add(q)
            String day = (q =~ /entryDate:>=(\S+)/)[0][1]
            return conn([ret("R" + day.substring(8, 10) + "01")])
        }]
        List<List<Map>> pages = Collections.synchronizedList([])
        Map result = OmsGqlReturnsPageSource.fetchAll(client(h), [fromMillis: FROM, thruMillis: FROM + 3 * 86400000L,
                config: CONFIG, pageConsumer: { List<Map> records -> pages.add(records) }])
        assertEquals([], result.errors)
        assertEquals(["R0101", "R0201", "R0301"], (pages.flatten() as List<Map>)*.returnId.sort())
        assertEquals(["entryDate:>=2026-10-01T00:00:00Z entryDate:<2026-10-02T00:00:00Z",
                      "entryDate:>=2026-10-02T00:00:00Z entryDate:<2026-10-03T00:00:00Z",
                      "entryDate:>=2026-10-03T00:00:00Z entryDate:<2026-10-04T00:00:00Z"], filters.sort())
    }

    /** DAR-BE-116: R8 (keeps returns with no Shopify reference) and R11 (does not) read the month's returns once. */
    @Test
    void twoReturnsExtractsOfOneWindowShareOneReadAndKeepTheirOwnScope() {
        OmsGqlSharedRead.reset()
        OmsGqlLookupCache.reset()
        java.util.concurrent.CountDownLatch both = new java.util.concurrent.CountDownLatch(2)
        Map<String, Closure> h = lookupsAnswering() + [returns: { Map v ->
            both.await(5, java.util.concurrent.TimeUnit.SECONDS)
            return conn([ret("M0001"), ret("M0002", [identifications: []]), ret("M0003", [statusId: "RETURN_CANCELLED"])])
        }]
        OmsGqlClient c = client(h)
        c.realEndpoint = true
        Map config = CONFIG
        def pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            List<List<List<Map>>> pages = [Collections.synchronizedList([]), Collections.synchronizedList([])]
            List futures = [true, false].withIndex().collect { boolean keepNoRef, int i ->
                pool.submit({ ->
                    both.countDown()
                    OmsGqlReturnsPageSource.fetchAll(c, [fromMillis: FROM, thruMillis: THRU, config: config,
                            includeReturnsWithoutShopifyRef: keepNoRef, pageConsumer: { List<Map> records -> pages[i].add(records) }])
                } as java.util.concurrent.Callable)
            }
            List<Map> results = futures.collect { (Map) it.get(10, java.util.concurrent.TimeUnit.SECONDS) }
            results.each { assertEquals([], it.errors) }
            assertEquals(1, requests.count { it.root == "returns" }, "one returns page for both extracts")
            // DAR-BE-318: both write every return with its class; Spark Keep keeps each check's own scope
            assertEquals(["M0001", "M0002", "M0003"], (pages[0].flatten() as List<Map>)*.returnId.sort())
            assertEquals(["M0001", "M0002", "M0003"], (pages[1].flatten() as List<Map>)*.returnId.sort())
            assertEquals(1, ((Map) results[1].serverCounts).excludedNoShopifyRefCount)
            assertEquals(1, ((Map) results[0].serverCounts).keptNoShopifyRefCount)
            results.each { assertEquals(1, ((Map) it.serverCounts).excludedCancelledCount) }
            assertFalse((pages[0].flatten() as List<Map>).any { it.containsKey(OmsGqlReturnsPageSource.SHARED_VERDICT) })
        } finally {
            pool.shutdownNow()
            OmsGqlSharedRead.reset()
            OmsGqlLookupCache.reset()
        }
    }

    @Test
    void costExceededHalvesTheReturnsPageAndAsksAgain() {
        List<Integer> firsts = []
        Map<String, Closure> h = lookupsAnswering() + [returns: { Map v ->
            firsts.add(v.first as int)
            if (firsts.size() == 1) return [errors: [[message: "cost too high", extensions: [code: "COST_EXCEEDED"]]]]
            return conn([ret("M0001")])
        }]
        List<List<Map>> pages = []
        assertEquals([], run(client(h), pages).errors)
        assertEquals(firsts[0].intdiv(2), firsts[1])
        assertEquals(["M0001"], (pages.flatten() as List<Map>)*.returnId)
    }

    @Test
    void aCheckAgainstNetSuiteKeepsTheReturnsShopifyNeverSees() {
        Map<String, Closure> h = lookupsAnswering() + [returns: { Map v ->
            conn([ret("M0001"), ret("628087", [identifications: [], returnChannelEnumId: "HAPPINESS_GUARANTEE", statusId: "RETURN_COMPLETED"]),
                  ret("M0002", [statusId: "RETURN_CANCELLED"])])
        }]
        List<List<Map>> pages = []
        Map result = OmsGqlReturnsPageSource.fetcher(client(h), [includeReturnsWithoutShopifyRef: true]).call(
                [fromMillis: FROM, thruMillis: THRU, config: CONFIG, pageConsumer: { List<Map> records -> pages.add(records) }])
        assertEquals([], result.errors)
        // DAR-BE-318: the cancelled one is written too, for Spark Keep to set aside (the R8 side's options still apply its line)
        assertEquals([["M0001", "KEEP"], ["628087", "NO_SHOPIFY_REF"], ["M0002", "CANCELLED"]],
                (pages.flatten() as List<Map>).collect { [it.returnId, it.omsReturnClass] })
        assertEquals(null, (pages.flatten() as List<Map>)[1].shopifyReturnId)
        assertEquals([returnsCount: 3, excludedNoShopifyRefCount: 0, excludedCancelledCount: 1, keptNoShopifyRefCount: 1], result.serverCounts,
                "the cancelled one counted as excluded; the kept ones counted")
    }

    @Test
    void aCheckOfCancelledReturnsAsksOnlyForThemAndKeepsEveryOne() {
        Map<String, Closure> h = lookupsAnswering() + [returns: { Map v ->
            conn([ret("M0002", [statusId: "RETURN_CANCELLED"]), ret("628087", [identifications: [], statusId: "RETURN_CANCELLED"]),
                  ret("M0001")])
        }]
        List<List<Map>> pages = []
        Map result = OmsGqlReturnsPageSource.fetcher(client(h), [onlyCancelledReturns: true]).call(
                [fromMillis: FROM, thruMillis: THRU, config: CONFIG, pageConsumer: { List<Map> records -> pages.add(records) }])
        assertEquals([], result.errors)
        assertTrue((requests.find { it.root == "returns" }.variables.q as String).startsWith("statusId:RETURN_CANCELLED entryDate:>="))
        assertEquals(["M0002", "628087"], (pages.flatten() as List<Map>)*.returnId, "cancelled ones kept, Shopify reference or not; a stray status dropped")
    }

    @Test
    void theWindowIsHalfOpenOnEntryDateAndTravelsAsAVariable() {
        Map result = run(client([returns: { Map v -> conn([]) }]))
        assertEquals([], result.errors)
        Map first = requests[0]
        assertEquals("entryDate:>=2026-10-01T00:00:00Z entryDate:<2026-10-02T00:00:00Z", first.variables.q)
        // Moqui's request sanitizer rejects `<` inside the document; it is safe only as a variable.
        assertFalse((first.query as String).contains("<"))
        assertFalse((first.query as String).toLowerCase().contains("mutation"))
    }

    /** A read by key makes no lookup for a page whose returns it all leaves out. */
    @Test
    void noLookupIsMadeForAPageWithNothingKept() {
        OmsGqlReturnsPageSource.fetchAll(client([returns: { Map v -> conn([ret("M0002", [statusId: "RETURN_CANCELLED"])]) }]),
                [config: CONFIG, lookupKeys: ["M0002"], pageConsumer: { List<Map> r -> }])
        assertEquals(["returns"], requests*.root)
    }

    @Test
    void returnItemsFollowTheirOwnCursor() {
        int itemCalls = 0
        Map<String, Closure> h = lookupsAnswering() + [returns: { Map v -> conn([ret("M0001")]) },
                returnItems: { Map v ->
                    itemCalls++
                    return itemCalls == 1
                            ? conn([[returnId: "M0001", returnItemSeqId: "01", orderId: "GR1", orderItemSeqId: "00101", productId: "P1",
                                     returnQuantity: 1, returnPrice: 10]], "i1")
                            : conn([[returnId: "M0001", returnItemSeqId: "02", orderId: "GR1", orderItemSeqId: "00101", productId: "P1",
                                     returnQuantity: 1, returnPrice: 5]])
                }]
        List<List<Map>> pages = []
        run(client(h), pages)
        Map r = (pages.flatten() as List<Map>)[0]
        assertEquals(["01", "02"], (r.items as List)*.returnItemSeqId, "a truncated items page would understate returnTotal")
        assertEquals("15", r.returnTotal.toString())
    }

    @Test
    void aGraphQlErrorFailsTheExtractRatherThanReturningAnEmptyOne() {
        Map result = run(client([returns: { Map v -> [data: null, errors: [[message: "Field 'returns' is undefined", extensions: [code: "FieldUndefined"]]]] }]))
        assertTrue((result.errors as List).any { (it as String).contains("FieldUndefined") }, "${result.errors}")
    }

    @Test
    void aLookupErrorFailsTheExtractToo() {
        // A missing order lookup would emit records with null Shopify ids that read as real differences.
        Map<String, Closure> h = lookupsAnswering() + [returns: { Map v -> conn([ret("M0001")]) },
                orders: { Map v -> [data: null, errors: [[message: "cost", extensions: [code: "COST_EXCEEDED"]]]] }]
        List<List<Map>> pages = []
        Map result = run(client(h), pages)
        assertTrue((result.errors as List).any { (it as String).contains("COST_EXCEEDED") }, "${result.errors}")
        assertEquals([], pages, "nothing reaches the consumer from a page whose lookups failed")
    }

    @Test
    void aWindowIsRequired() {
        Map result = OmsGqlReturnsPageSource.fetchAll(client([:]), [fromMillis: null, thruMillis: THRU, config: CONFIG,
                pageConsumer: { List<Map> r -> }])
        assertTrue((result.errors as List).any { (it as String).contains("window") })
    }
}
