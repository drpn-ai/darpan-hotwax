import darpan.facade.common.FacadeSupport
import darpan.facade.common.SecretCarryOver
import darpan.facade.common.SharedConfigAccessSupport
import darpan.facade.common.TenantAccessSupport
import darpan.hotwax.oms.OmsExtractSupport

import static darpan.common.ValueSupport.normalize
import static darpan.common.ValueSupport.normalizeBool
import static darpan.common.ValueSupport.normalizeInt

String configIdValue = normalize(omsRestSourceConfigId)
String descriptionValue = normalize(description)
String baseUrlValue = normalize(baseUrl)
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

if (!baseUrlValue) {
    ec.message.addError("Base URL is required.")
} else {
    // SSRF defense (audit H5.1 / H6.4): a tenant could otherwise set baseUrl to AWS IMDS / 127.x /
    // RFC1918 hosts and have Darpan fetch internal endpoints, returning the body as a
    // downloadable reconciliation artifact. No HotWax host allow-list is enforced because
    // customers run OMS on arbitrary self-hosted domains; OutboundHttpPolicy still requires
    // https, blocks loopback, link-local, private, multicast, and cloud-metadata literals.
    def __omsUrlCheck = darpan.facade.common.OutboundHttpPolicy.validate(baseUrlValue)
    if (!__omsUrlCheck.ok) ec.message.addError("OMS Base URL is not allowed: " + __omsUrlCheck.error)
}
String timeZoneError = TenantAccessSupport.validateTimeZone(timeZoneValue)
if (timeZoneError) ec.message.addError(timeZoneError)
if (!["NONE", "BASIC", "BEARER", "API_KEY"].contains(authTypeValue)) {
    ec.message.addError("Auth Type must be NONE, BASIC, BEARER, or API_KEY.")
}
if (connectTimeoutValue <= 0) connectTimeoutValue = 30
if (readTimeoutValue <= 0) readTimeoutValue = 60

if (headersJsonValue) {
    try {
        OmsExtractSupport.validateHeadersJson(headersJsonValue)
    } catch (IllegalArgumentException e) {
        ec.message.addError(e.message)
    }
}

if (!ec.message.hasError()) {
    // DAR-BE-135: a named id only updates a config this tenant owns or was granted; a missing row and
    // another tenant's row both answer "not found", and that answer comes before any check that reads
    // the row's secrets. A new config gets a minted id, so no tenant can squat or probe one.
    def existingConfig = configIdValue ? ec.entity.find("darpan.hotwax.HotWaxOmsRestSourceConfig")
            .condition("omsRestSourceConfigId", configIdValue)
            .disableAuthz()
            .useCache(false)
            .one() : null

    // DAR-BE-005: a member tenant may edit every field of a config shared with it (decision 3).
    // Computed from the ConfigTenantAccess grant table for the row actually on disk, before the
    // gate below, so this can never be forged by a caller-supplied parameter.
    boolean isSharedPeer = existingConfig != null && SharedConfigAccessSupport.isSharedWithTenant(
            ec, SharedConfigAccessSupport.CONFIG_TYPE_HOTWAX_OMS, configIdValue, activeTenantUserGroupId)

    try {
        OmsExtractSupport.requireWritableTenantConfig(
                existingConfig ? [companyUserGroupId: existingConfig.companyUserGroupId] : null,
                activeTenantUserGroupId,
                TenantAccessSupport.hasActiveTenantWriteAccess(ec),
                isSharedPeer
        )
        if (configIdValue && existingConfig == null) ec.message.addError("Requested OMS source config was not found.")
    } catch (IllegalArgumentException e) {
        ec.message.addError(e.message)
    }

    // DAR-BE-080: a stored password or token is reused only while it goes to the same place. When the base URL, auth type
    // or headers moved, NO stored secret is carried over (not even the one of the other kind than the caller sent): the
    // auth type's own checks below then need every secret it uses in this request. A peer of a shared config can edit
    // every field, so otherwise they could point it at their server and receive the stored secret.
    List<String> movedFields = (existingConfig && (existingConfig.password || existingConfig.apiToken)) ?
            SecretCarryOver.changedFields(existingConfig, [baseUrl: baseUrlValue, authType: authTypeValue, headersJson: headersJsonValue],
                    ["baseUrl", "authType", "headersJson"]) : []
    String storedPassword = movedFields ? null : existingConfig?.password
    String storedApiToken = movedFields ? null : existingConfig?.apiToken
    // DAR-BE-212: the edit form never shows the username back (the record carries only hasUsername) and says "leave
    // blank to keep existing", so a blank one keeps the stored one — under the same move rule as the secrets above.
    String storedUsername = movedFields ? null : existingConfig?.username

    if (!ec.message.hasError() && authTypeValue == "BASIC") {
        if (!usernameValue && !storedUsername) ec.message.addError("Username is required for BASIC auth.")
        if (!passwordValue && !storedPassword) ec.message.addError(movedFields ? SecretCarryOver.refusal(movedFields, "password or token") : "Password is required for BASIC auth.")
    }
    if (!ec.message.hasError() && authTypeValue == "BEARER" && !apiTokenValue && !passwordValue && !storedApiToken && !storedPassword) {
        ec.message.addError(movedFields ? SecretCarryOver.refusal(movedFields, "password or token") : "API token is required for BEARER auth.")
    }
    if (!ec.message.hasError() && authTypeValue == "API_KEY" && !apiTokenValue && !passwordValue && !storedApiToken && !storedPassword) {
        ec.message.addError(movedFields ? SecretCarryOver.refusal(movedFields, "password or token") : "API key is required for API_KEY auth.")
    }

    if (!ec.message.hasError()) {
        if (!configIdValue) configIdValue = darpan.facade.common.TenantScopedFinder.mintId(ec, "darpan.hotwax.HotWaxOmsRestSourceConfig", "omsRestSourceConfigId")
        // Ownership never transfers: an existing row keeps its original owner even when a peer
        // saves it — only a brand-new row is assigned to the active tenant. Reassigning here would
        // hand ownership to whichever peer saved last and anchor the peer group on the wrong tenant.
        String ownerTenantUserGroupId = existingConfig?.companyUserGroupId ?: activeTenantUserGroupId
        Map configMap = [
                omsRestSourceConfigId : configIdValue,
                description           : descriptionValue,
                companyUserGroupId    : ownerTenantUserGroupId,
                createdByUserId       : existingConfig?.createdByUserId ?: TenantAccessSupport.currentUserId(ec),
                baseUrl               : baseUrlValue,
                timeZone              : timeZoneValue,
                authType              : authTypeValue,
                username              : usernameValue ?: storedUsername,
                headersJson           : headersJsonValue,
                connectTimeoutSeconds : connectTimeoutValue,
                readTimeoutSeconds    : readTimeoutValue,
                isActive              : isActiveValue,
                canReadOrders         : canReadOrdersValue,
                createdDate           : existingConfig?.createdDate ?: ec.user.nowTimestamp,
                lastUpdatedDate       : ec.user.nowTimestamp,
        ]

        if (passwordValue) configMap.password = passwordValue
        else if (storedPassword) configMap.password = storedPassword

        if (apiTokenValue) configMap.apiToken = apiTokenValue
        else if (storedApiToken) configMap.apiToken = storedApiToken

        ec.service.sync()
                .name("store#darpan.hotwax.HotWaxOmsRestSourceConfig")
                .parameters(configMap)
                .disableAuthz()
                .call()

        savedOmsRestSourceConfig = OmsExtractSupport.safeConfigMap(configMap) +
                [isShared: ownerTenantUserGroupId != activeTenantUserGroupId]
        ec.message.addMessage("Saved OMS REST source config ${configIdValue}.")
    }
}

Map envelope = FacadeSupport.envelope(ec)
ok = envelope.ok
messages = envelope.messages
errors = envelope.errors
