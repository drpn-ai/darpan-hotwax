package darpan.hotwax.oms

import darpan.datasource.DataDefinedExtract
import darpan.datasource.GraphQlTransport
import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import groovy.json.JsonSlurper
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ArtifactExecutionInfo
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-176. The real extract#DataDefinedSource service, through Moqui, reading HotWax order items with the data
 * definition (loaded from darpan-hotwax's own seed file) on a HotWax config: the config gate, the window and key
 * reads, the filters, the file it writes, its metadata and warnings. The scripted server is the equivalence test's,
 * reached through DataDefinedExtract's transport seam; the endpoint is a test copy of the order-items row, so the real
 * OMS_ORDER_ITEMS_GQL row is untouched.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DataDefinedOrderItemsServiceSmokeTests {
    private static final String SERVICE = "reconciliation.DataDefinedExtractionServices.extract#DataDefinedSource"
    private static final String ENDPOINT = "TEST_ITEMS_DATA_DEFINED"
    private static final String CONFIG_ID = "DATA_DEFINED_OMS"
    private static final String TENANT = "DATA_DEFINED_TENANT"
    private static final Timestamp CREATED = Timestamp.valueOf("2026-10-01 00:00:00")

    private ExecutionContext ec
    private Closure originalFactory

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "data-defined-order-items")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/DarpanSystemSourceSeedData.xml")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/SourceSystemConnectorSeedData.xml")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan-hotwax/data/HotWaxDataSourceDefinitionsSeedData.xml")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        upsert("moqui.basic.Enumeration", [enumId: ENDPOINT], [enumId: ENDPOINT, enumTypeId: "DarpanSystemSource",
                enumCode: ENDPOINT, description: "Order items, data-defined (test)", parentEnumId: "OMS"])
        upsert("darpan.reconciliation.SourceSystemConnector", [systemEnumId: ENDPOINT], [
                systemEnumId: ENDPOINT, extractServiceName: SERVICE, dateFromParameterName: "windowStart",
                dateToParameterName: "windowEnd", expectedSourceConfigType: "TEST_DATA_DEFINED_ITEMS",
                configParameterName: "omsRestSourceConfigId", configEntityName: "darpan.hotwax.HotWaxOmsRestSourceConfig",
                filterParameterName: "sourceFilters", dataSourceDefinitionId: "OMS_ORDER_ITEMS_GQL_V1", enabled: "Y"])
        upsert("moqui.security.UserGroup", [userGroupId: TENANT], [userGroupId: TENANT,
                description: "Data-defined tenant", groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID])
        upsert("moqui.basic.EnumerationType", [enumTypeId: "DarpanSharedConfigType"],
                [enumTypeId: "DarpanSharedConfigType", description: "Darpan API source config types"])
        upsert("moqui.basic.Enumeration", [enumId: "SCFG_HOTWAX_OMS"],
                [enumId: "SCFG_HOTWAX_OMS", enumTypeId: "DarpanSharedConfigType", description: "HotWax OMS"])
        upsert("darpan.hotwax.HotWaxOmsRestSourceConfig", [omsRestSourceConfigId: CONFIG_ID], [
                omsRestSourceConfigId: CONFIG_ID, description: "Data-defined items", companyUserGroupId: TENANT,
                baseUrl: "https://oms.test/", ordersPath: "/rest/s1/oms/orders", authType: "API_KEY", apiToken: "k",
                connectTimeoutSeconds: 5, readTimeoutSeconds: 10, isActive: "Y", canReadOrders: "Y",
                createdDate: CREATED, lastUpdatedDate: CREATED])
        originalFactory = DataDefinedExtract.transportFactory
    }

    @AfterAll
    void cleanup() {
        DataDefinedExtract.transportFactory = originalFactory
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @AfterEach
    void reset() {
        ec.message.clearErrors()
        DataDefinedExtract.transportFactory = originalFactory
    }

    private OmsGqlOrderItemsDataDefinitionTests.Server serve() {
        OmsGqlOrderItemsDataDefinitionTests.Server server = new OmsGqlOrderItemsDataDefinitionTests.Server()
        DataDefinedExtract.transportFactory = { Map definition, Map record -> new GraphQlTransport(definition, record, server.sender()) }
        return server
    }

    private Map call(Map params) {
        return (Map) ec.service.sync().name(SERVICE).parameters([systemEnumId: ENDPOINT, omsRestSourceConfigId: CONFIG_ID,
                companyUserGroupId: TENANT, outputLocation: "runtime://tmp/data-defined-items-test"] + params).disableAuthz().call()
    }

    private List<Map> fileRecords(Map result) {
        return (List<Map>) ((Map) new JsonSlurper().parseText(ec.resource.getLocationReference((String) result.fileLocation).getText())).records
    }

    @Test
    void theServiceWritesTheCodedExtractsRecordsWithItsMetadataAndWarning() {
        OmsGqlOrderItemsDataDefinitionTests.Server server = serve()
        Map result = call([windowStart: "2026-10-01T00:00:00Z", windowEnd: "2026-10-03T00:00:00Z", fileName: "items-oct.json",
                           reconciliationRunResultId: null])
        assertTrue(((List) (result.errors ?: [])).isEmpty(), result.errors?.toString())
        assertEquals(true, result.dataAvailable)
        assertEquals("items-oct.json", result.fileName)
        Map coded = OmsGqlOrderItemsDataDefinitionTests.coded(new OmsGqlOrderItemsDataDefinitionTests.Server())
        assertEquals(OmsGqlOrderItemsDataDefinitionTests.canonical((List) coded.records),
                OmsGqlOrderItemsDataDefinitionTests.canonical(fileRecords(result)))
        assertEquals(((List) coded.records).size(), result.recordCount)
        Map meta = (Map) result.requestMetadata
        assertEquals("DATA_DEFINED", meta.sourceType)
        assertEquals("OMS_ORDER_ITEMS_GQL_V1", meta.dataSourceDefinitionId)
        ["keptNullShopifyIdCount", "nonUnitQuantityCount", "droppedNullLineIdCount"].each { String k ->
            assertEquals(((Map) coded.metadata).get(k), meta.get(k), k)
        }
        assertTrue(((List) result.warnings).any { it.toString().contains("had no Shopify order or line id") }, result.warnings?.toString())
        assertTrue(server.calls.every { it.url == "https://oms.test/rest/s1/graphql" && it.apiKey == "k" })
    }

    @Test
    void byKeysWithFiltersAndTheSidecar() {
        serve()
        Map result = call([lookupKeys: ["O1"], lookupKeyField: "omsOrderId",
                           sourceFilters: [[sequenceNum: 1, fieldExpression: "unitState", operator: "EXCLUDE_IN", filterValues: "CANCELLED"]]])
        assertTrue(((List) (result.errors ?: [])).isEmpty(), result.errors?.toString())
        List<Map> records = fileRecords(result)
        assertTrue(records && records.every { it.omsOrderId == "O1" && it.unitState != "CANCELLED" })
        List configured = (List) ((Map) ((Map) result.requestMetadata).filters).configuredExclusions
        assertEquals(1, configured[0].excludedCount)
        assertEquals(1, ((Map) result.requestMetadata).lookupKeyCount)
    }

    @Test
    void theGateRefusesInWordsBeforeAnyCall() {
        OmsGqlOrderItemsDataDefinitionTests.Server server = serve()
        Map otherTenant = call([windowStart: "2026-10-01T00:00:00Z", windowEnd: "2026-10-02T00:00:00Z", companyUserGroupId: "SOMEONE_ELSE"])
        assertTrue(((List) otherTenant.errors).any { it.toString().contains("${CONFIG_ID} not found") }, otherTenant.errors?.toString())
        ec.message.clearErrors()
        Map coded = call([systemEnumId: "OMS_ORDER_ITEMS_GQL", windowStart: "2026-10-01T00:00:00Z", windowEnd: "2026-10-02T00:00:00Z"])
        assertTrue(((List) coded.errors).any { it.toString().contains("is not a data-defined endpoint") }, coded.errors?.toString())
        ec.message.clearErrors()
        Map badFilter = call([windowStart: "2026-10-01T00:00:00Z", windowEnd: "2026-10-02T00:00:00Z",
                              sourceFilters: [[sequenceNum: 1, fieldExpression: "salesChannelEnumId", operator: "EXCLUDE_IN", filterValues: "POS"]]])
        assertTrue(((List) badFilter.errors).any { it.toString().contains("cannot filter on [salesChannelEnumId]") }, badFilter.errors?.toString())
        assertFalse(badFilter.dataAvailable as boolean)
        assertTrue(server.calls.isEmpty(), "nothing is asked of HotWax: ${server.calls}")
    }

    private void upsert(String entityName, Map<String, Object> pkFields, Map<String, Object> fields) {
        boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
        ArtifactExecutionInfo aei = ec.artifactExecution.push("seedDataDefinedItems",
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
