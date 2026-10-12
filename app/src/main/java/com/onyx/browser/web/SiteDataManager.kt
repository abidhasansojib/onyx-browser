package com.onyx.browser.web

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebViewDatabase
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SiteDataManager manages origin storage, cookies, and cache for specific web domains.
 * Allows purging cookies, local/session storage, and auth tokens for individual sites
 * without disturbing other active tabs or global browser state.
 */
object SiteDataManager {

    private val MULTI_PART_TLD_PARTS = setOf(
        "co", "com", "org", "net", "gov", "edu", "ac", "ne", "or", "go", "mil", "nom"
    )

    fun extractRootDomain(host: String): String {
        val cleanHost = host.trim().lowercase().removePrefix("www.")
        if (cleanHost.isEmpty()) return ""
        if (cleanHost.all { it.isDigit() || it == '.' || it == ':' }) return cleanHost
        val parts = cleanHost.split('.')
        if (parts.size <= 2) return cleanHost

        val secondToLast = parts[parts.size - 2]
        val last = parts.last()
        val isMultiPartTld = last.length == 2 && secondToLast in MULTI_PART_TLD_PARTS

        return if (isMultiPartTld && parts.size >= 3) {
            parts.takeLast(3).joinToString(".")
        } else {
            parts.takeLast(2).joinToString(".")
        }
    }

    fun getPurgeDomains(host: String): Set<String> {
        val result = linkedSetOf<String>()
        val cleanHost = host.trim().lowercase().removePrefix("www.")
        if (cleanHost.isEmpty()) return result

        result.add(cleanHost)
        result.add(host.trim().lowercase())

        val root = extractRootDomain(cleanHost)
        if (root.isNotEmpty()) {
            result.add(root)
            result.add("www.$root")
            result.add("m.$root")
            result.add("login.$root")
            result.add("auth.$root")
            result.add("accounts.$root")
        }
        return result
    }

    /**
     * Clears cookies, WebStorage (localStorage, IndexedDB), and HTTP auth credentials
     * strictly for the specified site/host.
     */
    fun clearSiteData(
        context: Context,
        hostOrUrl: String,
        isIncognito: Boolean = false,
        onFinished: (() -> Unit)? = null
    ) {
        val cleanHost = if (hostOrUrl.contains("://")) {
            try { Uri.parse(hostOrUrl).host.orEmpty() } catch (_: Throwable) { hostOrUrl }
        } else {
            hostOrUrl.substringBefore("/").substringBefore(":")
        }.trim().lowercase().removePrefix("www.")

        if (cleanHost.isBlank()) {
            onFinished?.invoke()
            return
        }

        val allPurgeDomains = getPurgeDomains(cleanHost)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. Delete WebStorage on Main Thread
                withContext(Dispatchers.Main) {
                    val webStorage = if (isIncognito && WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                        try {
                            ProfileStore.getInstance().getProfile(OnyxWebView.INCOGNITO_PROFILE_NAME)?.webStorage ?: WebStorage.getInstance()
                        } catch (_: Throwable) {
                            WebStorage.getInstance()
                        }
                    } else {
                        WebStorage.getInstance()
                    }
                    for (domain in allPurgeDomains) {
                        try { webStorage.deleteOrigin("https://$domain") } catch (_: Throwable) {}
                        try { webStorage.deleteOrigin("http://$domain") } catch (_: Throwable) {}
                    }
                }

                // 2. Purge CookieManager
                val cookieManager = if (isIncognito && WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                    try {
                        ProfileStore.getInstance().getProfile(OnyxWebView.INCOGNITO_PROFILE_NAME)?.cookieManager ?: CookieManager.getInstance()
                    } catch (_: Throwable) {
                        CookieManager.getInstance()
                    }
                } else {
                    CookieManager.getInstance()
                }

                val cookieNames = mutableSetOf<String>()
                for (domain in allPurgeDomains) {
                    for (scheme in listOf("https://", "http://")) {
                        val cookieHeader = cookieManager.getCookie("$scheme$domain/")
                        if (!cookieHeader.isNullOrBlank()) {
                            cookieHeader.split(";").forEach { part ->
                                val name = part.substringBefore("=").trim()
                                if (name.isNotEmpty()) {
                                    cookieNames.add(name)
                                }
                            }
                        }
                    }
                }

                // Add standard auth/session cookie names for thorough invalidation
                cookieNames.addAll(
                    listOf(
                        "session", "sessionid", "session_id", "token", "auth", "jwt",
                        "access_token", "refresh_token", "c_user", "xs", "datr", "user_session",
                        "SID", "HSID", "SSID", "APISID", "SAPISID", "LOGIN_INFO"
                    )
                )

                val expiry = "expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0"
                for (domain in allPurgeDomains) {
                    val httpsUrl = "https://$domain/"
                    for (cookieName in cookieNames) {
                        cookieManager.setCookie(httpsUrl, "$cookieName=; $expiry; path=/; domain=.$domain; Secure")
                        cookieManager.setCookie(httpsUrl, "$cookieName=; $expiry; path=/; Secure")
                    }
                }
                cookieManager.flush()

                // 3. Clear HTTP auth cache
                try {
                    val db = WebViewDatabase.getInstance(context)
                    db.clearHttpAuthUsernamePassword()
                } catch (_: Throwable) {}

            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                if (onFinished != null) {
                    withContext(Dispatchers.Main) {
                        onFinished()
                    }
                }
            }
        }
    }
}
