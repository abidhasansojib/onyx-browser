package com.onyx.browser.web

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.onyx.browser.data.local.AppDatabase
import com.onyx.browser.data.model.HistoryItem
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.nativebridge.AdBlockEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream

class OnyxWebViewClient(
    private val context: Context,
    private val coroutineScope: CoroutineScope,
    private val onUrlChanged: (String) -> Unit,
    private val onPageFinishedCallback: (String) -> Unit
) : WebViewClient() {

    private val preferences = BrowserPreferences.getInstance(context)
    private val database = AppDatabase.getInstance(context)

    @Volatile
    private var currentPageUrl: String = ""

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        if (request == null) return null

        return try {
            val url = request.url?.toString() ?: return null

            // Never block the main frame document itself; track the current page URL
            if (request.isForMainFrame) {
                currentPageUrl = url
                return null
            }

            // Only process http and https network requests for adblocking
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                return null
            }

            if (preferences.isAdBlockEnabled) {
                val referer = request.requestHeaders?.let { headers ->
                    headers["Referer"] ?: headers["referer"] ?: headers["Origin"] ?: headers["origin"]
                }
                val pageUrl = referer ?: currentPageUrl

                if (!preferences.isDomainWhitelisted(pageUrl)) {
                    val resourceType = detectResourceType(request)
                    val blocked = AdBlockEngine.shouldBlock(url, pageUrl, resourceType)
                    if (blocked) {
                        preferences.incrementBlockedRequests()
                        return WebResourceResponse(
                            "text/plain",
                            "UTF-8",
                            ByteArrayInputStream(ByteArray(0))
                        )
                    }
                }
            }

            null
        } catch (t: Throwable) {
            // Fail open: never allow adblock or inspection error to fail request or crash app
            null
        }
    }

    @Deprecated("Deprecated in Java")
    override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse? {
        if (url == null) return null
        return try {
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                return null
            }
            if (preferences.isAdBlockEnabled && !preferences.isDomainWhitelisted(currentPageUrl)) {
                val blocked = AdBlockEngine.shouldBlock(url, currentPageUrl, "other")
                if (blocked) {
                    preferences.incrementBlockedRequests()
                    return WebResourceResponse(
                        "text/plain",
                        "UTF-8",
                        ByteArrayInputStream(ByteArray(0))
                    )
                }
            }
            null
        } catch (t: Throwable) {
            null
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        if (request == null) return false
        val uri = request.url ?: return false
        val url = uri.toString()
        val scheme = uri.scheme?.lowercase() ?: ""

        // Standard web schemes — let WebView handle them normally
        if (scheme == "http" || scheme == "https" || scheme == "about" ||
            scheme == "data" || scheme == "blob" || scheme == "javascript" ||
            scheme == "file" || scheme == "content") {
            return false
        }

        // Custom app URL schemes (fb://, instagram://, twitter://, market://, etc.)
        // and intent:// scheme — dispatch via the OS so the native app opens.
        return try {
            if (scheme == "intent") {
                // Full intent:// parsing (preserves extras, package, fallback URL)
                val intent = android.content.Intent.parseUri(
                    url, android.content.Intent.URI_INTENT_SCHEME
                ).apply {
                    addCategory(android.content.Intent.CATEGORY_BROWSABLE)
                    component = null
                    selector = null
                }
                if (context.packageManager.resolveActivity(intent, 0) != null) {
                    context.startActivity(intent)
                    true
                } else {
                    val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                    if (!fallbackUrl.isNullOrBlank() &&
                        (fallbackUrl.startsWith("http://") || fallbackUrl.startsWith("https://"))) {
                        view?.loadUrl(fallbackUrl)
                    }
                    // Either loaded fallback or silently consumed — never show error page
                    true
                }
            } else {
                // For all other custom schemes (fb://, instagram://, tel://, mailto://, etc.)
                // try a plain ACTION_VIEW intent first.
                val intent = android.content.Intent(
                    android.content.Intent.ACTION_VIEW, uri
                ).apply {
                    addCategory(android.content.Intent.CATEGORY_BROWSABLE)
                }
                if (context.packageManager.resolveActivity(intent, 0) != null) {
                    context.startActivity(intent)
                } else {
                    // No app installed for this scheme — silently suppress the error page.
                    // (e.g. fb:// links when Facebook app is not installed)
                }
                true // Always consume: never show ERR_UNKNOWN_URL_SCHEME
            }
        } catch (e: Exception) {
            true // Consume on any error to prevent the error page
        }
    }

    override fun onPageCommitVisible(view: WebView?, url: String?) {
        super.onPageCommitVisible(view, url)
        if (url != null && (url.startsWith("http://") || url.startsWith("https://")) && preferences.isCosmeticFilteringEnabled && !preferences.isDomainWhitelisted(url)) {
            try {
                val cosmeticCss = AdBlockEngine.getCosmeticCss(url)
                if (cosmeticCss.isNotBlank()) {
                    injectCosmeticCss(view, cosmeticCss)
                }
            } catch (t: Throwable) {
                // Fail open
            }
        }
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        if (!url.isNullOrBlank()) {
            currentPageUrl = url
            // Apply correct UA (desktop or mobile) for this domain before the page loads.
            // This is what makes desktop mode per-domain: each navigation checks the domain
            // against the tab's desktopDomains set and swaps UA accordingly.
            (view as? OnyxWebView)?.applyUserAgentForUrl(url)
            onUrlChanged(url)
        }
    }

    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)
        if (!url.isNullOrBlank()) {
            currentPageUrl = url
        }
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        if (!url.isNullOrBlank()) {
            currentPageUrl = url
            onPageFinishedCallback(url)
            val isIncognito = (view as? OnyxWebView)?.isIncognito ?: false
            if (!isIncognito && url.startsWith("http")) {
                val title = view?.title ?: url
                coroutineScope.launch(Dispatchers.IO) {
                    try {
                        database.historyDao().insertHistory(
                            HistoryItem(
                                url = url,
                                title = title,
                                visitTime = System.currentTimeMillis()
                            )
                        )
                    } catch (e: Exception) {
                        // Ignore persistence error
                    }
                }
            }
        }
    }

    private fun detectResourceType(request: WebResourceRequest): String {
        if (request.isForMainFrame) return "main_frame"

        val acceptHeader = request.requestHeaders?.get("Accept")?.lowercase() ?: ""
        val urlPath = request.url?.path?.lowercase() ?: ""

        return when {
            acceptHeader.contains("text/css") || urlPath.endsWith(".css") -> "stylesheet"
            acceptHeader.contains("javascript") || urlPath.endsWith(".js") -> "script"
            acceptHeader.contains("image/") || urlPath.endsWith(".png") ||
                    urlPath.endsWith(".jpg") || urlPath.endsWith(".jpeg") ||
                    urlPath.endsWith(".webp") || urlPath.endsWith(".gif") ||
                    urlPath.endsWith(".svg") || urlPath.endsWith(".ico") -> "image"
            acceptHeader.contains("font/") || urlPath.endsWith(".woff") ||
                    urlPath.endsWith(".woff2") || urlPath.endsWith(".ttf") ||
                    urlPath.endsWith(".otf") -> "font"
            acceptHeader.contains("text/html") -> "subdocument"
            else -> "other"
        }
    }

    private fun injectCosmeticCss(view: WebView?, css: String) {
        val encodedCss = Base64.encodeToString(css.toByteArray(), Base64.NO_WRAP)
        val js = """
            (function() {
                try {
                    var style = document.getElementById('onyx-adblock-cosmetic');
                    if (!style) {
                        style = document.createElement('style');
                        style.id = 'onyx-adblock-cosmetic';
                        style.type = 'text/css';
                        (document.head || document.documentElement).appendChild(style);
                    }
                    style.textContent = window.atob('$encodedCss');
                } catch (e) {}
            })();
        """.trimIndent()

        view?.post {
            try {
                view.evaluateJavascript(js, null)
            } catch (t: Throwable) {
                // Ignore if view was destroyed
            }
        }
    }
}
