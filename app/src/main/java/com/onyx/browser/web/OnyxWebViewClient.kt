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

        if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("about:") || url.startsWith("data:")) {
            return false
        }

        return try {
            val intent = android.content.Intent.parseUri(url, android.content.Intent.URI_INTENT_SCHEME).apply {
                addCategory(android.content.Intent.CATEGORY_BROWSABLE)
                component = null
                selector = null
            }

            if (context.packageManager.resolveActivity(intent, 0) != null) {
                context.startActivity(intent)
                true
            } else {
                val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                if (!fallbackUrl.isNullOrBlank() && (fallbackUrl.startsWith("http://") || fallbackUrl.startsWith("https://"))) {
                    view?.loadUrl(fallbackUrl)
                    true
                } else {
                    false
                }
            }
        } catch (e: Exception) {
            false
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
