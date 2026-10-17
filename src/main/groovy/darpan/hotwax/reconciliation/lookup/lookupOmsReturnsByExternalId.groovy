import darpan.hotwax.oms.OmsReturnsSourceSupport
import darpan.hotwax.oms.OmsScriptServiceSupport

ok = false
foundIds = []
missingIds = []
unresolvedIds = []
errors = []

Map gate = OmsScriptServiceSupport.gateConfig(ec, omsRestSourceConfigId, companyUserGroupId, "OMS")
if (!gate.usable) { errors = gate.errors; return }
def sourceConfig = gate.sourceConfig

Map result = OmsReturnsSourceSupport.lookupReturnsByExternalId(sourceConfig, (List) externalIds)

ok = result.ok
foundIds = result.foundIds
missingIds = result.missingIds
unresolvedIds = result.unresolvedIds ?: []
errors = result.errors
