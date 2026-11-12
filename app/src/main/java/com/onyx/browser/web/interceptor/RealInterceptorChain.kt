package com.onyx.browser.web.interceptor

import android.webkit.WebResourceResponse

/**
 * Concrete [InterceptorChain] that dispatches a request sequentially through [interceptors].
 * When the end of the chain is reached without any interceptor returning a non-null
 * [WebResourceResponse], returns `null` so Chromium loads the resource natively.
 */
class RealInterceptorChain(
    private val interceptors: List<RequestInterceptor>,
    private val index: Int = 0,
    override val context: InterceptRequestContext
) : InterceptorChain {

    override fun proceed(context: InterceptRequestContext): WebResourceResponse? {
        if (index >= interceptors.size) {
            return null
        }
        val next = RealInterceptorChain(
            interceptors = interceptors,
            index = index + 1,
            context = context
        )
        return interceptors[index].intercept(next)
    }
}
