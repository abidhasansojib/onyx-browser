package com.onyx.browser.web

import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView

class OnyxWebChromeClient(
    private val onProgressChangedCallback: (Int) -> Unit,
    private val onTitleReceivedCallback: (String) -> Unit,
    private val onIconReceivedCallback: ((Bitmap?) -> Unit)? = null,
    private val onShowCustomViewCallback: ((View, CustomViewCallback) -> Unit)? = null,
    private val onHideCustomViewCallback: (() -> Unit)? = null,
    private val onFileChooserCallback: ((ValueCallback<Array<Uri>>?, FileChooserParams?) -> Boolean)? = null,
    private val onGeolocationPromptCallback: ((String?, GeolocationPermissions.Callback?) -> Unit)? = null,
    private val onPermissionRequestCallback: ((PermissionRequest?) -> Unit)? = null
) : WebChromeClient() {

    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        super.onProgressChanged(view, newProgress)
        onProgressChangedCallback(newProgress)
    }

    override fun onReceivedTitle(view: WebView?, title: String?) {
        super.onReceivedTitle(view, title)
        title?.let { onTitleReceivedCallback(it) }
    }

    override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
        super.onReceivedIcon(view, icon)
        onIconReceivedCallback?.invoke(icon)
    }

    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        super.onShowCustomView(view, callback)
        if (view != null && callback != null) {
            onShowCustomViewCallback?.invoke(view, callback)
        }
    }

    override fun onHideCustomView() {
        super.onHideCustomView()
        onHideCustomViewCallback?.invoke()
    }

    override fun onShowFileChooser(
        webView: WebView?,
        filePathCallback: ValueCallback<Array<Uri>>?,
        fileChooserParams: FileChooserParams?
    ): Boolean {
        return onFileChooserCallback?.invoke(filePathCallback, fileChooserParams)
            ?: super.onShowFileChooser(webView, filePathCallback, fileChooserParams)
    }

    override fun onGeolocationPermissionsShowPrompt(
        origin: String?,
        callback: GeolocationPermissions.Callback?
    ) {
        if (onGeolocationPromptCallback != null) {
            onGeolocationPromptCallback.invoke(origin, callback)
        } else {
            super.onGeolocationPermissionsShowPrompt(origin, callback)
        }
    }

    override fun onPermissionRequest(request: PermissionRequest?) {
        if (onPermissionRequestCallback != null) {
            onPermissionRequestCallback.invoke(request)
        } else {
            super.onPermissionRequest(request)
        }
    }
}
