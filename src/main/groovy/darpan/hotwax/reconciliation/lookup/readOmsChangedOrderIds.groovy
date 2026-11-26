import darpan.hotwax.oms.OmsOrderStatusLookupSupport
import darpan.hotwax.oms.OmsScriptServiceSupport

// DAR-BE-320. The HotWax orders one of statusIds was written for since changedSince (OmsOrderStatusLookupSupport.changedOrderIds).
ok = false
orderIds = []
calls = 0
errors = []

Map gate = OmsScriptServiceSupport.gateConfig(ec, omsRestSourceConfigId, companyUserGroupId, "OMS_GQL")
if (!gate.usable) { errors = gate.errors; return }

long thru = changedThru != null ? ((java.util.Date) changedThru).time : System.currentTimeMillis()
Map result = OmsOrderStatusLookupSupport.changedOrderIds(gate.sourceConfig, ((java.util.Date) changedSince).time, thru, (List) statusIds)
ok = result.ok
orderIds = result.orderIds ?: []
calls = result.calls ?: 0
errors = result.errors ?: []
