package com.onyx.browser.web.interceptor

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import com.onyx.browser.web.OnyxWebView

/**
 * Immutable snapshot of a WebView resource request passing through the interception pipeline.
 */
data class InterceptRequestContext(
    val view: WebView?,
    val onyxWebView: OnyxWebView?,
    val request: WebResourceRequest,
    val requestUrl: String,
    val requestHost: String,
    val pageUrl: String,
    val pageHost: String,
    val isMainFrame: Boolean,
    val resourceType: String,
    val isThirdParty: Boolean
)

/**
 * Chain of responsibility interface for processing [InterceptRequestContext].
 * Returning `null` from [proceed] or [RequestInterceptor.intercept] instructs Android WebView
 * to proceed with its native network stack.
 */
interface InterceptorChain {
    val context: InterceptRequestContext
    fun proceed(context: InterceptRequestContext = this.context): WebResourceResponse?
}

/**
 * Modular request interceptor in Onyx Browser's network interception pipeline.
 */
interface RequestInterceptor {
    fun intercept(chain: InterceptorChain): WebResourceResponse?
}
