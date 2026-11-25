package darpan.hotwax.oms

import darpan.dataset.KeptCopySupport
import darpan.reconciliation.automation.SourceExtractOptionsSupport
import darpan.reconciliation.source.KeepLineTranslator
import groovy.xml.XmlSlurper
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-345 (option B). A run side reading HotWax orders (OMS_GQL) can keep exchange orders: its connector declares the
 * keepExchangeOrders option (extractOptionsJson, data/HotWaxConnectorSeedData.xml), the built-in exchange Keep line applies
 * unless it is on (appliesUnless, as OMS_RETURNS_GQL's lines do), and the orders extract takes it so a re-read by key reads
 * as the window does. Off is the default and is never stored, so every side without it keeps its read, its Keep and its
 * kept-copy options key. No Moqui.
 */
class KeepExchangeOrdersOptionTieTests {

    private static final File SEED = new File(System.getProperty("user.dir"), "data/HotWaxConnectorSeedData.xml")
    private static final File SERVICES = new File(System.getProperty("user.dir"), "service/reconciliation/HotWaxOmsExtractionServices.xml")

    private static Map<String, Object> ordersConnector() {
        def row = new XmlSlurper().parse(SEED).'**'
                .find { it.name() == "darpan.reconciliation.SourceSystemConnector" && it.@systemEnumId == "OMS_GQL" }
        return [systemEnumId: "OMS_GQL", extractOptionsJson: row.@extractOptionsJson.toString() ?: null,
                keepLinesJson: row.@keepLinesJson.toString() ?: null] as Map<String, Object>
    }

    @Test
    void theOrdersConnectorDeclaresTheOptionAndOffIsTheDefault() {
        Map<String, Object> connector = ordersConnector()
        assertEquals([[name: "keepExchangeOrders", label: "Keep exchange orders", type: "boolean"]],
                SourceExtractOptionsSupport.declaredOptions(connector))
        assertEquals('{"keepExchangeOrders":true}', SourceExtractOptionsSupport.validatedJson(connector, [keepExchangeOrders: true]))
        assertNull(SourceExtractOptionsSupport.validatedJson(connector, [keepExchangeOrders: false]), "off is not stored")
        // a side that stores nothing keeps the options key of the dataset it reads today
        assertEquals([:], KeptCopySupport.optionsKey(connector, [omsRestSourceConfigId: "C1", windowStart: "x"]))
    }

    @Test
    void theBuiltInExchangeLineAppliesUnlessTheSideKeepsExchangeOrders() {
        Map<String, Object> connector = ordersConnector()
        assertEquals(["BUILT_IN:0"], KeepLineTranslator.declared(connector.keepLinesJson, [:])*.id, "a side without it: as before")
        assertEquals(["BUILT_IN:0"], KeepLineTranslator.declared(connector.keepLinesJson,
                SourceExtractOptionsSupport.runtimeOptions(connector, null))*.id)
        assertEquals([], KeepLineTranslator.declared(connector.keepLinesJson,
                SourceExtractOptionsSupport.runtimeOptions(connector, '{"keepExchangeOrders":true}')))
    }

    @Test
    void theOrdersExtractTakesTheOption() {
        def service = new XmlSlurper().parse(SERVICES).'**'
                .find { it.name() == "service" && it.@verb == "extract" && it.@noun == "HotWaxOmsGqlOrders" }
        def parameter = service.'in-parameters'.parameter.find { it.@name == "keepExchangeOrders" }
        assertTrue(parameter.size() == 1, "extract#HotWaxOmsGqlOrders declares keepExchangeOrders")
        assertEquals("Boolean", parameter.@type.toString())
    }
}
