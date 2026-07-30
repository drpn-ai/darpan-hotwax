import darpan.facade.common.TenantAccessSupport
import darpan.hotwax.oms.OmsRestSourceSupport

ok = false
ordersByExternalId = [:]
errors = []

String configIdValue = omsRestSourceConfigId?.toString()?.trim()
String companyUserGroupIdValue = companyUserGroupId?.toString()?.trim()
if (!configIdValue) { errors = ["OMS REST Source Config ID is required."]; return }

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
            ec, sourceConfig,
            "OMS REST source config ${configIdValue} not found.",
            "OMS REST source config ${configIdValue} is not available in your active tenant.")
}
if (sourceConfig && (sourceConfig.isActive ?: "Y").toString().equalsIgnoreCase("N")) {
    ec.message.addError("OMS REST source config ${configIdValue} is inactive.")
}
if (ec.message.hasError()) { errors = (ec.message?.getErrors() ?: []) as List; return }

// Default window: 400 days back from tomorrow — wide enough for any original order's orderDate.
long thruMillis = (windowEndMillis instanceof Number) ? ((Number) windowEndMillis).longValue()
        : System.currentTimeMillis() + 86400000L
long fromMillis = (windowStartMillis instanceof Number) ? ((Number) windowStartMillis).longValue()
        : thruMillis - 400L * 86400000L

Map result = OmsRestSourceSupport.lookupOrdersByExternalId(sourceConfig, (List) externalIds, fromMillis, thruMillis)
ok = result.ok
ordersByExternalId = result.ordersByExternalId
errors = result.errors
