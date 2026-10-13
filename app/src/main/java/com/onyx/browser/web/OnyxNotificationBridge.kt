package com.onyx.browser.web

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.onyx.browser.data.preferences.BrowserPreferences

/**
 * Bridges the standard W3C Web Notifications API (window.Notification)
 * to Onyx Browser's per-site permissions system and Android notifications.
 */
class OnyxNotificationBridge(
    private val activity: Activity,
    private val webView: WebView,
    private val onRequestPermission: (host: String, callback: (String) -> Unit) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs by lazy { BrowserPreferences.getInstance(activity) }

    @JavascriptInterface
    fun getPermission(hostOrUrl: String): String {
        val domain = prefs.cleanDomain(hostOrUrl)
        if (domain.isBlank()) return "denied"
        val perm = prefs.isWebNotificationsAllowed(domain)
        return when (perm) {
            true -> "granted"
            false -> "denied"
            null -> if (prefs.isNotificationsEnabledGlobal) "default" else "denied"
        }
    }

    @JavascriptInterface
    fun requestPermission(hostOrUrl: String, callbackId: String) {
        val domain = prefs.cleanDomain(hostOrUrl)
        mainHandler.post {
            onRequestPermission(domain) { result ->
                val js = "window.__onyxResolveNotificationPermission && window.__onyxResolveNotificationPermission('$callbackId', '$result');"
                webView.evaluateJavascript(js, null)
            }
        }
    }

    companion object {
        const val BRIDGE_NAME = "OnyxNotificationBridge"

        val POLYFILL_SCRIPT = """
            (function() {
                try {
                    if (window.__onyxNotificationInstalled) return;
                    window.__onyxNotificationInstalled = true;

                    var bridge = window.OnyxNotificationBridge;
                    if (!bridge) return;

                    var callbacks = {};
                    var callbackCounter = 0;

                    window.__onyxResolveNotificationPermission = function(id, result) {
                        var cb = callbacks[id];
                        if (cb) {
                            delete callbacks[id];
                            try { cb(result); } catch (_) {}
                        }
                    };

                    function getEffectivePermission() {
                        try {
                            return bridge.getPermission(location.hostname || location.origin || '');
                        } catch (_) {
                            return 'default';
                        }
                    }

                    function OnyxNotification(title, options) {
                        // Creates notification
                        this.title = title || '';
                        this.options = options || {};
                    }

                    Object.defineProperty(OnyxNotification, 'permission', {
                        get: function() { return getEffectivePermission(); },
                        enumerable: true,
                        configurable: true
                    });

                    OnyxNotification.requestPermission = function(callback) {
                        return new Promise(function(resolve) {
                            var id = 'notif_cb_' + (++callbackCounter);
                            callbacks[id] = function(res) {
                                if (typeof callback === 'function') {
                                    try { callback(res); } catch (_) {}
                                }
                                resolve(res);
                            };
                            try {
                                bridge.requestPermission(location.hostname || location.origin || '', id);
                            } catch (_) {
                                resolve('denied');
                            }
                        });
                    };

                    try {
                        window.Notification = OnyxNotification;
                    } catch (_) {}
                } catch (_) {}
            })();
        """.trimIndent()
    }
}
