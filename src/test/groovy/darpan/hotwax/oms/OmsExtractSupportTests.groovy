package darpan.hotwax.oms

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

class OmsExtractSupportTests {
    private static final JsonSlurper JSON_SLURPER = new JsonSlurper()

    @Test
    void safeRowsKeepSecretsOut() {
        Map<String, Object> safeRow = OmsExtractSupport.safeConfigMap([
                omsRestSourceConfigId: "KREWE_OMS",
                companyUserGroupId   : "KREWE",
                baseUrl              : "https://token@example.hotwax.io",
                authType             : "BEARER",
                timeZone             : "America/Chicago",
                apiToken             : "secret-token",
                password             : "secret-password",
                headersJson          : '{"X-Tenant":"KREWE","Authorization":"Bearer hidden"}',
                canReadOrders        : "N",
        ])

        assertEquals("KREWE_OMS", safeRow.omsRestSourceConfigId)
        assertEquals("https://example.hotwax.io", safeRow.baseUrl)
        assertTrue(safeRow.hasApiToken as boolean)
        assertTrue(safeRow.hasPassword as boolean)
        assertEquals("America/Chicago", safeRow.timeZone)
        assertFalse(safeRow.canReadOrders as boolean)
        assertFalse(safeRow.containsKey("apiToken"))
        assertFalse(safeRow.containsKey("password"))
        assertFalse(safeRow.customHeaderNames.contains("Authorization"))
    }

    @Test
    void safeRowsPreserveEntityBackedTimezoneAndReadFlag() {
        Map<String, Object> safeRow = OmsExtractSupport.safeConfigMap(new EntityLikeRecord(fields: [
                omsRestSourceConfigId: "GORJANA_OMS",
                companyUserGroupId   : "GORJANA",
                baseUrl              : "https://gorjana.hotwax.io",
                timeZone             : "America/Chicago",
                canReadOrders        : "N",
        ]))

        assertEquals("America/Chicago", safeRow.timeZone)
        assertFalse(safeRow.canReadOrders as boolean)
    }

    @Test
    void rejectsCrossTenantAndReadOnlyWrites() {
        IllegalArgumentException crossTenant = assertThrows(IllegalArgumentException) {
            OmsExtractSupport.requireWritableTenantConfig([companyUserGroupId: "GORJANA"], "KREWE", true)
        }
        assertTrue(crossTenant.message.contains("not found"))

        IllegalArgumentException readOnly = assertThrows(IllegalArgumentException) {
            OmsExtractSupport.requireWritableTenantConfig(null, "KREWE", false)
        }
        assertTrue(readOnly.message.contains("read-only"))
    }

    @Test
    void buildsExchangeManifestEntriesForExcludedExchangeOrders() {
        Map topLevelAssoc = [orderId: "M750653", externalId: "6941645013123", orderName: "EXC-#GOR196990495-1",
                orderTypeId: "SALES_ORDER", statusId: "ORDER_COMPLETED", grandTotal: 50.0, orderDate: 1785260782199L,
                itemAssocs: [[orderItemAssocTypeId: "EXCHANGE", toOrderId: "M686331", orderItemSeqId: "01", toOrderItemSeqId: "_NA_"]]]
        Map nestedAssocOnly = [orderId: "M900001", externalId: "7000000000001", orderName: "EXC-#GOR2-1",
                orderTypeId: "SALES_ORDER", statusId: "ORDER_CANCELLED", grandTotal: 10.0, orderDate: 1785260782199L,
                shipGroups: [[items: [[assocs: [[orderItemAssocTypeId: "exchange", toOrderId: "M900000"]]]]]]]
        Map keptOrder = [orderId: "M686331", externalId: "6941645013123", orderTypeId: "SALES_ORDER"]

        // A read by key leaves them out itself; a window read keeps them for Keep (anExchangeOrderIsWritten...).
        Map result = OmsExtractSupport.filterComparableOrderRecords([topLevelAssoc, nestedAssocOnly, keptOrder],
                [exchangeExclusionAtRead: true])

        assertEquals(["M686331"], ((List) result.records).collect { it.orderId })
        assertEquals(2, result.excludedExchangeOrderCount)
        List manifest = (List) result.excludedExchangeOrders
        assertEquals(2, manifest.size())
        assertEquals("M750653", manifest[0].omsOrderId)
        assertEquals("6941645013123", manifest[0].externalId)
        assertEquals("EXC-#GOR196990495-1", manifest[0].orderName)
        assertEquals("M686331", manifest[0].toOrderId)          // from top-level itemAssocs
        assertEquals("ORDER_COMPLETED", manifest[0].statusId)
        assertEquals(50.0, manifest[0].grandTotal)
        assertEquals("M900000", manifest[1].toOrderId)          // from nested shipGroups.items.assocs, case-insensitive type

        Map window = OmsExtractSupport.filterComparableOrderRecords([topLevelAssoc, nestedAssocOnly, keptOrder])
        assertEquals(["M750653", "M900001", "M686331"], ((List) window.records).collect { it.orderId })
        assertEquals(manifest, window.excludedExchangeOrders, "the same manifest either way")
        assertEquals(2, (window.flaggedExchangeOrders as Set).size())
    }

    @Test
    void exchangeManifestFileNameSwapsJsonSuffixCaseInsensitively() {
        assertEquals("oms-orders-1-2.exchange-manifest.json", OmsExtractSupport.exchangeManifestFileName("oms-orders-1-2.json"))
        assertEquals("weird.exchange-manifest.json", OmsExtractSupport.exchangeManifestFileName("weird.JSON"))
        assertEquals("noext.exchange-manifest.json", OmsExtractSupport.exchangeManifestFileName("noext"))
    }

    private static Map<String, Object> baseConfig(Map<String, Object> overrides = [:]) {
        return [
                omsRestSourceConfigId  : "KREWE_OMS",
                companyUserGroupId     : "KREWE",
                baseUrl                : "https://dev-maarg.hotwax.io",
                authType               : "NONE",
                connectTimeoutSeconds  : 5,
                readTimeoutSeconds     : 10,
                isActive               : "Y",
                canReadOrders          : "Y",
        ] + overrides
    }

    private static class EntityLikeRecord {
        Map<String, Object> fields = [:]

        Object get(String fieldName) {
            return fields[fieldName]
        }
    }

    private static Map salesOrder(String orderId, String channel) {
        return [orderId: orderId, orderTypeId: "SALES_ORDER", salesChannelEnumId: channel, grandTotal: 10]
    }

    private static Map exchangeOrder(String orderId, String toOrderId) {
        return [orderId: orderId, orderTypeId: "SALES_ORDER", statusId: "ORDER_COMPLETED", grandTotal: 20,
                orderItemAssocs: [[orderItemAssocTypeId: "EXCHANGE", toOrderId: toOrderId]]]
    }

    /** A page source that hands the given raw records to the real shaper, as OmsGqlOrderPageSource does. */
    private static Closure pagesOf(List<Map> raw, List seen = null) {
        return { Map ctx ->
            seen?.add(ctx)
            ((Closure) ctx.pageConsumer).call(OmsExtractSupport.shapePageRecords(raw, (Map) ctx.extractOptions,
                    (Collection) ctx.keepRuleFields, (Set) ctx.keepFieldSet) + [rawCount: raw.size()])
            return [errors: [], statusCode: 200, attemptCount: 1, transport: "GRAPHQL", pagination: [pageCount: 1]]
        }
    }

    private static Map extract(List<Map> raw, List keep, List keepRuleFields, Map options, List seen = null) {
        Map result = OmsExtractSupport.extractOrders(baseConfig(), "2026-05-01T00:00:00Z", "2026-05-02T00:00:00Z",
                keep, null, keepRuleFields, options, pagesOf(raw, seen))
        assertEquals([], result.errors)
        return result
    }

    /** DAR-BE-318 (Task 17): the side's Keep runs in Spark over the file, so the read drops nothing for a tenant's filter. */
    @Test
    void noRecordIsDroppedForATenantFilter() {
        List seen = []
        Map result = extract([salesOrder("1", "WEB_SALES_CHANNEL"), salesOrder("2", "POS_SALES_CHANNEL"),
                              [orderId: "3", orderTypeId: "SALES_ORDER"], [orderId: "4", orderTypeId: "PURCHASE_ORDER"]],
                null, ["salesChannelEnumId", "isExchangeOrder"], null, seen)

        assertEquals(["1", "2", "3"], (result.records as List<Map>)*.orderId, "every sales order; the read limits to sales orders only")
        assertEquals(["salesChannelEnumId", "isExchangeOrder"] as Set, seen[0].keepRuleFields, "the Keep fields reach the page source")
        Map filters = (Map) result.requestMetadata.filters
        assertFalse(filters.containsKey("configuredExclusions"), "no tenant filter ran, so darpan never reads the side as filtered at the read")
        assertEquals(1, filters.excludedNonSalesOrderCount)
    }

    /** Ruling 3b2b-12: the derived scalar is written after the projection, and the manifest still lists the exchange. */
    @Test
    void anExchangeOrderIsWrittenWithIsExchangeOrderAndStillInTheManifest() {
        Map exchange = exchangeOrder("M2", "M0")
        Map result = extract([salesOrder("M1", "WEB"), exchange], ["orderId", "grandTotal"], ["isExchangeOrder"], null)

        assertEquals([[orderId: "M1", grandTotal: 10, isExchangeOrder: "N"], [orderId: "M2", grandTotal: 20, isExchangeOrder: "Y"]],
                result.records, "every record flagged, whatever the projection keeps")
        assertEquals(2, result.recordCount)
        assertEquals(["M2"], (result.exchangeManifest as List<Map>)*.omsOrderId)
        assertEquals("M0", (result.exchangeManifest as List<Map>)[0].toOrderId)
        Map filters = (Map) result.requestMetadata.filters
        assertEquals(1, filters.excludedExchangeOrderCount)
        assertEquals(1, filters.flaggedExchangeOrderCount)
        assertFalse(exchange.containsKey(OmsExtractSupport.IS_EXCHANGE_ORDER_FIELD), "the raw record (a shared read's) is never changed")

        Map whole = extract([exchange], null, null, null)
        assertEquals("Y", (whole.records as List<Map>)[0].isExchangeOrder, "flagged without a projection too")
        assertTrue((whole.records as List<Map>)[0].containsKey("orderItemAssocs"))
    }

    /**
     * DAR-BE-205 / DAR-BE-318: a read by key reads exchanges exactly as before the move. A conclusion lookup runs no exchange
     * scan, so it writes no flag; any other read by key leaves the exchanges out itself and says N on what it keeps.
     */
    @Test
    void aLookupStillReadsExchangesAsToday() {
        List<Map> raw = [salesOrder("M1", "WEB"), exchangeOrder("M2", "M0")]

        Map conclusion = extract(raw, ["orderId"], null, OmsExtractSupport.lookupExtractOptions(["M1", "M2"], true))
        assertEquals([[orderId: "M1"], [orderId: "M2"]], conclusion.records, "a conclusion lookup reads exchange orders, unflagged")
        assertEquals([], conclusion.exchangeManifest)

        Map recheck = extract(raw, ["orderId"], null, OmsExtractSupport.lookupExtractOptions(["M1", "M2"], null))
        assertEquals([[orderId: "M1", isExchangeOrder: "N"]], recheck.records, "any other read by key leaves exchange orders out itself")
        assertEquals(["M2"], (recheck.exchangeManifest as List<Map>)*.omsOrderId)
        assertEquals(1, recheck.requestMetadata.filters.excludedExchangeOrderCount)
        assertEquals(0, recheck.requestMetadata.filters.flaggedExchangeOrderCount)
    }

    /**
     * Fix round 1 (I1): isExchangeOrder is a selectable field, and a side read by the other side's keys still runs Keep, so a
     * keyed read says N on every order it keeps, whatever it is asked to keep; and a unit of a scanned read says N.
     */
    @Test
    void aKeyedReadWritesIsExchangeOrderNOnWhatItKeeps() {
        List<Map> raw = [salesOrder("M1", "WEB"), salesOrder("M3", "POS"), exchangeOrder("M2", "M0")]
        Map keyed = extract(raw, ["orderId"], ["isExchangeOrder"], OmsExtractSupport.lookupExtractOptions(["M1", "M2", "M3"], null))
        assertEquals([[orderId: "M1", isExchangeOrder: "N"], [orderId: "M3", isExchangeOrder: "N"]], keyed.records)
        assertEquals(0, keyed.requestMetadata.filters.flaggedExchangeOrderCount, "no exchange order is written")

        Map whole = extract(raw, null, null, OmsExtractSupport.lookupExtractOptions(["M1"], null))
        assertTrue((whole.records as List<Map>).every { it.isExchangeOrder == "N" }, "without a projection too")

        Map scannedUnits = OmsExtractSupport.shapePageRecords([unitOrder(), unitOrder(orderId: "O2") + [orderItemAssocs: exchangeOrder("x", "O1").orderItemAssocs]],
                [emitGrain: "ORDER_LINE_UNIT", requireShopifyLineId: false], null, null)
        List<Map> scanned = (List<Map>) new JsonSlurper().parseText("[" + scannedUnits.serializedRecords + "]")
        assertEquals(["O1", "O1"], scanned*.omsOrderId, "unit grain leaves the exchange order's units out itself")
        assertEquals(["N", "N"], scanned*.isExchangeOrder)
        assertFalse(units(unitOrder(), null)[0].containsKey("isExchangeOrder"), "a unit read with the exclusion off says nothing")
    }

    private static Map unitOrder(Map extra = [:]) {
        return [orderId: "O1", orderName: "#1001", externalId: "6678687481987", orderTypeId: "SALES_ORDER", statusId: "ORDER_COMPLETED",
                productStoreId: "STORE", grandTotal: 30, salesChannelEnumId: "WEB_SALES_CHANNEL",
                shipGroups: [[facilityId: "F1", items: [[orderItemSeqId: "01", externalId: "152", quantity: 2,
                                                         statusId: "ITEM_CANCELLED", productId: "P1"]]]]] + extra
    }

    private static final Map UNIT_OPTIONS = [emitGrain: "ORDER_LINE_UNIT", applyExchangeExclusion: false, requireShopifyLineId: false]

    private static List<Map> units(Map order, Collection keepRuleFields) {
        Map bundle = OmsExtractSupport.shapePageRecords([order], UNIT_OPTIONS, keepRuleFields, null)
        return (List<Map>) new JsonSlurper().parseText("[" + bundle.serializedRecords + "]")
    }

    /** Ruling 3b2b-10: a unit gets a copy of each order scalar its Keep reads that it lacks, and nothing else. */
    @Test
    void aUnitCarriesOnlyTheKeepNamedOrderScalarsItLacks() {
        List<Map> plain = units(unitOrder(), null)
        List<Map> kept = units(unitOrder(), ["orderName", "productStoreId", "unitState", "shipGroups"])

        assertEquals(2, kept.size(), "one record per unit")
        kept.eachWithIndex { Map unit, int i ->
            assertEquals(plain[i] + [orderName: "#1001", productStoreId: "STORE"], unit)
        }
        assertFalse(kept[0].containsKey("grandTotal"), "an order field no Keep reads is not carried")
        assertFalse(kept[0].containsKey("shipGroups"), "a nested order field is never carried: the old view held scalars only")
        assertEquals(plain, units(unitOrder(), []), "no Keep, no copies")
    }

    /** The old filter view laid the unit over the order: a key the unit holds is its own, a null one included. */
    @Test
    void aUnitsOwnFieldWinsOverTheOrders() {
        // A top-level facilityId on the order, and a ship group with none: the unit's facilityId is null, and stays so.
        Map order = unitOrder(facilityId: "ORIGIN", shipGroups: [[items: [[orderItemSeqId: "01", externalId: "152", quantity: 1,
                                                                             statusId: "ITEM_CANCELLED", productId: "P1"]]]])
        List<Map> kept = units(order, ["statusId", "facilityId", "orderTypeId"])

        assertEquals(1, kept.size())
        assertEquals("ITEM_CANCELLED", kept[0].statusId, "the item's status, never the order's ORDER_COMPLETED")
        assertTrue(kept[0].containsKey("facilityId") && kept[0].facilityId == null, "a null unit field stays the unit's")
        assertEquals("SALES_ORDER", kept[0].orderTypeId, "a field the unit lacks comes from its order")
    }

    @Test
    void headersRejectControlCharactersOverlongValuesAndNonObjects() {
        IllegalArgumentException smuggled = assertThrows(IllegalArgumentException) {
            OmsExtractSupport.validateHeadersJson(JsonOutput.toJson(["X-Inject": "abc\r\nX-Evil: smuggled"]))
        }
        assertTrue(smuggled.message.contains("control characters"))
        IllegalArgumentException overlong = assertThrows(IllegalArgumentException) {
            OmsExtractSupport.validateHeadersJson(JsonOutput.toJson(["X-Big": "a" * 5000]))
        }
        assertTrue(overlong.message.contains("exceeds 4096 chars"))
        assertTrue(assertThrows(IllegalArgumentException) { OmsExtractSupport.validateHeadersJson('{not-json') }
                .message.contains("Headers JSON is invalid"))
        assertTrue(assertThrows(IllegalArgumentException) { OmsExtractSupport.validateHeadersJson('["a","b"]') }
                .message.contains("Headers JSON must be a JSON object."))
    }

    @Test
    void headersDropBlockedNamesAndCarryTheAuthHeader() {
        Map<String, String> headers = OmsExtractSupport.buildHeaders(baseConfig([authType: "BEARER", apiToken: "t0k",
                headersJson: '{"X-Tenant":"KREWE","Authorization":"Bearer hidden","Cookie":"c"}']))
        assertEquals("Bearer t0k", headers.Authorization)
        assertEquals("KREWE", headers["X-Tenant"])
        assertFalse(headers.containsKey("Cookie"))
        assertTrue(assertThrows(IllegalArgumentException) { OmsExtractSupport.buildHeaders(baseConfig([authType: "BASIC"])) }
                .message.contains("Username and password are required"))
    }

    @Test
    void buildDefaultFileNameUsesTheGivenPrefixWhenSupplied() {
        assertEquals("oms-orders-1-2.json", OmsExtractSupport.buildDefaultFileName(1L, 2L))
        assertEquals("oms-returns-1-2.json", OmsExtractSupport.buildDefaultFileName(1L, 2L, "oms-returns"))
        assertEquals("oms-orders-start-end.json", OmsExtractSupport.buildDefaultFileName(null, null, "   "))
    }

    @Test
    void extractOptionsDefaultToSalesOrderBehaviour() {
        Map<String, Object> options = OmsExtractSupport.normalizeExtractOptions(null)
        assertTrue(options.applyExchangeExclusion as boolean)
        assertEquals(false, options.exchangeExclusionAtRead, "a window read leaves the exchange to Keep")
        assertNull(options.emitGrain)
        assertEquals(true, options.requireShopifyLineId)
        assertEquals(false, OmsExtractSupport.normalizeExtractOptions([applyExchangeExclusion: false]).applyExchangeExclusion)
        assertEquals(false, OmsExtractSupport.normalizeExtractOptions([requireShopifyLineId: "N"]).requireShopifyLineId)
        assertEquals(true, OmsExtractSupport.normalizeExtractOptions([emitGrain: "ORDER_LINE_UNIT", exchangeExclusionAtRead: false])
                .exchangeExclusionAtRead, "a unit carries no flag: unit grain always leaves exchanges out itself")
    }

    /** DAR-BE-205 (gorjana R10): a lookup of approved exchange orders came back empty, so no facility rule could match. */
    @Test
    void onlyAKeyedConclusionLookupReadsExchangeOrders() {
        assertEquals([applyExchangeExclusion: false], OmsExtractSupport.lookupExtractOptions(["M1214942"], true))
        assertEquals([applyExchangeExclusion: false], OmsExtractSupport.lookupExtractOptions(["M1214942"], "Y"))
        // An audit re-check by key keeps leaving exchanges out at the read (DAR-BE-318: keyed reads answer as before).
        assertEquals([exchangeExclusionAtRead: true], OmsExtractSupport.lookupExtractOptions(["M1214942"], null))
        assertEquals([exchangeExclusionAtRead: true], OmsExtractSupport.lookupExtractOptions(["M1214942"], false))
        // A window read is never widened by the flag.
        assertNull(OmsExtractSupport.lookupExtractOptions(null, true))
        assertNull(OmsExtractSupport.lookupExtractOptions([], true))
    }

    @Test
    void anExtractNeedsAPageSourceAndBothWindowBounds() {
        Map noFetcher = OmsExtractSupport.extractOrders(baseConfig(), "2026-05-01T00:00:00Z", "2026-05-01T01:00:00Z",
                null, null, null, null, null)
        assertTrue(noFetcher.errors.any { it.contains("GraphQL page source") }, noFetcher.errors.toString())

        boolean called = false
        Closure fetcher = { Map ctx -> called = true; [errors: [], statusCode: 200, attemptCount: 1, transport: "GRAPHQL"] }
        Map halfWindow = OmsExtractSupport.extractOrders(baseConfig(), "2026-05-01T00:00:00Z", null, null, null, null, null, fetcher)
        assertTrue(halfWindow.errors.any { it.contains("both required") }, halfWindow.errors.toString())
        Map bad = OmsExtractSupport.extractOrders(baseConfig(), "not-a-date", "also-bad", null, null, null, null, fetcher)
        assertEquals(2, bad.errors.count { it.contains("must be a Timestamp") }, bad.errors.toString())
        assertFalse(called, "a rejected request never reaches the page source")
    }
}
