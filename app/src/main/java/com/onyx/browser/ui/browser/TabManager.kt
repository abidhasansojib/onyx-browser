package com.onyx.browser.ui.browser

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
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
    companion object {
        private var _activeInstanceRef: java.lang.ref.WeakReference<TabManager>? = null
        var activeInstance: TabManager?
            get() = _activeInstanceRef?.get()
            set(value) {
                _activeInstanceRef = value?.let { java.lang.ref.WeakReference(it) }
            }
    }

    private val database = AppDatabase.getInstance(context)

    private val _normalTabs = MutableStateFlow<List<TabItem>>(emptyList())
    val normalTabs: StateFlow<List<TabItem>> = _normalTabs.asStateFlow()

    private val _incognitoTabs = MutableStateFlow<List<TabItem>>(emptyList())
    val incognitoTabs: StateFlow<List<TabItem>> = _incognitoTabs.asStateFlow()

    private val _activeTab = MutableStateFlow<TabItem?>(null)
    val activeTab: StateFlow<TabItem?> = _activeTab.asStateFlow()

    private val webViewPool = mutableMapOf<String, OnyxWebView>()
    var isIncognitoUnlocked: Boolean = false
    var onTabClosedListener: ((TabItem) -> Unit)? = null
    
    private val maxMem = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = (maxMem / 8).coerceIn(4096, 32768)

    val snapshotCache = object : android.util.LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount / 1024
        }
        override fun entryRemoved(evicted: Boolean, key: String?, oldValue: Bitmap?, newValue: Bitmap?) {
            // Let GC reclaim memory smoothly
        }
    }

    fun clearAllThumbnailsAndCache() {
        snapshotCache.evictAll()
        com.onyx.browser.web.LocalFileLoader.cleanupAllPreviews(context)
        coroutineScope.launch(Dispatchers.IO) {
            try {
                getThumbnailDir().listFiles()?.forEach { it.delete() }
            } catch (_: Exception) {}
        }
    }

    init {
        activeInstance = this
    }

    private fun getThumbnailDir(): File {
        val dir = File(context.cacheDir, "tab_thumbnails")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getThumbnailFile(tabId: String): File =
        File(getThumbnailDir(), "$tabId.webp")

    private fun getStateDir(): File {
        val dir = File(context.filesDir, "tab_states")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getStateFile(tabId: String): File =
        File(getStateDir(), "state_$tabId.bin")

    private fun saveBundleToFile(bundle: Bundle, file: File) {
        val parcel = Parcel.obtain()
        try {
            bundle.writeToParcel(parcel, 0)
            val bytes = parcel.marshall()
            file.writeBytes(bytes)
        } finally {
            parcel.recycle()
        }
    }

    private fun loadBundleFromFile(file: File): Bundle? {
        if (!file.exists() || file.length() == 0L) return null
        val bytes = file.readBytes()
        val parcel = Parcel.obtain()
        return try {
            parcel.unmarshall(bytes, 0, bytes.size)
            parcel.setDataPosition(0)
            val bundle = Bundle()
            bundle.readFromParcel(parcel)
            bundle
        } catch (_: Throwable) {
            try { file.delete() } catch (_: Throwable) {}
            null
        } finally {
            parcel.recycle()
        }
    }

    fun saveTabState(tabId: String, webView: OnyxWebView) {
        val tab = _normalTabs.value.firstOrNull { it.id == tabId }
        // Never persist state for incognito tabs or blank/synthetic URLs
        if (tab == null || tab.isIncognito) return
        val currentUrl = webView.url
        if (currentUrl.isNullOrBlank() || OnyxWebView.isSyntheticOrDataUrl(currentUrl)) return

        try {
            val bundle = Bundle()
            val list = webView.saveState(bundle)
            if (list != null && list.size > 0) {
                saveBundleToFile(bundle, getStateFile(tabId))
            }
        } catch (_: Throwable) {}
    }

    fun restoreTabState(tabId: String, webView: OnyxWebView): Boolean {
        val tab = _normalTabs.value.firstOrNull { it.id == tabId }
        if (tab == null || tab.isIncognito) return false
        val file = getStateFile(tabId)
        if (!file.exists() || file.length() == 0L) return false

        return try {
            val bundle = loadBundleFromFile(file) ?: return false
            val list = webView.restoreState(bundle)
            list != null && list.size > 0
        } catch (_: Throwable) {
            try { file.delete() } catch (_: Throwable) {}
            false
        }
    }

    fun hasSavedTabState(tabId: String): Boolean {
        val file = getStateFile(tabId)
        return file.exists() && file.length() > 0L
    }

    fun deleteTabState(tabId: String) {
        try {
            getStateFile(tabId).delete()
        } catch (_: Throwable) {}
    }

    fun saveAllTabStates() {
        _normalTabs.value.forEach { tab ->
            val wv = webViewPool[tab.id]
            if (wv != null) {
                saveTabState(tab.id, wv)
            }
        }
    }

    fun cleanupOrphanedTabStates() {
        try {
            val validIds = _normalTabs.value.map { it.id }.toSet()
            val dir = getStateDir()
            dir.listFiles()?.forEach { file ->
                val tabId = file.name.removePrefix("state_").removeSuffix(".bin")
                if (tabId !in validIds) {
                    file.delete()
                }
            }
            val thumbDir = getThumbnailDir()
            thumbDir.listFiles()?.forEach { file ->
                val tabId = file.name.removeSuffix(".webp")
                if (tabId !in validIds) {
                    file.delete()
                }
            }
        } catch (_: Throwable) {}
    }

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
        // NEVER write incognito tab screenshots to disk
        val isIncognito = _incognitoTabs.value.any { it.id == tabId }
        if (isIncognito) return
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
            cleanupOrphanedTabStates()
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

    /** Returns all WebViews currently in the pool (live tabs). */
    fun getAllWebViews(): List<OnyxWebView> = webViewPool.values.toList()

    fun getTabById(tabId: String): TabItem? {
        return _normalTabs.value.firstOrNull { it.id == tabId }
            ?: _incognitoTabs.value.firstOrNull { it.id == tabId }
    }

    fun createNewTab(
        url: String = "",
        isIncognito: Boolean = false,
        parentId: String? = null
    ): TabItem {
        val newTab = TabItem(
            id = UUID.randomUUID().toString(),
            url = url,
            title = if (url.isBlank()) "New Tab" else url,
            isIncognito = isIncognito,
            position = if (isIncognito) _incognitoTabs.value.size else _normalTabs.value.size,
            parentId = parentId
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
                if (webView != null) {
                    saveTabState(tab.id, webView)
                    webView.destroySafely()
                }
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

    private fun autoclearTabData(tab: TabItem) {
        val prefs = com.onyx.browser.data.preferences.BrowserPreferences.getInstance(context)
        if (!prefs.isCookieAutoclearOnCloseEnabled) return
        val url = tab.url
        if (url.isBlank() || url.startsWith("file://") || url.startsWith("content://") ||
            url.startsWith("onyx://") || url.startsWith("about:")) return

        Handler(Looper.getMainLooper()).post {
            try {
                val uri = android.net.Uri.parse(url)
                val host = uri.host ?: return@post

                // Don't clear if another open tab has the same host still open
                val stillOpen = (_normalTabs.value + _incognitoTabs.value)
                    .any { it.id != tab.id && android.net.Uri.parse(it.url).host == host }
                if (stillOpen) return@post

                // Clear WebStorage (localStorage, sessionStorage, IndexedDB)
                val scheme = uri.scheme ?: "https"
                android.webkit.WebStorage.getInstance().deleteOrigin("$scheme://$host")

                // Clear cookies reliably: expire every named cookie for the host and its parent domain
                val cookieManager = android.webkit.CookieManager.getInstance()
                val expiry = "expires=Thu, 01 Jan 1970 00:00:00 GMT"
                val targets = buildList {
                    add(host)
                    // Also target leading-dot form for cross-subdomain cookies
                    add(".$host")
                    // Strip leading www for root domain
                    if (host.startsWith("www.")) add(host.removePrefix("www."))
                }
                for (target in targets) {
                    val raw = cookieManager.getCookie(target) ?: continue
                    raw.split(";").forEach { part ->
                        val name = part.substringBefore("=").trim()
                        if (name.isNotEmpty()) {
                            // Expire on both / path and root
                            cookieManager.setCookie(target, "$name=; $expiry; path=/")
                            cookieManager.setCookie(target, "$name=; $expiry")
                        }
                    }
                }
                cookieManager.flush()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    data class ClosedTabState(
        val tab: TabItem,
        val index: Int,
        val savedStateBundle: Bundle? = null,
        val snapshot: Bitmap? = null,
        val wasActive: Boolean = false,
        val autoCreatedTabId: String? = null
    )

    data class ClosedTabBatch(
        val states: List<ClosedTabState>,
        val autoCreatedTabId: String? = null,
        val isIncognito: Boolean
    )

    private val closedBatchesStack = ArrayDeque<ClosedTabBatch>()

    private fun finalizeClosedBatch(batch: ClosedTabBatch) {
        for (state in batch.states) {
            val tabId = state.tab.id
            snapshotCache.remove(tabId)
            coroutineScope.launch(Dispatchers.IO) {
                try { getThumbnailFile(tabId).delete() } catch (_: Exception) {}
                try { deleteTabState(tabId) } catch (_: Exception) {}
            }
        }
    }

    fun finalizeAllClosedTabs() {
        while (closedBatchesStack.isNotEmpty()) {
            finalizeClosedBatch(closedBatchesStack.removeFirst())
        }
    }

    fun undoCloseTab(): ClosedTabBatch? {
        val batch = closedBatchesStack.removeLastOrNull() ?: return null

        if (batch.isIncognito) {
            val current = _incognitoTabs.value.toMutableList()
            val toRestore = batch.states.map { it.tab }.filter { tab -> current.none { it.id == tab.id } }

            val sortedStates = batch.states.sortedBy { it.index }
            for (state in sortedStates) {
                if (toRestore.any { it.id == state.tab.id }) {
                    val targetIdx = state.index.coerceIn(0, current.size)
                    current.add(targetIdx, state.tab)
                    if (state.snapshot != null) {
                        snapshotCache.put(state.tab.id, state.snapshot)
                    }
                }
            }
            _incognitoTabs.value = current

            val activeState = batch.states.firstOrNull { it.wasActive }
            if (activeState != null && current.any { it.id == activeState.tab.id }) {
                _activeTab.value = activeState.tab
            } else if (_activeTab.value == null || _activeTab.value?.isIncognito == false) {
                _activeTab.value = current.lastOrNull()
            }
            return batch
        }

        // If an empty placeholder was auto-created because tabs were cleared, cleanly remove it
        if (batch.autoCreatedTabId != null) {
            val autoTab = _normalTabs.value.firstOrNull { it.id == batch.autoCreatedTabId }
            if (autoTab != null && autoTab.url.isBlank() && autoTab.title == "New Tab") {
                _normalTabs.value = _normalTabs.value.filter { it.id != autoTab.id }
                coroutineScope.launch(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
                    database.tabDao().deleteTabById(autoTab.id)
                }
            }
        }

        val current = _normalTabs.value.toMutableList()
        val toRestore = batch.states.map { it.tab }.filter { tab -> current.none { it.id == tab.id } }

        val sortedStates = batch.states.sortedBy { it.index }
        for (state in sortedStates) {
            if (toRestore.any { it.id == state.tab.id }) {
                val targetIdx = state.index.coerceIn(0, current.size)
                current.add(targetIdx, state.tab)

                if (state.savedStateBundle != null) {
                    try {
                        saveBundleToFile(state.savedStateBundle, getStateFile(state.tab.id))
                    } catch (_: Exception) {}
                }

                if (state.snapshot != null) {
                    snapshotCache.put(state.tab.id, state.snapshot)
                    saveSnapshot(state.tab.id, state.snapshot)
                }
            }
        }
        _normalTabs.value = current

        coroutineScope.launch(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
            database.tabDao().insertTabs(toRestore)
        }

        val activeState = batch.states.firstOrNull { it.wasActive }
        if (activeState != null && current.any { it.id == activeState.tab.id }) {
            _activeTab.value = activeState.tab
        } else if (_activeTab.value == null || _activeTab.value?.id == batch.autoCreatedTabId) {
            _activeTab.value = current.lastOrNull()
        }

        return batch
    }

    fun peekLastClosedBatch(isIncognito: Boolean? = null): ClosedTabBatch? {
        return if (isIncognito == null) {
            closedBatchesStack.lastOrNull()
        } else {
            closedBatchesStack.lastOrNull { it.isIncognito == isIncognito }
        }
    }

    fun peekLastClosedTab(isIncognito: Boolean? = null): TabItem? {
        return peekLastClosedBatch(isIncognito)?.states?.firstOrNull()?.tab
    }

    fun closeTab(tab: TabItem) {
        val originalIndex = (if (tab.isIncognito) _incognitoTabs.value else _normalTabs.value)
            .indexOfFirst { it.id == tab.id }
        val wasActive = _activeTab.value?.id == tab.id

        autoclearTabData(tab)

        // Cleanup any temporary preview files associated with this tab
        com.onyx.browser.web.LocalFileLoader.cleanupTabPreviews(context, tab.id)

        // If the closed tab was currently playing media, stop background play & dismiss notification immediately
        com.onyx.browser.media.MediaPlaybackBridge.onTabClosed(tab.id, context)
        onTabClosedListener?.invoke(tab)

        // Capture state before destroying webview
        val webView = webViewPool.remove(tab.id)
        var savedBundle: Bundle? = null
        if (webView != null && !tab.isIncognito) {
            try {
                val b = Bundle()
                val list = webView.saveState(b)
                if (list != null && list.size > 0) {
                    savedBundle = b
                }
            } catch (_: Throwable) {}
        }
        val snapshot = snapshotCache.get(tab.id)

        // Safe destruction of associated WebView
        webView?.destroySafely()

        // If this tab was opened from a parent tab, find if the parent tab is still alive
        val parentTab = tab.parentId?.let { pId ->
            _normalTabs.value.firstOrNull { it.id == pId }
                ?: _incognitoTabs.value.firstOrNull { it.id == pId }
        }

        val orphanParentId = tab.parentId
        var autoCreatedTabId: String? = null

        if (tab.isIncognito) {
            val updated = _incognitoTabs.value
                .filter { it.id != tab.id }
                .map { if (it.parentId == tab.id) it.copy(parentId = orphanParentId) else it }
            _incognitoTabs.value = updated
            if (_activeTab.value?.id == tab.id) {
                _activeTab.value = parentTab ?: updated.lastOrNull() ?: _normalTabs.value.lastOrNull()
            }
        } else {
            val updated = _normalTabs.value
                .filter { it.id != tab.id }
                .map { if (it.parentId == tab.id) it.copy(parentId = orphanParentId) else it }
            _normalTabs.value = updated
            coroutineScope.launch(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
                database.tabDao().deleteTabById(tab.id)
                database.tabDao().updateParentIdForChildren(tab.id, orphanParentId)
            }
            if (_activeTab.value?.id == tab.id) {
                if (parentTab != null) {
                    _activeTab.value = parentTab
                } else if (updated.isNotEmpty()) {
                    _activeTab.value = updated.last()
                } else {
                    // Always maintain at least one tab
                    val newTab = createNewTab(isIncognito = false)
                    autoCreatedTabId = newTab.id
                }
            }
        }

        val state = ClosedTabState(
            tab = tab,
            index = if (originalIndex >= 0) originalIndex else 0,
            savedStateBundle = savedBundle,
            snapshot = snapshot,
            wasActive = wasActive,
            autoCreatedTabId = autoCreatedTabId
        )
        val batch = ClosedTabBatch(
            states = listOf(state),
            autoCreatedTabId = autoCreatedTabId,
            isIncognito = tab.isIncognito
        )
        closedBatchesStack.addLast(batch)
        if (closedBatchesStack.size > 10) {
            val oldest = closedBatchesStack.removeFirst()
            finalizeClosedBatch(oldest)
        }
    }

    fun closeAllTabs(incognitoOnly: Boolean, canUndo: Boolean = true) {
        if (incognitoOnly) {
            val tabsToClose = _incognitoTabs.value
            if (tabsToClose.isEmpty()) return

            val closedPlayingTab = tabsToClose.any { it.id == com.onyx.browser.media.MediaPlaybackBridge.currentPlayingTabId }
            if (closedPlayingTab) {
                com.onyx.browser.media.MediaPlaybackBridge.resetMediaPlayback(context)
            }

            val states = mutableListOf<ClosedTabState>()
            tabsToClose.forEachIndexed { index, tab ->
                onTabClosedListener?.invoke(tab)
                autoclearTabData(tab)
                com.onyx.browser.web.LocalFileLoader.cleanupTabPreviews(context, tab.id)
                val snapshot = snapshotCache.get(tab.id)
                val wasActive = (_activeTab.value?.id == tab.id)
                webViewPool.remove(tab.id)?.destroySafely()
                if (canUndo) {
                    states.add(
                        ClosedTabState(
                            tab = tab,
                            index = index,
                            savedStateBundle = null,
                            snapshot = snapshot,
                            wasActive = wasActive
                        )
                    )
                } else {
                    snapshotCache.remove(tab.id)
                }
            }

            _incognitoTabs.value = emptyList()
            if (_activeTab.value?.isIncognito == true) {
                _activeTab.value = _normalTabs.value.lastOrNull() ?: createNewTab(isIncognito = false)
            }

            if (canUndo && states.isNotEmpty()) {
                val batch = ClosedTabBatch(
                    states = states,
                    autoCreatedTabId = null,
                    isIncognito = true
                )
                closedBatchesStack.addLast(batch)
                if (closedBatchesStack.size > 10) {
                    val oldest = closedBatchesStack.removeFirst()
                    finalizeClosedBatch(oldest)
                }
            } else {
                closedBatchesStack.removeAll { it.isIncognito }
            }
        } else {
            val tabsToClose = _normalTabs.value
            if (tabsToClose.isEmpty()) return

            com.onyx.browser.media.MediaPlaybackBridge.resetMediaPlayback(context)

            val states = mutableListOf<ClosedTabState>()
            tabsToClose.forEachIndexed { index, tab ->
                onTabClosedListener?.invoke(tab)
                autoclearTabData(tab)
                com.onyx.browser.web.LocalFileLoader.cleanupTabPreviews(context, tab.id)
                val webView = webViewPool.remove(tab.id)
                var savedBundle: Bundle? = null
                if (webView != null) {
                    try {
                        val b = Bundle()
                        val list = webView.saveState(b)
                        if (list != null && list.size > 0) {
                            savedBundle = b
                        }
                    } catch (_: Throwable) {}
                }
                val snapshot = snapshotCache.get(tab.id)
                val wasActive = (_activeTab.value?.id == tab.id)
                webView?.destroySafely()

                if (canUndo) {
                    states.add(
                        ClosedTabState(
                            tab = tab,
                            index = index,
                            savedStateBundle = savedBundle,
                            snapshot = snapshot,
                            wasActive = wasActive
                        )
                    )
                } else {
                    snapshotCache.remove(tab.id)
                    deleteTabState(tab.id)
                    try { getThumbnailFile(tab.id).delete() } catch (_: Exception) {}
                }
            }

            _normalTabs.value = emptyList()
            coroutineScope.launch(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
                database.tabDao().clearNormalTabs()
            }
            val newTab = createNewTab(isIncognito = false)
            _activeTab.value = newTab

            if (canUndo && states.isNotEmpty()) {
                val batch = ClosedTabBatch(
                    states = states,
                    autoCreatedTabId = newTab.id,
                    isIncognito = false
                )
                closedBatchesStack.addLast(batch)
                if (closedBatchesStack.size > 10) {
                    val oldest = closedBatchesStack.removeFirst()
                    finalizeClosedBatch(oldest)
                }
            } else {
                finalizeAllClosedTabs()
            }
        }
    }

    fun closeTabsCreatedSince(sinceTime: Long) {
        val normalToClose = _normalTabs.value.filter { it.createdAt >= sinceTime }
        val incognitoToClose = _incognitoTabs.value.filter { it.createdAt >= sinceTime }
        val allClosing = normalToClose + incognitoToClose

        allClosing.forEach { onTabClosedListener?.invoke(it) }

        if (allClosing.any { it.id == com.onyx.browser.media.MediaPlaybackBridge.currentPlayingTabId }) {
            com.onyx.browser.media.MediaPlaybackBridge.resetMediaPlayback(context)
        }

        for (tab in allClosing) {
            webViewPool.remove(tab.id)?.destroySafely()
            deleteTabState(tab.id)
            snapshotCache.remove(tab.id)
            try { getThumbnailFile(tab.id).delete() } catch (_: Exception) {}
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

        if (remainingNormal.isEmpty() && remainingIncognito.isEmpty()) {
            val newTab = createNewTab(isIncognito = false)
            _activeTab.value = newTab
        } else if (_activeTab.value == null || normalToClose.any { it.id == _activeTab.value?.id } || incognitoToClose.any { it.id == _activeTab.value?.id }) {
            val wasIncognito = incognitoToClose.any { it.id == _activeTab.value?.id } || _activeTab.value?.isIncognito == true
            _activeTab.value = if (wasIncognito && remainingIncognito.isNotEmpty()) {
                remainingIncognito.last()
            } else {
                remainingNormal.lastOrNull() ?: remainingIncognito.lastOrNull() ?: createNewTab(isIncognito = false)
            }
        }
    }

    fun updateTabUrlAndTitle(tabId: String, url: String, title: String) {
        if (url.startsWith("data:") || url.startsWith("file:///android_asset/") || url.startsWith("file:///android_res/")) {
            return
        }
        val current = getTabById(tabId) ?: return
        val targetUrl = if (com.onyx.browser.web.LocalFileLoader.isPreviewUrl(url)) {
            current.url.ifBlank { url }
        } else {
            url
        }
        val cleanTitle = if (title.startsWith("data:") || title.startsWith("file:///android_asset/") || title.startsWith("file:///android_res/")) {
            current.title.ifBlank { targetUrl }
        } else if (com.onyx.browser.web.LocalFileLoader.isPreviewUrl(title)) {
            current.title.ifBlank { "Web Archive" }
        } else {
            title.ifBlank { targetUrl }
        }
        val updatedTab = current.copy(
            url = targetUrl,
            title = cleanTitle,
            lastAccessedAt = System.currentTimeMillis(),
            isHibernated = false
        )

        if (updatedTab.isIncognito) {
            _incognitoTabs.value = _incognitoTabs.value.map { if (it.id == tabId) updatedTab else it }
        } else {
            _normalTabs.value = _normalTabs.value.map { if (it.id == tabId) updatedTab else it }
            coroutineScope.launch(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
                database.tabDao().updateTab(updatedTab)
            }
        }

        // Strictly guard: only update _activeTab if this specific tab is currently active
        if (_activeTab.value?.id == tabId) {
            _activeTab.value = updatedTab
        }
    }

    fun updateActiveTab(url: String, title: String) {
        val current = _activeTab.value ?: return
        updateTabUrlAndTitle(current.id, url, title)
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
        com.onyx.browser.media.MediaPlaybackBridge.resetMediaPlayback(context)
        webViewPool.values.forEach { it.destroySafely() }
        webViewPool.clear()
    }
}
