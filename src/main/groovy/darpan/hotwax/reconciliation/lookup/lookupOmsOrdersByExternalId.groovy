import darpan.hotwax.oms.OmsPairLookupSupport
import darpan.hotwax.oms.OmsScriptServiceSupport

ok = false
ordersByExternalId = [:]
errors = []

Map gate = OmsScriptServiceSupport.gateConfig(ec, omsRestSourceConfigId, companyUserGroupId, "OMS")
if (!gate.usable) { errors = gate.errors; return }
def sourceConfig = gate.sourceConfig

Map result = OmsPairLookupSupport.lookupOrdersByExternalId(sourceConfig, (List) externalIds)
ok = result.ok
ordersByExternalId = result.ordersByExternalId
errors = result.errors
