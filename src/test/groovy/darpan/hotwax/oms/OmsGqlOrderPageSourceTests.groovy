package darpan.hotwax.oms

import groovy.json.JsonSlurper
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertThrows
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

    /** DAR-BE-116: one exchange pass covers every buffered orders page, not one pass per page. */
    @Test
    void cursorsAdvanceAndOneExchangePassCoversTheBufferedPages() {
        ScriptedClient client = new ScriptedClient([
                ordersPage([order("M1")], true, "C1"),
                ordersPage([order("M2")], false), assocsPage([])])
        File out = File.createTempFile("gql-", ".json")
        try {
            assertEquals(2, run(client, out).recordCount)
            assertEquals("C1", ((Map) client.calls[1].variables).after)
            assertEquals("orderId:M1,M2 orderItemAssocTypeId:EXCHANGE", ((Map) client.calls[2].variables).q)
            assertEquals(3, client.calls.size())
        } finally { out.delete() }
    }

    @Test
    void theExchangePassRunsWhenTheBufferFillsNotOnlyAtTheEnd() {
        List<Map> big = (1..OmsGqlOrderPageSource.ASSOC_BATCH_ORDER_IDS).collect { order("B${it}") }
        ScriptedClient client = new ScriptedClient([
                ordersPage(big, true, "C1"), assocsPage([]),
                ordersPage([order("M2")], false), assocsPage([])])
        File out = File.createTempFile("gql-", ".json")
        try {
            assertEquals(big.size() + 1, run(client, out).recordCount)
            assertTrue(((Map) client.calls[1].variables).q.toString().startsWith("orderId:B1,"))
            assertEquals("orderId:M2 orderItemAssocTypeId:EXCHANGE", ((Map) client.calls[3].variables).q)
        } finally { out.delete() }
    }

    /** DAR-BE-116: the page is sized for a raised budget; a server that caps one query lower gets a smaller page, once. */
    @Test
    void costExceededHalvesThePageAndRetriesTheSameCursor() {
        ScriptedClient client = new ScriptedClient([
                ordersPage([order("M1")], true, "C1"),
                [thrown: new OmsGqlException("COST_EXCEEDED", "query cost 1700 exceeds 1000")],
                ordersPage([order("M2")], false), assocsPage([])])
        File out = File.createTempFile("gql-", ".json")
        try {
            assertEquals(2, run(client, out).recordCount)
            int first = ((Map) client.calls[0].variables).first as int
            assertEquals("C1", ((Map) client.calls[2].variables).after, "the rejected page is asked again")
            assertEquals(first.intdiv(2), ((Map) client.calls[2].variables).first)
        } finally { out.delete() }
    }

    /** DAR-BE-116: a multi-day window is fetched as day slices in parallel; every slice's orders arrive once. */
    @Test
    void aMultiDayWindowIsFetchedAsContiguousDaySlices() {
        List<String> filters = Collections.synchronizedList([])
        OmsGqlClient client = new OmsGqlClient(CONFIG, { String u, Map h, String b -> [statusCode: 200, body: "{}"] }) {
            @Override
            Map execute(String document, Map variables, int reservation) {
                String q = variables.q as String
                if (document.contains("orderItemAssocs")) return [data: assocsPage([]).data]
                filters.add(q)
                String day = (q =~ /orderDate:>=(\S+)/)[0][1]
                return [data: ordersPage([order("O" + day.take(10))], false).data]
            }
        }
        File out = File.createTempFile("gql-", ".json")
        try {
            Map result = OmsRestSourceSupport.extractOrdersToFile(CONFIG, "2026-09-21T07:00:00Z", "2026-09-24T07:00:00Z",
                    out, KEEP, null, null, null, OmsGqlOrderPageSource.fetcher(client))
            assertEquals([], result.errors)
            List<Map> recs = (List<Map>) (new JsonSlurper().parse(out) as Map).records
            assertEquals(["O2026-09-21", "O2026-09-22", "O2026-09-23"], recs*.orderId.sort())
            List<String> bounds = filters.collect { (it =~ /orderDate:>=(\S+) orderDate:<(\S+)/)[0] }
                    .collect { it[1] + ".." + it[2] }.sort()
            assertEquals(["2026-09-21T07:00:00Z..2026-09-22T07:00:00Z", "2026-09-22T07:00:00Z..2026-09-23T07:00:00Z",
                          "2026-09-23T07:00:00Z..2026-09-24T07:00:00Z"], bounds)
        } finally { out.delete() }
    }

    // ---- DAR-BE-116: ORDER_LINE_UNIT grain over GraphQL (the OMS order-items extract, off REST) ----

    private static final Map UNIT_OPTIONS = [emitGrain: "ORDER_LINE_UNIT", applyExchangeExclusion: false, requireShopifyLineId: false]

    /**
     * DAR-BE-119: an older open finding's order is read by key, whatever its date (the audit reads OMS for its recent days
     * only), through the same cursor loop and flattener; a key that could change the search string is refused.
     */
    @Test
    void unitsAreReadByOrderIdWhenAskedByKey() {
        ScriptedClient client = new ScriptedClient([
                ordersPage([unitOrder("M1")], false),
                itemsPage([item("M1", "00101", "00001", "ITEM_COMPLETED")]),
                productsPage(["P00101"]),
                facilitiesPage(["STORE_1", "WH"])])
        File out = File.createTempFile("gql-", ".json")
        try {
            Map result = OmsRestSourceSupport.extractOrdersToFile(CONFIG, FROM, THRU, out, null, null, null, UNIT_OPTIONS,
                    OmsGqlOrderPageSource.fetcher(client, [lookupKeys: ["M1"]]))
            assertEquals([], result.errors)
            assertEquals(["M1"], ((List<Map>) (new JsonSlurper().parse(out) as Map).records)*.omsOrderId)
            assertEquals("orderId:M1 orderTypeId:SALES_ORDER", ((Map) client.calls[0].variables).q, "by key, not by window")
        } finally { out.delete() }
        assertThrows(IllegalArgumentException) { OmsGqlQueries.keysFilter("orderId", ["M1 orderDate:>2020"]) }
    }

    private static Map unitOrder(String id) {
        return order(id) + [identifications: [[orderIdentificationTypeId: "NETSUITE_ORDER_ID", idValue: "NS" + id]],
                            shipGroups: [edges: [[node: [shipGroupSeqId: "00001", facilityId: "STORE_1"]],
                                                 [node: [shipGroupSeqId: "00002", facilityId: "WH"]]]]]
    }

    private static Map itemsPage(List nodes, boolean hasNext = false, String endCursor = null) {
        return [data: [orderItems: [edges: nodes.collect { [node: it] }, pageInfo: [hasNextPage: hasNext, endCursor: endCursor]]]]
    }

    private static Map facilitiesPage(List<String> facilityIds) {
        return [data: [facilities: [edges: facilityIds.collect { [node: [facilityId: it, facilityName: "Store " + it]] }, pageInfo: [hasNextPage: false, endCursor: null]]]]
    }

    private static Map productsPage(List<String> productIds) {
        return [data: [products: [edges: productIds.collect { [node: [productId: it, internalName: "SKU-" + it]] }, pageInfo: [hasNextPage: false, endCursor: null]]]]
    }

    private static Map item(String orderId, String seq, String group, String status = "ITEM_COMPLETED", int quantity = 1) {
        return [orderId: orderId, orderItemSeqId: seq, externalId: "L" + seq, productId: "P" + seq, quantity: quantity,
                statusId: status, shipGroupSeqId: group]
    }

    @Test
    void unitGrainWritesTheRestUnitRecordWithEachItemUnderItsShipGroup() {
        ScriptedClient client = new ScriptedClient([
                ordersPage([unitOrder("M1")], false),
                itemsPage([item("M1", "00101", "00001", "ITEM_COMPLETED", 2), item("M1", "00102", "00002", "ITEM_CANCELLED")]),
                productsPage(["P00101", "P00102"]),
                facilitiesPage(["STORE_1", "WH"])])
        File out = File.createTempFile("gql-", ".json")
        try {
            Map result = run(client, out, null, null, UNIT_OPTIONS)
            assertEquals([], result.errors)
            List<Map> recs = (List<Map>) (new JsonSlurper().parse(out) as Map).records
            assertEquals(3, recs.size(), "quantity 2 is two units, plus one")
            Map first = recs.find { it.orderItemSeqId == "00101" }
            assertEquals("SHM1", first.shopifyOrderId)
            assertEquals("M1", first.omsOrderId)
            assertEquals("STORE_1", first.facilityId)
            assertEquals("WH", recs.find { it.orderItemSeqId == "00102" }.facilityId)
            // DAR-UI-081: each unit names its SKU, read once for the page's products.
            assertEquals(["SKU-P00101", "SKU-P00101", "SKU-P00102"], recs.sort { it.orderItemSeqId }*.sku)
            assertEquals(4, client.calls.size(), "orders, one orderItems pass, one products pass, one facilities pass; no exchange pass when exclusion is off")
            assertEquals("productId:P00101,P00102", ((Map) client.calls[2].variables).q)
            assertEquals("facilityId:STORE_1,WH", ((Map) client.calls[3].variables).q)
            assertEquals(["Store STORE_1", "Store STORE_1", "Store WH"], recs.sort { it.orderItemSeqId }*.facilityName)
            // DAR-BE-129: a unit reads its orders in the shared orders document, so an order read of the window can share it.
            String doc = client.calls[0].document as String
            assertEquals(OmsGqlQueries.sharedOrdersPlan().document, doc)
            assertTrue(doc.contains("shipGroups(first: 3)"))
            assertFalse(doc.contains("identifications"))
            assertTrue(doc.contains("grandTotal"), "DAR-BE-118: 0 names a partial payment, which never reaches NetSuite")
            assertEquals("orderId:M1", ((Map) client.calls[1].variables).q)
        } finally { out.delete() }
    }

    @Test
    void anItemOutsideTheReadShipGroupsOrWithNoProductStillWritesItsUnit() {
        // DAR-BE-129: the order read takes two ship groups, so a third group's item arrives with no facility; on
        // 2026-10-26 that null key failed a 20-minute Oct 1-25 extract.
        Map noProduct = item("M1", "00102", "00001") + [productId: null]
        ScriptedClient client = new ScriptedClient([
                ordersPage([unitOrder("M1")], false),
                itemsPage([item("M1", "00101", "00003"), noProduct]),
                productsPage(["P00101"]),
                facilitiesPage(["STORE_1", "WH"])])
        File out = File.createTempFile("gql-", ".json")
        try {
            Map result = run(client, out, null, null, UNIT_OPTIONS)
            assertEquals([], result.errors)
            List<Map> recs = (List<Map>) (new JsonSlurper().parse(out) as Map).records
            assertEquals(["00101", "00102"], recs*.orderItemSeqId.sort())
            assertNull(recs.find { it.orderItemSeqId == "00101" }.facilityName)
            assertNull(recs.find { it.orderItemSeqId == "00102" }.sku)
        } finally { out.delete() }
    }

    @Test
    void aUnitLevelFilterIsAppliedToUnitsNotRefusedAsAnOrderField() {
        ScriptedClient client = new ScriptedClient([
                ordersPage([unitOrder("M1")], false),
                itemsPage([item("M1", "00101", "00001"), item("M1", "00102", "00002", "ITEM_CANCELLED")]),
                productsPage(["P00101", "P00102"]),
                facilitiesPage(["STORE_1", "WH"])])
        File out = File.createTempFile("gql-", ".json")
        try {
            Map result = run(client, out, null, [[fieldExpression: "unitState", operator: "INCLUDE_IN", filterValues: "COMPLETED", sequenceNum: 1]], UNIT_OPTIONS)
            assertEquals([], result.errors)
            List<Map> recs = (List<Map>) (new JsonSlurper().parse(out) as Map).records
            assertEquals(["00101"], recs*.orderItemSeqId)
        } finally { out.delete() }
    }

    @Test
    void aFilterOnAFieldTheGraphqlUnitDoesNotCarryFailsTheExtract() {
        ["salesChannelEnumId", '$.records[*].netsuiteOrderId'].each { String field ->
            ScriptedClient client = new ScriptedClient([])
            File out = File.createTempFile("gql-", ".json"); out.delete()
            Map result = run(client, out, null, [[fieldExpression: field, operator: "EXCLUDE_IN", filterValues: "X", sequenceNum: 1]], UNIT_OPTIONS)
            assertTrue(((List) result.errors)[0].toString().contains(field.replace('$.records[*].', '')), "${result.errors}")
            assertEquals(0, client.calls.size())
        }
    }

    /** DAR-BE-116: R1/R3 and R15 read the same order items for the same days; with sharing on, OMS is asked once. */
    @Test
    void twoUnitExtractsOfOneWindowShareOneReadAndKeepTheirOwnFilters() {
        OmsGqlSharedRead.reset()
        OmsGqlLookupCache.reset()
        List<String> asked = Collections.synchronizedList([])
        java.util.concurrent.CountDownLatch both = new java.util.concurrent.CountDownLatch(2)
        OmsGqlClient client = new OmsGqlClient(CONFIG, { String u, Map h, String b -> [statusCode: 200, body: "{}"] }) {
            @Override
            Map execute(String document, Map variables, int reservation) {
                asked.add(document.contains("orderItems(") ? "items" : document.contains("products(") ? "products" :
                        document.contains("facilities(") ? "facilities" : document.contains("orderItemAssocs(") ? "exchange" : "orders")
                both.await(5, java.util.concurrent.TimeUnit.SECONDS)
                if (document.contains("orderItemAssocs(")) return [data: [orderItemAssocs: [edges: [], pageInfo: [hasNextPage: false]]]]
                if (document.contains("products(")) return [data: productsPage(["P00101", "P00102"]).data]
                if (document.contains("facilities(")) return [data: facilitiesPage(["STORE_1", "WH"]).data]
                if (document.contains("orderItems(")) {
                    return [data: itemsPage([item("M1", "00101", "00001"), item("M1", "00102", "00002", "ITEM_CANCELLED")]).data]
                }
                return [data: ordersPage([unitOrder("M1")], false).data]
            }
        }
        client.realEndpoint = true
        Map config = CONFIG
        def pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            List<List> filters = [[[fieldExpression: "unitState", operator: "INCLUDE_IN", filterValues: "COMPLETED", sequenceNum: 1]],
                                  [[fieldExpression: "facilityId", operator: "EXCLUDE_IN", filterValues: "STORE_1", sequenceNum: 1]]]
            List<File> outs = filters.collect { File.createTempFile("gql-", ".json") }
            List futures = (0..1).collect { int i ->
                pool.submit({ ->
                    both.countDown()
                    OmsRestSourceSupport.extractOrdersToFile(config, FROM, THRU, outs[i], null, null, filters[i], UNIT_OPTIONS,
                            OmsGqlOrderPageSource.fetcher(client))
                } as java.util.concurrent.Callable)
            }
            futures.each { assertEquals([], ((Map) it.get(10, java.util.concurrent.TimeUnit.SECONDS)).errors) }
            assertEquals(["exchange", "facilities", "items", "orders", "products"], asked.sort(),
                    "one orders page, its exchange pass and one items, products and facilities pass, for both extracts")
            assertEquals(["00101"], ((List<Map>) (new JsonSlurper().parse(outs[0]) as Map).records)*.orderItemSeqId)
            assertEquals(["00102"], ((List<Map>) (new JsonSlurper().parse(outs[1]) as Map).records)*.orderItemSeqId)
            outs*.delete()
        } finally {
            pool.shutdownNow()
            OmsGqlSharedRead.reset()
            OmsGqlLookupCache.reset()
        }
    }

    /** DAR-BE-129: the Oct 1-25 audit read the month's orders twice, once per shape; now one read serves both. */
    @Test
    void anOrderExtractAndAUnitExtractOfOneWindowShareOneOrdersRead() {
        OmsGqlSharedRead.reset()
        OmsGqlLookupCache.reset()
        List<String> asked = Collections.synchronizedList([])
        java.util.concurrent.CountDownLatch both = new java.util.concurrent.CountDownLatch(2)
        OmsGqlClient client = new OmsGqlClient(CONFIG, { String u, Map h, String b -> [statusCode: 200, body: "{}"] }) {
            @Override
            Map execute(String document, Map variables, int reservation) {
                asked.add(document.contains("orderItems(") ? "items" : document.contains("products(") ? "products" :
                        document.contains("facilities(") ? "facilities" : document.contains("orderItemAssocs(") ? "exchange" : "orders")
                both.await(5, java.util.concurrent.TimeUnit.SECONDS)
                if (document.contains("orderItemAssocs(")) return [data: [orderItemAssocs: [edges: [], pageInfo: [hasNextPage: false]]]]
                if (document.contains("products(")) return [data: productsPage(["P00101"]).data]
                if (document.contains("facilities(")) return [data: facilitiesPage(["STORE_1", "WH"]).data]
                if (document.contains("orderItems(")) return [data: itemsPage([item("M1", "00101", "00001")]).data]
                return [data: ordersPage([unitOrder("M1")], false).data]
            }
        }
        client.realEndpoint = true
        def pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            List<File> outs = [File.createTempFile("gql-", ".json"), File.createTempFile("gql-", ".json")]
            // KEEP asks only for the always-read fields and a derived one: covered by the shared read, so it joins it.
            List<String> keep = KEEP
            List futures = [
                    pool.submit({ -> both.countDown()
                        OmsRestSourceSupport.extractOrdersToFile(CONFIG, FROM, THRU, outs[0], keep, null, null, null, OmsGqlOrderPageSource.fetcher(client))
                    } as java.util.concurrent.Callable),
                    pool.submit({ -> both.countDown()
                        OmsRestSourceSupport.extractOrdersToFile(CONFIG, FROM, THRU, outs[1], null, null, null, UNIT_OPTIONS, OmsGqlOrderPageSource.fetcher(client))
                    } as java.util.concurrent.Callable)]
            futures.each { assertEquals([], ((Map) it.get(10, java.util.concurrent.TimeUnit.SECONDS)).errors) }
            assertEquals(["exchange", "facilities", "items", "orders", "products"], asked.sort(), "the orders page is asked once for both")
            assertEquals(["M1"], ((List<Map>) (new JsonSlurper().parse(outs[0]) as Map).records)*.orderId)
            assertEquals(["00101"], ((List<Map>) (new JsonSlurper().parse(outs[1]) as Map).records)*.orderItemSeqId)
            outs*.delete()
        } finally {
            pool.shutdownNow()
            OmsGqlSharedRead.reset()
            OmsGqlLookupCache.reset()
        }
    }

    @Test
    void orderItemsFollowTheirOwnCursor() {
        ScriptedClient client = new ScriptedClient([
                ordersPage([unitOrder("M1")], false),
                itemsPage([item("M1", "00101", "00001")], true, "I1"),
                itemsPage([item("M1", "00102", "00001")], false),
                productsPage(["P00101", "P00102"]),
                facilitiesPage(["STORE_1", "WH"])])
        File out = File.createTempFile("gql-", ".json")
        try {
            assertEquals(2, run(client, out, null, null, UNIT_OPTIONS).recordCount)
            assertEquals("I1", ((Map) client.calls[2].variables).after)
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
                ordersPage([order("M1")], true, "C1"),
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

    /** DAR-BE-153: a rule on paymentRefNums asks six slots with manualRefNum and gets the ids on each record. */
    @Test
    void aRuleOnPaymentRefNumsReadsTheOppsShopifyTransactionIds() {
        ScriptedClient client = new ScriptedClient([
                ordersPage([order("M1", [[manualRefNum: "20766330257539"], [manualRefNum: null]])], false),
                assocsPage([])])
        File out = File.createTempFile("gql-", ".json")
        try {
            run(client, out, ["orderId", "externalId", "paymentRefNums"])
            assertTrue(((Map) client.calls[0]).document.toString().contains(
                    "paymentPreferences(first: ${OmsGqlQueries.REFNUM_PAYMENT_PREF_SLOTS}) { manualRefNum }".toString()))
            List records = (List) (new JsonSlurper().parse(out) as Map).records
            assertEquals("20766330257539", ((Map) records[0]).paymentRefNums)
        } finally { out.delete() }
    }

    // ---- review fix pass ----

    @Test
    void anUnsupportedFilterFieldFailsEvenWithNoProjection() {
        ScriptedClient client = new ScriptedClient([])
        File out = File.createTempFile("gql-", ".json"); out.delete()
        Map result = run(client, out, null, [[fieldExpression: "salesChannelEnumId", operator: "EXCLUDE_IN",
                                              filterValues: "POS_SALES_CHANNEL", sequenceNum: 1]])
        assertTrue(((List) result.errors)[0].toString().contains("salesChannelEnumId"))
        assertEquals(0, client.calls.size())
    }

    @Test
    void aFilterFieldIsSelectedEvenWithNoProjection() {
        ScriptedClient client = new ScriptedClient([ordersPage([], false)])
        File out = File.createTempFile("gql-", ".json")
        try {
            run(client, out, null, [[fieldExpression: "productStoreId", operator: "EXCLUDE_IN", filterValues: "X", sequenceNum: 1]])
            assertTrue((client.calls[0].document as String).contains("productStoreId"))
        } finally { out.delete() }
    }

    private static void assertFailsClosed(List<Map> queued, String expected, Map config = CONFIG) {
        ScriptedClient client = new ScriptedClient(queued)
        File out = File.createTempFile("gql-", ".json"); out.delete()
        Map result = OmsRestSourceSupport.extractOrdersToFile(config, FROM, THRU, out, KEEP, null, null, null,
                OmsGqlOrderPageSource.fetcher(client))
        assertTrue(((List) result.errors).size() > 0, "expected an error, got a clean extract")
        assertTrue(((List) result.errors)[0].toString().contains(expected), ((List) result.errors)[0].toString())
        assertEquals(false, result.dataAvailable)
        assertFalse(out.exists())
    }

    @Test
    void aResponseWithNoOrdersConnectionIsAnErrorNotAnEmptyExtract() {
        assertFailsClosed([[data: [:]]], "MALFORMED")
        assertFailsClosed([[data: null]], "MALFORMED")
        assertFailsClosed([[data: [orders: [pageInfo: [hasNextPage: false]]]]], "MALFORMED")
    }

    @Test
    void hasNextPageWithoutACursorIsAnErrorNotATruncatedExtract() {
        assertFailsClosed([ordersPage([order("M1")], true, null), assocsPage([])], "MALFORMED")
    }

    @Test
    void aRepeatedCursorIsAnErrorNotAnEndlessLoop() {
        assertFailsClosed([ordersPage([order("M1")], true, "C1"),
                           ordersPage([order("M2")], true, "C1")], "MALFORMED")
    }

    @Test
    void anAssocsResponseWithNoConnectionIsAnError() {
        // A missing exchange pass would let exchange orders re-enter as "in OMS, missing from Shopify".
        assertFailsClosed([ordersPage([order("M1")], false), [data: [:]]], "MALFORMED")
    }
}
