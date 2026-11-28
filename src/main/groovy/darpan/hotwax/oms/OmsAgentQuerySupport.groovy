package darpan.hotwax.oms

import darpan.agent.AgentQuerySupport
import darpan.facade.common.TenantAccessSupport

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


    /**
     * The AgentQuerySupport.graphqlQuery answer, or null with an error on ec when the caller may not read through this config.
     * S99-073: the query, schema, cap and unwrap flow is darpan's (AgentQuerySupport); the OMS gate, client and secrets are here.
     */
    static Map runAgentQuery(def ec, Map context) {
        String configId = context.get("omsRestSourceConfigId")?.toString()?.trim()
        String document = context.get("document")?.toString()
        Map meta = [systemEnumId: SYSTEM_ENUM_ID, connectionId: configId, queryText: document ?: ""]
        if (!TenantAccessSupport.requireActiveTenantRunAccess(
                ec, "Your active tenant only has view access, so it cannot read HotWax OMS.")) {
            return null
        }
        Map variables = context.get("variables") instanceof Map ? new LinkedHashMap((Map) context.get("variables")) : null
        return AgentQuerySupport.graphqlQuery(ec, meta, document, variables, { Map m -> gateAndClient(ec, configId, m, QUERY_MAX_RAW_BODY_BYTES) })
    }

    /** The trimmed schema for the config (AgentQuerySupport.graphqlSchema). Null with an error on ec when refused. */
    static Map getSchema(def ec, Map context) {
        String configId = context.get("omsRestSourceConfigId")?.toString()?.trim()
        Map meta = [systemEnumId: SYSTEM_ENUM_ID, connectionId: configId, queryText: "introspection (schema)"]
        return AgentQuerySupport.graphqlSchema(ec, meta, "OMS", { Map m -> gateAndClient(ec, configId, m, SCHEMA_MAX_RAW_BODY_BYTES) })
    }

    // ---- shared plumbing ------------------------------------------------------------------------------------------

    /**
     * Gate, config and client: [send, stamp], [refused: the logged REFUSED answer], or null with an error on ec. meta.secrets is set before anything that
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
            final OmsGqlClient client = new OmsGqlClient(config, sender)
            return [send: { String document, Map variables -> singleAnswer(client, document, variables) }, stamp: stamp]
        } catch (Exception e) {
            return [refused: AgentQuerySupport.refuse(ec, meta, "HotWax OMS connection '${configId}' cannot be used: ${e.message}".toString())]
        }
    }

    private static int nonZero(Object v, int fallback) {
        try { int n = (v as Number).intValue(); return n > 0 ? n : fallback } catch (Exception ignored) { return fallback }
    }

    /** One call, no retry or throttle wait: the 60 s wall clock bounds the whole thing. Capped by AgentQuerySupport. */
    private static Map singleAnswer(OmsGqlClient client, String document, Map variables) {
        Map result
        try {
            result = client.executeOnce(document, variables)
        } catch (OmsGqlClient.BodyTooLargeException ignored) {
            // Final review B3: the bounded sender stopped reading just past 5 MB.
            throw new AgentQuerySupport.AgentQueryRefused(AgentQuerySupport.TOO_LARGE)
        }
        return result?.data instanceof Map ? (Map) result.data : [:]
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
