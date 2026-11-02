package darpan.hotwax.migration

import groovy.json.JsonSlurper
import groovy.xml.XmlSlurper
import org.junit.jupiter.api.Test

import static darpan.hotwax.migration.OmsRestRetirement.*
import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-BE-052: migration OMS_REST_RETIRED, proven on plain rows (the plan is pure). */
class OmsRestRetirementTests {

    private static final String GET = "reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsOrders"

    private static Map<String, List<Map>> store() {
        return [
                (COMPARE_SOURCE)   : [[compareScopeId: "S1", fileSide: "FILE_1", systemEnumId: "OMS", sourceConfigType: "HOTWAX_OMS_REST"],
                                      [compareScopeId: "S2", fileSide: "FILE_2", systemEnumId: "OMS_RECON_ORDERS", sourceConfigType: "HOTWAX_OMS_REST_RECON"],
                                      [compareScopeId: "S3", fileSide: "FILE_2", systemEnumId: "OMS_RETURNS", sourceConfigType: "HOTWAX_OMS_REST_RETURNS"],
                                      [compareScopeId: "S4", fileSide: "FILE_1", systemEnumId: "OMS_TRANSFER_ORDERS", sourceConfigType: "HOTWAX_OMS_REST_TRANSFER"]],
                (AUTOMATION_SOURCE): [[automationId: "A1", fileSide: "FILE_1", systemEnumId: "OMS",
                                       safeMetadataJson: '{"extractServiceName":"' + GET + '","parameters":{"omsRestSourceConfigId":"c1"}}']],
                (ENDPOINT_ACCESS)  : [[configTypeEnumId: "HOTWAX_OMS_REST", configId: "c1", systemEnumId: "OMS", companyUserGroupId: "T", isEnabled: "Y"],
                                      [configTypeEnumId: "HOTWAX_OMS_REST", configId: "c1", systemEnumId: "OMS_RECON_ORDERS", companyUserGroupId: "T", isEnabled: "Y"],
                                      [configTypeEnumId: "HOTWAX_OMS_REST", configId: "c2", systemEnumId: "OMS_RETURNS", companyUserGroupId: "T", isEnabled: "N"]],
                (CONNECTOR_FIELD)  : [[systemEnumId: "OMS", fieldPath: '$.records[*].orderId']],
                (CONNECTOR)        : RETIRED_SYSTEMS.collect { [systemEnumId: it] },
        ]
    }

    @Test
    void everySourceWithATwinMovesToGraphQl() {
        Map p = plan(store())
        Map<String, Map> byScope = p.updates.findAll { it.entity == COMPARE_SOURCE }.collectEntries { [(it.pk.compareScopeId): it.fields] }
        assertEquals([systemEnumId: "OMS_GQL", sourceConfigType: "HOTWAX_OMS_GQL"], byScope.S1)
        assertEquals([systemEnumId: "OMS_GQL", sourceConfigType: "HOTWAX_OMS_GQL"], byScope.S2)
        assertEquals([systemEnumId: "OMS_RETURNS_GQL", sourceConfigType: "HOTWAX_OMS_GQL_RETURNS"], byScope.S3)
        assertEquals(["rule set compare scope S4 FILE_1 on OMS_TRANSFER_ORDERS"], p.stranded)
    }

    @Test
    void anAutomationRunsTheGraphQlServiceItStores() {
        Map u = plan(store()).updates.find { it.entity == AUTOMATION_SOURCE }
        assertEquals("OMS_GQL", u.fields.systemEnumId)
        Map metadata = (Map) new JsonSlurper().parseText((String) u.fields.safeMetadataJson)
        assertEquals("reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsGqlOrders", metadata.extractServiceName)
        assertEquals("c1", metadata.parameters.omsRestSourceConfigId)
    }

    @Test
    void grantsMoveOncePerConfigAndKeepTheirState() {
        Map p = plan(store(), [["HOTWAX_OMS_REST", "c9", "OMS_GQL"]])
        assertEquals([[configTypeEnumId: "HOTWAX_OMS_REST", configId: "c1", systemEnumId: "OMS_GQL", companyUserGroupId: "T", isEnabled: "Y"],
                      [configTypeEnumId: "HOTWAX_OMS_REST", configId: "c2", systemEnumId: "OMS_RETURNS_GQL", companyUserGroupId: "T", isEnabled: "N"]],
                p.creates*.fields)
        assertEquals(3, p.deletes.count { it.entity == ENDPOINT_ACCESS })
        assertTrue(plan(store(), [["HOTWAX_OMS_REST", "c1", "OMS_GQL"]]).creates.every { it.fields.configId != "c1" }, "an existing grant is kept, not duplicated")
    }

    @Test
    void theRestRowsAreDeletedAndASecondRunChangesNothing() {
        Map<String, List<Map>> s = store()
        Map p = plan(s)
        assertEquals(RETIRED_SYSTEMS, p.deletes.findAll { it.entity == CONNECTOR }.collect { it.pk.systemEnumId } as Set)
        // Apply to the in-memory store, then read back only rows still on a retired system (what run() reads).
        p.updates.each { Map u -> s[u.entity].find { r -> u.pk.every { k, v -> r[k] == v } }?.putAll(u.fields) }
        p.deletes.each { Map d -> s[d.entity].removeIf { r -> d.pk.every { k, v -> r[k] == v } } }
        p.creates.each { Map c -> s[c.entity] << c.fields }
        Map<String, List<Map>> retired = s.collectEntries { k, v -> [(k): v.findAll { it.systemEnumId in RETIRED_SYSTEMS }] }
        Map again = plan(retired, s[ENDPOINT_ACCESS].collect { [it.configTypeEnumId, it.configId, it.systemEnumId] })
        assertEquals([], again.updates + again.creates + again.deletes)
        assertEquals(1, again.stranded.size(), "the transfer-order source is still named, never silently dropped")
    }

    @Test
    void theTargetsMatchTheirConnectorRows() {
        File seed = new File(System.getProperty("user.dir"), "../darpan/data/SourceSystemConnectorSeedData.xml")
        Map<String, Map> rows = new XmlSlurper().parse(seed).'**'.findAll { it.name() == "darpan.reconciliation.SourceSystemConnector" }
                .collectEntries { [(it.@systemEnumId.toString()): [type: it.@expectedSourceConfigType.toString(), service: it.@extractServiceName.toString()]] }
        TARGETS.each { String id, Map t -> assertEquals(t, rows[id], id) }
        assertTrue(RETIRED_SYSTEMS.every { !rows.containsKey(it) }, "a retired REST row is back in the seed")
    }
}
