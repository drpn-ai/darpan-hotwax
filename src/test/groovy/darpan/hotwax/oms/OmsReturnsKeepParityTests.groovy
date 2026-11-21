package darpan.hotwax.oms

import darpan.reconciliation.conclusion.ConclusionKeySupport
import darpan.reconciliation.conclusion.ExcludedRecordsSidecar
import darpan.reconciliation.conclusion.RunConclusionSupport
import darpan.reconciliation.source.KeepArtifacts
import darpan.reconciliation.source.KeepPlanSupport
import darpan.reconciliation.source.KeepStepSupport
import darpan.reconciliation.source.SourceFilterSupport
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.xml.XmlSlurper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import org.moqui.context.ExecutionContext
import org.moqui.context.ResourceFacade
import org.moqui.entity.EntityFacade
import org.moqui.entity.EntityFind
import org.moqui.entity.EntityValue

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-318 (plan 3b-2b, Task 18). HotWax returns leave Keep to Spark: on HotWax-shaped returns, a run's kept records,
 * set-aside records, conclusions and counts are what the read-time filtering gave before, for each returns option.
 *
 * BEFORE is the read as it filtered (ReadTimeFilterBefore, kept here line for line): the page source's old scope (a cancelled
 * return left out unless the read asks only for cancelled ones, a return with no Shopify reference unless it keeps them),
 * then OmsReturnsSourceSupport's old pipeline (SourceFilterSupport.evaluate, the join-key fallback, the projection, the
 * metadata with configuredExclusions). AFTER is the read as it is now, on the same scripted OMS (every return written with
 * its omsReturnClass), followed by darpan's own Spark Keep with the plan a run builds for the side:
 * KeepPlanSupport.effectiveSideKeep over the OMS_RETURNS_GQL row (data/HotWaxConnectorSeedData.xml's keepLinesJson and
 * excludedRecordsSidecar, core's filterParameterName) and the extract service as this component declares it, then
 * KeepStepSupport.keptValues, as ingest runs it.
 *
 * Discovery risk 3: the returns side keeps no set-aside records (excludedRecordsSidecar N). Its conclusions read a record the
 * read left out as ABSENT when only the built-in scope left it out, or when every tenant rule reports it dropped nothing, and
 * as UNKNOWN when a tenant rule dropped something (RunConclusionSupport: no side file, so the counts decide). Under Spark the
 * Keep summary's configuredExclusions carry the TENANT rules only, the built-in lines run first and own the records they set
 * aside, and no .excluded.json is written, so every key reads the same. Pinned for each option and rule set below.
 *
 * No Moqui: the ExecutionContext is a stub that resolves locations as plain paths and answers the one connector-row read
 * effectiveSideKeep makes. Spark is on this component's runtime classpath (through darpan).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OmsReturnsKeepParityTests {

    @TempDir
    File dir

    private static final Map CONFIG = [baseUrl: "https://returns-parity.hotwax.io", authType: "BEARER", apiToken: "t"]
    private static final long FROM = 1790812800000L   // 2026-10-01T00:00:00Z
    private static final long THRU = 1790899200000L   // 2026-10-02T00:00:00Z
    /** The OMS_RETURNS_GQL connector's keepFieldsBase: what an interactive side projects to. */
    private static final List<String> BASE = ["returnId", "shopifyReturnId", "externalId", "orderExternalId", "statusId", "entryDate",
                                              "returnTotal", "currencyUomId", "returnChannelEnumId"]
    private static final String SERVICES = "service/reconciliation/HotWaxOmsExtractionServices.xml"

    private final ExecutionContext ec = [getResource: { ->
        [getLocationReference: { String location -> null }] as ResourceFacade
    }, getEntity: { -> connectorRowReads() }] as ExecutionContext

    @BeforeEach
    void freshCaches() {
        OmsGqlSharedRead.reset()
        OmsGqlLookupCache.reset()
    }

    // ------------------------------------------------------------------------------------------------------------ fixtures

    private static Map ret(String id, Map o = [:]) {
        return [returnId: id, statusId: "RETURN_REQUESTED", entryDate: "2026-10-01T10:00:00Z", externalId: "SH" + id,
                currencyUomId: "USD", returnChannelEnumId: "ECOM_RTN_CHANNEL", fromPartyId: "CUST",
                identifications: [[returnIdentificationTypeId: "SHOPIFY_RETURN_ID", idValue: "3744700${id.takeRight(2)}", thruDate: null]]] + o
    }

    private static final Map NO_REF = [externalId: null, identifications: []]
    private static final Map POS = [returnChannelEnumId: "POS_RTN_CHANNEL"]
    private static final Map CANCELLED = [statusId: "RETURN_CANCELLED"]

    /** The window's returns as the OMS answers them, one of each case the read and Keep tell apart. */
    private static final List<Map> RETURNS = [
            ret("R01"),
            ret("R02", POS),                                                     // a tenant rule's
            ret("R03"),                                                          // no items: no store
            ret("R04", CANCELLED),                                               // the built-in's
            ret("R05", CANCELLED + POS),                                         // cancelled and POS: the built-in's, first
            ret("R06", NO_REF + [statusId: "RETURN_COMPLETED", returnChannelEnumId: "HAPPINESS_GUARANTEE"]),
            ret("R07", NO_REF + POS),
            ret("R08", NO_REF + CANCELLED),                                      // cancelled wins the classification
            ret("R09"),                                                          // another store
            ret("R10", [externalId: null]),                                      // no refund yet: the join-key fallback
            ret("R11"),                                                          // a total of 24.00
    ]

    /** returnId -> [orderId, price]; R03 has no items. */
    private static final Map<String, List> ITEMS = [R01: ["ORD_US", "65"], R02: ["ORD_US", "10"], R04: ["ORD_US", "10"],
                                                    R05: ["ORD_US", "10"], R06: ["ORD_US", "30"], R07: ["ORD_US", "5"],
                                                    R08: ["ORD_US", "7"], R09: ["ORD_CA", "65"], R10: ["ORD_US", "12"],
                                                    R11: ["ORD_US", "24.00"]]

    /** The scripted OMS: returns (by status when asked), then the id-keyed lookups the page source makes. */
    private static OmsGqlClient client(boolean realEndpoint) {
        OmsGqlClient c = new OmsGqlClient(CONFIG, { String url, Map headers, String body ->
            Map payload = (Map) new JsonSlurper().parseText(body)
            String root = (payload.query =~ /\{\s*(\w+)\(/)[0][1]
            String q = (payload.variables as Map).q as String
            List<String> asked = q.contains(":") ? (q.substring(q.lastIndexOf(':') + 1).split(",") as List<String>) : []
            List<Map> nodes
            switch (root) {
                case "returns":
                    nodes = q.startsWith("statusId:RETURN_CANCELLED") ? RETURNS.findAll { it.statusId == "RETURN_CANCELLED" } : RETURNS
                    break
                case "returnItems":
                    nodes = asked.findAll { ITEMS[it] }.collect { String id ->
                        [returnId: id, returnItemSeqId: "01", orderId: ITEMS[id][0], orderItemSeqId: "00101", productId: "P1",
                         returnQuantity: 1, receivedQuantity: 0, returnPrice: ITEMS[id][1], returnReasonId: "UNKNOWN",
                         statusId: "RETURN_REQUESTED", returnTypeId: "RTN_REFUND"] }
                    break
                case "orders":
                    nodes = [[orderId: "ORD_US", externalId: "SO1", orderName: "#US1", productStoreId: "STORE_US", grandTotal: "100", identifications: []],
                             [orderId: "ORD_CA", externalId: "SO2", orderName: "#CA1", productStoreId: "STORE_CA", grandTotal: "100", identifications: []]]
                            .findAll { asked.contains(it.orderId) }
                    break
                case "orderItems":
                    nodes = asked.collect { [orderId: it, orderItemSeqId: "00101", externalId: "L-" + it] }
                    break
                case "products":
                    nodes = [[productId: "P1", internalName: "SKU-1"]]
                    break
                case "goodIdentifications":
                    nodes = []
                    break
                default:
                    throw new AssertionError("unexpected GraphQL root ${root}")
            }
            return [statusCode: 200, body: JsonOutput.toJson([data: [(root): [edges: nodes.collect { [node: it] },
                                                                              pageInfo: [hasNextPage: false]]], errors: []])]
        })
        c.sleeper = { long ms -> }
        c.realEndpoint = realEndpoint
        return c
    }

    /** The tenant's rows as KeepPlanSupport.tenantRuleRows reads them (stored $.records[*] paths, getter shape). */
    private static List<Map<String, Object>> rows(List<Map> raw) {
        return SourceFilterSupport.toRecordFieldRules(raw as List<Map<String, Object>>)
    }

    private static final List<Map> TENANT_RULES = [
            [sequenceNum: 1, fieldExpression: '$.records[*].returnChannelEnumId', operator: "EXCLUDE_IN", filterValues: "POS_RTN_CHANNEL"],
            [sequenceNum: 2, fieldExpression: '$.records[*].orderProductStoreId', operator: "INCLUDE_IN", filterValues: "STORE_US"],
            [sequenceNum: 3, fieldExpression: '$.records[*].returnTotal', operator: "EXCLUDE_IN", filterValues: "24"],
    ]

    // ------------------------------------------------------------------------------------------------------------- the tests

    /** Every returns option, with the tenant's rules: what is kept, set aside, counted and concluded is unchanged. */
    @Test
    void eachOptionKeepsSetsAsideConcludesAndCountsAsTheReadDid() {
        [[:], [includeReturnsWithoutShopifyRef: true], [onlyCancelledReturns: true],
         [onlyCancelledReturns: true, includeReturnsWithoutShopifyRef: true]].each { Map options ->
            Map parity = assertParity(options, rows(TENANT_RULES), false)
            assertTrue(parity.after.unknownWhenAbsent as boolean, "${options}: a tenant rule dropped something, so absent is unknown")
        }
        Map dflt = assertParity([:], rows(TENANT_RULES), false)
        assertEquals([R02: "A:1", R03: "A:2", R04: "BUILT_IN:0", R05: "BUILT_IN:0", R06: "BUILT_IN:1", R07: "BUILT_IN:1",
                      R08: "BUILT_IN:0", R09: "A:2", R11: "A:3"], dflt.setAsideBy, "the cancelled and POS return is the built-in's, first")
        assertEquals(["R01", "R10"], dflt.keptIds)
        Map noRef = assertParity([includeReturnsWithoutShopifyRef: true], rows(TENANT_RULES), false)
        assertEquals(["R01", "R06", "R10"], noRef.keptIds, "R8's side keeps the return with no Shopify reference")
        Map onlyCancelled = assertParity([onlyCancelledReturns: true], rows(TENANT_RULES), false)
        assertEquals(["R04", "R08"], onlyCancelled.keptIds, "R16's side keeps the cancelled returns, the tenant's rules still apply")
    }

    /** The shared read (R8 and R11 reading one month once) writes the same file, so Keep gives the same answers. */
    @Test
    void theSharedReadKeepsAsTheReadDid() {
        [[:], [includeReturnsWithoutShopifyRef: true]].each { Map options -> assertParity(options, rows(TENANT_RULES), true) }
    }

    /**
     * Discovery risk 3, no tenant rule: the built-in lines set the cancelled and no-reference returns aside, as the read left
     * them out, and the side's conclusions read them ABSENT, never UNKNOWN; no side file is written.
     */
    @Test
    void builtInsAloneLeaveTheReturnsSideKnown() {
        Map parity = assertParity([:], [], false)
        assertFalse(parity.after.unknownWhenAbsent as boolean, "nothing a tenant rule dropped: absent is absent")
        assertEquals(["R04", "R05", "R06", "R07", "R08"], (parity.setAsideBy as Map).keySet() as List)
        assertEquals("ABSENT", RunConclusionSupport.lookup(parity.after.index, ConclusionKeySupport.compareKey([returnId: "R04"], ["returnId"], null)).presence)
        assertEquals([], KeepArtifacts.readSummary(parity.file as File).configuredExclusions)
    }

    /**
     * Discovery risk 3, a tenant rule that matches only returns the built-ins own: it reports zero, as the read's rule did
     * (those returns never reached it), so the side stays known.
     */
    @Test
    void aRuleMatchingOnlyWhatTheBuiltInsOwnLeavesTheSideKnown() {
        Map parity = assertParity([:], rows([[sequenceNum: 1, fieldExpression: '$.records[*].statusId', operator: "EXCLUDE_IN",
                                              filterValues: "RETURN_CANCELLED"]]), false)
        assertFalse(parity.after.unknownWhenAbsent as boolean)
        Map rule = (KeepArtifacts.readSummary(parity.file as File).configuredExclusions as List<Map>)[0]
        assertEquals([0, 0], [rule.excludedCount, rule.fieldAbsentCount])
    }

    /**
     * The one difference, pinned: the join-key fallback (a blank externalId takes shopifyReturnId) ran AFTER the read's
     * filters and runs BEFORE Spark's Keep. A tenant rule on externalId now sees the filled key. No tenant rule reads it (it
     * is the join key); a rule on any other field sees what the read's filter saw.
     */
    @Test
    void aRuleOnExternalIdSeesTheJoinKeyFallback() {
        List<Map<String, Object>> onKey = rows([[sequenceNum: 1, fieldExpression: '$.records[*].externalId', operator: "INCLUDE_PREFIX",
                                                 filterValues: "SH"]])
        Map before = ReadTimeFilterBefore.read(RETURNS, [:], onKey, BASE)
        Map after = afterKeep([:], onKey, false)
        assertEquals("A:1", before.droppedBy.R10, "the read's filter saw R10's blank key")
        assertEquals("A:1", after.setAsideBy.R10, "and so does Keep: the filled key 374470010 is not an SH one")
        List<Map<String, Object>> exact = rows([[sequenceNum: 1, fieldExpression: '$.records[*].externalId', operator: "INCLUDE_IN",
                                                 filterValues: "SHR01,374470010"]])
        assertEquals("A:1", ReadTimeFilterBefore.read(RETURNS, [:], exact, BASE).droppedBy.R10, "blank before the fallback")
        assertFalse(afterKeep([:], exact, false).setAsideBy.containsKey("R10"), "filled before Keep: kept")
    }

    // ------------------------------------------------------------------------------------------------------------ parity

    /** BEFORE and AFTER for one option and rule set, asserted equal on every point; returns AFTER's answers. */
    private Map assertParity(Map options, List<Map<String, Object>> tenantRows, boolean shared) {
        Map before = ReadTimeFilterBefore.read(RETURNS, options, tenantRows, BASE)
        Map after = afterKeep(options, tenantRows, shared)
        String label = "${options}${shared ? ' shared' : ''}"

        // kept: the same returns, in the same order, with the same values on every field the read wrote before
        List<Map> kept = after.kept as List<Map>
        List<Map> beforeKept = before.kept as List<Map>
        assertEquals(beforeKept*.returnId, kept*.returnId, label)
        assertEquals(beforeKept, kept.withIndex().collect { Map r, int i -> r.subMap(beforeKept[i].keySet()) }, "${label}: kept records")
        // the extras are the Keep fields the run joins to the projection (the tenant rules' fields) and the class the read writes
        Set<String> extras = kept.collectMany { it.keySet() - (BASE + ["items"]) } as Set<String>
        assertTrue(((after.keepFields as Set<String>) + [OmsGqlReturnsPageSource.RETURN_CLASS_FIELD]).containsAll(extras),
                "${label}: only Keep fields join: ${extras}")
        assertTrue(kept.every { it.containsKey(OmsGqlReturnsPageSource.RETURN_CLASS_FIELD) }, "${label}: every window record has its class")

        // set aside: what the read left out or dropped, under the rule that did (the built-in scope first)
        assertEquals(before.droppedBy, after.setAsideBy, "${label}: set aside")

        // counts: per tenant rule, per built-in line, what was kept, and the read's own counts
        Map summary = KeepArtifacts.readSummary(after.file as File)
        assertEquals(before.configuredExclusions, summary.configuredExclusions, "${label}: configuredExclusions")
        assertEquals(before.builtIn, (summary.builtIn as List<Map>).collectEntries { [(it.sequenceNum as int): it.setAsideCount as int] },
                "${label}: built-in set-aside counts")
        assertTrue((summary.builtIn as List<Map>).every { it.label == OmsGqlReturnsPageSource.RETURN_CLASS_FIELD })
        assertEquals((before.kept as List).size(), summary.keptCount as int, label)
        Map afterCounts = new LinkedHashMap((Map) ((Map) after.metadata).filters)
        assertFalse(afterCounts.containsKey("configuredExclusions"), "${label}: the read applies no rule")
        assertEquals(before.serverCounts, afterCounts, "${label}: the read's counts")

        // no side file either way (OMS_RETURNS_GQL N), and the conclusions read every key the same
        assertFalse((after.sideKeep as Map).writeSidecar as boolean)
        assertFalse(new File((after.file as File).parentFile, ExcludedRecordsSidecar.fileNameFor((after.file as File).name)).exists(),
                "${label}: no .excluded.json")
        Map conclusions = assertConcludesTheSame(before.file as File, after.file as File, label)
        return [setAsideBy: after.setAsideBy, keptIds: kept*.returnId, file: after.file, after: conclusions]
    }

    /**
     * The read as it is now, then Spark Keep with the side's run-built plan. Returns the file, its records kept and set aside
     * (rule id by returnId), its metadata, the Keep fields and the plan.
     */
    private Map afterKeep(Map options, List<Map<String, Object>> tenantRows, boolean shared) {
        Map sideKeep = KeepPlanSupport.effectiveSideKeep(ec, tenantRows, connector(), options, returnsServiceDefinition(), "records",
                ['$.records[*].returnId'], true)
        assertEquals(KeepPlanSupport.SPARK, sideKeep.placement, "the gate keeps the returns side in Spark")
        assertEquals([], sideKeep.readFilters, "the read is sent no filters")
        Set<String> keepFields = sideKeep.fields as Set<String>
        assertTrue(keepFields.contains(OmsGqlReturnsPageSource.RETURN_CLASS_FIELD) || options.onlyCancelledReturns == true)
        // as RunExtractSupport: the Keep fields join a projecting side's keep fields (Ruling 3b2b-4)
        List<String> keep = (BASE + keepFields).unique()

        freshCaches()
        File file = new File(dir, "oms-returns-${UUID.randomUUID()}.json")
        Map result = OmsReturnsSourceSupport.extractReturnsToFile(CONFIG, FROM, THRU, file, keep, null,
                [pageFetcher: OmsGqlReturnsPageSource.fetcher(client(shared), options)])
        assertEquals([], result.errors)
        Map doc = (Map) new JsonSlurper().parse(file)

        KeepStepSupport.keptValues(ec, file, sideKeep, "returnId")
        List<Map> kept = []
        KeepArtifacts.forEachKeptRecord(file) { Map r -> kept.add(r) }
        Map<String, String> setAsideBy = new TreeMap<String, String>()
        KeepArtifacts.forEachSetAsideRecord(file) { Map r, String ruleId -> setAsideBy.put(r.returnId as String, ruleId) }
        return [file: file, kept: kept, setAsideBy: setAsideBy, metadata: doc.metadata, keepFields: keepFields, sideKeep: sideKeep]
    }

    // ------------------------------------------------------------------------------------------------------------ helpers

    /** The OMS_RETURNS_GQL row as a run reads it: core's filter parameter, this component's declarations. */
    private static Map connector() {
        return [systemEnumId: "OMS_RETURNS_GQL", filterParameterName: "sourceFilters",
                excludedRecordsSidecar: seedRow("OMS_RETURNS_GQL").@excludedRecordsSidecar.toString()]
    }

    private static def seedRow(String systemEnumId) {
        return new XmlSlurper().parse(new File(System.getProperty("user.dir"), "data/HotWaxConnectorSeedData.xml")).'**'
                .find { it.name() == "darpan.reconciliation.SourceSystemConnector" && it.@systemEnumId == systemEnumId }
    }

    /** extract#HotWaxOmsGqlReturns' in-parameter names as Moqui merges them: its own, then the contract's it implements. */
    private static Map returnsServiceDefinition() {
        def services = new XmlSlurper().parse(new File(System.getProperty("user.dir"), SERVICES)).'**'.findAll { it.name() == "service" }
        def service = services.find { it.@verb == "extract" && it.@noun == "HotWaxOmsGqlReturns" }
        def contract = services.find { "reconciliation.HotWaxOmsExtractionServices.${it.@verb}#${it.@noun}".toString() ==
                service.implements.@service.toString() }
        List<String> names = (contract.'in-parameters'.parameter*.@name*.toString() + service.'in-parameters'.parameter*.@name*.toString()).unique()
        assertTrue(names.containsAll(["omsRestSourceConfigId", "keepRecordFields", "onlyCancelledReturns"]), names.toString())
        return [inParameterNameList: names]
    }

    /** The one entity read effectiveSideKeep makes: the connector row's keepLinesJson (KeepPlanSupport.keepLines). */
    private static EntityFacade connectorRowReads() {
        EntityFind find
        find = [condition: { Object[] args -> find }, useCache: { Object cache -> find },
                one      : { -> [get: { Object key -> key == "keepLinesJson" ? seedRow("OMS_RETURNS_GQL").@keepLinesJson.toString() : null }] as EntityValue }] as EntityFind
        return [find: { String entityName ->
            assert entityName == "darpan.reconciliation.SourceSystemConnector"
            return find
        }] as EntityFacade
    }

    static Object json(Object value) {
        return new JsonSlurper().parseText(JsonOutput.toJson(value))
    }

    /** RunConclusionSupport reads each key the same from BEFORE's file as from AFTER's file and Keep artifacts. */
    private static Map assertConcludesTheSame(File before, File after, String label) {
        List<String> keyFields = ["returnId"]
        Set<String> keys = (RETURNS*.returnId + ["R99"]).collect { ConclusionKeySupport.compareKey([returnId: it], keyFields, null) } as Set<String>
        Set<String> firsts = keys.collect { ConclusionKeySupport.firstField(it) } as Set<String>
        Map oldIndex = RunConclusionSupport.indexSide([extractFile: before, keyFields: keyFields], keys, firsts)
        Map newIndex = RunConclusionSupport.indexSide([extractFile: after, keyFields: keyFields], keys, firsts)
        assertEquals(oldIndex.unknownWhenAbsent, newIndex.unknownWhenAbsent, "${label}: unknown when absent")
        keys.each { String key ->
            assertEquals(RunConclusionSupport.lookup(oldIndex, key).presence, RunConclusionSupport.lookup(newIndex, key).presence, "${label}: ${key}")
        }
        assertTrue(keys.any { RunConclusionSupport.lookup(newIndex, it).presence == "KEPT" })
        return [unknownWhenAbsent: newIndex.unknownWhenAbsent, index: newIndex]
    }

    // ------------------------------------------------------------------------------------------------------------ before

    /**
     * The HotWax returns read as it filtered before Task 18, kept line for line: OmsGqlReturnsPageSource's scope (fetchSlice's
     * keep closure: classify, then the cancelled and no-reference returns left out and counted), then
     * OmsReturnsSourceSupport's pipeline (SourceFilterSupport.evaluate before the join-key fallback, the projection that kept
     * the asked fields and items[], buildMetadata with configuredExclusions). The records themselves are the page source's own
     * (OmsGqlReturnAssembler, unchanged), read for this purpose without the class the read now writes.
     */
    static class ReadTimeFilterBefore {

        static Map read(List<Map> nodes, Map options, List<Map<String, Object>> rows, List<String> keepFields) {
            boolean onlyCancelled = options.onlyCancelledReturns == true
            boolean keepNoShopifyRef = options.includeReturnsWithoutShopifyRef == true || onlyCancelled
            List<Map> served = onlyCancelled ? nodes.findAll { it.statusId == "RETURN_CANCELLED" } : nodes
            Map serverCounts = [excludedNoShopifyRefCount: 0, serverReportedReturnsCount: served.size(), excludedCancelledCount: 0]
            Map<String, String> droppedBy = new TreeMap<String, String>()
            Map<Integer, Integer> builtIn = [:]
            List<String> scoped = []
            served.each { Map n ->
                String verdict = OmsGqlReturnAssembler.classify(n)
                // fetchSlice before Task 18
                if (verdict == OmsGqlReturnAssembler.CANCELLED && onlyCancelled) scoped.add(n.returnId as String)
                else if (onlyCancelled) { /* a status the OMS was not asked for */ }
                else if (verdict == OmsGqlReturnAssembler.CANCELLED) {
                    serverCounts.excludedCancelledCount++
                    droppedBy.put(n.returnId as String, "BUILT_IN:0")
                    builtIn.put(0, (builtIn.get(0) ?: 0) + 1)
                } else if (verdict == OmsGqlReturnAssembler.NO_SHOPIFY_REF && keepNoShopifyRef) scoped.add(n.returnId as String)
                else if (verdict == OmsGqlReturnAssembler.NO_SHOPIFY_REF) {
                    serverCounts.excludedNoShopifyRefCount++
                    droppedBy.put(n.returnId as String, "BUILT_IN:1")
                    builtIn.put(1, (builtIn.get(1) ?: 0) + 1)
                } else scoped.add(n.returnId as String)
            }
            // the built-in lines a run compiles for these options count every set-aside, zero included
            if (!onlyCancelled) {
                builtIn.putIfAbsent(0, 0)
                if (!keepNoShopifyRef) builtIn.putIfAbsent(1, 0)
            }

            // the records the page source built for the scoped returns, in page order
            List<Map> page = []
            OmsGqlSharedRead.reset()
            OmsGqlLookupCache.reset()
            OmsGqlReturnsPageSource.fetchAll(OmsReturnsKeepParityTests.client(false), [fromMillis: OmsReturnsKeepParityTests.FROM,
                    thruMillis: OmsReturnsKeepParityTests.THRU, config: OmsReturnsKeepParityTests.CONFIG,
                    onlyCancelledReturns: onlyCancelled, includeReturnsWithoutShopifyRef: options.includeReturnsWithoutShopifyRef == true,
                    pageConsumer: { List<Map> records -> page.addAll(records) }])
            List<Map> records = page.findAll { scoped.contains(it.returnId) }
                    .collect { Map r -> r.findAll { k, v -> k != OmsGqlReturnsPageSource.RETURN_CLASS_FIELD } as Map }

            // OmsReturnsSourceSupport.extractReturnsInternal's consumePage before Task 18
            List<Map<String, Object>> rules = SourceFilterSupport.parseRules(rows)
            Map<String, Integer> exclusionCounts = [:], fieldAbsentCounts = [:]
            Set<String> keep = new LinkedHashSet<String>(keepFields)
            keep.add("items")
            List written = []
            records.each { Map record ->
                Map<String, Object> verdict = SourceFilterSupport.evaluate(record, rules)
                if (verdict != null) {
                    String key = String.valueOf(((Map) verdict.get("rule")).get("sequenceNum"))
                    Map<String, Integer> bucket = SourceFilterSupport.REASON_FIELD_ABSENT == verdict.get("reason") ? fieldAbsentCounts : exclusionCounts
                    bucket.put(key, (bucket.get(key) ?: 0) + 1)
                    droppedBy.put(record.returnId as String, "A:${key}".toString())
                    return
                }
                OmsReturnsSourceSupport.applyJoinKeyFallback(record)
                written.add(record.findAll { k, v -> keep.contains(k as String) })
            }
            List configured = rules.collect { Map<String, Object> rule ->
                String key = String.valueOf(rule.get("sequenceNum"))
                [sequenceNum     : rule.get("sequenceNum"), fieldExpression: rule.get("fieldExpression"), operator: rule.get("operator"),
                 values          : new ArrayList<String>((List) rule.get("values")), excludedCount: exclusionCounts.get(key) ?: 0,
                 fieldAbsentCount: fieldAbsentCounts.get(key) ?: 0]
            }
            Map filters = new LinkedHashMap(serverCounts)
            if (rules) filters.configuredExclusions = configured
            File file = File.createTempFile("before-returns-", ".json")
            file.deleteOnExit()
            file.setText(JsonOutput.toJson([records: written, metadata: [filters: filters, transport: "GRAPHQL"]]), "UTF-8")
            return [file: file, kept: OmsReturnsKeepParityTests.json(written), droppedBy: droppedBy,
                    configuredExclusions: OmsReturnsKeepParityTests.json(configured), builtIn: builtIn,
                    serverCounts: OmsReturnsKeepParityTests.json(serverCounts)]
        }
    }
}
