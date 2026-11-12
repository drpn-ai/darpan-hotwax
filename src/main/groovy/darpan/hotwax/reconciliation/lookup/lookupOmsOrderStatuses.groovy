import darpan.hotwax.oms.OmsOrderStatusLookupSupport
import darpan.hotwax.oms.OmsScriptServiceSupport

ok = false
statusByOrderId = [:]
itemStatusByKey = [:]
itemShipGroupByKey = [:]
shipGroupFacilityByKey = [:]
missingIds = []
errors = []

Map gate = OmsScriptServiceSupport.gateConfig(ec, omsRestSourceConfigId, companyUserGroupId, "OMS_GQL")
if (!gate.usable) { errors = gate.errors; return }

// DAR-BE-190: withItems asks each order's items instead, keyed orderId|orderItemSeqId.
// DAR-BE-245: withShipGroupFacilities asks where each order's ship groups ship from, keyed orderId|shipGroupSeqId.
Map result = withShipGroupFacilities ? OmsOrderStatusLookupSupport.lookupShipGroupFacilities(gate.sourceConfig, (List) orderIds) :
        withItems ? OmsOrderStatusLookupSupport.lookupOrderItemStatuses(gate.sourceConfig, (List) orderIds) :
        OmsOrderStatusLookupSupport.lookupOrderStatuses(gate.sourceConfig, (List) orderIds)
ok = result.ok
statusByOrderId = result.statusByOrderId ?: [:]
itemStatusByKey = result.itemStatusByKey ?: [:]
itemShipGroupByKey = result.itemShipGroupByKey ?: [:]
shipGroupFacilityByKey = result.shipGroupFacilityByKey ?: [:]
missingIds = result.missingIds
errors = result.errors
