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

    @JavascriptInterface
    fun reload() {
        reload(null)
    }

    @JavascriptInterface
    fun reload(targetUrl: String?) {
        webView.post {
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
            webView.clearSyntheticState()
            if (webView.canGoBack()) {
                webView.goBack()
            } else {
                (activity as? MainActivity)?.showHomeScreen()
            }
        }
    }

    @JavascriptInterface
    fun goHome() {
        webView.post {
            webView.clearSyntheticState()
            (activity as? MainActivity)?.showHomeScreen()
        }
    }

    @JavascriptInterface
    fun search(query: String?) {
        webView.post {
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
        activity?.let {
            it.startActivity(Intent(it, com.onyx.browser.ui.settings.SettingsActivity::class.java))
        }
    }

    @JavascriptInterface
    fun openNetworkSettings() {
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
        activity?.let {
            it.startActivity(Intent(it, com.onyx.browser.ui.settings.ShieldsActivity::class.java))
        }
    }

    @JavascriptInterface
    fun allowSiteShield(domain: String?) {
        webView.post {
            val d = if (!domain.isNullOrBlank()) domain else webView.currentSyntheticState?.domain ?: ""
            if (d.isNotBlank()) {
                (activity as? MainActivity)?.let { mainAct ->
                    val prefs = com.onyx.browser.data.preferences.BrowserPreferences.getInstance(mainAct)
                    prefs.addWhitelistedDomain(d)
                    val target = webView.currentSyntheticState?.failingUrl ?: "https://$d"
                    webView.clearSyntheticState()
                    webView.loadUrl(target)
                }
            }
        }
    }

    @JavascriptInterface
    fun openDownloads() {
        activity?.let {
            it.startActivity(Intent(it, com.onyx.browser.ui.downloads.DownloadsActivity::class.java))
        }
    }

    @JavascriptInterface
    fun proceedSsl() {
        webView.post {
            val currentUrl = webView.currentSyntheticState?.failingUrl ?: webView.lastFailingUrl ?: webView.url ?: ""
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
