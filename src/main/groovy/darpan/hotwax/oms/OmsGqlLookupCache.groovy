package darpan.hotwax.oms

import java.util.concurrent.ConcurrentHashMap

/**
 * DAR-BE-129. By-id lookups answered once per OMS read scope (tenant, OMS config, endpoint) for a while, instead of
 * once per page. On the 2026-10-26 Oct 1-25 audit the same products and facilities were asked for on every page of
 * every read: 1,007 product-name, 289 product NetSuite id and 206 facility-name calls.
 *
 * An id the fetch did not answer is remembered as absent too, so it is not asked again. Kinds a fix can change (an
 * order's NetSuite id, a product's) live minutes, long enough for one run's pages and short enough that a re-run after
 * a fix reads the fix. A client with an injected sender (tests) is never cached: each test keeps its own answers.
 */
class OmsGqlLookupCache {

    /** Names do not decide any conclusion; ids do, and a fix in NetSuite or HotWax must be read by the next run. */
    static final long NAME_TTL_MILLIS = 6 * 3_600_000L
    static final long ID_TTL_MILLIS = 15 * 60_000L

    static final String PRODUCT_NAME = "productName"
    static final String PRODUCT_NETSUITE_ID = "productNetsuiteId"
    static final String FACILITY_NAME = "facilityName"
    static final String ORDER = "order"
    static final String ORDER_ITEMS = "orderItems"

    private static final Map<String, Long> TTL = [(PRODUCT_NAME): NAME_TTL_MILLIS, (FACILITY_NAME): NAME_TTL_MILLIS,
            (PRODUCT_NETSUITE_ID): ID_TTL_MILLIS, (ORDER): ID_TTL_MILLIS, (ORDER_ITEMS): ID_TTL_MILLIS]
    private static final Object ABSENT = new Object()
    /** Past this many entries a lookup first drops the expired ones (a month's returns name ~25,000 orders). */
    static final int MAX_ENTRIES = 100_000
    private static final Map<String, List> ENTRIES = new ConcurrentHashMap<>()
    static Closure<Long> clock = { -> System.currentTimeMillis() }

    /**
     * The answer for each of `ids` the fetch or the cache has; `fetch(missingIds)` answers a Map id -> value for the ids
     * not cached (or expired), and runs only when there are some.
     */
    static Map<String, Object> lookup(OmsGqlClient client, String kind, Collection<String> ids, Closure<Map<String, Object>> fetch) {
        List<String> wanted = (ids ?: []).findAll { it } as List<String>
        if (!client.realEndpoint) return wanted ? (fetch.call(wanted.unique()) ?: [:]) : [:]
        return lookup(client.readScope, kind, wanted, fetch)
    }

    static Map<String, Object> lookup(String scope, String kind, Collection<String> ids, Closure<Map<String, Object>> fetch) {
        long now = clock.call()
        long ttl = TTL[kind] ?: ID_TTL_MILLIS
        Map<String, Object> answers = [:]
        List<String> missing = []
        (ids ?: []).findAll { it }.unique().each { String id ->
            List entry = ENTRIES.get(key(scope, kind, id))
            if (entry != null && now - (entry[1] as long) < ttl) { if (!entry[0].is(ABSENT)) answers[id] = entry[0] }
            else missing.add(id)
        }
        if (missing) {
            if (ENTRIES.size() > MAX_ENTRIES) sweep()
            Map<String, Object> fetched = fetch.call(missing) ?: [:]
            missing.each { String id ->
                Object value = fetched.get(id)
                ENTRIES.put(key(scope, kind, id), [value == null ? ABSENT : value, now])
                if (value != null) answers[id] = value
            }
        }
        return answers
    }

    private static String key(String scope, String kind, String id) { return "${scope}|${kind}|${id}".toString() }

    /** Drops expired entries. */
    static void sweep() {
        long now = clock.call()
        ENTRIES.entrySet().removeIf { Map.Entry<String, List> e ->
            String kind = e.key.split('\\|')[-2]
            now - (e.value[1] as long) >= (TTL[kind] ?: ID_TTL_MILLIS)
        }
    }

    static void reset() {
        ENTRIES.clear()
        clock = { -> System.currentTimeMillis() }
    }
}
