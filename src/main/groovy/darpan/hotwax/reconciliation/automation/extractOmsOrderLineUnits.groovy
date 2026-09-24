import darpan.facade.common.DataManagerSupport
import darpan.facade.reconciliation.RunObservability
import darpan.hotwax.oms.OmsRestSourceSupport

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

OmsRestSourceSupport.requireUsableOmsConfig(ec, sourceConfig, configIdValue, companyUserGroupIdValue, "OMS_ORDER_LINE_UNITS")

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
        ? File.createTempFile("oms-order-line-units-extract-", ".partial", outputDirectory)
        : File.createTempFile("oms-order-line-units-extract-", ".partial")

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
    // No keepRecordFields is passed, and that is deliberate rather than an omission: the unit
    // record IS the projection. An order-shaped keepRecordFields would strip every field a unit
    // record has, leaving a file of empty objects that still counts as a successful extract.
    //
    // applyExchangeExclusion is stated explicitly even though it already defaults to true for
    // SALES_ORDER, because at item grain the temptation is to turn it OFF (see the connector seed
    // comment, and DAR-BE-050 D2): an exchange line sits on an ORIGINAL Shopify order that may be
    // months old, so a creation-windowed Shopify sweep never sees it while OMS lands a new EXC-
    // order inside the window. Inverting this makes every exchange a false missing-in-Shopify.
    Map extractOptions = [
            emitGrain             : "ORDER_LINE_UNIT",
            windowFieldName       : windowFieldName?.toString()?.trim() ?: null,
            applyExchangeExclusion: true,
            orderStatusIds        : (orderStatusIds instanceof List) ? (List) orderStatusIds : null,
    ]
    Map extraction = OmsRestSourceSupport.extractOrdersToFile(sourceConfig, windowStart, windowEnd, workFile,
            null, pageProgressListener, sourceFiltersValue, extractOptions)
    warnings = extraction.warnings ?: []
    errors = extraction.errors ?: []
    requestMetadata = extraction.requestMetadata ?: [:]
    recordCount = extraction.recordCount ?: 0
    dataAvailable = extraction.dataAvailable == true

    // Units the extract could not key are LOST, not excluded, so they are surfaced as a warning
    // rather than left for whoever thinks to open requestMetadata. A clean run that silently
    // dropped units is the failure this whole pair exists to prevent.
    int droppedNullLineIdCount = (requestMetadata?.droppedNullLineIdCount ?: 0) as int
    if (droppedNullLineIdCount > 0) {
        warnings = warnings + ["${droppedNullLineIdCount} order item(s) had no Shopify line id and were not compared.".toString()]
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
} finally {
    if (workFile.exists()) workFile.delete()
}
