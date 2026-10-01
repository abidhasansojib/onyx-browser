package com.onyx.browser.web

import android.content.Context
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Manages Developer Tools (Mobile Eruda Console & Remote WebContents Debugging).
 *
 * Capabilities:
 * 1. Pre-buffering Console & Error messages at document_start so early page errors are captured.
 * 2. In-memory caching of the eruda.min.js engine via Mutex-guarded coroutine-safe init.
 * 3. Dynamic theme synchronization (Light / Dark) matching Onyx Browser themes.
 * 4. Responsive panel height scaled to the device's real screen size.
 * 5. Intelligent Toggle logic (initialization → show → hide) with state tracking.
 * 6. Clean lifecycle teardown (destroy) when DevTools is dismissed.
 * 7. Object serialization in console buffer using JSON.stringify for rich object inspection.
 * 8. Live Eruda console forwarding from WebChromeClient.onConsoleMessage when DevTools is open.
 */
object DevToolsManager {

    private const val TAG = "DevToolsManager"

    @Volatile
    private var cachedErudaCode: String? = null
    private val erudaMutex = Mutex()

    /**
     * Injected at document_start into all WebViews to record console calls and uncaught errors
     * prior to Eruda initialization. When Eruda is opened, these are replayed into its console.
     *
     * Improvements over prior version:
     * - Buffer cap raised to 500 entries.
     * - Complex objects are serialized with JSON.stringify to preserve structure.
     * - Stack traces captured from Error objects in console.error() calls.
     * - unhandledrejection includes full reason.stack when available.
     */
    val consoleBufferScript: String = """
        (function() {
            if (window.__onyx_console_buffer) return;
            window.__onyx_console_buffer = [];

            function safeSerialize(arg) {
                if (arg === null) return 'null';
                if (arg === undefined) return 'undefined';
                var t = typeof arg;
                if (t === 'string' || t === 'number' || t === 'boolean') return arg;
                if (arg instanceof Error) return (arg.stack || arg.message || String(arg));
                try { return JSON.stringify(arg); } catch (_) { return String(arg); }
            }

            var methods = ['log', 'warn', 'error', 'info', 'debug'];
            methods.forEach(function(m) {
                var orig = console[m];
                console[m] = function() {
                    try {
                        if (window.__onyx_console_buffer.length < 500) {
                            var serialized = Array.prototype.map.call(arguments, safeSerialize);
                            window.__onyx_console_buffer.push({
                                level: m,
                                args: serialized,
                                timestamp: Date.now()
                            });
                        }
                        // Forward live to Eruda console if DevTools already initialized
                        try {
                            if (window.eruda && window.eruda._isInit) {
                                var cTool = window.eruda.get('console');
                                if (cTool && typeof cTool[m] === 'function') {
                                    cTool[m].apply(cTool, Array.prototype.slice.call(arguments));
                                }
                            }
                        } catch (_) {}
                    } catch (_) {}
                    if (orig) return orig.apply(this, arguments);
                };
            });

            window.addEventListener('error', function(e) {
                try {
                    if (window.__onyx_console_buffer.length < 500) {
                        var loc = e.filename ? ' (' + e.filename + ':' + e.lineno + ':' + e.colno + ')' : '';
                        var stack = (e.error && e.error.stack) ? '\n' + e.error.stack : '';
                        window.__onyx_console_buffer.push({
                            level: 'error',
                            args: [(e.message || 'Error') + loc + stack],
                            timestamp: Date.now()
                        });
                    }
                } catch (_) {}
            }, true);

            window.addEventListener('unhandledrejection', function(e) {
                try {
                    if (window.__onyx_console_buffer.length < 500) {
                        var reason;
                        if (e.reason && e.reason.stack) {
                            reason = e.reason.stack;
                        } else if (e.reason !== null && e.reason !== undefined) {
                            try { reason = JSON.stringify(e.reason); } catch (_) { reason = String(e.reason); }
                        } else {
                            reason = 'Unhandled Promise Rejection';
                        }
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

    /**
     * Thread-safe loading of eruda.min.js from assets, cached in memory after first load.
     * Uses a coroutine Mutex instead of Java synchronized() to be safe on Dispatchers.IO.
     */
    suspend fun getErudaCode(context: Context): String = erudaMutex.withLock {
        cachedErudaCode ?: withContext(Dispatchers.IO) {
            try {
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
     * Calculates an appropriate Eruda panel display size (35–60%) based on screen height.
     * Smaller phones get a smaller panel so the page is still navigable.
     */
    private fun getDisplaySize(context: Context): Int {
        return try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm?.defaultDisplay?.getMetrics(metrics)
            val dpHeight = (metrics.heightPixels / metrics.density).toInt()
            when {
                dpHeight < 600 -> 38  // small phones (< 600dp)
                dpHeight < 750 -> 45  // mid-size phones
                dpHeight < 900 -> 50  // large phones
                else -> 55            // tablets / extra large
            }
        } catch (_: Exception) {
            45
        }
    }

    /**
     * Builds the Eruda init + log-replay script block.
     * Extracted to avoid duplication between toggleDevTools and autoReinjectDevTools.
     */
    private fun buildInitScript(erudaCode: String, themeName: String, displaySize: Int): String = """
        (function() {
            try {
                // Guard: if already initialized, do nothing
                if (window.eruda && window.eruda._isInit) return 'already_init';

                // Inject Eruda engine
                $erudaCode

                if (!window.eruda) return 'inject_failed';

                window.eruda.init({
                    tool: ['console', 'elements', 'network', 'resource', 'info', 'snippets', 'sources', 'storage'],
                    defaults: {
                        displaySize: $displaySize,
                        transparency: 0.97,
                        theme: '$themeName'
                    }
                });

                // Replay pre-buffered console logs into Eruda Console panel
                try {
                    if (Array.isArray(window.__onyx_console_buffer)) {
                        var cTool = window.eruda.get('console');
                        if (cTool) {
                            window.__onyx_console_buffer.forEach(function(entry) {
                                var fn = cTool[entry.level] || cTool.log;
                                if (typeof fn === 'function') {
                                    fn.apply(cTool, Array.isArray(entry.args) ? entry.args : [entry.args]);
                                }
                            });
                        }
                    }
                } catch (_) {}

                return 'init_ok';
            } catch (e) {
                return 'init_error: ' + e.message;
            }
        })();
    """.trimIndent()

    /**
     * Toggles the developer tools overlay.
     *
     * Result values passed to onResult:
     * - "shown"   : DevTools panel was opened/initialized and shown.
     * - "hidden"  : DevTools panel was minimized (user can re-open via Eruda icon).
     * - "failed"  : Eruda assets could not be loaded from disk.
     * - "error"   : JS execution threw an unexpected exception.
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
        val displaySize = getDisplaySize(context)

        val toggleScript = """
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

                    ${buildInitScript(erudaCode, themeName, displaySize)}

                    if (window.eruda && window.eruda._isInit) {
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
            webView.evaluateJavascript(toggleScript) { rawResult ->
                // evaluateJavascript wraps string results in JSON quotes: "shown" -> need to unwrap
                val result = rawResult
                    ?.trim()
                    ?.removeSurrounding("\"")
                    ?.removeSurrounding("'")
                    ?.lowercase()
                    ?: "failed"
                val status = when {
                    result == "shown" -> "shown"
                    result == "hidden" -> "hidden"
                    result.startsWith("error") -> "error"
                    else -> "failed"
                }
                onResult(status)
            }
        }
    }

    /**
     * Re-injects DevTools automatically after page navigation when active on a tab.
     * Keeps Eruda initialized but hidden (user taps the Eruda icon to reopen it).
     */
    suspend fun autoReinjectDevTools(
        context: Context,
        webView: WebView,
        isDark: Boolean
    ) {
        val erudaCode = getErudaCode(context)
        if (erudaCode.isBlank()) return

        val themeName = if (isDark) "Dark" else "Light"
        val displaySize = getDisplaySize(context)

        // After page navigation the document is new, so eruda._isInit will be false.
        // We re-init but keep the panel hidden (eruda icon remains visible for the user to tap).
        val reinjectScript = buildInitScript(erudaCode, themeName, displaySize) + """
            ;(function() {
                // After auto-reinject, keep panel hidden — user taps Eruda icon to open
                try {
                    window.__onyx_eruda_visible = false;
                } catch (_) {}
            })();
        """

        withContext(Dispatchers.Main) {
            webView.evaluateJavascript(reinjectScript.trimIndent(), null)
        }
    }

    /**
     * Updates the Eruda theme dynamically without re-initializing.
     * Called when the user switches the browser theme while DevTools is open.
     */
    fun updateTheme(webView: WebView, isDark: Boolean) {
        val themeName = if (isDark) "Dark" else "Light"
        webView.evaluateJavascript("""
            (function() {
                try {
                    if (window.eruda && window.eruda._isInit) {
                        window.eruda.util.theme('$themeName');
                    }
                } catch (_) {}
            })();
        """.trimIndent(), null)
    }

    /**
     * Forwards a console message from WebChromeClient.onConsoleMessage directly into
     * Eruda's live console panel if DevTools is currently open and initialized.
     * This fills the gap between Chromium's native console and Eruda's panel.
     */
    fun forwardConsoleMessage(webView: WebView, level: String, message: String, source: String, line: Int) {
        val safeMsg = message
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "")
        val safeSource = source
            .replace("\\", "\\\\")
            .replace("'", "\\'")
        val erudaLevel = when (level.lowercase()) {
            "error" -> "error"
            "warning" -> "warn"
            "debug" -> "debug"
            "info" -> "info"
            else -> "log"
        }
        webView.evaluateJavascript("""
            (function() {
                try {
                    if (window.eruda && window.eruda._isInit && window.__onyx_eruda_visible) {
                        var cTool = window.eruda.get('console');
                        if (cTool && typeof cTool['$erudaLevel'] === 'function') {
                            cTool['$erudaLevel']('$safeMsg ($safeSource:$line)');
                        }
                    }
                } catch (_) {}
            })();
        """.trimIndent(), null)
    }

    /**
     * Completely destroys and unloads Eruda from the page DOM.
     * Safe to call even if Eruda was never initialized.
     */
    fun destroyDevTools(webView: WebView) {
        webView.evaluateJavascript("""
            (function() {
                try {
                    if (window.eruda) {
                        window.eruda.destroy();
                    }
                    window.__onyx_eruda_visible = false;
                    window.__onyx_eruda_init_attempted = false;
                } catch (_) {}
            })();
        """.trimIndent(), null)
    }
}
