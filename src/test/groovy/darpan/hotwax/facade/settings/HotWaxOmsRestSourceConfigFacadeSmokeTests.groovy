package darpan.hotwax.facade.settings

import darpan.facade.common.TenantAccessSupport
import darpan.hotwax.oms.OmsGqlClient
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import groovy.json.JsonSlurper
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
    private static final JsonSlurper JSON_SLURPER = new JsonSlurper()
    private static final String ENTITY_NAME = "darpan.hotwax.HotWaxOmsRestSourceConfig"
    private static final String TEST_USER_ID = "TEST_CUSTOMER_USER"
    private static final String KREWE = "KREWE"
    private static final String GORJANA = "GORJANA"
    private static final Timestamp TEST_FROM_DATE = Timestamp.valueOf("2026-05-01 00:00:00")

    private ExecutionContext ec
    /** DAR-BE-135: the server mints a new config's id, so these tests name configs by alias and map the alias here. */
    private final Map<String, String> aliasIds = [:]

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "hotwax-oms-rest-source-config-smoke")
        // Task 16: requireUsableOmsConfig's new endpoint gate derives its catalog from
        // SourceSystemConnector rows (SourceEndpointAccessSupport.listEndpointsForConfig), not just
        // from SourceConfigEndpointAccess decisions. Without this load the catalog is empty, so
        // isEndpointEnabled("OMS") is false for every config — not the "absent decision means
        // enabled" default — and every extract#HotWaxOmsGqlOrders call below is wrongly refused.
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/SourceSystemConnectorSeedData.xml")
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
        assertEquals(idOf("GORJANA_DELETE_HOTWAX"), deleteResult.deletedOmsRestSourceConfigId)
        assertTrue(idOf("GORJANA_DELETE_HOTWAX") ==~ /\d+/, "DAR-BE-135: a new config takes a minted id")
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
        serveGqlOrders([[orderId: "O100", externalId: "E100", orderTypeId: "SALES_ORDER", statusId: "ORDER_APPROVED",
                         orderDate: "2026-05-01T10:00:00Z", grandTotal: 10]], [])

        try {
            Map<String, Object> result = (Map<String, Object>) ec.service.sync()
                .name("reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsGqlOrders")
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
            GroovySystem.metaClassRegistry.removeMetaClass(OmsGqlClient)
        }
    }

    @Test
    void automationExtractionWritesExchangeManifestSidecarWhenWindowHasExchangeOrders() {
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, GORJANA)
        serveGqlOrders([
                [orderId: "O100", externalId: "E100", orderTypeId: "SALES_ORDER", statusId: "ORDER_APPROVED",
                 orderDate: "2026-05-01T10:00:00Z", grandTotal: 10],
                [orderId: "M750653", externalId: "6941645013123", orderName: "EXC-#GOR196990495-1",
                 orderTypeId: "SALES_ORDER", statusId: "ORDER_COMPLETED", grandTotal: 50.0, orderDate: "2026-05-01T11:00:00Z"],
        ], [[orderId: "M750653", orderItemSeqId: "01", toOrderId: "M686331", toOrderItemSeqId: "01", orderItemAssocTypeId: "EXCHANGE"]])

        try {
            Map<String, Object> result = (Map<String, Object>) ec.service.sync()
                .name("reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsGqlOrders")
                .parameters([
                    omsRestSourceConfigId: "KREWE_HOTWAX",
                    companyUserGroupId   : KREWE,
                    automationExecutionId: "AUTO_EXEC_KREWE_EXCHANGE",
                    windowStart          : Timestamp.valueOf("2026-05-01 00:00:00"),
                    windowEnd            : Timestamp.valueOf("2026-05-02 00:00:00"),
                    outputLocation        : "runtime://tmp/hotwax-automation-exchange-manifest-test",
                ])
                .disableAuthz()
                .call()

            assertTrue((result.errors ?: []).isEmpty(), result.errors?.toString())
            assertTrue(result.dataAvailable as boolean)
            assertEquals(1, result.recordCount)

            // The kept sales order is written to the primary extract file; the exchange order is
            // excluded from it (unchanged exclusion behavior) but captured into the sidecar below.
            File extractFile = ec.resource.getLocationReference(result.fileLocation as String).getFile()
            String extractText = extractFile.text
            assertTrue(extractText.contains("O100"))
            assertFalse(extractText.contains("M750653"))

            assertNotNull(result.exchangeManifestFileLocation)
            String manifestLocation = result.exchangeManifestFileLocation as String
            assertTrue(manifestLocation.contains("hotwax-automation-exchange-manifest-test"))
            File manifestFile = ec.resource.getLocationReference(manifestLocation).getFile()
            assertNotNull(manifestFile)
            assertTrue(manifestFile.exists())

            Map manifestDoc = JSON_SLURPER.parseText(manifestFile.text) as Map
            List manifest = manifestDoc.manifest as List
            assertEquals(1, manifest.size())
            assertEquals("M750653", manifest[0].omsOrderId)
            assertEquals("6941645013123", manifest[0].externalId)
            assertEquals("M686331", manifest[0].toOrderId)
            assertFalse(manifestDoc.truncated as boolean)
            assertEquals(result.fileName, manifestDoc.sourceFileName)
        } finally {
            GroovySystem.metaClassRegistry.removeMetaClass(OmsGqlClient)
        }
    }

    /** DAR-BE-135: a named id only updates. Another tenant's id and a missing one answer alike, before any secret check. */
    @Test
    void aForeignIdAndAMissingIdAnswerAlikeAndNeitherCreatesARow() {
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, GORJANA)
        // BEARER with no token: before, the secret check read the foreign row's stored token first.
        Map<String, Object> params = [description: "Probe", baseUrl: "https://probe.hotwax.io", authType: "BEARER",
                                      timeZone: "America/Chicago", isActive: true, canReadOrders: true]

        Map<String, Object> foreign = callSave(params + [omsRestSourceConfigId: "KREWE_HOTWAX"])
        ec.message.clearErrors()
        Map<String, Object> missing = callSave(params + [omsRestSourceConfigId: "NO_SUCH_HOTWAX"])

        assertFalse((Boolean) foreign.ok)
        assertFalse((Boolean) missing.ok)
        assertEquals(["Requested OMS source config was not found."], foreign.errors)
        assertEquals(foreign.errors, missing.errors)
        assertNull(findOne("NO_SUCH_HOTWAX"), "a caller-named id must never create a row")
        assertEquals(KREWE, findOne("KREWE_HOTWAX").companyUserGroupId)
    }

    @Test
    void saveRejectsDisallowedBaseUrls() {
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, GORJANA)

        Map<String, Object> plaintext = saveFacade([
            omsRestSourceConfigId: "GORJANA_SSRF_HTTP",
            baseUrl              : "http://oms.example.com",
            authType             : "NONE",
            timeZone             : "America/Chicago",
            connectTimeoutSeconds: 30,
            readTimeoutSeconds   : 60,
            isActive             : true,
            canReadOrders        : true,
        ])
        assertFalse((Boolean) plaintext.ok)
        assertTrue((plaintext.errors ?: []).join(" ").contains("OMS Base URL is not allowed"))
        assertNull(findOne("GORJANA_SSRF_HTTP"))

        ec.message.clearErrors()
        Map<String, Object> metadataIp = saveFacade([
            omsRestSourceConfigId: "GORJANA_SSRF_METADATA",
            baseUrl              : "https://169.254.169.254/latest/meta-data",
            authType             : "NONE",
            timeZone             : "America/Chicago",
            connectTimeoutSeconds: 30,
            readTimeoutSeconds   : 60,
            isActive             : true,
            canReadOrders        : true,
        ])
        assertFalse((Boolean) metadataIp.ok)
        assertTrue((metadataIp.errors ?: []).join(" ").contains("OMS Base URL is not allowed"))
        assertNull(findOne("GORJANA_SSRF_METADATA"))
    }

    @Test
    void listIsTenantScopedSearchableAndPaginated() {
        upsertEntityValue(ENTITY_NAME, [omsRestSourceConfigId: "GORJANA_LIST_HOTWAX"], [
            omsRestSourceConfigId: "GORJANA_LIST_HOTWAX",
            description          : "Gorjana List",
            companyUserGroupId   : GORJANA,
            baseUrl              : "https://gorjana-list.hotwax.io",
            authType             : "NONE",
            isActive             : "Y",
            canReadOrders        : "Y",
            createdDate          : TEST_FROM_DATE,
            lastUpdatedDate      : TEST_FROM_DATE,
        ])
        upsertEntityValue(ENTITY_NAME, [omsRestSourceConfigId: "KREWE_HOTWAX_2"], [
            omsRestSourceConfigId: "KREWE_HOTWAX_2",
            description          : "Krewe Second",
            companyUserGroupId   : KREWE,
            baseUrl              : "https://krewe2.hotwax.io",
            authType             : "NONE",
            isActive             : "Y",
            canReadOrders        : "Y",
            createdDate          : TEST_FROM_DATE,
            lastUpdatedDate      : TEST_FROM_DATE,
        ])

        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, KREWE)
        Map<String, Object> all = listFacade([:])
        assertTrue((Boolean) all.ok, all.errors?.toString())
        List rows = all.omsRestSourceConfigs as List
        List ids = rows.collect { it.omsRestSourceConfigId }
        assertTrue(ids.contains("KREWE_HOTWAX"))
        assertTrue(ids.contains("KREWE_HOTWAX_2"))
        assertFalse(ids.contains("GORJANA_LIST_HOTWAX"))
        rows.each { row ->
            assertTrue(row.containsKey("hasApiToken"))
            assertTrue(row.containsKey("hasPassword"))
            assertFalse(row.containsKey("apiToken"))
            assertFalse(row.containsKey("password"))
        }
        int totalCount = (all.pagination as Map).totalCount as int
        assertTrue(totalCount >= 2)

        ec.message.clearErrors()
        Map<String, Object> searched = listFacade([query: "second"])
        List searchedIds = (searched.omsRestSourceConfigs as List).collect { it.omsRestSourceConfigId }
        assertTrue(searchedIds.contains("KREWE_HOTWAX_2"))
        assertFalse(searchedIds.contains("KREWE_HOTWAX"))

        ec.message.clearErrors()
        Map<String, Object> paged = listFacade([pageSize: 1])
        Map pagination = paged.pagination as Map
        assertEquals(totalCount, pagination.totalCount)
        assertEquals(totalCount, pagination.pageCount)
        assertEquals(1, (paged.omsRestSourceConfigs as List).size())

        ec.message.clearErrors()
        Map<String, Object> beyond = listFacade([pageIndex: 99, pageSize: 1])
        assertTrue((Boolean) beyond.ok)
        assertTrue((beyond.omsRestSourceConfigs as List).isEmpty())
    }

    @Test
    void preservesSecretsOnBlankUpdateAndRequiresSecretForNewBearer() {
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, GORJANA)

        Map<String, Object> created = saveFacade([
            omsRestSourceConfigId: "GORJANA_BEARER_HOTWAX",
            description          : "Bearer One",
            baseUrl              : "https://bearer.hotwax.io",
            authType             : "BEARER",
            apiToken             : "tok-12345",
            timeZone             : "America/Chicago",
            connectTimeoutSeconds: 30,
            readTimeoutSeconds   : 60,
            isActive             : true,
            canReadOrders        : true,
        ])
        assertTrue((Boolean) created.ok, created.errors?.toString())

        ec.message.clearErrors()
        Map<String, Object> updated = saveFacade([
            omsRestSourceConfigId: "GORJANA_BEARER_HOTWAX",
            description          : "Bearer One Updated",
            baseUrl              : "https://bearer.hotwax.io",
            authType             : "BEARER",
            timeZone             : "America/Chicago",
            connectTimeoutSeconds: 30,
            readTimeoutSeconds   : 60,
            isActive             : true,
            canReadOrders        : true,
        ])
        assertTrue((Boolean) updated.ok, updated.errors?.toString())
        def stored = findOne("GORJANA_BEARER_HOTWAX")
        assertEquals("Bearer One Updated", stored.description)
        assertEquals("tok-12345", stored.apiToken?.toString())

        ec.message.clearErrors()
        Map<String, Object> rejected = saveFacade([
            omsRestSourceConfigId: "GORJANA_BEARER_MISSING",
            baseUrl              : "https://bearer2.hotwax.io",
            authType             : "BEARER",
            timeZone             : "America/Chicago",
            connectTimeoutSeconds: 30,
            readTimeoutSeconds   : 60,
            isActive             : true,
            canReadOrders        : true,
        ])
        assertFalse((Boolean) rejected.ok)
        assertTrue((rejected.errors ?: []).join(" ").contains("API token is required for BEARER auth."))
        assertNull(findOne("GORJANA_BEARER_MISSING"))
    }

    /** DAR-BE-080: a stored token is reused only while base URL, auth type and headers are the same. */
    @Test
    void movingTheServerNeedsTheSecretAgain() {
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, GORJANA)
        Map<String, Object> base = [omsRestSourceConfigId: "GORJANA_MOVE_HOTWAX", authType: "BEARER", timeZone: "America/Chicago",
                connectTimeoutSeconds: 30, readTimeoutSeconds: 60, isActive: true, canReadOrders: true]
        assertTrue((Boolean) saveFacade(base + [baseUrl: "https://move.hotwax.io", apiToken: "tok-stored"]).ok)

        for (Map moved in [[baseUrl: "https://other.hotwax.io"], [baseUrl: "https://move.hotwax.io", authType: "API_KEY"],
                           [baseUrl: "https://move.hotwax.io", headersJson: '{"X-Forward":"1"}']]) {
            ec.message.clearErrors()
            Map<String, Object> refused = saveFacade(base + moved)
            assertFalse((Boolean) refused.ok, "${moved} kept the stored token")
            assertTrue((refused.errors ?: []).join(" ").contains("Enter the password or token again"), refused.errors?.toString())
        }
        assertEquals(["https://move.hotwax.io", "tok-stored"], findOne("GORJANA_MOVE_HOTWAX").with { [it.baseUrl, it.apiToken?.toString()] })

        ec.message.clearErrors()
        assertTrue((Boolean) saveFacade(base + [baseUrl: "https://MOVE.hotwax.io/", description: "same place"]).ok, "the same address keeps it")
        ec.message.clearErrors()
        Map<String, Object> withToken = saveFacade(base + [baseUrl: "https://other.hotwax.io", apiToken: "tok-new"])
        assertTrue((Boolean) withToken.ok, withToken.errors?.toString())
        assertEquals("tok-new", findOne("GORJANA_MOVE_HOTWAX").apiToken?.toString())
    }

    /** Review of DAR-BE-080: a new token sent with a moved address must not carry the stored password along (BASIC sends it). */
    @Test
    void aNewSecretOfOneKindDoesNotCarryTheOtherStoredOneToAMovedAddress() {
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, GORJANA)
        Map<String, Object> base = [omsRestSourceConfigId: "GORJANA_MIX_HOTWAX", authType: "BASIC", username: "svc", timeZone: "America/Chicago",
                connectTimeoutSeconds: 30, readTimeoutSeconds: 60, isActive: true, canReadOrders: true]
        assertTrue((Boolean) saveFacade(base + [baseUrl: "https://mix.hotwax.io", password: "pw-stored"]).ok)
        ec.message.clearErrors()
        Map<String, Object> out = saveFacade(base + [baseUrl: "https://other.hotwax.io", apiToken: "tok-decoy"])
        assertFalse((Boolean) out.ok, "the stored BASIC password went along to the moved address")
        assertEquals(["https://mix.hotwax.io", "pw-stored"], findOne("GORJANA_MIX_HOTWAX").with { [it.baseUrl, it.password?.toString()] })
    }

    @Test
    void deleteReportsNotFoundForMissingConfig() {
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, GORJANA)
        Map<String, Object> result = deleteFacade("GORJANA_DOES_NOT_EXIST")
        assertFalse((Boolean) result.ok)
        assertTrue((result.errors ?: []).join(" ").contains("was not found"))
        assertEquals(false, result.deleted)
    }

    @Test
    void extractionBlocksInactiveConfigAndAutomationTenantMismatch() {
        Map<String, Object> mismatch = (Map<String, Object>) ec.service.sync()
            .name("reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsGqlOrders")
            .parameters([
                omsRestSourceConfigId: "KREWE_HOTWAX",
                companyUserGroupId   : GORJANA,
                windowStart          : Timestamp.valueOf("2026-05-01 00:00:00"),
                windowEnd            : Timestamp.valueOf("2026-05-02 00:00:00"),
            ])
            .disableAuthz()
            .call()
        // DAR-BE-005 B1: a foreign automation tenant with no standing (not owner, not a peer) now
        // gets the same collapsed "not found" text as a nonexistent config id — see
        // OmsExtractSupport.requireUsableOmsConfig's Javadoc for why the two messages merged.
        assertTrue((mismatch.errors ?: []).join(" ").contains("not found"))
        assertFalse(mismatch.dataAvailable as boolean)
        assertEquals(0, mismatch.recordCount)
        assertNull(mismatch.fileLocation)

        // Clear the mismatch error before the next service call: Moqui skips a service invocation when
        // ec.message already has errors, which would otherwise return null for the inactive-config call.
        ec.message.clearErrors()
        upsertEntityValue(ENTITY_NAME, [omsRestSourceConfigId: "KREWE_INACTIVE_HOTWAX"], [
            omsRestSourceConfigId: "KREWE_INACTIVE_HOTWAX",
            description          : "Krewe Inactive",
            companyUserGroupId   : KREWE,
            baseUrl              : "https://krewe-inactive.hotwax.io",
            authType             : "NONE",
            isActive             : "N",
            canReadOrders        : "Y",
            createdDate          : TEST_FROM_DATE,
            lastUpdatedDate      : TEST_FROM_DATE,
        ])
        Map<String, Object> inactive = (Map<String, Object>) ec.service.sync()
            .name("reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsGqlOrders")
            .parameters([
                omsRestSourceConfigId: "KREWE_INACTIVE_HOTWAX",
                companyUserGroupId   : KREWE,
                windowStart          : Timestamp.valueOf("2026-05-01 00:00:00"),
                windowEnd            : Timestamp.valueOf("2026-05-02 00:00:00"),
            ])
            .disableAuthz()
            .call()
        assertTrue((inactive.errors ?: []).join(" ").contains("is inactive"))
        assertFalse(inactive.dataAvailable as boolean)
        assertEquals(0, inactive.recordCount)
    }

    /** Swaps OmsGqlClient's one-arg constructor (the one the scripts call) for a scripted sender: orders and their EXCHANGE assocs. */
    private static void serveGqlOrders(List<Map> orders, List<Map> exchangeAssocs) {
        Closure sender = { String url, Map headers, String body ->
            String root = (JSON_SLURPER.parseText(body).query =~ /\{\s*(\w+)\(/)[0][1]
            List nodes = root == "orders" ? orders : root == "orderItemAssocs" ? exchangeAssocs : []
            return [statusCode: 200, body: groovy.json.JsonOutput.toJson([data: [(root): [edges: nodes.collect { [node: it] },
                    pageInfo: [hasNextPage: false]]], errors: []])]
        }
        java.lang.reflect.Constructor ctor = OmsGqlClient.getConstructor(Map, Closure)
        OmsGqlClient.metaClass.constructor = { Map config ->
            OmsGqlClient c = (OmsGqlClient) ctor.newInstance(config, sender)
            c.sleeper = { long ms -> }
            return c
        }
    }

    private Map<String, Object> listFacade(Map<String, Object> parameters) {
        return (Map<String, Object>) ec.service.sync()
            .name("facade.HotWaxOmsFacadeServices.list#HotWaxOmsRestSourceConfigs")
            .parameters(parameters)
            .disableAuthz()
            .call()
    }

    private String idOf(String alias) {
        return aliasIds.get(alias) ?: alias
    }

    /** An alias that names no row yet is a create: the id is left out and the minted one remembered. */
    private Map<String, Object> saveFacade(Map<String, Object> parameters) {
        Map<String, Object> params = new LinkedHashMap<String, Object>(parameters)
        String alias = params.omsRestSourceConfigId as String
        if (alias && findOne(alias) == null) params.remove("omsRestSourceConfigId")
        else if (alias) params.omsRestSourceConfigId = idOf(alias)
        Map<String, Object> out = callSave(params)
        String savedId = ((Map<String, Object>) out.savedOmsRestSourceConfig)?.omsRestSourceConfigId as String
        if (alias && savedId) aliasIds.put(alias, savedId)
        return out
    }

    private Map<String, Object> callSave(Map<String, Object> parameters) {
        return (Map<String, Object>) ec.service.sync()
            .name("facade.HotWaxOmsFacadeServices.save#HotWaxOmsRestSourceConfig")
            .parameters(parameters)
            .disableAuthz()
            .call()
    }

    private Map<String, Object> deleteFacade(String configId) {
        return (Map<String, Object>) ec.service.sync()
            .name("facade.HotWaxOmsFacadeServices.delete#HotWaxOmsRestSourceConfig")
            .parameters([omsRestSourceConfigId: idOf(configId)])
            .disableAuthz()
            .call()
    }

    private def findOne(String configId) {
        return ec.entity.find(ENTITY_NAME)
            .condition("omsRestSourceConfigId", idOf(configId))
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
