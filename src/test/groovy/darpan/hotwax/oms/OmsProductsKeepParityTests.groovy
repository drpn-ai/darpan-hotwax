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
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-318 (plan 3b-2b, Task 19). HotWax products leave Keep to Spark: on HotWax-shaped products, a run's kept records,
 * set-aside records, side file, conclusions and counts are what the read-time filtering gave before.
 *
 * BEFORE is the products read as it filtered (ReadTimeFilterBefore, kept here line for line): both sweeps, the records
 * (OmsGqlProductsPageSource.buildRecords, unchanged), SourceFilterSupport.applyToRecords' loop over them (evaluate, the
 * collector, the per-rule counts), the metadata with configuredExclusions, and the side file OmsScriptServiceSupport.runExtract
 * wrote from the collector when it held a record. AFTER is the read as it is now (every record written), followed by darpan's
 * own Spark Keep with the plan a run builds for the side: KeepPlanSupport.effectiveSideKeep over the OMS_PRODUCTS_GQL row
 * (core's filterParameterName, this component's excludedRecordsSidecar Y, no keepLinesJson) and the extract service as this
 * component declares it, then KeepStepSupport.keptValues, as ingest runs it.
 *
 * Built-in drops: none. Which products are records at all (the sellable ones, plus one not-selling product per NetSuite id no
 * sellable product holds) is the read's grain, unchanged, and was never a filter: nothing counted it and no side file held it.
 *
 * Discovery risk 3 does not apply: the products side kept its set-aside records before (the read's side file) and keeps them
 * now (Spark writes the side file, the row saying Y), so its conclusions read a set-aside product as set aside, not unknown.
 * Pinned below with the side file compared record for record.
 *
 * No Moqui: the ExecutionContext is a stub that resolves locations as plain paths and answers the one connector-row read
 * effectiveSideKeep makes. Spark is on this component's runtime classpath (through darpan).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OmsProductsKeepParityTests {

    @TempDir
    File dir

    private static final Map CONFIG = [baseUrl: "https://products-parity.hotwax.io", authType: "BEARER", apiToken: "t"]
    private static final String SERVICES = "service/reconciliation/HotWaxOmsExtractionServices.xml"

    private final ExecutionContext ec = [getResource: { ->
        [getLocationReference: { String location -> null }] as ResourceFacade
    }, getEntity: { -> connectorRowReads() }] as ExecutionContext

    // ------------------------------------------------------------------------------------------------------------ fixtures

    private static Map product(String id, String type, Map o = [:]) {
        return [productId: id, internalName: "SKU-${id}".toString(), productTypeId: type, isVirtual: "N", isVariant: "Y",
                createdDate: "2024-10-04T07:13:41Z", salesDiscontinuationDate: null] + o
    }

    /** The catalogue as the OMS answers it, one of each case the read and Keep tell apart. */
    private static final List<Map> PRODUCTS = [
            product("P01", "FINISHED_GOOD"),                                                // kept
            product("P02", "FINISHED_GOOD"),                                                // no NetSuite id: HOTWAX-ONLY, kept
            product("P03", "FINISHED_GOOD"),                                                // shares 1003, owns it: rule 3
            product("P04", "MARKETING_PKG_PICK"),                                           // shares 1003: DUPLICATE, rule 3
            product("P05", "FINISHED_GOOD", [internalName: "Straße\tGold ı", salesDiscontinuationDate: "2025-01-01T00:00:00Z"]),
            product("P06", "FINISHED_GOOD", [isVirtual: "Y", isVariant: "N"]),             // virtual: sellable N, rule 1
            product("P07", null),                                                           // no type: sellable, rule 2's absent
            product("P08", "DIGITAL_GOOD"),                                                 // not selling, no NetSuite id: no record
            product("P09", "FINISHED_GOOD", [salesDiscontinuationDate: "2025-01-01T00:00:00Z"]), // 1001 is held by P01: no record
    ]

    private static final List<Map> IDENTS = [
            [productId: "P01", idValue: "1001"], [productId: "P03", idValue: "1003"], [productId: "P04", idValue: "1003"],
            [productId: "P05", idValue: "1005"], [productId: "P06", idValue: "1006"], [productId: "P07", idValue: "1007"],
            [productId: "P09", idValue: "1001"], [productId: "P02", idValue: "1002", thruDate: "2025-03-01T00:00:00Z"],
    ]

    /** The scripted OMS: the two sweeps the read makes, one page each. */
    private static OmsGqlClient client() {
        OmsGqlClient c = new OmsGqlClient(CONFIG, { String url, Map headers, String body ->
            String root = body.contains("goodIdentifications(") ? "goodIdentifications" : "products"
            List<Map> nodes = root == "products" ? PRODUCTS : IDENTS
            return [statusCode: 200, body: JsonOutput.toJson([data: [(root): [edges: nodes.collect { [node: it] },
                                                                              pageInfo: [hasNextPage: false]]]])]
        })
        c.sleeper = { long ms -> }
        return c
    }

    /** The tenant's rows as KeepPlanSupport.tenantRuleRows reads them (stored $.records[*] paths, getter shape). */
    private static List<Map<String, Object>> rows(List<Map> raw) {
        return SourceFilterSupport.toRecordFieldRules(raw as List<Map<String, Object>>)
    }

    /** R22's rule, an allowlist on a field one record lacks, and a rule on a boolean (Groovy text "true"). */
    private static final List<Map> TENANT_RULES = [
            [sequenceNum: 1, fieldExpression: '$.records[*].sellable', operator: "EXCLUDE_IN", filterValues: "N"],
            [sequenceNum: 2, fieldExpression: '$.records[*].productTypeId', operator: "INCLUDE_IN", filterValues: "FINISHED_GOOD,MARKETING_PKG_PICK"],
            [sequenceNum: 3, fieldExpression: '$.records[*].duplicateNetsuiteId', operator: "EXCLUDE_IN", filterValues: "true"],
    ]

    // ------------------------------------------------------------------------------------------------------------- the tests

    /** The tenant's rules: what is kept, set aside, written to the side file, counted and concluded is unchanged. */
    @Test
    void theTenantsRulesKeepSetAsideConcludeAndCountAsTheReadDid() {
        Map parity = assertParity(rows(TENANT_RULES))
        assertEquals(["1001", "HOTWAX-ONLY:P02"], parity.keptKeys)
        assertEquals(["1003": "A:3", "1005": "A:1", "1006": "A:1", "1007": "A:2", "DUPLICATE:1003:P04": "A:3"], parity.setAsideBy,
                "R22 owns the not-selling products, the allowlist the product with no type, the boolean rule both holders of 1003")
        Map rule2 = (KeepArtifacts.readSummary(parity.file as File).configuredExclusions as List<Map>).find { it.sequenceNum == 2 }
        assertEquals([0, 1], [rule2.excludedCount, rule2.fieldAbsentCount], "P07 has no type: field absent, as the read counted it")
        assertEquals("EXCLUDED", presence(parity, "1005"), "the side file names what R22 set aside, so a cause can name it")
    }

    /** R22 alone, the live rule: the not-selling products are set aside and written to the side file. */
    @Test
    void r22AloneSetsAsideTheNotSellingProducts() {
        Map parity = assertParity(rows([TENANT_RULES[0]]))
        assertEquals(["1005": "A:1", "1006": "A:1"], parity.setAsideBy)
        assertEquals(["1001", "HOTWAX-ONLY:P02", "1003", "DUPLICATE:1003:P04", "1007"], parity.keptKeys, "in productId order, as the read wrote them")
    }

    /** A rule that drops nothing: zero counts, no side file either way, and the side reads as known. */
    @Test
    void aRuleThatDropsNothingWritesNoSideFile() {
        Map parity = assertParity(rows([[sequenceNum: 1, fieldExpression: '$.records[*].sellable', operator: "EXCLUDE_IN", filterValues: "X"]]))
        assertEquals([:], parity.setAsideBy)
        assertFalse(parity.unknownWhenAbsent as boolean)
    }

    /** No rule at all: the read wrote no counts and Spark runs nothing, so no Keep artifact is written. */
    @Test
    void noRuleRunsNoKeep() {
        Map parity = assertParity([])
        assertEquals(7, (parity.keptKeys as List).size(), "every record the read builds, all kept")
        assertNull(KeepArtifacts.readSummary(parity.file as File), "no rule, no .keep.json")
        assertFalse(parity.unknownWhenAbsent as boolean)
    }

    // ------------------------------------------------------------------------------------------------------------ parity

    /** BEFORE and AFTER for one rule set, asserted equal on every point; returns AFTER's answers. */
    private Map assertParity(List<Map<String, Object>> tenantRows) {
        File run = new File(dir, UUID.randomUUID().toString())
        Map before = ReadTimeFilterBefore.read(client(), tenantRows, new File(run, "before"))
        Map after = afterKeep(tenantRows, new File(run, "after"))

        // kept: the same records, in the same order, with the same values on every field (the read projects nothing)
        assertEquals(before.kept, json(after.kept), "kept records")
        // set aside: what the read dropped, under the rule that did
        assertEquals(before.droppedBy, after.setAsideBy, "set aside")

        // counts: per tenant rule (no built-in line), what was kept, and the read's own metadata
        Map summary = KeepArtifacts.readSummary(after.file as File)
        if (tenantRows) {
            assertEquals(before.configuredExclusions, summary.configuredExclusions, "configuredExclusions")
            assertEquals([], summary.builtIn, "the products read declares no built-in Keep line")
            assertEquals((before.kept as List).size(), summary.keptCount as int)
        }
        Map beforeMetadata = new LinkedHashMap((Map) before.metadata)
        beforeMetadata.remove("filters")
        assertEquals(beforeMetadata, after.metadata, "the read's metadata, less the counts Keep now reports")
        assertFalse(((Map) after.metadata).containsKey("filters"), "the read applies no rule")

        // the side file (OMS_PRODUCTS_GQL Y): Spark writes the products the read's collector wrote, under the same _excludedBy
        assertTrue((after.sideKeep as Map).writeSidecar as boolean, "the row says Y, so Spark writes the side file")
        Map oldSide = ExcludedRecordsSidecar.read(before.file as File)
        Map newSide = ExcludedRecordsSidecar.read(after.file as File)
        assertEquals(oldSide.present, newSide.present, "a side file exactly when the read wrote one")
        if (oldSide.present) {
            assertEquals([oldSide.total, oldSide.truncated, oldSide.sourceFileName], [newSide.total, newSide.truncated, newSide.sourceFileName])
            assertEquals(json(oldSide.records), json(newSide.records), "the side file's records, field for field")
        }

        Map conclusions = assertConcludesTheSame(before.file as File, after.file as File)
        return [setAsideBy: after.setAsideBy, keptKeys: (after.kept as List<Map>)*.nsKey, file: after.file,
                unknownWhenAbsent: conclusions.unknownWhenAbsent, index: conclusions.index]
    }

    /**
     * The read as it is now, then Spark Keep with the side's run-built plan. Returns the file, its records kept and set aside
     * (rule id by nsKey), its metadata and the plan.
     */
    private Map afterKeep(List<Map<String, Object>> tenantRows, File out) {
        Map sideKeep = KeepPlanSupport.effectiveSideKeep(ec, tenantRows, connector(), [:], productsServiceDefinition(), "records",
                ['$.records[*].nsKey'], true)
        assertEquals(KeepPlanSupport.SPARK, sideKeep.placement, "the gate keeps the products side in Spark")
        assertEquals([], sideKeep.readFilters, "the read is sent no filters")

        out.mkdirs()
        File file = new File(out, "oms-gql-products.json")
        Map result = OmsGqlProductsPageSource.extractToFile(client(), file)
        assertEquals([], result.errors)
        Map doc = (Map) new JsonSlurper().parse(file)

        KeepStepSupport.keptValues(ec, file, sideKeep, "nsKey")
        List<Map> kept = []
        KeepArtifacts.forEachKeptRecord(file) { Map r -> kept.add(r) }
        Map<String, String> setAsideBy = new TreeMap<String, String>()
        KeepArtifacts.forEachSetAsideRecord(file) { Map r, String ruleId -> setAsideBy.put(r.nsKey as String, ruleId) }
        return [file: file, kept: kept, setAsideBy: setAsideBy, metadata: doc.metadata, sideKeep: sideKeep]
    }

    // ------------------------------------------------------------------------------------------------------------ helpers

    /** The OMS_PRODUCTS_GQL row as a run reads it: core's filter parameter, this component's declarations. */
    private static Map connector() {
        return [systemEnumId: "OMS_PRODUCTS_GQL", filterParameterName: "sourceFilters",
                excludedRecordsSidecar: seedRow().@excludedRecordsSidecar.toString(),
                keepLinesJson: seedRow().@keepLinesJson.toString()]
    }

    private static def seedRow() {
        return new XmlSlurper().parse(new File(System.getProperty("user.dir"), "data/HotWaxConnectorSeedData.xml")).'**'
                .find { it.name() == "darpan.reconciliation.SourceSystemConnector" && it.@systemEnumId == "OMS_PRODUCTS_GQL" }
    }

    /** extract#HotWaxOmsGqlProducts' in-parameter names as Moqui merges them: the contract's it implements, then its own. */
    private static Map productsServiceDefinition() {
        def services = new XmlSlurper().parse(new File(System.getProperty("user.dir"), SERVICES)).'**'.findAll { it.name() == "service" }
        def service = services.find { it.@verb == "extract" && it.@noun == "HotWaxOmsGqlProducts" }
        def contract = services.find { "reconciliation.HotWaxOmsExtractionServices.${it.@verb}#${it.@noun}".toString() ==
                service.implements.@service.toString() }
        List<String> names = (contract.'in-parameters'.parameter*.@name*.toString() + service.'in-parameters'.parameter*.@name*.toString()).unique()
        assertTrue(names.containsAll(["omsRestSourceConfigId", "keepRecordFields", "lookupKeys"]), names.toString())
        return [inParameterNameList: names]
    }

    /** The one entity read effectiveSideKeep makes: the connector row's keepLinesJson, which this row does not set. */
    private static EntityFacade connectorRowReads() {
        EntityFind find
        find = [condition: { Object[] args -> find }, useCache: { Object cache -> find },
                one      : { -> [get: { Object key -> key == "keepLinesJson" ? (seedRow().@keepLinesJson.toString() ?: null) : null }] as EntityValue }] as EntityFind
        return [find: { String entityName ->
            assert entityName == "darpan.reconciliation.SourceSystemConnector"
            return find
        }] as EntityFacade
    }

    static Object json(Object value) {
        return new JsonSlurper().parseText(JsonOutput.toJson(value))
    }

    private static String presence(Map parity, String nsKey) {
        return RunConclusionSupport.lookup(parity.index, ConclusionKeySupport.compareKey([nsKey: nsKey], ["nsKey"], null)).presence
    }

    /** RunConclusionSupport reads each key the same from BEFORE's file and side file as from AFTER's file and Keep artifacts. */
    private static Map assertConcludesTheSame(File before, File after) {
        List<String> keyFields = ["nsKey"]
        List<String> every = ["1001", "HOTWAX-ONLY:P02", "1003", "DUPLICATE:1003:P04", "1005", "1006", "1007", "HOTWAX-ONLY:P08", "9999"]
        Set<String> keys = every.collect { ConclusionKeySupport.compareKey([nsKey: it], keyFields, null) } as Set<String>
        Set<String> firsts = keys.collect { ConclusionKeySupport.firstField(it) } as Set<String>
        Map oldIndex = RunConclusionSupport.indexSide([extractFile: before, keyFields: keyFields], keys, firsts)
        Map newIndex = RunConclusionSupport.indexSide([extractFile: after, keyFields: keyFields], keys, firsts)
        assertEquals(oldIndex.unknownWhenAbsent, newIndex.unknownWhenAbsent, "unknown when absent")
        keys.each { String key ->
            Map was = RunConclusionSupport.lookup(oldIndex, key), now = RunConclusionSupport.lookup(newIndex, key)
            assertEquals(was.presence, now.presence, key)
            assertEquals(json(was.record), json(now.record), "${key}: the record a conclusion reads")
        }
        assertTrue(keys.any { RunConclusionSupport.lookup(newIndex, it).presence == "KEPT" })
        return [unknownWhenAbsent: newIndex.unknownWhenAbsent, index: newIndex]
    }

    // ------------------------------------------------------------------------------------------------------------ before

    /**
     * The HotWax products read as it filtered before Task 19, kept line for line: OmsGqlProductsPageSource.extractToFile (both
     * sweeps, buildRecords, the rules over the records, the metadata with configuredExclusions only when rules were sent),
     * SourceFilterSupport.applyToRecords' loop (evaluate, the first rejecting rule owns the record and its count, the
     * collector), and OmsScriptServiceSupport.runExtract's side file (ExcludedRecordsSidecar.writeBeside: only when the
     * collector holds a record).
     */
    static class ReadTimeFilterBefore {

        static Map read(OmsGqlClient client, List<Map<String, Object>> rows, File dir) {
            Map pagination = [calls: 0]
            List<Map> products = OmsGqlProductsPageSource.sweep(client, OmsGqlQueries.productsSweepDocument(), "products", null, pagination)
            List<Map> ids = OmsGqlProductsPageSource.sweep(client, OmsGqlQueries.netsuiteIdsSweepDocument(), "goodIdentifications",
                    OmsGqlQueries.NETSUITE_PRODUCT_ID_FILTER, pagination)
            Map built = OmsGqlProductsPageSource.buildRecords(products, ids)

            // SourceFilterSupport.applyToRecords
            List<Map<String, Object>> rules = SourceFilterSupport.parseRules(rows)
            List<Map> kept = []
            List configured = null
            Map<String, String> droppedBy = new TreeMap<String, String>()
            Map collector = null
            if (!rules) {
                kept.addAll((List<Map>) built.records)
            } else {
                collector = ExcludedRecordsSidecar.newCollector()
                Map<String, Integer> exclusionCounts = [:], fieldAbsentCounts = [:]
                ((List<Map>) built.records).each { Map record ->
                    Map<String, Object> verdict = SourceFilterSupport.evaluate(record, rules)
                    if (verdict == null) {
                        kept.add(record)
                        return
                    }
                    ExcludedRecordsSidecar.collect(collector, record, (Map) verdict.get("rule"))
                    String key = String.valueOf(((Map) verdict.get("rule")).get("sequenceNum"))
                    Map<String, Integer> bucket = SourceFilterSupport.REASON_FIELD_ABSENT == verdict.get("reason") ? fieldAbsentCounts : exclusionCounts
                    bucket.put(key, (bucket.get(key) ?: 0) + 1)
                    droppedBy.put(record.nsKey as String, "A:${key}".toString())
                }
                configured = rules.collect { Map<String, Object> rule ->
                    String key = String.valueOf(rule.get("sequenceNum"))
                    [sequenceNum     : rule.get("sequenceNum"), fieldExpression: rule.get("fieldExpression"), operator: rule.get("operator"),
                     values          : new ArrayList<String>((List) rule.get("values")), excludedCount: exclusionCounts.get(key) ?: 0,
                     fieldAbsentCount: fieldAbsentCounts.get(key) ?: 0]
                }
                ExcludedRecordsSidecar.flush(collector)
            }

            Map metadata = (Map) built.metadata + [identificationsRead: ids.size(), gqlCalls: pagination.calls]
            if (configured != null) metadata.filters = [configuredExclusions: configured]
            dir.mkdirs()
            File file = new File(dir, "oms-gql-products.json")
            file.setText(JsonOutput.toJson([records: kept, metadata: metadata]), "UTF-8")
            // OmsScriptServiceSupport.runExtract -> ExcludedRecordsSidecar.writeBeside
            if (collector != null && ((collector.total ?: 0) as int) > 0) {
                ExcludedRecordsSidecar.write(new File(dir, ExcludedRecordsSidecar.fileNameFor(file.name)), collector, file.name)
            }
            ExcludedRecordsSidecar.discard(collector)
            return [file: file, kept: json(kept), droppedBy: droppedBy, configuredExclusions: json(configured), metadata: json(metadata)]
        }
    }
}
