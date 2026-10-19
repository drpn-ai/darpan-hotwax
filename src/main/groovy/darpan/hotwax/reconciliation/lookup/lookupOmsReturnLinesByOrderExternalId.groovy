import darpan.hotwax.oms.OmsReturnLinesLookupSupport
import darpan.hotwax.oms.OmsScriptServiceSupport

ok = false
returnLinesByExternalId = [:]
errors = []

Map gate = OmsScriptServiceSupport.gateConfig(ec, omsRestSourceConfigId, companyUserGroupId, "OMS")
if (!gate.usable) { errors = gate.errors; return }

Map result = OmsReturnLinesLookupSupport.lookupReturnLinesByOrderExternalId(gate.sourceConfig, (List) externalIds)
ok = result.ok
returnLinesByExternalId = result.returnLinesByExternalId
errors = result.errors
