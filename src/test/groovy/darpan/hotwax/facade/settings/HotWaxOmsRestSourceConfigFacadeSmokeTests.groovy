package darpan.hotwax.facade.settings

import darpan.facade.common.TenantAccessSupport
import darpan.hotwax.oms.OmsRestSourceSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
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

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HotWaxOmsRestSourceConfigFacadeSmokeTests {
    private static final String ENTITY_NAME = "darpan.hotwax.HotWaxOmsRestSourceConfig"
    private static final String TEST_USER_ID = "TEST_CUSTOMER_USER"
    private static final String KREWE = "KREWE"
    private static final String GORJANA = "GORJANA"
    private static final Timestamp TEST_FROM_DATE = Timestamp.valueOf("2026-05-01 00:00:00")

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "hotwax-oms-rest-source-config-smoke")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        seedPermissionGroup(TenantAccessSupport.DARPAN_COMPANY_VIEW_ONLY_GROUP_ID, "Can view tenant-scoped Darpan data but cannot mutate it")
        replaceTenantPermission(KREWE, TenantAccessSupport.DARPAN_COMPANY_VIEW_ONLY_GROUP_ID)
        seedTenant(GORJANA, "Gorjana")
        seedHotWaxFixtures()
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void clearErrors() {
        ec.message.clearErrors()
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, KREWE)
    }

    @Test
    void tenantAdminCanDeleteOwnConfigButViewOnlyTenantCannotDelete() {
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, GORJANA)

        Map<String, Object> createResult = saveFacade([
            omsRestSourceConfigId: "GORJANA_DELETE_HOTWAX",
            description          : "Delete HotWax",
            baseUrl              : "https://delete.hotwax.io",
            ordersPath           : "/rest/s1/oms/orders",
            authType             : "NONE",
            timeZone             : "America/Chicago",
            connectTimeoutSeconds: 30,
            readTimeoutSeconds   : 60,
            isActive             : true,
            canReadOrders        : false,
        ])
        assertTrue((Boolean) createResult.ok, createResult.errors?.toString())
        def createdConfig = findOne("GORJANA_DELETE_HOTWAX")
        assertEquals(GORJANA, createdConfig.companyUserGroupId)
        assertEquals("America/Chicago", createdConfig.timeZone)
        assertEquals("America/Chicago", ((Map<String, Object>) createResult.savedOmsRestSourceConfig).timeZone)
        assertEquals("N", createdConfig.canReadOrders)
        assertFalse(((Map<String, Object>) createResult.savedOmsRestSourceConfig).canReadOrders as boolean)

        ec.message.clearErrors()
        Map<String, Object> deleteResult = deleteFacade("GORJANA_DELETE_HOTWAX")
        assertTrue((Boolean) deleteResult.ok, deleteResult.errors?.toString())
        assertEquals(true, deleteResult.deleted)
        assertEquals("GORJANA_DELETE_HOTWAX", deleteResult.deletedOmsRestSourceConfigId)
        assertNull(findOne("GORJANA_DELETE_HOTWAX"))

        ec.message.clearErrors()
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, KREWE)
        Map<String, Object> blockedResult = deleteFacade("KREWE_HOTWAX")
        assertFalse((Boolean) blockedResult.ok)
        assertTrue((blockedResult.errors ?: []).join(" ").contains("read-only"))
        assertNotNull(findOne("KREWE_HOTWAX"))
    }

    @Test
    void automationExtractionUsesAutomationTenantWhenActiveTenantDiffers() {
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, GORJANA)
        OmsRestSourceSupport.setHttpClient { Map ignored ->
            [statusCode: 200, body: '{"orders":[{"orderId":"O100","orderTypeId":"SALES_ORDER"}]}']
        }

        try {
            Map<String, Object> result = (Map<String, Object>) ec.service.sync()
                .name("reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsOrders")
                .parameters([
                    omsRestSourceConfigId: "KREWE_HOTWAX",
                    companyUserGroupId   : KREWE,
                    automationExecutionId: "AUTO_EXEC_KREWE",
                    windowStart          : Timestamp.valueOf("2026-05-01 00:00:00"),
                    windowEnd            : Timestamp.valueOf("2026-05-02 00:00:00"),
                    outputLocation        : "runtime://tmp/hotwax-automation-extraction-test",
                ])
                .disableAuthz()
                .call()

            assertTrue((result.errors ?: []).isEmpty(), result.errors?.toString())
            assertTrue(result.dataAvailable as boolean)
            assertEquals(1, result.recordCount)
            assertTrue((result.fileLocation as String).contains("hotwax-automation-extraction-test"))
        } finally {
            OmsRestSourceSupport.resetHttpClient()
        }
    }

    private Map<String, Object> saveFacade(Map<String, Object> parameters) {
        return (Map<String, Object>) ec.service.sync()
            .name("facade.HotWaxOmsFacadeServices.save#HotWaxOmsRestSourceConfig")
            .parameters(parameters)
            .disableAuthz()
            .call()
    }

    private Map<String, Object> deleteFacade(String configId) {
        return (Map<String, Object>) ec.service.sync()
            .name("facade.HotWaxOmsFacadeServices.delete#HotWaxOmsRestSourceConfig")
            .parameters([omsRestSourceConfigId: configId])
            .disableAuthz()
            .call()
    }

    private def findOne(String configId) {
        return ec.entity.find(ENTITY_NAME)
            .condition("omsRestSourceConfigId", configId)
            .disableAuthz()
            .useCache(false)
            .one()
    }

    private void seedPermissionGroup(String permissionGroupId, String description) {
        upsertEntityValue("moqui.security.UserGroup", [userGroupId: permissionGroupId], [
            userGroupId    : permissionGroupId,
            description    : description,
            groupTypeEnumId: TenantAccessSupport.DARPAN_PERMISSION_GROUP_TYPE_ENUM_ID,
        ])
    }

    private void seedTenant(String tenantId, String label) {
        upsertEntityValue("moqui.security.UserGroup", [userGroupId: tenantId], [
            userGroupId    : tenantId,
            description    : label,
            groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID,
        ])
        upsertEntityValue("moqui.security.UserGroupMember", [
            userGroupId: tenantId,
            userId     : TEST_USER_ID,
            fromDate   : TEST_FROM_DATE,
        ], [
            userGroupId: tenantId,
            userId     : TEST_USER_ID,
            fromDate   : TEST_FROM_DATE,
        ])
        replaceTenantPermission(tenantId, TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID)
    }

    private void replaceTenantPermission(String tenantId, String permissionGroupId) {
        boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
        ArtifactExecutionInfo aei = ec.artifactExecution.push(
            "replaceHotWaxTenantPermission",
            ArtifactExecutionInfo.AT_OTHER,
            ArtifactExecutionInfo.AUTHZA_ALL,
            false
        )
        ec.artifactExecution.setAnonymousAuthorizedAll()
        try {
            ec.entity.find(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME)
                .condition("tenantUserGroupId", tenantId)
                .condition("userId", TEST_USER_ID)
                .disableAuthz()
                .useCache(false)
                .list()
                .each { it.delete() }
        } finally {
            ec.artifactExecution.pop(aei)
            if (!alreadyDisabled) ec.artifactExecution.enableAuthz()
        }
        upsertEntityValue(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME, [
            tenantUserGroupId    : tenantId,
            userId               : TEST_USER_ID,
            permissionUserGroupId: permissionGroupId,
            fromDate             : TEST_FROM_DATE,
        ], [
            tenantUserGroupId    : tenantId,
            userId               : TEST_USER_ID,
            permissionUserGroupId: permissionGroupId,
            fromDate             : TEST_FROM_DATE,
        ])
    }

    private void seedHotWaxFixtures() {
        upsertEntityValue(ENTITY_NAME, [omsRestSourceConfigId: "KREWE_HOTWAX"], [
            omsRestSourceConfigId : "KREWE_HOTWAX",
            description           : "Krewe HotWax",
            companyUserGroupId    : KREWE,
            createdByUserId       : TEST_USER_ID,
            baseUrl               : "https://krewe.hotwax.io",
            ordersPath            : "/rest/s1/oms/orders",
            authType              : "NONE",
            connectTimeoutSeconds : 30,
            readTimeoutSeconds    : 60,
            isActive              : "Y",
            canReadOrders         : "Y",
            createdDate           : TEST_FROM_DATE,
            lastUpdatedDate       : TEST_FROM_DATE,
        ])
    }

    private void upsertEntityValue(String entityName, Map<String, Object> pkFields, Map<String, Object> fields) {
        boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
        ArtifactExecutionInfo aei = ec.artifactExecution.push(
            "seedHotWaxOmsRestSourceConfig",
            ArtifactExecutionInfo.AT_OTHER,
            ArtifactExecutionInfo.AUTHZA_ALL,
            false
        )
        ec.artifactExecution.setAnonymousAuthorizedAll()
        try {
            def existing = ec.entity.find(entityName)
                .condition(pkFields)
                .disableAuthz()
                .useCache(false)
                .one()
            if (existing != null) return

            ec.service.sync()
                .name("store#${entityName}")
                .parameters(fields)
                .disableAuthz()
                .call()
        } finally {
            ec.artifactExecution.pop(aei)
            if (!alreadyDisabled) ec.artifactExecution.enableAuthz()
        }
    }
}
