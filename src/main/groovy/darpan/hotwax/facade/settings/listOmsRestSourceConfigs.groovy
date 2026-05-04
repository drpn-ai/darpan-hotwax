import darpan.facade.common.FacadeSupport
import darpan.facade.common.TenantAccessSupport
import darpan.hotwax.oms.OmsRestSourceSupport

int page = Math.max(0, FacadeSupport.normalizeInt(pageIndex, 0))
int size = Math.max(1, Math.min(200, FacadeSupport.normalizeInt(pageSize, 20)))
String activeTenantUserGroupId = TenantAccessSupport.currentActiveTenantUserGroupId(ec)

List<Map<String, Object>> rows = []
if (!activeTenantUserGroupId) {
    ec.message.addError("An active tenant is required to list OMS REST source configs.")
} else {
    List configs = ec.entity.find("darpan.hotwax.HotWaxOmsRestSourceConfig")
            .condition("companyUserGroupId", activeTenantUserGroupId)
            .disableAuthz()
            .useCache(false)
            .orderBy("description,omsRestSourceConfigId")
            .list() ?: []
    rows = configs.collect { cfg -> OmsRestSourceSupport.safeConfigMap(cfg) }
}

String search = FacadeSupport.normalize(query)?.toLowerCase()
List<Map<String, Object>> filtered = search ? rows.findAll { row ->
    [row.omsRestSourceConfigId, row.description, row.baseUrl, row.ordersPath, row.authType].any {
        it?.toString()?.toLowerCase()?.contains(search)
    }
} : rows

int totalCount = filtered.size()
int fromIndex = Math.min(page * size, totalCount)
int toIndex = Math.min(fromIndex + size, totalCount)
omsRestSourceConfigs = filtered.subList(fromIndex, toIndex)

pagination = [
        pageIndex : page,
        pageSize  : size,
        totalCount: totalCount,
        pageCount : Math.max(1, Math.ceil(totalCount / (double) size) as int)
]

Map envelope = FacadeSupport.envelope(ec)
ok = envelope.ok
messages = envelope.messages
errors = envelope.errors
