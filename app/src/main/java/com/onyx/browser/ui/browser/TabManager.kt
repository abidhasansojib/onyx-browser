package com.onyx.browser.ui.browser

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.view.PixelCopy
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebViewDatabase
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

    private val MULTI_PART_TLD_PARTS = setOf(
        "co", "com", "org", "net", "gov", "edu", "ac", "ne", "or", "go", "mil", "nom"
    )

    private fun extractRootDomain(host: String): String {
        val cleanHost = host.trim().lowercase().removePrefix("www.")
        if (cleanHost.isEmpty()) return ""
        if (cleanHost.all { it.isDigit() || it == '.' || it == ':' }) return cleanHost
        val parts = cleanHost.split('.')
        if (parts.size <= 2) return cleanHost

        val secondToLast = parts[parts.size - 2]
        val last = parts.last()
        val isMultiPartTld = last.length == 2 && secondToLast in MULTI_PART_TLD_PARTS

        return if (isMultiPartTld && parts.size >= 3) {
            parts.takeLast(3).joinToString(".")
        } else {
            parts.takeLast(2).joinToString(".")
        }
    }

    private fun getPurgeDomains(host: String): Set<String> {
        val result = linkedSetOf<String>()
        val cleanHost = host.trim().lowercase().removePrefix("www.")
        if (cleanHost.isEmpty()) return result

        result.add(cleanHost)
        result.add(host.trim().lowercase())

        val root = extractRootDomain(cleanHost)
        if (root.isNotEmpty()) {
            result.add(root)
            result.add("www.$root")
            result.add("m.$root")
            result.add("login.$root")
            result.add("auth.$root")
            result.add("accounts.$root")
        }
        return result
    }

    private fun extractCandidateUrls(tab: TabItem, webView: OnyxWebView?): List<String> {
        val urls = linkedSetOf<String>()
        if (tab.url.isNotBlank()) urls.add(tab.url)
        webView?.url?.let { if (it.isNotBlank()) urls.add(it) }
        webView?.originalUrl?.let { if (it.isNotBlank()) urls.add(it) }
        try {
            val history = webView?.copyBackForwardList()
            if (history != null) {
                for (i in 0 until history.size) {
                    val u = history.getItemAtIndex(i)?.url
                    if (!u.isNullOrBlank()) urls.add(u)
                }
            }
        } catch (_: Throwable) {}
        return urls.toList()
    }

    private fun autoclearTabData(
        candidateUrls: List<String>,
        excludedTabIds: Set<String>
    ) {
        val prefs = com.onyx.browser.data.preferences.BrowserPreferences.getInstance(context)
        if (!prefs.isCookieAutoclearOnCloseEnabled || candidateUrls.isEmpty()) return

        // Fast main-thread snapshot of URLs from remaining open tabs
        val remainingUrls = (_normalTabs.value + _incognitoTabs.value)
            .filter { it.id !in excludedTabIds }
            .flatMap { other ->
                listOfNotNull(
                    other.url.takeIf { it.isNotBlank() },
                    webViewPool[other.id]?.url?.takeIf { it.isNotBlank() }
                )
            }

        // Run entire cookie and storage purging asynchronously on Dispatchers.IO
        // This guarantees zero UI thread freeze (no ANR) and zero WebView race conditions.
        coroutineScope.launch(Dispatchers.IO) {
            try {
                // 1. Filter out non-web schemes
                val validUrls = candidateUrls.filter { u ->
                    (u.startsWith("http://", ignoreCase = true) || u.startsWith("https://", ignoreCase = true)) &&
                        !u.startsWith("file://", ignoreCase = true) &&
                        !u.startsWith("content://", ignoreCase = true) &&
                        !u.startsWith("onyx://", ignoreCase = true) &&
                        !u.startsWith("about:", ignoreCase = true) &&
                        !u.startsWith("data:", ignoreCase = true)
                }
                if (validUrls.isEmpty()) return@launch

                // 2. Extract distinct hosts and root domains to purge
                val hostsToPurge = mutableSetOf<String>()
                val rootsToPurge = mutableSetOf<String>()

                for (urlStr in validUrls) {
                    val uri = try { Uri.parse(urlStr) } catch (_: Throwable) { null } ?: continue
                    val host = uri.host?.lowercase() ?: continue
                    val root = extractRootDomain(host)

                    // Check if this website is still open in another remaining tab
                    val stillOpen = remainingUrls.any { candidate ->
                        val otherUri = try { Uri.parse(candidate) } catch (_: Throwable) { null }
                        val otherHost = otherUri?.host?.lowercase() ?: return@any false
                        otherHost == host || (root.isNotEmpty() && extractRootDomain(otherHost) == root)
                    }

                    if (!stillOpen) {
                        hostsToPurge.add(host)
                        if (root.isNotEmpty()) rootsToPurge.add(root)
                    }
                }

                if (hostsToPurge.isEmpty() && rootsToPurge.isEmpty()) return@launch

                // 3. Build target purge domains
                val allPurgeDomains = linkedSetOf<String>()
                for (h in hostsToPurge) {
                    allPurgeDomains.addAll(getPurgeDomains(h))
                }
                for (r in rootsToPurge) {
                    allPurgeDomains.addAll(getPurgeDomains(r))
                }
                if (allPurgeDomains.isEmpty()) return@launch

                // 4. Delete WebStorage on Main Thread (WebStorage requires UI thread)
                try {
                    withContext(Dispatchers.Main) {
                        val webStorage = WebStorage.getInstance()
                        for (domain in allPurgeDomains) {
                            try { webStorage.deleteOrigin("https://$domain") } catch (_: Throwable) {}
                            try { webStorage.deleteOrigin("http://$domain") } catch (_: Throwable) {}
                        }
                    }
                } catch (_: Throwable) {}

                // 5. Purge CookieManager
                val cookieManager = CookieManager.getInstance()
                val cookieNames = mutableSetOf<String>()

                // Collect existing cookies across target domains
                for (domain in allPurgeDomains) {
                    for (scheme in listOf("https://", "http://")) {
                        val cookieHeader = cookieManager.getCookie("$scheme$domain/")
                        if (!cookieHeader.isNullOrBlank()) {
                            cookieHeader.split(";").forEach { part ->
                                val name = part.substringBefore("=").trim()
                                if (name.isNotEmpty()) {
                                    cookieNames.add(name)
                                }
                            }
                        }
                    }
                }

                // Add well-known auth/session cookie names to ensure guaranteed logout
                if (allPurgeDomains.any { it.contains("facebook") || it.contains("fb") }) {
                    cookieNames.addAll(listOf(
                        "c_user", "xs", "datr", "sb", "fr", "wd", "presence", "spin", "act",
                        "m_pixel_ratio", "locale", "dpr", "fbl_st", "fbl_cs", "fbl_ci"
                    ))
                }
                if (allPurgeDomains.any { it.contains("github") }) {
                    cookieNames.addAll(listOf(
                        "user_session", "__Host-user_session_same_site", "logged_in", "dotcom_user",
                        "_gh_sess", "has_recent_activity", "tz", "_octo"
                    ))
                }
                if (allPurgeDomains.any { it.contains("google") }) {
                    cookieNames.addAll(listOf(
                        "SID", "HSID", "SSID", "APISID", "SAPISID", "LOGIN_INFO",
                        "__Secure-1PSID", "__Secure-3PSID", "__Secure-1PAPISID", "__Secure-3PAPISID",
                        "__Secure-1PSIDTS", "__Secure-3PSIDTS", "__Secure-1PSIDCC", "__Secure-3PSIDCC",
                        "OSID", "__Secure-OSID"
                    ))
                }
                if (allPurgeDomains.any { it.contains("twitter") || it.contains("x.com") }) {
                    cookieNames.addAll(listOf("auth_token", "ct0", "kdt", "twid", "remember_checked_on"))
                }
                if (allPurgeDomains.any { it.contains("reddit") }) {
                    cookieNames.addAll(listOf("reddit_session", "token_v2", "session", "session_tracker"))
                }
                cookieNames.addAll(listOf("session", "sessionid", "session_id", "token", "auth", "jwt", "access_token", "refresh_token"))

                // Target paths
                val targetPaths = mutableSetOf("/")
                for (urlStr in validUrls) {
                    try {
                        val path = Uri.parse(urlStr).path
                        if (!path.isNullOrBlank() && path != "/") {
                            targetPaths.add(path)
                        }
                    } catch (_: Throwable) {}
                }

                val expiry = "expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0"

                for (domain in allPurgeDomains) {
                    val httpsUrl = "https://$domain/"
                    for (cookieName in cookieNames) {
                        for (path in targetPaths) {
                            val pathAttr = "; path=$path"
                            // HTTPS Domain-level and host-only cookies
                            cookieManager.setCookie(httpsUrl, "$cookieName=; $expiry$pathAttr; domain=.$domain; Secure")
                            cookieManager.setCookie(httpsUrl, "$cookieName=; $expiry$pathAttr; Secure")
                        }
                    }
                }

                cookieManager.flush()

                // 6. Clear HTTP authentication credentials and form data
                try {
                    val db = WebViewDatabase.getInstance(context)
                    db.clearHttpAuthUsernamePassword()
                    db.clearFormData()
                } catch (_: Throwable) {}

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

        // Retrieve webview before destruction and extract candidate URLs
        val webView = webViewPool.remove(tab.id)
        val candidateUrls = extractCandidateUrls(tab, webView)
        autoclearTabData(candidateUrls, setOf(tab.id))

        // Cleanup any temporary preview files associated with this tab
        com.onyx.browser.web.LocalFileLoader.cleanupTabPreviews(context, tab.id)

        // If the closed tab was currently playing media, stop background play & dismiss notification immediately
        com.onyx.browser.media.MediaPlaybackBridge.onTabClosed(tab.id, context)
        onTabClosedListener?.invoke(tab)

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

            val closingIds = tabsToClose.map { it.id }.toSet()
            val candidateUrls = mutableListOf<String>()
            val states = mutableListOf<ClosedTabState>()
            tabsToClose.forEachIndexed { index, tab ->
                onTabClosedListener?.invoke(tab)
                val webView = webViewPool.remove(tab.id)
                candidateUrls.addAll(extractCandidateUrls(tab, webView))
                com.onyx.browser.web.LocalFileLoader.cleanupTabPreviews(context, tab.id)
                val snapshot = snapshotCache.get(tab.id)
                val wasActive = (_activeTab.value?.id == tab.id)
                webView?.destroySafely()
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
            autoclearTabData(candidateUrls, closingIds)

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

            val closingIds = tabsToClose.map { it.id }.toSet()
            val candidateUrls = mutableListOf<String>()
            val states = mutableListOf<ClosedTabState>()
            tabsToClose.forEachIndexed { index, tab ->
                onTabClosedListener?.invoke(tab)
                val webView = webViewPool.remove(tab.id)
                candidateUrls.addAll(extractCandidateUrls(tab, webView))
                com.onyx.browser.web.LocalFileLoader.cleanupTabPreviews(context, tab.id)
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
            autoclearTabData(candidateUrls, closingIds)

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
        val closingIds = allClosing.map { it.id }.toSet()

        allClosing.forEach { onTabClosedListener?.invoke(it) }

        if (allClosing.any { it.id == com.onyx.browser.media.MediaPlaybackBridge.currentPlayingTabId }) {
            com.onyx.browser.media.MediaPlaybackBridge.resetMediaPlayback(context)
        }

        val candidateUrls = mutableListOf<String>()
        for (tab in allClosing) {
            val webView = webViewPool.remove(tab.id)
            candidateUrls.addAll(extractCandidateUrls(tab, webView))
            webView?.destroySafely()
            deleteTabState(tab.id)
            snapshotCache.remove(tab.id)
            try { getThumbnailFile(tab.id).delete() } catch (_: Exception) {}
        }
        autoclearTabData(candidateUrls, closingIds)

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
