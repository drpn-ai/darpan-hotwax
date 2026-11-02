package darpan.hotwax.migration

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

/**
 * DAR-BE-052 (Aditi 2026-11-02: "DELETE any option for using REST ... no option no chance of using it"). One-time
 * migration OMS_REST_RETIRED: every stored HotWax source moves from a deleted REST endpoint to its GraphQL endpoint, and
 * the REST connector rows, their field pills and endpoint grants are deleted.
 *
 * {@link #plan} is pure (rows in, writes out), so idempotence is provable without a database: a second plan over the
 * written rows is empty. {@link #run} reads and writes the rows through Moqui; the migration supervisor calls it with
 * authz disabled, so it sees every tenant. History (AuditFindingRecord, AuditSourceRead) and the enum rows stay: they
 * are referenced by findings and runs already made.
 */
class OmsRestRetirement {

    /** REST endpoint -> its GraphQL endpoint. Transfer orders and line units have none: no run anywhere used them. */
    static final Map<String, String> TARGET_BY_SYSTEM = [
            OMS             : "OMS_GQL",
            OMS_RECON_ORDERS: "OMS_GQL",
            OMS_RETURNS     : "OMS_RETURNS_GQL",
            OMS_ORDER_ITEMS : "OMS_ORDER_ITEMS_GQL",
    ].asImmutable()
    static final Set<String> RETIRED_SYSTEMS = (TARGET_BY_SYSTEM.keySet() + ["OMS_TRANSFER_ORDERS", "OMS_ORDER_LINE_UNITS"]).asImmutable()

    /** The GraphQL endpoint's config type and extract service, as its connector row declares them. */
    static final Map<String, Map<String, String>> TARGETS = [
            OMS_GQL            : [type: "HOTWAX_OMS_GQL", service: "reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsGqlOrders"],
            OMS_RETURNS_GQL    : [type: "HOTWAX_OMS_GQL_RETURNS", service: "reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsGqlReturns"],
            OMS_ORDER_ITEMS_GQL: [type: "HOTWAX_OMS_GQL_ORDER_ITEMS", service: "reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsGqlOrderItems"],
    ].asImmutable()

    static final String COMPARE_SOURCE = "darpan.rule.RuleSetCompareSource"
    static final String AUTOMATION_SOURCE = "darpan.reconciliation.ReconciliationAutomationSource"
    static final String ENDPOINT_ACCESS = "darpan.reconciliation.SourceConfigEndpointAccess"
    static final String SCHEMA = "darpan.reconciliation.JsonSchema"
    static final String MAPPING_MEMBER = "darpan.mapping.ReconciliationMappingMember"
    static final String CONNECTOR_FIELD = "darpan.reconciliation.SourceSystemConnectorField"
    static final String CONNECTOR = "darpan.reconciliation.SourceSystemConnector"

    /**
     * rows: entity name -> the rows of that entity whose systemEnumId is a retired system (plain maps); existingGrants:
     * [configTypeEnumId, configId, systemEnumId] of the GraphQL endpoint grants already stored. Returns [updates: [[entity, pk, fields]], creates: [[entity, fields]], deletes: [[entity, pk]], stranded: [labels]].
     */
    static Map<String, List> plan(Map<String, List<Map>> rows, Collection<List> existingGrants = []) {
        List updates = [], creates = [], deletes = [], stranded = []
        rowsOf(rows, COMPARE_SOURCE).each { Map r ->
            String target = TARGET_BY_SYSTEM[(String) r.systemEnumId]
            if (!target) { stranded << "rule set compare scope ${r.compareScopeId} ${r.fileSide} on ${r.systemEnumId}".toString(); return }
            updates << [entity: COMPARE_SOURCE, pk: [compareScopeId: r.compareScopeId, fileSide: r.fileSide],
                        fields: [systemEnumId: target, sourceConfigType: TARGETS[target].type]]
        }
        rowsOf(rows, AUTOMATION_SOURCE).each { Map r ->
            String target = TARGET_BY_SYSTEM[(String) r.systemEnumId]
            if (!target) { stranded << "automation ${r.automationId} ${r.fileSide} on ${r.systemEnumId}".toString(); return }
            updates << [entity: AUTOMATION_SOURCE, pk: [automationId: r.automationId, fileSide: r.fileSide],
                        fields: [systemEnumId: target, safeMetadataJson: metadataFor((String) r.safeMetadataJson, TARGETS[target].service)]]
        }
        Set<List> granted = new HashSet<List>(existingGrants ?: [])
        rowsOf(rows, ENDPOINT_ACCESS).each { Map r ->
            String target = TARGET_BY_SYSTEM[(String) r.systemEnumId]
            if (target && granted.add([r.configTypeEnumId, r.configId, target])) {
                creates << [entity: ENDPOINT_ACCESS, fields: [configTypeEnumId: r.configTypeEnumId, configId: r.configId,
                                                             systemEnumId: target, companyUserGroupId: r.companyUserGroupId, isEnabled: r.isEnabled]]
            }
            deletes << [entity: ENDPOINT_ACCESS, pk: [configTypeEnumId: r.configTypeEnumId, configId: r.configId, systemEnumId: r.systemEnumId]]
        }
        [(SCHEMA): "jsonSchemaId", (MAPPING_MEMBER): "mappingMemberId"].each { String entity, String pkField ->
            rowsOf(rows, entity).each { Map r ->
                String target = TARGET_BY_SYSTEM[(String) r.systemEnumId]
                if (target) updates << [entity: entity, pk: [(pkField): r.get(pkField)], fields: [systemEnumId: target]]
                else stranded << "${entity.tokenize('.').last()} ${r.get(pkField)} on ${r.systemEnumId}".toString()
            }
        }
        rowsOf(rows, CONNECTOR_FIELD).each { Map r -> deletes << [entity: CONNECTOR_FIELD, pk: [systemEnumId: r.systemEnumId, fieldPath: r.fieldPath]] }
        rowsOf(rows, CONNECTOR).each { Map r -> deletes << [entity: CONNECTOR, pk: [systemEnumId: r.systemEnumId]] }
        return [updates: updates, creates: creates, deletes: deletes, stranded: stranded]
    }

    /** The automation's stored extract service is what runs (AutomationExecutionSupport), so it moves with the source. */
    static String metadataFor(String json, String service) {
        Map metadata = json ? (Map) new JsonSlurper().parseText(json) : [:]
        if (!(metadata.extractServiceName || metadata.serviceName)) return json
        metadata.extractServiceName = service
        if (metadata.serviceName) metadata.serviceName = service
        return JsonOutput.toJson(metadata)
    }

    private static List<Map> rowsOf(Map<String, List<Map>> rows, String entity) {
        return (List<Map>) (rows.get(entity) ?: [])
    }

    static Map<String, Object> run(def ec, boolean dryRun) {
        Map<String, List<Map>> rows = [:]
        [COMPARE_SOURCE, AUTOMATION_SOURCE, ENDPOINT_ACCESS, SCHEMA, MAPPING_MEMBER, CONNECTOR_FIELD, CONNECTOR].each { String entity ->
            rows[entity] = ec.entity.find(entity).condition("systemEnumId", "in", RETIRED_SYSTEMS).useCache(false).list()
                    .collect { it.getMap() } as List<Map>
        }
        List<List> existingGrants = ec.entity.find(ENDPOINT_ACCESS).condition("systemEnumId", "in", TARGET_BY_SYSTEM.values() as Set)
                .useCache(false).list().collect { [it.configTypeEnumId, it.configId, it.systemEnumId] as List }
        Map<String, List> p = plan(rows, existingGrants)
        if (!dryRun) {
            p.updates.each { Map u ->
                def v = ec.entity.find(u.entity).condition(u.pk).useCache(false).one()
                if (v != null) { v.setAll(u.fields); v.update() }
            }
            p.creates.each { Map c -> ec.entity.makeValue(c.entity).setAll(c.fields).create() }
            p.deletes.each { Map d -> ec.entity.find(d.entity).condition(d.pk).deleteAll() }
        }
        List<String> messages = [("${dryRun ? 'Would move' : 'Moved'} ${p.updates.size()} stored source/schema/mapping row(s) to GraphQL, " +
                "${dryRun ? 'add' : 'added'} ${p.creates.size()} GraphQL endpoint grant(s), ${dryRun ? 'delete' : 'deleted'} ${p.deletes.size()} REST row(s).").toString()]
        if (p.stranded) messages << ("No GraphQL endpoint for: ${p.stranded.join('; ')}. Left as they are: they cannot run.").toString()
        return [rowsChangedCount: p.updates.size() + p.creates.size() + p.deletes.size(), strandedCount: p.stranded.size(), messages: messages]
    }
}
