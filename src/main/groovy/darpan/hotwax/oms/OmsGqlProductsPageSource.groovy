package darpan.hotwax.oms

/**
 * DAR-BE-157: HotWax's side of the NetSuite <-> HotWax product reconciliation. Every product and every NETSUITE_PRODUCT_ID
 * identification, swept whole (no window: a product missing since August never falls inside a day's window), joined into
 * one record per SELLABLE product: not virtual, FINISHED_GOOD or MARKETING_PKG_PICK, no salesDiscontinuationDate.
 *
 * The compare key, nsKey, is the NetSuite item internal id. A product without one keeps a key of its own
 * (HOTWAX-ONLY:<productId>) so it surfaces as missing in NetSuite rather than dropping out. A NetSuite id on several
 * products keys the lowest productId; every other product gets DUPLICATE:<id>:<productId>, because duplicate compare keys
 * collapse to a clean match and no conclusion could name them.
 */
class OmsGqlProductsPageSource {

    static final List<String> SELLABLE_TYPES = ["FINISHED_GOOD", "MARKETING_PKG_PICK"].asImmutable()

    /** Every node of `root`, cursor-paged at PRODUCT_PAGE_SIZE; pagination.calls counts the pages. */
    static List<Map> sweep(OmsGqlClient client, String document, String root, String q, Map pagination) {
        List<Map> nodes = []
        Set<String> seenCursors = [] as Set
        String cursor = null
        while (true) {
            Map answer = client.execute(document, OmsGqlQueries.pageVariables(q, OmsGqlQueries.PRODUCT_PAGE_SIZE, cursor),
                    OmsGqlQueries.PRODUCT_PAGE_SIZE)
            pagination.calls = ((pagination.calls ?: 0) as int) + 1
            Map connection = OmsGqlOrderPageSource.requireConnection(answer, root)
            nodes.addAll(OmsGqlOrderGrainAssembler.edgeNodes(connection))
            cursor = OmsGqlOrderPageSource.nextCursor(connection, seenCursors, root)
            if (cursor == null) return nodes
        }
    }

    static Map buildRecords(List<Map> products, List<Map> identifications) {
        Map<String, String> nsIdByProduct = [:]
        identifications.findAll { !it.thruDate && it.idValue }.each { nsIdByProduct.put(it.productId as String, it.idValue as String) }
        List<Map> sellable = products.findAll {
            it.isVirtual != "Y" && SELLABLE_TYPES.contains(it.productTypeId) && !it.salesDiscontinuationDate
        }.sort { it.productId as String }
        Map<String, List<String>> productsByNsId = sellable.findAll { nsIdByProduct[it.productId as String] }
                .groupBy { nsIdByProduct[it.productId as String] }
                .collectEntries { k, v -> [(k): v*.productId*.toString()] }
        List<Map> records = sellable.collect { Map p ->
            String productId = p.productId as String
            String nsId = nsIdByProduct[productId]
            List<String> sharing = nsId ? productsByNsId[nsId] : []
            String nsKey = !nsId ? "HOTWAX-ONLY:${productId}" : sharing[0] == productId ? nsId : "DUPLICATE:${nsId}:${productId}"
            [productId: productId, internalName: p.internalName, productTypeId: p.productTypeId, createdDate: p.createdDate,
             netsuiteProductId: nsId, nsKey: nsKey.toString(), duplicateNetsuiteId: sharing.size() > 1] as Map
        }
        return [records : records,
                metadata: [productsRead: products.size(), sellable: records.size(),
                           withoutNetsuiteId: records.count { !it.netsuiteProductId },
                           netsuiteIdsOnSeveralProducts: productsByNsId.count { k, v -> v.size() > 1 },
                           windowIgnored: true]]
    }
}
