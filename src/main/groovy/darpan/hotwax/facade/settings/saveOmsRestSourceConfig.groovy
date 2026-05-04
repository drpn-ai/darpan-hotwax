import darpan.facade.common.FacadeSupport
import darpan.facade.common.TenantAccessSupport
import darpan.hotwax.oms.OmsRestSourceSupport

String configIdValue = FacadeSupport.normalize(omsRestSourceConfigId)
String descriptionValue = FacadeSupport.normalize(description)
String baseUrlValue = FacadeSupport.normalize(baseUrl)
String ordersPathValue = FacadeSupport.normalize(ordersPath) ?: OmsRestSourceSupport.DEFAULT_ORDERS_PATH
String authTypeValue = FacadeSupport.normalize(authType)?.toUpperCase() ?: "NONE"
String usernameValue = FacadeSupport.normalize(username)
String passwordValue = FacadeSupport.normalize(password)
String apiTokenValue = FacadeSupport.normalize(apiToken)
String headersJsonValue = FacadeSupport.normalize(headersJson)
Integer connectTimeoutValue = FacadeSupport.normalizeInt(connectTimeoutSeconds, 30)
Integer readTimeoutValue = FacadeSupport.normalizeInt(readTimeoutSeconds, 60)
String isActiveValue = FacadeSupport.normalizeBool(isActive, true) ? "Y" : "N"
String canReadOrdersValue = FacadeSupport.normalizeBool(canReadOrders, true) ? "Y" : "N"
String activeTenantUserGroupId = TenantAccessSupport.currentActiveTenantUserGroupId(ec)

if (!TenantAccessSupport.requireActiveTenantWriteAccess(ec)) {
    Map envelope = FacadeSupport.envelope(ec)
    ok = envelope.ok
    messages = envelope.messages
    errors = envelope.errors
    return
}

if (!configIdValue) ec.message.addError("OMS REST Source Config ID is required.")
if (!baseUrlValue) ec.message.addError("Base URL is required.")
if (!["NONE", "BASIC", "BEARER", "API_KEY"].contains(authTypeValue)) {
    ec.message.addError("Auth Type must be NONE, BASIC, BEARER, or API_KEY.")
}
if (connectTimeoutValue <= 0) connectTimeoutValue = 30
if (readTimeoutValue <= 0) readTimeoutValue = 60

if (headersJsonValue) {
    try {
        OmsRestSourceSupport.safeConfigMap([headersJson: headersJsonValue])
    } catch (IllegalArgumentException e) {
        ec.message.addError(e.message)
    }
}

if (!ec.message.hasError()) {
    def existingConfig = ec.entity.find("darpan.hotwax.HotWaxOmsRestSourceConfig")
            .condition("omsRestSourceConfigId", configIdValue)
            .disableAuthz()
            .useCache(false)
            .one()

    if (authTypeValue == "BASIC") {
        if (!usernameValue) ec.message.addError("Username is required for BASIC auth.")
        if (!passwordValue && !existingConfig?.password) ec.message.addError("Password is required for BASIC auth.")
    }
    if (authTypeValue == "BEARER" && !apiTokenValue && !passwordValue && !existingConfig?.apiToken && !existingConfig?.password) {
        ec.message.addError("API token is required for BEARER auth.")
    }
    if (authTypeValue == "API_KEY" && !apiTokenValue && !passwordValue && !existingConfig?.apiToken && !existingConfig?.password) {
        ec.message.addError("API key is required for API_KEY auth.")
    }

    if (!ec.message.hasError()) {
        try {
            OmsRestSourceSupport.requireWritableTenantConfig(
                    existingConfig ? [companyUserGroupId: existingConfig.companyUserGroupId] : null,
                    activeTenantUserGroupId,
                    TenantAccessSupport.hasActiveTenantWriteAccess(ec)
            )
        } catch (IllegalArgumentException e) {
            ec.message.addError(e.message)
        }
    }

    if (!ec.message.hasError()) {
        Map configMap = [
                omsRestSourceConfigId : configIdValue,
                description           : descriptionValue,
                companyUserGroupId    : activeTenantUserGroupId,
                createdByUserId       : existingConfig?.createdByUserId ?: TenantAccessSupport.currentUserId(ec),
                baseUrl               : baseUrlValue,
                ordersPath            : ordersPathValue,
                authType              : authTypeValue,
                username              : usernameValue,
                headersJson           : headersJsonValue,
                connectTimeoutSeconds : connectTimeoutValue,
                readTimeoutSeconds    : readTimeoutValue,
                isActive              : isActiveValue,
                canReadOrders         : canReadOrdersValue,
                createdDate           : existingConfig?.createdDate ?: ec.user.nowTimestamp,
                lastUpdatedDate       : ec.user.nowTimestamp,
        ]

        if (passwordValue) configMap.password = passwordValue
        else if (existingConfig?.password) configMap.password = existingConfig.password

        if (apiTokenValue) configMap.apiToken = apiTokenValue
        else if (existingConfig?.apiToken) configMap.apiToken = existingConfig.apiToken

        ec.service.sync()
                .name("store#darpan.hotwax.HotWaxOmsRestSourceConfig")
                .parameters(configMap)
                .disableAuthz()
                .call()

        savedOmsRestSourceConfig = OmsRestSourceSupport.safeConfigMap(configMap)
        ec.message.addMessage("Saved OMS REST source config ${configIdValue}.")
    }
}

Map envelope = FacadeSupport.envelope(ec)
ok = envelope.ok
messages = envelope.messages
errors = envelope.errors
