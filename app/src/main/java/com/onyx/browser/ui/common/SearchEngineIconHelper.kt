package com.onyx.browser.ui.common

import android.content.Context
import android.widget.ImageView
import com.onyx.browser.data.favicon.FaviconManager
import com.onyx.browser.data.model.SearchEngine

/**
 * Universal helper for loading, dynamically fetching, caching, and shaping search engine logos.
 * Ensures all logos (both built-in and custom) are rendered with smooth circular/organic
 * shape without square card artifacts, matching Google and Brave.
 */
object SearchEngineIconHelper {

    /**
     * Loads a search engine's logo into an ImageView.
     * Uses immediate crisp bundled fallback, checks memory cache, and dynamically fetches
     * the official high-resolution logo from the search engine's domain if needed.
     */
    fun loadSearchEngineIcon(
        context: Context,
        imageView: ImageView,
        engine: SearchEngine,
        isCircular: Boolean = true
    ) {
        val targetUrl = engine.homeUrl.ifBlank { engine.queryUrl }

        // 1. Immediately set the bundled crisp transparent drawable as initial fallback
        imageView.setImageResource(engine.iconResId)
        imageView.clearColorFilter()
        imageView.imageTintList = null

        if (targetUrl.isBlank()) return

        val key = FaviconManager.getCacheKey(targetUrl)
        imageView.tag = key

        // 2. Check in-memory favicon cache
        val memBmp = FaviconManager.getCachedFavicon(targetUrl)
        if (memBmp != null) {
            val shaped = if (isCircular) FaviconManager.getCircularBitmap(memBmp) else memBmp
            imageView.setImageBitmap(shaped)
            return
        }

        // 3. Asynchronously fetch, shape, and cache from disk/network
        FaviconManager.loadFavicon(
            context = context,
            urlOrHost = targetUrl,
            saveToDisk = true
        ) { bmp ->
            if (bmp != null && imageView.tag == key) {
                val shaped = if (isCircular) FaviconManager.getCircularBitmap(bmp) else bmp
                imageView.setImageBitmap(shaped)
                imageView.clearColorFilter()
                imageView.imageTintList = null
            }
        }
    }
}
