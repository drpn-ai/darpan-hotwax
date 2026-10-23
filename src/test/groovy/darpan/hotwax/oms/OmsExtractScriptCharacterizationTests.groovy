package darpan.hotwax.oms

import darpan.facade.common.SharedConfigAccessSupport
import darpan.facade.common.TenantAccessSupport
import darpan.facade.reconciliation.RunObservability
import darpan.reconciliation.automation.SourceEndpointAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ArtifactExecutionInfo
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * Characterization of the eight OMS extract scripts and the two OMS lookup scripts, dispatched as the
 * REAL Moqui services (a unit test of the support classes cannot reach a script: it has no package, so
 * a wrong import or a dropped binding only fails when the script runs).
 *
 * Pins what each service hands back and leaves on disk: the config gate (missing id, unknown id,
 * inactive config, the endpoint each script names), the outputs each failure path sets and the ones it
 * leaves alone (an input fileName passes straight through a gate failure), the success outputs and the
 * written document, the work file's name prefix while the extract runs and its removal afterwards
 * (success and failure alike), the sidecars, and the throttled progress heartbeat.
 *
 * The REST transport is OmsRestSourceSupport.setHttpClient. The GraphQL scripts build their own
 * OmsGqlClient with the default socket sender, so those cases swap the client's constructor for one
 * that injects a scripted sender — no socket is ever opened.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OmsExtractScriptCharacterizationTests {
    private static final String SVC = "reconciliation.HotWaxOmsExtractionServices."
    private static final String CONFIG_ID = "CHAR_OMS"
    private static final String INACTIVE_ID = "CHAR_OMS_OFF"
    private static final String GATED_ID = "CHAR_OMS_GATED"
    private static final String TENANT = "CHAR_TENANT"
    private static final Timestamp FROM = Timestamp.valueOf("2026-05-01 00:00:00")
    private static final Timestamp THRU = Timestamp.valueOf("2026-05-02 00:00:00")
    private static final String OUT_BASE = "runtime://tmp/oms-extract-script-char"
    private static final JsonSlurper JSON = new JsonSlurper()

    /** Extract service noun -> the endpoint its gate must name. */
    private static final Map<String, String> EXTRACT_ENDPOINTS = [
            HotWaxOmsOrders              : "OMS",
            HotWaxOmsGqlOrders           : "OMS_GQL",
            HotWaxOmsReconciliationOrders: "OMS_RECON_ORDERS",
            HotWaxOmsReturns             : "OMS_RETURNS",
            HotWaxOmsGqlReturns          : "OMS_RETURNS_GQL",
            HotWaxOmsTransferOrders      : "OMS_TRANSFER_ORDERS",
            HotWaxOmsOrderLineUnits      : "OMS_ORDER_LINE_UNITS",
            HotWaxOmsOrderItems          : "OMS_ORDER_ITEMS",
    ]

    private ExecutionContext ec
    private int outSeq = 0

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "hotwax-oms-extract-script-char")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/DarpanSystemSourceSeedData.xml")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/SourceSystemConnectorSeedData.xml")
        // RunObservability.STATUS_* are FK-checked enumerations; without them no run or step row is minted.
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AutomationSeedData.xml")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        upsert("moqui.security.UserGroup", [userGroupId: TENANT], [userGroupId: TENANT,
                description: "Extract script tenant", groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID])
        upsert("moqui.basic.EnumerationType", [enumTypeId: "DarpanSharedConfigType"],
                [enumTypeId: "DarpanSharedConfigType", description: "Darpan API source config types"])
        upsert("moqui.basic.Enumeration", [enumId: SharedConfigAccessSupport.CONFIG_TYPE_HOTWAX_OMS],
                [enumId: SharedConfigAccessSupport.CONFIG_TYPE_HOTWAX_OMS, enumTypeId: "DarpanSharedConfigType", description: "HotWax OMS"])
        [(CONFIG_ID): "Y", (INACTIVE_ID): "N", (GATED_ID): "Y"].each { String id, String active ->
            upsert("darpan.hotwax.HotWaxOmsRestSourceConfig", [omsRestSourceConfigId: id], [
                    omsRestSourceConfigId: id, description: id, companyUserGroupId: TENANT,
                    baseUrl: "https://char.hotwax.io", ordersPath: "/rest/s1/oms/orders", authType: "NONE",
                    connectTimeoutSeconds: 5, readTimeoutSeconds: 10, isActive: active, canReadOrders: "Y",
                    createdDate: FROM, lastUpdatedDate: FROM])
        }
        EXTRACT_ENDPOINTS.values().each { String endpoint ->
            upsert(SourceEndpointAccessSupport.ENTITY_NAME, [configTypeEnumId: SharedConfigAccessSupport.CONFIG_TYPE_HOTWAX_OMS,
                    configId: GATED_ID, systemEnumId: endpoint], [
                    configTypeEnumId: SharedConfigAccessSupport.CONFIG_TYPE_HOTWAX_OMS, configId: GATED_ID,
                    systemEnumId: endpoint, companyUserGroupId: TENANT, isEnabled: "N"])
        }
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void clearMessages() {
        ec.message.clearErrors()
    }

    @AfterEach
    void resetSeams() {
        ec.message.clearErrors()
        OmsRestSourceSupport.resetHttpClient()
        OmsReturnsSourceSupport.resetGqlClientFactory()
        GroovySystem.metaClassRegistry.removeMetaClass(OmsGqlClient)
    }

    // ---- the effective contracts ----------------------------------------------------------------

    /**
     * Four services take their parameters through <implements> of a sibling, and a script service DROPS any
     * result key it does not declare. Pin each service's effective in/out names, in order, as Moqui merges them.
     */
    @Test
    void everyServiceKeepsItsEffectiveParameterNamesInOrder() {
        List<String> windowedIn = ["omsRestSourceConfigId", "automationExecutionId", "companyUserGroupId", "windowStart",
                                   "windowEnd", "outputLocation", "fileName", "keepRecordFields", "sourceFilters",
                                   "reconciliationRunResultId", "progressStageCode", "expectedRecordCount"]
        List<String> unitIn = ["omsRestSourceConfigId", "automationExecutionId", "companyUserGroupId", "windowStart",
                               "windowEnd", "windowFieldName", "orderStatusIds", "outputLocation", "fileName", "sourceFilters",
                               "reconciliationRunResultId", "progressStageCode", "expectedRecordCount"]
        List<String> ordersOut = ["dataAvailable", "fileLocation", "exchangeManifestFileLocation", "fileName", "recordCount",
                                  "requestMetadata", "warnings", "errors"]
        List<String> plainOut = ["dataAvailable", "fileLocation", "fileName", "recordCount", "requestMetadata", "warnings", "errors"]
        Map<String, List<List<String>>> expected = [
                "extract#HotWaxOmsOrders"              : [windowedIn, ordersOut],
                "extract#HotWaxOmsGqlOrders"           : [windowedIn, ordersOut],
                "extract#HotWaxOmsReconciliationOrders": [windowedIn, ordersOut],
                "extract#HotWaxOmsReturns"             : [windowedIn, plainOut],
                // DAR-BE-094 R8 / R16 added the two returns-scope switches.
                "extract#HotWaxOmsGqlReturns"          : [windowedIn + ["includeReturnsWithoutShopifyRef", "onlyCancelledReturns"], plainOut],
                "extract#HotWaxOmsTransferOrders"      : [windowedIn[0..4] + ["windowFieldName", "orderStatusIds"] + windowedIn[5..-1], plainOut],
                "extract#HotWaxOmsOrderLineUnits"      : [unitIn, plainOut],
                "extract#HotWaxOmsOrderItems"          : [unitIn, plainOut],
                "lookup#HotWaxOmsOrdersByExternalId"   : [["omsRestSourceConfigId", "externalIds", "companyUserGroupId",
                                                           "windowStartMillis", "windowEndMillis"], ["ok", "ordersByExternalId", "errors"]],
                "lookup#HotWaxOmsReturnsByExternalId"  : [["omsRestSourceConfigId", "externalIds", "companyUserGroupId"],
                                                          ["ok", "foundIds", "missingIds", "unresolvedIds", "errors"]],
        ]
        expected.each { String name, List<List<String>> inOut ->
            def sd = ec.factory.serviceFacade.getServiceDefinition(SVC + name)
            assertNotNull(sd, name)
            assertEquals(inOut[0], sd.getInParameterNames() as List, "${name} in-parameters")
            assertEquals(inOut[1], sd.getOutParameterNames() as List, "${name} out-parameters")
        }
        // Required-ness survives the merge: the windowed extracts require a window, transfer orders do not.
        ["extract#HotWaxOmsGqlOrders", "extract#HotWaxOmsReconciliationOrders", "extract#HotWaxOmsGqlReturns",
         "extract#HotWaxOmsOrderItems"].each { String name ->
            def sd = ec.factory.serviceFacade.getServiceDefinition(SVC + name)
            ["omsRestSourceConfigId", "windowStart", "windowEnd"].each { String p ->
                assertEquals("true", sd.getInParameter(p).attribute("required"), "${name}.${p}")
            }
        }
        assertNull(ec.factory.serviceFacade.getServiceDefinition(SVC + "extract#HotWaxOmsTransferOrders")
                .getInParameter("windowStart").attribute("required"))
    }

    // ---- the config gate, every extract -------------------------------------------------------

    @Test
    void aBlankConfigIdFailsBeforeTheConfigIsReadAndLeavesNoOutputDirectory() {
        EXTRACT_ENDPOINTS.keySet().each { String noun ->
            String out = freshOut()
            Map result = extract(noun, baseParams(out) + [omsRestSourceConfigId: "   ", fileName: "keep-me.json"])
            assertEquals(["OMS REST Source Config ID is required."], result.errors, noun)
            assertGateFailureShape(noun, result, out)
        }
    }

    @Test
    void anUnknownConfigIsNotFound() {
        EXTRACT_ENDPOINTS.keySet().each { String noun ->
            String out = freshOut()
            Map result = extract(noun, baseParams(out) + [omsRestSourceConfigId: "NO_SUCH_CONFIG", fileName: "keep-me.json"])
            assertEquals(["OMS REST source config NO_SUCH_CONFIG not found."], result.errors, noun)
            assertGateFailureShape(noun, result, out)
        }
    }

    @Test
    void anInactiveConfigIsRefused() {
        EXTRACT_ENDPOINTS.keySet().each { String noun ->
            String out = freshOut()
            Map result = extract(noun, baseParams(out) + [omsRestSourceConfigId: INACTIVE_ID, fileName: "keep-me.json"])
            assertEquals(["OMS REST source config ${INACTIVE_ID} is inactive.".toString()], result.errors, noun)
            assertGateFailureShape(noun, result, out)
        }
    }

    @Test
    void withNoTrustedTenantTheSessionTenantGateRunsBeforeTheInactiveCheck() {
        EXTRACT_ENDPOINTS.keySet().each { String noun ->
            String out = freshOut()
            Map params = baseParams(out) + [omsRestSourceConfigId: INACTIVE_ID, fileName: "keep-me.json"]
            params.remove("companyUserGroupId")
            Map result = extract(noun, params)
            assertEquals(["OMS REST source config ${INACTIVE_ID} not found.".toString(),
                          "OMS REST source config ${INACTIVE_ID} is inactive.".toString()], result.errors, noun)
            assertGateFailureShape(noun, result, out)
        }
        ["lookup#HotWaxOmsOrdersByExternalId", "lookup#HotWaxOmsReturnsByExternalId"].each { String lookup ->
            Map result = callService(lookup, [omsRestSourceConfigId: INACTIVE_ID, externalIds: ["E1"]])
            assertEquals(["OMS REST source config ${INACTIVE_ID} not found.".toString(),
                          "OMS REST source config ${INACTIVE_ID} is inactive.".toString()], result.errors, lookup)
            assertEquals(false, result.ok, lookup)
        }
    }

    @Test
    void eachExtractGatesOnItsOwnEndpoint() {
        EXTRACT_ENDPOINTS.each { String noun, String endpoint ->
            String out = freshOut()
            Map result = extract(noun, baseParams(out) + [omsRestSourceConfigId: GATED_ID, fileName: "keep-me.json"])
            assertEquals(["OMS REST source config ${GATED_ID} is not enabled for ${endpoint}.".toString()], result.errors, noun)
            assertGateFailureShape(noun, result, out)
        }
    }

    /** A gate failure sets exactly errors/warnings/dataAvailable/recordCount; an input fileName passes through. */
    private void assertGateFailureShape(String noun, Map result, String out) {
        assertEquals([], result.warnings, noun)
        assertEquals(false, result.dataAvailable, noun)
        assertEquals(0, result.recordCount, noun)
        assertEquals("keep-me.json", result.fileName, "${noun}: the input fileName is not cleared by a gate failure")
        // Moqui drops null outputs, so "unset" and "set to null" read the same here; requestMetadata is
        // the one non-null output a gate failure never sets.
        assertFalse(result.containsKey("fileLocation"), "${noun}: ${result}")
        assertFalse(result.containsKey("requestMetadata"), "${noun}: ${result}")
        assertFalse(result.containsKey("exchangeManifestFileLocation"), "${noun}: ${result}")
        assertFalse(outDir(out).exists(), "${noun}: the gate runs before the output directory is created")
    }

    // ---- REST order-shaped extracts: success --------------------------------------------------

    @Test
    void ordersWritesTheDocumentAndRemovesItsWorkFile() {
        String out = freshOut()
        List<String> partialsSeen = []
        List<String> urls = []
        serveRest(out, partialsSeen, urls) { String url -> [orders: [salesOrder("O1"), salesOrder("O2")]] }

        Map result = extract("HotWaxOmsOrders", baseParams(out))

        assertEquals([], result.errors)
        assertEquals([], result.warnings)
        assertEquals(true, result.dataAvailable)
        assertEquals(2, result.recordCount)
        assertEquals(expectedOrdersFileName(), result.fileName)
        assertEquals("${out}/${result.fileName}".toString(), result.fileLocation)
        assertNull(result.exchangeManifestFileLocation)
        assertTrue(result.requestMetadata instanceof Map)
        assertEquals(["O1", "O2"], records(result)*.orderId)
        assertTrue(partialsSeen.every { it.startsWith("oms-orders-extract-") && it.endsWith(".partial") }, partialsSeen.toString())
        assertFalse(partialsSeen.isEmpty(), "the work file sits in the output directory while pages arrive")
        assertEquals([result.fileName], listing(out))
    }

    @Test
    void ordersHonoursAnExplicitFileNameAndKeepRecordFields() {
        String out = freshOut()
        serveRest(out, [], []) { String url -> [orders: [salesOrder("O1")]] }

        Map result = extract("HotWaxOmsOrders", baseParams(out) + [fileName: "../my orders.json", keepRecordFields: ["orderId"]])

        assertEquals([], result.errors)
        assertEquals(OmsRestSourceSupport.safeFileName("../my orders.json"), result.fileName)
        assertEquals([[orderId: "O1"]], records(result))
        assertEquals([result.fileName], listing(out))
    }

    @Test
    void ordersWritesTheExchangeManifestSidecar() {
        String out = freshOut()
        serveRest(out, [], []) { String url ->
            [orders: [salesOrder("O1"), salesOrder("X1", [orderName: "EXC-1", orderItemAssocs: [[orderItemAssocTypeId: "EXCHANGE", toOrderId: "O9"]]])]]
        }

        Map result = extract("HotWaxOmsOrders", baseParams(out))

        assertEquals([], result.errors)
        assertEquals(["O1"], records(result)*.orderId)
        assertNotNull(result.exchangeManifestFileLocation, result.toString())
        String manifestName = OmsRestSourceSupport.exchangeManifestFileName(result.fileName)
        assertEquals("${out}/${manifestName}".toString(), result.exchangeManifestFileLocation)
        Map manifest = (Map) JSON.parseText(fileAt((String) result.exchangeManifestFileLocation))
        assertEquals(result.fileName, manifest.sourceFileName)
        assertEquals(false, manifest.truncated)
        assertEquals(["X1"], ((List<Map>) manifest.manifest)*.omsOrderId)
        assertEquals([result.fileName, manifestName].sort(), listing(out))
    }

    @Test
    void reconciliationOrdersUsesTheReconEndpointAndWritesTheInlineExchangeManifest() {
        String out = freshOut()
        List<String> partialsSeen = []
        List<String> urls = []
        serveRest(out, partialsSeen, urls) { String url ->
            reconPage([reconOrder("O1"), reconOrder("X1") + [isExchange: true, originalOrderId: "O9", originalExternalId: "E9"]], false)
        }

        Map result = extract("HotWaxOmsReconciliationOrders", baseParams(out))

        assertEquals([], result.errors)
        assertEquals(1, result.recordCount)
        assertEquals(expectedOrdersFileName(), result.fileName)
        assertTrue(urls.every { it.contains("/rest/s1/oms/reconciliationOrders") }, urls.toString())
        assertTrue(partialsSeen.every { it.startsWith("oms-recon-orders-extract-") }, partialsSeen.toString())
        assertFalse(partialsSeen.isEmpty())
        String manifestName = OmsRestSourceSupport.exchangeManifestFileName(result.fileName)
        assertEquals("${out}/${manifestName}".toString(), result.exchangeManifestFileLocation)
        Map manifest = (Map) JSON.parseText(fileAt((String) result.exchangeManifestFileLocation))
        assertEquals("O9", ((List<Map>) manifest.manifest)[0].toOrderId)
        assertEquals([result.fileName, manifestName].sort(), listing(out))
    }

    @Test
    void transferOrdersSendsTheTransferTypeWindowFieldAndStatuses() {
        String out = freshOut()
        List<String> partialsSeen = []
        List<String> urls = []
        serveRest(out, partialsSeen, urls) { String url -> [orders: [salesOrder("T1", [orderTypeId: "TRANSFER_ORDER"])]] }

        Map result = extract("HotWaxOmsTransferOrders", baseParams(out) + [windowFieldName: " lastUpdatedTxStamp ",
                orderStatusIds: ["ORDER_APPROVED"]])

        assertEquals([], result.errors)
        assertEquals(1, result.recordCount)
        assertEquals(["T1"], records(result)*.orderId)
        assertTrue(urls.every { it.contains("TRANSFER_ORDER") }, urls.toString())
        assertTrue(urls.every { it.contains("lastUpdatedTxStamp") }, urls.toString())
        assertTrue(urls.every { it.contains("ORDER_APPROVED") }, urls.toString())
        assertTrue(partialsSeen.every { it.startsWith("oms-transfer-orders-extract-") }, partialsSeen.toString())
        assertFalse(partialsSeen.isEmpty())
        assertFalse(result.containsKey("exchangeManifestFileLocation"))
        assertEquals([result.fileName], listing(out))
    }

    @Test
    void orderLineUnitsWarnsAboutUnkeyedUnitsAndWritesTheExcludedSidecar() {
        String out = freshOut()
        List<String> partialsSeen = []
        serveRest(out, partialsSeen, []) { String url ->
            [orders: [salesOrder("M1", [shipGroups: [[facilityId: "WH", items: [
                    [orderItemSeqId: "01", externalId: "L1", statusId: "ITEM_COMPLETED", quantity: 1],
                    [orderItemSeqId: "02", externalId: null, statusId: "ITEM_COMPLETED", quantity: 1],
                    [orderItemSeqId: "03", externalId: "L3", statusId: "ITEM_CANCELLED", quantity: 1]]]]])]]
        }

        Map result = extract("HotWaxOmsOrderLineUnits", baseParams(out) + [keepRecordFields: ["orderId"],
                sourceFilters: [[sequenceNum: 1, fieldExpression: "statusId", operator: "EXCLUDE_IN", filterValues: "ITEM_CANCELLED"]]])

        assertEquals([], result.errors)
        assertEquals(["1 order item(s) had no Shopify line id and were not compared."], result.warnings)
        assertEquals(1, result.recordCount)
        assertEquals(1, ((Map) result.requestMetadata).droppedNullLineIdCount)
        assertTrue(records(result)[0].containsKey("unitOrdinal"), "keepRecordFields is NOT applied at unit grain")
        assertTrue(partialsSeen.every { it.startsWith("oms-order-line-units-extract-") }, partialsSeen.toString())
        assertFalse(partialsSeen.isEmpty())
        assertFalse(result.containsKey("exchangeManifestFileLocation"))
        List<String> files = listing(out)
        assertTrue(files.contains(result.fileName), files.toString())
        assertEquals(2, files.size(), "the extract plus the excluded-records sidecar: ${files}")
    }

    @Test
    void orderItemsKeepsUnkeyedItemsWithAWarningAndWritesTheExcludedSidecar() {
        String out = freshOut()
        List<String> partialsSeen = []
        serveRest(out, partialsSeen, []) { String url ->
            [orders: [salesOrder("M1", [shipGroups: [[facilityId: "WH", items: [
                    [orderItemSeqId: "01", externalId: "L1", statusId: "ITEM_COMPLETED", quantity: 1],
                    [orderItemSeqId: "02", externalId: null, statusId: "ITEM_COMPLETED", quantity: 1],
                    [orderItemSeqId: "03", externalId: "L3", statusId: "ITEM_CANCELLED", quantity: 1]]]]])]]
        }

        Map result = extract("HotWaxOmsOrderItems", baseParams(out) + [
                sourceFilters: [[sequenceNum: 1, fieldExpression: "unitState", operator: "INCLUDE_IN", filterValues: "COMPLETED"]]])

        assertEquals([], result.errors)
        assertEquals(["1 order item(s) had no Shopify order or line id; they were compared with it left empty."], result.warnings)
        assertEquals(2, result.recordCount)
        assertTrue(partialsSeen.every { it.startsWith("oms-order-items-extract-") }, partialsSeen.toString())
        assertFalse(partialsSeen.isEmpty())
        List<String> files = listing(out)
        assertTrue(files.contains(result.fileName), files.toString())
        assertEquals(2, files.size(), "the extract plus the excluded-records sidecar: ${files}")
    }

    @Test
    void returnsWritesTheReturnsDocumentUnderTheReturnsFileName() {
        String out = freshOut()
        List<String> partialsSeen = []
        List<String> urls = []
        serveRest(out, partialsSeen, urls) { String url -> returnsPage([returnRecord("R1", "5001")], false) }

        Map result = extract("HotWaxOmsReturns", baseParams(out))

        assertEquals([], result.errors)
        assertEquals(1, result.recordCount)
        assertEquals(true, result.dataAvailable)
        assertTrue(((String) result.fileName).startsWith("oms-returns-"), result.fileName as String)
        assertEquals(OmsReturnsSourceSupport.safeReturnsFileName(result.fileName), result.fileName)
        assertEquals(["R1"], records(result)*.returnId)
        assertTrue(urls.every { it.contains("/rest/s1/oms/reconciliationReturns") }, urls.toString())
        assertTrue(partialsSeen.every { it.startsWith("oms-returns-extract-") }, partialsSeen.toString())
        assertFalse(partialsSeen.isEmpty())
        assertFalse(result.containsKey("exchangeManifestFileLocation"))
        assertEquals([result.fileName], listing(out))
    }

    // ---- REST extracts: failure inside the extraction -----------------------------------------

    @Test
    void anExtractionErrorClearsTheFileOutputsAndDeletesTheWorkFile() {
        EXTRACT_ENDPOINTS.keySet().findAll { !it.contains("Gql") }.each { String noun ->
            String out = freshOut()
            List<String> partialsSeen = []
            serveRestStatus(out, partialsSeen, 401, '{"errors":"Unauthorized"}')

            Map result = extract(noun, baseParams(out) + [fileName: "keep-me.json"])

            assertFalse(((List) result.errors).isEmpty(), "${noun}: ${result}")
            assertNull(result.fileName, "${noun}: an extraction error clears the input fileName")
            assertNull(result.fileLocation, noun)
            assertTrue(result.requestMetadata instanceof Map, noun)
            assertEquals(0, result.recordCount, noun)
            assertEquals(false, result.dataAvailable, noun)
            assertFalse(partialsSeen.isEmpty(), "${noun}: the work file existed during the call")
            assertEquals([], listing(out), "${noun}: the work file is deleted on failure")
        }
    }

    @Test
    void aTransportExceptionPropagatesAfterTheWorkFileIsDeleted() {
        EXTRACT_ENDPOINTS.keySet().findAll { !it.contains("Gql") }.each { String noun ->
            String out = freshOut()
            OmsRestSourceSupport.setHttpClient { Map request -> throw new IllegalStateException("socket closed") }

            Map result = extract(noun, baseParams(out))

            assertEquals([], listing(out), "${noun}: no work file survives a thrown transport error")
            assertEquals(0, (result.recordCount ?: 0) as int, "${noun}: ${result}")
            assertFalse(result.dataAvailable == true, noun)
        }
    }

    // ---- GraphQL extracts ----------------------------------------------------------------------

    @Test
    void gqlOrdersWritesTheDocumentAndTheExchangeManifest() {
        String out = freshOut()
        List<String> partialsSeen = []
        serveGql(out, partialsSeen, [
                orders         : { Map v -> conn([gqlOrder("G1"), gqlOrder("G2")]) },
                orderItemAssocs: { Map v -> conn([[orderId: "G2", orderItemSeqId: "01", toOrderId: "G9", toOrderItemSeqId: "01",
                                                   orderItemAssocTypeId: "EXCHANGE"]]) },
        ])

        Map result = extract("HotWaxOmsGqlOrders", baseParams(out) + [
                sourceFilters: [[sequenceNum: 1, fieldExpression: "statusId", operator: "EXCLUDE_IN", filterValues: "ORDER_CANCELLED"]]])

        assertEquals([], result.errors, result.toString())
        assertEquals([], result.warnings)
        assertEquals(1, result.recordCount, "the exchange order is excluded into the manifest")
        assertEquals(["G1"], records(result)*.orderId)
        assertEquals(expectedOrdersFileName(), result.fileName)
        assertEquals("${out}/${result.fileName}".toString(), result.fileLocation)
        assertTrue(partialsSeen.every { it.startsWith("oms-gql-orders-extract-") }, partialsSeen.toString())
        assertFalse(partialsSeen.isEmpty())
        String manifestName = OmsRestSourceSupport.exchangeManifestFileName(result.fileName)
        assertEquals("${out}/${manifestName}".toString(), result.exchangeManifestFileLocation)
        // The excluded-records sidecar is attempted but empty at order grain, so it writes nothing.
        assertEquals([result.fileName, manifestName].sort(), listing(out))
    }

    @Test
    void gqlReturnsWritesTheReturnsDocument() {
        String out = freshOut()
        List<String> partialsSeen = []
        serveGql(out, partialsSeen, [
                returns    : { Map v -> conn([gqlReturn("M0001")]) },
                returnItems: { Map v -> conn([[returnId: "M0001", returnItemSeqId: "01", orderId: "GR1", orderItemSeqId: "00101",
                                               productId: "P1", returnQuantity: 1, receivedQuantity: 0, returnPrice: 65,
                                               returnReasonId: "UNKNOWN", statusId: "RETURN_REQUESTED", returnTypeId: "RTN_REFUND"]]) },
                orders     : { Map v -> conn([[orderId: "GR1", externalId: "7186636636291", productStoreId: "STORE"]]) },
                orderItems : { Map v -> conn([[orderId: "GR1", orderItemSeqId: "00101", externalId: "16501091238019"]]) },
                products   : { Map v -> conn([[productId: "P1", internalName: "219-001-G"]]) },
                goodIdentifications: { Map v -> conn([]) },
        ])

        Map result = extract("HotWaxOmsGqlReturns", baseParams(out))

        assertEquals([], result.errors, result.toString())
        assertEquals(1, result.recordCount)
        assertEquals(true, result.dataAvailable)
        assertTrue(((String) result.fileName).startsWith("oms-returns-"), result.fileName as String)
        assertEquals(["M0001"], records(result)*.returnId)
        assertTrue(partialsSeen.every { it.startsWith("oms-gql-returns-extract-") }, partialsSeen.toString())
        assertFalse(partialsSeen.isEmpty())
        assertFalse(result.containsKey("exchangeManifestFileLocation"))
        assertEquals([result.fileName], listing(out))
    }

    @Test
    void aGqlErrorClearsTheFileOutputsAndDeletesTheWorkFile() {
        ["HotWaxOmsGqlOrders", "HotWaxOmsGqlReturns"].each { String noun ->
            String out = freshOut()
            List<String> partialsSeen = []
            serveGql(out, partialsSeen, [:], true)

            Map result = extract(noun, baseParams(out) + [fileName: "keep-me.json"])

            assertFalse(((List) result.errors).isEmpty(), "${noun}: ${result}")
            assertNull(result.fileName, noun)
            assertNull(result.fileLocation, noun)
            assertEquals(0, result.recordCount, noun)
            assertEquals(false, result.dataAvailable, noun)
            assertFalse(partialsSeen.isEmpty(), noun)
            assertEquals([], listing(out), noun)
        }
    }

    // ---- progress heartbeat --------------------------------------------------------------------

    @Test
    void theHeartbeatLandsOnTheNamedStageAndIsThrottledAcrossPages() {
        String out = freshOut()
        String runId = RunObservability.beginRun(ec, [companyUserGroupId: TENANT])
        def step = RunObservability.beginStep(ec, runId, [companyUserGroupId: TENANT], RunObservability.STAGE_EXTRACT_FILE1)
        assertNotNull(step)
        serveRest(out, [], []) { String url ->
            int pageIndex = queryInt(url, "pageIndex")
            pageIndex < 2 ? reconPage([reconOrder("P${pageIndex}A"), reconOrder("P${pageIndex}B")], true, 5)
                          : reconPage([reconOrder("P2A")], false, 5)
        }

        Map result = extract("HotWaxOmsReconciliationOrders", baseParams(out) + [reconciliationRunResultId: " ${runId} ",
                progressStageCode: " EXTRACT_FILE1 ", expectedRecordCount: 10])

        assertEquals([], result.errors)
        assertEquals(5, result.recordCount)
        Map stepRow = stepRow(runId, RunObservability.STAGE_EXTRACT_FILE1)
        assertEquals(2L, (stepRow.recordCount as Long), "only the first page's heartbeat lands inside the 2s throttle")
        assertEquals(20L, (runRow(runId).progressPercent as Long), "percent = first heartbeat / expectedRecordCount")
    }

    @Test
    void theHeartbeatDefaultsToFile2AndOmitsThePercentWithoutAnExpectedTotal() {
        EXTRACT_ENDPOINTS.keySet().findAll { !it.contains("Gql") }.each { String noun ->
            String out = freshOut()
            String runId = RunObservability.beginRun(ec, [companyUserGroupId: TENANT])
            RunObservability.beginStep(ec, runId, [companyUserGroupId: TENANT], RunObservability.STAGE_EXTRACT_FILE2)
            serveRest(out, [], []) { String url ->
                if (noun == "HotWaxOmsReconciliationOrders") return reconPage([reconOrder("O1")], false)
                if (noun == "HotWaxOmsReturns") return returnsPage([returnRecord("R1", "5001")], false)
                return [orders: [salesOrder("O1", [shipGroups: [[facilityId: "WH", items: [
                        [orderItemSeqId: "01", externalId: "L1", statusId: "ITEM_COMPLETED", quantity: 1]]]]])]]
            }

            Map result = extract(noun, baseParams(out) + [reconciliationRunResultId: runId])

            assertEquals([], result.errors, noun)
            assertTrue(((stepRow(runId, RunObservability.STAGE_EXTRACT_FILE2).recordCount ?: 0) as Long) > 0L,
                    "${noun}: the heartbeat targets EXTRACT_FILE2 when no stage code is passed")
            assertNull(runRow(runId).progressPercent, "${noun}: no expected total, no percent")
        }
    }

    @Test
    void noRunIdMeansNoHeartbeat() {
        String out = freshOut()
        String runId = RunObservability.beginRun(ec, [companyUserGroupId: TENANT])
        RunObservability.beginStep(ec, runId, [companyUserGroupId: TENANT], RunObservability.STAGE_EXTRACT_FILE2)
        serveRest(out, [], []) { String url -> [orders: [salesOrder("O1")]] }

        Map result = extract("HotWaxOmsOrders", baseParams(out) + [reconciliationRunResultId: "  ", expectedRecordCount: 4])

        assertEquals([], result.errors)
        assertNull(stepRow(runId, RunObservability.STAGE_EXTRACT_FILE2).recordCount)
    }

    // ---- lookups --------------------------------------------------------------------------------

    @Test
    void lookupOrdersGateFailuresReturnOnlyErrorsBesideTheEmptyDefaults() {
        ["   ": "OMS REST Source Config ID is required.",
         NO_SUCH_CONFIG: "OMS REST source config NO_SUCH_CONFIG not found.",
         (INACTIVE_ID): "OMS REST source config ${INACTIVE_ID} is inactive.".toString(),
         (GATED_ID): "OMS REST source config ${GATED_ID} is not enabled for OMS.".toString()].each { String id, String error ->
            ec.message.clearErrors()
            Map result = callService("lookup#HotWaxOmsOrdersByExternalId", [omsRestSourceConfigId: id, externalIds: ["E1"],
                    companyUserGroupId: TENANT])
            assertEquals([error], result.errors, id)
            assertEquals(false, result.ok, id)
            assertEquals([:], result.ordersByExternalId, id)
        }
    }

    @Test
    void lookupOrdersDefaultsToATenYearWindowEndingTomorrow() {
        List<String> urls = []
        OmsRestSourceSupport.setHttpClient { Map request ->
            urls.add(request.url as String)
            [statusCode: 200, body: JsonOutput.toJson([orders: [salesOrder("O1", [externalId: "E1"])]])]
        }
        long before = System.currentTimeMillis()

        Map result = callService("lookup#HotWaxOmsOrdersByExternalId", [omsRestSourceConfigId: CONFIG_ID, externalIds: ["E1"],
                companyUserGroupId: TENANT])

        assertEquals(true, result.ok, result.toString())
        assertEquals([], result.errors)
        assertEquals(["E1"], (result.ordersByExternalId as Map).keySet() as List)
        long from = (urls.first() =~ /orderDate_from=(\d+)/)[0][1] as long
        long thru = (urls.first() =~ /orderDate_thru=(\d+)/)[0][1] as long
        assertEquals(3650L * 86400000L, thru - from)
        assertTrue(thru >= before + 86400000L && thru <= System.currentTimeMillis() + 86400000L, "thru = now + 1 day")
    }

    @Test
    void lookupOrdersHonoursAnExplicitWindow() {
        List<String> urls = []
        OmsRestSourceSupport.setHttpClient { Map request ->
            urls.add(request.url as String)
            [statusCode: 200, body: JsonOutput.toJson([orders: []])]
        }

        Map result = callService("lookup#HotWaxOmsOrdersByExternalId", [omsRestSourceConfigId: CONFIG_ID, externalIds: ["E1"],
                companyUserGroupId: TENANT, windowStartMillis: 1777593600000L, windowEndMillis: 1777680000000L])

        assertTrue(urls.first().contains("orderDate_from=1777593600000"), urls.first())
        assertTrue(urls.first().contains("orderDate_thru=1777680000000"), urls.first())
        assertNotNull(result.ok)
    }

    @Test
    void lookupReturnsGateFailuresReturnOnlyErrorsBesideTheEmptyDefaults() {
        ["   ": "OMS REST Source Config ID is required.",
         NO_SUCH_CONFIG: "OMS REST source config NO_SUCH_CONFIG not found.",
         (INACTIVE_ID): "OMS REST source config ${INACTIVE_ID} is inactive.".toString(),
         (GATED_ID): "OMS REST source config ${GATED_ID} is not enabled for OMS.".toString()].each { String id, String error ->
            ec.message.clearErrors()
            Map result = callService("lookup#HotWaxOmsReturnsByExternalId", [omsRestSourceConfigId: id, externalIds: ["E1"],
                    companyUserGroupId: TENANT])
            assertEquals([error], result.errors, id)
            assertEquals(false, result.ok, id)
            assertEquals([], result.foundIds, id)
            assertEquals([], result.missingIds, id)
            assertEquals([], result.unresolvedIds, id)
        }
    }

    @Test
    void lookupReturnsSplitsFoundFromMissingAndReportsUnresolved() {
        OmsReturnsSourceSupport.setGqlClientFactory { Map config ->
            // DAR-BE-116: GraphQL only. "aaa" is a Shopify refund id the externalId pass finds; "bbb" is nowhere.
            OmsGqlClient c = new OmsGqlClient(config, { String url, Map headers, String body ->
                Map payload = (Map) new groovy.json.JsonSlurper().parseText(body)
                String q = ((Map) payload.variables).q as String
                if ((payload.query as String).contains("returns(")) {
                    List rows = q.startsWith("externalId:") && q.contains("aaa") ? [[node: [returnId: "M1", externalId: "aaa"]]] : []
                    return [statusCode: 200, body: JsonOutput.toJson([data: [returns: [edges: rows,
                            pageInfo: [hasNextPage: false]]], errors: []])]
                }
                [statusCode: 200, body: JsonOutput.toJson([data: [returnIdentifications: [edges: [],
                        pageInfo: [hasNextPage: false]]], errors: []])]
            })
            c.sleeper = { long ms -> }
            return c
        }
        OmsRestSourceSupport.setHttpClient { Map request -> throw new AssertionError("REST must not be called") }

        Map result = callService("lookup#HotWaxOmsReturnsByExternalId", [omsRestSourceConfigId: CONFIG_ID,
                externalIds: ["aaa", "bbb"], companyUserGroupId: TENANT])

        assertEquals(true, result.ok, result.toString())
        assertEquals(["aaa"], result.foundIds)
        assertEquals(["bbb"], result.missingIds)
        assertEquals([], result.unresolvedIds)
        assertEquals([], result.errors)
    }

    // ---- fixtures -------------------------------------------------------------------------------

    private Map baseParams(String out) {
        return [omsRestSourceConfigId: CONFIG_ID, companyUserGroupId: TENANT, windowStart: FROM, windowEnd: THRU,
                outputLocation: out]
    }

    private static String expectedOrdersFileName() {
        return "oms-orders-${FROM.time}-${THRU.time}.json".toString()
    }

    private Map extract(String noun, Map params) {
        return callService("extract#${noun}", params)
    }

    private Map callService(String verbNoun, Map params) {
        ec.message.clearErrors()
        Map result = (Map) ec.service.sync().name(SVC + verbNoun).parameters(params).disableAuthz().call()
        // A gate failure hands back ec.message's LIVE error list, so copy before clearing the messages.
        Map copy = [:]
        (result ?: [:]).each { k, v -> copy.put(k, v instanceof List ? new ArrayList((List) v) : v) }
        ec.message.clearErrors()
        return copy
    }

    private String freshOut() {
        String loc = "${OUT_BASE}/${System.nanoTime()}-${outSeq++}".toString()
        File dir = outDir(loc)
        if (dir.exists()) dir.deleteDir()
        return loc
    }

    private File outDir(String loc) {
        return new File(ec.factory.runtimePath, loc.replace("runtime://", ""))
    }

    private List<String> listing(String loc) {
        File dir = outDir(loc)
        return dir.exists() ? (dir.list() as List<String>).sort() : []
    }

    private String fileAt(String loc) {
        return ec.resource.getLocationReference(loc).getText()
    }

    private List<Map> records(Map result) {
        return (List<Map>) ((Map) JSON.parseText(fileAt((String) result.fileLocation))).records
    }

    private List<String> partials(String loc) {
        return listing(loc).findAll { it.endsWith(".partial") }
    }

    private void serveRest(String out, List<String> partialsSeen, List<String> urls, Closure bodyFor) {
        OmsRestSourceSupport.setHttpClient { Map request ->
            String url = request.url as String
            urls.add(url)
            partialsSeen.addAll(partials(out))
            Object body = bodyFor.call(url)
            return [statusCode: 200, body: body instanceof String ? body : JsonOutput.toJson(body)]
        }
    }

    private void serveRestStatus(String out, List<String> partialsSeen, int status, String body) {
        OmsRestSourceSupport.setHttpClient { Map request ->
            partialsSeen.addAll(partials(out))
            return [statusCode: status, body: body]
        }
    }

    /** Swaps OmsGqlClient's one-arg constructor (the one the scripts call) for a scripted sender. */
    private void serveGql(String out, List<String> partialsSeen, Map<String, Closure> handlers, boolean fail = false) {
        Closure sender = { String url, Map headers, String body ->
            partialsSeen.addAll(partials(out))
            if (fail) return [statusCode: 200, body: JsonOutput.toJson([data: null, errors: [[message: "boom",
                    extensions: [code: "INTERNAL"]]]])]
            Map payload = (Map) JSON.parseText(body)
            String root = (payload.query =~ /\{\s*(\w+)\(/)[0][1]
            Closure h = handlers[root]
            if (h == null) throw new AssertionError("unexpected GraphQL root ${root}")
            return [statusCode: 200, body: JsonOutput.toJson([data: [(root): h.call(payload.variables)], errors: []])]
        }
        java.lang.reflect.Constructor ctor = OmsGqlClient.getConstructor(Map, Closure)
        OmsGqlClient.metaClass.constructor = { Map config ->
            OmsGqlClient c = (OmsGqlClient) ctor.newInstance(config, sender)
            c.sleeper = { long ms -> }
            return c
        }
    }

    private static Map conn(List nodes, String endCursor = null) {
        return [edges: nodes.collect { [node: it] }, pageInfo: [hasNextPage: endCursor != null, endCursor: endCursor]]
    }

    private static Map salesOrder(String id, Map extra = [:]) {
        return [orderId: id, orderName: "#${id}".toString(), externalId: "EXT-${id}".toString(), orderTypeId: "SALES_ORDER",
                statusId: "ORDER_APPROVED", grandTotal: 10, orderDate: 1777600000000L] + extra
    }

    private static Map reconOrder(String id) {
        return [orderId: id, externalId: "EXT-${id}".toString(), orderName: "#${id}".toString(), grandTotal: 42.50,
                orderDate: 1777600000000L, statusId: "ORDER_APPROVED", isExchange: false]
    }

    private static String reconPage(List orders, boolean hasMore, Integer ordersCount = null) {
        return JsonOutput.toJson([orders: orders, ordersCount: ordersCount != null ? ordersCount : orders.size(),
                hasMore: hasMore, pageIndex: 0, pageSize: 500, exchangeOrderCount: 0,
                excludedNonSalesOrderCount: 0, missingExternalIdCount: 0])
    }

    private static Map gqlOrder(String id) {
        return [orderId: id, orderName: "#" + id, externalId: "SH" + id, statusId: "ORDER_COMPLETED",
                orderTypeId: "SALES_ORDER", orderDate: "2026-05-01T10:00:00Z", grandTotal: 10,
                paymentPreferences: [[paymentMethodTypeId: "CREDIT_CARD", statusId: "PAYMENT_SETTLED"]]]
    }

    private static Map gqlReturn(String id) {
        return [returnId: id, statusId: "RETURN_REQUESTED", entryDate: "2026-05-01T10:00:00Z", externalId: null,
                currencyUomId: "USD", returnChannelEnumId: "ADMIN_RTN_CHANNEL",
                identifications: [[returnIdentificationTypeId: "SHOPIFY_RETURN_ID", idValue: "3744700${id.takeRight(4)}", thruDate: null]]]
    }

    private static Map returnRecord(String returnId, String externalId) {
        return [returnId: returnId, externalId: externalId, orderExternalId: "7025799037059", statusId: "RETURN_COMPLETED",
                entryDate: "2026-05-01T10:00:00Z", returnTotal: "42.00", currencyUomId: "USD",
                returnChannelEnumId: "SHOPIFY_RETURN_CHANNEL",
                items: [[returnItemSeqId: "00001", orderItemExternalId: "oi-1", productId: "P1", sku: "SKU-1",
                         returnQuantity: 1, receivedQuantity: 1, returnPrice: "42.00", lineAmount: "42.00",
                         returnReasonId: "DAMAGED", returnItemStatusId: "RETURN_ITEM_COMPLETED"]]]
    }

    private static String returnsPage(List returns, boolean hasMore) {
        return JsonOutput.toJson([returns: returns, returnsCount: returns.size(), hasMore: hasMore, pageIndex: 0,
                pageSize: 500, excludedNoShopifyRefCount: 0])
    }

    private static int queryInt(String url, String key) {
        String query = new URI(url).rawQuery ?: ""
        return Integer.parseInt(query.split("&").collect { it.split("=", 2) }.find { it[0] == key }?.getAt(1))
    }

    private Map stepRow(String runId, String stage) {
        def row = ec.entity.find(RunObservability.RUN_STEP_ENTITY).condition("reconciliationRunResultId", runId)
                .condition("stageCode", stage).disableAuthz().useCache(false).one()
        return row == null ? [:] : row.getMap()
    }

    private Map runRow(String runId) {
        def row = ec.entity.find(RunObservability.RUN_RESULT_ENTITY).condition("reconciliationRunResultId", runId)
                .disableAuthz().useCache(false).one()
        return row == null ? [:] : row.getMap()
    }

    private void upsert(String entityName, Map<String, Object> pkFields, Map<String, Object> fields) {
        boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
        ArtifactExecutionInfo aei = ec.artifactExecution.push("seedOmsExtractScriptChar",
                ArtifactExecutionInfo.AT_OTHER, ArtifactExecutionInfo.AUTHZA_ALL, false)
        ec.artifactExecution.setAnonymousAuthorizedAll()
        try {
            if (ec.entity.find(entityName).condition(pkFields).disableAuthz().useCache(false).one() != null) return
            ec.service.sync().name("store#${entityName}").parameters(fields).disableAuthz().call()
        } finally {
            ec.artifactExecution.pop(aei)
            if (!alreadyDisabled) ec.artifactExecution.enableAuthz()
        }
    }
}
