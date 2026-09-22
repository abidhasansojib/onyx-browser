package com.onyx.browser.web.error

import android.app.Activity
import android.content.Intent
import android.webkit.JavascriptInterface
import com.onyx.browser.MainActivity
import com.onyx.browser.web.OnyxWebView

/**
 * JavaScript interface that bridges error page actions (reload, search, go back,
 * SSL bypass, settings, downloads) to native Android methods.
 */
class OnyxErrorBridge(
    private val webView: OnyxWebView,
    private val activity: Activity?
) {

    @JavascriptInterface
    fun reload() {
        webView.post {
            webView.reload()
        }
    }

    @JavascriptInterface
    fun goBack() {
        webView.post {
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
            (activity as? MainActivity)?.showHomeScreen()
        }
    }

    @JavascriptInterface
    fun search(query: String?) {
        webView.post {
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
    fun openShields() {
        activity?.let {
            it.startActivity(Intent(it, com.onyx.browser.ui.settings.ShieldsActivity::class.java))
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
            webView.pendingSslHandler?.proceed()
            webView.pendingSslHandler = null
        }
    }
}
