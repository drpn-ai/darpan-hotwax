package darpan.hotwax.oms

import darpan.facade.common.DataManagerSupport
import darpan.facade.reconciliation.RunObservability
import darpan.reconciliation.conclusion.ExcludedRecordsSidecar
import groovy.json.JsonOutput

/**
 * The service edge shared by the OMS extract scripts (reconciliation/automation/extractOms*.groovy) and
 * the OMS lookup scripts (reconciliation/lookup/lookupOms*.groovy).
 *
 * A Moqui script service binds one script location and reads its parameters as bare bindings, so each
 * connector keeps its own thin script; everything those scripts did identically lives here. The script
 * passes its binding (`context`, the service's ContextStack) and this class reads inputs from it and
 * writes outputs into it exactly where the scripts used to assign bare variables — including leaving an
 * output UNSET on the paths that never assigned it (a gate failure leaves an input fileName in place).
 *
 * What still varies per script, and is passed in: the endpoint the gate checks, the work-file prefix,
 * the extraction call itself, the file-name sanitiser, a post-extraction warning, and which sidecars to
 * write. Everything connector-specific stays in OmsRestSourceSupport / OmsReturnsSourceSupport.
 */
class OmsScriptServiceSupport {
    static final String CONFIG_ENTITY_NAME = "darpan.hotwax.HotWaxOmsRestSourceConfig"
    static final String CONFIG_ID_REQUIRED = "OMS REST Source Config ID is required."
    static final long PROGRESS_MIN_INTERVAL_MS = 2000L

    /**
     * The config gate every OMS extract and lookup runs first: id present, config visible to the trusted
     * tenant (or the session's active tenant), endpoint enabled, config active.
     *
     * @return [usable: true, configId, sourceConfig], or [usable: false, errors]. On a refusal the errors
     *         are ec.message's own list (not a copy), as the scripts always returned them; `usable` is
     *         explicit because a validation-only message state yields an EMPTY error list that still
     *         refuses.
     */
    static Map<String, Object> gateConfig(def ec, Object omsRestSourceConfigId, Object companyUserGroupId,
                                          String requiredSystemEnumId) {
        String configIdValue = omsRestSourceConfigId?.toString()?.trim()
        String companyUserGroupIdValue = companyUserGroupId?.toString()?.trim()
        if (!configIdValue) return [usable: false, errors: [CONFIG_ID_REQUIRED]] as Map<String, Object>

        def sourceConfig = ec.entity.find(CONFIG_ENTITY_NAME)
                .condition("omsRestSourceConfigId", configIdValue)
                .disableAuthz()
                .useCache(false)
                .one()

        OmsRestSourceSupport.requireUsableOmsConfig(ec, sourceConfig, configIdValue, companyUserGroupIdValue, requiredSystemEnumId)

        if (sourceConfig && (sourceConfig.isActive ?: "Y").toString().equalsIgnoreCase("N")) {
            ec.message.addError("OMS REST source config ${configIdValue} is inactive.")
        }

        if (ec.message.hasError()) {
            return [usable: false, errors: (ec.message?.getErrors() ?: []) as List] as Map<String, Object>
        }
        return [usable: true, configId: configIdValue, sourceConfig: sourceConfig] as Map<String, Object>
    }

    /**
     * Live progress (advisory): with a run id, heartbeat the running record count onto this stage's
     * RUNNING extract step as pages are consumed. The count IS the progress — an expected total is
     * optional and only adds a percent, which the first extract of a run can never have because
     * nothing has finished yet to divide against.
     *
     * Throttled on elapsed time rather than on percent change: percent-throttling needed the total,
     * and a count has no natural "changed enough" threshold when page sizes vary by an order of
     * magnitude across sources (a 50-record OMS page vs a Shopify bulk page). Writing faster than the
     * UI polls buys nothing, so one heartbeat every couple of seconds is the useful ceiling — that
     * also bounds the write volume on a 99k-order window to a few hundred updates instead of ~2000.
     * The stage code is threaded in because the same extractor runs as file1 or file2 depending on
     * the saved run's source order; it used to be hardcoded to file2, so every run with OMS on the
     * first side silently looked up a step that was not RUNNING yet and reported nothing.
     * Never fails the extraction.
     *
     * @return the page listener, or null when there is no run id to report against.
     */
    static Closure throttledProgressListener(def ec, Object reconciliationRunResultId, Object progressStageCode,
                                             Object expectedRecordCount) {
        String progressRunId = reconciliationRunResultId?.toString()?.trim()
        String progressStage = progressStageCode?.toString()?.trim() ?: RunObservability.STAGE_EXTRACT_FILE2
        Integer progressExpectedTotal = null
        try {
            progressExpectedTotal = expectedRecordCount != null ? (expectedRecordCount as Integer) : null
        } catch (Exception ignored) {
        }
        if (!progressRunId) return null

        Integer expectedTotal = progressExpectedTotal != null && progressExpectedTotal > 0 ? progressExpectedTotal : null
        long lastReportedAtMs = 0L
        return { Object cumulativeRawCount ->
            long nowMs = System.currentTimeMillis()
            if (nowMs - lastReportedAtMs < PROGRESS_MIN_INTERVAL_MS) return
            lastReportedAtMs = nowMs
            RunObservability.heartbeatStageProgress(ec, progressRunId, progressStage, cumulativeRawCount, expectedTotal)
        }
    }

    /**
     * The whole body of an OMS extract script: gate, output location, work file, progress, the
     * extraction, the outputs, the final move, and the advisory sidecars.
     *
     * Outputs written into {@code context}, on each path exactly as the scripts assigned them:
     * a gate refusal sets errors, warnings, dataAvailable, recordCount (fileName and fileLocation are
     * left untouched); an extraction error additionally sets requestMetadata and nulls fileName and
     * fileLocation; success sets fileName and fileLocation and, when the exchange-manifest sidecar is
     * on, exchangeManifestFileLocation (null when the window held no exchange orders).
     *
     * @param spec
     *   requiredSystemEnumId (String) — the endpoint the gate checks, e.g. "OMS_TRANSFER_ORDERS";
     *   workFilePrefix (String) — temp-file prefix, e.g. "oms-transfer-orders-extract-";
     *   extract (Closure, Map -> Map) — receives [sourceConfig, workFile, keepRecordFields,
     *       pageProgressListener, sourceFilters] (the two lists already narrowed to List-or-null) and
     *       returns the support class's extraction result;
     *   safeFileName (Closure, optional) — defaults to OmsRestSourceSupport.safeFileName;
     *   afterExtract (Closure, optional) — runs after the extraction outputs are set and before the
     *       error check, for a script's own warning (it reads and writes the script binding directly);
     *   excludedRecordsSidecar (boolean) — write what the source filters dropped beside the extract;
     *   exchangeManifestSidecar (boolean) — write the exchange-manifest sidecar.
     */
    static void runExtract(def ec, Map context, Map spec) {
        Map<String, Object> gate = gateConfig(ec, context.get("omsRestSourceConfigId"), context.get("companyUserGroupId"),
                (String) spec.requiredSystemEnumId)
        if (!gate.usable) {
            context.put("errors", gate.errors)
            context.put("warnings", [])
            context.put("dataAvailable", false)
            context.put("recordCount", 0)
            return
        }
        def sourceConfig = gate.sourceConfig
        String configIdValue = (String) gate.configId

        String timestamp = DataManagerSupport.formatRunTimestamp(ec)
        String outputBaseLocation = context.get("outputLocation") ?: DataManagerSupport.resolveReconciliationRunLocation(
                ec,
                context.get("automationExecutionId") ?: configIdValue,
                timestamp
        )

        // Stream the extraction page by page into a work file (in the output directory when it is
        // file-backed, so the final move is an atomic same-volume rename) and only move it into place
        // after the whole window succeeded. The window is never materialized in heap, and a mid-window
        // failure never leaves a partial file where reconciliation could read it.
        String workFilePrefix = (String) spec.workFilePrefix
        File outputDirectory = DataManagerSupport.resolveDirectoryFile(ec, outputBaseLocation, true)
        File workFile = outputDirectory != null
                ? File.createTempFile(workFilePrefix, ".partial", outputDirectory)
                : File.createTempFile(workFilePrefix, ".partial")

        Closure pageProgressListener = throttledProgressListener(ec, context.get("reconciliationRunResultId"),
                context.get("progressStageCode"), context.get("expectedRecordCount"))

        try {
            Object keepRecordFields = context.get("keepRecordFields")
            Object sourceFilters = context.get("sourceFilters")
            Map extraction = (Map) ((Closure) spec.extract).call([
                    sourceConfig        : sourceConfig,
                    workFile            : workFile,
                    keepRecordFields    : (keepRecordFields instanceof List) ? (List) keepRecordFields : null,
                    pageProgressListener: pageProgressListener,
                    sourceFilters       : (sourceFilters instanceof List) ? (List) sourceFilters : null,
            ])
            List errors = extraction.errors ?: []
            context.put("warnings", extraction.warnings ?: [])
            context.put("errors", errors)
            context.put("requestMetadata", extraction.requestMetadata ?: [:])
            context.put("recordCount", extraction.recordCount ?: 0)
            context.put("dataAvailable", extraction.dataAvailable == true)

            if (spec.afterExtract instanceof Closure) ((Closure) spec.afterExtract).call()

            if (errors) {
                context.put("fileLocation", null)
                context.put("fileName", null)
                return
            }

            Closure safeFileName = (spec.safeFileName instanceof Closure) ? (Closure) spec.safeFileName
                    : { Object name -> OmsRestSourceSupport.safeFileName(name) }
            String outputFileName = safeFileName.call(context.get("fileName") ?: extraction.fileName)
            context.put("fileName", outputFileName)
            String fileLocation = DataManagerSupport.childLocation(outputBaseLocation, outputFileName)
            context.put("fileLocation", fileLocation)
            DataManagerSupport.moveIntoLocation(ec, workFile, fileLocation)

            if (spec.excludedRecordsSidecar) {
                // DAR-UI-044 / DAR-BE-063: what the source filters dropped, beside the extract, for the
                // conclude pass. Advisory. Empty at order grain until the shared shaper collects there
                // (see FilteringConnectorSidecarContractTests).
                try {
                    ExcludedRecordsSidecar.writeBeside(ec, (Map) extraction.excludedCollector, outputBaseLocation, outputFileName, outputDirectory)
                } catch (Exception sidecarError) {
                    context.put("warnings", ((context.get("warnings") ?: []) as List) +
                            ["Excluded-records sidecar not written: ${sidecarError.message}".toString()])
                }
            }

            if (spec.exchangeManifestSidecar) {
                writeExchangeManifest(ec, context, extraction, outputBaseLocation, outputFileName, outputDirectory)
            }
        } finally {
            if (workFile.exists()) workFile.delete()
        }
    }

    /**
     * The sidecar is advisory: it unlocks exchange-pair verification but the primary extract has
     * already succeeded, so a sidecar failure (including temp-file creation) must never fail the
     * extraction — degrade to a warning and move on. On the reconciliationOrders endpoint the entries
     * are built from the inline originalOrderId/originalExternalId, so the manifest costs no
     * pair-lookup round-trips at all.
     */
    private static void writeExchangeManifest(def ec, Map context, Map extraction, String outputBaseLocation,
                                              String outputFileName, File outputDirectory) {
        context.put("exchangeManifestFileLocation", null)
        List exchangeManifestValue = (List) (extraction.exchangeManifest ?: [])
        if (!exchangeManifestValue) return

        File manifestWorkFile = null
        try {
            String manifestFileName = OmsRestSourceSupport.exchangeManifestFileName(outputFileName)
            manifestWorkFile = outputDirectory != null
                    ? File.createTempFile("oms-exchange-manifest-", ".partial", outputDirectory)
                    : File.createTempFile("oms-exchange-manifest-", ".partial")
            manifestWorkFile.text = JsonOutput.toJson([
                    manifest: exchangeManifestValue,
                    truncated: extraction.exchangeManifestTruncated == true,
                    sourceFileName: outputFileName,
            ])
            String manifestLocation = DataManagerSupport.childLocation(outputBaseLocation, manifestFileName)
            DataManagerSupport.moveIntoLocation(ec, manifestWorkFile, manifestLocation)
            context.put("exchangeManifestFileLocation", manifestLocation)
        } catch (Exception e) {
            context.put("warnings", (context.get("warnings") ?: []) +
                    ["Exchange manifest sidecar could not be written: ${e.message} — exchange pair verification will be unavailable for this run.".toString()])
        } finally {
            if (manifestWorkFile != null && manifestWorkFile.exists()) manifestWorkFile.delete()
        }
    }
}
