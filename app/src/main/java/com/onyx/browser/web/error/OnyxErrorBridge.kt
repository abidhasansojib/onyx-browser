package com.onyx.browser.web.error

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.webkit.JavascriptInterface
import com.onyx.browser.MainActivity
import com.onyx.browser.web.OnyxWebView

/**
 * JavaScript interface that bridges synthetic error page actions (reload, network settings,
 * search, go back, SSL session bypass, Wayback Machine archive lookups, shields bypass)
 * directly to native Android browser methods.
 */
class OnyxErrorBridge(
    private val webView: OnyxWebView,
    private val activity: Activity?
) {

    private fun isSyntheticErrorPageActive(): Boolean {
        if (webView.isLoadingSyntheticPage) return true
        if (webView.currentSyntheticState == null) return false
        val url = webView.url ?: ""
        return OnyxWebView.isSyntheticOrDataUrl(url) ||
                url.startsWith("https://onyx.browser/", ignoreCase = true) ||
                url.startsWith("file:///android_asset/error_page.html", ignoreCase = true)
    }

    @JavascriptInterface
    fun reload() {
        reload(null)
    }

    @JavascriptInterface
    fun reload(targetUrl: String?) {
        webView.post {
            if (!isSyntheticErrorPageActive()) return@post
            val target = when {
                !targetUrl.isNullOrBlank() && !OnyxWebView.isSyntheticOrDataUrl(targetUrl) -> targetUrl
                !webView.currentSyntheticState?.failingUrl.isNullOrBlank() -> webView.currentSyntheticState?.failingUrl
                !webView.lastFailingUrl.isNullOrBlank() -> webView.lastFailingUrl
                else -> null
            }
            webView.clearSyntheticState()
            if (!target.isNullOrBlank()) {
                val mainAct = activity as? MainActivity
                if (mainAct != null) {
                    mainAct.performSearchOrLoad(target)
                } else {
                    webView.loadUrl(target)
                }
            } else {
                webView.reload()
            }
        }
    }

    @JavascriptInterface
    fun goBack() {
        webView.post {
            if (!isSyntheticErrorPageActive()) return@post
            val mainAct = activity as? MainActivity
            if (mainAct != null) {
                mainAct.handleErrorPageBack(webView)
            } else {
                webView.clearSyntheticState()
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    webView.loadUrl("about:blank")
                }
            }
        }
    }

    @JavascriptInterface
    fun goHome() {
        webView.post {
            if (!isSyntheticErrorPageActive()) return@post
            webView.clearSyntheticState()
            webView.stopLoading()
            webView.loadUrl("about:blank")
            val mainAct = activity as? MainActivity
            if (mainAct != null) {
                mainAct.tabManager.updateActiveTab("", "New Tab")
                mainAct.showHomeScreen()
            }
        }
    }

    @JavascriptInterface
    fun search(query: String?) {
        webView.post {
            if (!isSyntheticErrorPageActive()) return@post
            webView.clearSyntheticState()
            val q = query?.trim() ?: ""
            if (q.isNotEmpty()) {
                (activity as? MainActivity)?.let { mainAct ->
                    val prefs = com.onyx.browser.data.preferences.BrowserPreferences.getInstance(mainAct)
                    val url = prefs.searchEngine.buildSearchUrl(q)
                    mainAct.performSearchOrLoad(url)
                }
            }
        }
    }

    @JavascriptInterface
    fun openSettings() {
        if (!isSyntheticErrorPageActive()) return
        activity?.let {
            it.startActivity(Intent(it, com.onyx.browser.ui.settings.SettingsActivity::class.java))
        }
    }

    @JavascriptInterface
    fun openNetworkSettings() {
        if (!isSyntheticErrorPageActive()) return
        activity?.let { act ->
            try {
                act.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            } catch (_: Exception) {
                try {
                    act.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                } catch (_: Exception) {
                    act.startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
        }
    }

    @JavascriptInterface
    fun checkWaybackMachine(url: String?) {
        webView.post {
            if (!isSyntheticErrorPageActive()) return@post
            val target = if (!url.isNullOrBlank()) url else webView.currentSyntheticState?.failingUrl ?: webView.url ?: ""
            if (target.isNotBlank()) {
                webView.clearSyntheticState()
                val waybackUrl = "https://web.archive.org/web/*/$target"
                (activity as? MainActivity)?.performSearchOrLoad(waybackUrl)
            }
        }
    }

    @JavascriptInterface
    fun openShields() {
        if (!isSyntheticErrorPageActive()) return
        activity?.let {
            it.startActivity(Intent(it, com.onyx.browser.ui.settings.ShieldsActivity::class.java))
        }
    }

    @JavascriptInterface
    fun allowSiteShield(domain: String?) {
        webView.post {
            if (!isSyntheticErrorPageActive()) return@post
            val activeState = webView.currentSyntheticState ?: return@post
            val validDomain = activeState.domain
            if (validDomain.isBlank()) return@post
            // Only allow whitelisting the domain that actually triggered the error
            if (!domain.isNullOrBlank() && !domain.equals(validDomain, ignoreCase = true)) {
                return@post
            }
            val d = validDomain
            (activity as? MainActivity)?.let { mainAct ->
                val prefs = com.onyx.browser.data.preferences.BrowserPreferences.getInstance(mainAct)
                prefs.addWhitelistedDomain(d)
                val target = activeState.failingUrl.ifBlank { "https://$d" }
                webView.clearSyntheticState()
                webView.loadUrl(target)
            }
        }
    }

    @JavascriptInterface
    fun openDownloads() {
        if (!isSyntheticErrorPageActive()) return
        activity?.let {
            it.startActivity(Intent(it, com.onyx.browser.ui.downloads.DownloadsActivity::class.java))
        }
    }

    @JavascriptInterface
    fun proceedSsl() {
        webView.post {
            if (!isSyntheticErrorPageActive()) return@post
            val activeState = webView.currentSyntheticState
            if (activeState?.isSslError != true && webView.pendingSslHandler == null) {
                return@post
            }
            val currentUrl = activeState?.failingUrl ?: webView.lastFailingUrl ?: ""
            val host = try { Uri.parse(currentUrl).host } catch (_: Exception) { null }
            if (!host.isNullOrBlank()) {
                webView.sessionSslBypasses.add(host)
            }
            webView.pendingSslHandler?.proceed()
            webView.clearSyntheticState()
            if (currentUrl.isNotBlank() && !OnyxWebView.isSyntheticOrDataUrl(currentUrl)) {
                val mainAct = activity as? MainActivity
                if (mainAct != null) {
                    mainAct.performSearchOrLoad(currentUrl)
                } else {
                    webView.loadUrl(currentUrl)
                }
            } else {
                webView.reload()
            }
        }
    }
}
