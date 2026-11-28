package darpan.hotwax.oms

import groovy.json.JsonSlurper
import org.junit.jupiter.api.Test
import org.w3c.dom.Element

import javax.xml.parsers.DocumentBuilderFactory

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-OPS-029 S40d (DAR-BE-176). A coded HotWax read and its DRAFT data definition (HotWaxDataSourceDefinitionsSeedData.xml)
 * must not drift apart silently: S40d found the coded read 20 commits ahead, one of them a silently wrong itemQuantity
 * (OC225). For each pair this lists what the coded side offers against what the definition declares, and fails on any
 * difference that is not in the pair's allow-list below, each entry with its reason. The allow-lists are compared
 * exactly: closing a gap means deleting its entry, so a list never outlives what it excuses.
 *
 * Compared, per pair:
 *  - switches: the coded extract service's in-parameters (its interface's merged in, as Moqui merges them) and the
 *    connector's extractOptionsJson, against what the generic extract#DataDefinedSource takes (window, keys, keep,
 *    filters, plumbing), the connector's config parameter, and the definition's options. A new coded switch fails here.
 *    (DataDefinedExtract.optionMismatch refuses an options mismatch at run time; this fails it at test time.)
 *  - lookup key fields the coded read accepts, against the definition's keyFields.
 *  - output fields, where the coded read declares them as constants (orders: OmsGqlQueries; order items:
 *    OmsGqlOrderPageSource.UNIT_FILTER_FIELDS), against the definition's record and filter fields. Returns and products
 *    declare no field constant; their records are compared record by record in their equivalence suites.
 *
 * Not compared here (known, no coded declaration to read): run metadata counts. OC146's droppedNonPositiveQuantity
 * (S40b, 57a667d) and OC151's window/page/lookup completeness (S40a, 1cccca7) are written by the coded reads only; the
 * definitions' runner reports its own counts. Re-prove both at the cutover.
 */
class DataDefinitionDriftGuardTests {

    /** The coded read's accepted lookupKeyField values, as its code checks them (no constant to read yet). */
    static final Map<String, List<String>> CODED_KEY_FIELDS = [
            // OmsGqlOrderPageSource.groovy:68 (orderId, omsOrderId -> orderId; externalId)
            OMS_ORDERS_GQL_V1     : ["orderId", "omsOrderId", "externalId"],
            // the unit read shares the order page source's key check
            OMS_ORDER_ITEMS_GQL_V1: ["orderId", "omsOrderId", "externalId"],
            // OmsGqlReturnsPageSource.groovy:71
            OMS_RETURNS_GQL_V1    : ["returnId", OmsGqlReturnsPageSource.REPLACEMENT_KEY, OmsGqlReturnsPageSource.ORDER_KEY],
            // the coded products read sweeps the whole catalogue and ignores keys, as the definition does
            OMS_PRODUCTS_GQL_V1   : [],
    ]

    static final Map<String, String> ORDERS_ALLOWED = [
            keepRuleFields            : "DAR-BE-318: the coded read leaves Keep to Spark over the fields named here; the definition still filters at its read (Ruling 3b2b-16) through sourceFilters.",
            readFields                : "DAR-BE-239: the coded read selects the rule-read fields without trimming; the definition asks what keep and filters name. Map at cutover.",
            conclusionLookup          : "DAR-BE-205: a conclusion lookup by key keeps exchange orders; the definition always leaves them out.",
            exchangeManifestMaxEntries: "DAR-BE-318: the manifest cap is fixed in the definition's drop sidecar (max); a dataset refresh's larger cap has no counterpart.",
            withOrderItems            : "DAR-BE-368: the definition serves orderItems as a field (S40d 47787cf), not as an option: the cutover must turn the switch into the orderItems keep field.",
            keepExchangeOrders        : "DAR-BE-345: the definition always leaves exchange orders out.",
            changedSince              : "DAR-BE-356: the definition has no changed-since read.",
    ]
    static final Map<String, String> ORDER_ITEMS_ALLOWED = [
            keepRuleFields: "Ruling 3b2b-10: each coded unit carries copies of the order scalars Keep reads; the definition's filter overlays the order level instead.",
            orderStatusIds: "Declared by the coded read only to refuse it (the GraphQL read cannot filter by status). extract#DataDefinedSource does not declare it, so Moqui would drop it silently: the cutover must refuse it too.",
            changedSince  : "DAR-BE-356: the definition has no changed-since read.",
    ]
    static final Map<String, String> RETURNS_ALLOWED = [
            withReturnCredit                         : "DAR-BE-192: the definition does not read the credit HotWax gave each return (returnCredit).",
            ("key:" + OmsGqlReturnsPageSource.REPLACEMENT_KEY): "The coded read finds the return that names a replacement order; the definition reads by returnId only.",
            ("key:" + OmsGqlReturnsPageSource.ORDER_KEY)      : "DAR-BE-352: the coded read reads by the return's Shopify order id; the definition reads by returnId only.",
    ]
    static final Map<String, String> PRODUCTS_ALLOWED = [:]

    @Test
    void ordersDefinitionDeclaresWhatTheCodedOrdersReadOffers() {
        Set<String> offered = (OmsGqlQueries.HEADER_FIELDS + OmsGqlQueries.DERIVED_FIELDS + OmsGqlQueries.NESTED_FIELDS.keySet() +
                [OmsGqlQueries.RAW_PAYMENTS_FIELD]) as Set<String>
        checkPair("OMS_GQL", "extract#HotWaxOmsGqlOrders", "OMS_ORDERS_GQL_V1", ORDERS_ALLOWED, offered)
    }

    @Test
    void orderItemsDefinitionDeclaresWhatTheCodedUnitReadOffers() {
        checkPair("OMS_ORDER_ITEMS_GQL", "extract#HotWaxOmsGqlOrderItems", "OMS_ORDER_ITEMS_GQL_V1", ORDER_ITEMS_ALLOWED,
                OmsGqlOrderPageSource.UNIT_FILTER_FIELDS, true)
    }

    @Test
    void returnsDefinitionDeclaresWhatTheCodedReturnsReadOffers() {
        checkPair("OMS_RETURNS_GQL", "extract#HotWaxOmsGqlReturns", "OMS_RETURNS_GQL_V1", RETURNS_ALLOWED, null)
    }

    @Test
    void productsDefinitionDeclaresWhatTheCodedProductsReadOffers() {
        checkPair("OMS_PRODUCTS_GQL", "extract#HotWaxOmsGqlProducts", "OMS_PRODUCTS_GQL_V1", PRODUCTS_ALLOWED, null)
    }

    /** Every definition in the seed has a pair here, so a new one cannot skip the guard. */
    @Test
    void everyDefinitionHasAPairInThisGuard() {
        assertEquals(CODED_KEY_FIELDS.keySet(), definitions().keySet(), "a definition without a pair in this guard (or a pair without a definition)")
    }

    // ---------------------------------------------------------------- the comparison

    private static void checkPair(String systemEnumId, String codedService, String definitionId, Map<String, String> allowed,
                                  Set<String> codedFields, boolean filterOnly = false) {
        Map definition = definitions().get(definitionId)
        assertTrue(definition != null, "no definition ${definitionId}")
        Map<String, String> connector = connector(systemEnumId)
        Set<String> definitionOptions = ((List<Map>) (definition.options ?: [])).collect { it.name as String } as Set<String>
        Set<String> generic = serviceInParameters(darpanFile("service/reconciliation/DataDefinedExtractionServices.xml"), "extract#DataDefinedSource")
        Set<String> coded = serviceInParameters(hotwaxFile("service/reconciliation/HotWaxOmsExtractionServices.xml"), codedService)
        Set<String> connectorOptions = optionNames(connector.extractOptionsJson)

        Set<String> differences = new TreeSet<String>()
        // A coded switch or a connector option the definition cannot take.
        ((coded - generic - [connector.configParameterName]) + connectorOptions).each { if (!definitionOptions.contains(it)) differences << it }
        // A definition option nothing offers.
        definitionOptions.each { if (!connectorOptions.contains(it) || !coded.contains(it)) differences << "option:" + it }
        // Lookup key fields.
        Set<String> codedKeys = CODED_KEY_FIELDS.get(definitionId) as Set<String>
        Set<String> definitionKeys = ((((Map) ((Map) ((List<Map>) definition.steps)[0].bind)?.ids)?.keyFields ?: [:]) as Map).keySet() as Set<String>
        (codedKeys - definitionKeys).each { differences << "key:" + it }
        (definitionKeys - codedKeys).each { differences << "definitionKey:" + it }
        // Output fields the coded read declares.
        if (codedFields != null) {
            Map filter = ((List<Map>) definition.steps).find { it.kind == "filter" }
            Set<String> filterFields = (filter?.fields ?: []) as Set<String>
            Map record = (Map) (definition.record ?: [:])
            Set<String> recordFields = (((List) (record.fields ?: [])) + ((List) (record.optionalFields ?: []))) as Set<String>
            (codedFields - filterFields).each { differences << "filterField:" + it }
            (filterFields - codedFields).each { differences << "definitionFilterField:" + it }
            if (!filterOnly) {
                (codedFields - recordFields).each { differences << "recordField:" + it }
                (recordFields - codedFields).each { differences << "definitionRecordField:" + it }
            }
        }
        assertEquals(new TreeSet<String>(allowed.keySet()), differences,
                "${definitionId} vs ${codedService}: every difference is in the allow-list with its reason, and every allow-list entry is still a difference")
    }

    // ---------------------------------------------------------------- the sources

    static File hotwaxFile(String path) {
        return [new File(path), new File("runtime/component/darpan-hotwax/" + path)].find { it.exists() } ?: new File(path)
    }

    static File darpanFile(String path) {
        return [new File("../darpan/" + path), new File("runtime/component/darpan/" + path)].find { it.exists() } ?: new File("../darpan/" + path)
    }

    private static List<Element> elements(File file, String tag) {
        def nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName(tag)
        return (0..<nodes.length).collect { nodes.item(it) as Element }
    }

    static Map<String, Map> definitions() {
        return elements(hotwaxFile("data/HotWaxDataSourceDefinitionsSeedData.xml"), "darpan.datasource.DataSourceDefinition").collectEntries { Element e ->
            String json = e.getElementsByTagName("definitionJson").item(0).textContent
            [(e.getAttribute("dataSourceDefinitionId")): new JsonSlurper().parseText(json)]
        }
    }

    /** The connector row's attributes, the HotWax component's row over darpan's (it loads later and updates the same row). */
    static Map<String, String> connector(String systemEnumId) {
        Map<String, String> out = [:]
        [darpanFile("data/SourceSystemConnectorSeedData.xml"), hotwaxFile("data/HotWaxConnectorSeedData.xml")].each { File f ->
            elements(f, "darpan.reconciliation.SourceSystemConnector").findAll { it.getAttribute("systemEnumId") == systemEnumId }.each { Element e ->
                def attrs = e.attributes
                (0..<attrs.length).each { out.put(attrs.item(it).nodeName, attrs.item(it).nodeValue) }
            }
        }
        assertTrue(!out.isEmpty(), "no connector ${systemEnumId}")
        return out
    }

    static Set<String> optionNames(String extractOptionsJson) {
        if (!extractOptionsJson?.trim()) return [] as Set<String>
        return ((List<Map>) new JsonSlurper().parseText(extractOptionsJson)).collect { it.name as String } as Set<String>
    }

    /** A service's in-parameter names, its <implements> interfaces' first (Moqui's merge), from the same file. */
    static Set<String> serviceInParameters(File file, String verbNoun) {
        Map<String, Element> services = elements(file, "service").collectEntries { [(it.getAttribute("verb") + "#" + it.getAttribute("noun")): it] }
        Closure<Set<String>> params
        params = { String name ->
            Element s = services.get(name)
            assertTrue(s != null, "no service ${name} in ${file}")
            Set<String> out = new LinkedHashSet<String>()
            def implementsList = s.getElementsByTagName("implements")
            (0..<implementsList.length).each { out.addAll(params.call(((Element) implementsList.item(it)).getAttribute("service").tokenize(".").last())) }
            def inParams = s.getElementsByTagName("in-parameters")
            if (inParams.length > 0) {
                def children = ((Element) inParams.item(0)).childNodes
                (0..<children.length).each { def c = children.item(it); if (c instanceof Element && c.tagName == "parameter") out.add(c.getAttribute("name")) }
            }
            return out
        }
        return params.call(verbNoun)
    }
}
