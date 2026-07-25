import darpan.facade.common.DataManagerSupport
import darpan.facade.common.TenantAccessSupport
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

if (companyUserGroupIdValue) {
    if (!sourceConfig) {
        ec.message.addError("OMS REST source config ${configIdValue} not found.")
    } else if (sourceConfig.companyUserGroupId?.toString()?.trim() != companyUserGroupIdValue) {
        ec.message.addError("OMS REST source config ${configIdValue} is not available in this automation tenant.")
    }
} else {
    TenantAccessSupport.requireTenantRecordAccess(
            ec,
            sourceConfig,
            "OMS REST source config ${configIdValue} not found.",
            "OMS REST source config ${configIdValue} is not available in your active tenant."
    )
}

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

// Stream the extraction page by page into a work file (in the output directory when it is
// file-backed, so the final move is an atomic same-volume rename) and only move it into place
// after the whole window succeeded. The window is never materialized in heap, and a mid-window
// failure never leaves a partial file where reconciliation could read it.
File outputDirectory = DataManagerSupport.resolveDirectoryFile(ec, outputBaseLocation, true)
File workFile = outputDirectory != null
        ? File.createTempFile("oms-orders-extract-", ".partial", outputDirectory)
        : File.createTempFile("oms-orders-extract-", ".partial")

try {
    Map extraction = OmsRestSourceSupport.extractOrdersToFile(sourceConfig, windowStart, windowEnd, workFile)
    warnings = extraction.warnings ?: []
    errors = extraction.errors ?: []
    requestMetadata = extraction.requestMetadata ?: [:]
    recordCount = extraction.recordCount ?: 0
    dataAvailable = extraction.dataAvailable == true

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
