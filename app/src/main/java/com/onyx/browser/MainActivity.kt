package com.onyx.browser

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.app.SearchManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.util.Rational
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.view.ViewCompat
import android.content.ClipData
import android.content.ClipboardManager
import java.io.File
import com.onyx.browser.ui.home.AdjustQuickActionsBottomSheet
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.onyx.browser.data.local.AppDatabase
import com.onyx.browser.data.model.SearchEngine
import com.onyx.browser.data.model.ShortcutItem
import com.onyx.browser.data.model.TabItem
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.data.search.SearchSuggestionRepository
import com.onyx.browser.databinding.ActivityMainBinding
import com.onyx.browser.ui.bookmarks.BookmarksActivity
import com.onyx.browser.ui.browser.FindInPageController
import com.onyx.browser.ui.browser.PageExportManager
import com.onyx.browser.ui.browser.PipController
import com.onyx.browser.ui.browser.SearchController
import com.onyx.browser.ui.browser.TabManager
import com.onyx.browser.ui.common.SearchEnginePickerDialog
import com.onyx.browser.ui.common.SearchEnginePopupMenu
import com.onyx.browser.ui.common.SearchEngineIconHelper
import com.onyx.browser.ui.downloads.DownloadsActivity
import com.onyx.browser.ui.history.HistoryActivity
import com.onyx.browser.ui.home.EditShortcutDialog
import com.onyx.browser.ui.home.ManageShortcutsBottomSheet
import com.onyx.browser.ui.home.ShortcutsAdapter
import com.onyx.browser.ui.menu.MenuBottomSheetDialogFragment
import com.onyx.browser.ui.menu.ContextMenuBottomSheet
import com.onyx.browser.ui.menu.LanguageSelectionDialog
import com.onyx.browser.ui.tabs.TabSwitcherBottomSheet
import com.onyx.browser.web.DownloadHandler
import com.onyx.browser.web.LocalFileLoader
import com.onyx.browser.web.OnyxWebChromeClient
import com.onyx.browser.web.OnyxWebView
import com.onyx.browser.web.OnyxWebViewClient
import com.onyx.browser.data.filter.FilterListManager
import com.onyx.browser.download.DownloadNotificationHelper
import com.onyx.browser.media.MediaPlaybackBridge
import com.onyx.browser.media.MediaPlaybackService
import com.onyx.browser.web.DevToolsManager
import com.onyx.browser.web.MediaPlaybackManager
import com.onyx.browser.web.translate.PageTranslateManager
import com.onyx.browser.web.TabActionCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity(), TabActionCallback {

    private lateinit var binding: ActivityMainBinding
    lateinit var tabManager: TabManager
    private lateinit var preferences: BrowserPreferences
    private lateinit var pageExportManager: PageExportManager
    private lateinit var findInPageController: FindInPageController
    private lateinit var searchController: SearchController

    fun getActiveWebView(): OnyxWebView? = if (::tabManager.isInitialized) tabManager.getActiveWebView() else null
    private lateinit var shortcutsAdapter: ShortcutsAdapter

    lateinit var pipController: PipController
    val customVideoView: View?
        get() = if (::pipController.isInitialized) pipController.customVideoView else null
    val customViewCallback: WebChromeClient.CustomViewCallback?
        get() = if (::pipController.isInitialized) pipController.customViewCallback else null
    var justExitedPip: Boolean
        get() = if (::pipController.isInitialized) pipController.justExitedPip else false
        set(value) {
            if (::pipController.isInitialized) pipController.justExitedPip = value
        }
    var isCurrentlyInPip: Boolean
        get() = MediaPlaybackBridge.isCurrentlyInPip
        set(value) {
            MediaPlaybackBridge.isCurrentlyInPip = value
            if (::pipController.isInitialized) pipController.isCurrentlyInPip = value
        }
    private var currentDisplayedTabId: String? = null
    private var isTabsRestored = false
    private var pendingIntent: Intent? = null
    private var lastThemeMode: Int = -1
    private var lastNightMode: Int = -1

    companion object {
        var currentInstance: java.lang.ref.WeakReference<MainActivity>? = null
        const val ACTION_PIP_PLAY_PAUSE = "com.onyx.browser.action.PIP_PLAY_PAUSE"
        const val ACTION_PIP_REWIND = "com.onyx.browser.action.PIP_REWIND"
        const val ACTION_PIP_FORWARD = "com.onyx.browser.action.PIP_FORWARD"
        const val ACTION_WIDGET_SEARCH = "com.onyx.browser.action.WIDGET_SEARCH"
        const val ACTION_WIDGET_VOICE_SEARCH = "com.onyx.browser.action.WIDGET_VOICE_SEARCH"
        const val ACTION_WIDGET_INCOGNITO_SEARCH = "com.onyx.browser.action.WIDGET_INCOGNITO_SEARCH"
        const val EXTRA_FROM_DOWNLOADS = "com.onyx.browser.extra.FROM_DOWNLOADS"
    }

    private fun getMediaTargetWebView(): OnyxWebView? {
        val playingWv = MediaPlaybackBridge.currentPlayingWebView?.get() as? OnyxWebView
        if (playingWv != null) return playingWv
        val playingId = MediaPlaybackBridge.currentPlayingTabId
        if (!playingId.isNullOrBlank()) {
            val wv = tabManager.getWebView(playingId)
            if (wv != null) return wv
        }
        return tabManager.getActiveWebView()
    }


    private var lastNavBarBottomInset: Int = 0
    // Permission Launchers
    private var pendingStorageAction: (() -> Unit)? = null
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            pendingStorageAction?.invoke()
        } else {
            Toast.makeText(this, "Storage permission required to download files", Toast.LENGTH_SHORT).show()
        }
        pendingStorageAction = null
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(this, "Download notifications disabled", Toast.LENGTH_SHORT).show()
        }
    }

    private val voiceSearchMicLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startVoiceSearchIntent()
        } else {
            Toast.makeText(this, "Microphone permission required for voice search", Toast.LENGTH_SHORT).show()
        }
    }

    private var pendingGeoOrigin: String? = null
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fine = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarse = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        val granted = fine || coarse
        pendingGeoCallback?.invoke(pendingGeoOrigin, granted, false)
        pendingGeoOrigin = null
        pendingGeoCallback = null
    }

    private var pendingWebMediaRequest: PermissionRequest? = null
    private val webMediaPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val micGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
        val camGranted = permissions[Manifest.permission.CAMERA] == true
        val grantedList = mutableListOf<String>()
        if (micGranted) grantedList.add(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
        if (camGranted) grantedList.add(PermissionRequest.RESOURCE_VIDEO_CAPTURE)

        if (grantedList.isNotEmpty()) {
            pendingWebMediaRequest?.grant(grantedList.toTypedArray())
        } else {
            pendingWebMediaRequest?.deny()
        }
        pendingWebMediaRequest = null
    }

    private fun handleGeolocationPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fine || coarse) {
            callback?.invoke(origin, true, false)
        } else {
            pendingGeoOrigin = origin
            pendingGeoCallback = callback
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun handleWebPermissionRequest(request: PermissionRequest?) {
        if (request == null) return
        val resources = request.resources
        val needed = mutableListOf<String>()
        if (resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.RECORD_AUDIO)
            }
        }
        if (resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.CAMERA)
            }
        }

        if (needed.isEmpty()) {
            request.grant(resources)
        } else {
            pendingWebMediaRequest = request
            webMediaPermissionLauncher.launch(needed.toTypedArray())
        }
    }

    // Voice Search Result Launcher
    private val voiceSearchLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            if (!matches.isNullOrEmpty()) {
                val query = matches[0]
                binding.etUrl.setText(query)
                performSearchOrLoad(query)
            }
        }
    }

    // Bookmarks URL Result Launcher
    private val bookmarksLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val url = result.data?.getStringExtra(BookmarksActivity.EXTRA_URL)
            if (!url.isNullOrBlank()) {
                performSearchOrLoad(url)
            }
        }
    }

    // History URL Result Launcher
    private val historyLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val url = result.data?.getStringExtra(HistoryActivity.EXTRA_URL)
            if (!url.isNullOrBlank()) {
                performSearchOrLoad(url)
            }
        }
    }

    fun openBookmarks() {
        bookmarksLauncher.launch(Intent(this, BookmarksActivity::class.java))
    }

    fun openHistory() {
        historyLauncher.launch(Intent(this, HistoryActivity::class.java))
    }

    fun openDownloads() {
        startActivity(Intent(this, DownloadsActivity::class.java))
    }

    // QR Scanner Result Launcher
    private val qrScannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val value = result.data?.getStringExtra(com.onyx.browser.ui.qr.QrScannerActivity.RESULT_QR_VALUE)
            if (!value.isNullOrBlank()) {
                performSearchOrLoad(value)
            }
        }
    }

    private val previewTabsFromDownloads = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val previewTabsFromExternal = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val localFileViewTabs = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    fun isLocalFileView(tab: TabItem?): Boolean {
        if (tab == null) return false
        val activeWv = tabManager.getActiveWebView()
        val currentWvUrl = activeWv?.url
        if (currentWvUrl != null && (currentWvUrl.startsWith("http://", ignoreCase = true) || currentWvUrl.startsWith("https://", ignoreCase = true))) {
            return false
        }
        val tabUrl = tab.url
        if (tabUrl.startsWith("http://", ignoreCase = true) || tabUrl.startsWith("https://", ignoreCase = true)) {
            return false
        }
        return localFileViewTabs.contains(tab.id) ||
                previewTabsFromDownloads.contains(tab.id) ||
                previewTabsFromExternal.contains(tab.id) ||
                com.onyx.browser.web.LocalFileLoader.isLocalFile(this, tabUrl) ||
                com.onyx.browser.web.LocalFileLoader.isPreviewUrl(tabUrl) ||
                (currentWvUrl != null && (com.onyx.browser.web.LocalFileLoader.isLocalFile(this, currentWvUrl) || com.onyx.browser.web.LocalFileLoader.isPreviewUrl(currentWvUrl)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentInstance = java.lang.ref.WeakReference(this)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        )
        enforceHighRefreshRate()
        
        preferences = BrowserPreferences.getInstance(this)
        preferences.applyTheme()
        lastThemeMode = preferences.themeMode
        lastNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        com.onyx.browser.download.OnyxDownloadManager.init(this)
        pageExportManager = PageExportManager(this, lifecycleScope)
        tabManager = TabManager(this, lifecycleScope)
        tabManager.onTabClosedListener = { tab ->
            previewTabsFromDownloads.remove(tab.id)
            previewTabsFromExternal.remove(tab.id)
            localFileViewTabs.remove(tab.id)
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Handle Scroll to Top FAB
        binding.fabScrollToTop.setOnClickListener {
            val wv = tabManager.getActiveWebView()
            wv?.scrollTo(0, 0)
            binding.fabScrollToTop.visibility = android.view.View.GONE
        }
        
        // Handle edge-to-edge system bars (Status Bar & Gesture Nav Bar)
        ViewCompat.setOnApplyWindowInsetsListener(binding.mainRoot) { _, windowInsets ->
            val statusBarInsets = windowInsets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val navBarInsets = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            lastNavBarBottomInset = navBarInsets.bottom

            val bottomPaddingPx = (8 * resources.displayMetrics.density).toInt()
            val topPaddingPx = (4 * resources.displayMetrics.density).toInt()
            binding.topBar.setPadding(
                binding.topBar.paddingLeft,
                statusBarInsets.top + topPaddingPx,
                binding.topBar.paddingRight,
                bottomPaddingPx
            )

            binding.contentContainer.setPadding(
                binding.contentContainer.paddingLeft,
                binding.contentContainer.paddingTop,
                binding.contentContainer.paddingRight,
                navBarInsets.bottom
            )
            windowInsets
        }
        
        binding.swipeRefreshLayout.setOnRefreshListener { 
            val activeTab = tabManager.activeTab.value
            val wv = tabManager.getActiveWebView()
            val failingUrl = wv?.currentSyntheticState?.failingUrl ?: wv?.lastFailingUrl
            if (!failingUrl.isNullOrBlank() && wv != null) {
                wv.clearSyntheticState()
                wv.loadUrl(failingUrl)
                binding.swipeRefreshLayout.isRefreshing = false
            } else if (activeTab != null && wv != null && LocalFileLoader.isLocalFile(this, activeTab.url)) {
                LocalFileLoader.loadLocalFile(this, wv, activeTab.url)
                binding.swipeRefreshLayout.isRefreshing = false
            } else {
                wv?.reload() 
            }
        }
        binding.swipeRefreshLayout.setOnChildScrollUpCallback { _, _ ->
            // Block pull-to-refresh on homepage and when webview can't scroll up
            if (binding.homeLayout.root.visibility == View.VISIBLE) return@setOnChildScrollUpCallback true
            val webView = tabManager.getActiveWebView() ?: return@setOnChildScrollUpCallback true
            if (webView.canScrollVertically(-1) || webView.scrollY > 0) return@setOnChildScrollUpCallback true

            // Block pull-to-refresh on immersive reels, shorts, and video feeds
            val url = (webView.url ?: "").lowercase()
            if (isReelOrFeedUrl(url)) return@setOnChildScrollUpCallback true

            // Block pull-to-refresh if inner container is scrolled, overscroll-behavior is none/contain,
            // or body is overflow-hidden
            if (!webView.touchBridge.isPullToRefreshAllowed) return@setOnChildScrollUpCallback true

            false
        }

        val database = AppDatabase.getInstance(this)
        val suggestionRepository = SearchSuggestionRepository(database.historyDao(), database.bookmarkDao())
        searchController = SearchController(
            activity = this,
            binding = binding,
            preferences = preferences,
            suggestionRepository = suggestionRepository,
            coroutineScope = lifecycleScope,
            getActivePageUrl = { getActivePageUrl() },
            getActiveTab = { tabManager.activeTab.value },
            performSearchOrLoad = { target -> performSearchOrLoad(target) },
            updateAddressBarDisplay = { url -> updateAddressBarDisplay(url) },
            showSoftKeyboard = { showSoftKeyboard() },
            hideSoftKeyboard = { hideSoftKeyboard() },
            isLikelyUrl = { url -> isLikelyUrl(url) }
        )
        searchController.setup()

        setupTopToolbar()
        findInPageController = FindInPageController(this, binding) { tabManager.getActiveWebView() }
        findInPageController.setup()
        setupTranslateBar()
        setupHomepageInteractions()
        setupBackNavigation()
        setupMediaPlaybackListener()

        pipController = PipController(
            activity = this,
            binding = binding,
            preferences = preferences,
            getActiveWebView = { tabManager.getActiveWebView() },
            getMediaTargetWebView = { getMediaTargetWebView() },
            onHideUiForPip = {
                binding.contentContainer.setPadding(0, 0, 0, 0)
                binding.topBar.visibility = View.GONE
                binding.topBarDivider.visibility = View.GONE
                binding.homeLayout.root.visibility = View.GONE
                binding.fullscreenControlsOverlay.visibility = View.GONE
                binding.progressBar.visibility = View.GONE
                findInPageController.hide()
                binding.searchOverlay.visibility = View.GONE
            },
            onRestoreUiFromPip = { isCustomVideo ->
                binding.contentContainer.setPadding(0, 0, 0, lastNavBarBottomInset)
                if (!isCustomVideo) {
                    val activeTab = tabManager.activeTab.value
                    if (activeTab != null && activeTab.url.isNotBlank() &&
                        !activeTab.url.startsWith("onyx://") && !activeTab.url.startsWith("about:")) {
                        binding.homeLayout.root.visibility = View.GONE
                        binding.webViewContainer.visibility = View.VISIBLE
                    } else {
                        binding.homeLayout.root.visibility = View.VISIBLE
                        binding.webViewContainer.visibility = View.GONE
                    }
                }
            }
        )
        pipController.registerReceiver()

        checkNotificationPermissionForDownloads()

        lifecycleScope.launch {
            tabManager.restoreTabs()
            tabManager.hibernateIdleTabs()
            isTabsRestored = true
            observeTabs()
            val intentToHandle = pendingIntent ?: intent
            pendingIntent = null
            val isActionIntent = intentToHandle?.action?.startsWith("com.onyx.browser.action.") == true
            if (savedInstanceState == null || intentToHandle != intent || isActionIntent) {
                handleIncomingIntent(intentToHandle)
            }
        }

        // Check and auto-update filter lists in background (every 24h by default)
        lifecycleScope.launch(Dispatchers.IO) {
            FilterListManager.checkAndAutoUpdateFilters(applicationContext)
        }
    }

    private fun setupTopToolbar() {
        // Back Button in Search Mode (returns to page or homepage)
        binding.btnSearchBack.setOnClickListener {
            searchController.exitSearchMode()
        }

        // Search Engine Icon
        updateSearchEngineIcon()
        binding.btnSearchEngine.setOnClickListener {
            val popup = SearchEnginePopupMenu(
                context = this,
                currentEngine = preferences.searchEngine,
                onEngineSelected = { engine ->
                    preferences.searchEngine = engine
                    updateSearchEngineIcon()
                    com.onyx.browser.ui.widget.SearchWidgetProvider.updateAllWidgets(this)
                    val currentQuery = binding.etUrl.text?.toString()?.trim() ?: ""
                    if (searchController.isSearchMode && currentQuery.isNotEmpty()) {
                        searchController.fetchSearchSuggestions(currentQuery)
                    }
                }
            )
            popup.show(binding.btnSearchEngine)
        }

        // Home / Back Button
        binding.btnHome.setOnClickListener {
            val currentTab = tabManager.activeTab.value
            if (isLocalFileView(currentTab)) {
                onBackPressedDispatcher.onBackPressed()
            } else {
                tabManager.updateActiveTab("", "New Tab")
                showHomeScreen()
            }
        }

        // Address Bar Focus & Search Mode
        binding.etUrl.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && !searchController.isSearchMode) {
                searchController.enterSearchMode()
            }
        }

        binding.etUrl.setOnClickListener {
            if (!searchController.isSearchMode) {
                searchController.enterSearchMode()
            }
        }

        binding.searchBarContainer.setOnClickListener {
            if (!searchController.isSearchMode) {
                searchController.enterSearchMode()
            }
        }

        binding.etUrl.doAfterTextChanged { text ->
            if (searchController.isSearchMode) {
                searchController.onQueryTextChanged(text)
            }
        }

        binding.btnClearUrl.setOnClickListener {
            binding.etUrl.setText("")
        }

        binding.btnQrScanner.setOnClickListener {
            startQrScanner()
        }

        binding.btnVoiceSearch.setOnClickListener {
            launchVoiceSearch()
        }

        binding.etUrl.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                val input = binding.etUrl.text?.toString()?.trim() ?: ""
                if (input.isNotEmpty()) {
                    performSearchOrLoad(input)
                    hideSoftKeyboard()
                    binding.etUrl.clearFocus()
                }
                true
            } else {
                false
            }
        }

        // Tab Switcher Button
        binding.btnTabSwitcher.setOnClickListener {
            val activeTabId = tabManager.activeTab.value?.id
            val activeWebView = tabManager.getActiveWebView()

            val sheet = TabSwitcherBottomSheet(
                tabManager = tabManager,
                coroutineScope = lifecycleScope,
                onTabSelected = { tab ->
                    displayTab(tab)
                },
                onNewTabRequested = { isIncognito ->
                    val newTab = tabManager.createNewTab(isIncognito = isIncognito)
                    displayTab(newTab)
                },
                onSearchRequested = {
                    searchController.enterSearchMode()
                }
            )

            if (activeTabId != null && activeWebView != null) {
                tabManager.captureTabSnapshot(activeTabId, activeWebView) {
                    runOnUiThread {
                        if (sheet.isAdded && !sheet.isHidden) {
                            sheet.refreshTabsList()
                        }
                    }
                }
            }

            sheet.show(supportFragmentManager, TabSwitcherBottomSheet.TAG)
        }

        // Three-Dot Menu Button
        binding.btnMenu.setOnClickListener {
            val activeTab = tabManager.activeTab.value
            val activeWebView = tabManager.getActiveWebView()
            val isHome = (binding.homeLayout.root.visibility == View.VISIBLE) || activeTab?.url.isNullOrBlank()

            val sheet = MenuBottomSheetDialogFragment().apply {
                isHomePage = isHome
                currentUrl = activeTab?.url ?: ""
                currentTitle = activeTab?.title ?: ""
                isDesktopSiteEnabled = activeWebView?.isDesktopModeEnabledForCurrentPage() ?: false
                onDesktopSiteToggled = { _ ->
                    // Toggle desktop mode scoped to the current page's domain only.
                    // Other domains and other tabs remain unaffected.
                    activeWebView?.toggleDesktopModeForPage(activeTab?.url ?: "")
                }
                onFindInPageClicked = {
                    findInPageController.show()
                }
                onTranslateClicked = { langCode ->
                    translateCurrentPage(langCode)
                }
                onSavePageClicked = {
                    showSavePageDialog()
                }
                onAddToHomeScreenClicked = {
                    addCurrentPageToHomeScreen()
                }
                onDeveloperToolsClicked = {
                    injectDeveloperTools()
                }
                onBlockElementClicked = {
                    startElementBlocker()
                }
                onSiteShieldWhitelistChanged = { _ ->
                    activeWebView?.reload()
                }

            }
            sheet.show(supportFragmentManager, MenuBottomSheetDialogFragment.TAG)
        }
    }

    private fun setupHomepageInteractions() {
        val home = binding.homeLayout

        // Dynamic Quick Actions (Clean Non-Box Circular UI, Reorderable via Drag or Dialog, Add in 4th)
        // Dynamic Shortcuts RecyclerView
        shortcutsAdapter = ShortcutsAdapter(
            onShortcutClick = { shortcut ->
                if (shortcut.id == ShortcutItem.ID_ADD_SHORTCUT || shortcut.url == ShortcutItem.URL_ADD_SHORTCUT) {
                    val sheet = ManageShortcutsBottomSheet()
                    sheet.show(supportFragmentManager, ManageShortcutsBottomSheet.TAG)
                } else {
                    performSearchOrLoad(shortcut.url)
                }
            }
        )

        val shortcutSpanCount = if (resources.configuration.screenWidthDp >= 600) 6 else 4
        home.rvShortcuts.layoutManager = GridLayoutManager(this, shortcutSpanCount)
        home.rvShortcuts.adapter = shortcutsAdapter

        var hasDragged = false
        var draggedShortcut: ShortcutItem? = null

        // Drag-and-drop reordering with ItemTouchHelper
        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.START or ItemTouchHelper.END,
            0
        ) {
            override fun getDragDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                val pos = viewHolder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION || pos >= shortcutsAdapter.getItems().size) {
                    return 0
                }
                return super.getDragDirs(recyclerView, viewHolder)
            }

            override fun canDropOver(
                recyclerView: RecyclerView,
                current: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val targetPos = target.bindingAdapterPosition
                return targetPos != RecyclerView.NO_POSITION && targetPos < shortcutsAdapter.getItems().size
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from != RecyclerView.NO_POSITION && to != RecyclerView.NO_POSITION) {
                    hasDragged = true
                    return shortcutsAdapter.onItemMove(from, to)
                }
                return false
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    val pos = viewHolder?.bindingAdapterPosition ?: RecyclerView.NO_POSITION
                    if (pos != RecyclerView.NO_POSITION && pos < shortcutsAdapter.getItems().size) {
                        draggedShortcut = shortcutsAdapter.getItems()[pos]
                    }
                    viewHolder?.itemView?.animate()?.scaleX(1.10f)?.scaleY(1.10f)?.setDuration(150)?.start()
                }
            }

            override fun clearView(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                if (hasDragged) {
                    preferences.saveShortcuts(shortcutsAdapter.getItems())
                }
                draggedShortcut = null
                hasDragged = false
            }
        })
        touchHelper.attachToRecyclerView(home.rvShortcuts)

        // Observe shortcuts flow
        lifecycleScope.launch {
            preferences.shortcutsFlow.collectLatest { shortcuts ->
                shortcutsAdapter.submitList(shortcuts)
            }
        }
    }

    private fun observeTabs() {
        lifecycleScope.launch {
            tabManager.activeTab.collectLatest { activeTab ->
                if (activeTab != null) {
                    displayTab(activeTab)
                }
            }
        }

        lifecycleScope.launch {
            tabManager.normalTabs.collectLatest {
                updateTabBadgeCount()
            }
        }

        lifecycleScope.launch {
            tabManager.incognitoTabs.collectLatest { tabs ->
                updateTabBadgeCount()
                // Update (or dismiss) incognito persistent notification
                com.onyx.browser.incognito.IncognitoNotificationHelper.updateNotification(this@MainActivity, tabs.size)
            }
        }
    }

    private fun displayTab(tab: TabItem) {
        updateTabBadgeCount()

        val previousTabId = currentDisplayedTabId
        val tabChanged = (previousTabId != tab.id)
        if (tabChanged) {
            if (previousTabId != null) {
                val outgoingWebView = tabManager.getWebView(previousTabId)
                if (outgoingWebView != null) {
                    outgoingWebView.onPause()
                    tabManager.captureTabSnapshot(previousTabId, outgoingWebView)
                    tabManager.saveTabState(previousTabId, outgoingWebView)
                }
            }
            searchController.exitSearchMode()
            hideTranslateBar(restoreOriginal = false)
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        currentDisplayedTabId = tab.id

        if (tab.url.isBlank() || tab.url == "about:blank") {
            showHomeScreen()
        } else {
            showWebView(tab, reloadIfChanged = tabChanged)
        }
    }

    fun showHomeScreen() {
        if (customVideoView == null) {
            binding.topBar.visibility = View.VISIBLE
            binding.topBarDivider.visibility = View.GONE
        }
        binding.homeLayout.root.visibility = View.VISIBLE
        binding.webViewContainer.visibility = View.GONE
        binding.etUrl.setText("")
        binding.ivSslLock.visibility = View.GONE
        binding.progressBar.visibility = View.GONE
        binding.swipeRefreshLayout.isEnabled = false
        binding.btnHome.setImageResource(R.drawable.ic_home)
        binding.btnHome.contentDescription = getString(R.string.home)
        hideTranslateBar(restoreOriginal = false)

        val isIncognito = tabManager.activeTab.value?.isIncognito == true
        updateIncognitoUI(isIncognito)

        val child = if (binding.webViewContainer.childCount > 0) binding.webViewContainer.getChildAt(0) as? OnyxWebView else null
        child?.onPause()
        binding.webViewContainer.removeAllViews()
    }

    private fun updateIncognitoUI(isIncognito: Boolean) {
        val home = binding.homeLayout
        if (isIncognito) {
            home.ivBrandLogo.visibility = View.GONE
            home.tvTagline.visibility = View.GONE
            home.incognitoHeader.visibility = View.VISIBLE
            binding.ivIncognitoIndicator.visibility = View.VISIBLE
            binding.etUrl.hint = getString(R.string.search_privately)
        } else {
            home.ivBrandLogo.visibility = View.VISIBLE
            home.tvTagline.visibility = View.VISIBLE
            home.incognitoHeader.visibility = View.GONE
            binding.ivIncognitoIndicator.visibility = View.GONE
            binding.etUrl.hint = getString(R.string.search_or_type_url)
        }
    }

    private fun showWebView(tab: TabItem, forceUrl: String? = null, reloadIfChanged: Boolean = false) {
        if (customVideoView == null) {
            binding.topBar.visibility = View.VISIBLE
            binding.topBarDivider.visibility = View.GONE
        }
        binding.homeLayout.root.visibility = View.GONE
        binding.webViewContainer.visibility = View.VISIBLE
        binding.swipeRefreshLayout.isEnabled = true

        updateIncognitoUI(tab.isIncognito)

        val webView = tabManager.getOrCreateWebView(tab)
        attachWebViewToContainer(webView)

        val targetUrl = forceUrl ?: tab.url
        if (targetUrl.isNotBlank() && targetUrl != "about:blank") {
            val isCurrentLoaded = !webView.url.isNullOrBlank() && webView.url != "about:blank"
            val needsLoad = forceUrl != null || !isCurrentLoaded || (reloadIfChanged && webView.url != targetUrl)
            if (needsLoad) {
                try {
                    if (LocalFileLoader.isLocalFile(this, targetUrl)) {
                        LocalFileLoader.loadLocalFile(this, webView, targetUrl) { title ->
                            tabManager.updateActiveTab(targetUrl, title)
                        }
                    } else {
                        var restored = false
                        if (forceUrl == null && !tab.isIncognito && (webView.url.isNullOrBlank() || webView.url == "about:blank")) {
                            restored = tabManager.restoreTabState(tab.id, webView)
                        }
                        if (!restored) {
                            webView.stopLoading()
                            webView.loadUrl(targetUrl)
                        }
                    }
                } catch (t: Throwable) {
                    Toast.makeText(this, "Failed to load URL: ${t.message}", Toast.LENGTH_SHORT).show()
                }
            }
            updateAddressBarDisplay(if (LocalFileLoader.isLocalFile(this, targetUrl)) targetUrl else (webView.url ?: targetUrl))
            webView.evaluateJavascript(com.onyx.browser.web.MediaPlaybackManager.mediaMonitorScript, null)
        } else {
            showHomeScreen()
        }
    }

    private fun attachWebViewToContainer(webView: OnyxWebView) {
        if (webView.parent != binding.webViewContainer) {
            val currentChild = if (binding.webViewContainer.childCount > 0) {
                binding.webViewContainer.getChildAt(0) as? OnyxWebView
            } else null
            currentChild?.onPause()

            (webView.parent as? ViewGroup)?.removeView(webView)
            binding.webViewContainer.removeAllViews()
            binding.webViewContainer.addView(
                webView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )

            setupWebViewClients(webView)
            webView.onResume()
        }
    }

    private fun setupWebViewClients(webView: OnyxWebView) {
        val tabId = webView.tabId
        webView.onScrollChangedCallback = { _, t, _, oldt ->
            if (preferences.isScrollToTopEnabled && currentDisplayedTabId == tabId && tabManager.activeTab.value?.id == tabId) {
                if (t > 500 && t > oldt) {
                    binding.fabScrollToTop.visibility = android.view.View.VISIBLE
                } else if (t < 10) {
                    binding.fabScrollToTop.visibility = android.view.View.GONE
                }
            }
        }

        val client = OnyxWebViewClient(
            context = this,
            coroutineScope = lifecycleScope,
            onUrlChanged = { newUrl ->
                val isPreview = LocalFileLoader.isPreviewUrl(newUrl)
                val currentTab = tabManager.getTabById(tabId)
                val realTabUrl = currentTab?.url ?: ""
                val isLocalView = isLocalFileView(currentTab)
                val isDirectoryUrl = newUrl.endsWith("/") || (newUrl.startsWith("file://") && try { File(Uri.parse(newUrl).path ?: "").isDirectory } catch (_: Exception) { false })
                val cleanUrl = if (isPreview) {
                    realTabUrl
                } else if (isLocalView && isDirectoryUrl) {
                    realTabUrl
                } else if (newUrl.startsWith("data:") || newUrl.startsWith("file:///android_asset/") || newUrl.startsWith("file:///android_res/")) {
                    webView.currentSyntheticState?.failingUrl ?: realTabUrl
                } else {
                    newUrl
                }
                if (cleanUrl.isNotBlank() && !cleanUrl.startsWith("about:blank", ignoreCase = true) && !cleanUrl.startsWith("data:") && !cleanUrl.startsWith("file:///android_asset/") && !cleanUrl.startsWith("file:///android_res/")) {
                    val finalTitle = webView.title?.takeIf { it.isNotBlank() }
                        ?: if (isPreview) currentTab?.title?.takeIf { it.isNotBlank() } ?: "Web Archive" else cleanUrl
                    tabManager.updateTabUrlAndTitle(tabId, cleanUrl, finalTitle)
                    if (currentDisplayedTabId == tabId && tabManager.activeTab.value?.id == tabId) {
                        updateAddressBarDisplay(cleanUrl)
                        val activeTab = tabManager.activeTab.value
                        if (activeTab != null && !activeTab.isIncognito) {
                            tabManager.saveTabState(activeTab.id, webView)
                        }
                    }
                }
            },
            onPageFinishedCallback = { finishedUrl ->
                val isPreview = LocalFileLoader.isPreviewUrl(finishedUrl)
                val currentTab = tabManager.getTabById(tabId)
                val realTabUrl = currentTab?.url ?: ""
                val isLocalView = isLocalFileView(currentTab)
                val isDirectoryUrl = finishedUrl.endsWith("/") || (finishedUrl.startsWith("file://") && try { File(Uri.parse(finishedUrl).path ?: "").isDirectory } catch (_: Exception) { false })
                val cleanUrl = if (isPreview) {
                    realTabUrl
                } else if (isLocalView && isDirectoryUrl) {
                    realTabUrl
                } else if (finishedUrl.startsWith("data:") || finishedUrl.startsWith("file:///android_asset/") || finishedUrl.startsWith("file:///android_res/")) {
                    webView.currentSyntheticState?.failingUrl ?: realTabUrl
                } else {
                    finishedUrl
                }
                if (cleanUrl.isNotBlank() && !cleanUrl.startsWith("about:blank", ignoreCase = true) && !cleanUrl.startsWith("data:") && !cleanUrl.startsWith("file:///android_asset/") && !cleanUrl.startsWith("file:///android_res/")) {
                    val finalTitle = webView.title?.takeIf { it.isNotBlank() }
                        ?: if (isPreview) currentTab?.title?.takeIf { it.isNotBlank() } ?: "Web Archive" else cleanUrl
                    tabManager.updateTabUrlAndTitle(tabId, cleanUrl, finalTitle)
                    if (currentDisplayedTabId == tabId && tabManager.activeTab.value?.id == tabId) {
                        updateAddressBarDisplay(cleanUrl)
                    }
                }
                if (currentDisplayedTabId == tabId) {
                    binding.progressBar.visibility = View.GONE
                    val activeTab = tabManager.activeTab.value
                    if (activeTab != null && !activeTab.isIncognito) {
                        tabManager.saveTabState(activeTab.id, webView)
                    }
                    if (activeTab != null && (webView.url == finishedUrl || webView.currentSyntheticState != null)) {
                        webView.postDelayed({
                            if (currentDisplayedTabId == tabId) {
                                tabManager.captureTabSnapshot(activeTab.id, webView)
                            }
                        }, 400)
                    }
                }
            },
            onPageCommitVisibleCallback = { view, _ ->
                if (view is OnyxWebView && view.tabId == currentDisplayedTabId && tabManager.activeTab.value?.id == tabId) {
                    val activeTab = tabManager.activeTab.value
                    if (activeTab != null) {
                        view.postDelayed({
                            if (currentDisplayedTabId == tabId) {
                                tabManager.captureTabSnapshot(activeTab.id, view)
                            }
                        }, 300)
                    }
                }
            },
            tabActionCallback = this
        )
        client.onOpenInAppPrompt = { intent, appName, fallback ->
            if (!isFinishing && !isDestroyed) {
                val promptDialog = com.onyx.browser.ui.dialog.OpenInAppPromptDialog.newInstance(
                    intent = intent,
                    appName = appName,
                    onStayInOnyx = { fallback?.invoke() },
                    onOpenInApp = null
                )
                promptDialog.show(supportFragmentManager, com.onyx.browser.ui.dialog.OpenInAppPromptDialog.TAG)
            }
        }
        webView.webViewClient = client

        webView.webChromeClient = OnyxWebChromeClient(
            onProgressChangedCallback = { progress ->
                if (currentDisplayedTabId != tabId) return@OnyxWebChromeClient
                if (binding.progressBar.visibility != android.view.View.VISIBLE && progress < 100) {
                    binding.progressBar.visibility = android.view.View.VISIBLE
                    binding.progressBar.alpha = 1f
                    binding.progressBar.progress = 0
                }
                
                val animator = android.animation.ObjectAnimator.ofInt(binding.progressBar, "progress", binding.progressBar.progress, progress)
                animator.duration = 200
                animator.interpolator = android.view.animation.DecelerateInterpolator()
                animator.start()
                
                if (progress >= 100) {
                    binding.swipeRefreshLayout.isRefreshing = false
                    binding.progressBar.animate()
                        .alpha(0f)
                        .setStartDelay(200)
                        .setDuration(200)
                        .withEndAction {
                            binding.progressBar.visibility = android.view.View.GONE
                            binding.progressBar.progress = 0
                            binding.progressBar.alpha = 1f
                        }
                        .start()
                }
            },
            onTitleReceivedCallback = { title ->
                tabManager.updateTabUrlAndTitle(tabId, webView.url ?: "", title)
            },
            onShowCustomViewCallback = { view, callback ->
                showCustomFullscreenVideo(view, callback)
            },
            onHideCustomViewCallback = {
                hideCustomFullscreenVideo()
            },
            onGeolocationPromptCallback = { origin, callback ->
                handleGeolocationPrompt(origin, callback)
            },
            onPermissionRequestCallback = { request ->
                handleWebPermissionRequest(request)
            },
            onCreateWindowCallback = { sourceWebView, isDialog, isUserGesture, resultMsg ->
                // Only open a new tab when triggered by an explicit user gesture (tap/click).
                // Script-driven window.open() calls (ads, pop-unders, redirect loops) have
                // isUserGesture=false and must be silently blocked.
                if (!isUserGesture || resultMsg == null) {
                    false
                } else {
                    val parentTab = (sourceWebView as? OnyxWebView)?.tabId?.let { tabManager.getTabById(it) }
                        ?: tabManager.activeTab.value
                    val isIncognito = parentTab?.isIncognito ?: false
                    val newTab = tabManager.createNewTab(
                        url = "",
                        isIncognito = isIncognito,
                        parentId = parentTab?.id
                    )
                    val newWebView = tabManager.getOrCreateWebView(newTab)
                    newWebView.isPopupPendingDisplay = true
                    if (!isIncognito) {
                        try {
                            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(newWebView, true)
                        } catch (_: Exception) {}
                    }

                    // Pre-setup the clients before passing it back, so it instantly has download listeners
                    setupWebViewClients(newWebView)

                    val transport = resultMsg.obj as? WebView.WebViewTransport
                    if (transport != null) {
                        transport.webView = newWebView
                        resultMsg.sendToTarget()

                        // NOTE: Do NOT immediately switch tabs or pause the parent tab!
                        // If this window.open was triggered by an ad clickjack on a video player,
                        // switching tabs would call onPause() on the video tab and pause the video!
                        // Instead, keep the parent tab active. When newWebView navigates:
                        // - If it's an ad URL, handleUrlLoading cancels it and closes newTab silently!
                        // - If it's a legitimate URL, displayPopupTab(newTab) switches to it smoothly!
                        newWebView.postDelayed({
                            if (newWebView.isPopupPendingDisplay) {
                                val current = tabManager.getTabById(newTab.id)
                                val currentUrl = (newWebView.url ?: "").lowercase()
                                val isAuth = currentUrl.contains("login") || currentUrl.contains("auth") ||
                                        currentUrl.contains("oauth") || currentUrl.contains("facebook") ||
                                        currentUrl.contains("google") || currentUrl.contains("checkpoint")
                                if (!isAuth && current != null && currentDisplayedTabId != newTab.id) {
                                    closeTabById(newTab.id)
                                }
                            }
                        }, 8000)

                        true
                    } else {
                        false
                    }
                }
            },
            onCloseWindowCallback = {
                val activeTab = tabManager.activeTab.value
                if (activeTab != null) {
                    tabManager.closeTab(activeTab)
                }
            }
        )

        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            if (LocalFileLoader.isLocalFile(this, url)) {
                LocalFileLoader.loadLocalFile(this, webView, url)
                return@setDownloadListener
            }
            val cookies = try {
                android.webkit.CookieManager.getInstance().getCookie(url) ?: ""
            } catch (_: Exception) {
                ""
            }
            val referer = webView.url ?: ""
            DownloadHandler.handleDownload(
                activity = this,
                coroutineScope = lifecycleScope,
                url = url,
                userAgent = userAgent,
                contentDisposition = contentDisposition,
                mimeType = mimetype,
                contentLength = contentLength,
                cookies = cookies,
                referer = referer
            )
        }

        fun showContextMenuForElement(
            linkUrl: String? = null,
            imageUrl: String? = null,
            videoUrl: String? = null,
            linkText: String? = null
        ) {
            val cleanLink = linkUrl?.takeIf { it.isNotBlank() }
            val cleanImg = imageUrl?.takeIf { it.isNotBlank() }
            val cleanVid = videoUrl?.takeIf { it.isNotBlank() }
            val cleanText = linkText?.takeIf { it.isNotBlank() }

            val sheet = when {
                cleanLink != null && cleanImg != null ->
                    ContextMenuBottomSheet.forImageLink(cleanLink, cleanImg, cleanText)
                cleanVid != null ->
                    ContextMenuBottomSheet.forVideo(cleanVid)
                cleanImg != null ->
                    ContextMenuBottomSheet.forImage(cleanImg)
                cleanLink != null ->
                    ContextMenuBottomSheet.forLink(cleanLink, cleanText)
                else -> return
            }

            sheet.setOnOpenInNewTab { u -> openUrlInNewTab(u) }
            sheet.setOnOpenInIncognitoTab { u -> openUrlInIncognitoTab(u) }
            sheet.setOnEditUrl { u -> editUrlInSearchBar(u) }
            sheet.setOnDownloadUrl { u ->
                val cookies = android.webkit.CookieManager.getInstance().getCookie(u) ?: ""
                val userAgent = webView.settings.userAgentString ?: ""
                DownloadHandler.handleDownload(
                    activity = this,
                    coroutineScope = lifecycleScope,
                    url = u,
                    userAgent = userAgent,
                    contentDisposition = "",
                    mimeType = "",
                    contentLength = -1L,
                    cookies = cookies,
                    referer = webView.url ?: ""
                )
            }
            sheet.show(supportFragmentManager, ContextMenuBottomSheet.TAG)
        }

        // Long-press context menu for links, images, image links, videos, and media
        webView.setOnLongClickListener {
            val touched = webView.touchBridge.lastTouchedElement
            val hit = webView.hitTestResult
            val hitType = hit.type
            val hitExtra = hit.extra

            // 1. Tier 1: Real-time touch bridge pre-computed element
            if (touched.hasContent) {
                val linkUrl = touched.linkUrl.ifBlank {
                    if (hitType == android.webkit.WebView.HitTestResult.SRC_ANCHOR_TYPE) hitExtra ?: "" else ""
                }
                val imgUrl = touched.imageUrl.ifBlank {
                    if (hitType == android.webkit.WebView.HitTestResult.IMAGE_TYPE ||
                        hitType == android.webkit.WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) hitExtra ?: "" else ""
                }
                showContextMenuForElement(
                    linkUrl = linkUrl,
                    imageUrl = imgUrl,
                    videoUrl = touched.videoUrl,
                    linkText = touched.linkText
                )
                return@setOnLongClickListener true
            }

            // 2. Tier 2: HitTestResult matching
            when (hitType) {
                android.webkit.WebView.HitTestResult.SRC_ANCHOR_TYPE,
                android.webkit.WebView.HitTestResult.ANCHOR_TYPE -> {
                    val url = hitExtra ?: return@setOnLongClickListener false
                    showContextMenuForElement(linkUrl = url)
                    return@setOnLongClickListener true
                }
                android.webkit.WebView.HitTestResult.IMAGE_TYPE -> {
                    val imgUrl = hitExtra ?: return@setOnLongClickListener false
                    showContextMenuForElement(imageUrl = imgUrl)
                    return@setOnLongClickListener true
                }
                android.webkit.WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                    val imgUrl = hitExtra ?: ""
                    // requestFocusNodeHref to retrieve the real anchor link URL!
                    val msg = android.os.Handler(android.os.Looper.getMainLooper()) { m ->
                        val linkUrl = m.data.getString("url")
                        val title = m.data.getString("title")
                        val src = m.data.getString("src") ?: imgUrl
                        showContextMenuForElement(
                            linkUrl = linkUrl,
                            imageUrl = src,
                            linkText = title
                        )
                        true
                    }.obtainMessage()
                    webView.requestFocusNodeHref(msg)
                    return@setOnLongClickListener true
                }
                android.webkit.WebView.HitTestResult.EMAIL_TYPE -> {
                    val mail = hitExtra ?: return@setOnLongClickListener false
                    val mailto = if (mail.startsWith("mailto:", ignoreCase = true)) mail else "mailto:$mail"
                    showContextMenuForElement(linkUrl = mailto, linkText = mail)
                    return@setOnLongClickListener true
                }
                android.webkit.WebView.HitTestResult.PHONE_TYPE -> {
                    val phone = hitExtra ?: return@setOnLongClickListener false
                    val tel = if (phone.startsWith("tel:", ignoreCase = true)) phone else "tel:$phone"
                    showContextMenuForElement(linkUrl = tel, linkText = phone)
                    return@setOnLongClickListener true
                }
                android.webkit.WebView.HitTestResult.GEO_TYPE -> {
                    val geo = hitExtra ?: return@setOnLongClickListener false
                    val geoUri = if (geo.startsWith("geo:", ignoreCase = true)) geo else "geo:0,0?q=$geo"
                    showContextMenuForElement(linkUrl = geoUri, linkText = geo)
                    return@setOnLongClickListener true
                }
                android.webkit.WebView.HitTestResult.EDIT_TEXT_TYPE -> {
                    // Let Android native text editing handles work
                    return@setOnLongClickListener false
                }
                else -> {
                    // Tier 3: UNKNOWN_TYPE fallback via elementFromPoint and requestFocusNodeHref
                    val density = resources.displayMetrics.density
                    val cssX = (webView.lastTouchX / density).toInt()
                    val cssY = (webView.lastTouchY / density).toInt()
                    val jsCheck = """
                        (function(x, y) {
                            try {
                                var el = document.elementFromPoint(x, y);
                                if (!el) return null;
                                var a = el.closest('a');
                                var img = el.closest('img') || (el.tagName === 'IMG' ? el : el.querySelector('img'));
                                var video = el.closest('video') || (el.tagName === 'VIDEO' ? el : el.querySelector('video'));
                                if (!video) {
                                    var s = el.querySelector('source');
                                    if (s && s.parentElement && s.parentElement.tagName === 'VIDEO') video = s.parentElement;
                                }
                                if (!a && !img && !video) return null;
                                var linkUrl = a ? (a.href || a.getAttribute('href') || '') : '';
                                var linkText = a ? (a.innerText || a.textContent || '').trim().substring(0, 150) : '';
                                var imageUrl = img ? (img.currentSrc || img.src || '') : '';
                                var videoUrl = video ? (video.currentSrc || video.src || '') : '';
                                return JSON.stringify({
                                    linkUrl: linkUrl,
                                    linkText: linkText,
                                    imageUrl: imageUrl,
                                    videoUrl: videoUrl
                                });
                            } catch(e) { return null; }
                        })($cssX, $cssY);
                    """.trimIndent()

                    webView.evaluateJavascript(jsCheck) { jsonResult ->
                        if (!jsonResult.isNullOrBlank() && jsonResult != "null") {
                            try {
                                val cleanJson = if (jsonResult.startsWith("\"") && jsonResult.endsWith("\"")) {
                                    org.json.JSONTokener(jsonResult).nextValue().toString()
                                } else jsonResult
                                val obj = org.json.JSONObject(cleanJson)
                                val lUrl = obj.optString("linkUrl").takeIf { it.isNotBlank() }
                                val lText = obj.optString("linkText").takeIf { it.isNotBlank() }
                                val iUrl = obj.optString("imageUrl").takeIf { it.isNotBlank() }
                                val vUrl = obj.optString("videoUrl").takeIf { it.isNotBlank() }
                                if (lUrl != null || iUrl != null || vUrl != null) {
                                    showContextMenuForElement(
                                        linkUrl = lUrl,
                                        imageUrl = iUrl,
                                        videoUrl = vUrl,
                                        linkText = lText
                                    )
                                }
                            } catch (_: Exception) {}
                        }
                    }

                    val msg = android.os.Handler(android.os.Looper.getMainLooper()) { m ->
                        val linkUrl = m.data.getString("url")
                        val title = m.data.getString("title")
                        val src = m.data.getString("src")
                        if (!linkUrl.isNullOrBlank() || !src.isNullOrBlank()) {
                            showContextMenuForElement(
                                linkUrl = linkUrl,
                                imageUrl = src,
                                linkText = title
                            )
                        }
                        true
                    }.obtainMessage()
                    webView.requestFocusNodeHref(msg)

                    return@setOnLongClickListener false
                }
            }
        }
    }

    private fun editUrlInSearchBar(url: String) {
        binding.etUrl.setText(url)
        binding.etUrl.setSelection(binding.etUrl.text?.length ?: 0)
        binding.etUrl.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(binding.etUrl, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun openUrlInNewTab(url: String) {
        val parentTab = tabManager.activeTab.value
        val newTab = tabManager.createNewTab(url = url, isIncognito = false, parentId = parentTab?.id)
        // Explicitly set as active tab and navigate to it, ensuring currentDisplayedTabId
        // is updated before the StateFlow observer fires to avoid a no-op reload.
        tabManager.selectTab(newTab)
        currentDisplayedTabId = newTab.id
        updateTabBadgeCount()
        showWebView(newTab, forceUrl = url, reloadIfChanged = true)
    }

    private fun openUrlInIncognitoTab(url: String) {
        val parentTab = tabManager.activeTab.value
        val newTab = tabManager.createNewTab(url = url, isIncognito = true, parentId = parentTab?.id)
        tabManager.selectTab(newTab)
        currentDisplayedTabId = newTab.id
        updateTabBadgeCount()
        showWebView(newTab, forceUrl = url, reloadIfChanged = true)
    }

    override fun closeTab(tabId: String) {
        closeTabById(tabId)
    }

    fun closeTabById(tabId: String) {
        val tab = tabManager.getTabById(tabId) ?: return
        tabManager.closeTab(tab)
    }

    override fun getTabById(tabId: String): TabItem? {
        return if (::tabManager.isInitialized) tabManager.getTabById(tabId) else null
    }

    override fun displayPopupTab(tab: TabItem) {
        runOnUiThread {
            val wv = tabManager.getWebView(tab.id)
            if (wv != null && wv.isPopupPendingDisplay) {
                wv.isPopupPendingDisplay = false
                tabManager.selectTab(tab)
                currentDisplayedTabId = tab.id
                updateTabBadgeCount()
                showWebView(tab)
            }
        }
    }

    private fun startQrScanner() {
        qrScannerLauncher.launch(Intent(this, com.onyx.browser.ui.qr.QrScannerActivity::class.java))
    }

    fun performSearchOrLoad(input: String) {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return

        if (trimmed == "onyx://bookmarks") {
            openBookmarks()
            return
        }
        if (trimmed == "onyx://history") {
            openHistory()
            return
        }
        if (trimmed == "onyx://downloads") {
            openDownloads()
            return
        }
        if (trimmed == "onyx://qr") {
            qrScannerLauncher.launch(Intent(this, com.onyx.browser.ui.qr.QrScannerActivity::class.java))
            return
        }

        val url = when {
            LocalFileLoader.isSensitiveOrRestrictedPath(this, trimmed) -> {
                Toast.makeText(this, "Access to private or restricted files is blocked", Toast.LENGTH_SHORT).show()
                return
            }

            LocalFileLoader.isLocalFile(this, trimmed) -> trimmed

            trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("about:", ignoreCase = true) ||
            trimmed.startsWith("data:", ignoreCase = true) -> trimmed

            trimmed.startsWith("file://", ignoreCase = true) -> {
                val parsed = try { Uri.parse(trimmed) } catch (_: Exception) { null }
                val path = parsed?.path
                if (path != null && File(path).exists()) {
                    trimmed
                } else {
                    Toast.makeText(this, "File not found: $trimmed", Toast.LENGTH_SHORT).show()
                    return
                }
            }

            isLikelyUrl(trimmed) -> "https://$trimmed"

            else -> {
                val allEngines = preferences.getAllSearchEngines()
                val spaceIndex = trimmed.indexOf(' ')
                if (spaceIndex > 0) {
                    val firstToken = trimmed.substring(0, spaceIndex).trim()
                    val queryAfterKeyword = trimmed.substring(spaceIndex + 1).trim()
                    val matchedEngine = allEngines.firstOrNull {
                        it.keyword.isNotBlank() && it.keyword.equals(firstToken, ignoreCase = true)
                    }
                    if (matchedEngine != null && queryAfterKeyword.isNotEmpty()) {
                        matchedEngine.buildSearchUrl(queryAfterKeyword)
                    } else {
                        preferences.searchEngine.buildSearchUrl(trimmed)
                    }
                } else {
                    preferences.searchEngine.buildSearchUrl(trimmed)
                }
            }
        }

        val activeTab = tabManager.activeTab.value ?: tabManager.createNewTab()
        tabManager.updateActiveTab(url, url)
        showWebView(activeTab, forceUrl = url)
        hideTranslateBar(restoreOriginal = false)
        if (searchController.isSearchMode) {
            searchController.exitSearchMode()
        }
    }

    private fun isLikelyUrl(input: String): Boolean {
        return com.onyx.browser.data.search.SearchSuggestionRepository.isLikelyDomainOrUrl(input)
    }

    private fun isReelOrFeedUrl(url: String): Boolean {
        if (url.isBlank()) return false
        if (url.contains("facebook.com") && (url.contains("/reel") || url.contains("/watch") || url.contains("/videos"))) {
            return true
        }
        if (url.contains("instagram.com") && (url.contains("/reel") || url.contains("/reels") || url.contains("/stories"))) {
            return true
        }
        if (url.contains("youtube.com/shorts") || url.contains("tiktok.com")) {
            return true
        }
        return false
    }

    private fun getActivePageUrl(): String {
        val activeWv = tabManager.getActiveWebView()
        val failingUrl = activeWv?.currentSyntheticState?.failingUrl
        if (!failingUrl.isNullOrBlank()) {
            return failingUrl
        }
        val tabUrl = tabManager.activeTab.value?.url ?: ""
        if (tabUrl.startsWith("data:") || tabUrl.startsWith("file:///android_asset/") || tabUrl.startsWith("file:///android_res/") ||
            LocalFileLoader.isPreviewUrl(tabUrl) || LocalFileLoader.isSensitiveOrRestrictedPath(this, tabUrl)) {
            return ""
        }
        return tabUrl
    }

    private fun updateAddressBarDisplay(url: String) {
        val activeWv = tabManager.getActiveWebView()
        val displayUrl = when {
            url.isBlank() || url.startsWith("about:blank", ignoreCase = true) || url.startsWith("data:") || url.startsWith("file:///android_asset/") || url.startsWith("file:///android_res/") -> {
                activeWv?.currentSyntheticState?.failingUrl ?: tabManager.activeTab.value?.url?.takeIf { !it.startsWith("about:blank", ignoreCase = true) } ?: ""
            }
            LocalFileLoader.isPreviewUrl(url) -> {
                tabManager.activeTab.value?.url ?: ""
            }
            else -> url
        }

        if (displayUrl.isBlank() || displayUrl.startsWith("about:blank", ignoreCase = true) || displayUrl.startsWith("data:") || displayUrl.startsWith("file:///android_asset/") || displayUrl.startsWith("file:///android_res/")) {
            binding.etUrl.setText("")
            binding.ivSslLock.visibility = View.GONE
            binding.btnHome.setImageResource(R.drawable.ic_home)
            binding.btnHome.contentDescription = getString(R.string.home)
            return
        }

        val isHttps = displayUrl.startsWith("https://")
        binding.ivSslLock.visibility = if (isHttps) View.VISIBLE else View.GONE

        val isLocalDoc = isLocalFileView(tabManager.activeTab.value) ||
                LocalFileLoader.isLocalFile(this, displayUrl) ||
                LocalFileLoader.isPreviewUrl(displayUrl)
        if (isLocalDoc) {
            binding.btnHome.setImageResource(R.drawable.ic_arrow_back)
            binding.btnHome.contentDescription = getString(R.string.back)
        } else {
            binding.btnHome.setImageResource(R.drawable.ic_home)
            binding.btnHome.contentDescription = getString(R.string.home)
        }

        val host = when {
            isLocalDoc -> {
                val realDocUrl = tabManager.activeTab.value?.url?.takeIf { !it.endsWith("/") && (LocalFileLoader.isLocalFile(this, it) || LocalFileLoader.isPreviewUrl(it)) } ?: displayUrl
                if (LocalFileLoader.isLocalFile(this, realDocUrl)) {
                    LocalFileLoader.getDisplayName(this, LocalFileLoader.parseUri(realDocUrl))
                } else {
                    tabManager.activeTab.value?.title?.takeIf { it.isNotBlank() } ?: "Document Preview"
                }
            }
            LocalFileLoader.isLocalFile(this, displayUrl) -> {
                LocalFileLoader.getDisplayName(this, LocalFileLoader.parseUri(displayUrl))
            }
            LocalFileLoader.isPreviewUrl(displayUrl) -> {
                tabManager.activeTab.value?.title?.takeIf { it.isNotBlank() } ?: "Web Archive"
            }
            else -> try {
                Uri.parse(displayUrl).host ?: displayUrl
            } catch (e: Exception) {
                displayUrl
            }
        }

        if (!searchController.isSearchMode) {
            binding.etUrl.setText(host)
        }
    }

    private fun updateSearchEngineIcon() {
        SearchEngineIconHelper.loadSearchEngineIcon(this, binding.btnSearchEngine, preferences.searchEngine)
    }

    private fun updateTabBadgeCount() {
        val count = tabManager.getOpenTabCount()
        binding.tvTabCount.text = count.toString()
    }

    fun checkNotificationPermissionForDownloads() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    fun checkAndRequestStoragePermission(onGranted: () -> Unit) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                pendingStorageAction = onGranted
                storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                return
            }
        }
        onGranted()
    }

    private fun launchVoiceSearch() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startVoiceSearchIntent()
        } else {
            voiceSearchMicLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startVoiceSearchIntent() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH
            )
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_search))
        }
        try {
            voiceSearchLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Voice search unavailable", Toast.LENGTH_SHORT).show()
        }
    }

    fun showCustomFullscreenVideo(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (::pipController.isInitialized) {
            pipController.showCustomFullscreenVideo(view, callback)
        } else {
            callback.onCustomViewHidden()
        }
    }

    fun hideCustomFullscreenVideo() {
        if (::pipController.isInitialized) {
            pipController.hideCustomFullscreenVideo()
        }
    }

    fun updatePipParams(
        isVideoPlaying: Boolean = MediaPlaybackBridge.isMediaPlaying,
        width: Int = MediaPlaybackBridge.lastVideoWidth,
        height: Int = MediaPlaybackBridge.lastVideoHeight,
        shouldAutoEnter: Boolean = false
    ) {
        if (::pipController.isInitialized) {
            pipController.updatePipParams(isVideoPlaying, width, height, shouldAutoEnter)
        }
    }

    fun requestInPageVideoPip() {
        if (::pipController.isInitialized) {
            pipController.requestInPageVideoPip()
        }
    }

    fun enterPipMode() {
        if (::pipController.isInitialized) {
            pipController.enterPipMode()
        }
    }

    private fun setupMediaPlaybackListener() {
        MediaPlaybackService.mediaActionListener = object : MediaPlaybackService.MediaActionListener {
            override fun onPlayMedia() {
                runOnUiThread {
                    getMediaTargetWebView()?.evaluateJavascript(MediaPlaybackManager.playAllMediaScript, null)
                }
            }

            override fun onPauseMedia() {
                runOnUiThread {
                    getMediaTargetWebView()?.evaluateJavascript(MediaPlaybackManager.pauseAllMediaScript, null)
                }
            }

            override fun onSeekMedia(deltaSeconds: Int) {
                runOnUiThread {
                    getMediaTargetWebView()?.evaluateJavascript(MediaPlaybackManager.getSeekMediaScript(deltaSeconds), null)
                }
            }

            override fun onSeekToMedia(positionMs: Long) {
                runOnUiThread {
                    val posSec = positionMs / 1000.0
                    getMediaTargetWebView()?.evaluateJavascript(MediaPlaybackManager.getSeekToPositionScript(posSec), null)
                }
            }

            override fun onSkipNextMedia() {
                runOnUiThread {
                    getMediaTargetWebView()?.evaluateJavascript(MediaPlaybackManager.skipNextMediaScript, null)
                }
            }

            override fun onSkipPreviousMedia() {
                runOnUiThread {
                    getMediaTargetWebView()?.evaluateJavascript(MediaPlaybackManager.skipPreviousMediaScript, null)
                }
            }

            override fun onStopMedia() {
                runOnUiThread {
                    getMediaTargetWebView()?.evaluateJavascript(MediaPlaybackManager.pauseAllMediaScript, null)
                    MediaPlaybackBridge.resetMediaPlayback(this@MainActivity)
                }
            }
        }

        MediaPlaybackBridge.onMediaPlaybackStartedListener = { playingWebView ->
            runOnUiThread {
                tabManager.normalTabs.value.forEach { tab ->
                    val wv = tabManager.getWebView(tab.id)
                    if (wv != null && wv != playingWebView) {
                        wv.evaluateJavascript(MediaPlaybackManager.pauseAllMediaScript, null)
                    }
                }
                tabManager.incognitoTabs.value.forEach { tab ->
                    val wv = tabManager.getWebView(tab.id)
                    if (wv != null && wv != playingWebView) {
                        wv.evaluateJavascript(MediaPlaybackManager.pauseAllMediaScript, null)
                    }
                }
            }
        }

        MediaPlaybackBridge.onMediaStateListener = { isPlaying, isVideo, width, height ->
            runOnUiThread {
                updatePipParams(isVideo && isPlaying, width, height)
                // Keep screen on while video is actively playing in-page
                if (isVideo && isPlaying) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else if (!isPlaying) {
                    // Only clear the flag if we are not currently in PiP mode
                    val inPip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode
                    if (!inPip) {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
            }
        }

        MediaPlaybackBridge.onVideoBoundsListener = { _, _, _, _ ->
            runOnUiThread {
                if (MediaPlaybackBridge.isVideoPlaying && customVideoView != null) {
                    updatePipParams()
                }
            }
        }

        MediaPlaybackBridge.onPipRequestedListener = {
            runOnUiThread {
                val inPip = isCurrentlyInPip || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode)
                if (!inPip && !justExitedPip) {
                    requestInPageVideoPip()
                }
            }
        }

        MediaPlaybackBridge.onPipExitListener = {
            runOnUiThread {
                if (customVideoView != null) {
                    hideCustomFullscreenVideo()
                }
            }
        }
    }

    private fun showSoftKeyboard() {
        binding.etUrl.post {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(binding.etUrl, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideSoftKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.etUrl.windowToken, 0)
    }

    private var currentTranslateTargetCode: String = ""
    private var isUpdatingTranslateUi = false

    fun setTranslateLoading(isLoading: Boolean) {
        runOnUiThread {
            binding.pbTranslateLoading.visibility = if (isLoading) View.VISIBLE else View.GONE
        }
    }

    private fun setupTranslateBar() {
        binding.toggleTranslateMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isUpdatingTranslateUi || !isChecked) return@addOnButtonCheckedListener
            val wv = tabManager.getActiveWebView() ?: return@addOnButtonCheckedListener
            when (checkedId) {
                R.id.btnTranslateOriginal -> {
                    wv.evaluateJavascript(PageTranslateManager.restoreOriginalScript, null)
                    binding.pbTranslateLoading.visibility = View.GONE
                }
                R.id.btnTranslateTarget -> {
                    val code = currentTranslateTargetCode.ifBlank { preferences.targetTranslateLanguage }
                    binding.pbTranslateLoading.visibility = View.VISIBLE
                    wv.evaluateJavascript(PageTranslateManager.toggleTranslatedScript) { result ->
                        val res = result?.trim('"')
                        if (res != "translated") {
                            wv.evaluateJavascript(PageTranslateManager.getTranslateScript(code), null)
                        } else {
                            binding.pbTranslateLoading.visibility = View.GONE
                        }
                    }
                }
            }
        }

        binding.btnChangeTranslateLanguage.setOnClickListener {
            val dialog = LanguageSelectionDialog { code, name ->
                currentTranslateTargetCode = code
                preferences.targetTranslateLanguage = code
                preferences.targetTranslateLanguageName = name
                binding.btnTranslateTarget.text = name
                isUpdatingTranslateUi = true
                try {
                    binding.toggleTranslateMode.check(R.id.btnTranslateTarget)
                } finally {
                    isUpdatingTranslateUi = false
                }
                val wv = tabManager.getActiveWebView() ?: return@LanguageSelectionDialog
                binding.pbTranslateLoading.visibility = View.VISIBLE
                wv.evaluateJavascript(PageTranslateManager.getTranslateScript(code), null)
            }
            dialog.show(supportFragmentManager, "LanguageSelectionDialog")
        }

        binding.btnCloseTranslate.setOnClickListener {
            hideTranslateBar(restoreOriginal = true)
        }
    }

    private fun showTranslateBar(targetCode: String, targetName: String) {
        currentTranslateTargetCode = targetCode
        binding.btnTranslateTarget.text = targetName
        isUpdatingTranslateUi = true
        try {
            binding.toggleTranslateMode.check(R.id.btnTranslateTarget)
        } finally {
            isUpdatingTranslateUi = false
        }
        binding.translateBar.visibility = View.VISIBLE
    }

    private fun hideTranslateBar(restoreOriginal: Boolean = false) {
        binding.translateBar.visibility = View.GONE
        binding.pbTranslateLoading.visibility = View.GONE
        if (restoreOriginal) {
            tabManager.getActiveWebView()?.evaluateJavascript(PageTranslateManager.restoreOriginalScript, null)
        }
    }

    private fun translateCurrentPage(langCode: String) {
        val activeTab = tabManager.activeTab.value ?: return
        val currentUrl = activeTab.url
        if (currentUrl.isBlank() || currentUrl.startsWith("onyx://") || currentUrl.startsWith("about:") || LocalFileLoader.isLocalFile(this, currentUrl)) {
            Toast.makeText(this, "Cannot translate internal page", Toast.LENGTH_SHORT).show()
            return
        }

        val targetCode = if (langCode.isNotBlank()) langCode else preferences.targetTranslateLanguage
        val targetName = preferences.targetTranslateLanguageName

        val webView = tabManager.getActiveWebView()
        if (webView == null) {
            Toast.makeText(this, "No active webpage to translate", Toast.LENGTH_SHORT).show()
            return
        }

        showTranslateBar(targetCode, targetName)
        binding.pbTranslateLoading.visibility = View.VISIBLE

        webView.evaluateJavascript(PageTranslateManager.getTranslateScript(targetCode), null)
    }

    private fun addCurrentPageToHomeScreen() {
        val activeTab = tabManager.activeTab.value ?: return
        val currentUrl = activeTab.url
        if (currentUrl.isBlank() || currentUrl.startsWith("onyx://") || currentUrl.startsWith("about:")) {
            Toast.makeText(this, "Cannot add internal page to Home screen", Toast.LENGTH_SHORT).show()
            return
        }
        val title = activeTab.title.ifBlank { currentUrl }
        val webView = tabManager.getActiveWebView()
        val favicon = webView?.favicon

        if (ShortcutManagerCompat.isRequestPinShortcutSupported(this)) {
            val shortcutIntent = Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse(currentUrl)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val iconCompat = if (favicon != null) {
                IconCompat.createWithBitmap(favicon)
            } else {
                IconCompat.createWithResource(this, R.mipmap.ic_launcher)
            }
            val pinShortcutInfo = ShortcutInfoCompat.Builder(this, "onyx_web_${currentUrl.hashCode()}")
                .setShortLabel(title.take(20))
                .setLongLabel(title)
                .setIcon(iconCompat)
                .setIntent(shortcutIntent)
                .build()
            ShortcutManagerCompat.requestPinShortcut(this, pinShortcutInfo, null)
            Toast.makeText(this, getString(R.string.added_to_home_screen), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Pin shortcut not supported by launcher", Toast.LENGTH_SHORT).show()
        }
    }

    private fun injectDeveloperTools() {
        val webView = tabManager.getActiveWebView() ?: return
        val activeTab = tabManager.activeTab.value
        if (activeTab == null || activeTab.url.isBlank() ||
            activeTab.url.startsWith("onyx://") || activeTab.url.startsWith("about:")) {
            Toast.makeText(this, "Developer tools can only run on active web pages", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val isDark = preferences.isDarkMode()
            DevToolsManager.toggleDevTools(this@MainActivity, webView, isDark) { status: String ->
                when (status) {
                    "shown" -> {
                        webView.isDevToolsActive = true
                        // No toast needed — the Eruda panel appearing is its own feedback
                    }
                    "hidden" -> {
                        // Panel is minimized but Eruda icon stays visible on screen
                        // Keep isDevToolsActive = true so auto-reinject fires on navigation
                        webView.isDevToolsActive = true
                    }
                    "failed" -> {
                        webView.isDevToolsActive = false
                        Toast.makeText(this@MainActivity, "Unable to load developer tools", Toast.LENGTH_SHORT).show()
                    }
                    "error" -> {
                        webView.isDevToolsActive = false
                        Toast.makeText(this@MainActivity, "Developer tools encountered an error", Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        // Unexpected — do not change state
                    }
                }
            }
        }
    }

    private fun startElementBlocker() {
        val webView = tabManager.getActiveWebView() ?: return
        val activeTab = tabManager.activeTab.value
        if (activeTab == null || activeTab.url.isBlank() ||
            activeTab.url.startsWith("onyx://") || activeTab.url.startsWith("about:")) {
            Toast.makeText(this, "Element blocker can only run on active web pages", Toast.LENGTH_SHORT).show()
            return
        }
        com.onyx.browser.ui.menu.ElementPickerManager.startPicker(webView)
        Toast.makeText(this, R.string.block_element_hint, Toast.LENGTH_SHORT).show()
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Don't intercept back in PiP mode — system manages PiP dismissal
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                    return
                }

                if (customVideoView != null || binding.fullscreenCustomViewContainer.visibility == View.VISIBLE) {
                    hideCustomFullscreenVideo()
                    return
                }

                // Cancel element picker if active — back press exits picker, not page
                if (com.onyx.browser.ui.menu.ElementPickerManager.isPickerActive) {
                    val wv = tabManager.getActiveWebView()
                    if (wv != null) com.onyx.browser.ui.menu.ElementPickerManager.cancelPicker(wv)
                    return
                }

                if (binding.topBar.visibility != View.VISIBLE) {
                    binding.topBar.visibility = View.VISIBLE
                    binding.topBarDivider.visibility = View.VISIBLE
                    val activeTab = tabManager.activeTab.value
                    if (activeTab != null && activeTab.url.isNotBlank() &&
                        !activeTab.url.startsWith("onyx://") && !activeTab.url.startsWith("about:")) {
                        binding.webViewContainer.visibility = View.VISIBLE
                        binding.webViewContainer.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        binding.webViewContainer.requestLayout()
                    }
                    tabManager.getActiveWebView()?.evaluateJavascript(MediaPlaybackManager.restoreVideoFromPipScript, null)
                    return
                }

                if (findInPageController.isVisible) {
                    findInPageController.hide()
                    return
                }

                if (binding.translateBar.visibility == View.VISIBLE) {
                    hideTranslateBar(restoreOriginal = true)
                    return
                }

                if (searchController.isSearchMode) {
                    searchController.exitSearchMode()
                    return
                }

                val activeWebView = tabManager.getActiveWebView()
                val isErrorPage = activeWebView != null && (
                    activeWebView.currentSyntheticState != null ||
                    activeWebView.url?.startsWith("https://onyx.browser/", ignoreCase = true) == true ||
                    activeWebView.url?.startsWith("file:///android_asset/error_page.html", ignoreCase = true) == true
                )

                if (isErrorPage && activeWebView != null) {
                    if (handleErrorPageBack(activeWebView)) {
                        return
                    }
                }

                val activeTab = tabManager.activeTab.value
                val isLocalPreview = isLocalFileView(activeTab)

                if (isLocalPreview && activeTab != null) {
                    val anchorStep = activeWebView?.let { findPreviousValidLocalPreviewStep(it, activeTab.url) }
                    if (anchorStep != null && activeWebView != null) {
                        activeWebView.goBackOrForward(anchorStep)
                        return
                    }

                    val tabId = activeTab.id
                    val wasFromDownloads = previewTabsFromDownloads.remove(tabId)
                    val wasFromExternal = previewTabsFromExternal.remove(tabId)
                    localFileViewTabs.remove(tabId)

                    // If opened from an external app (ACTION_VIEW / external task): call finish() to return to the file manager
                    if (wasFromExternal) {
                        tabManager.closeTab(activeTab)
                        finish()
                        return
                    }

                    // If opened from within Onyx (with a parent tab or Downloads behind it):
                    // close the preview tab (tabManager.closeTab(activeTab)), restoring the previous tab or Downloads
                    tabManager.closeTab(activeTab)

                    if (wasFromDownloads) {
                        startActivity(Intent(this@MainActivity, DownloadsActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        })
                        return
                    }

                    // Never wipe the tab to "" or trap the user on a blank white page
                    return
                }

                if (activeWebView != null) {
                    val steps = findPreviousValidHistoryStep(activeWebView)
                    if (steps != null) {
                        activeWebView.goBackOrForward(steps)
                        return
                    }
                }

                if (activeTab != null) {
                    val parentId = activeTab.parentId
                    val parentTab = if (!parentId.isNullOrBlank()) {
                        tabManager.getTabById(parentId)
                    } else null

                    if (parentTab != null) {
                        // This tab was opened from another tab (e.g. redirected or opened in new tab).
                        // Since all history steps in this tab are gone, pressing back closes this tab
                        // and returns to the original tab from where the user was redirected.
                        tabManager.closeTab(activeTab)
                        return
                    }

                    if (binding.homeLayout.root.visibility != View.VISIBLE || (activeTab.url.isNotBlank() && !activeTab.url.startsWith("about:blank", ignoreCase = true))) {
                        // Navigate back to home screen on this tab
                        tabManager.updateActiveTab("", "New Tab")
                        showHomeScreen()
                        return
                    }
                }

                // Default activity back behavior
                finish()
            }
        })
    }

    fun handleErrorPageBack(webView: OnyxWebView): Boolean {
        val activeTab = tabManager.activeTab.value
        val steps = findPreviousValidHistoryStep(webView)

        if (steps != null) {
            webView.clearSyntheticState()
            webView.goBackOrForward(steps)
            return true
        }

        // No valid previous history step in this tab — return to home screen or parent tab
        webView.clearSyntheticState()
        webView.stopLoading()
        webView.loadUrl("about:blank")

        if (activeTab != null) {
            val parentId = activeTab.parentId
            val parentTab = if (!parentId.isNullOrBlank()) {
                tabManager.getTabById(parentId)
            } else null

            if (parentTab != null) {
                tabManager.closeTab(activeTab)
                return true
            }

            tabManager.updateActiveTab("", "New Tab")
            showHomeScreen()
            return true
        }

        showHomeScreen()
        return true
    }

    private fun findPreviousValidLocalPreviewStep(webView: OnyxWebView, currentFileUrl: String): Int? {
        val list = try { webView.copyBackForwardList() } catch (_: Throwable) { return null }
        val currentIndex = list.currentIndex
        if (currentIndex <= 0) return null

        val currentNormalized = currentFileUrl.substringBefore('#').trimEnd('/')

        for (i in currentIndex - 1 downTo 0) {
            val item = list.getItemAtIndex(i) ?: continue
            val itemUrl = item.url ?: continue

            // Skip blank, data, or synthetic error page URLs
            if (itemUrl.isBlank() ||
                itemUrl == "about:blank" ||
                itemUrl.startsWith("about:blank", ignoreCase = true) ||
                OnyxWebView.isSyntheticOrDataUrl(itemUrl) ||
                itemUrl.startsWith("https://onyx.browser/", ignoreCase = true) ||
                itemUrl.startsWith("file:///android_asset/error_page.html", ignoreCase = true)
            ) {
                continue
            }

            // Only allow back navigation within the preview if it is an in-document anchor to the same local file
            val itemNormalized = itemUrl.substringBefore('#').trimEnd('/')
            if (itemNormalized.equals(currentNormalized, ignoreCase = true) && itemUrl != webView.url) {
                return i - currentIndex
            }
        }

        return null
    }

    private fun findPreviousValidHistoryStep(webView: OnyxWebView): Int? {
        val list = try { webView.copyBackForwardList() } catch (_: Throwable) { return null }
        val currentIndex = list.currentIndex
        if (currentIndex <= 0) return null

        val failingUrl = webView.currentSyntheticState?.failingUrl ?: webView.lastFailingUrl ?: ""
        val normalizedFailing = failingUrl.trimEnd('/')
        val failingWithoutScheme = normalizedFailing.removePrefix("https://").removePrefix("http://")

        for (i in currentIndex - 1 downTo 0) {
            val item = list.getItemAtIndex(i) ?: continue
            val itemUrl = item.url ?: continue
            val normalizedItem = itemUrl.trimEnd('/')
            val itemWithoutScheme = normalizedItem.removePrefix("https://").removePrefix("http://")

            // Skip blank, data, or synthetic error page URLs
            if (itemUrl.isBlank() ||
                itemUrl == "about:blank" ||
                itemUrl.startsWith("about:blank", ignoreCase = true) ||
                OnyxWebView.isSyntheticOrDataUrl(itemUrl) ||
                itemUrl.startsWith("https://onyx.browser/", ignoreCase = true) ||
                itemUrl.startsWith("file:///android_asset/error_page.html", ignoreCase = true)
            ) {
                continue
            }

            // Skip the failing URL itself (or redirects between http/https)
            if (failingWithoutScheme.isNotBlank() && itemWithoutScheme.equals(failingWithoutScheme, ignoreCase = true)) {
                continue
            }

            // Valid previous webpage found!
            return i - currentIndex
        }

        return null
    }

    override fun onPause() {
        super.onPause()
        MediaPlaybackBridge.isAppInBackground = true
        val activeTabId = tabManager.activeTab.value?.id
        val activeWebView = tabManager.getActiveWebView()
        if (activeTabId != null && activeWebView != null) {
            tabManager.captureTabSnapshot(activeTabId, activeWebView)
            tabManager.saveTabState(activeTabId, activeWebView)
        }
        if (!preferences.isPipEnabled) {
            updatePipParams(isVideoPlaying = false)
        }
        val isPip = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) isInPictureInPictureMode else false
        if (preferences.isBackgroundPlayEnabled) {
            tabManager.getAllWebViews().forEach { wv ->
                val isWvInPip = isPip && (wv == tabManager.getActiveWebView() || (wv as? com.onyx.browser.web.OnyxWebView)?.tabId == MediaPlaybackBridge.currentPlayingTabId)
                wv.evaluateJavascript(
                    MediaPlaybackManager.getSetBackgroundStateScript(!isWvInPip),
                    null
                )
            }
            tabManager.getActiveWebView()?.resumeTimers()
        }
        if (!isPip && !preferences.isBackgroundPlayEnabled) {
            tabManager.getActiveWebView()?.onPause()
        }
    }



    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        tabManager.saveAllTabStates()
    }

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private fun registerNetworkRecoveryCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread {
                    val wv = tabManager.getActiveWebView() ?: return@runOnUiThread
                    val state = wv.currentSyntheticState
                    if (state?.category == com.onyx.browser.web.error.SyntheticNavigationState.ErrorCategory.OFFLINE) {
                        val url = state.failingUrl.ifBlank { wv.lastFailingUrl ?: "" }
                        wv.clearSyntheticState()
                        if (url.isNotBlank() && !LocalFileLoader.isLocalFile(this@MainActivity, url)) {
                            wv.loadUrl(url)
                        } else {
                            wv.reload()
                        }
                    }
                }
            }
        }
        try {
            cm.registerNetworkCallback(request, networkCallback!!)
        } catch (_: Exception) {}
    }

    private fun unregisterNetworkRecoveryCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        networkCallback?.let {
            try {
                cm.unregisterNetworkCallback(it)
            } catch (_: Exception) {}
        }
        networkCallback = null
    }

    override fun onStart() {
        super.onStart()
        registerNetworkRecoveryCallback()
        
        val activeTab = tabManager.activeTab.value
        if (activeTab?.isIncognito == true && preferences.isBiometricIncognitoEnabled && !tabManager.isIncognitoUnlocked) {
            // Show overlay to obscure the webview
            binding.incognitoLockedOverlay.visibility = android.view.View.VISIBLE
            
            // Must authenticate
            val executor = androidx.core.content.ContextCompat.getMainExecutor(this)
            val biometricPrompt = androidx.biometric.BiometricPrompt(this, executor,
                object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) {
                        super.onAuthenticationSucceeded(result)
                        tabManager.isIncognitoUnlocked = true
                        binding.incognitoLockedOverlay.visibility = android.view.View.GONE
                    }
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        super.onAuthenticationError(errorCode, errString)
                        binding.incognitoLockedOverlay.visibility = android.view.View.GONE
                        switchToNormalTabOrNew()
                    }
                    override fun onAuthenticationFailed() {
                        super.onAuthenticationFailed()
                        // Allow user retry on biometric prompt UI
                    }
                })

            val promptInfo = com.onyx.browser.ui.common.BiometricAuthHelper.createPromptInfo(
                title = "Unlock Incognito Tab",
                subtitle = "Confirm identity to resume browsing"
            )
            biometricPrompt.authenticate(promptInfo)
        }
    }
    
    private fun switchToNormalTabOrNew() {
        val normalTabs = tabManager.normalTabs.value
        if (normalTabs.isNotEmpty()) {
            tabManager.selectTab(normalTabs.first())
        } else {
            val newTab = tabManager.createNewTab(url = "", isIncognito = false)
            tabManager.selectTab(newTab)
        }
    }

    override fun onStop() {
        super.onStop()
        tabManager.saveAllTabStates()
        unregisterNetworkRecoveryCallback()
        tabManager.isIncognitoUnlocked = false
        val isPip = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) isInPictureInPictureMode else false

        // If the user closed PiP via the 'X' button, onPictureInPictureModeChanged(false) was followed immediately by onStop()
        if (justExitedPip) {
            if (customVideoView != null) {
                hideCustomFullscreenVideo()
            }
            tabManager.getActiveWebView()?.evaluateJavascript(MediaPlaybackManager.pauseAllMediaScript, null)
            tabManager.getActiveWebView()?.evaluateJavascript(MediaPlaybackManager.restoreVideoFromPipScript, null)
            MediaPlaybackService.stopNotificationOnly(this)
        }

        if (!isPip && !MediaPlaybackBridge.isBackgroundPlayActive(preferences)) {
            tabManager.getActiveWebView()?.onPause()
        } else if (preferences.isBackgroundPlayEnabled) {
            tabManager.getActiveWebView()?.resumeTimers()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Do not auto-enter PiP on user leave to prevent unwanted PiP re-entry loops
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (::pipController.isInitialized) {
            pipController.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        }
    }

    override fun onResume() {
        super.onResume()
        MediaPlaybackBridge.isAppInBackground = false
        MediaPlaybackBridge.isExplicitUserPause = false

        // 1. Sync theme if changed in Settings or system dark mode toggled
        preferences.applyTheme()
        val currentNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if ((lastThemeMode != -1 && lastThemeMode != preferences.themeMode) ||
            (lastNightMode != -1 && lastNightMode != currentNightMode)) {
            lastThemeMode = preferences.themeMode
            lastNightMode = currentNightMode
            recreate()
            return
        }

        // 2. Sync search engine & update home widgets
        updateSearchEngineIcon()
        com.onyx.browser.ui.widget.SearchWidgetProvider.updateAllWidgets(this)

        // 3. Sync scroll to top FAB visibility
        if (!preferences.isScrollToTopEnabled) {
            binding.fabScrollToTop.visibility = View.GONE
        }

        // 4. Sync webview settings
        val wv = tabManager.getActiveWebView()
        wv?.onResume()
        wv?.applyUserAgentForUrl(wv.url ?: "")
        if (wv != null) {
            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(
                wv,
                preferences.cookieBlockingMode != BrowserPreferences.COOKIE_BLOCK_THIRD_PARTY &&
                preferences.cookieBlockingMode != BrowserPreferences.COOKIE_BLOCK_ALL
            )
            wv.settings.javaScriptEnabled = !preferences.isGlobalScriptBlockingEnabled
        }

        updatePipParams()
        binding.root.post {
            wv?.resumeTimers()
            wv?.requestFocus()
        }
        tabManager.getAllWebViews().forEach { v ->
            v.evaluateJavascript(
                MediaPlaybackManager.getSetBackgroundStateScript(false),
                null
            )
        }
        // Restore screen-on flag if video was still playing when we came back to the app
        if (MediaPlaybackBridge.isVideoPlaying) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        
        // Guarantee cleanup of custom view container and restoration of toolbar
        val isPip = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) isInPictureInPictureMode else false
        if (!isPip) {
            if (customVideoView == null) {
                binding.fullscreenCustomViewContainer.visibility = View.GONE
                binding.fullscreenControlsOverlay.visibility = View.GONE
                binding.topBar.visibility = View.VISIBLE
                binding.topBarDivider.visibility = View.VISIBLE
                val activeTab = tabManager.activeTab.value
                if (activeTab != null && activeTab.url.isNotBlank() &&
                    !activeTab.url.startsWith("onyx://") && !activeTab.url.startsWith("about:")) {
                    binding.homeLayout.root.visibility = View.GONE
                    binding.webViewContainer.visibility = View.VISIBLE
                    binding.webViewContainer.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                }
                androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                wv?.evaluateJavascript(MediaPlaybackManager.restoreVideoFromPipScript, null)
                binding.topBar.requestLayout()
                binding.webViewContainer.requestLayout()
            } else {
                binding.fullscreenControlsOverlay.visibility = View.VISIBLE
                binding.fullscreenControlsOverlay.bringToFront()
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val newNightMode = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (lastNightMode != -1 && lastNightMode != newNightMode) {
            lastNightMode = newNightMode
            recreate()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            val wv = tabManager.getActiveWebView()
            wv?.resumeTimers()
            wv?.requestFocus()
            val isPip = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) isInPictureInPictureMode else false
            if (!isPip && customVideoView == null) {
                if (binding.topBar.visibility != View.VISIBLE) {
                    binding.topBar.visibility = View.VISIBLE
                    binding.topBarDivider.visibility = View.VISIBLE
                }
                val activeTab = tabManager.activeTab.value
                if (activeTab != null && activeTab.url.isNotBlank() &&
                    !activeTab.url.startsWith("onyx://") && !activeTab.url.startsWith("about:")) {
                    if (binding.webViewContainer.visibility != View.VISIBLE) {
                        binding.webViewContainer.visibility = View.VISIBLE
                        binding.webViewContainer.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        binding.webViewContainer.requestLayout()
                    }
                }
                wv?.evaluateJavascript(MediaPlaybackManager.restoreVideoFromPipScript, null)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isTabsRestored) {
            handleIncomingIntent(intent)
        } else {
            pendingIntent = intent
        }
    }


    private fun handleShortcuts(intent: Intent?): Boolean {
        if (intent == null) return false
        val action = intent.action ?: return false
        when (action) {
            ACTION_WIDGET_SEARCH, "com.onyx.browser.action.SEARCH" -> {
                intent.action = null
                binding.root.post {
                    searchController.enterSearchMode()
                }
                return true
            }
            ACTION_WIDGET_VOICE_SEARCH -> {
                intent.action = null
                binding.root.post {
                    launchVoiceSearch()
                }
                return true
            }
            ACTION_WIDGET_INCOGNITO_SEARCH -> {
                intent.action = null
                val newTab = tabManager.createNewTab(url = "", isIncognito = true)
                displayTab(newTab)
                binding.root.post {
                    searchController.enterSearchMode()
                }
                return true
            }
            "com.onyx.browser.action.NEW_INCOGNITO_TAB" -> {
                intent.action = null
                val newTab = tabManager.createNewTab(url = "", isIncognito = true)
                displayTab(newTab)
                return true
            }
            "com.onyx.browser.action.SCAN_QR" -> {
                intent.action = null
                startQrScanner()
                return true
            }
        }
        return false
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (handleShortcuts(intent)) return
        if (intent == null) return
        val targetUrl = extractUrlFromIntent(intent) ?: return

        val isFromDownloads = intent.getBooleanExtra(EXTRA_FROM_DOWNLOADS, false)
        val isFromExternal = !isFromDownloads && (
            intent.action == Intent.ACTION_VIEW ||
            intent.categories?.contains(Intent.CATEGORY_BROWSABLE) == true ||
            intent.data != null
        )
        val isLocalDoc = com.onyx.browser.web.LocalFileLoader.isLocalFile(this, targetUrl) ||
                com.onyx.browser.web.LocalFileLoader.isPreviewUrl(targetUrl)

        val currentTab = tabManager.activeTab.value
        val targetTab = if (currentTab != null && !currentTab.isIncognito && currentTab.url.isBlank()) {
            // Current tab is an unused blank normal tab: reuse it
            tabManager.updateActiveTab(targetUrl, targetUrl)
            showWebView(currentTab, forceUrl = targetUrl)
            currentTab
        } else {
            // Create a new normal tab for the incoming link so existing tabs remain intact
            val newTab = tabManager.createNewTab(url = targetUrl, isIncognito = false, parentId = currentTab?.id)
            displayTab(newTab)
            newTab
        }

        if (isLocalDoc || intent.action == Intent.ACTION_VIEW) {
            localFileViewTabs.add(targetTab.id)
            if (isFromDownloads) {
                previewTabsFromDownloads.add(targetTab.id)
            } else if (isFromExternal) {
                previewTabsFromExternal.add(targetTab.id)
            }
        }

        if (searchController.isSearchMode) {
            searchController.exitSearchMode()
        }
    }

    private fun extractUrlFromIntent(intent: Intent?): String? {
        if (intent == null) return null

        // Priority 1: Direct data URI from file manager, link click, or download item
        val dataUri = intent.data ?: intent.clipData?.let { if (it.itemCount > 0) it.getItemAt(0).uri else null }
        if (dataUri != null) {
            val uriStr = dataUri.toString().trim()
            if (uriStr.isNotBlank()) {
                return uriStr
            }
        }
        val dataString = intent.dataString?.trim()
        if (!dataString.isNullOrBlank()) {
            return dataString
        }

        val action = intent.action ?: return null

        when (action) {
            Intent.ACTION_VIEW, Intent.ACTION_SEND -> {
                val extraText = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!extraText.isNullOrBlank()) {
                    return parseUrlOrExtract(extraText)
                }
            }
            Intent.ACTION_WEB_SEARCH, Intent.ACTION_SEARCH -> {
                val query = intent.getStringExtra(SearchManager.QUERY)
                    ?: intent.getStringExtra("query")
                if (!query.isNullOrBlank()) {
                    return preferences.searchEngine.buildSearchUrl(query.trim())
                }
            }
        }
        return null
    }

    private fun parseUrlOrExtract(text: String): String {
        val trimmed = text.trim()
        if (LocalFileLoader.isSensitiveOrRestrictedPath(this, trimmed)) {
            return preferences.searchEngine.buildSearchUrl(trimmed)
        }
        if (LocalFileLoader.isLocalFile(this, trimmed) ||
            trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("file://", ignoreCase = true) ||
            trimmed.startsWith("content://", ignoreCase = true) ||
            trimmed.startsWith("about:", ignoreCase = true)
        ) {
            return trimmed
        }
        val urlRegex = Regex("(https?://[^\\s]+)")
        val match = urlRegex.find(trimmed)
        if (match != null) {
            return match.value
        }
        if (isLikelyUrl(trimmed)) {
            return "https://$trimmed"
        }
        return preferences.searchEngine.buildSearchUrl(trimmed)
    }

    private fun enforceHighRefreshRate() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            try {
                val display = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    display
                } else {
                    @Suppress("DEPRECATION")
                    windowManager.defaultDisplay
                }
                val modes = display?.supportedModes
                val highestMode = modes?.maxByOrNull { it.refreshRate }
                if (highestMode != null) {
                    val params = window.attributes
                    params.preferredDisplayModeId = highestMode.modeId
                    window.attributes = params
                }
            } catch (_: Exception) {}
        }
    }

    private fun showSavePageDialog() {
        val activeWebView = tabManager.getActiveWebView() ?: return
        val currentTab = tabManager.activeTab.value ?: return
        pageExportManager.showSavePageDialog(activeWebView, currentTab.title)
    }

    override fun onDestroy() {
        super.onDestroy()
        tabManager.saveAllTabStates()
        if (::pipController.isInitialized) {
            pipController.unregisterReceiver()
        }
        MediaPlaybackService.mediaActionListener = null
        if (isFinishing) {
            tabManager.closeAllTabs(incognitoOnly = true)
            com.onyx.browser.incognito.IncognitoNotificationHelper.dismissNotification(this)
            if (TabManager.activeInstance == tabManager) {
                TabManager.activeInstance = null
            }
        }
        tabManager.clearAllWebViews()
        if (currentInstance?.get() == this) {
            currentInstance = null
        }
    }
}
