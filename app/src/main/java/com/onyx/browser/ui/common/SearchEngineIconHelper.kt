package com.onyx.browser.ui.common

import android.content.Context
import android.widget.ImageView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.onyx.browser.R
import com.onyx.browser.data.favicon.FaviconManager
import com.onyx.browser.data.model.SearchEngine
import com.onyx.browser.ui.home.rememberFavicon

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

/**
 * Composable search engine icon that dynamically resolves and renders the search engine's
 * authentic logo: checks dynamic live favicon first, bundled crisp transparent drawable second,
 * and falls back to a clean initial avatar.
 */
@Composable
fun SearchEngineIcon(
    engine: SearchEngine,
    modifier: Modifier = Modifier.size(28.dp)
) {
    val targetUrl = engine.homeUrl.ifBlank { engine.queryUrl }
    val dynamicFavicon = if (targetUrl.isNotBlank()) rememberFavicon(targetUrl) else null

    when {
        dynamicFavicon != null -> {
            Image(
                bitmap = dynamicFavicon,
                contentDescription = engine.displayName,
                contentScale = ContentScale.Fit,
                modifier = modifier.clip(CircleShape)
            )
        }
        engine.iconResId != 0 && engine.iconResId != R.drawable.ic_web -> {
            Image(
                painter = painterResource(engine.iconResId),
                contentDescription = engine.displayName,
                contentScale = ContentScale.Fit,
                modifier = modifier.clip(CircleShape)
            )
        }
        else -> {
            Box(
                modifier = modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                val initial = engine.displayName.trim().firstOrNull()?.uppercase() ?: "S"
                Text(
                    text = initial,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
