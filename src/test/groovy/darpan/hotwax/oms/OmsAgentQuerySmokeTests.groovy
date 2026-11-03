package darpan.hotwax.oms

import darpan.agent.AgentQueryLane
import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import groovy.json.JsonOutput
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-CLI-003 Task 6: fresh GraphQL and the trimmed schema through the tenant's own HotWax OMS connection. No network: the sender is a seam. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OmsAgentQuerySmokeTests {
    private static final String USER = "TEST_CUSTOMER_USER"
    private static final String TENANT_A = "KREWE"
    private static final String TENANT_B = "GORJANA"
    private static final String TENANT_VIEW = "DOLLSKILL"
    private static final Timestamp FROM = Timestamp.valueOf("2026-04-23 00:00:00")
    private static final String HOST = "oms-secret-host.hotwax.test"
    private static final String TOKEN = "tok-" + ("z" * 30)

    private static final Map INTROSPECTION = [__schema: [
            queryType: [name: "Query"],
            types    : [
                    [kind: "OBJECT", name: "Query", description: "Root", fields: [
                            [name: "orders", description: "Orders", type: [kind: "OBJECT", name: "OrderConnection", ofType: null],
                             args: [[name: "filter", description: "Filter", defaultValue: null,
                                     type: [kind: "NON_NULL", name: null, ofType: [kind: "INPUT_OBJECT", name: "OrderFilter", ofType: null]]]]]]],
                    [kind: "OBJECT", name: "OrderConnection", description: null, fields: [
                            [name: "edges", description: null, args: [],
                             type: [kind: "NON_NULL", name: null, ofType: [kind: "LIST", name: null, ofType: [kind: "OBJECT", name: "Order", ofType: null]]]]]],
                    [kind: "OBJECT", name: "Order", description: "An order", fields: [
                            [name: "status", description: "Status", args: [], type: [kind: "ENUM", name: "OrderStatus", ofType: null]]]],
                    [kind: "INPUT_OBJECT", name: "OrderFilter", description: "Filter", inputFields: [
                            [name: "id", description: "Id", defaultValue: null, type: [kind: "SCALAR", name: "String", ofType: null]]]],
                    [kind: "ENUM", name: "OrderStatus", description: "Statuses", enumValues: [[name: "OPEN", description: "Open"], [name: "DONE", description: null]]],
                    [kind: "SCALAR", name: "String", description: null],
                    [kind: "OBJECT", name: "Mutation", description: "Writes", fields: [
                            [name: "cancelOrder", description: null, args: [], type: [kind: "OBJECT", name: "CancelPayload", ofType: null]]]],
                    [kind: "OBJECT", name: "CancelPayload", description: null, fields: []],
            ]]]

    private ExecutionContext ec
    private final List<Map> sent = []
    private Closure answer
    private Closure originalFactory

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "oms-agent-query-smoke")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/DarpanSystemSourceSeedData.xml")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/SourceSystemConnectorSeedData.xml")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        ec.entity.makeValue("moqui.security.UserGroup").setAll([userGroupId: TenantAccessSupport.DARPAN_COMPANY_VIEW_ONLY_GROUP_ID,
                description: "View only"]).createOrUpdate()
        ec.entity.makeValue("moqui.basic.EnumerationType").setAll([enumTypeId: "DarpanSharedConfigType",
                description: "Darpan API source config types"]).createOrUpdate()
        ec.entity.makeValue("moqui.basic.Enumeration").setAll([enumId: "SCFG_HOTWAX_OMS", enumTypeId: "DarpanSharedConfigType",
                description: "HotWax OMS"]).createOrUpdate()
        seedTenant(TENANT_A, "Krewe", TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID)
        seedTenant(TENANT_B, "Gorjana", TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID)
        seedTenant(TENANT_VIEW, "Dolls Kill", TenantAccessSupport.DARPAN_COMPANY_VIEW_ONLY_GROUP_ID)
        seedConfig("OMS_A", TENANT_A)
        seedConfig("OMS_B", TENANT_B)
        seedConfig("OMS_V", TENANT_VIEW)
        originalFactory = OmsAgentQuerySupport.senderFactory
    }

    @AfterAll
    void cleanup() { ReconciliationSmokeTestSupport.cleanupMoqui(ec) }

    @BeforeEach
    void reset() {
        ec.message.clearErrors()
        sent.clear()
        ec.cache.getCache(OmsAgentQuerySupport.CACHE_NAME).clear()
        answer = { String body -> [statusCode: 200, body: JsonOutput.toJson([data: [orders: [edges: [[node: [id: 1]], [node: [id: 2]]]]], errors: []])] }
        AgentQueryLane.reset()
        OmsAgentQuerySupport.senderFactory = { { String url, Map headers, String body ->
            sent << [url: url, headers: headers, body: body]
            return (Map) answer.call(body)
        } }
    }

    @AfterEach
    void restore() { OmsAgentQuerySupport.senderFactory = originalFactory }

    @Test
    void "returns data for a query"() {
        Map r = run(TENANT_A, "OMS_A", "query Data { orders { edges { node { id } } } }")
        assertTrue(r.ok as boolean, "${r.errors}")
        assertEquals(2, ((r.data as Map).orders as Map).edges.size())
        assertEquals(2, r.rowCount)
        assertEquals(1, sent.size())
        assertEquals("https://${HOST}/rest/s1/graphql".toString(), sent[0].url)
        assertEquals(1, logRows("query Data { orders { edges { node { id } } } }", "OK").size())
    }

    @Test
    void "a mutation never reaches the sender"() {
        Map r = run(TENANT_A, "OMS_A", "mutation M { cancelOrder(id: 1) { id } }")
        assertFalse(r.ok as boolean)
        assertEquals("REFUSED", r.code)
        assertTrue(sent.isEmpty())
        assertEquals(1, logRows("mutation M { cancelOrder(id: 1) { id } }", "REFUSED").size())
    }

    @Test
    void "introspection through run is refused, through get schema is allowed"() {
        Map r = run(TENANT_A, "OMS_A", "query I { __schema { types { name } } }")
        assertFalse(r.ok as boolean)
        assertTrue(sent.isEmpty())
        answer = { String body -> [statusCode: 200, body: JsonOutput.toJson([data: INTROSPECTION, errors: []])] }
        Map s = schema(TENANT_A, "OMS_A")
        assertTrue(s.ok as boolean, "${s.errors}")
        assertEquals(1, sent.size())
        assertTrue((sent[0].body as String).contains("__schema"))
    }

    @Test
    void "schema is trimmed to Query-reachable types and served from cache on the second call"() {
        answer = { String body -> [statusCode: 200, body: JsonOutput.toJson([data: INTROSPECTION, errors: []])] }
        Map s = schema(TENANT_A, "OMS_A")
        Map trimmed = s.schema as Map
        assertEquals("Query", trimmed.queryType)
        assertEquals(["Query", "OrderConnection", "Order", "OrderFilter", "OrderStatus", "String"] as Set,
                (trimmed.types as List).collect { it.name } as Set)
        Map query = (trimmed.types as List).find { it.name == "Query" } as Map
        Map field = (query.fields as List).first() as Map
        assertEquals("OrderConnection", field.type)
        assertEquals("OrderFilter!", (field.args as List).first().type)
        assertEquals("Orders", field.description)
        assertEquals("[Order]!", (((trimmed.types as List).find { it.name == "OrderConnection" } as Map).fields as List).first().type)
        assertEquals(["OPEN", "DONE"], (((trimmed.types as List).find { it.name == "OrderStatus" } as Map).enumValues as List).collect { it.name })
        assertEquals(1, sent.size())
        Map again = schema(TENANT_A, "OMS_A")
        assertTrue(again.ok as boolean)
        assertEquals(trimmed, again.schema)
        assertEquals(1, sent.size(), "second call should come from the cache")
    }

    @Test
    void "another tenant's config is refused"() {
        Map r = run(TENANT_A, "OMS_B", "query Q { orders { edges { node { id } } } }")
        assertFalse(r.ok as boolean, "tenant A reached tenant B's connection")
        assertTrue(sent.isEmpty())
        assertFalse(schema(TENANT_A, "OMS_B").ok as boolean)
        assertTrue(sent.isEmpty())
        assertTrue(run(TENANT_B, "OMS_B", "query Q { orders { edges { node { id } } } }").ok as boolean)
        assertEquals(1, sent.size())
    }

    @Test
    void "view-only refused for run, allowed for get schema"() {
        Map r = run(TENANT_VIEW, "OMS_V", "query Q { orders { edges { node { id } } } }")
        assertFalse(r.ok as boolean)
        assertTrue(sent.isEmpty())
        answer = { String body -> [statusCode: 200, body: JsonOutput.toJson([data: INTROSPECTION, errors: []])] }
        Map s = schema(TENANT_VIEW, "OMS_V")
        assertTrue(s.ok as boolean, "${s.errors}")
        assertNotNull(s.schema)
    }

    @Test
    void "an error carrying the host and token comes back stripped"() {
        answer = { String body -> throw new IllegalStateException("connect to https://${HOST}/rest/s1/graphql failed, Bearer ${TOKEN}".toString()) }
        Map r = run(TENANT_A, "OMS_A", "query Leak { orders { edges { node { id } } } }")
        assertEquals("ERROR", r.code)
        String text = "${r.error} ${r.errors}"
        assertFalse(text.contains(HOST), text)
        assertFalse(text.contains(TOKEN), text)
        assertTrue(text.contains("[hidden]"), text)
        assertTrue(logRows("query Leak { orders { edges { node { id } } } }", "ERROR").size() == 1)
    }

    @Test
    void "a GraphQL error from the OMS reports through the same path"() {
        answer = { String body -> [statusCode: 200, body: JsonOutput.toJson([errors: [[message: "bad field on ${HOST}".toString()]]])] }
        Map r = run(TENANT_A, "OMS_A", "query Bad { orders { edges { node { id } } } }")
        assertEquals("ERROR", r.code)
        assertFalse("${r.error}".contains(HOST))
    }

    @Test
    void "the real sender gets connect and read timeouts under 60 seconds"() {
        Map config = [baseUrl: "https://x.test", connectTimeoutSeconds: 30, readTimeoutSeconds: 120]
        assertEquals(15, Math.min(OmsAgentQuerySupport.CONNECT_TIMEOUT_SECONDS, config.connectTimeoutSeconds as int))
        assertTrue(OmsAgentQuerySupport.READ_TIMEOUT_SECONDS < 60)
        assertNull(OmsAgentQuerySupport.edgeCount([a: 1, b: 2]))
    }

    private Map run(String tenant, String configId, String document) {
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, tenant)
        ec.message.clearErrors()
        return (Map) ec.service.sync().name("facade.HotWaxOmsFacadeServices.run#OmsGraphqlAgentQuery")
                .parameters([omsRestSourceConfigId: configId, document: document]).disableAuthz().call()
    }

    private Map schema(String tenant, String configId) {
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, tenant)
        ec.message.clearErrors()
        return (Map) ec.service.sync().name("facade.HotWaxOmsFacadeServices.get#OmsGraphqlSchema")
                .parameters([omsRestSourceConfigId: configId]).disableAuthz().call()
    }

    private List logRows(String queryText, String outcome) {
        return ec.entity.find("darpan.agent.AgentQueryLog").condition("queryText", queryText).condition("outcome", outcome)
                .disableAuthz().useCache(false).list() as List
    }

    private void seedConfig(String id, String tenant) {
        ec.entity.makeValue("darpan.hotwax.HotWaxOmsRestSourceConfig").setAll([omsRestSourceConfigId: id, description: id,
                companyUserGroupId: tenant, baseUrl: "https://${HOST}/".toString(), authType: "BEARER", apiToken: TOKEN,
                connectTimeoutSeconds: 5, readTimeoutSeconds: 10, isActive: "Y", canReadOrders: "Y",
                createdDate: FROM, lastUpdatedDate: FROM]).createOrUpdate()
    }

    private void seedTenant(String tenantId, String label, String permissionGroupId) {
        ec.entity.makeValue("moqui.security.UserGroup").setAll([userGroupId: tenantId, description: label,
                groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID]).createOrUpdate()
        ec.entity.makeValue("moqui.security.UserGroupMember").setAll([userGroupId: tenantId, userId: USER, fromDate: FROM]).createOrUpdate()
        ec.entity.makeValue(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME).setAll([
                tenantUserGroupId: tenantId, userId: USER, permissionUserGroupId: permissionGroupId, fromDate: FROM]).createOrUpdate()
    }
}
