package darpan.hotwax.oms

import darpan.facade.common.SharedConfigAccessSupport
import darpan.reconciliation.automation.SourceEndpointAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OmsEndpointGateTests {
    private ExecutionContext ec
    private static final String CONFIG_ID = "gate-oms"
    private static final String TENANT = "GATE_TENANT"

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "oms-endpoint-gate")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/DarpanSystemSourceSeedData.xml")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/SourceSystemConnectorSeedData.xml")

        // FK prerequisites: SourceConfigEndpointAccess declares type="one" relationships to
        // moqui.security.UserGroup (companyUserGroupId) and TWICE to moqui.basic.Enumeration
        // (configTypeEnumId, systemEnumId) — real FK constraints exist. HotWaxOmsRestSourceConfig
        // separately FKs companyUserGroupId to the same UserGroup entity. systemEnumId="OMS_RETURNS_GQL"
        // is already covered by the DarpanSystemSourceSeedData.xml load above, but configTypeEnumId
        // (SCFG_HOTWAX_OMS) and the tenant's UserGroup row are normally seeded by
        // darpan/data/SecuritySeedData.xml in production — this isolated test DB does not auto-load
        // it, so both are hand-seeded here, matching the convention in
        // SourceEndpointAccessSupportTests.setup() (darpan component).
        ec.entity.makeValue("moqui.security.UserGroup")
                .setAll([userGroupId: TENANT, description: "Gate seam smoke-test tenant"]).createOrUpdate()
        ec.entity.makeValue("moqui.basic.EnumerationType")
                .setAll([enumTypeId: "DarpanSharedConfigType", description: "Darpan API source config types"]).createOrUpdate()
        ec.entity.makeValue("moqui.basic.Enumeration")
                .setAll([enumId: SharedConfigAccessSupport.CONFIG_TYPE_HOTWAX_OMS, enumTypeId: "DarpanSharedConfigType"]).createOrUpdate()

        ec.entity.makeValue("darpan.hotwax.HotWaxOmsRestSourceConfig")
                .setAll([omsRestSourceConfigId: CONFIG_ID, description: "Gate",
                         companyUserGroupId   : TENANT, baseUrl: "https://gate.example.com",
                         isActive             : "Y", canReadOrders: "Y"]).createOrUpdate()
        ec.entity.makeValue(SourceEndpointAccessSupport.ENTITY_NAME)
                .setAll([configTypeEnumId  : SharedConfigAccessSupport.CONFIG_TYPE_HOTWAX_OMS,
                         configId          : CONFIG_ID, systemEnumId: "OMS_RETURNS_GQL",
                         companyUserGroupId: TENANT, isEnabled: "N"]).createOrUpdate()
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    private boolean rejects(String requiredSystemEnumId) {
        ec.message.clearErrors()
        def config = ec.entity.find("darpan.hotwax.HotWaxOmsRestSourceConfig")
                .condition("omsRestSourceConfigId", CONFIG_ID).useCache(false).one()
        OmsExtractSupport.requireUsableOmsConfig(ec, config, CONFIG_ID, TENANT, requiredSystemEnumId)
        boolean hadError = ec.message.hasError()
        ec.message.clearErrors()
        return hadError
    }

    @Test
    void disabledEndpointIsRefused() {
        assertTrue(rejects("OMS_RETURNS_GQL"))
    }

    @Test
    void enabledEndpointIsPermitted() {
        assertFalse(rejects("OMS_GQL"))
    }

    @Test
    void omittedEndpointKeepsLegacyBehaviour() {
        // Existing callers that pass no endpoint must behave exactly as before this change.
        assertFalse(rejects(null))
    }

    // Proves the WIRING at a real production call site, not just the chokepoint above: the gate refuses before any
    // page is fetched, so no transport seam is needed and nothing reaches the fixture's bogus baseUrl.

    @Test
    void extractOmsGqlReturnsPathRefusesWhenItsEndpointIsDisabled() {
        ec.message.clearErrors()
        try {
            Map<String, Object> result = (Map<String, Object>) ec.service.sync()
                    .name("reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsGqlReturns")
                    .parameters([
                            omsRestSourceConfigId: CONFIG_ID,
                            companyUserGroupId   : TENANT,
                            windowStart          : Timestamp.valueOf("2026-05-01 00:00:00"),
                            windowEnd            : Timestamp.valueOf("2026-05-02 00:00:00"),
                            outputLocation       : "runtime://tmp/oms-endpoint-gate-extract-returns-test",
                    ])
                    .disableAuthz()
                    .call()
            List<String> errors = (result?.errors ?: []) as List<String>
            assertTrue(errors.any { it.contains("not enabled for OMS_RETURNS_GQL") },
                    "extract#HotWaxOmsGqlReturns must refuse when this config's OMS_RETURNS_GQL endpoint is disabled: ${errors}")
            assertFalse(result?.dataAvailable as boolean)
        } finally {
            ec.message.clearErrors()
        }
    }
}
