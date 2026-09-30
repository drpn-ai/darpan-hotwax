package darpan.hotwax.oms

import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import groovy.json.JsonOutput
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
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * Tri-system order runs, D4. Dispatches the REAL extract#HotWaxOmsOrderItems service script, which
 * a unit test of OmsRestSourceSupport cannot reach: a Moqui extract script has no package
 * declaration, so an unimported class compiles and only fails when the script runs.
 *
 * The OMS_ORDER_ITEMS connector row and enum are seeded HERE because the darpan component owns the
 * seed files; the endpoint gate derives its catalog from connector rows, so without one every call
 * is refused as "not enabled for OMS_ORDER_ITEMS".
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OmsOrderItemsExtractServiceTests {
    private static final String CONFIG_ID = "ORDER_ITEMS_OMS"
    private static final String TENANT = "ORDER_ITEMS_TENANT"
    private static final Timestamp FROM_DATE = Timestamp.valueOf("2026-05-01 00:00:00")

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "hotwax-oms-order-items-extract")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/DarpanSystemSourceSeedData.xml")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/SourceSystemConnectorSeedData.xml")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        upsert("moqui.basic.Enumeration", [enumId: "OMS_ORDER_ITEMS"], [enumId: "OMS_ORDER_ITEMS",
                enumTypeId: "DarpanSystemSource", enumCode: "HOTWAX_ORDER_ITEMS",
                description: "HotWax Order Items", parentEnumId: "OMS"])
        upsert("darpan.reconciliation.SourceSystemConnector", [systemEnumId: "OMS_ORDER_ITEMS"], [
                systemEnumId            : "OMS_ORDER_ITEMS",
                extractServiceName      : "reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsOrderItems",
                dateFromParameterName   : "windowStart",
                dateToParameterName     : "windowEnd",
                expectedSourceConfigType: "HOTWAX_OMS_REST_ORDER_ITEMS",
                configParameterName     : "omsRestSourceConfigId",
                configEntityName        : "darpan.hotwax.HotWaxOmsRestSourceConfig",
                filterParameterName     : "sourceFilters",
                windowFieldName         : "orderDate",
                enabled                 : "Y"])
        upsert("moqui.security.UserGroup", [userGroupId: TENANT], [userGroupId: TENANT,
                description: "Order items tenant", groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID])
        upsert("moqui.basic.EnumerationType", [enumTypeId: "DarpanSharedConfigType"],
                [enumTypeId: "DarpanSharedConfigType", description: "Darpan API source config types"])
        upsert("moqui.basic.Enumeration", [enumId: "SCFG_HOTWAX_OMS"],
                [enumId: "SCFG_HOTWAX_OMS", enumTypeId: "DarpanSharedConfigType", description: "HotWax OMS"])
        upsert("darpan.hotwax.HotWaxOmsRestSourceConfig", [omsRestSourceConfigId: CONFIG_ID], [
                omsRestSourceConfigId: CONFIG_ID, description: "Order items", companyUserGroupId: TENANT,
                baseUrl: "https://order-items.hotwax.io", ordersPath: "/rest/s1/oms/orders", authType: "NONE",
                connectTimeoutSeconds: 5, readTimeoutSeconds: 10, isActive: "Y", canReadOrders: "Y",
                createdDate: FROM_DATE, lastUpdatedDate: FROM_DATE])
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @AfterEach
    void resetClient() {
        ec.message.clearErrors()
        OmsRestSourceSupport.resetHttpClient()
    }

    @Test
    void keepsExchangeOrdersAndUnkeyedItemsAndFiltersPerUnit() {
        List orders = [
                [orderId: "M1", orderName: "#GOR1", externalId: "7160857329795", orderTypeId: "SALES_ORDER",
                 statusId: "ORDER_COMPLETED",
                 identifications: [[orderIdentificationTypeId: "NETSUITE_ORDER_ID", idValue: "79270892"]],
                 shipGroups: [[facilityId: "WH", items: [
                         [orderItemSeqId: "01", externalId: "L1", statusId: "ITEM_COMPLETED", quantity: 1],
                         [orderItemSeqId: "02", externalId: null, statusId: "ITEM_COMPLETED", quantity: 1],
                         [orderItemSeqId: "03", externalId: "L1", statusId: "ITEM_CANCELLED", quantity: 1]]]]],
                // An EXC- order: excluded by the line-units service, a real order to NetSuite.
                [orderId: "M2", orderName: "EXC-1", externalId: "7160857329795", orderTypeId: "SALES_ORDER",
                 statusId: "ORDER_COMPLETED", orderItemAssocs: [[orderItemAssocTypeId: "EXCHANGE"]],
                 shipGroups: [[facilityId: "WH", items: [
                         [orderItemSeqId: "01", externalId: "L9", statusId: "ITEM_COMPLETED", quantity: 1]]]]]]
        OmsRestSourceSupport.setHttpClient { Map ignored -> [statusCode: 200, body: JsonOutput.toJson([orders: orders])] }

        Map<String, Object> result = (Map<String, Object>) ec.service.sync()
                .name("reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsOrderItems")
                .parameters([omsRestSourceConfigId: CONFIG_ID, companyUserGroupId: TENANT,
                             windowStart: FROM_DATE, windowEnd: Timestamp.valueOf("2026-05-02 00:00:00"),
                             outputLocation: "runtime://tmp/oms-order-items-extract-test",
                             sourceFilters: [[sequenceNum: 1, fieldExpression: "unitState",
                                              operator: "INCLUDE_IN", filterValues: "COMPLETED"]]])
                .disableAuthz()
                .call()

        assertTrue(((List) (result.errors ?: [])).isEmpty(), result.errors?.toString())
        // M1: L1 completed + the unkeyed item; the cancelled unit is filtered out. M2: kept.
        assertEquals(3, result.recordCount)
        Map requestMetadata = (Map) result.requestMetadata
        assertEquals(1, requestMetadata.keptNullShopifyIdCount)
        assertEquals(0, requestMetadata.droppedNullLineIdCount)
        assertEquals(0, ((Map) requestMetadata.filters).excludedExchangeOrderCount)
        assertTrue(((List) result.warnings).any { it.toString().contains("no Shopify order or line id") },
                result.warnings?.toString())
    }

    private void upsert(String entityName, Map<String, Object> pkFields, Map<String, Object> fields) {
        boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
        ArtifactExecutionInfo aei = ec.artifactExecution.push("seedOmsOrderItemsExtract",
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
