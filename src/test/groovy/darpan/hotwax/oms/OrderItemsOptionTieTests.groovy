package darpan.hotwax.oms

import darpan.dataset.KeptCopySupport
import darpan.reconciliation.automation.SourceExtractOptionsSupport
import groovy.xml.XmlSlurper
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-368. A run side reading HotWax orders (OMS_GQL) can read each order's items, so an audit finding about the order
 * lists its lines: the connector declares the withOrderItems option (data/HotWaxConnectorSeedData.xml), the orders extract
 * takes it, and on it adds orderItems to the fields the read asks for. Off is the default and is never stored, so every side
 * without it keeps its read and its kept-copy options key. No Moqui.
 */
class OrderItemsOptionTieTests {

    private static final File SEED = new File(System.getProperty("user.dir"), "data/HotWaxConnectorSeedData.xml")
    private static final File SERVICES = new File(System.getProperty("user.dir"), "service/reconciliation/HotWaxOmsExtractionServices.xml")

    private static Map<String, Object> ordersConnector() {
        def row = new XmlSlurper().parse(SEED).'**'
                .find { it.name() == "darpan.reconciliation.SourceSystemConnector" && it.@systemEnumId == "OMS_GQL" }
        return [systemEnumId: "OMS_GQL", extractOptionsJson: row.@extractOptionsJson.toString() ?: null] as Map<String, Object>
    }

    @Test
    void theOrdersConnectorDeclaresTheOptionAndOffIsNeverStored() {
        Map<String, Object> connector = ordersConnector()
        assertTrue(SourceExtractOptionsSupport.declaredOptions(connector).contains([name: "withOrderItems", label: "Order items", type: "boolean"]))
        assertEquals('{"withOrderItems":true}', SourceExtractOptionsSupport.validatedJson(connector, [withOrderItems: true]))
        assertNull(SourceExtractOptionsSupport.validatedJson(connector, [withOrderItems: false]), "off is not stored")
        assertEquals([withOrderItems: true], SourceExtractOptionsSupport.runtimeOptions(connector, '{"withOrderItems":true}'))
        assertEquals([:], KeptCopySupport.optionsKey(connector, [omsRestSourceConfigId: "C1", windowStart: "x"]))
    }

    @Test
    void theOrdersExtractTakesTheOptionAndOnItAsksForEachOrdersItems() {
        def service = new XmlSlurper().parse(SERVICES).'**'
                .find { it.name() == "service" && it.@verb == "extract" && it.@noun == "HotWaxOmsGqlOrders" }
        def parameter = service.'in-parameters'.parameter.find { it.@name == "withOrderItems" }
        assertTrue(parameter.size() == 1, "extract#HotWaxOmsGqlOrders declares withOrderItems")
        assertEquals("Boolean", parameter.@type.toString())

        assertEquals(["isCompleted", "orderItems"], OmsExtractSupport.readFieldsWithOptions(["isCompleted"], true))
        assertEquals(["orderItems"], OmsExtractSupport.readFieldsWithOptions(null, "true"))
        assertEquals(["orderItems"], OmsExtractSupport.readFieldsWithOptions(["orderItems"], true), "asked once")
        assertEquals(["isCompleted"], OmsExtractSupport.readFieldsWithOptions(["isCompleted"], false))
        assertNull(OmsExtractSupport.readFieldsWithOptions(null, null), "off and nothing asked: the read is as before")
    }
}
