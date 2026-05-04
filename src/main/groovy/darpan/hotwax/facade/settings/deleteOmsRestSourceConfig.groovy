import darpan.facade.common.FacadeSupport
import darpan.facade.common.TenantAccessSupport
import darpan.hotwax.oms.OmsRestSourceSupport

String configId = FacadeSupport.normalize(omsRestSourceConfigId)
String activeTenantUserGroupId = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
deleted = false

if (!configId) {
    ec.message.addError("HotWax OMS REST source config ID is required.")
}

def config = null
if (!ec.message.hasError()) {
    config = ec.entity.find("darpan.hotwax.HotWaxOmsRestSourceConfig")
        .condition("omsRestSourceConfigId", configId)
        .disableAuthz()
        .useCache(false)
        .one()
    try {
        OmsRestSourceSupport.requireWritableTenantConfig(
            config ? [companyUserGroupId: config.companyUserGroupId] : null,
            activeTenantUserGroupId,
            TenantAccessSupport.hasActiveTenantWriteAccess(ec)
        )
        if (config == null) {
            ec.message.addError("HotWax OMS REST source config '${configId}' was not found.")
        }
    } catch (IllegalArgumentException e) {
        ec.message.addError(e.message)
    }
}

if (!ec.message.hasError()) {
    ec.service.sync()
        .name("delete#darpan.hotwax.HotWaxOmsRestSourceConfig")
        .parameters([omsRestSourceConfigId: configId])
        .disableAuthz()
        .call()
    deleted = true
    deletedOmsRestSourceConfigId = configId
    ec.message.addMessage("Deleted HotWax OMS REST source config ${configId}.")
}

Map envelope = FacadeSupport.envelope(ec)
ok = envelope.ok
messages = envelope.messages
errors = envelope.errors
