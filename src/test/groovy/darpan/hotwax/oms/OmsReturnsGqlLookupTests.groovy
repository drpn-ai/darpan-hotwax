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

    private List<Map> returnsRequests = []

    /**
     * GraphQL answers by root. returnIdentifications: the identifications `answer` builds from the
     * requested ids. returns (the returnId / externalId passes, DAR-BE-116): the rows `returnsAnswer`
     * builds from (field, ids) — none by default, so those passes find nothing.
     */
    private void gqlAnswers(Closure<List<Map>> answer, boolean hasNextPage = false,
                            Closure<List<Map>> returnsAnswer = { String field, List<String> ids -> [] }) {
        OmsReturnsSourceSupport.setGqlClientFactory { Map config ->
            OmsGqlClient c = new OmsGqlClient(config, { String url, Map headers, String body ->
                Map payload = (Map) new JsonSlurper().parseText(body)
                String q = ((Map) payload.variables).q as String
                if ((payload.query as String).contains("returns(")) {
                    returnsRequests.add(payload)
                    String field = q.substring(0, q.indexOf(":"))
                    List<Map> rows = returnsAnswer.call(field, q.substring(q.indexOf(":") + 1).split(",").toList())
                    return [statusCode: 200, body: JsonOutput.toJson([data: [returns: [
                            edges: rows.collect { [node: it] }, pageInfo: [hasNextPage: false]]], errors: []])]
                }
                gqlRequests.add(payload)
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
    void identificationsAreAskedOnlyForIdsTheReturnsPassesLeft() {
        gqlAnswers({ List<String> ids -> [] }, false, { String field, List<String> ids ->
            field == "externalId" ? ids.findAll { it == "aaa" }.collect { [returnId: "M1", externalId: it] } : [] })

        Map result = OmsReturnsSourceSupport.lookupReturnsByExternalId(CONFIG, ["aaa", "bbb"])

        assertEquals(1, gqlRequests.size())
        assertEquals("idValue:bbb", ((Map) gqlRequests[0].variables).q)
        assertEquals(["aaa"], result.foundIds)
        assertEquals(["bbb"], result.missingIds)
    }

    @Test
    void anOmsReturnIdIsFoundByTheFirstPassAndAsksNothingMore() {
        // DAR-BE-116: a returnId-keyed compare (OMS returns vs NetSuite RMAs) hands the lookup OMS returnIds.
        gqlAnswers({ List<String> ids -> throw new AssertionError("identifications must not be asked") }, false,
                { String field, List<String> ids -> field == "returnId" ? ids.collect { [returnId: it, externalId: null] } : [] })

        Map result = OmsReturnsSourceSupport.lookupReturnsByExternalId(CONFIG, ["M246772"])

        assertEquals(["M246772"], result.foundIds)
        assertEquals(1, returnsRequests.size(), "no externalId pass once returnId resolved everything")
        assertEquals("returnId:M246772", ((Map) returnsRequests[0].variables).q)
        assertEquals(0, gqlRequests.size())
    }

    @Test
    void theLookupNeverCallsRest() {
        OmsReturnsSourceSupport.setHttpClient { Map request -> throw new AssertionError("REST must not be called: ${request.url}") }
        gqlAnswers { List<String> ids -> [] }

        Map result = OmsReturnsSourceSupport.lookupReturnsByExternalId(CONFIG, ["aaa"])

        assertEquals(["aaa"], result.missingIds)
    }

    @Test
    void aReturnsFilterTheServerIgnoredLeavesTheChunkUnresolved() {
        gqlAnswers({ List<String> ids -> [] }, false,
                { String field, List<String> ids -> field == "externalId" ? [[returnId: "M9", externalId: "unrelated"]] : [] })

        Map result = OmsReturnsSourceSupport.lookupReturnsByExternalId(CONFIG, ["aaa"])

        assertEquals([], result.missingIds)
        assertEquals(["aaa"], result.unresolvedIds)
        assertTrue((result.errors as List).any { (it as String).contains("externalId") }, "${result.errors}")
    }

    @Test
    void everyPassBatchesItsIds() {
        gqlAnswers { List<String> ids -> [] }

        List<String> ids = (1..120).collect { "id${it}".toString() }
        OmsReturnsSourceSupport.lookupReturnsByExternalId(CONFIG, ids)

        assertEquals(3, gqlRequests.size(), "120 ids at 50 per call is 3 calls, never one per id")
        assertEquals(6, returnsRequests.size(), "returnId and externalId passes, 3 chunks each")
        assertTrue((gqlRequests + returnsRequests).every { !((String) it.query).toLowerCase().contains("mutation") })
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

        assertTrue(result.ok as Boolean, "a pass that could not run is unresolved ids, not a failed lookup")
        assertEquals([], result.foundIds)
        assertEquals([], result.missingIds)
        assertEquals(["37447008387"], result.unresolvedIds)
        assertTrue((result.errors as List).any { (it as String).contains("GraphQL") }, "${result.errors}")
    }

    @Test
    void aServerErrorStopsEachPassAndIsReportedOncePerPass() {
        // DAR-BE-067, gorjana result 101474: the OMS entity layer answered every chunk with the same
        // Moqui REST 400, so 6 calls produced 6 identical warnings. Every chunk of a pass sends the same
        // document, so one server error is that pass's answer for all of them.
        OmsReturnsSourceSupport.setGqlClientFactory { Map config ->
            OmsGqlClient c = new OmsGqlClient(config, { String url, Map headers, String body ->
                gqlRequests.add((Map) new JsonSlurper().parseText(body))
                [statusCode: 400, body: JsonOutput.toJson([errorCode: 400,
                        errors: "Invalid entity relationship, org.apache.ofbiz.product.store.ProductStoreFacilityPurpose " +
                                "not found in definition for entity org.apache.ofbiz.product.facility.Facility"])]
            })
            c.sleeper = { long ms -> }
            return c
        }

        List<String> ids = (1..120).collect { "id${it}".toString() }
        Map result = OmsReturnsSourceSupport.lookupReturnsByExternalId(CONFIG, ids)

        assertEquals(3, gqlRequests.size(), "one call per pass, no further chunks after the server rejected the document")
        assertEquals(ids, result.unresolvedIds)
        assertEquals([], result.missingIds)
        assertEquals(3, (result.errors as List).size(), "${result.errors}")
        assertTrue((result.errors as List).every { (it as String).contains("120 id(s)") && (it as String).contains("ProductStoreFacilityPurpose") },
                "${result.errors}")
    }

    @Test
    void aTransportFailureOnOneChunkStillChecksTheRest() {
        // A timeout says nothing about the next chunk, unlike a server error.
        int identificationCalls = 0
        OmsReturnsSourceSupport.setGqlClientFactory { Map config ->
            OmsGqlClient c = new OmsGqlClient(config, { String url, Map headers, String body ->
                Map payload = (Map) new JsonSlurper().parseText(body)
                if ((payload.query as String).contains("returns(")) {
                    return [statusCode: 200, body: JsonOutput.toJson([data: [returns: [edges: [], pageInfo: [hasNextPage: false]]], errors: []])]
                }
                gqlRequests.add(payload)
                if (identificationCalls++ == 0) throw new java.net.http.HttpTimeoutException("request timed out")
                List<String> asked = ((((Map) payload.variables).q as String) - "idValue:").split(",").toList()
                [statusCode: 200, body: JsonOutput.toJson([data: [returnIdentifications: [
                        edges: asked.collect { [node: ident(it)] }, pageInfo: [hasNextPage: false]]], errors: []])]
            })
            c.sleeper = { long ms -> }
            return c
        }

        List<String> ids = (1..120).collect { "id${it}".toString() }
        Map result = OmsReturnsSourceSupport.lookupReturnsByExternalId(CONFIG, ids)

        assertEquals(3, gqlRequests.size())
        assertEquals(ids.take(50), result.unresolvedIds)
        assertEquals(ids.drop(50) as Set, result.foundIds as Set)
        assertEquals(1, (result.errors as List).size(), "${result.errors}")
        assertTrue(((result.errors as List)[0] as String).contains("50 id(s)"), "${result.errors}")
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
