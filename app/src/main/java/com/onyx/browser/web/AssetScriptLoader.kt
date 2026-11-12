package com.onyx.browser.web

import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe in-memory script loader and cache for externalized WebView JavaScript assets.
 *
 * Pre-warms scripts from `assets/scripts/` into memory during application startup so
 * `onPageStarted` and `onPageFinished` evaluations execute with zero I/O or string
 * template allocation overhead across tab navigations.
 */
object AssetScriptLoader {

    private const val TAG = "AssetScriptLoader"

    const val SCRIPT_PASSKEY_POLYFILL = "scripts/passkey_webauthn_polyfill.js"
    const val SCRIPT_MEDIA_OBSERVER = "scripts/media_playback_observer.js"
    const val SCRIPT_ADBLOCK_PROCEDURAL = "scripts/adblock_procedural_engine.js"

    private val PRELOAD_SCRIPTS = arrayOf(
        SCRIPT_PASSKEY_POLYFILL,
        SCRIPT_MEDIA_OBSERVER,
        SCRIPT_ADBLOCK_PROCEDURAL
    )

    private val scriptCache = ConcurrentHashMap<String, String>()

    @Volatile
    private var appContext: Context? = null

    /**
     * Pre-loads all registered JavaScript assets into memory.
     * Safe to invoke from a background I/O thread during [com.onyx.browser.OnyxApplication.onCreate].
     */
    fun preloadAll(context: Context) {
        val appCtx = context.applicationContext ?: context
        appContext = appCtx
        for (assetPath in PRELOAD_SCRIPTS) {
            if (!scriptCache.containsKey(assetPath)) {
                readAssetText(appCtx, assetPath)?.let { content ->
                    if (content.isNotEmpty()) {
                        scriptCache.putIfAbsent(assetPath, content)
                    }
                }
            }
        }
    }

    /**
     * Returns the cached script for [assetPath], or reads and caches it from [context]'s assets.
     * Falls back to [fallbackProvider] if the asset cannot be read.
     */
    fun loadScript(
        context: Context,
        assetPath: String,
        fallbackProvider: () -> String = { "" }
    ): String {
        scriptCache[assetPath]?.let { return it }

        val appCtx = context.applicationContext ?: context
        if (appContext == null) {
            appContext = appCtx
        }

        val loaded = readAssetText(appCtx, assetPath)?.takeIf { it.isNotEmpty() }
            ?: fallbackProvider()

        if (loaded.isNotEmpty()) {
            scriptCache.putIfAbsent(assetPath, loaded)
        }
        return scriptCache[assetPath] ?: loaded
    }

    /**
     * Synchronous lock-free lookup from [scriptCache] for callers without a [Context] reference.
     * If not yet cached, attempts to read from the registered [appContext] assets first,
     * then falls back to [fallbackProvider] and caches the non-empty result.
     */
    fun getCachedOrFallback(
        assetPath: String,
        fallbackProvider: () -> String
    ): String {
        scriptCache[assetPath]?.let { return it }

        val ctx = appContext
        val resolved = if (ctx != null && assetPath.endsWith(".js")) {
            readAssetText(ctx, assetPath)?.takeIf { it.isNotEmpty() } ?: fallbackProvider()
        } else {
            fallbackProvider()
        }

        if (resolved.isNotEmpty()) {
            scriptCache.putIfAbsent(assetPath, resolved)
        }
        return scriptCache[assetPath] ?: resolved
    }

    private fun readAssetText(context: Context, assetPath: String): String? {
        return try {
            context.assets.open(assetPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (t: Throwable) {
            Log.w(TAG, "Asset script read fallback for $assetPath: ${t.message}")
            null
        }
    }
}
