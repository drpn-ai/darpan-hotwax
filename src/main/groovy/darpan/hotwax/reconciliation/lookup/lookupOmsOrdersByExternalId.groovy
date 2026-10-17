import darpan.hotwax.oms.OmsRestSourceSupport
import darpan.hotwax.oms.OmsScriptServiceSupport

ok = false
ordersByExternalId = [:]
errors = []

Map gate = OmsScriptServiceSupport.gateConfig(ec, omsRestSourceConfigId, companyUserGroupId, "OMS")
if (!gate.usable) { errors = gate.errors; return }
def sourceConfig = gate.sourceConfig

// Default window: 10 years back from tomorrow. Originals can be years older than their exchange
// (live run 2026-07-31: a 400-day default windowed out 18 old originals, turning every one into a
// false EXCHANGE_ORIGINAL_MISSING_IN_OMS row). The externalId filter does the narrowing; the date
// window exists only because the orders API requires the parameters.
long thruMillis = (windowEndMillis instanceof Number) ? ((Number) windowEndMillis).longValue()
        : System.currentTimeMillis() + 86400000L
long fromMillis = (windowStartMillis instanceof Number) ? ((Number) windowStartMillis).longValue()
        : thruMillis - 3650L * 86400000L

Map result = OmsRestSourceSupport.lookupOrdersByExternalId(sourceConfig, (List) externalIds, fromMillis, thruMillis)
ok = result.ok
ordersByExternalId = result.ordersByExternalId
errors = result.errors
