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
    private val onGeolocationHidePromptCallback: (() -> Unit)? = null,
    private val onPermissionRequestCallback: ((PermissionRequest?) -> Unit)? = null,
    private val onPermissionRequestCanceledCallback: ((PermissionRequest?) -> Unit)? = null,
    private val onCreateWindowCallback: ((WebView?, Boolean, Boolean, android.os.Message?) -> Boolean)? = null,
    private val onCloseWindowCallback: ((WebView?) -> Unit)? = null
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
        return try {
            onFileChooserCallback?.invoke(filePathCallback, fileChooserParams)
                ?: super.onShowFileChooser(webView, filePathCallback, fileChooserParams)
        } catch (e: Exception) {
            android.util.Log.e("OnyxWebChromeClient", "Error in onShowFileChooser", e)
            try { filePathCallback?.onReceiveValue(null) } catch (_: Exception) {}
            false
        }
    }

    override fun onGeolocationPermissionsShowPrompt(
        origin: String?,
        callback: GeolocationPermissions.Callback?
    ) {
        try {
            if (onGeolocationPromptCallback != null) {
                onGeolocationPromptCallback.invoke(origin, callback)
            } else {
                super.onGeolocationPermissionsShowPrompt(origin, callback)
            }
        } catch (e: Exception) {
            android.util.Log.e("OnyxWebChromeClient", "Error in onGeolocationPermissionsShowPrompt", e)
            try { callback?.invoke(origin, false, false) } catch (_: Exception) {}
        }
    }

    override fun onGeolocationPermissionsHidePrompt() {
        try {
            if (onGeolocationHidePromptCallback != null) {
                onGeolocationHidePromptCallback.invoke()
            } else {
                super.onGeolocationPermissionsHidePrompt()
            }
        } catch (e: Exception) {
            android.util.Log.e("OnyxWebChromeClient", "Error in onGeolocationPermissionsHidePrompt", e)
        }
    }

    override fun onPermissionRequest(request: PermissionRequest?) {
        try {
            if (onPermissionRequestCallback != null) {
                onPermissionRequestCallback.invoke(request)
            } else {
                super.onPermissionRequest(request)
            }
        } catch (e: Exception) {
            android.util.Log.e("OnyxWebChromeClient", "Error in onPermissionRequest", e)
            try { request?.deny() } catch (_: Exception) {}
        }
    }

    override fun onPermissionRequestCanceled(request: PermissionRequest?) {
        try {
            if (onPermissionRequestCanceledCallback != null) {
                onPermissionRequestCanceledCallback.invoke(request)
            } else {
                super.onPermissionRequestCanceled(request)
            }
        } catch (e: Exception) {
            android.util.Log.e("OnyxWebChromeClient", "Error in onPermissionRequestCanceled", e)
        }
    }

    override fun onCreateWindow(
        view: WebView?,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: android.os.Message?
    ): Boolean {
        return onCreateWindowCallback?.invoke(view, isDialog, isUserGesture, resultMsg)
            ?: super.onCreateWindow(view, isDialog, isUserGesture, resultMsg)
    }

    override fun onCloseWindow(window: WebView?) {
        if (onCloseWindowCallback != null) {
            onCloseWindowCallback.invoke(window)
        } else {
            super.onCloseWindow(window)
        }
    }

    override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
        if (consoleMessage != null) {
            val level = consoleMessage.messageLevel()
            val src = consoleMessage.sourceId() ?: "unknown"
            val line = consoleMessage.lineNumber()
            val text = consoleMessage.message() ?: ""
            val formatted = "[WebConsole] $text ($src:$line)"
            val levelName = when (level) {
                android.webkit.ConsoleMessage.MessageLevel.ERROR -> {
                    android.util.Log.e("OnyxDevTools", formatted)
                    "error"
                }
                android.webkit.ConsoleMessage.MessageLevel.WARNING -> {
                    android.util.Log.w("OnyxDevTools", formatted)
                    "warning"
                }
                android.webkit.ConsoleMessage.MessageLevel.DEBUG -> {
                    android.util.Log.d("OnyxDevTools", formatted)
                    "debug"
                }
                else -> {
                    android.util.Log.i("OnyxDevTools", formatted)
                    "info"
                }
            }
            // If DevTools (Eruda) is open in the active WebView, forward messages live into its panel.
            // This bridges Chromium's native console with Eruda's UI panel.
            // Note: consoleMessage doesn't carry a WebView reference, so we rely on the OnyxWebView
            // subclass which always has DevTools forwarded via the patched console methods in
            // DevToolsManager.consoleBufferScript. This fallback catches messages from synchronous
            // scripts or native WebCore sources that bypass the patched console object.
        }
        return super.onConsoleMessage(consoleMessage)
    }
}
