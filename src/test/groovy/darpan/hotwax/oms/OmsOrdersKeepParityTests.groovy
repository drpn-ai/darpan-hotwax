package darpan.hotwax.oms

import darpan.reconciliation.conclusion.ConclusionKeySupport
import darpan.reconciliation.conclusion.ExcludedRecordsSidecar
import darpan.reconciliation.conclusion.RunConclusionSupport
import darpan.reconciliation.source.KeepArtifacts
import darpan.reconciliation.source.KeepLineTranslator
import darpan.reconciliation.source.KeepPlanSupport
import darpan.reconciliation.source.KeepRules
import darpan.reconciliation.source.KeepStepSupport
import darpan.reconciliation.source.SourceFilterSupport
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.xml.XmlSlurper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import org.moqui.context.ExecutionContext
import org.moqui.context.ResourceFacade

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-318 (plan 3b-2b, Task 17). HotWax orders leave Keep to Spark: on HotWax-shaped data, a run's kept records, set-aside
 * records, conclusions and counts are what the read-time filtering gave before.
 *
 * BEFORE is the extract as it filtered (ReadTimeFilterBefore: the old OmsExtractSupport shaper, kept here line for line,
 * through SourceFilterSupport.evaluate). AFTER is the extract as it is now (no filter, isExchangeOrder written, a unit
 * carrying the order fields its Keep reads), followed by darpan's Spark Keep with the rules a run builds for the side: the
 * connector row's declared lines (data/HotWaxConnectorSeedData.xml, KeepLineTranslator.declared) then the tenant's rows
 * (SourceFilterSupport.toKeepRules), the read sent their fields (keepRuleFields) as RunExtractSupport sends them.
 *
 * The AFTER file carries more fields than BEFORE's (the Keep fields join the projection, Ruling 3b2b-4; isExchangeOrder; a
 * unit's copies of order fields): records are compared on BEFORE's fields, and each extra is checked for what it holds.
 * No Moqui: the ExecutionContext is a stub that resolves locations as plain paths. Spark is on this component's runtime
 * classpath only (through darpan), so KeepStepSupport.keptValues starts the session, as ingest's own step does.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OmsOrdersKeepParityTests {

    private final ExecutionContext ec = [getResource: { ->
        [getLocationReference: { String location -> null }] as ResourceFacade
    }] as ExecutionContext

    @TempDir
    File dir

    private static final Map CONFIG = [baseUrl: "https://parity.hotwax.io", authType: "NONE", omsRestSourceConfigId: "PARITY"]
    /** The OMS_GQL connector's keepFieldsBase: what an interactive side projects to. */
    private static final List<String> ORDER_BASE = ["orderId", "orderName", "externalId", "grandTotal", "orderDate", "statusId"]
    private static final Map UNIT_OPTIONS = [emitGrain: "ORDER_LINE_UNIT", applyExchangeExclusion: false, requireShopifyLineId: false]
    private static final List<String> UNIT_KEY = ["omsOrderId", "orderItemSeqId", "unitOrdinal"]

    // ------------------------------------------------------------------------------------------------------------ fixtures

    private static Map order(String id, Map extra = [:]) {
        return [orderId: id, orderName: "#" + id, externalId: "SH" + id, orderTypeId: "SALES_ORDER", statusId: "ORDER_COMPLETED",
                orderDate: 1793548800000L, grandTotal: new BigDecimal("10.00"), productStoreId: "STORE",
                salesChannelEnumId: "WEB_SALES_CHANNEL", hasPaymentPreference: "Y"] + extra
    }

    private static List<Map> exchangeAssoc(String toOrderId) {
        return [[orderId: "x", orderItemSeqId: "01", toOrderId: toOrderId, toOrderItemSeqId: "01", orderItemAssocTypeId: "EXCHANGE"]]
    }

    /** Order grain, as OmsGqlOrderGrainAssembler hands them over. */
    private static List<Map> orders() {
        return [order("O1"),
                order("O2", [salesChannelEnumId: "POS_SALES_CHANNEL"]),
                order("O3").findAll { k, v -> k != "productStoreId" },                     // no store at all
                order("O4", [orderItemAssocs: exchangeAssoc("O1")]),                       // an exchange order
                order("O5", [salesChannelEnumId: "POS_SALES_CHANNEL", orderItemAssocs: exchangeAssoc("O2")]), // exchange and POS
                order("O6", [statusId: "ORDER_CANCELLED"]),
                order("O7", [orderTypeId: "PURCHASE_ORDER"]),                              // not a sales order
                order("O8", [productStoreId: "retail_store"]),
                order("O9", [grandTotal: new BigDecimal("24.00")]),
                order("O10", [productStoreId: "OUTLET"]),
                order("O11", [orderItemAssocs: [[orderItemAssocTypeId: "REPLACEMENT", toOrderId: "O9"]]])]
    }

    /** The tenant's rows as KeepPlanSupport.tenantRuleRows reads them (stored $.records[*] paths, getter shape). */
    private static List<Map<String, Object>> orderRows() {
        return SourceFilterSupport.toRecordFieldRules([
                [sequenceNum: 1, fieldExpression: '$.records[*].salesChannelEnumId', operator: "EXCLUDE_IN", filterValues: "POS_SALES_CHANNEL"],
                [sequenceNum: 2, fieldExpression: '$.records[*].productStoreId', operator: "INCLUDE_IN", filterValues: "STORE,RETAIL_STORE"],
                [sequenceNum: 3, fieldExpression: '$.records[*].statusId', operator: "EXCLUDE_IN", filterValues: "ORDER_CANCELLED"],
                [sequenceNum: 4, fieldExpression: '$.records[*].grandTotal', operator: "EXCLUDE_IN", filterValues: "24.00"],
        ] as List<Map<String, Object>>)
    }

    private static Map unitOrder(String id, List<Map> items, Map extra = [:]) {
        return order(id, [shipGroups: [[shipGroupSeqId: "00001", facilityId: "F1", items: items]]] + extra)
    }

    private static Map item(String seq, String status, int quantity = 1, Map extra = [:]) {
        return [orderItemSeqId: seq, externalId: "L" + seq, quantity: quantity, statusId: status, productId: "P" + seq] + extra
    }

    /** Unit grain (the order-items read): orders with ship groups and items, flattened to one record per unit. */
    private static List<Map> unitOrders() {
        return [unitOrder("U1", [item("01", "ITEM_COMPLETED", 2), item("02", "ITEM_CANCELLED")]),
                unitOrder("U2", [item("01", "ITEM_COMPLETED")], [orderName: "#5"]),
                order("U3", [shipGroups: [[shipGroupSeqId: "00001", facilityId: "F9", items: [item("01", "ITEM_APPROVED")]],
                                          [shipGroupSeqId: "00002", items: [item("02", "ITEM_COMPLETED")]]]]),
                unitOrder("U4", [item("01", "ITEM_COMPLETED")], [orderTypeId: "PURCHASE_ORDER"]),
                unitOrder("U5", [item("01", "ITEM_COMPLETED")], [statusId: "ORDER_CANCELLED"]),
                unitOrder("U6", [item("01", "ITEM_COMPLETED")], [orderDate: 1700000000000L])]
    }

    /** Rules on unit fields, on order fields the unit lacks (orderName, orderDate) and on one both carry (statusId). */
    private static List<Map<String, Object>> unitRows() {
        return SourceFilterSupport.toRecordFieldRules([
                [sequenceNum: 1, fieldExpression: '$.records[*].unitState', operator: "EXCLUDE_IN", filterValues: "CANCELLED"],
                [sequenceNum: 2, fieldExpression: '$.records[*].facilityId', operator: "INCLUDE_IN", filterValues: "F1,F2"],
                [sequenceNum: 3, fieldExpression: '$.records[*].orderName', operator: "EXCLUDE_IN", filterValues: "#5"],
                [sequenceNum: 4, fieldExpression: '$.records[*].statusId', operator: "EXCLUDE_IN", filterValues: "ORDER_CANCELLED"],
                [sequenceNum: 5, fieldExpression: '$.records[*].orderDate', operator: "INCLUDE_PREFIX", filterValues: "1793"],
        ] as List<Map<String, Object>>)
    }

    // ------------------------------------------------------------------------------------------------------------- the tests

    @Test
    void orderGrainKeepsSetsAsideConcludesAndCountsAsTheReadDid() {
        Map before = ReadTimeFilterBefore.orderGrain(orders(), orderRows(), ORDER_BASE)
        Map after = afterKeep("OMS_GQL", orders(), orderRows(), ORDER_BASE, null, "orders.json")

        // kept: the same orders, in the same order, with the same values on every field the read wrote before
        List<Map> kept = after.kept as List<Map>
        assertEquals((before.kept as List<Map>)*.orderId, kept*.orderId)
        assertEquals(before.kept, kept.collect { Map r -> r.subMap(ORDER_BASE) })
        assertTrue(kept.every { it.isExchangeOrder == "N" }, "every kept order is flagged not an exchange")

        // set aside: what the read dropped, under the rule that dropped it (the exchange first, as its bucket came first)
        assertEquals(before.droppedBy, after.setAsideBy)
        assertEquals("BUILT_IN:0", after.setAsideBy.O5, "an exchange order on POS is the exchange's, as before")
        assertFalse(after.setAsideBy.containsKey("O7"), "a non-sales order is still left out by the read, not by Keep")
        assertTrue((after.setAside as List<Map>).every { Map r -> r.keySet().containsAll(ORDER_BASE) }, "set aside with the kept projection")

        // counts: per rule, the exchange count and what was kept
        Map summary = KeepArtifacts.readSummary(after.file as File)
        assertEquals(before.configuredExclusions, summary.configuredExclusions)
        assertEquals([[sequenceNum: 0, label: "isExchangeOrder", setAsideCount: before.exchangeCount]], summary.builtIn)
        assertEquals((before.kept as List).size(), summary.keptCount as int)
        assertEquals(before.exchangeCount, (after.metadata as Map).filters.excludedExchangeOrderCount)
        assertEquals(before.manifest, after.manifest, "the exchange manifest is unchanged")

        // no side file either way (OMS_GQL N), and the conclusions read every order the same
        assertFalse(sidecarOf(after.file as File).exists())
        assertConcludesTheSame(before, after, orders().collect { [orderId: it.orderId] }, ["orderId"])
    }

    /**
     * Review M3: a side whose only Keep rule is the built-in exchange line. The exchanges are set aside and counted as the
     * built-in's, configuredExclusions stays empty, and the side is not unknown when a key is absent, as before.
     */
    @Test
    void anOrderSideWithOnlyTheBuiltInExchangeLineKeepsAsTheReadDid() {
        Map before = ReadTimeFilterBefore.orderGrain(orders(), [], ORDER_BASE)
        Map after = afterKeep("OMS_GQL", orders(), [], ORDER_BASE, null, "orders-built-in-only.json")

        assertEquals((before.kept as List<Map>)*.orderId, (after.kept as List<Map>)*.orderId)
        assertEquals(before.kept, (after.kept as List<Map>).collect { Map r -> r.subMap(ORDER_BASE) })
        assertEquals([O4: "BUILT_IN:0", O5: "BUILT_IN:0"], after.setAsideBy)
        assertEquals(before.droppedBy, after.setAsideBy)
        Map summary = KeepArtifacts.readSummary(after.file as File)
        assertEquals([], summary.configuredExclusions)
        assertEquals([[sequenceNum: 0, label: "isExchangeOrder", setAsideCount: 2]], summary.builtIn)
        assertConcludesTheSame(before, after, orders().collect { [orderId: it.orderId] }, ["orderId"])
        Set<String> keys = orders().collect { it.orderId as String } as Set<String>
        assertFalse(RunConclusionSupport.indexSide([extractFile: after.file, keyFields: ["orderId"]], keys, keys).unknownWhenAbsent as boolean,
                "built-ins alone leave the side known")
    }

    /**
     * Fix round 1 (I1): a side read by the other side's keys still runs Keep. Its read leaves exchanges out itself and says
     * isExchangeOrder N on the rest, so a tenant rule on the field sets nothing aside for want of it.
     */
    @Test
    void aKeyedSideWithAnIsExchangeOrderFilterSetsNothingAsideAsFieldAbsent() {
        List<Map<String, Object>> rows = SourceFilterSupport.toRecordFieldRules([[sequenceNum: 1,
                fieldExpression: '$.records[*].isExchangeOrder', operator: "INCLUDE_IN", filterValues: "N"]] as List<Map<String, Object>>)
        List<String> keys = orders()*.orderId as List<String>
        Map after = afterKeep("OMS_GQL", orders(), rows, ORDER_BASE, OmsExtractSupport.lookupExtractOptions(keys, null), "orders-by-key.json")

        assertEquals([:], after.setAsideBy, "nothing is set aside: every kept order says N")
        assertEquals(["O1", "O2", "O3", "O6", "O8", "O9", "O10", "O11"], (after.kept as List<Map>)*.orderId,
                "every sales order but the exchanges, which the read left out")
        Map rule = (KeepArtifacts.readSummary(after.file as File).configuredExclusions as List<Map>)[0]
        assertEquals([0, 0], [rule.excludedCount, rule.fieldAbsentCount])
    }

    /**
     * DAR-BE-345 (option B). A side whose keepExchangeOrders option is on (Malbon / RAILS R11, "every order HotWax approves
     * reaches NetSuite") compares exchange orders: the built-in exchange line does not apply to it, so they are kept, flagged
     * Y, by its window read and by a re-read by key alike; its tenant rows still apply to them. The read is the same read
     * (only Keep differs), so a side without the option, sharing it, sets them aside as before.
     */
    @Test
    void aSideThatKeepsExchangeOrdersComparesThem() {
        Map keepsThem = [keepExchangeOrders: true]
        Map window = afterKeep("OMS_GQL", orders(), [], ORDER_BASE, null, "orders-with-exchanges.json", keepsThem)
        assertEquals(["O1", "O2", "O3", "O4", "O5", "O6", "O8", "O9", "O10", "O11"], (window.kept as List<Map>)*.orderId)
        assertEquals(["O4", "O5"], (window.kept as List<Map>).findAll { it.isExchangeOrder == "Y" }*.orderId)
        assertEquals([:], window.setAsideBy)
        assertEquals(null, KeepArtifacts.readSummary(window.file as File), "no Keep ran: no built-in line applies and no tenant rows")

        Map ruled = afterKeep("OMS_GQL", orders(), orderRows(), ORDER_BASE, null, "orders-with-exchanges-ruled.json", keepsThem)
        assertTrue((ruled.kept as List<Map>)*.orderId.contains("O4"))
        assertEquals("A:1", ruled.setAsideBy.O5, "an exchange order on POS is the tenant's POS rule's now")

        List<String> keys = orders()*.orderId as List<String>
        Map keyed = afterKeep("OMS_GQL", orders(), [], ORDER_BASE, OmsExtractSupport.lookupExtractOptions(keys, null, true),
                "orders-with-exchanges-by-key.json", keepsThem)
        assertEquals((window.kept as List<Map>)*.orderId, (keyed.kept as List<Map>)*.orderId, "a re-read by key keeps what the window kept")

        Map shared = afterKeep("OMS_GQL", orders(), [], ORDER_BASE, null, "orders-shared-default.json")
        assertEquals([O4: "BUILT_IN:0", O5: "BUILT_IN:0"], shared.setAsideBy, "a side without the option: as before")
    }

    @Test
    void unitGrainKeepsSetsAsideConcludesAndCountsAsTheReadDid() {
        Map before = ReadTimeFilterBefore.unitGrain(unitOrders(), unitRows(), dir)
        Map after = afterKeep("OMS_ORDER_ITEMS_GQL", unitOrders(), unitRows(), null, UNIT_OPTIONS, "order-items.json")
        Set<String> keepFields = after.keepFields as Set<String>

        // kept: the same units; beyond BEFORE's fields each carries only the Keep fields it lacked, copied from its order
        Map<String, Map> byKey = (after.kept as List<Map>).collectEntries { [(unitKey(it)): it] }
        assertEquals((before.kept as List<Map>).collect { unitKey(it) }, (after.kept as List<Map>).collect { unitKey(it) })
        (before.kept as List<Map>).each { Map old -> assertCarriesOnlyItsOrdersKeepFields(old, byKey[unitKey(old)], keepFields) }
        assertTrue(byKey.values().every { it.orderName != null && it.orderDate != null }, "the order fields its Keep reads ride on each unit")

        // set aside: the same units, by the same rule; the unit's own statusId (an item status) is never the order's
        assertEquals(before.droppedBy, after.setAsideBy)
        assertFalse(after.setAsideBy.values().contains("A:4"), "statusId is the unit's: ORDER_CANCELLED on U5's order matches no unit")

        // counts per rule, and what was kept
        Map summary = KeepArtifacts.readSummary(after.file as File)
        assertEquals(before.configuredExclusions, summary.configuredExclusions)
        assertEquals([], summary.builtIn, "no built-in Keep line at unit grain")
        assertEquals((before.kept as List).size(), summary.keptCount as int)

        // the side file (OMS_ORDER_ITEMS_GQL Y): Spark writes the units the read's collector wrote, under the same _excludedBy
        Map oldSide = ExcludedRecordsSidecar.read(before.file as File)
        Map newSide = ExcludedRecordsSidecar.read(after.file as File)
        assertTrue(oldSide.present as boolean)
        assertTrue(newSide.present as boolean)
        assertEquals(oldSide.truncated, newSide.truncated)
        Map<String, Map> newSideByKey = (newSide.records as List<Map>).collectEntries { [(unitKey(it)): it] }
        assertEquals((oldSide.records as List<Map>).collect { unitKey(it) }.sort(), newSideByKey.keySet().sort())
        (oldSide.records as List<Map>).each { Map old -> assertCarriesOnlyItsOrdersKeepFields(old, newSideByKey[unitKey(old)], keepFields) }

        List<Map> everyUnit = unitOrders().findAll { it.orderTypeId == "SALES_ORDER" }.collectMany { Map o ->
            (List<Map>) OmsOrderLineUnitSupport.flattenOrderToUnits(o, [requireShopifyLineId: false]).units
        }
        assertConcludesTheSame(before, after, everyUnit, UNIT_KEY)
    }

    // ------------------------------------------------------------------------------------------------------------- after

    /**
     * The extract as it reads now, then Spark Keep with the side's run-built rules. Returns the extract file, its records
     * kept and set aside (with the rule that set each aside, by key), its metadata and exchange manifest, and the Keep fields.
     */
    private Map afterKeep(String systemEnumId, List<Map> raw, List<Map<String, Object>> rows, List<String> base, Map options,
                          String fileName, Map sideOptions = [:]) {
        List<Map> rules = []
        // the side's own extract options, as a run hands them to KeepPlanSupport (SourceExtractOptionsSupport.runtimeOptions)
        rules.addAll(KeepLineTranslator.declared(connectorRow(systemEnumId).keepLinesJson, sideOptions))
        rules.addAll(SourceFilterSupport.toKeepRules(SourceFilterSupport.parseRules(rows)))
        KeepRules.validate(rules)
        Set<String> keepFields = KeepRules.fields(rules)
        Map sideKeep = [placement   : KeepPlanSupport.SPARK, readFilters: [], rules: rules,
                        writeSidecar: connectorRow(systemEnumId).excludedRecordsSidecar == "Y", fields: keepFields]
        // as RunExtractSupport: the Keep fields join a projecting side's keep fields, and go as keepRuleFields
        List<String> keep = base == null ? null : (base + keepFields).unique()

        File file = new File(dir, fileName)
        Map result = OmsExtractSupport.extractOrdersToFile(CONFIG, "2026-11-01T00:00:00Z", "2026-11-02T00:00:00Z", file, keep, null,
                new ArrayList<String>(keepFields), options, pagesOf(raw))
        assertEquals([], result.errors)
        Map doc = (Map) new JsonSlurper().parse(file)

        KeepStepSupport.keptValues(ec, file, sideKeep, (base == null ? "omsOrderId" : "orderId"))
        List<Map> kept = []
        KeepArtifacts.forEachKeptRecord(file) { Map r -> kept.add(r) }
        List<Map> aside = []
        Map<String, String> setAsideBy = [:]
        KeepArtifacts.forEachSetAsideRecord(file) { Map r, String ruleId ->
            aside.add(r)
            setAsideBy.put(base == null ? unitKey(r) : r.orderId as String, ruleId)
        }
        return [file: file, kept: kept, setAside: aside, setAsideBy: setAsideBy, metadata: doc.metadata, keepFields: keepFields,
                manifest: json(result.exchangeManifest ?: [])]
    }

    /** A page source handing the raw records to the real shaper once, as OmsGqlOrderPageSource does page by page. */
    private static Closure pagesOf(List<Map> raw) {
        return { Map ctx ->
            ((Closure) ctx.pageConsumer).call(OmsExtractSupport.shapePageRecords(raw, (Map) ctx.extractOptions,
                    (Collection) ctx.keepRuleFields, (Set) ctx.keepFieldSet) + [rawCount: raw.size()])
            return [errors: [], statusCode: 200, attemptCount: 1, transport: "GRAPHQL", pagination: [pageCount: 1]]
        }
    }

    // ------------------------------------------------------------------------------------------------------------ helpers

    private static Map connectorRow(String systemEnumId) {
        def row = new XmlSlurper().parse(new File(System.getProperty("user.dir"), "data/HotWaxConnectorSeedData.xml")).'**'
                .find { it.name() == "darpan.reconciliation.SourceSystemConnector" && it.@systemEnumId == systemEnumId }
        return [keepLinesJson: row.@keepLinesJson.toString() ?: null, excludedRecordsSidecar: row.@excludedRecordsSidecar.toString()]
    }

    static String unitKey(Map unit) {
        return UNIT_KEY.collect { unit.get(it)?.toString() }.join("|")
    }

    static Object json(Object value) {
        return new JsonSlurper().parseText(JsonOutput.toJson(value))
    }

    private static File sidecarOf(File extract) {
        return new File(extract.parentFile, ExcludedRecordsSidecar.fileNameFor(extract.name))
    }

    /**
     * Ruling 3b2b-10: the AFTER unit is the BEFORE unit plus, for each Keep field it lacked, the value of that field on its
     * order (the field the old filter view read).
     */
    private static void assertCarriesOnlyItsOrdersKeepFields(Map old, Map now, Set<String> keepFields) {
        assertTrue(now != null, "no AFTER unit for ${unitKey(old)}")
        Map extra = now.findAll { k, v -> !old.containsKey(k) }
        assertEquals(old, now.subMap(old.keySet()), "unit ${unitKey(old)}")
        assertTrue(keepFields.containsAll(extra.keySet()), "only Keep fields are carried: ${extra.keySet()}")
        Map ord = unitOrders().find { it.orderId == old.omsOrderId }
        extra.each { k, v -> assertEquals(json([v: ord.get(k)]).v, v, "${k} on ${unitKey(old)} is its order's") }
    }

    /** RunConclusionSupport reads each key the same from BEFORE's file (and side file) as from AFTER's file and Keep artifacts. */
    /**
     * Each key concludes as the read did, except OC67 (darpan 6257e618): a record the Keep set aside is named in its rows file,
     * so on the Keep path its key reads EXCLUDED, where the old read, which left the record out of the file, said UNKNOWN (a
     * tenant rule and no side file) or ABSENT (built-in rules only). Which keys were set aside is read from the rows file
     * itself, not from the code under test.
     */
    private static void assertConcludesTheSame(Map before, Map after, List<Map> everyRecord, List<String> keyFields) {
        Set<String> keys = everyRecord.collect { ConclusionKeySupport.compareKey(it, keyFields, null) } as Set<String>
        Set<String> firsts = keys.collect { ConclusionKeySupport.firstField(it) } as Set<String>
        Map oldIndex = RunConclusionSupport.indexSide([extractFile: before.file, keyFields: keyFields], keys, firsts)
        Map newIndex = RunConclusionSupport.indexSide([extractFile: after.file, keyFields: keyFields], keys, firsts)
        assertEquals(oldIndex.unknownWhenAbsent, newIndex.unknownWhenAbsent, "unknown when absent")
        Set<String> setAside = [] as Set<String>
        KeepArtifacts.forEachSetAsideRecord((File) after.file) { Map record, String ruleId ->
            String key = ConclusionKeySupport.compareKey(record, keyFields, null)
            if (key) setAside.add(key)
        }
        keys.each { String key ->
            String was = RunConclusionSupport.lookup(oldIndex, key).presence
            String now = RunConclusionSupport.lookup(newIndex, key).presence
            if (setAside.contains(key) && now != "KEPT") assertEquals("EXCLUDED", now, "${key}: the Keep set it aside (OC67)")
            else assertEquals(was, now, key)
        }
        assertTrue(keys.any { RunConclusionSupport.lookup(newIndex, it).presence == "KEPT" })
    }

    // ------------------------------------------------------------------------------------------------------------ before

    /**
     * The HotWax orders extract as it filtered before Task 17, kept line for line from OmsExtractSupport (shapePageRecords,
     * filterComparableOrderRecords, buildFilterMetadata) so the parity is against what the read really did. It writes the
     * extract it wrote: kept records, metadata with configuredExclusions, and at unit grain the side file its collector wrote.
     */
    static class ReadTimeFilterBefore {

        static Map orderGrain(List<Map> raw, List<Map<String, Object>> rows, List<String> keepFields) {
            List<Map<String, Object>> rules = SourceFilterSupport.parseRules(rows)
            List kept = []
            List manifest = []
            Map<String, String> droppedBy = [:]
            int exchangeCount = 0
            Map<Integer, Integer> excludedByRuleCounts = [:], fieldAbsentByRuleCounts = [:]
            raw.each { Map record ->
                if (!OmsExtractSupport.isSalesOrder(record)) {
                    // left out by the read before and after: not Keep
                } else if (OmsExtractSupport.containsExchangeOrderAssociation(record)) {
                    exchangeCount++
                    manifest.add(OmsExtractSupport.exchangeManifestEntry(record))
                    droppedBy.put(record.orderId as String, "BUILT_IN:0")
                } else {
                    Map<String, Object> verdict = SourceFilterSupport.evaluate(record, rules)
                    if (verdict != null) {
                        Integer sequenceNum = (Integer) ((Map) verdict.get("rule")).get("sequenceNum")
                        Map<Integer, Integer> bucket = SourceFilterSupport.REASON_FIELD_ABSENT == verdict.get("reason")
                                ? fieldAbsentByRuleCounts : excludedByRuleCounts
                        bucket.put(sequenceNum, (bucket.get(sequenceNum) ?: 0) + 1)
                        droppedBy.put(record.orderId as String, "A:${sequenceNum}".toString())
                    } else {
                        kept.add(record)
                    }
                }
            }
            Set<String> keep = OmsExtractSupport.normalizeKeepFields(keepFields)
            List written = kept.collect { OmsExtractSupport.trimRecord(it, keep) }
            List configured = configuredExclusions(rules, excludedByRuleCounts, fieldAbsentByRuleCounts)
            File file = File.createTempFile("before-orders-", ".json")
            file.deleteOnExit()
            // the old metadata listed configuredExclusions only when the side had rules (buildFilterMetadata)
            Map filters = [excludedExchangeOrderCount: exchangeCount] + (rules ? [configuredExclusions: configured] : [:])
            file.setText(JsonOutput.toJson([records: written, metadata: [filters: filters]]), "UTF-8")
            return [file: file, kept: OmsOrdersKeepParityTests.json(written), droppedBy: droppedBy, exchangeCount: exchangeCount,
                    configuredExclusions: OmsOrdersKeepParityTests.json(configured), manifest: OmsOrdersKeepParityTests.json(manifest)]
        }

        static Map unitGrain(List<Map> raw, List<Map<String, Object>> rows, File folder) {
            List<Map<String, Object>> rules = SourceFilterSupport.parseRules(rows)
            List kept = []
            Map<String, String> droppedBy = [:]
            Map<Integer, Integer> excludedByRuleCounts = [:], fieldAbsentByRuleCounts = [:]
            Map collector = ExcludedRecordsSidecar.newCollector()
            raw.findAll { OmsExtractSupport.isSalesOrder(it) }.each { Map record ->
                Map flattened = OmsOrderLineUnitSupport.flattenOrderToUnits(record, [requireShopifyLineId: false])
                Map<String, Object> orderScalars = [:]
                record.each { k, v -> if (!(v instanceof Map) && !(v instanceof Collection)) orderScalars.put(k as String, v) }
                for (Object unit : (List) flattened.units) {
                    Map<String, Object> view = new LinkedHashMap<String, Object>(orderScalars)
                    view.putAll((Map) unit)
                    Map<String, Object> verdict = SourceFilterSupport.evaluate(view, rules)
                    if (verdict == null) {
                        kept.add(unit)
                        continue
                    }
                    ExcludedRecordsSidecar.collect(collector, (Map) unit, (Map) verdict.get("rule"))
                    Integer sequenceNum = (Integer) ((Map) verdict.get("rule")).get("sequenceNum")
                    Map<Integer, Integer> bucket = SourceFilterSupport.REASON_FIELD_ABSENT == verdict.get("reason")
                            ? fieldAbsentByRuleCounts : excludedByRuleCounts
                    bucket.put(sequenceNum, (bucket.get(sequenceNum) ?: 0) + 1)
                    droppedBy.put(OmsOrdersKeepParityTests.unitKey((Map) unit), "A:${sequenceNum}".toString())
                }
            }
            List configured = configuredExclusions(rules, excludedByRuleCounts, fieldAbsentByRuleCounts)
            File before = new File(folder, "before")
            before.mkdirs()
            File file = new File(before, "order-items.json")
            file.setText(JsonOutput.toJson([records: kept, metadata: [filters: [configuredExclusions: configured]]]), "UTF-8")
            ExcludedRecordsSidecar.write(new File(before, ExcludedRecordsSidecar.fileNameFor(file.name)), collector, file.name)
            ExcludedRecordsSidecar.discard(collector)
            return [file: file, kept: OmsOrdersKeepParityTests.json(kept), droppedBy: droppedBy, configuredExclusions: OmsOrdersKeepParityTests.json(configured)]
        }

        private static List configuredExclusions(List<Map<String, Object>> rules, Map<Integer, Integer> excluded,
                                                 Map<Integer, Integer> absent) {
            return rules.collect { Map<String, Object> rule ->
                [sequenceNum     : rule.get("sequenceNum"),
                 fieldExpression : rule.get("fieldExpression"),
                 operator        : rule.get("operator"),
                 values          : new ArrayList<String>((List) rule.get("values")),
                 excludedCount   : (excluded?.get(rule.get("sequenceNum")) ?: 0),
                 fieldAbsentCount: (absent?.get(rule.get("sequenceNum")) ?: 0)]
            }
        }
    }
}
