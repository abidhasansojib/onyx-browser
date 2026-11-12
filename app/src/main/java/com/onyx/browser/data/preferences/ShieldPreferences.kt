package com.onyx.browser.data.preferences

import kotlinx.coroutines.flow.StateFlow

/**
 * Domain-scoped preference store for Onyx Shields, ad/tracker blocking,
 * HTTPS upgrade policy, cookie isolation, DNS, and exit data shredding.
 */
class ShieldPreferences(private val browserPrefs: BrowserPreferences) {

    var isAdBlockEnabled: Boolean
        get() = browserPrefs.isAdBlockEnabled
        set(value) {
            browserPrefs.isAdBlockEnabled = value
        }

    var blockingLevel: Int
        get() = browserPrefs.blockingLevel
        set(value) {
            browserPrefs.blockingLevel = value
        }

    var isHttpsUpgradeEnabled: Boolean
        get() = browserPrefs.isHttpsUpgradeEnabled
        set(value) {
            browserPrefs.isHttpsUpgradeEnabled = value
        }

    var httpsMode: Int
        get() = browserPrefs.httpsMode
        set(value) {
            browserPrefs.httpsMode = value
        }

    var httpsUpgradeMode: Int
        get() = browserPrefs.httpsUpgradeMode
        set(value) {
            browserPrefs.httpsUpgradeMode = value
        }

    var cookieMode: Int
        get() = browserPrefs.cookieMode
        set(value) {
            browserPrefs.cookieMode = value
        }

    var cookieBlockingMode: Int
        get() = browserPrefs.cookieBlockingMode
        set(value) {
            browserPrefs.cookieBlockingMode = value
        }

    var isSecureDnsEnabled: Boolean
        get() = browserPrefs.isSecureDnsEnabled
        set(value) {
            browserPrefs.isSecureDnsEnabled = value
        }

    var dnsProvider: String
        get() = browserPrefs.dnsProvider
        set(value) {
            browserPrefs.dnsProvider = value
        }

    var secureDnsProvider: String
        get() = browserPrefs.secureDnsProvider
        set(value) {
            browserPrefs.secureDnsProvider = value
        }

    var customDnsUrl: String
        get() = browserPrefs.customDnsUrl
        set(value) {
            browserPrefs.customDnsUrl = value
        }

    var isWebRtcProtectedGlobal: Boolean
        get() = browserPrefs.isWebRtcProtectedGlobal
        set(value) {
            browserPrefs.isWebRtcProtectedGlobal = value
        }

    var isBlockScriptsGlobal: Boolean
        get() = browserPrefs.isBlockScriptsGlobal
        set(value) {
            browserPrefs.isBlockScriptsGlobal = value
        }

    var isGlobalScriptBlockingEnabled: Boolean
        get() = browserPrefs.isGlobalScriptBlockingEnabled
        set(value) {
            browserPrefs.isGlobalScriptBlockingEnabled = value
        }

    var isClearOnExitEnabled: Boolean
        get() = browserPrefs.isClearOnExitEnabled
        set(value) {
            browserPrefs.isClearOnExitEnabled = value
        }

    var isClearOnExitTabsEnabled: Boolean
        get() = browserPrefs.isClearOnExitTabsEnabled
        set(value) {
            browserPrefs.isClearOnExitTabsEnabled = value
        }

    var isClearOnExitHistoryEnabled: Boolean
        get() = browserPrefs.isClearOnExitHistoryEnabled
        set(value) {
            browserPrefs.isClearOnExitHistoryEnabled = value
        }

    var isClearOnExitCookiesEnabled: Boolean
        get() = browserPrefs.isClearOnExitCookiesEnabled
        set(value) {
            browserPrefs.isClearOnExitCookiesEnabled = value
        }

    var isClearOnExitCacheEnabled: Boolean
        get() = browserPrefs.isClearOnExitCacheEnabled
        set(value) {
            browserPrefs.isClearOnExitCacheEnabled = value
        }

    var isElementBlockerEnabled: Boolean
        get() = browserPrefs.isElementBlockerEnabled
        set(value) {
            browserPrefs.isElementBlockerEnabled = value
        }

    var isBlockElementEnabled: Boolean
        get() = browserPrefs.isBlockElementEnabled
        set(value) {
            browserPrefs.isBlockElementEnabled = value
        }

    var isCosmeticFilteringEnabled: Boolean
        get() = browserPrefs.isCosmeticFilteringEnabled
        set(value) {
            browserPrefs.isCosmeticFilteringEnabled = value
        }

    var isFingerprintProtectionEnabled: Boolean
        get() = browserPrefs.isFingerprintProtectionEnabled
        set(value) {
            browserPrefs.isFingerprintProtectionEnabled = value
        }

    var isDoNotTrackEnabled: Boolean
        get() = browserPrefs.isDoNotTrackEnabled
        set(value) {
            browserPrefs.isDoNotTrackEnabled = value
        }

    val blockedRequestsFlow: StateFlow<Long>
        get() = browserPrefs.blockedRequestsFlow

    fun incrementBlockedRequests(): Long = browserPrefs.incrementBlockedRequests()

    fun getBlockedRequestsCount(): Long = browserPrefs.getBlockedRequestsCount()

    fun resetBlockedRequests() {
        browserPrefs.resetBlockedRequests()
    }
}
