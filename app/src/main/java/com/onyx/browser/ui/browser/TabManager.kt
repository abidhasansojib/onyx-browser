package com.onyx.browser.ui.browser

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import com.onyx.browser.data.local.AppDatabase
import com.onyx.browser.data.model.TabItem
import com.onyx.browser.web.OnyxWebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class TabManager(
    private val context: Context,
    private val coroutineScope: CoroutineScope
) {
    private val database = AppDatabase.getInstance(context)

    private val _normalTabs = MutableStateFlow<List<TabItem>>(emptyList())
    val normalTabs: StateFlow<List<TabItem>> = _normalTabs.asStateFlow()

    private val _incognitoTabs = MutableStateFlow<List<TabItem>>(emptyList())
    val incognitoTabs: StateFlow<List<TabItem>> = _incognitoTabs.asStateFlow()

    private val _activeTab = MutableStateFlow<TabItem?>(null)
    val activeTab: StateFlow<TabItem?> = _activeTab.asStateFlow()

    private val webViewPool = mutableMapOf<String, OnyxWebView>()
    
    val snapshotCache = object : android.util.LruCache<String, Bitmap>(30) {
        override fun entryRemoved(evicted: Boolean, key: String?, oldValue: Bitmap?, newValue: Bitmap?) {
            // Let GC reclaim memory smoothly
        }
    }

    private fun getThumbnailDir(): File {
        val dir = File(context.cacheDir, "tab_thumbnails")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getThumbnailFile(tabId: String): File =
        File(getThumbnailDir(), "$tabId.webp")

    /**
     * Retrieves the tab snapshot from memory cache or persistent disk cache.
     */
    fun getSnapshot(tabId: String): Bitmap? {
        val mem = snapshotCache.get(tabId)
        if (mem != null) return mem

        try {
            val file = getThumbnailFile(tabId)
            if (file.exists() && file.length() > 0) {
                val diskBmp = BitmapFactory.decodeFile(file.absolutePath)
                if (diskBmp != null) {
                    snapshotCache.put(tabId, diskBmp)
                    return diskBmp
                }
            }
        } catch (_: Exception) {}
        return null
    }

    /**
     * Proactively captures a crisp, memory-optimized thumbnail of the given WebView.
     * Uses hardware PixelCopy with synchronous Canvas fallback.
     */
    fun captureTabSnapshot(tabId: String, webView: OnyxWebView?, onCaptured: ((Bitmap) -> Unit)? = null) {
        if (webView == null || webView.width <= 0 || webView.height <= 0) return
        val w = webView.width
        val h = webView.height
        val scale = (360f / w).coerceAtMost(0.5f)
        val targetW = (w * scale).toInt().coerceAtLeast(1)
        val targetH = (h * scale).toInt().coerceAtLeast(1)

        val activity = (context as? Activity)
        val window = activity?.window
        var pixelCopySuccess = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && window != null) {
            try {
                val bitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                val location = IntArray(2)
                webView.getLocationInWindow(location)
                val rect = Rect(
                    location[0],
                    location[1],
                    location[0] + w,
                    location[1] + h
                )
                PixelCopy.request(
                    window,
                    rect,
                    bitmap,
                    { copyResult ->
                        if (copyResult == PixelCopy.SUCCESS) {
                            saveSnapshot(tabId, bitmap)
                            onCaptured?.invoke(bitmap)
                        } else {
                            fallbackCanvasCapture(tabId, webView, targetW, targetH, scale, onCaptured)
                        }
                    },
                    Handler(Looper.getMainLooper())
                )
                pixelCopySuccess = true
            } catch (_: Exception) {
                pixelCopySuccess = false
            }
        }

        if (!pixelCopySuccess) {
            fallbackCanvasCapture(tabId, webView, targetW, targetH, scale, onCaptured)
        }
    }

    private fun fallbackCanvasCapture(
        tabId: String,
        webView: OnyxWebView,
        targetW: Int,
        targetH: Int,
        scale: Float,
        onCaptured: ((Bitmap) -> Unit)?
    ) {
        try {
            val bitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.RGB_565)
            val canvas = Canvas(bitmap)
            canvas.scale(scale, scale)
            webView.draw(canvas)
            saveSnapshot(tabId, bitmap)
            onCaptured?.invoke(bitmap)
        } catch (_: Exception) {}
    }

    private fun saveSnapshot(tabId: String, bitmap: Bitmap) {
        snapshotCache.put(tabId, bitmap)
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val file = getThumbnailFile(tabId)
                FileOutputStream(file).use { out ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 85, out)
                    } else {
                        @Suppress("DEPRECATION")
                        bitmap.compress(Bitmap.CompressFormat.WEBP, 85, out)
                    }
                    out.flush()
                }
            } catch (_: Exception) {}
        }
    }

    suspend fun restoreTabs() = withContext(Dispatchers.IO) {
        val savedTabs = database.tabDao().getAllNormalTabs()
        if (savedTabs.isNotEmpty()) {
            _normalTabs.value = savedTabs
            val firstTab = savedTabs.first()
            _activeTab.value = firstTab
        } else {
            // Create default initial tab
            val defaultTab = TabItem(
                id = UUID.randomUUID().toString(),
                url = "",
                title = "New Tab",
                isIncognito = false,
                position = 0
            )
            database.tabDao().insertTab(defaultTab)
            _normalTabs.value = listOf(defaultTab)
            _activeTab.value = defaultTab
        }
    }

    fun getOrCreateWebView(tab: TabItem): OnyxWebView {
        var webView = webViewPool[tab.id]
        if (webView == null) {
            webView = OnyxWebView(context).apply {
                tabId = tab.id
                setIncognitoMode(tab.isIncognito)
            }
            webViewPool[tab.id] = webView
        }
        return webView
    }

    fun getActiveWebView(): OnyxWebView? {
        val current = _activeTab.value ?: return null
        return webViewPool[current.id]
    }

    fun getWebView(tabId: String): OnyxWebView? = webViewPool[tabId]

    fun createNewTab(url: String = "", isIncognito: Boolean = false): TabItem {
        val newTab = TabItem(
            id = UUID.randomUUID().toString(),
            url = url,
            title = if (url.isBlank()) "New Tab" else url,
            isIncognito = isIncognito,
            position = if (isIncognito) _incognitoTabs.value.size else _normalTabs.value.size
        )

        if (isIncognito) {
            _incognitoTabs.value = _incognitoTabs.value + newTab
        } else {
            _normalTabs.value = _normalTabs.value + newTab
            coroutineScope.launch(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
                database.tabDao().insertTab(newTab)
            }
        }

        _activeTab.value = newTab
        return newTab
    }

    fun selectTab(tab: TabItem) {
        val updatedTab = tab.copy(lastAccessedAt = System.currentTimeMillis(), isHibernated = false)
        if (!updatedTab.isIncognito) {
            coroutineScope.launch(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
                database.tabDao().updateTab(updatedTab)
            }
            val newList = _normalTabs.value.map { if (it.id == updatedTab.id) updatedTab else it }
            _normalTabs.value = newList
        } else {
            val newList = _incognitoTabs.value.map { if (it.id == updatedTab.id) updatedTab else it }
            _incognitoTabs.value = newList
        }
        _activeTab.value = updatedTab
    }

    fun hibernateIdleTabs() {
        val cutoff = System.currentTimeMillis() - (24 * 60 * 60 * 1000L) // 24 hours
        
        var hasNormalChanges = false
        val updatedNormal = _normalTabs.value.map { tab ->
            if (tab.id != _activeTab.value?.id && !tab.isHibernated && tab.lastAccessedAt < cutoff) {
                val webView = webViewPool.remove(tab.id)
                webView?.destroySafely()
                hasNormalChanges = true
                val hibernatedTab = tab.copy(isHibernated = true)
                coroutineScope.launch(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
                    database.tabDao().updateTab(hibernatedTab)
                }
                hibernatedTab
            } else {
                tab
            }
        }
        if (hasNormalChanges) {
            _normalTabs.value = updatedNormal
        }
        
        var hasIncognitoChanges = false
        val updatedIncognito = _incognitoTabs.value.map { tab ->
            if (tab.id != _activeTab.value?.id && !tab.isHibernated && tab.lastAccessedAt < cutoff) {
                val webView = webViewPool.remove(tab.id)
                webView?.destroySafely()
                hasIncognitoChanges = true
                tab.copy(isHibernated = true)
            } else {
                tab
            }
        }
        if (hasIncognitoChanges) {
            _incognitoTabs.value = updatedIncognito
        }
    }

    fun closeTab(tab: TabItem) {
        // Safe destruction of associated WebView
        val webView = webViewPool.remove(tab.id)
        webView?.destroySafely()
        snapshotCache.remove(tab.id)
        coroutineScope.launch(Dispatchers.IO) {
            try { getThumbnailFile(tab.id).delete() } catch (_: Exception) {}
        }

        if (tab.isIncognito) {
            val updated = _incognitoTabs.value.filter { it.id != tab.id }
            _incognitoTabs.value = updated
            if (_activeTab.value?.id == tab.id) {
                _activeTab.value = updated.lastOrNull() ?: _normalTabs.value.lastOrNull()
            }
        } else {
            val updated = _normalTabs.value.filter { it.id != tab.id }
            _normalTabs.value = updated
            coroutineScope.launch(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
                database.tabDao().deleteTabById(tab.id)
            }
            if (_activeTab.value?.id == tab.id) {
                if (updated.isNotEmpty()) {
                    _activeTab.value = updated.last()
                } else {
                    // Always maintain at least one tab
                    createNewTab(isIncognito = false)
                }
            }
        }
    }

    fun closeAllTabs(incognitoOnly: Boolean) {
        if (incognitoOnly) {
            _incognitoTabs.value.forEach { tab ->
                webViewPool.remove(tab.id)?.destroySafely()
            }
            _incognitoTabs.value = emptyList()
            if (_activeTab.value?.isIncognito == true) {
                _activeTab.value = _normalTabs.value.firstOrNull() ?: createNewTab(isIncognito = false)
            }
        } else {
            _normalTabs.value.forEach { tab ->
                webViewPool.remove(tab.id)?.destroySafely()
            }
            _normalTabs.value = emptyList()
            coroutineScope.launch(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
                database.tabDao().clearNormalTabs()
            }
            val newTab = createNewTab(isIncognito = false)
            _activeTab.value = newTab
        }
    }

    fun closeTabsCreatedSince(sinceTime: Long) {
        val normalToClose = _normalTabs.value.filter { it.createdAt >= sinceTime }
        val incognitoToClose = _incognitoTabs.value.filter { it.createdAt >= sinceTime }

        for (tab in normalToClose + incognitoToClose) {
            webViewPool.remove(tab.id)?.destroySafely()
        }

        val remainingNormal = _normalTabs.value.filter { it.createdAt < sinceTime }
        val remainingIncognito = _incognitoTabs.value.filter { it.createdAt < sinceTime }

        _normalTabs.value = remainingNormal
        _incognitoTabs.value = remainingIncognito

        coroutineScope.launch(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
            for (tab in normalToClose) {
                database.tabDao().deleteTabById(tab.id)
            }
        }

        if (remainingNormal.isEmpty()) {
            val newTab = createNewTab(isIncognito = false)
            _activeTab.value = newTab
        } else if (_activeTab.value == null || normalToClose.any { it.id == _activeTab.value?.id } || incognitoToClose.any { it.id == _activeTab.value?.id }) {
            _activeTab.value = remainingNormal.lastOrNull()
        }
    }

    fun updateActiveTab(url: String, title: String) {
        val current = _activeTab.value ?: return
        val updatedTab = current.copy(url = url, title = title.ifBlank { url }, lastAccessedAt = System.currentTimeMillis(), isHibernated = false)
        _activeTab.value = updatedTab

        if (updatedTab.isIncognito) {
            _incognitoTabs.value = _incognitoTabs.value.map { if (it.id == updatedTab.id) updatedTab else it }
        } else {
            _normalTabs.value = _normalTabs.value.map { if (it.id == updatedTab.id) updatedTab else it }
            coroutineScope.launch(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
                database.tabDao().updateTab(updatedTab)
            }
        }
    }

    fun getOpenTabCount(): Int {
        val active = _activeTab.value
        return if (active?.isIncognito == true) {
            _incognitoTabs.value.size
        } else {
            _normalTabs.value.size
        }
    }

    fun clearAllWebViews() {
        webViewPool.values.forEach { it.destroySafely() }
        webViewPool.clear()
    }
}
