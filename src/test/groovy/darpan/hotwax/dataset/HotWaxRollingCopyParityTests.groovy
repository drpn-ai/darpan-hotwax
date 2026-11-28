package darpan.hotwax.dataset

import darpan.dataset.DatasetRefreshSupport
import darpan.dataset.DatasetSupport
import darpan.dataset.KeptCopyStore
import darpan.dataset.KeptCopySupport

import darpan.facade.common.DataManagerSupport
import darpan.facade.common.TenantAccessSupport
import darpan.facade.reconciliation.RunExtractSupport
import darpan.reconciliation.automation.AutomationExecutionSupport
import darpan.reconciliation.automation.SourceSystemConnectorSupport
import darpan.reconciliation.conclusion.ExcludedRecordsSidecar
import darpan.reconciliation.conclusion.RunConclusionSupport
import darpan.reconciliation.core.ReconciliationServices
import darpan.reconciliation.source.KeepArtifacts
import darpan.reconciliation.source.KeepStepSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ArtifactExecutionInfo
import org.moqui.context.ExecutionContext

import java.sql.Timestamp
import java.time.Instant

import static org.junit.jupiter.api.Assertions.assertArrayEquals
import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-318 plan 3b-1, Tasks 2 and 3 (in darpan-hotwax since ruling P3b1-9: these serve HotWax endpoints, so they run the
 * real HotWax projection service, project#HotWaxOmsKeptRecords, which darpan core reaches only through the OMS rows' data).
 * HotWax returns kept as a ROLLING copy: the whole window by entryDate, re-read in full each
 * day, and a run served from it is the run read live.
 *
 * The fixture is 20 synthetic returns (R001..R020) built in this class, entryDate-ordered around the window
 * [2026-11-10T08:00Z, 2026-11-11T08:00Z): R004 exactly at the start (in), R017 exactly at the end (out), two of a channel a
 * source filter excludes, and a few days before and after. The stub extractor stands in for extract#HotWaxOmsGqlReturns for
 * the refresh and for the live read, so no OMS is ever called. DAR-BE-318 (Task 18): the returns read takes no source
 * filters, so it drops nothing and writes each return's omsReturnClass; Spark Keep sets the filtered channel aside.
 *
 * The returns carry entryDate in epoch milliseconds, as the GraphQL extract writes them (OmsGqlReturnAssembler). The
 * connector row is the seeded one (Task 2b, ruling 3b1-9): OMS_RETURNS_GQL names no windowFieldName and no
 * preserveWindowInstants, so the side's field is its extractor's own (entryDate) and the slice is cut on the very instants
 * the live call sends.
 *
 * A side that sends keepRecordFields (every interactive side with a compare scope) is served projected, through the projection
 * service the HotWax rows name (project#HotWaxOmsKeptRecords, ruling P3b1-9), which trims with the extract's own function. Each fixture return carries returnDate, a field outside the
 * connector's keepFieldsBase, and the live stub projects as the real extractor does (keep fields plus items).
 *
 * <p>Task 3: HotWax ORDERS kept as a rolling copy that holds the union of the fields its checks read (ruling 3b1-7). The
 * orders fixture is built the same way (O001..O020 around the same window, by orderDate in epoch milliseconds), with two
 * exchange orders the read sets aside into its exchange manifest and a cancelled one a source filter drops; the OMS stub
 * filters, sets exchanges aside and projects as extract#HotWaxOmsGqlOrders does.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HotWaxRollingCopyParityTests {

    private static final String A = "KREWE"
    private static final String PREF_KEY = TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY
    private static final String EDITOR_ID = "TEST_CUSTOMER_USER"
    private static final String EDITOR_USERNAME = "test.customer"
    private static final Timestamp FROM_DATE = Timestamp.valueOf("2026-04-21 00:00:00")
    private static final String FACADE_GRANT = "facade.ReconciliationFacadeServices.list#Reconciliations"
    private static final String RETURNS_EXTRACT = "reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsGqlReturns"
    private static final Timestamp START = Timestamp.from(Instant.parse("2026-11-10T08:00:00Z"))
    private static final Timestamp END = Timestamp.from(Instant.parse("2026-11-11T08:00:00Z"))
    private static final Instant COPY_READ_AT = Instant.parse("2026-11-20T00:00:00Z")
    private static final String STAMP = "20261120-000000000"
    private static final String CONN = "rcp-hw"
    private static final List<Map> FILTERS = [[sequenceNum: 1, fieldExpression: "returnChannelEnumId", operator: "EXCLUDE_IN", filterValues: "POS_RTN_CHANNEL"]]
    private static final String B = "GORJANA"
    private static final String ORDERS_EXTRACT = "reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsGqlOrders"
    /** The OMS_GQL connector's keepFieldsBase, as seeded: every orders side keeps at least these. */
    private static final List<String> ORDER_BASE = ["orderId", "orderName", "externalId", "grandTotal", "orderDate", "statusId",
                                                    "currencyUomId", "billToCustomer", "shipGroups"]

    private ExecutionContext ec
    private File dataManagerRoot
    private File fixtures
    private int seq = 0
    private String datasetId

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "hotwax-rolling-copy-parity")
        dataManagerRoot = DataManagerSupport.resolveDirectoryFile(ec, DataManagerSupport.resolveDataManagerLocation(ec), true)
        assertTrue(dataManagerRoot.path.contains("test-data-manager"), "tests never write the dev datamanager: ${dataManagerRoot}")
        // the test data manager outlives the test DB, whose fresh sequence hands out the same ids again
        ["datasets", DataManagerSupport.RECONCILIATION_RUNS_PATH, "rcp-out", "rcp-fixtures"].each { new File(dataManagerRoot, it).deleteDir() }
        fixtures = new File(dataManagerRoot, "rcp-fixtures")
        fixtures.mkdirs()

        ec.entity.makeDataLoader().location("component://darpan/data/SecuritySeedData.xml").dummyFks(true).load()
        ReconciliationSmokeTestSupport.loadSeedData(ec, "classpath://data/EntityTypeData.xml",
                "component://moqui-rules/data/RuleSeedData.xml", "component://darpan/data/AuditSeedData.xml",
                "component://darpan/data/JudgeSeedData.xml", "component://darpan/data/MappingSeedData.xml",
                "component://darpan/data/DarpanSystemSourceSeedData.xml", "component://darpan/data/AutomationSeedData.xml",
                "component://darpan/data/ConclusionCatalogSeedData.xml", "component://darpan/data/DatasetSeedData.xml")
        ec.entity.makeDataLoader().location("component://darpan/data/SourceSystemConnectorSeedData.xml").dummyFks(true).load()
        // the system's own declarations on its rows (ruling P3b1-9): side files, the projection service its reads are served through
        ec.entity.makeDataLoader().location("component://darpan-hotwax/data/HotWaxConnectorSeedData.xml").dummyFks(true).load()
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        insert("moqui.security.UserGroupMember", [userGroupId: "DARPAN_USER", userId: EDITOR_ID, fromDate: FROM_DATE])
        ec.entity.makeDataLoader().xmlText("""<entity-facade-xml type="seed">
            <darpan.rule.RuleSet ruleSetId="RCP_RS" ruleSetName="HotWax returns vs NetSuite" companyUserGroupId="${A}"/>
            <darpan.rule.RuleSetCompareScope compareScopeId="RCP_SCOPE" ruleSetId="RCP_RS" objectType="RETURN" companyUserGroupId="${A}"
                description="HotWax returns against NetSuite returns"/>
            <darpan.rule.RuleSetCompareSource compareScopeId="RCP_SCOPE" fileSide="FILE_1" systemEnumId="OMS_RETURNS_GQL" fileTypeEnumId="DftJson"
                recordRootExpression="records" primaryIdExpression="returnId" companyUserGroupId="${A}"/>
            <darpan.rule.RuleSetCompareSource compareScopeId="RCP_SCOPE" fileSide="FILE_2" systemEnumId="NETSUITE" fileTypeEnumId="DftJson"
                recordRootExpression="records" primaryIdExpression="returnId" companyUserGroupId="${A}"/>
            <darpan.rule.RuleSetCompareSourceFilter compareScopeId="RCP_SCOPE" fileSide="FILE_1" sequenceNum="1"
                fieldExpression="\$.records[*].returnChannelEnumId" operator="EXCLUDE_IN" filterValues="POS_RTN_CHANNEL" companyUserGroupId="${A}"/>
            <darpan.reconciliation.ReconciliationAutomation automationId="RCP_AUTO" automationName="returns copy" companyUserGroupId="${A}"
                inputModeEnumId="${AutomationExecutionSupport.AUTOMATION_INPUT_API_RANGE}" savedRunId="RCP_RS"/>
            <darpan.reconciliation.ReconciliationAutomationSource automationId="RCP_AUTO" fileSide="FILE_1" companyUserGroupId="${A}"
                sourceTypeEnumId="${AutomationExecutionSupport.AUTOMATION_SOURCE_API}" systemEnumId="OMS_RETURNS_GQL"/>
            <darpan.reconciliation.ReconciliationAutomationSourceFilter automationId="RCP_AUTO" fileSide="FILE_1" sequenceNum="1"
                fieldExpression="\$.records[*].returnChannelEnumId" operator="EXCLUDE_IN" filterValues="POS_RTN_CHANNEL" companyUserGroupId="${A}"/>
        </entity-facade-xml>""").dummyFks(true).load()
        ordersSetup()
        // the other side: NetSuite lacks R004 (the start return), holds R017 (the end return, outside the window) and a stranger
        List<Map> netsuite = returns().findAll { it.returnId != "R004" && it.returnId in ["R001", "R005", "R006", "R007", "R017"] }.collect {
            [returnId: it.returnId] } + [[returnId: "R999"]]
        new File(fixtures, "ns-returns.json").setText(JsonOutput.toJson([records: netsuite]), "UTF-8")
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void actAsTenantA() {
        actAs(EDITOR_USERNAME, A)
    }

    // ----------------------------------------------------------------------------------------------------- the tests

    @Test
    void aRollingReturnsDatasetIsStoredAndRefreshed() {
        String id = rollingReturns(CONN + "-store")
        def row = ec.entity.find("darpan.dataset.Dataset").condition("datasetId", id).disableAuthz().useCache(false).one()
        assertEquals("DRR_ROLLING", row.readRuleEnumId)
        assertEquals(60, row.horizonDays as int)
        assertEquals("entryDate", row.windowField)
        assertEquals("returnId", row.idField)
        assertEquals("OMS_RETURNS_GQL", row.systemEnumId)

        List<Map> calls = []
        Map res = atCopyClock(0) { refresh(id, stub(calls)) }
        assertEquals([], res.errors)
        assertEquals("DRK_FULL", res.out.readKindEnumId)
        assertEquals(1, calls.size())
        assertEquals(RETURNS_EXTRACT, calls[0].service)
        assertEquals("entryDate", calls[0].params.windowFieldName, "the read windows on the returns' own field")
        assertEquals(Timestamp.from(COPY_READ_AT.minusSeconds(60 * 86400L)).toInstant(),
                instantOf(calls[0].params.windowStart), "the whole 60-day window, not a changed-since range")
        assertEquals(20, KeptCopyStore.manifest(DatasetRefreshSupport.datasetDir(ec, A, id)).recordCount)
        assertNull(res.out.missCount, "a rolling read counts no misses")

        // a second day's refresh is another whole read, never an incremental one
        Map again = atCopyClock(1) { refresh(id, stub([])) }
        assertEquals([], again.errors)
        assertEquals("DRK_FULL", again.out.readKindEnumId)
        assertNull(again.out.missCount)
    }

    @Test
    void aServedReturnsRunMatchesALiveRunWithSeededConnectorRows() {
        // the seeded row, unchanged: no window field, no exact-instants flag, not a whole-days source
        def connector = ec.entity.find("darpan.reconciliation.SourceSystemConnector").condition("systemEnumId", "OMS_RETURNS_GQL")
                .disableAuthz().useCache(false).one()
        assertNotNull(connector)
        assertNull(connector.windowFieldName)
        assertEquals("N", connector.preserveWindowInstants)
        assertFalse(connector.windowDateOnly == "Y")
        String id = rollingReturns(CONN)
        assertEquals([], atCopyClock(0) { refresh(id, stub([])) }.errors)

        List<Map> servedCalls = []
        Map served = scheduled(servedCalls, "served")
        assertEquals([], served.errors)
        assertEquals(0, servedCalls.size(), "the served run never calls the live extractor")

        setStatus(id, "DST_PAUSED")
        List<Map> liveCalls = []
        Map live
        try {
            live = scheduled(liveCalls, "live")
        } finally {
            setStatus(id, "DST_ACTIVE")
        }
        assertEquals([], live.errors)
        assertEquals(1, liveCalls.size(), "the paused dataset reads live, once")
        assertFalse(liveCalls[0].params.containsKey("sourceFilters"), "DAR-BE-318 (Task 18): the returns read is sent no filters")
        assertFalse(liveCalls[0].params.containsKey("windowFieldName"), "the live call names no field: its extractor windows on entryDate")
        assertFalse(liveCalls[0].params.containsKey("keepRecordFields"), "a scheduled side keeps whole records")
        assertEquals(START, liveCalls[0].params.windowStart, "the live call is sent the window's instants")
        assertEquals(END, liveCalls[0].params.windowEnd)

        Map servedDoc = json(new File(served.result.fileLocation as String))
        Map liveDoc = json(new File(live.result.fileLocation as String))
        assertEquals(true, servedDoc.metadata.servedFromCopy)
        assertEquals(true, liveDoc.metadata.live)
        List<String> readIds = servedDoc.records*.returnId
        assertTrue(readIds.contains("R004"), "a return exactly at the window start is in")
        assertFalse(readIds.contains("R017"), "a return exactly at the window end is out")
        assertEquals(["R002", "R003"], ["R002", "R003"].intersect(readIds), "DAR-BE-318: the filtered channel is read, for Keep")
        assertTrue(readIds.size() >= 8, readIds.toString())
        assertTrue(servedDoc.records.every { it.returnDate != null }, "whole records, as the scheduled live read keeps them")
        assertEquals(JsonOutput.toJson(liveDoc.records), JsonOutput.toJson(servedDoc.records))
        assertFalse(JsonOutput.toJson(servedDoc.records).contains("_copyId"), "no derived id leaks into a slice")
        // Spark Keep (the plan the scheduled side was read with) sets the filtered channel aside on both
        Map sideKeep = (Map) live.sideKeep
        assertEquals("SPARK", sideKeep?.placement, "the scheduled returns side keeps in Spark")
        [served, live].each { Map run ->
            Set<String> kept = KeepStepSupport.keptValues(ec, new File(run.result.fileLocation as String), sideKeep, "returnId")
            assertEquals(["R002", "R003"].intersect(kept), [], "the filtered channel is set aside")
            assertEquals(readIds - ["R002", "R003"], kept as List)
        }
        // ruling P3b1-7: no excluded-records side file (OMS_RETURNS_GQL N, so Spark writes none either), so the conclude pass
        // reads the filtered side UNKNOWN on both, from the Keep summary's tenant rule count
        assertFalse(sidecarOf(served).exists(), "no side file, as the live returns read writes none")
        assertFalse(sidecarOf(live).exists())
        [served, live].each { Map run ->
            assertTrue(RunConclusionSupport.indexSide([extractFile: new File(run.result.fileLocation as String), keyFields: ["returnId"]],
                    ["R002"] as Set<String>, [] as Set<String>).unknownWhenAbsent as boolean, "a filtered side with no side file is UNKNOWN")
        }

        Map servedOut = compareAndWrite(served + [sideKeep: sideKeep], "served")
        Map liveOut = compareAndWrite(live, "live")
        ["diff.json", "result.json"].each { String name ->
            assertTrue(servedOut[name].length > 0, name)
            assertArrayEquals(liveOut[name], servedOut[name], "${name} differs between the served run and the live run")
        }
        Map<String, String> typeById = ((List<Map>) json(new File(dataManagerRoot, "rcp-out/served/diff.json")).differences)
                .collectEntries { [(it.primaryId as String): it.diffType as String] }
        assertEquals("MISSING_IN_FILE_2", typeById["R004"], "the start return is in the window and NetSuite lacks it")
        assertEquals("MISSING_IN_FILE_1", typeById["R017"], "NetSuite holds the end return, which the window leaves out")
        assertEquals("MISSING_IN_FILE_1", typeById["R999"])
    }

    /**
     * An interactive side with a compare scope sends keepRecordFields, narrower than the records (no returnDate). The slice
     * projects with the extractor's own function (ruling P3b1-8), so the served side gets the records its live read writes.
     */
    @Test
    void aSideThatKeepsFewerFieldsIsServedTheExtractorsProjection() {
        String id = rollingReturns(CONN + "-keep")
        assertEquals([], atCopyClock(0) { refresh(id, stub([])) }.errors)

        List<Map> servedCalls = []
        Map served = interactive(servedCalls, "keep-served", CONN + "-keep")
        assertEquals([], served.errors)
        assertEquals(0, servedCalls.size(), "a side that asks for kept fields is served from the copy")
        setStatus(id, "DST_PAUSED", CONN + "-keep")
        List<Map> liveCalls = []
        Map live
        try {
            live = interactive(liveCalls, "keep-live", CONN + "-keep")
        } finally {
            setStatus(id, "DST_ACTIVE", CONN + "-keep")
        }
        assertEquals(1, liveCalls.size())
        List keep = liveCalls[0].params.keepRecordFields as List
        assertTrue(keep.contains("returnId") && !keep.contains("returnDate"), keep.toString())
        Map servedDoc = json(new File(served.result.fileLocation as String))
        Map liveDoc = json(new File(live.result.fileLocation as String))
        assertEquals(true, servedDoc.metadata.servedFromCopy)
        assertTrue(servedDoc.records.every { !it.containsKey("returnDate") }, "projected as the extractor does")
        assertEquals(JsonOutput.toJson(liveDoc.records), JsonOutput.toJson(servedDoc.records))
    }

    @Test
    void aReturnThatLeftTheWindowIsRemovedNotMissed() {
        String id = rollingReturns(CONN + "-left")
        assertEquals([], atCopyClock(0) { refresh(id, stub([])) }.errors)
        File dir = DatasetRefreshSupport.datasetDir(ec, A, id)
        assertEquals(20, KeptCopyStore.manifest(dir).recordCount)

        // next day: R001 aged out of the OMS window, R005 changed its status without any stamp moving, R021 arrived
        List<Map> next = returns().findAll { it.returnId != "R001" }.collect { Map r ->
            r.returnId == "R005" ? r + [statusId: "RETURN_COMPLETED"] : r } + [ret("R021", "2026-11-19T10:00:00Z")]
        Map res = atCopyClock(1) { refresh(id, stub([], next)) }
        assertEquals([], res.errors)
        assertEquals("DRK_FULL", res.out.readKindEnumId)
        assertNull(res.out.missCount)

        def row = ec.entity.find("darpan.dataset.Dataset").condition("datasetId", id).disableAuthz().useCache(false).one()
        assertNull(row.lastMissCount, "no miss is counted on a rolling dataset")
        assertNull(row.warningText, "and no miss warning is raised")
        def read = ec.entity.find("darpan.dataset.DatasetRead").condition("datasetId", id).orderBy("-readSeqId").disableAuthz().useCache(false).list().first()
        assertNull(read.missCount)
        assertEquals(1, read.removedCount as int, "the return that left the window is removed")
        assertEquals(1, read.addedCount as int)
        assertEquals(1, read.changedCount as int, "a status change with no stamp is a change, not a miss")
        Map byId = KeptCopyStore.records(dir).collectEntries { [(it.id): it.record] }
        assertFalse(byId.containsKey("R001"))
        assertEquals("RETURN_COMPLETED", byId["R005"].statusId)
        assertEquals(20, byId.size())
    }

    // ------------------------------------------------------------------------------------------- Task 3: orders on a copy

    /** Ruling 3b1-7: the copy keeps the union of what every matching source reads, in order; a source on another connection adds nothing. */
    @Test
    void keptFieldsIsTheUnionOverEveryMatchingSource() {
        String id = rollingOrders("rcp-oms-union")
        List<String> expected = ORDER_BASE + ["productStoreId", "salesChannelEnumId"]
        assertEquals(expected, keptFieldsOf(id), "keep fields and filter fields of both sources, then the dataset's own; never an ask's id field")

        List<Map> calls = []
        assertEquals([], atCopyClock(0) { refresh(id, omsStub(calls)) }.errors)
        assertEquals(ORDERS_EXTRACT, calls[0].service)
        assertEquals(expected, calls[0].params.keepRecordFields, "the read keeps the union")
        assertEquals("orderDate", calls[0].params.windowFieldName)
        assertEquals(50000, calls[0].params.exchangeManifestMaxEntries, "the HotWax row's dataset read parameters")
        Map manifest = KeptCopyStore.manifest(DatasetRefreshSupport.datasetDir(ec, A, id))
        assertEquals(expected, manifest.keptFields)
        assertEquals(1, manifest.sideFiles.size(), "the read's exchange manifest is kept with the copy: ${manifest.sideFiles}")
        assertTrue((manifest.sideFiles[0] as String).endsWith(".exchange-manifest.json"))
        assertTrue((manifest.heldFields as List).contains("productStoreId"), "the records carry what the copy keeps")
        assertFalse((manifest.heldFields as List).contains("paymentPreferences"), "and nothing it does not: ${manifest.heldFields}")
        def row = datasetRow(id)
        assertEquals(JsonOutput.toJson(expected), row.keptFieldsJson)
    }

    /**
     * I2: a source's rule that reads a field the OMS order read cannot select never fails the shared read: the keep list
     * leaves it out (the HotWax service's keepableFields), the rule's read fields go as readFields, as a live read sends them.
     */
    @Test
    void aFieldTheOmsCannotSelectNeverFailsTheDatasetRead() {
        String id = rollingOrders("rcp-oms-bad")
        List<String> kept = keptFieldsOf(id)
        assertFalse(kept.contains("notAnOmsField"), kept.toString())
        assertTrue(kept.contains("salesChannelEnumId"), "a selectable rule field is kept")
        List<Map> calls = []
        assertEquals([], atCopyClock(0) { refresh(id, omsStub(calls)) }.errors)
        assertFalse((calls[0].params.keepRecordFields as List).contains("notAnOmsField"))
        assertEquals(["notAnOmsField", "salesChannelEnumId"], (calls[0].params.readFields as List).sort(), "the rules' fields, as readFields")
        Map manifest = KeptCopyStore.manifest(DatasetRefreshSupport.datasetDir(ec, A, id))
        assertNotNull(manifest.version, "the copy builds")
        assertEquals(["notAnOmsField", "salesChannelEnumId"], (manifest.readFields as List).sort())
    }

    /** A source whose rules cannot be projected, or an automation source (which keeps whole records), makes the copy whole. */
    @Test
    void aSourceThatCannotProjectMakesTheCopyKeepWholeRecords() {
        String raw = rollingOrders("rcp-oms-whole")
        assertNull(keptFieldsOf(raw), "a rule with no field path on either side reads whole records")
        String auto = rollingOrders("rcp-oms-auto")
        assertNull(keptFieldsOf(auto), "an automation source reads whole records")

        List<Map> calls = []
        assertEquals([], atCopyClock(0) { refresh(raw, omsStub(calls)) }.errors)
        assertFalse(calls[0].params.containsKey("keepRecordFields"), "a whole read sends no keep fields")
        Map manifest = KeptCopyStore.manifest(DatasetRefreshSupport.datasetDir(ec, A, raw))
        assertNull(manifest.keptFields)
        assertTrue((manifest.heldFields as List).containsAll(["productStoreId", "paymentPreferences"]), manifest.heldFields.toString())
        assertNull(datasetRow(raw).keptFieldsJson)
    }

    /** A7: tenant B's sources on a connection of the same name, run and automation alike, never touch tenant A's union. */
    @Test
    void anOtherTenantsSourcesNeverWidenTheUnion() {
        String id = rollingOrders("rcp-oms-union")
        List<String> kept = keptFieldsOf(id)
        assertNotNull(kept, "B's automation source on the same connection name does not make A's copy whole")
        assertFalse(kept.contains("orderItemCount"), "B's key field is not in A's union: ${kept}")
        // and as tenant B, A's dataset has no union at all
        actAs(EDITOR_USERNAME, B)
        try {
            assertEquals(B, TenantAccessSupport.currentActiveTenantUserGroupId(ec), "test precondition: acting as B")
            assertNull(DatasetSupport.keptFields(ec, datasetRow(id).getMap() as Map))
        } finally {
            actAs(EDITOR_USERNAME, A)
        }
    }

    /**
     * Review Focus 3: a check that now filters on a field the copy lacks reads live, logged, and the dataset is queued for
     * the read that keeps it; once that read has run, the side is served, and its filter sees the field. A filter field is
     * not kept, so only the copy's own field list can tell the slice it lacks it.
     */
    @Test
    void aSideNeedingAFieldTheCopyLacksReadsLiveAndQueuesARebuild() {
        String conn = "rcp-oms-lack"
        String id = rollingOrders(conn)
        assertEquals([], atCopyClock(0) { refresh(id, omsStub([])) }.errors)
        List<Map> before = []
        assertEquals([], ordersRun(before, "lack-before", conn, "RCP_OLACK").errors)
        assertEquals(0, before.size(), "served while the copy holds every field the side reads")
        assertFalse(KeptCopyStore.manifest(DatasetRefreshSupport.datasetDir(ec, A, id)).keptFields.contains("salesChannelEnumId"))

        // the check now sets POS orders aside on this side
        ec.entity.makeDataLoader().xmlText("""<entity-facade-xml type="seed">
            <darpan.rule.RuleSetCompareSourceFilter compareScopeId="RCP_OLACK" fileSide="FILE_1" sequenceNum="1"
                fieldExpression="\$.records[*].salesChannelEnumId" operator="EXCLUDE_IN" filterValues="POS_SALES_CHANNEL" companyUserGroupId="${A}"/>
        </entity-facade-xml>""").dummyFks(true).load()
        try {
            def row = datasetRow(id)
            row.nextReadDate = new Timestamp(System.currentTimeMillis() + 30 * 86_400_000L)
            row.update()
            List<Map> lacking = []
            Map run = ordersRun(lacking, "lack-live", conn, "RCP_OLACK")
            assertEquals([], run.errors)
            assertEquals(1, lacking.size(), "the side reads live: the copy lacks salesChannelEnumId")
            // DAR-BE-318 (Task 17): a Spark side's Keep fields join its projection and go as keepRuleFields; no filters are sent
            assertTrue((lacking[0].params.keepRecordFields as List).contains("salesChannelEnumId"), "a Keep field is kept (Ruling 3b2b-4)")
            assertEquals(["isExchangeOrder", "salesChannelEnumId"], lacking[0].params.keepRuleFields)
            assertTrue(json(new File(run.result.fileLocation as String)).records*.orderId.contains("O008"), "the read drops nothing")
            assertFalse(keptOrderIds(run).contains("O008"), "Spark Keep sets the POS order aside")
            assertTrue((datasetRow(id).nextReadDate as Timestamp).time <= System.currentTimeMillis(), "the dataset is due now")

            // the next scan's read keeps the field, and the side is served again: its filter sees the field
            assertEquals([], atCopyClock(0) { refresh(id, omsStub([])) }.errors)
            assertTrue(KeptCopyStore.manifest(DatasetRefreshSupport.datasetDir(ec, A, id)).keptFields.contains("salesChannelEnumId"))
            List<Map> after = []
            Map served = ordersRun(after, "lack-after", conn, "RCP_OLACK")
            assertEquals(0, after.size(), "served once the copy keeps the field")
            Map servedDoc = json(new File(served.result.fileLocation as String))
            assertEquals(json(new File(run.result.fileLocation as String)).records, servedDoc.records, "the live read's records")
            assertEquals(keptOrderIds(run), keptOrderIds(served), "Spark Keep keeps the same orders of both")
            Map rule = KeepArtifacts.readSummary(new File(served.result.fileLocation as String)).configuredExclusions[0] as Map
            assertEquals([1, 0], [rule.excludedCount, rule.fieldAbsentCount], "O008 set aside on its field")
        } finally {
            ec.entity.find("darpan.rule.RuleSetCompareSourceFilter").condition("compareScopeId", "RCP_OLACK").disableAuthz().deleteAll()
        }
    }

    /**
     * An interactive orders run served from the rolling copy is the run read live: the same records (projected from a copy
     * that keeps more fields than this side), the same exchange manifest beside them, the same diff and result.
     */
    @Test
    void aServedOrdersRunMatchesALiveRun() {
        String conn = "rcp-oms"
        String id = rollingOrders(conn)
        List<Map> refreshCalls = []
        assertEquals([], atCopyClock(0) { refresh(id, omsStub(refreshCalls)) }.errors)
        List keptByCopy = refreshCalls[0].params.keepRecordFields as List
        assertEquals(ORDER_BASE + ["productStoreId"], keptByCopy, "the wider source widens the copy")

        List<Map> servedCalls = []
        Map served = ordersRun(servedCalls, "orders-served", conn, "RCP_OSCOPE")
        assertEquals([], served.errors)
        assertEquals(0, servedCalls.size(), "the served run never calls the OMS")

        setStatus(id, "DST_PAUSED", conn, "OMS_GQL", "orderId", "orderDate")
        List<Map> liveCalls = []
        Map live
        try {
            live = ordersRun(liveCalls, "orders-live", conn, "RCP_OSCOPE")
        } finally {
            setStatus(id, "DST_ACTIVE", conn, "OMS_GQL", "orderId", "orderDate")
        }
        assertEquals([], live.errors)
        assertEquals(1, liveCalls.size())
        assertEquals(ORDER_BASE + ["isExchangeOrder"], liveCalls[0].params.keepRecordFields,
                "the side keeps fewer fields than the copy (its Keep fields join them: statusId is one already)")

        Map servedDoc = json(new File(served.result.fileLocation as String))
        Map liveDoc = json(new File(live.result.fileLocation as String))
        assertEquals(true, servedDoc.metadata.servedFromCopy)
        List<String> ids = servedDoc.records*.orderId
        assertTrue(ids.contains("O004") && !ids.contains("O017"), "half-open on orderDate: ${ids}")
        // DAR-BE-318 (Task 17): the read drops nothing; the exchanges are flagged, and Spark Keep sets them and O003 aside
        assertTrue(ids.containsAll(["O003", "O006", "O009"]), "every order of the window is written: ${ids}")
        assertEquals(["O006", "O009"], servedDoc.records.findAll { it.isExchangeOrder == "Y" }*.orderId.sort())
        assertFalse(keptOrderIds(served).any { it in ["O003", "O006", "O009"] }, "the cancelled order and the exchanges are set aside")
        assertEquals(keptOrderIds(live), keptOrderIds(served), "Spark Keep keeps the same orders of both")
        assertTrue(servedDoc.records.every { !it.containsKey("productStoreId") }, "projected to the side's own fields")
        assertEquals(JsonOutput.toJson(liveDoc.records), JsonOutput.toJson(servedDoc.records))

        Map servedManifest = json(exchangeManifestOf(served))
        Map liveManifest = json(exchangeManifestOf(live))
        assertEquals(["O006", "O009"], servedManifest.manifest*.omsOrderId.sort())
        assertEquals(liveManifest.manifest.sort { it.omsOrderId }, servedManifest.manifest.sort { it.omsOrderId })
        assertEquals(liveManifest.truncated, servedManifest.truncated)

        Map servedOut = compareOrders(served, "orders-served")
        Map liveOut = compareOrders(live, "orders-live")
        ["diff.json", "result.json"].each { String name ->
            assertTrue(servedOut[name].length > 0, name)
            assertArrayEquals(liveOut[name], servedOut[name], "${name} differs between the served run and the live run")
        }
    }

    // --------------------------------------------------------------------------------------------------------- the runs

    /** One interactive FILE_1 side through RunExtractSupport.extractApiSource; the live extractor is the stub below. */
    private Map interactive(List<Map> calls, String token, String conn = CONN) {
        String runToken = "p${++seq}"
        Map source = [systemEnumId: "OMS_RETURNS_GQL", sourceConfigType: "HOTWAX_OMS_GQL_RETURNS", sourceConfigId: conn, fileSide: "FILE_1",
                      compareScopeId: "RCP_SCOPE", sourceFilters: FILTERS]
        Map artifact = [location: DataManagerSupport.resolveReconciliationRunLocation(ec, "rcp-${token}-${runToken}", STAMP), runToken: "parity"]
        ec.message.clearAll()
        Map result = RunExtractSupport.extractApiSource(ec, source, "FILE_1", "File 1", artifact,
                [windowStartDate: START, windowEndDate: END, timeZone: "UTC"], null, liveExtractor(calls), null, sideKeepOf(source))
        List<String> errors = ec.message.errors.collect { it.toString() }
        ec.message.clearAll()
        return [result: result, artifact: artifact, source: source, errors: errors]
    }

    /**
     * One scheduled FILE_1 side through AutomationExecutionSupport.callConfiguredSourceExtractor; the live extractor is the
     * stub below. The result is the extract's file, under one compare label for both runs.
     */
    private Map scheduled(List<Map> calls, String token) {
        Map automation = [automationId: "RCP_AUTO", companyUserGroupId: A, compareScopeId: "RCP_SCOPE",
                          relativeWindowTypeEnumId: AutomationExecutionSupport.WINDOW_PREVIOUS_DAY]
        Map source = [automationId: "RCP_AUTO", fileSide: "FILE_1", systemEnumId: "OMS_RETURNS_GQL", companyUserGroupId: A,
                      sourceTypeEnumId: AutomationExecutionSupport.AUTOMATION_SOURCE_API,
                      safeMetadataJson: JsonOutput.toJson([extractServiceName: RETURNS_EXTRACT, parameters: [omsRestSourceConfigId: CONN]])]
        String location = DataManagerSupport.resolveReconciliationRunLocation(ec, "rcp-${token}-s${++seq}", STAMP)
        ec.message.clearAll()
        Map out = null
        List<String> errors = []
        try {
            // the run loop says its compare takes keepBySide (DAR-BE-318, Task 14), so a Spark side leaves Keep to Spark
            out = AutomationExecutionSupport.callConfiguredSourceExtractor(ec, automation, source,
                    [childWindowStartDate: START, childWindowEndDate: END],
                    [automationExecutionId: "RCP_EXEC_${seq}".toString(), sideOutputLocation: location, liveExtractor: liveExtractor(calls),
                     (AutomationExecutionSupport.KEEP_IN_SPARK): true])
        } catch (Exception e) {
            errors << e.toString()
        }
        errors += ec.message.errors.collect { it.toString() }
        ec.message.clearAll()
        String loc = out?.fileLocation as String
        File file = loc == null ? null : (loc.startsWith("/") ? new File(loc) : ec.resource.getLocationReference(loc).getFile())
        return [result: [fileLocation: file?.absolutePath, fileName: "oms-returns.json", requestMetadata: out?.requestMetadata],
                errors: errors, sideKeep: out?.get(AutomationExecutionSupport.SIDE_KEEP)]
    }

    /**
     * What a live OMS returns read answers for the window: the returns with entryDate in [start, end). DAR-BE-318 (Task 18):
     * the read takes no source filters, so it drops nothing; each fixture return carries the omsReturnClass the read writes.
     */
    private Closure liveExtractor(List<Map> calls) {
        return { String service, Map params ->
            calls << [service: service, params: new LinkedHashMap(params)]
            assertFalse(params.containsKey("sourceFilters"), "the returns read takes no source filters")
            Closure<Instant> at = { Object v -> v instanceof Timestamp ? ((Timestamp) v).toInstant() : Instant.parse(v as String) }
            Instant from = at(params.windowStart), to = at(params.windowEnd)
            String fileName = (params.fileName ?: "oms-returns-${from.toEpochMilli()}-${to.toEpochMilli()}.json") as String
            List<Map> records = returns().findAll { Map r ->
                Instant e = Instant.ofEpochMilli(r.entryDate as long)
                !e.isBefore(from) && e.isBefore(to)
            }
            // as OmsReturnsSourceSupport.projectRecord: keep the asked fields, items and the class
            List keepFields = params.keepRecordFields as List
            if (keepFields) {
                Set<String> keep = (keepFields.collect { it.toString() } + ["items", "omsReturnClass"]) as Set<String>
                records = records.collect { Map r -> r.findAll { k, v -> (k as String) in keep } }
            }
            File dir = DataManagerSupport.resolveDirectoryFile(ec, params.outputLocation as String, true)
            new File(dir, fileName).setText(JsonOutput.toJson([metadata: [live: true, windowStart: from.toString(), windowEnd: to.toString(),
                                                                          filters: [excludedNoShopifyRefCount: 0]],
                                                               records : records]), "UTF-8")
            return [fileLocation: DataManagerSupport.childLocation(params.outputLocation as String, fileName), fileName: fileName,
                    recordCount: records.size()]
        }
    }

    /** A refresh's extractor: every return, or the given ones, as a live extract writes them. */
    private Closure stub(List<Map> calls, List<Map> records = null) {
        return { String svc, Map p ->
            calls << [service: svc, params: new LinkedHashMap(p)]
            List<Map> rows = records != null ? records : returns()
            File out = new File(DataManagerSupport.resolveDirectoryFile(ec, p.outputLocation as String, true), p.fileName as String)
            out.setText(JsonOutput.toJson([metadata: [windowStart: p.windowStart, windowEnd: p.windowEnd], records: rows]), "UTF-8")
            return [fileLocation: out.absolutePath, recordCount: rows.size(), dataAvailable: !rows.isEmpty()]
        }
    }

    /**
     * DAR-OPS-029 (S40a/S40c, after darpan OC164): store#Dataset now resolves its source config like any API source (owner or
     * shared, active, enabled), so each connection the copies read through exists as A's own active HotWax config.
     */
    private void ensureConfig(String conn) {
        def existing = ec.entity.find("darpan.hotwax.HotWaxOmsRestSourceConfig").condition("omsRestSourceConfigId", conn)
                .disableAuthz().useCache(false).one()
        if (existing != null) return
        ec.entity.makeValue("darpan.hotwax.HotWaxOmsRestSourceConfig").setAll([omsRestSourceConfigId: conn, companyUserGroupId: A,
                description: conn, baseUrl: "https://oms.test", authType: "NONE", timeZone: "UTC", isActive: "Y"]).create()
    }

    private String rollingReturns(String conn) {
        ensureConfig(conn)
        Map stored = underFacadeGrant("dataset.DatasetServices.store#Dataset", [systemEnumId: "OMS_RETURNS_GQL", sourceConfigId: conn,
                options: [:], idField: "returnId", windowField: "entryDate", readRuleEnumId: "DRR_ROLLING"])
        assertEquals([], stored.errors)
        if (conn == CONN) datasetId = stored.out.datasetId
        return stored.out.datasetId as String
    }

    private Map atCopyClock(int plusDays, Closure<Map> work) {
        ec.user.setEffectiveTime(Timestamp.from(COPY_READ_AT.plusSeconds(plusDays * 86400L)))
        try {
            return work.call()
        } finally {
            ec.user.setEffectiveTime(null)
        }
    }

    private Map refresh(String id, Closure extractor) {
        return underFacadeGrant("dataset.DatasetServices.refresh#Dataset", [datasetId: id, extractor: extractor, forceFull: false])
    }

    private void setStatus(String id, String statusId, String conn = CONN, String system = "OMS_RETURNS_GQL",
                           String idField = "returnId", String windowField = "entryDate") {
        ensureConfig(conn)
        assertEquals([], underFacadeGrant("dataset.DatasetServices.store#Dataset", [datasetId: id, statusId: statusId,
                systemEnumId: system, sourceConfigId: conn, options: [:], idField: idField, windowField: windowField]).errors)
    }

    private static Instant instantOf(Object v) {
        return v instanceof Timestamp ? ((Timestamp) v).toInstant() : Instant.parse(v as String)
    }

    // ----------------------------------------------------------------------------------------------------- orders helpers

    /** The orders rule sets and sources Task 3's tests read, for tenant A and (A7) tenant B. */
    private void ordersSetup() {
        insert("moqui.security.UserGroup", [userGroupId: B, description: "Gorjana", groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID])
        insert("moqui.security.UserGroupMember", [userGroupId: B, userId: EDITOR_ID, fromDate: FROM_DATE])
        insert(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME, [tenantUserGroupId: B, userId: EDITOR_ID,
                permissionUserGroupId: TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID, fromDate: FROM_DATE])
        Closure<String> omsSide = { String scope, String side, String conn, String tenant = A ->
            """<darpan.rule.RuleSetCompareSource compareScopeId="${scope}" fileSide="${side}" systemEnumId="OMS_GQL" fileTypeEnumId="DftJson"
                sourceConfigId="${conn}" sourceConfigType="HOTWAX_OMS_GQL" recordRootExpression="records" primaryIdExpression="orderId"
                companyUserGroupId="${tenant}"/>"""
        }
        Closure<String> scope = { String ruleSet, String scopeId, String tenant = A ->
            """<darpan.rule.RuleSet ruleSetId="${ruleSet}" ruleSetName="${ruleSet}" companyUserGroupId="${tenant}"/>
            <darpan.rule.RuleSetCompareScope compareScopeId="${scopeId}" ruleSetId="${ruleSet}" objectType="ORDER" companyUserGroupId="${tenant}"/>"""
        }
        ec.entity.makeDataLoader().xmlText("""<entity-facade-xml type="seed">
            ${scope('RCP_ORS', 'RCP_OSCOPE')}
            ${omsSide('RCP_OSCOPE', 'FILE_1', 'rcp-oms')}
            <darpan.rule.RuleSetCompareSource compareScopeId="RCP_OSCOPE" fileSide="FILE_2" systemEnumId="NETSUITE" fileTypeEnumId="DftJson"
                recordRootExpression="records" primaryIdExpression="orderId" companyUserGroupId="${A}"/>
            <darpan.rule.RuleSetCompareSourceFilter compareScopeId="RCP_OSCOPE" fileSide="FILE_1" sequenceNum="1"
                fieldExpression="\$.records[*].statusId" operator="EXCLUDE_IN" filterValues="ORDER_CANCELLED" companyUserGroupId="${A}"/>
            ${scope('RCP_OWRS', 'RCP_OWIDE')}
            ${omsSide('RCP_OWIDE', 'FILE_1', 'rcp-oms')}
            <darpan.rule.RuleSetCompareSourceKeyField compareScopeId="RCP_OWIDE" fileSide="FILE_1" sequenceNum="1"
                fieldExpression="productStoreId" companyUserGroupId="${A}"/>

            ${scope('RCP_OLRS', 'RCP_OLACK')}
            ${omsSide('RCP_OLACK', 'FILE_1', 'rcp-oms-lack')}

            ${scope('RCP_URS1', 'RCP_OU1')}
            ${omsSide('RCP_OU1', 'FILE_1', 'rcp-oms-union')}
            <darpan.rule.RuleSetCompareSourceKeyField compareScopeId="RCP_OU1" fileSide="FILE_1" sequenceNum="1"
                fieldExpression="productStoreId" companyUserGroupId="${A}"/>
            <darpan.audit.AuditCheck checkId="RCP_CHK" companyUserGroupId="${A}" checkCode="RCP" label="orders" savedRunId="RCP_URS1"/>
            <darpan.judge.CheckAsk checkId="RCP_CHK" askName="flow" companyUserGroupId="${A}" subjectSide="FILE_1" idField="statusFlowId"
                systemEnumId="NETSUITE" sourceConfigId="ns" keyField="flowId"/>
            <darpan.judge.CheckAsk checkId="RCP_CHK" askName="other" companyUserGroupId="${A}" subjectSide="FILE_2" idField="orderItemCount"
                systemEnumId="NETSUITE" sourceConfigId="ns" keyField="x"/>
            ${scope('RCP_URS2', 'RCP_OU2')}
            ${omsSide('RCP_OU2', 'FILE_2', 'rcp-oms-union')}
            <darpan.rule.RuleSetCompareSourceFilter compareScopeId="RCP_OU2" fileSide="FILE_2" sequenceNum="1"
                fieldExpression="\$.records[*].salesChannelEnumId" operator="EXCLUDE_IN" filterValues="POS_SALES_CHANNEL" companyUserGroupId="${A}"/>
            ${scope('RCP_URS3', 'RCP_OU3')}
            ${omsSide('RCP_OU3', 'FILE_1', 'rcp-oms-elsewhere')}
            <darpan.rule.RuleSetCompareSourceKeyField compareScopeId="RCP_OU3" fileSide="FILE_1" sequenceNum="1"
                fieldExpression="orderItemCount" companyUserGroupId="${A}"/>

            ${scope('RCP_BRS', 'RCP_BSCOPE', B)}
            ${omsSide('RCP_BSCOPE', 'FILE_1', 'rcp-oms-union', B)}
            <darpan.rule.RuleSetCompareSourceKeyField compareScopeId="RCP_BSCOPE" fileSide="FILE_1" sequenceNum="1"
                fieldExpression="orderItemCount" companyUserGroupId="${B}"/>
            <darpan.reconciliation.ReconciliationAutomation automationId="RCP_BAUTO" automationName="B orders" companyUserGroupId="${B}"
                inputModeEnumId="${AutomationExecutionSupport.AUTOMATION_INPUT_API_RANGE}" savedRunId="RCP_BRS"/>
            <darpan.reconciliation.ReconciliationAutomationSource automationId="RCP_BAUTO" fileSide="FILE_1" companyUserGroupId="${B}"
                sourceTypeEnumId="${AutomationExecutionSupport.AUTOMATION_SOURCE_API}" systemEnumId="OMS_GQL"
                safeMetadataJson='{"parameters":{"omsRestSourceConfigId":"rcp-oms-union"}}'/>

            ${scope('RCP_BADRS', 'RCP_OBAD')}
            ${omsSide('RCP_OBAD', 'FILE_1', 'rcp-oms-bad')}
            <darpan.rule.Rule ruleId="RCP_BAD_RULE" ruleSetId="RCP_BADRS" sequenceNum="1" ruleText="bad" enabled="Y"
                expression='{"file1FieldPath":"notAnOmsField","file2FieldPath":"x","operator":"EQUALS"}'/>
            <darpan.rule.Rule ruleId="RCP_CHANNEL_RULE" ruleSetId="RCP_BADRS" sequenceNum="2" ruleText="channel" enabled="Y"
                expression='{"file1FieldPath":"salesChannelEnumId","file2FieldPath":"x","operator":"EQUALS"}'/>

            ${scope('RCP_WRS', 'RCP_OWHOLE')}
            ${omsSide('RCP_OWHOLE', 'FILE_1', 'rcp-oms-whole')}
            <darpan.rule.Rule ruleId="RCP_RAW_RULE" ruleSetId="RCP_WRS" sequenceNum="1" ruleText="raw" expression="{}" enabled="Y"/>
            ${scope('RCP_ARS', 'RCP_OAUTO')}
            ${omsSide('RCP_OAUTO', 'FILE_1', 'rcp-oms-auto')}
            <darpan.reconciliation.ReconciliationAutomation automationId="RCP_AAUTO" automationName="A orders" companyUserGroupId="${A}"
                inputModeEnumId="${AutomationExecutionSupport.AUTOMATION_INPUT_API_RANGE}" savedRunId="RCP_ARS"/>
            <darpan.reconciliation.ReconciliationAutomationSource automationId="RCP_AAUTO" fileSide="FILE_1" companyUserGroupId="${A}"
                sourceTypeEnumId="${AutomationExecutionSupport.AUTOMATION_SOURCE_API}" systemEnumId="OMS_GQL"
                safeMetadataJson='{"parameters":{"omsRestSourceConfigId":"rcp-oms-auto"}}'/>
        </entity-facade-xml>""").dummyFks(true).load()
        List<Map> netsuite = orders().findAll { it.orderId in ["O001", "O005", "O007", "O017"] }.collect { [orderId: it.orderId] } +
                [[orderId: "O999"]]
        new File(fixtures, "ns-orders.json").setText(JsonOutput.toJson([records: netsuite]), "UTF-8")
    }

    private String rollingOrders(String conn) {
        ensureConfig(conn)
        Map stored = underFacadeGrant("dataset.DatasetServices.store#Dataset", [systemEnumId: "OMS_GQL", sourceConfigId: conn,
                options: [:], idField: "orderId", windowField: "orderDate", readRuleEnumId: "DRR_ROLLING"])
        assertEquals([], stored.errors)
        return stored.out.datasetId as String
    }

    private def datasetRow(String id) {
        return ec.entity.find("darpan.dataset.Dataset").condition("datasetId", id).disableAuthz().useCache(false).one()
    }

    private List<String> keptFieldsOf(String id) {
        Set<String> kept = DatasetSupport.keptFields(ec, datasetRow(id).getMap() as Map)
        return kept == null ? null : new ArrayList<String>(kept)
    }

    /** One interactive FILE_1 orders side, its source the stored row, through RunExtractSupport.extractApiSource. */
    private Map ordersRun(List<Map> calls, String token, String conn, String scopeId) {
        def row = ec.entity.find("darpan.rule.RuleSetCompareSource").condition("compareScopeId", scopeId).condition("fileSide", "FILE_1")
                .disableAuthz().useCache(false).one()
        assertEquals(conn, row.sourceConfigId)
        Map source = new LinkedHashMap(row.getMap())
        Map artifact = [location: DataManagerSupport.resolveReconciliationRunLocation(ec, "rcp-${token}-p${++seq}", STAMP), runToken: "parity"]
        ec.message.clearAll()
        Map sideKeep = sideKeepOf(source)
        Map result = RunExtractSupport.extractApiSource(ec, source, "FILE_1", "File 1", artifact,
                [windowStartDate: START, windowEndDate: END, timeZone: "UTC"], null, omsStub(calls), null, sideKeep)
        List<String> errors = ec.message.errors.collect { it.toString() }
        ec.message.clearAll()
        return [result: result, errors: errors, sideKeep: sideKeep]
    }

    /** The side's Keep plan as an interactive run builds it (RunExtractSupport.sideKeep, for the active tenant). */
    private Map sideKeepOf(Map source) {
        return RunExtractSupport.sideKeep(ec, source, "FILE_1", RunExtractSupport.resolveExtractConnector(ec, source))
    }

    /** Spark Keep over one extract, as ingest runs it: the kept orderIds (KeepStepSupport.keptValues writes the artifact). */
    private Set<String> keptOrderIds(Map run) {
        return KeepStepSupport.keptValues(ec, new File(run.result.fileLocation as String), (Map) run.sideKeep, "orderId")
    }

    /**
     * The OMS as extract#HotWaxOmsGqlOrders answers, for the refresh and for the live read alike: orders with orderDate in
     * [windowStart, windowEnd), each record trimmed with the extractor's projection, then flagged isExchangeOrder Y or N;
     * exchange orders also listed in the exchange manifest (at most exchangeManifestMaxEntries, 500 by default). DAR-BE-318
     * (Task 17): the read takes no source filters, so it drops nothing; Spark Keep sets records aside at the compare.
     */
    private Closure omsStub(List<Map> calls) {
        return { String service, Map params ->
            calls << [service: service, params: new LinkedHashMap(params)]
            Instant from = instantOf(params.windowStart), to = instantOf(params.windowEnd)
            String fileName = (params.fileName ?: "oms-orders-${from.toEpochMilli()}-${to.toEpochMilli()}.json") as String
            List<Map> inWindow = orders().findAll { Map o ->
                Instant d = Instant.ofEpochMilli(o.orderDate as long)
                !d.isBefore(from) && d.isBefore(to)
            }
            assertFalse(params.containsKey("sourceFilters"), "the orders read takes no source filters")
            List<Map> exchanges = inWindow.findAll { it.orderItemAssocs }
            // as extract#HotWaxOmsGqlOrders trims: the asked fields (raw payment rows bring their slots-full flag), record order
            Set<String> keep = (params.keepRecordFields as List)?.collect { it.toString().trim() }?.findAll { it } as Set<String>
            if (keep?.contains("paymentPreferences")) keep.add("paymentPreferenceSlotsFull")
            List<Map> records = inWindow.collect { Map r ->
                (keep ? r.findAll { k, v -> (k as String) in keep } : new LinkedHashMap(r)) + [isExchangeOrder: r.orderItemAssocs ? "Y" : "N"]
            }
            File dir = DataManagerSupport.resolveDirectoryFile(ec, params.outputLocation as String, true)
            new File(dir, fileName).setText(JsonOutput.toJson([records: records, metadata: [live: true]]), "UTF-8")
            String manifestLocation = null
            if (exchanges) {
                int cap = (params.exchangeManifestMaxEntries ?: 500) as int
                String manifestName = fileName.replaceAll(/\.json$/, "") + ".exchange-manifest.json"
                new File(dir, manifestName).setText(JsonOutput.toJson([manifest: exchanges.take(cap).collect { Map o ->
                    [omsOrderId: o.orderId, externalId: o.externalId, orderName: o.orderName, toOrderId: o.orderItemAssocs[0].toOrderId,
                     grandTotal: o.grandTotal, orderDate: o.orderDate, statusId: o.statusId] }, truncated: exchanges.size() > cap,
                                                                      sourceFileName: fileName]), "UTF-8")
                manifestLocation = DataManagerSupport.childLocation(params.outputLocation as String, manifestName)
            }
            return [fileLocation: DataManagerSupport.childLocation(params.outputLocation as String, fileName), fileName: fileName,
                    recordCount: records.size(), exchangeManifestFileLocation: manifestLocation]
        }
    }

    private File exchangeManifestOf(Map run) {
        File extract = new File(run.result.fileLocation as String)
        File f = new File(extract.parentFile, extract.name.replaceAll(/\.json$/, "") + ".exchange-manifest.json")
        assertTrue(f.isFile(), "no exchange manifest beside ${extract}")
        return f
    }

    private Map compareOrders(Map run, String tag) {
        Map called = underFacadeGrant("reconciliation.ReconciliationCoreServices.reconcile#RuleSetCompareScope", [
                ruleSetId: "RCP_ORS", compareScopeId: "RCP_OSCOPE", file1Location: run.result.fileLocation,
                file2Location: new File(fixtures, "ns-orders.json").absolutePath, file1Name: "oms-orders.json",
                file2Name: "ns-orders.json", sparkAppName: "HotWaxRollingCopyParityTests", keepBySide: [FILE_1: run.sideKeep]])
        assertEquals([], called.errors, tag)
        Map out = called.out
        try {
            File dir = new File(dataManagerRoot, "rcp-out/${tag}")
            dir.deleteDir()
            Map written = ReconciliationServices.writeDiffDatasetOutput(ec, out.diffDf, dir.absolutePath, "diff.json", "diff.json",
                    [timestamp: "fixed", ruleSetId: out.ruleSetId, compareScopeId: out.compareScopeId],
                    [totalDifferences: out.differenceCount], (List) out.validationErrors, (List) out.processingWarnings)
            new File(dir, "result.json").setText(JsonOutput.toJson(new TreeMap(out.findAll { k, v ->
                !isSparkDataset(v) && k != "persistedSources" })), "UTF-8")
            return ["diff.json": new File(written.diffLocation as String).bytes, "result.json": new File(dir, "result.json").bytes]
        } finally {
            ReconciliationServices.unpersistDatasets((List) (out?.persistedSources ?: []))
        }
    }

    private static Map order(String id, String orderDate, Map extra = [:]) {
        long at = Instant.parse(orderDate).toEpochMilli()
        return [orderId: id, orderName: "#${id}".toString(), externalId: "SH${id.substring(1)}".toString(), statusId: "ORDER_COMPLETED",
                orderTypeId: "SALES_ORDER", orderDate: at, grandTotal: 25.5, currencyUomId: "USD", productStoreId: "STORE",
                salesChannelEnumId: "WEB_SALES_CHANNEL", billToCustomer: [firstName: "Ann", lastName: id],
                shipGroups: [[shipGroupSeqId: "00001", facilityId: "WH"]], hasPaymentPreference: "Y",
                paymentMethodTypeIds: "CREDIT_CARD", paymentPreferenceSlotsFull: false, isCompleted: "Y",
                paymentPreferences: [[paymentMethodTypeId: "CREDIT_CARD", statusId: "PAYMENT_SETTLED", maxAmount: 25.5]]] + extra
    }

    /**
     * 20 synthetic orders around the window [2026-11-10T08:00Z, 2026-11-11T08:00Z): O006 and O009 exchanges, O003 cancelled,
     * O008 sold at the POS.
     */
    private static List<Map> orders() {
        Map exchange = [orderItemAssocs: [[orderItemAssocTypeId: "EXCHANGE", toOrderId: "EXC1"]]]
        return [
                order("O001", "2026-10-02T09:00:00Z"), order("O002", "2026-11-10T09:30:00Z"),
                order("O003", "2026-11-10T22:00:00Z", [statusId: "ORDER_CANCELLED"]), order("O004", "2026-11-10T08:00:00Z"),
                order("O005", "2026-11-10T08:00:01Z"), order("O006", "2026-11-10T11:00:00Z", exchange),
                order("O007", "2026-11-10T13:15:00Z"), order("O008", "2026-11-10T15:45:00Z", [salesChannelEnumId: "POS_SALES_CHANNEL"]),
                order("O009", "2026-11-10T18:00:00Z", exchange), order("O010", "2026-11-10T20:30:00Z"),
                order("O011", "2026-11-11T00:10:00Z"), order("O012", "2026-11-11T03:00:00Z"), order("O013", "2026-11-11T07:59:59Z"),
                order("O014", "2026-11-10T07:59:59Z"), order("O015", "2026-11-09T12:00:00Z", exchange),
                order("O016", "2026-11-12T12:00:00Z"), order("O017", "2026-11-11T08:00:00Z"), order("O018", "2026-11-15T12:00:00Z"),
                order("O019", "2026-11-18T12:00:00Z"), order("O020", "2026-10-25T12:00:00Z"),
        ]
    }

    // ------------------------------------------------------------------------------------------------------- the compare

    private Map compareAndWrite(Map run, String tag) {
        Map called = underFacadeGrant("reconciliation.ReconciliationCoreServices.reconcile#RuleSetCompareScope", [
                ruleSetId: "RCP_RS", compareScopeId: "RCP_SCOPE", file1Location: run.result.fileLocation,
                file2Location: new File(fixtures, "ns-returns.json").absolutePath, file1Name: run.result.fileName,
                file2Name: "ns-returns.json", sparkAppName: "HotWaxRollingCopyParityTests", keepBySide: [FILE_1: run.sideKeep]])
        assertEquals([], called.errors, tag)
        Map out = called.out
        try {
            File dir = new File(dataManagerRoot, "rcp-out/${tag}")
            dir.deleteDir()
            Map written = ReconciliationServices.writeDiffDatasetOutput(ec, out.diffDf, dir.absolutePath, "diff.json", "diff.json",
                    [timestamp: "fixed", ruleSetId: out.ruleSetId, compareScopeId: out.compareScopeId],
                    [totalDifferences: out.differenceCount], (List) out.validationErrors, (List) out.processingWarnings)
            // DAR-BE-318 (Task 18): the Keep summary names the extract file it ran on, and a slice's file is named its own way
            // (KeptCopySupport); everything else in the result is the run's
            Map result = new TreeMap(out.findAll { k, v -> !isSparkDataset(v) && k != "persistedSources" })
            if (result.keepSummaryBySide instanceof Map) {
                result.keepSummaryBySide = ((Map) result.keepSummaryBySide).collectEntries { k, v ->
                    [(k): v instanceof Map ? ((Map) v).findAll { key, x -> key != "sourceFileName" } : v] }
            }
            new File(dir, "result.json").setText(JsonOutput.toJson(result), "UTF-8")
            return ["diff.json": new File(written.diffLocation as String).bytes, "result.json": new File(dir, "result.json").bytes]
        } finally {
            ReconciliationServices.unpersistDatasets((List) (out?.persistedSources ?: []))
        }
    }

    /** Spark is on this component's runtime classpath only (through darpan), so a Dataset is told by its class name. */
    private static boolean isSparkDataset(Object v) {
        return v != null && v.getClass().name.startsWith("org.apache.spark.sql.")
    }

    private File sidecarOf(Map run) {
        File extract = new File(run.result.fileLocation as String)
        return new File(extract.parentFile, ExcludedRecordsSidecar.fileNameFor(extract.name))
    }

    // ------------------------------------------------------------------------------------------------------------ fixture

    private static Map ret(String id, String entryDate, String channel = "ECOM_RTN_CHANNEL") {
        return [returnId: id, statusId: "RETURN_REQUESTED", entryDate: Instant.parse(entryDate).toEpochMilli(),
                returnDate: Instant.parse(entryDate).minusSeconds(86400).toEpochMilli(), externalId: "SHR${id.substring(1)}".toString(),
                returnChannelEnumId: channel, currencyUomId: "USD", returnTotal: 10, omsReturnClass: "KEEP"]
    }

    /** 20 synthetic returns, ordered by entryDate, around the window [2026-11-10T08:00Z, 2026-11-11T08:00Z). */
    private static List<Map> returns() {
        return [
                ret("R001", "2026-10-02T09:00:00Z"), ret("R002", "2026-11-10T09:30:00Z", "POS_RTN_CHANNEL"),
                ret("R003", "2026-11-10T22:00:00Z", "POS_RTN_CHANNEL"), ret("R004", "2026-11-10T08:00:00Z"),
                ret("R005", "2026-11-10T08:00:01Z"), ret("R006", "2026-11-10T11:00:00Z"), ret("R007", "2026-11-10T13:15:00Z"),
                ret("R008", "2026-11-10T15:45:00Z"), ret("R009", "2026-11-10T18:00:00Z"), ret("R010", "2026-11-10T20:30:00Z"),
                ret("R011", "2026-11-11T00:10:00Z"), ret("R012", "2026-11-11T03:00:00Z"), ret("R013", "2026-11-11T07:59:59Z"),
                ret("R014", "2026-11-10T07:59:59Z"), ret("R015", "2026-11-09T12:00:00Z"), ret("R016", "2026-11-12T12:00:00Z"),
                ret("R017", "2026-11-11T08:00:00Z"), ret("R018", "2026-11-15T12:00:00Z"), ret("R019", "2026-11-18T12:00:00Z"),
                ret("R020", "2026-10-25T12:00:00Z"),
        ]
    }

    private static Map json(File f) {
        return (Map) new JsonSlurper().parse(f, "UTF-8")
    }

    private Map underFacadeGrant(String serviceName, Map params) {
        def harnessFrame = ec.artifactExecution.peek()
        assertEquals("smokeTests", harnessFrame?.name, "test precondition: unexpected artifact stack")
        ec.artifactExecution.pop(harnessFrame)
        ec.artifactExecution.enableAuthz()
        try {
            assertFalse(ec.artifactExecution.authzDisabled, "test precondition: authz must be ON")
            def aei = ec.artifactExecution.push(FACADE_GRANT, ArtifactExecutionInfo.AT_SERVICE, ArtifactExecutionInfo.AUTHZA_ALL, true)
            try {
                ec.message.clearAll()
                Map out = null
                try {
                    out = ec.service.sync().name(serviceName).parameters(new HashMap(params)).call()
                } catch (Exception e) {
                    ec.message.addError(e.toString())
                }
                List<String> errors = ec.message.errors.collect { it.toString() } +
                        ec.message.validationErrors.collect { it.message?.toString() }
                ec.message.clearAll()
                return [out: errors ? null : out, errors: errors]
            } finally {
                ec.artifactExecution.pop(aei)
            }
        } finally {
            ec.artifactExecution.disableAuthz()
            ec.artifactExecution.push("smokeTests", ArtifactExecutionInfo.AT_OTHER, ArtifactExecutionInfo.AUTHZA_ALL, false)
            ec.artifactExecution.setAnonymousAuthorizedAll()
        }
    }

    private void actAs(String username, String tenant) {
        assertTrue(ec.user.internalLoginUser(username), "could not log in ${username}")
        if (tenant) ec.user.setPreference(PREF_KEY, tenant)
        TenantAccessSupport.syncUserContext(ec)
        ec.message.clearErrors()
    }

    private void insert(String entityName, Map<String, Object> fields) {
        ec.entity.makeValue(entityName).setAll(fields).createOrUpdate()
    }
}
