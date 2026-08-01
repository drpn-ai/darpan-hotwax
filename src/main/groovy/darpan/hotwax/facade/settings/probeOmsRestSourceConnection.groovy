import darpan.hotwax.oms.OmsRestSourceSupport

/**
 * Connection diagnostics for a saved OMS REST source config.
 *
 * Invoked only through facade.SettingsFacadeServices.test#SourceConnection, which already resolved
 * the config against the active tenant and enforced write access. Deliberately adds NOTHING to
 * ec.message: a rejected credential is a diagnostic result, and an error here would make the SPA
 * client throw instead of rendering the failure rows.
 *
 * password and apiToken are encrypt="true", so reading them is where a bad entity_ds_crypt_pass
 * throws — before any network call. Those reads are isolated below and the failure is handed to the
 * probe as credentialError, so it reports as a credential fault rather than a connectivity one.
 * safeConfigMap is deliberately NOT used here: it redacts secrets to hasApiToken booleans, which is
 * right for display and useless for an actual connection attempt.
 */

checks = []
nextStage = null

String configIdValue = omsRestSourceConfigId?.toString()?.trim()
if (!configIdValue) {
    checks = [OmsRestSourceSupport.diagnosticsCheck("credential", "Configuration found", "FAIL",
            "OMS REST Source Config ID is required.")]
    return
}

def sourceConfig = ec.entity.find("darpan.hotwax.HotWaxOmsRestSourceConfig")
        .condition("omsRestSourceConfigId", configIdValue)
        .disableAuthz()
        .useCache(false)
        .one()

if (!sourceConfig) {
    checks = [OmsRestSourceSupport.diagnosticsCheck("credential", "Configuration found", "FAIL",
            "The configuration could not be loaded.")]
    return
}

if ((sourceConfig.isActive ?: "Y").toString().equalsIgnoreCase("N")) {
    checks = [OmsRestSourceSupport.diagnosticsCheck("credential", "Configuration active", "FAIL",
            "This configuration is marked inactive.")]
    return
}

Map probeConfig = [
        baseUrl              : sourceConfig.baseUrl,
        ordersPath           : sourceConfig.ordersPath,
        authType             : sourceConfig.authType,
        headersJson          : sourceConfig.headersJson,
        connectTimeoutSeconds: sourceConfig.connectTimeoutSeconds,
        readTimeoutSeconds   : sourceConfig.readTimeoutSeconds,
]
try {
    probeConfig.username = sourceConfig.username
    probeConfig.password = sourceConfig.password
    probeConfig.apiToken = sourceConfig.apiToken
} catch (Throwable t) {
    probeConfig.credentialError = "The stored credentials could not be decrypted."
}

String requestedStage = stage?.toString()?.trim()
Map probeResult = requestedStage
        ? OmsRestSourceSupport.probeConnectionStage(probeConfig, requestedStage)
        : OmsRestSourceSupport.probeConnection(probeConfig)
checks = probeResult.checks
nextStage = probeResult.nextStage
