package darpan.hotwax.oms

import java.util.regex.Pattern

import static darpan.common.ValueSupport.normalize

/**
 * DAR-OPS-029 S40b. What every by-id OMS lookup shares: plain-identifier keys, a readable config, a client, and two
 * guarantees. A failed call classifies nothing: the answer is ok=false with the lookup's empty shape, so no order is
 * ever called missing because HotWax could not be asked. And an answer naming none of the asked ids is an ignored
 * filter, never "HotWax has none of them" (OmsPairLookupSupport's rule since DAR-BE-129, now every lookup's).
 */
class OmsIdLookup {

    /** The search string is `key:a,b`; an id carrying a separator would corrupt it. */
    static final Pattern ASKABLE_ID = Pattern.compile('^[A-Za-z0-9_\\-]+$')

    /** The ids, trimmed, blanks dropped, each once, in the order given. */
    static List<String> ids(Collection raw) {
        return (raw ?: []).collect { normalize(it) }.findAll { it }.unique() as List<String>
    }

    static boolean askable(Collection<String> ids) {
        return ids.every { ASKABLE_ID.matcher(it).matches() }
    }

    /**
     * Runs `body(client)` with a client for the config and answers its Map. Any failure answers the lookup's `empty`
     * shape with ok=false and one error, "<label> failed [CODE]: message" (the code when the OMS gave one).
     */
    static Map<String, Object> run(Object rawConfig, Closure clientFactory, Map empty, String label, Closure body) {
        Map<String, Object> config = OmsExtractSupport.toPlainMap(rawConfig)
        if (!config) return failure(empty, "OMS source config could not be read.")
        try {
            return (Map<String, Object>) body.call((OmsGqlClient) clientFactory.call(config))
        } catch (OmsGqlException e) {
            return failure(empty, "${label} failed [${e.code}]: ${e.message}".toString())
        } catch (Exception e) {
            return failure(empty, "${label} failed: ${e.message ?: e.class.simpleName}".toString())
        }
    }

    static Map<String, Object> failure(Map empty, String message) {
        return ([ok: false] + empty + [errors: [message]]) as Map<String, Object>
    }

    /** The nodes, or FILTER_IGNORED when some came back and none names an asked id in `field`. */
    static List<Map> requireEcho(List<Map> nodes, Collection<String> requested, String field, String what) {
        if (nodes && !nodes.any { requested.contains(normalize(it.get(field))) }) {
            throw new OmsGqlException("FILTER_IGNORED",
                    "the ${what} search did not honour its id filter (no answer names an asked id)".toString())
        }
        return nodes
    }
}
