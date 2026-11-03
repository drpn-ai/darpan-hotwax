import darpan.hotwax.oms.OmsOrderStatusLookupSupport
import darpan.hotwax.oms.OmsScriptServiceSupport

ok = false
statusByOrderId = [:]
itemStatusByKey = [:]
missingIds = []
errors = []

Map gate = OmsScriptServiceSupport.gateConfig(ec, omsRestSourceConfigId, companyUserGroupId, "OMS_GQL")
if (!gate.usable) { errors = gate.errors; return }

// DAR-BE-190: withItems asks each order's items instead, keyed orderId|orderItemSeqId.
Map result = withItems ? OmsOrderStatusLookupSupport.lookupOrderItemStatuses(gate.sourceConfig, (List) orderIds) :
        OmsOrderStatusLookupSupport.lookupOrderStatuses(gate.sourceConfig, (List) orderIds)
ok = result.ok
statusByOrderId = result.statusByOrderId ?: [:]
itemStatusByKey = result.itemStatusByKey ?: [:]
missingIds = result.missingIds
errors = result.errors
