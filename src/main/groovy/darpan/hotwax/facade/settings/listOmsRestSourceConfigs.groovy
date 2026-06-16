import darpan.facade.common.FacadeSupport
import darpan.facade.common.PaginationSupport
import darpan.facade.common.TenantAccessSupport
import darpan.hotwax.oms.OmsRestSourceSupport

import static darpan.common.ValueSupport.boundedInt
import static darpan.common.ValueSupport.normalize
import static darpan.common.ValueSupport.normalizeInt

int page = Math.max(0, normalizeInt(pageIndex, 0))
int size = boundedInt(pageSize, 20, 1, 200)
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

String search = normalize(query)?.toLowerCase()
List<Map<String, Object>> filtered = search ? rows.findAll { row ->
    [row.omsRestSourceConfigId, row.description, row.baseUrl, row.ordersPath, row.timeZone, row.authType].any {
        it?.toString()?.toLowerCase()?.contains(search)
    }
} : rows

int totalCount = filtered.size()
omsRestSourceConfigs = PaginationSupport.pageRows(filtered, page, size)
pagination = PaginationSupport.pagination(page, size, totalCount)

Map envelope = FacadeSupport.envelope(ec)
ok = envelope.ok
messages = envelope.messages
errors = envelope.errors
