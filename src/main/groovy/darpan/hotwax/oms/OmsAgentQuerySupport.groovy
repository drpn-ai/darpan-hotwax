package darpan.hotwax.oms

import darpan.agent.AgentQuerySupport
import darpan.agent.GraphqlSchemaTrim
import darpan.facade.common.TenantAccessSupport
import darpan.datasource.GraphQlDocumentCheck
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import java.nio.charset.StandardCharsets

/**
 * DAR-CLI-003. A tenant user's agent sends one fresh, read-only GraphQL query through a HotWax OMS connection the
 * tenant already stores (Darpan holds the credentials), and reads a trimmed copy of the OMS schema to work out what to
 * ask. Gated as every OMS GraphQL read is (OmsScriptServiceSupport.gateConfig on the active tenant); the document is
 * checked, run with connect/read timeouts below the 60 s wall clock, logged and stripped of connection secrets by
 * {@link AgentQuerySupport#run}.
 */
class OmsAgentQuerySupport {

    static final String SYSTEM_ENUM_ID = "OMS_GQL"
    static final String CACHE_NAME = "darpan.agent.schema"
    static final long SCHEMA_TTL_MILLIS = 24L * 60 * 60 * 1000
    static final int CONNECT_TIMEOUT_SECONDS = 15
    static final int READ_TIMEOUT_SECONDS = 45

    /** Final review B3: room for the {"data":...,"errors":[]} wrapper around a 5 MB answer. */
    static final long RAW_BODY_MARGIN_BYTES = 64L * 1024
    /** A fresh query's answer is read at most this far (an agent controls the document, e.g. orders(first: 50000)). */
    static final long QUERY_MAX_RAW_BODY_BYTES = AgentQuerySupport.MAX_BODY_BYTES + RAW_BODY_MARGIN_BYTES
    /** 0 = unbounded: the introspection document is Darpan's own, and a raw schema over 5 MB is trimmed, not refused. */
    static final long SCHEMA_MAX_RAW_BODY_BYTES = 0L

    /** Test seam: () -> sender closure for OmsGqlClient (null = the real HTTP sender). */
    static Closure senderFactory = { null }


    /** The AgentQuerySupport.run answer, or null with an error on ec when the caller may not read through this config. */
    static Map runAgentQuery(def ec, Map context) {
        String configId = context.get("omsRestSourceConfigId")?.toString()?.trim()
        String document = context.get("document")?.toString()
        Map meta = [systemEnumId: SYSTEM_ENUM_ID, connectionId: configId, queryText: document ?: ""]
        if (!TenantAccessSupport.requireActiveTenantRunAccess(
                ec, "Your active tenant only has view access, so it cannot read HotWax OMS.")) {
            return null
        }
        Map variables = context.get("variables") instanceof Map ? new LinkedHashMap((Map) context.get("variables")) : null
        Map gate = gateAndClient(ec, configId, meta, QUERY_MAX_RAW_BODY_BYTES)
        if (gate == null) return null
        if (gate.refused) return (Map) gate.refused
        // The document check is last so a refusal is logged with the secrets already set on meta.
        String problem = AgentQuerySupport.checkGraphqlQuery(document)
        if (problem) return AgentQuerySupport.refuse(ec, meta, problem)

        final OmsGqlClient client = (OmsGqlClient) gate.client
        final String doc = document
        Map answer = AgentQuerySupport.run(ec, meta, {
            Map data = singleAnswer(client, doc, variables)
            return [rows: [data], hasMore: false, offset: 0] as Map
        } as Closure<Map>)
        return unwrap(answer)
    }

    /** The trimmed schema for the config: from the 24 h cache, else one introspection. Null with an error on ec when refused. */
    static Map getSchema(def ec, Map context) {
        String configId = context.get("omsRestSourceConfigId")?.toString()?.trim()
        Map meta = [systemEnumId: SYSTEM_ENUM_ID, connectionId: configId, queryText: "introspection (schema)"]
        Map gate = gateAndClient(ec, configId, meta, SCHEMA_MAX_RAW_BODY_BYTES)
        if (gate == null) return null
        if (gate.refused) return (Map) gate.refused

        // The gate ran above, so a cache hit is never served to a caller who failed it. Final review B8 (as Shopify):
        // the config's last-updated stamp is in the key, so an edited connection does not serve the old schema, and
        // the cache holds its own copy, so a caller changing what it was given cannot change what the next one reads.
        def cache = ec.cache.getCache(CACHE_NAME)
        String key = "OMS:${configId}:${gate.stamp}".toString()
        Map cached = (Map) cache.get(key)
        if (cached != null && System.currentTimeMillis() - (cached.at as long) < SCHEMA_TTL_MILLIS) {
            return unwrap([rows: [copyOf((Map) cached.schema)], rowCount: 1, hasMore: false, truncated: false, durationMs: 0L])
        }
        // Introspection is allowed only here: the document check tells the only caller that wants it.
        List errors = (List) GraphQlDocumentCheck.check(GraphqlSchemaTrim.INTROSPECTION_QUERY, true).errors
        if (errors) return AgentQuerySupport.refuse(ec, meta, errors.join(" "))

        final OmsGqlClient client = (OmsGqlClient) gate.client
        Map answer = AgentQuerySupport.run(ec, meta, {
            Map data = singleAnswer(client, GraphqlSchemaTrim.INTROSPECTION_QUERY, null, false)
            Map trimmed = GraphqlSchemaTrim.trimWithinCap(data)
            return [rows: [trimmed], hasMore: false, offset: 0] as Map
        } as Closure<Map>)
        Map out = unwrap(answer)
        if (out.rows) cache.put(key, [at: System.currentTimeMillis(), schema: copyOf((Map) ((List) out.rows)[0])])
        return out
    }

    // ---- shared plumbing ------------------------------------------------------------------------------------------

    /**
     * Gate, config and client: [client, stamp], [refused: the logged REFUSED answer], or null with an error on ec. meta.secrets is set before anything that
     * can fail. maxRawBodyBytes above 0 reads the OMS answer bounded (final review B3); 0 reads it whole.
     */
    private static Map gateAndClient(def ec, String configId, Map meta, long maxRawBodyBytes) {
        String tenant = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
        Map gate = OmsScriptServiceSupport.gateConfig(ec, configId, tenant, SYSTEM_ENUM_ID)
        if (!gate.usable) return null
        Map config = OmsExtractSupport.toPlainMap(gate.sourceConfig)
        meta.secrets = secretsOf(config)
        // Final review R25: fresh queries and schema reads run only on the active tenant's own connection.
        String notOwned = AgentQuerySupport.requireOwnedConnection(ec, config.companyUserGroupId)
        if (notOwned) return [refused: AgentQuerySupport.refuse(ec, meta, notOwned)]
        config.connectTimeoutSeconds = Math.min(CONNECT_TIMEOUT_SECONDS, nonZero(config.connectTimeoutSeconds, CONNECT_TIMEOUT_SECONDS))
        config.readTimeoutSeconds = Math.min(READ_TIMEOUT_SECONDS, nonZero(config.readTimeoutSeconds, READ_TIMEOUT_SECONDS))
        String stamp = gate.sourceConfig?.get("lastUpdatedStamp")?.toString() ?: ""
        try {
            Closure sender = (Closure) senderFactory.call()
            if (sender == null && maxRawBodyBytes > 0) sender = OmsGqlClient.httpSender(config, maxRawBodyBytes)
            return [client: new OmsGqlClient(config, sender), stamp: stamp]
        } catch (Exception e) {
            return [refused: AgentQuerySupport.refuse(ec, meta, "HotWax OMS connection '${configId}' cannot be used: ${e.message}".toString())]
        }
    }

    private static int nonZero(Object v, int fallback) {
        try { int n = (v as Number).intValue(); return n > 0 ? n : fallback } catch (Exception ignored) { return fallback }
    }

    /** One call, no retry or throttle wait: the 60 s wall clock bounds the whole thing. */
    private static Map singleAnswer(OmsGqlClient client, String document, Map variables, boolean capAnswer = true) {
        Map result
        try {
            result = client.executeOnce(document, variables)
        } catch (OmsGqlClient.BodyTooLargeException ignored) {
            // Final review B3: the bounded sender stopped reading just past 5 MB.
            throw new AgentQuerySupport.AgentQueryRefused(TOO_LARGE)
        }
        Map data = result?.data instanceof Map ? (Map) result.data : [:]
        // A single 5 MB body cap; a JSON document cannot be cut partway, so an over-size answer is refused with advice.
        if (capAnswer && JsonOutput.toJson(data).getBytes(StandardCharsets.UTF_8).length > AgentQuerySupport.MAX_BODY_BYTES - 64) {
            throw new AgentQuerySupport.AgentQueryRefused(TOO_LARGE)
        }
        return data
    }

    static final String TOO_LARGE = "The answer is larger than 5 MB; ask for fewer fields or a smaller 'first'."

    private static Map copyOf(Map m) { (Map) new JsonSlurper().parseText(JsonOutput.toJson(m)) }

    /** rows:[payload] becomes data/schema; rowCount is the edge count of the one top-level connection, else null. */
    private static Map unwrap(Map answer) {
        if (answer.error) return answer
        if (!(answer.rows instanceof List) || ((List) answer.rows).isEmpty()) return [error: "The system returned no usable answer.", code: "ERROR"]
        Map payload = ((List) answer.rows)[0] as Map
        Map out = new LinkedHashMap(answer)
        out.schema = payload
        out.data = payload
        out.rowCount = edgeCount(payload)
        return out
    }

    static Integer edgeCount(Map data) {
        if (data == null || data.size() != 1) return null
        Object only = data.values().first()
        if (only instanceof Map && ((Map) only).edges instanceof List) return ((List) ((Map) only).edges).size()
        return null
    }

    /** Everything about the connection that must never reach the agent or the log. */
    static List<String> secretsOf(Map config) {
        List<String> out = []
        ["username", "password", "apiToken"].each { String k ->
            String v = config.get(k)?.toString()?.trim()
            if (v) out.add(v)
        }
        String user = config.username?.toString()?.trim()
        String pass = config.password?.toString()?.trim()
        if (user && pass) out.add("${user}:${pass}".getBytes(StandardCharsets.UTF_8).encodeBase64().toString())
        try {
            OmsExtractSupport.parseHeadersJson(config.headersJson).each { k, v -> if (v?.toString()?.trim()) out.add(v.toString().trim()) }
        } catch (Exception ignored) { }
        String base = config.baseUrl?.toString()?.trim()
        if (base) {
            out.add(base)
            out.add(base.replaceAll('/+$', ''))
            try {
                URI uri = URI.create(base)
                if (uri.host) out.add(uri.host)
                if (uri.userInfo) out.add(uri.userInfo)
            } catch (Exception ignored) { }
        }
        return out.findAll { it }.unique()
    }
}
