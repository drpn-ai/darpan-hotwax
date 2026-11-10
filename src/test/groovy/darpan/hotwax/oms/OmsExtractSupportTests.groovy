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

        Map result = OmsExtractSupport.filterComparableOrderRecords([topLevelAssoc, nestedAssocOnly, keptOrder])

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

    private static List<Map<String, Object>> posChannelRule() {
        return darpan.reconciliation.source.SourceFilterSupport.parseRules([[
                sequenceNum    : 1,
                fieldExpression: "salesChannelEnumId",
                filterValues   : "POS_SALES_CHANNEL",
        ]])
    }

    @Test
    void noExclusionRulesLeavesFilteringExactlyAsBefore() {
        List records = [
                salesOrder("1", "WEB_SALES_CHANNEL"),
                salesOrder("2", "POS_SALES_CHANNEL"),
                [orderId: "3", orderTypeId: "PURCHASE_ORDER"],
        ]

        Map result = OmsExtractSupport.filterComparableOrderRecords(records)

        assertEquals(2, (result.records as List).size())
        assertEquals(1, result.excludedNonSalesOrderCount)
        assertEquals(0, result.excludedExchangeOrderCount)
        assertEquals([:], result.excludedByRuleCounts)
    }

    @Test
    void configuredRuleExcludesMatchingRecordsAndCountsThemByRule() {
        List records = [
                salesOrder("1", "WEB_SALES_CHANNEL"),
                salesOrder("2", "POS_SALES_CHANNEL"),
                salesOrder("3", "pos_sales_channel"),
        ]

        Map result = OmsExtractSupport.filterComparableOrderRecords(records, posChannelRule())

        assertEquals(1, (result.records as List).size())
        assertEquals("1", (result.records as List)[0].orderId)
        assertEquals([(1): 2], result.excludedByRuleCounts)
    }

    @Test
    void includeRuleKeepsOnlyListedChannelsAndCountsAbsentSeparately() {
        // DAR-BE-054. Under INCLUDE_IN the two rejection reasons are different facts: order 2 named
        // a channel this rule does not allow, order 3 named no channel at all. Folding them into one
        // count would let an include rule quietly delete a whole row class and read as a busy rule.
        List<Map<String, Object>> rules = darpan.reconciliation.source.SourceFilterSupport.parseRules([[
                sequenceNum    : 1,
                fieldExpression: "salesChannelEnumId",
                operator       : "INCLUDE_IN",
                filterValues   : "WEB_SALES_CHANNEL",
        ]])
        List records = [
                salesOrder("1", "WEB_SALES_CHANNEL"),
                salesOrder("2", "POS_SALES_CHANNEL"),
                [orderId: "3", orderTypeId: "SALES_ORDER"],
        ]

        Map result = OmsExtractSupport.filterComparableOrderRecords(records, rules)

        assertEquals(1, (result.records as List).size())
        assertEquals("1", (result.records as List)[0].orderId)
        assertEquals([(1): 1], result.excludedByRuleCounts)
        assertEquals([(1): 1], result.fieldAbsentByRuleCounts)
    }

    @Test
    void anExcludeRuleNeverReportsFieldAbsentDrops() {
        // The bucket exists on both modes so a reader need not know which keys apply to which, but
        // under EXCLUDE_IN a record with no value is KEPT, so the bucket is structurally empty.
        List records = [salesOrder("1", "POS_SALES_CHANNEL"), [orderId: "2", orderTypeId: "SALES_ORDER"]]

        Map result = OmsExtractSupport.filterComparableOrderRecords(records, posChannelRule())

        assertEquals(1, (result.records as List).size())
        assertEquals([(1): 1], result.excludedByRuleCounts)
        assertEquals([:], result.fieldAbsentByRuleCounts)
    }

    @Test
    void builtInExclusionsKeepPriorityOverConfiguredOnes() {
        // A non-sales order that also matches a configured rule must stay attributed to the built-in
        // counter, so existing excludedNonSalesOrderCount values never shift when a filter is added.
        List records = [[orderId: "1", orderTypeId: "PURCHASE_ORDER", salesChannelEnumId: "POS_SALES_CHANNEL"]]

        Map result = OmsExtractSupport.filterComparableOrderRecords(records, posChannelRule())

        assertEquals(1, result.excludedNonSalesOrderCount)
        assertEquals([:], result.excludedByRuleCounts)
        assertEquals(0, (result.records as List).size())
    }

    @Test
    void exchangeExclusionKeepsPriorityOverConfiguredRules() {
        List records = [[
                orderId           : "1",
                orderTypeId       : "SALES_ORDER",
                salesChannelEnumId: "POS_SALES_CHANNEL",
                items             : [[orderItemAssocTypeId: "EXCHANGE", toOrderId: "9"]],
        ]]

        Map result = OmsExtractSupport.filterComparableOrderRecords(records, posChannelRule())

        assertEquals(1, result.excludedExchangeOrderCount)
        assertEquals([:], result.excludedByRuleCounts)
        // Must actually fall into the exchange branch, not merely skip the rule branch: the record
        // is dropped from the kept set and shows up on the exchange manifest, not silently kept.
        assertEquals(0, (result.records as List).size())
        List excludedExchangeOrders = result.excludedExchangeOrders as List
        assertEquals(1, excludedExchangeOrders.size())
        assertEquals("1", (excludedExchangeOrders[0] as Map).omsOrderId)
        assertEquals("9", (excludedExchangeOrders[0] as Map).toOrderId)
    }

    @Test
    void recordMissingTheFilteredFieldIsKept() {
        List records = [[orderId: "1", orderTypeId: "SALES_ORDER"]]

        Map result = OmsExtractSupport.filterComparableOrderRecords(records, posChannelRule())

        assertEquals(1, (result.records as List).size())
        assertEquals([:], result.excludedByRuleCounts)
    }

    /**
     * Shaped from a real captured OMS extract (`_entity` = org.apache.ofbiz.order.order.OrderHeader):
     * the exact top-level key set the live orders endpoint returns. The point is the field NAME —
     * a pill offering `productStoreId` against a record that spells it differently, or nests it,
     * would persist a rule that matches nothing and excludes nothing, with no error anywhere.
     */
    private static Map omsOrderHeaderRecord(String orderId, String productStoreId) {
        return [
                _entity                 : "org.apache.ofbiz.order.order.OrderHeader",
                orderId                 : orderId,
                orderTypeId             : "SALES_ORDER",
                orderName               : "SO-${orderId}".toString(),
                externalId              : "ext-${orderId}".toString(),
                statusId                : "ORDER_APPROVED",
                productStoreId          : productStoreId,
                salesChannelEnumId      : "WEB_SALES_CHANNEL",
                originFacilityId        : "WH1",
                grandTotal              : 10,
                currencyUom             : "USD",
                presentmentCurrencyUom  : "USD",
        ]
    }

    @Test
    void configuredRuleExcludesByProductStoreIdOnRealOrderShape() {
        List records = [
                omsOrderHeaderRecord("1", "STORE"),
                omsOrderHeaderRecord("2", "RETAIL_STORE"),
                omsOrderHeaderRecord("3", "retail_store"),
        ]

        List<Map<String, Object>> rules = darpan.reconciliation.source.SourceFilterSupport.parseRules([[
                sequenceNum    : 1,
                fieldExpression: "productStoreId",
                filterValues   : "RETAIL_STORE",
        ]])

        Map result = OmsExtractSupport.filterComparableOrderRecords(records, rules)

        // Values match case-insensitively, so both spellings of the excluded store go.
        assertEquals(1, (result.records as List).size())
        assertEquals("1", (result.records as List)[0].orderId)
        assertEquals([(1): 2], result.excludedByRuleCounts)
    }

    @Test
    void productStoreIdPillJsonPathSurvivesTheRealDispatchChain() {
        // End-to-end over the chain production actually uses for the pill the rules board writes:
        //   stored `$.records[*].productStoreId`
        //     -> toRecordFieldRules  (dispatch: AutomationRuntimeSupport / ReconciliationSavedRunSupport)
        //     -> parseRules          (inside the extractor)
        //     -> evaluate            (scans TOP-LEVEL record keys)
        // evaluate never reduces anything itself, so skipping the dispatch step is what would
        // silently match nothing. This asserts the pill's stored path lands on the real record key.
        List<Map<String, Object>> dispatched = darpan.reconciliation.source.SourceFilterSupport
                .toRecordFieldRules([[
                        sequenceNum    : 1,
                        fieldExpression: "\$.records[*].productStoreId",
                        filterValues   : "STORE",
                ] as Map<String, Object>])
        assertEquals("productStoreId", dispatched[0].fieldExpression)

        Map result = OmsExtractSupport.filterComparableOrderRecords(
                [omsOrderHeaderRecord("1", "STORE"), omsOrderHeaderRecord("2", "OTHER_STORE")],
                darpan.reconciliation.source.SourceFilterSupport.parseRules(dispatched))

        assertEquals(1, (result.records as List).size())
        assertEquals("2", (result.records as List)[0].orderId)
        assertEquals([(1): 1], result.excludedByRuleCounts)
    }

    @Test
    void salesOrderFilterIsUnchangedWhenOptionsAbsent() {
        List records = [
                [orderId: "SO-1", orderTypeId: "SALES_ORDER"],
                [orderId: "SO-2", orderTypeId: "SALES_ORDER",
                 itemAssocs: [[orderItemAssocTypeId: "EXCHANGE", toOrderId: "SO-1"]]],
                [orderId: "TO-1", orderTypeId: "TRANSFER_ORDER"],
        ]

        Map<String, Object> result = OmsExtractSupport.filterComparableOrderRecords(records)

        assertEquals(["SO-1"], (result.records as List).collect { it.orderId })
        assertEquals(1, result.excludedNonSalesOrderCount)
        assertEquals(1, result.excludedExchangeOrderCount)
    }

    @Test
    void configuredExclusionsAppearInRequestMetadataWithCounts() {
        Map<String, Object> filters = OmsExtractSupport.buildFilterMetadata(
                4, 2, false, posChannelRule(), [(1): 7], [:])

        assertEquals("SALES_ORDER", filters.requiredOrderTypeId)
        assertEquals(4, filters.excludedNonSalesOrderCount)
        assertEquals(2, filters.excludedExchangeOrderCount)

        List configured = filters.configuredExclusions as List
        assertEquals(1, configured.size())
        assertEquals(1, configured[0].sequenceNum)
        assertEquals("salesChannelEnumId", configured[0].fieldExpression)
        assertEquals("EXCLUDE_IN", configured[0].operator)
        assertEquals(["POS_SALES_CHANNEL"], configured[0].values)
        assertEquals(7, configured[0].excludedCount)
        // Always present, structurally zero on an exclude rule.
        assertEquals(0, configured[0].fieldAbsentCount)
    }

    @Test
    void metadataOmitsConfiguredExclusionsWhenNoRulesAreSet() {
        Map<String, Object> filters = OmsExtractSupport.buildFilterMetadata(1, 0, false, [], [:], [:])

        assertFalse(filters.containsKey("configuredExclusions"))
    }

    @Test
    void ruleThatMatchedNothingStillReportsAZeroCount() {
        // An operator who configured an exclusion needs to see it ran and matched nothing, rather
        // than see the rule vanish from metadata and wonder whether it was applied at all.
        Map<String, Object> filters = OmsExtractSupport.buildFilterMetadata(0, 0, false, posChannelRule(), [:], [:])

        assertEquals(0, (filters.configuredExclusions as List)[0].excludedCount)
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
        assertNull(options.emitGrain)
        assertEquals(true, options.requireShopifyLineId)
        assertEquals(false, OmsExtractSupport.normalizeExtractOptions([applyExchangeExclusion: false]).applyExchangeExclusion)
        assertEquals(false, OmsExtractSupport.normalizeExtractOptions([requireShopifyLineId: "N"]).requireShopifyLineId)
    }

    /** DAR-BE-205 (gorjana R10): a lookup of approved exchange orders came back empty, so no facility rule could match. */
    @Test
    void onlyAKeyedConclusionLookupReadsExchangeOrders() {
        assertEquals([applyExchangeExclusion: false], OmsExtractSupport.lookupExtractOptions(["M1214942"], true))
        assertEquals([applyExchangeExclusion: false], OmsExtractSupport.lookupExtractOptions(["M1214942"], "Y"))
        // An audit re-check by key sends no flag: its comparison still leaves exchanges out.
        assertNull(OmsExtractSupport.lookupExtractOptions(["M1214942"], null))
        assertNull(OmsExtractSupport.lookupExtractOptions(["M1214942"], false))
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

    @Test
    void malformedExcludeFilterFailsBeforeThePageSourceIsAsked() {
        boolean called = false
        Closure fetcher = { Map ctx -> called = true; [errors: []] }
        Map result = OmsExtractSupport.extractOrders(baseConfig(), "2026-05-01T00:00:00Z", "2026-05-01T01:00:00Z",
                null, null, [[sequenceNum: 1, fieldExpression: "", filterValues: "X"]], null, fetcher)
        assertTrue(result.errors as boolean)
        assertFalse(called)
    }
}
