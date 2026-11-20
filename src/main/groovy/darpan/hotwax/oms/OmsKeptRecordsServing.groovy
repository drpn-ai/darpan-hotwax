package darpan.hotwax.oms

import darpan.dataset.KeptCopyStore
import darpan.facade.common.DataManagerSupport
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import java.sql.Timestamp
import java.time.Instant

/**
 * DAR-BE-318 plan 3b-1 (ruling P3b1-9): project#HotWaxOmsKeptRecords, the HotWax side of darpan's kept-copy projection
 * contract (dataset.DatasetServices.project#KeptRecords). The OMS orders and returns connector rows name it
 * (data/HotWaxConnectorSeedData.xml), so a kept-copy slice of those endpoints is trimmed by the same code the extracts trim
 * with ({@link OmsKeptFieldsProjection}), and only when that code says the copy stands in for the side's live read.
 *
 * <p>The orders extract also writes an exchange manifest beside its file (the exchange orders it set aside in its window),
 * which the exchange-pair check reads. A dataset read keeps its own manifest among the copy's side files; a slice lists the
 * exchange orders its window holds, as the window's live read would. A copy that kept no side files, a manifest cut short,
 * or a window holding more than the live read lists makes the side read live.</p>
 */
class OmsKeptRecordsServing {

    static final String EXCHANGE_MANIFEST_SUFFIX = ".exchange-manifest.json"

    /** The service body: the contract's in-parameters in, its out-parameters back. */
    static Map<String, Object> project(def ec, Map ctx) {
        OmsKeptFieldsProjection.Spec spec = OmsKeptFieldsProjection.specFor(ctx.systemEnumId)
        if (spec == null) return [servingProblem: "HotWax keeps no projection for ${ctx.systemEnumId}".toString()]
        Map copy = (ctx.copy ?: [:]) as Map
        Map side = (ctx.side ?: [:]) as Map
        Map problem = OmsKeptFieldsProjection.servingProblem(spec, copy, side)
        if (problem != null) {
            return [servingProblem: problem.reason, missingFields: problem.missing == null ? null : new ArrayList(problem.missing as Collection).sort()]
        }
        Map exchange = spec.exchangeManifestMax == null ? null :
                exchangeEntries(copy.sideFiles as Map, side.windowField as String, ctx.windowStart as Timestamp,
                        ctx.windowEnd as Timestamp, spec.exchangeManifestMax)
        if (exchange?.problem) return [servingProblem: exchange.problem]
        if (!ctx.recordsFile) return [:]

        Set<String> keep = OmsKeptFieldsProjection.keptBy(spec, side.keepFields)
        long count = 0
        JsonSlurper slurper = new JsonSlurper()
        new File(ctx.outputFile as String).withWriter("UTF-8") { Writer w ->
            new File(ctx.recordsFile as String).eachLine("UTF-8") { String line ->
                if (!line) return
                // the extracts trim after their filters; the slice has applied the side's filters already
                w.write(keep == null ? line : JsonOutput.toJson(OmsKeptFieldsProjection.project(slurper.parseText(line), keep)))
                w.write("\n")
                count++
            }
        }
        Map<String, Object> out = [recordCount: count, writtenLocations: [], resultFields: [:]]
        if (exchange != null) {
            // as the live read: a file only when the window held an excluded exchange order, its location answered either way
            String location = null
            if (exchange.entries) {
                location = writeExchangeManifest(ec, (List) exchange.entries, ctx.outputLocation as String, ctx.fileName as String)
                ((List) out.writtenLocations).add(location)
            }
            ((Map) out.resultFields).put("exchangeManifestFileLocation", location)
        }
        return out
    }

    /** The copy's excluded exchange orders in [from, to), or [problem] when a slice cannot list them as the live read would. */
    static Map exchangeEntries(Map sideFiles, String windowField, Timestamp from, Timestamp to, int liveMax) {
        if (sideFiles == null) return [problem: "the copy kept no side files, and the live read writes an exchange manifest"]
        String path = sideFiles.find { k, v -> (k as String).endsWith(EXCHANGE_MANIFEST_SUFFIX) }?.value as String
        // the read writes a manifest only when it set an exchange order aside
        if (path == null) return [entries: []]
        Object parsed = new JsonSlurper().parse(new File(path), "UTF-8")
        if (!(parsed instanceof Map)) return [problem: "the copy's exchange manifest is unreadable"]
        if (((Map) parsed).truncated == true) return [problem: "the copy's exchange manifest was cut short"]
        List all = (((Map) parsed).manifest ?: []) as List
        Instant start = from.toInstant(), end = to.toInstant()
        List entries = []
        for (Object entry : all) {
            Instant at = KeptCopyStore.parseInstant(entry instanceof Map ? ((Map) entry).get(windowField) : null)
            if (at == null) return [problem: "an exchange order in the copy's manifest has no ${windowField}".toString()]
            if (!at.isBefore(start) && at.isBefore(end)) entries.add(entry)
        }
        // the live read lists at most so many and says it cut the list: a slice cannot say which it would have kept
        if (entries.size() > liveMax) return [problem: "the window holds more exchange orders than the live read lists"]
        return [entries: entries]
    }

    /** Writes {manifest, truncated, sourceFileName} beside the slice, as OmsScriptServiceSupport does beside the extract. */
    private static String writeExchangeManifest(def ec, List entries, String outputLocation, String fileName) {
        File outDir = DataManagerSupport.resolveDirectoryFile(ec, outputLocation, true)
        File work = File.createTempFile("oms-kept-exchange-", ".partial", outDir)
        try {
            work.setText(JsonOutput.toJson([manifest: entries, truncated: false, sourceFileName: fileName]), "UTF-8")
            String location = DataManagerSupport.childLocation(outputLocation, OmsExtractSupport.exchangeManifestFileName(fileName))
            DataManagerSupport.moveIntoLocation(ec, work, location)
            return location
        } finally {
            work.delete()
        }
    }
}
