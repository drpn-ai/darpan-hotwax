import darpan.facade.common.FacadeSupport
import darpan.facade.common.TenantAccessSupport
import darpan.hotwax.oms.OmsRestSourceSupport

import static darpan.common.ValueSupport.normalize
import static darpan.common.ValueSupport.normalizeBool
import static darpan.common.ValueSupport.normalizeInt

String configIdValue = normalize(omsRestSourceConfigId)
String descriptionValue = normalize(description)
String baseUrlValue = normalize(baseUrl)
String ordersPathValue = normalize(ordersPath) ?: OmsRestSourceSupport.DEFAULT_ORDERS_PATH
String timeZoneValue = normalize(timeZone) ?: "UTC"
String authTypeValue = normalize(authType)?.toUpperCase() ?: "NONE"
String usernameValue = normalize(username)
String passwordValue = normalize(password)
String apiTokenValue = normalize(apiToken)
String headersJsonValue = normalize(headersJson)
Integer connectTimeoutValue = normalizeInt(connectTimeoutSeconds, 30)
Integer readTimeoutValue = normalizeInt(readTimeoutSeconds, 60)
String isActiveValue = normalizeBool(isActive, true) ? "Y" : "N"
String canReadOrdersValue = normalizeBool(canReadOrders, true) ? "Y" : "N"
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
String timeZoneError = TenantAccessSupport.validateTimeZone(timeZoneValue)
if (timeZoneError) ec.message.addError(timeZoneError)
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
                timeZone              : timeZoneValue,
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
