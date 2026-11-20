package darpan.hotwax.oms

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

import java.nio.file.Files
import java.sql.Timestamp
import java.time.Instant

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-318 plan 3b-1 (ruling P3b1-9). project#HotWaxOmsKeptRecords' body: when a kept copy of HotWax orders or returns may
 * stand in for a side's live read, and how it trims the slice's records and lists the window's exchange orders. Pure; the
 * written exchange manifest is exercised end to end by darpan's RollingCopyParityTests through the real service.
 */
class OmsKeptRecordsServingTests {

    private static final Timestamp START = Timestamp.from(Instant.parse("2026-11-10T08:00:00Z"))
    private static final Timestamp END = Timestamp.from(Instant.parse("2026-11-11T08:00:00Z"))
    private static final List<String> BASE = ["orderId", "statusId", "orderDate"]
    private final File dir = Files.createTempDirectory("oms-kept-serving").toFile()

    @AfterEach
    void cleanup() {
        dir.deleteDir()
    }

    @Test
    void aCopyLackingAFieldIsRefusedNamingIt() {
        Map out = OmsKeptRecordsServing.project(null, request("OMS_GQL", [keptFields: BASE, sideFiles: [:]], BASE + ["salesChannelEnumId"]))
        assertTrue((out.servingProblem as String).contains("salesChannelEnumId"))
        assertEquals(["salesChannelEnumId"], out.missingFields)
        assertTrue((OmsKeptRecordsServing.project(null, request("OMS_SOMETHING", [keptFields: BASE], BASE)).servingProblem as String)
                .contains("no projection"))
    }

    /** The orders read writes an exchange manifest; a copy that cannot list the window's exchange orders as it would is refused. */
    @Test
    void anOrdersCopyMustListTheWindowsExchangeOrdersAsTheLiveReadWould() {
        assertTrue((check([keptFields: BASE, sideFiles: null]).servingProblem as String).contains("no side files"))
        assertNull(check([keptFields: BASE, sideFiles: [:]]).servingProblem, "a read that wrote no manifest set no exchange aside")
        assertTrue((check([keptFields: BASE, sideFiles: manifest([entry("E1", "2026-11-10T09:00:00Z")], true)]).servingProblem as String)
                .contains("cut short"))
        assertTrue((check([keptFields: BASE, sideFiles: manifest([[omsOrderId: "E0"]])]).servingProblem as String).contains("orderDate"))
        List<Map> many = (1..501).collect { entry("E${it}", "2026-11-10T09:00:00Z") }
        assertTrue((check([keptFields: BASE, sideFiles: manifest(many)]).servingProblem as String).contains("more exchange orders"))
        Map listed = OmsKeptRecordsServing.exchangeEntries(manifest([entry("IN", "2026-11-10T08:00:00Z"), entry("OUT", "2026-11-11T08:00:00Z"),
                                                                     entry("BEFORE", "2026-11-10T07:59:59Z")]), "orderDate", START, END, 500)
        assertEquals(["IN"], listed.entries*.omsOrderId, "half-open on orderDate, as the live read's window")
    }

    @Test
    void theSlicesRecordsAreTrimmedAsTheExtractTrimsThem() {
        File records = new File(dir, "records.jsonl")
        records.text = [[returnId: "R1", statusId: "A", entryDate: 1, items: [[x: 1]]], [returnId: "R2", statusId: "B", entryDate: 2]]
                .collect { JsonOutput.toJson(it) }.join("\n") + "\n"
        File output = new File(dir, "out.jsonl")
        Map out = OmsKeptRecordsServing.project(null, request("OMS_RETURNS_GQL", [keptFields: null, heldFields: null], ["returnId", "entryDate"],
                "entryDate") + [recordsFile: records.absolutePath, outputFile: output.absolutePath])
        assertNull(out.servingProblem)
        assertEquals(2L, out.recordCount)
        assertEquals([[returnId: "R1", entryDate: 1, items: [[x: 1]]], [returnId: "R2", entryDate: 2]],
                output.readLines().collect { new JsonSlurper().parseText(it) }, "keep fields plus items, in the record's order")
        assertEquals([:], out.resultFields, "returns answer nothing besides their file")

        // orders whose window held no exchange order write no manifest, and say so
        records.text = JsonOutput.toJson([orderId: "O1", statusId: "S", orderDate: 5, productStoreId: "X"]) + "\n"
        Map orders = OmsKeptRecordsServing.project(null, request("OMS_GQL", [keptFields: BASE + ["productStoreId"], sideFiles: [:]], BASE) +
                [recordsFile: records.absolutePath, outputFile: output.absolutePath])
        assertEquals([[orderId: "O1", statusId: "S", orderDate: 5]], output.readLines().collect { new JsonSlurper().parseText(it) })
        assertEquals([exchangeManifestFileLocation: null], orders.resultFields)
        assertEquals([], orders.writtenLocations)
    }

    /** I2: a planned dataset read keeps only fields the HotWax read can select, so one bad field never fails the shared read. */
    @Test
    void aPlannedReadKeepsOnlyFieldsTheOmsReadCanSelect() {
        Map orders = OmsKeptRecordsServing.project(null, [systemEnumId: "OMS_GQL",
                unionFields: ["orderId", "notAnOmsField", "paymentRefNums", "billToCustomer", "salesChannelEnumId"]])
        assertEquals(["orderId", "paymentRefNums", "billToCustomer", "salesChannelEnumId"], orders.keepableFields)
        assertEquals(["returnId", "anything"], OmsKeptRecordsServing.project(null, [systemEnumId: "OMS_RETURNS_GQL",
                unionFields: ["returnId", "anything"]]).keepableFields, "a returns record keeps whatever it carries, never fails")
        assertNull(OmsKeptRecordsServing.project(null, [systemEnumId: "OMS_OTHER", unionFields: ["x"]]).keepableFields,
                "an endpoint HotWax keeps no projection for keeps whole records")
        try {
            OmsGqlQueries.orderGrainPagePlan(["orderId", "notAnOmsField"] as Set)
            throw new AssertionError("the order read refuses a field it cannot select")
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.message.contains("notAnOmsField"))
        }
    }

    private Map check(Map copy) {
        return OmsKeptRecordsServing.project(null, request("OMS_GQL", copy, BASE))
    }

    private static Map request(String system, Map copy, List<String> keep, String windowField = "orderDate") {
        return [systemEnumId: system, copy: copy, side: [keepFields: keep, filterFields: [], readFields: [], windowField: windowField],
                windowStart : START, windowEnd: END]
    }

    private Map<String, String> manifest(List<Map> entries, boolean truncated = false) {
        File f = new File(dir, "read-000001.exchange-manifest.json")
        f.text = JsonOutput.toJson([manifest: entries, truncated: truncated, sourceFileName: "read-000001.json"])
        return [(f.name): f.absolutePath]
    }

    private static Map entry(String id, String orderDate) {
        return [omsOrderId: id, externalId: "SH${id}".toString(), orderDate: Instant.parse(orderDate).toEpochMilli()]
    }
}
