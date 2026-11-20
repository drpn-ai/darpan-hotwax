package darpan.hotwax.oms

import static darpan.common.ValueSupport.normalize

/**
 * DAR-BE-318 plan 3b-1 (ruling P3b1-9): how the HotWax extractors keep only some fields of each record, written once. The
 * order and returns extracts trim with these functions, and so does extract#HotWaxOmsGqlOrders' kept-copy projection
 * (project#HotWaxOmsKeptRecords, OmsKeptRecordsServing), so a served side's records are the ones its live read writes.
 * darpan core names no system: it calls that service because the OMS connector rows name it (servingProjectionServiceName,
 * data/HotWaxConnectorSeedData.xml).
 *
 * <p>A {@link Spec} says what one endpoint's projection does besides trimming to the asked fields, and what must hold before
 * a copy record may stand in for its live read ({@link #servingProblem}). KeptFieldsProjectionTieTests pins every claim a
 * spec makes against the real extract code.</p>
 */
class OmsKeptFieldsProjection {

    /** What one connector's projection does, and what decides whether a copy record can stand in for its live read. */
    static final class Spec {
        /** Fields every projection keeps, asked or not (OMS returns keep items[]). */
        final Set<String> alwaysKept
        /** A kept field that brings others with it (OMS orders: the raw payment rows bring their slots-full flag). */
        final Map<String, Set<String>> companions
        /** A non-empty list projects even when no name in it survives trimming (OMS returns); else such a list keeps all. */
        final boolean projectsOnAnyList
        /**
         * Fields whose asking changes how other fields are read (OMS orders: asking paymentRefNums or the raw payment rows
         * reads five payment slots, not two). A side may be served only when it asks exactly the ones the copy's read asked.
         */
        final Set<String> readShapers
        /** The fields whose values the readShapers change. */
        final Set<String> shapedFields
        /**
         * A whole record holds what the read was asked for, not a fixed document (OMS orders select per field): a whole copy
         * then holds only the fields its records carry (the manifest's heldFields).
         */
        final boolean recordFollowsAskedFields
        /**
         * The most excluded exchange orders the live read lists in its exchange-manifest side file; null when the extractor
         * writes no such file.
         */
        final Integer exchangeManifestMax

        Spec(Map args) {
            alwaysKept = ((args.alwaysKept ?: []) as Set<String>).asImmutable()
            companions = ((args.companions ?: [:]) as Map<String, Set<String>>).asImmutable()
            projectsOnAnyList = args.projectsOnAnyList == true
            readShapers = ((args.readShapers ?: []) as Set<String>).asImmutable()
            shapedFields = ((args.shapedFields ?: []) as Set<String>).asImmutable()
            recordFollowsAskedFields = args.recordFollowsAskedFields == true
            exchangeManifestMax = args.exchangeManifestMax as Integer
        }
    }

    /** HotWax OMS orders (extract#HotWaxOmsGqlOrders, order grain). */
    static final Spec OMS_ORDERS = new Spec(
            companions: [paymentPreferences: ["paymentPreferenceSlotsFull"] as Set<String>],
            readShapers: ["paymentRefNums", "paymentPreferences", "paymentMethodTypeIds"],
            shapedFields: ["paymentRefNums", "paymentPreferences", "paymentMethodTypeIds", "paymentPreferenceSlotsFull"],
            recordFollowsAskedFields: true,
            exchangeManifestMax: 500)

    /** HotWax OMS returns (extract#HotWaxOmsGqlReturns): items[] always kept (RQ-21). */
    static final Spec OMS_RETURNS = new Spec(alwaysKept: ["items"], projectsOnAnyList: true)

    /** The HotWax endpoints whose live projection is this class, by systemEnumId. */
    static final Map<String, Spec> SPECS = ([OMS_GQL: OMS_ORDERS, OMS_RETURNS_GQL: OMS_RETURNS] as Map<String, Spec>).asImmutable()

    static Spec specFor(Object systemEnumId) {
        return systemEnumId == null ? null : SPECS.get(systemEnumId.toString())
    }

    /** The asked names trimmed, blanks dropped, first occurrence kept, in order; null when none is left. */
    static Set<String> keepSet(Object rawFields) {
        if (!(rawFields instanceof Collection) || ((Collection) rawFields).isEmpty()) return null
        Set<String> keep = new LinkedHashSet<String>()
        ((Collection) rawFields).each { Object field ->
            String name = normalize(field)
            if (name) keep.add(name)
        }
        return keep ?: null
    }

    /** {@code keep} plus the companions of the fields in it, appended; null stays null. */
    static Set<String> withCompanions(Set<String> keep, Map<String, Set<String>> companions) {
        if (keep == null || !companions) return keep
        Set<String> added = new LinkedHashSet<String>()
        companions.each { String field, Set<String> brought -> if (keep.contains(field)) added.addAll(brought) }
        return added ? new LinkedHashSet<String>(keep) + added : keep
    }

    /** The fields the connector's extractor trims each record to when sent {@code rawFields}; null: no projection. */
    static Set<String> keptBy(Spec spec, Object rawFields) {
        if (spec == null) return keepSet(rawFields)
        if (spec.projectsOnAnyList) {
            if (!(rawFields instanceof Collection) || ((Collection) rawFields).isEmpty()) return null
            Set<String> keep = keepSet(rawFields) ?: new LinkedHashSet<String>()
            keep.addAll(spec.alwaysKept)
            return keep
        }
        Set<String> keep = withCompanions(keepSet(rawFields), spec.companions)
        return keep == null || !spec.alwaysKept ? keep : new LinkedHashSet<String>(keep) + spec.alwaysKept
    }

    /** The record's fields named in {@code keep}, in the record's own order; a record that is not a map is empty. */
    static Map project(Object record, Set<String> keep) {
        if (!(record instanceof Map)) return [:]
        Map trimmed = new LinkedHashMap()
        ((Map) record).each { Object key, Object value ->
            if (keep.contains(key?.toString()?.trim())) trimmed.put(key, value)
        }
        return trimmed
    }

    /**
     * Whether a side may be served from a copy instead of its live read, for a connector with a spec. Null when it may;
     * otherwise [reason, missing], where missing names the fields the copy lacks (null when the copy is not short of a
     * field but read differently, which a rebuild under the same union would not change).
     *
     * @param copy keptFields (the list the copy's read was sent; null: whole records) and heldFields (the top-level fields
     *        the copy's records carry), both from its manifest
     * @param side keepFields (the list the side sends; null: whole records), filterFields, readFields, windowField
     */
    static Map servingProblem(Spec spec, Map copy, Map side) {
        Set<String> copyKeep = keepSet(copy?.keptFields)
        Set<String> copyKept = copyKeep == null ? null : keptBy(spec, copyKeep)
        Set<String> held = copy?.heldFields instanceof Collection ? (copy.heldFields as Set<String>) : null
        Set<String> sideKept = keptBy(spec, side?.keepFields)
        Set<String> filterFields = (side?.filterFields ?: []) as Set<String>
        Set<String> readFields = (side?.readFields ?: []) as Set<String>
        String windowField = normalize(side?.windowField)

        if (sideKept == null && copyKept != null) {
            return [reason: "the side keeps whole records, and the copy holds only some fields", missing: null]
        }
        // what the side's read asks for besides what it keeps; a whole record also writes what it was asked
        Set<String> asked = new LinkedHashSet<String>(filterFields)
        asked.addAll(readFields)
        Set<String> needed = new LinkedHashSet<String>(sideKept ?: [])
        needed.addAll(filterFields)
        if (windowField) needed.add(windowField)
        if (sideKept == null) needed.addAll(readFields)

        if (copyKept != null) {
            Set<String> missing = needed.findAll { !copyKept.contains(it) } as Set<String>
            if (missing) return [reason: "the copy lacks ${missing.sort().join(', ')}".toString(), missing: missing]
            // the side keeps a field whose value depends on how the payments were read: read the same way, or live
            if (sideKept.any { spec.shapedFields.contains(it) }) {
                Set<String> sideAsked = new LinkedHashSet<String>(sideKept) + asked
                Set<String> differ = spec.readShapers.findAll { sideAsked.contains(it) != copyKept.contains(it) } as Set<String>
                if (differ) return [reason: "the copy read ${differ.sort().join(', ')} otherwise than this side would".toString(), missing: null]
            }
            // a record lists the fields its read asked in the order they were asked: the side's must come in its own order
            List<String> copyOrder = copyKept.findAll { sideKept.contains(it) } as List<String>
            if (copyOrder != (sideKept as List<String>)) {
                return [reason: "the copy was asked for this side's fields in another order", missing: null]
            }
            return null
        }
        // a whole copy
        if (!spec.recordFollowsAskedFields) return null
        Set<String> missing = needed.findAll { held == null || !held.contains(it) } as Set<String>
        if (missing) return [reason: "the copy's records carry no ${missing.sort().join(', ')}".toString(), missing: missing]
        // a whole read asks none of the shapers: a side that asks one reads otherwise
        Set<String> shaping = spec.readShapers.findAll { (sideKept ?: [] as Set<String>).contains(it) || asked.contains(it) } as Set<String>
        if (shaping) return [reason: "the side asks ${shaping.sort().join(', ')}, which the copy's whole read did not".toString(), missing: null]
        return null
    }
}
