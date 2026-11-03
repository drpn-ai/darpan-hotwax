package darpan.hotwax.oms

import darpan.agent.AgentQuerySupport
import darpan.facade.common.TenantAccessSupport
import darpan.datasource.GraphQlDocumentCheck
import groovy.json.JsonOutput

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

    /** Test seam: () -> sender closure for OmsGqlClient (null = the real HTTP sender). */
    static Closure senderFactory = { null }

    static final String INTROSPECTION_QUERY = '''query IntrospectionQuery {
  __schema {
    queryType { name }
    types {
      kind name description
      fields(includeDeprecated: true) { name description args { name description defaultValue type { ...TypeRef } } type { ...TypeRef } }
      inputFields { name description defaultValue type { ...TypeRef } }
      interfaces { ...TypeRef }
      enumValues(includeDeprecated: true) { name description }
      possibleTypes { ...TypeRef }
    }
  }
}
fragment TypeRef on __Type { kind name ofType { kind name ofType { kind name ofType { kind name ofType { kind name ofType { kind name ofType { kind name ofType { kind name } } } } } } } }
'''

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
        Map gate = gateAndClient(ec, configId, meta)
        if (gate == null) return null
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
        Map gate = gateAndClient(ec, configId, meta)
        if (gate == null) return null

        def cache = ec.cache.getCache(CACHE_NAME)
        String key = "OMS:${configId}".toString()
        Map cached = (Map) cache.get(key)
        if (cached != null && System.currentTimeMillis() - (cached.at as long) < SCHEMA_TTL_MILLIS) {
            return unwrap([rows: [cached.schema], rowCount: 1, hasMore: false, truncated: false, durationMs: 0L])
        }
        // Introspection is allowed only here: the document check tells the only caller that wants it.
        List errors = (List) GraphQlDocumentCheck.check(INTROSPECTION_QUERY, true).errors
        if (errors) return AgentQuerySupport.refuse(ec, meta, errors.join(" "))

        final OmsGqlClient client = (OmsGqlClient) gate.client
        Map answer = AgentQuerySupport.run(ec, meta, {
            Map data = singleAnswer(client, INTROSPECTION_QUERY, null)
            Map trimmed = trimSchema(data)
            return [rows: [trimmed], hasMore: false, offset: 0] as Map
        } as Closure<Map>)
        Map out = unwrap(answer)
        if (out.rows) cache.put(key, [at: System.currentTimeMillis(), schema: ((List) out.rows)[0]])
        return out
    }

    // ---- shared plumbing ------------------------------------------------------------------------------------------

    /** Gate, config and client: [client] or null with an error on ec. meta.secrets is set before anything that can fail. */
    private static Map gateAndClient(def ec, String configId, Map meta) {
        String tenant = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
        Map gate = OmsScriptServiceSupport.gateConfig(ec, configId, tenant, SYSTEM_ENUM_ID)
        if (!gate.usable) return null
        Map config = OmsExtractSupport.toPlainMap(gate.sourceConfig)
        meta.secrets = secretsOf(config)
        config.connectTimeoutSeconds = Math.min(CONNECT_TIMEOUT_SECONDS, nonZero(config.connectTimeoutSeconds, CONNECT_TIMEOUT_SECONDS))
        config.readTimeoutSeconds = Math.min(READ_TIMEOUT_SECONDS, nonZero(config.readTimeoutSeconds, READ_TIMEOUT_SECONDS))
        try {
            return [client: new OmsGqlClient(config, (Closure) senderFactory.call())]
        } catch (Exception e) {
            AgentQuerySupport.refuse(ec, meta,
                    AgentQuerySupport.strip("HotWax OMS connection '${configId}' cannot be used: ${e.message}".toString(), meta.secrets as List<String>))
            return null
        }
    }

    private static int nonZero(Object v, int fallback) {
        try { int n = (v as Number).intValue(); return n > 0 ? n : fallback } catch (Exception ignored) { return fallback }
    }

    /** One call, no retry or throttle wait: the 60 s wall clock bounds the whole thing. */
    private static Map singleAnswer(OmsGqlClient client, String document, Map variables) {
        Map result = client.executeOnce(document, variables)
        Map data = result?.data instanceof Map ? (Map) result.data : [:]
        // A single 5 MB body cap; a JSON document cannot be cut partway, so an over-size answer is refused with advice.
        if (JsonOutput.toJson(data).getBytes(StandardCharsets.UTF_8).length > AgentQuerySupport.MAX_BODY_BYTES - 64) {
            throw new AgentQuerySupport.AgentQueryRefused("The answer is larger than 5 MB; ask for fewer fields or a smaller 'first'.")
        }
        return data
    }

    /** rows:[payload] becomes data/schema; rowCount is the edge count of the one top-level connection, else null. */
    private static Map unwrap(Map answer) {
        if (answer.error) return answer
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

    // ---- schema trimming ------------------------------------------------------------------------------------------

    /**
     * From the raw introspection data, only the types reachable from the Query root (through field types, argument
     * types, input-field types, interfaces and possible types), as compact maps. Mutation and Subscription roots are
     * not followed; a type reachable only through them is dropped.
     */
    static Map trimSchema(Map data) {
        Map schema = (Map) data?.get("__schema")
        if (schema == null) throw new IllegalStateException("The OMS answered without a schema.")
        String root = ((Map) schema.queryType)?.name
        Map<String, Map> byName = [:]
        ((schema.types ?: []) as List<Map>).each { Map t -> if (t.name) byName[t.name as String] = t }
        LinkedHashSet<String> seen = new LinkedHashSet<>()
        ArrayDeque<String> todo = new ArrayDeque<>()
        if (root) todo.add(root)
        while (!todo.isEmpty()) {
            String name = todo.poll()
            if (!seen.add(name)) continue
            Map t = byName[name]
            if (t == null) continue
            List<Object> refs = []
            ((t.fields ?: []) as List<Map>).each { Map f ->
                refs << f.type
                ((f.args ?: []) as List<Map>).each { Map a -> refs << a.type }
            }
            ((t.inputFields ?: []) as List<Map>).each { Map f -> refs << f.type }
            ((t.interfaces ?: []) as List).each { refs << it }
            ((t.possibleTypes ?: []) as List).each { refs << it }
            refs.each { String n = namedType(it); if (n && !seen.contains(n)) todo.add(n) }
        }
        List<Map> types = seen.findAll { byName[it] != null }.collect { String n -> trimType(byName[n]) }
        return [queryType: root, types: types]
    }

    private static Map trimType(Map t) {
        Map out = [name: t.name, kind: t.kind]
        if (t.description) out.description = t.description
        if (t.fields) out.fields = ((List<Map>) t.fields).collect { Map f ->
            Map o = [name: f.name, type: typeString(f.type)]
            if (f.description) o.description = f.description
            if (f.args) o.args = ((List<Map>) f.args).collect { Map a -> argument(a) }
            o
        }
        if (t.inputFields) out.inputFields = ((List<Map>) t.inputFields).collect { Map a -> argument(a) }
        if (t.enumValues) out.enumValues = ((List<Map>) t.enumValues).collect { Map e ->
            e.description ? [name: e.name, description: e.description] : [name: e.name]
        }
        if (t.possibleTypes) out.possibleTypes = ((List<Map>) t.possibleTypes).collect { it.name }
        if (t.interfaces) out.interfaces = ((List<Map>) t.interfaces).collect { it.name }
        return out
    }

    private static Map argument(Map a) {
        Map o = [name: a.name, type: typeString(a.type)]
        if (a.description) o.description = a.description
        if (a.defaultValue != null) o.defaultValue = a.defaultValue
        return o
    }

    /** The named type under any NON_NULL / LIST wrapping. */
    static String namedType(Object ref) {
        Object cur = ref
        while (cur instanceof Map && ((Map) cur).name == null && ((Map) cur).ofType != null) cur = ((Map) cur).ofType
        return cur instanceof Map ? ((Map) cur).name as String : null
    }

    /** [Order!]! style. */
    static String typeString(Object ref) {
        if (!(ref instanceof Map)) return "?"
        Map m = (Map) ref
        if (m.kind == "NON_NULL") return typeString(m.ofType) + "!"
        if (m.kind == "LIST") return "[" + typeString(m.ofType) + "]"
        return m.name as String
    }
}
