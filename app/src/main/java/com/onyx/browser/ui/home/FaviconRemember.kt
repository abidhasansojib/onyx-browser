package com.onyx.browser.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.onyx.browser.data.favicon.FaviconManager

/**
 * Compose hook that instantly checks FaviconManager's in-memory LruCache,
 * then asynchronously loads from persistent disk cache or network if needed.
 */
@Composable
fun rememberFavicon(url: String): ImageBitmap? {
    val context = LocalContext.current
    val trimmed = url.trim()
    var bitmap by remember(trimmed) {
        val cached = if (trimmed.isNotBlank() &&
            !trimmed.startsWith("onyx://") &&
            !trimmed.startsWith("about:") &&
            !trimmed.startsWith("chrome:")
        ) {
            FaviconManager.getCachedFavicon(trimmed)
        } else {
            null
        }
        mutableStateOf(cached?.asImageBitmap())
    }

    LaunchedEffect(trimmed) {
        if (bitmap == null &&
            trimmed.isNotBlank() &&
            !trimmed.startsWith("onyx://") &&
            !trimmed.startsWith("about:") &&
            !trimmed.startsWith("chrome:")
        ) {
            FaviconManager.loadFavicon(context, trimmed, saveToDisk = true) { loaded ->
                if (loaded != null) {
                    bitmap = loaded.asImageBitmap()
                }
            }
        }
    }
    return bitmap
}
