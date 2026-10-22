import darpan.hotwax.oms.OmsOrderStatusLookupSupport
import darpan.hotwax.oms.OmsScriptServiceSupport

ok = false
statusByOrderId = [:]
missingIds = []
errors = []

Map gate = OmsScriptServiceSupport.gateConfig(ec, omsRestSourceConfigId, companyUserGroupId, "OMS")
if (!gate.usable) { errors = gate.errors; return }

Map result = OmsOrderStatusLookupSupport.lookupOrderStatuses(gate.sourceConfig, (List) orderIds)
ok = result.ok
statusByOrderId = result.statusByOrderId
missingIds = result.missingIds
errors = result.errors
