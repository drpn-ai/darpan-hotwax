package darpan.hotwax.oms

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-040: the GraphQL pass of the OMS returns by-id lookup.
 *
 * Live 2026-10-03 on gorjana prod: reconciliationReturns serves REFUNDED returns only. All 268
 * RETURN_REQUESTED returns since Oct 1 were absent from it, and 10 sampled by their
 * SHOPIFY_RETURN_ID resolved 0/10 through both REST lookup passes and 10/10 through GraphQL
 * returnIdentifications(idValue:). So the verify pass reported every pending return as confirmed
 * missing in HotWax. Both transports are injected; nothing here opens a socket.
 */
class OmsReturnsGqlLookupTests {

    private static final Map CONFIG = [omsRestSourceConfigId: "TEST_CFG", baseUrl: "https://oms.example.com",
                                       apiKey: "test-key", isActive: "Y"]

    private List<Map> gqlRequests = []

    @BeforeEach
    void restAnswersNothing() {
        // The REST endpoint as it now behaves for a pending return: both id filters find nothing.
        OmsReturnsSourceSupport.setHttpClient { Map request -> [statusCode: 200, body: restBody([])] }
    }

    @AfterEach
    void reset() {
        OmsReturnsSourceSupport.resetHttpClient()
        OmsReturnsSourceSupport.resetGqlClientFactory()
    }

    /** GraphQL answers each request with the identifications `answer` builds from the requested ids. */
    private void gqlAnswers(Closure<List<Map>> answer, boolean hasNextPage = false) {
        OmsReturnsSourceSupport.setGqlClientFactory { Map config ->
            OmsGqlClient c = new OmsGqlClient(config, { String url, Map headers, String body ->
                Map payload = (Map) new JsonSlurper().parseText(body)
                gqlRequests.add(payload)
                String q = ((Map) payload.variables).q as String
                List<String> ids = (q - "idValue:").split(",").toList()
                List<Map> nodes = answer.call(ids)
                return [statusCode: 200, body: JsonOutput.toJson([data: [returnIdentifications: [
                        edges: nodes.collect { [node: it] }, pageInfo: [hasNextPage: hasNextPage]]], errors: []])]
            })
            c.sleeper = { long ms -> }
            return c
        }
    }

    private static Map ident(String idValue, String type = "SHOPIFY_RETURN_ID", String returnId = "M1") {
        return [returnId: returnId, returnIdentificationTypeId: type, idValue: idValue]
    }

    @Test
    void aPendingReturnInvisibleToRestIsFoundByGraphQl() {
        gqlAnswers { List<String> ids -> ids.findAll { it == "37447008387" }.collect { ident(it, "SHOPIFY_RETURN_ID", "M246772") } }

        Map result = OmsReturnsSourceSupport.lookupReturnsByExternalId(CONFIG, ["37447008387", "99999999999"])

        assertTrue(result.ok as Boolean)
        assertEquals(["37447008387"], result.foundIds)
        assertEquals(["99999999999"], result.missingIds, "an id GraphQL also cannot find is genuinely missing")
        assertEquals([], result.unresolvedIds)
        assertTrue(((String) gqlRequests[0].query).contains("returnIdentifications"))
    }

    @Test
    void theOldEnumCountsAsAShopifyReference() {
        gqlAnswers { List<String> ids -> ids.collect { ident(it, "SHOPIFY_RTN_ID") } }

        Map result = OmsReturnsSourceSupport.lookupReturnsByExternalId(CONFIG, ["27151073411"])

        assertEquals(["27151073411"], result.foundIds)
    }

    @Test
    void aNonShopifyIdentificationWithTheSameValueIsNotAMatch() {
        // NetSuite RMA ids are 8-digit numerics in the same table; a coincidental value under another
        // type says nothing about whether OMS holds the Shopify return.
        gqlAnswers { List<String> ids -> ids.collect { ident(it, "NETSUITE_RMA_ID") } }

        Map result = OmsReturnsSourceSupport.lookupReturnsByExternalId(CONFIG, ["80249409"])

        assertEquals([], result.foundIds)
        assertEquals(["80249409"], result.missingIds)
    }

    @Test
    void graphQlIsAskedOnlyForIdsRestLeftUnresolved() {
        OmsReturnsSourceSupport.setHttpClient { Map request ->
            String url = request.url as String
            if (url.contains("shopifyReturnId=")) return [statusCode: 200, body: restBody([])]
            return [statusCode: 200, body: restBody([[returnId: "M1", externalId: "aaa"]])]
        }
        gqlAnswers { List<String> ids -> [] }

        Map result = OmsReturnsSourceSupport.lookupReturnsByExternalId(CONFIG, ["aaa", "bbb"])

        assertEquals(1, gqlRequests.size())
        assertEquals("idValue:bbb", ((Map) gqlRequests[0].variables).q)
        assertEquals(["aaa"], result.foundIds)
        assertEquals(["bbb"], result.missingIds)
    }

    @Test
    void noGraphQlCallWhenRestResolvedEverything() {
        OmsReturnsSourceSupport.setHttpClient { Map request ->
            [statusCode: 200, body: restBody([[returnId: "M1", externalId: "aaa"]])]
        }
        gqlAnswers { List<String> ids -> throw new AssertionError("GraphQL must not be called") }

        Map result = OmsReturnsSourceSupport.lookupReturnsByExternalId(CONFIG, ["aaa"])

        assertEquals(0, gqlRequests.size())
        assertEquals(["aaa"], result.foundIds)
    }

    @Test
    void graphQlBatchesTheUnresolvedIds() {
        gqlAnswers { List<String> ids -> [] }

        List<String> ids = (1..45).collect { "id${it}".toString() }
        OmsReturnsSourceSupport.lookupReturnsByExternalId(CONFIG, ids)

        assertEquals(3, gqlRequests.size(), "45 ids at 20 per call is 3 calls, never one per id")
        assertTrue(gqlRequests.every { !((String) it.query).toLowerCase().contains("mutation") })
    }

    @Test
    void aGraphQlFailureLeavesIdsUncheckedNotConfirmedMissing() {
        // An OMS without the 2026-09-30 GraphQL upgrade answers FieldUndefined. REST cannot see pending
        // returns, so calling those ids "confirmed missing" would publish a blind spot as a finding.
        OmsReturnsSourceSupport.setGqlClientFactory { Map config ->
            OmsGqlClient c = new OmsGqlClient(config, { String url, Map headers, String body ->
                [statusCode: 200, body: JsonOutput.toJson([data: null,
                        errors: [[message: "Field 'returnIdentifications' in type 'Query' is undefined",
                                  extensions: [code: "FieldUndefined"]]]])]
            })
            c.sleeper = { long ms -> }
            return c
        }

        Map result = OmsReturnsSourceSupport.lookupReturnsByExternalId(CONFIG, ["37447008387"])

        assertTrue(result.ok as Boolean, "the REST passes succeeded; the lookup as a whole did not fail")
        assertEquals([], result.foundIds)
        assertEquals([], result.missingIds)
        assertEquals(["37447008387"], result.unresolvedIds)
        assertTrue((result.errors as List).any { (it as String).contains("GraphQL") }, "${result.errors}")
    }

    @Test
    void aGraphQlAnswerThatEchoesNoRequestedIdIsAnIgnoredFilter() {
        // Same guard as the REST pass: a filter the server did not apply answers 200 with unrelated rows.
        gqlAnswers { List<String> ids -> [ident("something-else")] }

        Map result = OmsReturnsSourceSupport.lookupReturnsByExternalId(CONFIG, ["aaa"])

        assertEquals([], result.foundIds)
        assertEquals([], result.missingIds)
        assertEquals(["aaa"], result.unresolvedIds)
    }

    @Test
    void aTruncatedGraphQlPageLeavesItsUnmatchedIdsUnchecked() {
        // hasNextPage means some identifications were not returned, so an id absent from this page
        // may still exist. Only ids actually seen are classified.
        gqlAnswers({ List<String> ids -> [ident("aaa")] }, true)

        Map result = OmsReturnsSourceSupport.lookupReturnsByExternalId(CONFIG, ["aaa", "bbb"])

        assertEquals(["aaa"], result.foundIds)
        assertEquals([], result.missingIds)
        assertEquals(["bbb"], result.unresolvedIds)
    }

    private static String restBody(List returns) {
        return JsonOutput.toJson([returns: returns, returnsCount: returns.size(), hasMore: false,
                                  pageIndex: 0, pageSize: 50, excludedNoShopifyRefCount: 0])
    }
}
