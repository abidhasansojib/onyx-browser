package com.onyx.browser.data.favicon

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Universal asynchronous Favicon & Website Logo fetcher and caching engine.
 * Employs a multi-tiered architecture:
 * 1. In-Memory LruCache (instant UI recycling and zero redraw jitter).
 * 2. Persistent Disk Cache (offline capability and persistent session availability).
 * 3. Remote High-Resolution Google S2 & Direct Favicon Fetcher.
 */
object FaviconManager {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Memory cache holding up to 120 decoded Bitmaps
    private val memoryCache = object : LruCache<String, Bitmap>(120) {
        override fun entryRemoved(evicted: Boolean, key: String?, oldValue: Bitmap?, newValue: Bitmap?) {
            // Let GC collect recycled bitmaps safely
        }
    }

    /**
     * Extracts a normalized cache key from a URL or hostname.
     */
    fun getCacheKey(urlOrHost: String): String {
        val trimmed = urlOrHost.trim()
        val host = try {
            val uri = Uri.parse(if (!trimmed.contains("://")) "https://$trimmed" else trimmed)
            uri.host?.removePrefix("www.")?.lowercase() ?: trimmed.lowercase()
        } catch (_: Exception) {
            trimmed.lowercase()
        }
        return hashKey(host.ifBlank { "unknown" })
    }

    private fun hashKey(input: String): String {
        return try {
            val md = MessageDigest.getInstance("MD5")
            val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
            bytes.joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            input.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        }
    }

    private fun getDiskCacheDir(context: Context): File {
        val dir = File(context.cacheDir, "favicons")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Synchronously retrieves a cached favicon from memory, or null if not yet resident.
     */
    fun getCachedFavicon(urlOrHost: String): Bitmap? {
        val key = getCacheKey(urlOrHost)
        return memoryCache.get(key)
    }

    /**
     * Asynchronously loads a website favicon and applies it to an ImageView.
     * Optionally toggles a fallback single-letter badge view.
     */
    fun loadFavicon(
        context: Context,
        imageView: ImageView,
        urlOrHost: String,
        fallbackLetterView: View? = null,
        isCircular: Boolean = false
    ) {
        val trimmed = urlOrHost.trim()
        if (trimmed.isBlank() || trimmed.startsWith("about:") || trimmed.startsWith("chrome:") || trimmed.startsWith("onyx:")) {
            fallbackLetterView?.visibility = View.VISIBLE
            imageView.visibility = View.GONE
            return
        }

        val key = getCacheKey(trimmed)
        imageView.tag = key

        // 1. Memory Cache Hit (Immediate UI update)
        val memBitmap = memoryCache.get(key)
        if (memBitmap != null) {
            val finalBmp = if (isCircular) getCircularBitmap(memBitmap) else memBitmap
            imageView.setImageBitmap(finalBmp)
            imageView.clearColorFilter()
            imageView.imageTintList = null
            imageView.visibility = View.VISIBLE
            fallbackLetterView?.visibility = View.GONE
            return
        }

        // Show fallback letter view while loading in background
        fallbackLetterView?.visibility = View.VISIBLE
        imageView.visibility = View.GONE

        // 2. Asynchronous Disk & Remote Fetch
        scope.launch {
            val bitmap = loadFaviconInternal(context, trimmed, key)
            if (bitmap != null) {
                val finalBmp = if (isCircular) getCircularBitmap(bitmap) else bitmap
                withContext(Dispatchers.Main) {
                    if (imageView.tag == key) {
                        imageView.setImageBitmap(finalBmp)
                        imageView.clearColorFilter()
                        imageView.imageTintList = null
                        imageView.visibility = View.VISIBLE
                        fallbackLetterView?.visibility = View.GONE
                    }
                }
            }
        }
    }

    /**
     * Asynchronously retrieves a favicon Bitmap, querying memory, disk, and network.
     */
    fun loadFavicon(
        context: Context,
        urlOrHost: String,
        onLoaded: (Bitmap?) -> Unit
    ) {
        val trimmed = urlOrHost.trim()
        if (trimmed.isBlank() || trimmed.startsWith("about:") || trimmed.startsWith("chrome:") || trimmed.startsWith("onyx:")) {
            onLoaded(null)
            return
        }

        val key = getCacheKey(trimmed)
        val memBitmap = memoryCache.get(key)
        if (memBitmap != null) {
            onLoaded(memBitmap)
            return
        }

        scope.launch {
            val bitmap = loadFaviconInternal(context, trimmed, key)
            withContext(Dispatchers.Main) {
                onLoaded(bitmap)
            }
        }
    }

    private suspend fun loadFaviconInternal(context: Context, rawUrlOrHost: String, key: String): Bitmap? = withContext(Dispatchers.IO) {
        // A. Check Disk Cache
        try {
            val diskFile = File(getDiskCacheDir(context), "$key.png")
            if (diskFile.exists() && diskFile.length() > 0) {
                val diskBitmap = BitmapFactory.decodeFile(diskFile.absolutePath)
                if (diskBitmap != null) {
                    memoryCache.put(key, diskBitmap)
                    return@withContext diskBitmap
                }
            }
        } catch (_: Exception) {}

        // B. Resolve Domain Host
        val host = try {
            val uri = Uri.parse(if (!rawUrlOrHost.contains("://")) "https://$rawUrlOrHost" else rawUrlOrHost)
            uri.host?.removePrefix("www.")?.lowercase() ?: rawUrlOrHost.lowercase()
        } catch (_: Exception) {
            rawUrlOrHost.lowercase()
        }

        if (host.isBlank() || host == "localhost" || host.contains("127.0.0.1")) {
            return@withContext null
        }

        // C. Fetch Remote Favicon via Google S2 CDN (128px high-res)
        var fetchedBitmap: Bitmap? = downloadBitmap("https://www.google.com/s2/favicons?domain=$host&sz=128")

        // D. Fallback to direct favicon.ico if Google S2 returns empty or fails
        if (fetchedBitmap == null) {
            fetchedBitmap = downloadBitmap("https://$host/favicon.ico")
        }

        if (fetchedBitmap != null) {
            // Ensure size does not exceed 128x128
            val scaled = if (fetchedBitmap.width > 128 || fetchedBitmap.height > 128) {
                val factor = 128f / maxOf(fetchedBitmap.width, fetchedBitmap.height)
                Bitmap.createScaledBitmap(
                    fetchedBitmap,
                    (fetchedBitmap.width * factor).toInt().coerceAtLeast(1),
                    (fetchedBitmap.height * factor).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                fetchedBitmap
            }

            // Save to memory cache
            memoryCache.put(key, scaled)

            // Save to disk cache
            try {
                val diskFile = File(getDiskCacheDir(context), "$key.png")
                FileOutputStream(diskFile).use { out ->
                    scaled.compress(Bitmap.CompressFormat.PNG, 90, out)
                    out.flush()
                }
            } catch (_: Exception) {}

            return@withContext scaled
        }

        return@withContext null
    }

    private fun downloadBitmap(urlStr: String): Bitmap? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 3500
                readTimeout = 3500
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) OnyxBrowser/1.0")
            }
            conn.connect()
            if (conn.responseCode in 200..299) {
                conn.inputStream.use { input ->
                    val bytes = input.readBytes()
                    if (bytes.isNotEmpty()) {
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    } else null
                }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    /**
     * Produces an anti-aliased circular crop of the given Bitmap.
     */
    fun getCircularBitmap(src: Bitmap): Bitmap {
        val size = minOf(src.width, src.height)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val paint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
            isDither = true
        }

        val rect = Rect(0, 0, size, size)
        val rectF = RectF(rect)

        val srcLeft = (src.width - size) / 2
        val srcTop = (src.height - size) / 2
        val srcRect = Rect(srcLeft, srcTop, srcLeft + size, srcTop + size)

        canvas.drawARGB(0, 0, 0, 0)
        canvas.drawOval(rectF, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(src, srcRect, rect, paint)
        return output
    }
}
