import darpan.facade.common.DataManagerSupport
import darpan.facade.reconciliation.RunObservability
import darpan.hotwax.oms.OmsRestSourceSupport
import darpan.reconciliation.conclusion.ExcludedRecordsSidecar

String configIdValue = omsRestSourceConfigId?.toString()?.trim()
String companyUserGroupIdValue = companyUserGroupId?.toString()?.trim()
if (!configIdValue) {
    errors = ["OMS REST Source Config ID is required."]
    warnings = []
    dataAvailable = false
    recordCount = 0
    return
}

def sourceConfig = ec.entity.find("darpan.hotwax.HotWaxOmsRestSourceConfig")
        .condition("omsRestSourceConfigId", configIdValue)
        .disableAuthz()
        .useCache(false)
        .one()

OmsRestSourceSupport.requireUsableOmsConfig(ec, sourceConfig, configIdValue, companyUserGroupIdValue, "OMS_ORDER_ITEMS")

if (sourceConfig && (sourceConfig.isActive ?: "Y").toString().equalsIgnoreCase("N")) {
    ec.message.addError("OMS REST source config ${configIdValue} is inactive.")
}

if (ec.message.hasError()) {
    errors = (ec.message?.getErrors() ?: []) as List
    warnings = []
    dataAvailable = false
    recordCount = 0
    return
}

String timestamp = DataManagerSupport.formatRunTimestamp(ec)
String outputBaseLocation = outputLocation ?: DataManagerSupport.resolveReconciliationRunLocation(
        ec,
        automationExecutionId ?: configIdValue,
        timestamp
)

File outputDirectory = DataManagerSupport.resolveDirectoryFile(ec, outputBaseLocation, true)
File workFile = outputDirectory != null
        ? File.createTempFile("oms-order-items-extract-", ".partial", outputDirectory)
        : File.createTempFile("oms-order-items-extract-", ".partial")

final long PROGRESS_MIN_INTERVAL_MS = 2000L
Closure pageProgressListener = null
String progressRunId = reconciliationRunResultId?.toString()?.trim()
String progressStage = progressStageCode?.toString()?.trim() ?: RunObservability.STAGE_EXTRACT_FILE2
Integer progressExpectedTotal = null
try {
    progressExpectedTotal = expectedRecordCount != null ? (expectedRecordCount as Integer) : null
} catch (Exception ignored) {
}
if (progressRunId) {
    Integer expectedTotal = progressExpectedTotal != null && progressExpectedTotal > 0 ? progressExpectedTotal : null
    long lastReportedAtMs = 0L
    pageProgressListener = { Object cumulativeRawCount ->
        long nowMs = System.currentTimeMillis()
        if (nowMs - lastReportedAtMs < PROGRESS_MIN_INTERVAL_MS) return
        lastReportedAtMs = nowMs
        RunObservability.heartbeatStageProgress(ec, progressRunId, progressStage, cumulativeRawCount, expectedTotal)
    }
}

try {
    List sourceFiltersValue = (sourceFilters instanceof List) ? (List) sourceFilters : null
    // Tri-system D4. Same flattener and unit record as extract#HotWaxOmsOrderLineUnits, with the
    // two DAR-BE-050 guards that only exist for the Shopify side turned OFF:
    //  - applyExchangeExclusion false: an EXC- order is a real OMS order that syncs to NetSuite the
    //    same day. The exclusion exists only for Shopify's creation-window asymmetry.
    //  - requireShopifyLineId false: the NetSuite hops key on the OMS order, so an item with no
    //    Shopify line id is still a real unit; it is emitted with that id null, and counted.
    // No keepRecordFields, for the same reason as the sibling: the unit record IS the projection.
    Map extractOptions = [
            emitGrain             : "ORDER_LINE_UNIT",
            windowFieldName       : windowFieldName?.toString()?.trim() ?: null,
            applyExchangeExclusion: false,
            requireShopifyLineId  : false,
            orderStatusIds        : (orderStatusIds instanceof List) ? (List) orderStatusIds : null,
    ]
    Map extraction = OmsRestSourceSupport.extractOrdersToFile(sourceConfig, windowStart, windowEnd, workFile,
            null, pageProgressListener, sourceFiltersValue, extractOptions)
    warnings = extraction.warnings ?: []
    errors = extraction.errors ?: []
    requestMetadata = extraction.requestMetadata ?: [:]
    recordCount = extraction.recordCount ?: 0
    dataAvailable = extraction.dataAvailable == true

    // Nothing is dropped for want of a Shopify id here, but the count is still surfaced: an item
    // OMS never tied to a Shopify line is worth knowing about even when it is compared.
    int keptNullShopifyIdCount = (requestMetadata?.keptNullShopifyIdCount ?: 0) as int
    if (keptNullShopifyIdCount > 0) {
        warnings = warnings + ["${keptNullShopifyIdCount} order item(s) had no Shopify order or line id; they were compared with it left empty.".toString()]
    }

    if (errors) {
        fileLocation = null
        fileName = null
        return
    }

    String outputFileName = OmsRestSourceSupport.safeFileName(fileName ?: extraction.fileName)
    fileName = outputFileName
    fileLocation = DataManagerSupport.childLocation(outputBaseLocation, outputFileName)
    DataManagerSupport.moveIntoLocation(ec, workFile, fileLocation as String)
    // DAR-UI-044: what the source filters dropped, beside the extract, for the conclude pass. Advisory.
    try {
        ExcludedRecordsSidecar.writeBeside(ec, (Map) extraction.excludedCollector, outputBaseLocation, outputFileName, outputDirectory)
    } catch (Exception sidecarError) {
        warnings = ((warnings ?: []) as List) + ["Excluded-records sidecar not written: ${sidecarError.message}".toString()]
    }
} finally {
    if (workFile.exists()) workFile.delete()
}
