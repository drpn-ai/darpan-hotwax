package darpan.hotwax.oms

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-157: HotWax's side of the NetSuite product reconciliation. Every sellable product (not virtual, FINISHED_GOOD or
 * MARKETING_PKG_PICK, not discontinued), keyed by its NETSUITE_PRODUCT_ID, which is the NetSuite item internal id
 * (5,265 of 5,267 on gorjana). Pure: no Moqui, no OMS.
 */
class OmsGqlProductsPageSourceTests {

    private static Map product(String id, Map overrides = [:]) {
        return [productId: id, internalName: "SKU-${id}".toString(), productTypeId: "FINISHED_GOOD", isVirtual: "N",
                isVariant: "Y", createdDate: "2024-10-04T07:13:41Z", salesDiscontinuationDate: null] + overrides
    }

    private static Map ident(String productId, String nsId, String thruDate = null) {
        return [productId: productId, idValue: nsId, thruDate: thruDate]
    }

    @Test
    void keepsOnlySellableProducts() {
        Map built = OmsGqlProductsPageSource.buildRecords([
                product("1", [isVirtual: "Y", isVariant: "N"]),
                product("2", [salesDiscontinuationDate: "2025-01-01T00:00:00Z"]),
                product("3", [productTypeId: "DIGITAL_GOOD"]),
                product("4", [productTypeId: "SERVICE"]),
                product("5"),
                product("6", [productTypeId: "MARKETING_PKG_PICK"]),
        ], [])
        assertEquals(["5", "6"], built.records*.productId)
        assertEquals(6, built.metadata.productsRead)
        assertEquals(2, built.metadata.sellable)
    }

    @Test
    void keysOnTheNetsuiteId() {
        Map r = OmsGqlProductsPageSource.buildRecords([product("10001")], [ident("10001", "26161")]).records[0]
        assertEquals("26161", r.netsuiteProductId)
        assertEquals("26161", r.nsKey)
        assertEquals(false, r.duplicateNetsuiteId)
    }

    @Test
    void aProductWithoutANetsuiteIdKeepsAKey() {
        Map built = OmsGqlProductsPageSource.buildRecords([product("10056")], [])
        assertNull(built.records[0].netsuiteProductId)
        assertEquals("HOTWAX-ONLY:10056", built.records[0].nsKey)
        assertEquals(1, built.metadata.withoutNetsuiteId)
    }

    @Test
    void anEndedIdentificationDoesNotCount() {
        Map r = OmsGqlProductsPageSource.buildRecords([product("10056")], [ident("10056", "26161", "2025-03-01T00:00:00Z")]).records[0]
        assertEquals("HOTWAX-ONLY:10056", r.nsKey)
    }

    // A duplicate compare key collapses to a clean match, so every product after the first gets a key of its own and
    // becomes a HotWax-only finding the conclusions can name.
    @Test
    void sharedNetsuiteIdsAreMarked() {
        Map built = OmsGqlProductsPageSource.buildRecords([product("10200"), product("10100")],
                [ident("10200", "43000"), ident("10100", "43000")])
        Map<String, Map> byId = built.records.collectEntries { [(it.productId): it] }
        assertEquals("43000", byId["10100"].nsKey)
        assertEquals("DUPLICATE:43000:10200", byId["10200"].nsKey)
        assertEquals("43000", byId["10200"].netsuiteProductId)
        assertTrue(byId["10100"].duplicateNetsuiteId && byId["10200"].duplicateNetsuiteId)
        assertEquals(1, built.metadata.netsuiteIdsOnSeveralProducts)
        assertEquals(true, built.metadata.windowIgnored)
    }

    @Test
    void sweepFollowsCursorsAt100() {
        List<Map> pages = [
                [edges: [[node: product("1")]], pageInfo: [hasNextPage: true, endCursor: "c1"]],
                [edges: [[node: product("2")]], pageInfo: [hasNextPage: true, endCursor: "c2"]],
                [edges: [[node: product("3")]], pageInfo: [hasNextPage: false, endCursor: "c3"]],
        ]
        List<Map> sent = []
        int call = 0
        OmsGqlClient c = new OmsGqlClient([baseUrl: "https://gorjana-maarg.hotwax.io", authType: "BEARER", apiToken: "t"],
                { String url, Map headers, String body ->
                    sent << (Map) new JsonSlurper().parseText(body)
                    return [statusCode: 200, body: JsonOutput.toJson([data: [products: pages[call++]]])]
                })
        c.sleeper = { long ms -> }
        Map pagination = [:]
        List<Map> nodes = OmsGqlProductsPageSource.sweep(c, OmsGqlQueries.productsSweepDocument(), "products", null, pagination)
        assertEquals(["1", "2", "3"], nodes*.productId)
        assertEquals([100, 100, 100], sent*.variables*.first)
        assertEquals([null, "c1", "c2"], sent*.variables*.after)
        assertEquals(3, pagination.calls)
    }
}
