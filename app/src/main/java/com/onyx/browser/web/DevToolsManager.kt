package com.onyx.browser.web

import android.content.Context
import android.util.Log
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manages Developer Tools (Mobile Eruda Console & Remote WebContents Debugging).
 *
 * Capabilities:
 * 1. Pre-buffering Console & Error messages at document_start so early page errors are captured.
 * 2. In-memory caching of the 489KB eruda.min.js engine to eliminate repeated disk I/O.
 * 3. Dynamic theme synchronization (Light / Dark) matching Onyx Browser themes.
 * 4. Responsive viewport scaling (displaySize) optimized for mobile Android screens.
 * 5. Intelligent Toggle logic (initialization -> show -> hide) with user feedback.
 * 6. Clean lifecycle teardown (destroy) when DevTools is dismissed.
 */
object DevToolsManager {

    private const val TAG = "DevToolsManager"

    @Volatile
    private var cachedErudaCode: String? = null

    /**
     * Injected at document_start into all WebViews to record console calls and uncaught errors
     * prior to Eruda initialization. When Eruda is opened, these are replayed into its console.
     */
    val consoleBufferScript: String = """
        (function() {
            if (window.__onyx_console_buffer) return;
            window.__onyx_console_buffer = [];
            
            var methods = ['log', 'warn', 'error', 'info', 'debug'];
            methods.forEach(function(m) {
                var orig = console[m];
                console[m] = function() {
                    try {
                        if (window.__onyx_console_buffer && window.__onyx_console_buffer.length < 250) {
                            window.__onyx_console_buffer.push({
                                level: m,
                                args: Array.prototype.slice.call(arguments),
                                timestamp: Date.now()
                            });
                        }
                    } catch (_) {}
                    if (orig) return orig.apply(this, arguments);
                };
            });

            window.addEventListener('error', function(e) {
                try {
                    if (window.__onyx_console_buffer && window.__onyx_console_buffer.length < 250) {
                        var msg = (e.message || 'Error') + (e.filename ? ' (' + e.filename + ':' + e.lineno + ':' + e.colno + ')' : '');
                        window.__onyx_console_buffer.push({
                            level: 'error',
                            args: [msg],
                            timestamp: Date.now()
                        });
                    }
                } catch (_) {}
            }, true);

            window.addEventListener('unhandledrejection', function(e) {
                try {
                    if (window.__onyx_console_buffer && window.__onyx_console_buffer.length < 250) {
                        var reason = (e.reason && e.reason.stack) ? e.reason.stack : (e.reason || 'Unhandled Promise Rejection');
                        window.__onyx_console_buffer.push({
                            level: 'error',
                            args: ['[Unhandled Rejection]: ' + reason],
                            timestamp: Date.now()
                        });
                    }
                } catch (_) {}
            }, true);
        })();
    """.trimIndent()

    suspend fun getErudaCode(context: Context): String = withContext(Dispatchers.IO) {
        cachedErudaCode ?: synchronized(this) {
            cachedErudaCode ?: try {
                context.assets.open("eruda.min.js").bufferedReader().use { it.readText() }.also {
                    cachedErudaCode = it
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load eruda.min.js from assets", e)
                ""
            }
        }
    }

    /**
     * Toggles the developer tools overlay.
     * Returns:
     * - "shown": Overlay was shown/expanded
     * - "hidden": Overlay was minimized
     * - "failed": Failed to load or execute
     */
    suspend fun toggleDevTools(
        context: Context,
        webView: WebView,
        isDark: Boolean,
        onResult: (status: String) -> Unit
    ) {
        val erudaCode = getErudaCode(context)
        if (erudaCode.isBlank()) {
            onResult("failed")
            return
        }

        val themeName = if (isDark) "Dark" else "Light"
        val script = """
            (function() {
                try {
                    if (window.eruda && window.eruda._isInit) {
                        if (window.__onyx_eruda_visible) {
                            window.eruda.hide();
                            window.__onyx_eruda_visible = false;
                            return 'hidden';
                        } else {
                            window.eruda.show();
                            window.__onyx_eruda_visible = true;
                            return 'shown';
                        }
                    }

                    // Inject Eruda source
                    $erudaCode

                    if (window.eruda) {
                        window.eruda.init({
                            tool: ['console', 'elements', 'network', 'resource', 'info', 'snippets', 'sources', 'storage'],
                            defaults: {
                                displaySize: 55,
                                transparency: 0.95,
                                theme: '$themeName'
                            }
                        });

                        // Replay pre-buffered console logs into Eruda Console tool
                        try {
                            if (window.__onyx_console_buffer && Array.isArray(window.__onyx_console_buffer)) {
                                var cTool = window.eruda.get('console');
                                if (cTool) {
                                    window.__onyx_console_buffer.forEach(function(entry) {
                                        var fn = cTool[entry.level] || cTool.log;
                                        if (typeof fn === 'function') {
                                            fn.apply(cTool, entry.args);
                                        }
                                    });
                                }
                            }
                        } catch (_) {}

                        window.eruda.show();
                        window.__onyx_eruda_visible = true;
                        return 'shown';
                    }
                    return 'failed';
                } catch (e) {
                    return 'error: ' + e.message;
                }
            })();
        """.trimIndent()

        withContext(Dispatchers.Main) {
            webView.evaluateJavascript(script) { result ->
                val clean = result?.trim('"', '\'')?.lowercase() ?: "failed"
                onResult(clean)
            }
        }
    }

    /**
     * Re-injects DevTools automatically after navigation when active on a tab.
     */
    suspend fun autoReinjectDevTools(
        context: Context,
        webView: WebView,
        isDark: Boolean
    ) {
        val erudaCode = getErudaCode(context)
        if (erudaCode.isBlank()) return

        val themeName = if (isDark) "Dark" else "Light"
        val script = """
            (function() {
                try {
                    if (window.eruda && window.eruda._isInit) return;
                    $erudaCode
                    if (window.eruda) {
                        window.eruda.init({
                            tool: ['console', 'elements', 'network', 'resource', 'info', 'snippets', 'sources', 'storage'],
                            defaults: {
                                displaySize: 55,
                                transparency: 0.95,
                                theme: '$themeName'
                            }
                        });
                        try {
                            if (window.__onyx_console_buffer && Array.isArray(window.__onyx_console_buffer)) {
                                var cTool = window.eruda.get('console');
                                if (cTool) {
                                    window.__onyx_console_buffer.forEach(function(entry) {
                                        var fn = cTool[entry.level] || cTool.log;
                                        if (typeof fn === 'function') {
                                            fn.apply(cTool, entry.args);
                                        }
                                    });
                                }
                            }
                        } catch (_) {}
                        window.__onyx_eruda_visible = false; // keep minimized until user taps icon
                    }
                } catch (_) {}
            })();
        """.trimIndent()

        withContext(Dispatchers.Main) {
            webView.evaluateJavascript(script, null)
        }
    }

    /**
     * Completely destroys and unloads Eruda from the page DOM.
     */
    fun destroyDevTools(webView: WebView) {
        webView.evaluateJavascript("""
            (function() {
                try {
                    if (window.eruda) {
                        window.eruda.destroy();
                        window.__onyx_eruda_visible = false;
                    }
                } catch (_) {}
            })();
        """.trimIndent(), null)
    }
}
