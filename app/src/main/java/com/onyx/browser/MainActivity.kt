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
import com.onyx.browser.ui.browser.TabManager
import com.onyx.browser.ui.common.SearchEnginePickerDialog
import com.onyx.browser.ui.common.SearchEnginePopupMenu
import com.onyx.browser.ui.downloads.DownloadsActivity
import com.onyx.browser.ui.history.HistoryActivity
import com.onyx.browser.ui.home.EditShortcutDialog
import com.onyx.browser.ui.home.ManageShortcutsBottomSheet
import com.onyx.browser.ui.home.ShortcutsAdapter
import com.onyx.browser.ui.menu.MenuBottomSheetDialogFragment
import com.onyx.browser.ui.menu.ContextMenuBottomSheet
import com.onyx.browser.ui.menu.LanguageSelectionDialog
import com.onyx.browser.ui.search.SuggestionsAdapter
import com.onyx.browser.ui.tabs.TabSwitcherBottomSheet
import com.onyx.browser.web.DownloadHandler
import com.onyx.browser.web.LocalFileLoader
import com.onyx.browser.web.OnyxWebChromeClient
import com.onyx.browser.web.OnyxWebView
import com.onyx.browser.web.OnyxWebViewClient
import com.onyx.browser.data.filter.FilterListManager
import com.onyx.browser.media.MediaPlaybackBridge
import com.onyx.browser.media.MediaPlaybackService
import com.onyx.browser.web.MediaPlaybackManager
import com.onyx.browser.web.translate.PageTranslateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    lateinit var tabManager: TabManager
    private lateinit var preferences: BrowserPreferences

    fun getActiveWebView(): OnyxWebView? = if (::tabManager.isInitialized) tabManager.getActiveWebView() else null
    private lateinit var suggestionRepository: SearchSuggestionRepository
    private lateinit var suggestionsAdapter: SuggestionsAdapter
    private lateinit var shortcutsAdapter: ShortcutsAdapter
    private var suggestionJob: Job? = null

    private var isSearchMode = false
    private var customVideoView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var shouldAutoEnterPipOnCustomView: Boolean = false
    private var currentDisplayedTabId: String? = null
    private var isTabsRestored = false
    private var pendingIntent: Intent? = null
    private var wasShowingWebViewBeforePip = false

    companion object {
        const val ACTION_PIP_PLAY_PAUSE = "com.onyx.browser.action.PIP_PLAY_PAUSE"
        const val ACTION_PIP_REWIND = "com.onyx.browser.action.PIP_REWIND"
        const val ACTION_PIP_FORWARD = "com.onyx.browser.action.PIP_FORWARD"
        const val ACTION_WIDGET_SEARCH = "com.onyx.browser.action.WIDGET_SEARCH"
        const val ACTION_WIDGET_VOICE_SEARCH = "com.onyx.browser.action.WIDGET_VOICE_SEARCH"
        const val ACTION_WIDGET_INCOGNITO_SEARCH = "com.onyx.browser.action.WIDGET_INCOGNITO_SEARCH"
    }

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PIP_PLAY_PAUSE -> {
                    if (MediaPlaybackBridge.isMediaPlaying) {
                        tabManager.getActiveWebView()?.evaluateJavascript(MediaPlaybackManager.pauseAllMediaScript, null)
                        MediaPlaybackBridge.isMediaPlaying = false
                    } else {
                        tabManager.getActiveWebView()?.evaluateJavascript(MediaPlaybackManager.playAllMediaScript, null)
                        MediaPlaybackBridge.isMediaPlaying = true
                    }
                    updatePipParams()
                }
                ACTION_PIP_REWIND -> {
                    tabManager.getActiveWebView()?.evaluateJavascript(MediaPlaybackManager.getSeekMediaScript(-10), null)
                }
                ACTION_PIP_FORWARD -> {
                    tabManager.getActiveWebView()?.evaluateJavascript(MediaPlaybackManager.getSeekMediaScript(10), null)
                }
            }
        }
    }

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enforceHighRefreshRate()
        
        preferences = BrowserPreferences.getInstance(this)
        preferences.applyTheme()
        tabManager = TabManager(this, lifecycleScope)

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
            if (!failingUrl.isNullOrBlank()) {
                wv.clearSyntheticState()
                wv.loadUrl(failingUrl)
                binding.swipeRefreshLayout.isRefreshing = false
            } else if (activeTab != null && wv != null && LocalFileLoader.isLocalFile(activeTab.url)) {
                LocalFileLoader.loadLocalFile(this, wv, activeTab.url)
                binding.swipeRefreshLayout.isRefreshing = false
            } else {
                wv?.reload() 
            }
        }
        binding.swipeRefreshLayout.setOnChildScrollUpCallback { _, _ ->
            // Block pull-to-refresh on homepage and when webview can't scroll up
            if (binding.homeLayout.root.visibility == View.VISIBLE) return@setOnChildScrollUpCallback true
            val webView = tabManager.getActiveWebView()
            webView != null && webView.scrollY > 0
        }

        val database = AppDatabase.getInstance(this)
        suggestionRepository = SearchSuggestionRepository(database.historyDao(), database.bookmarkDao())

        setupTopToolbar()
        setupSearchOverlay()
        setupFindInPage()
        setupTranslateBar()
        setupHomepageInteractions()
        setupBackNavigation()
        setupMediaPlaybackListener()

        val pipFilter = IntentFilter().apply {
            addAction(ACTION_PIP_PLAY_PAUSE)
            addAction(ACTION_PIP_REWIND)
            addAction(ACTION_PIP_FORWARD)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pipReceiver, pipFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(pipReceiver, pipFilter)
        }

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
            exitSearchMode()
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
                    if (isSearchMode && currentQuery.isNotEmpty()) {
                        fetchSearchSuggestions(currentQuery)
                    }
                }
            )
            popup.show(binding.btnSearchEngine)
        }

        // Home Button
        binding.btnHome.setOnClickListener {
            val currentTab = tabManager.activeTab.value
            if (currentTab != null) {
                tabManager.updateActiveTab("", "New Tab")
                showHomeScreen()
            }
        }

        // Address Bar Focus & Search Mode
        binding.etUrl.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && !isSearchMode) {
                enterSearchMode()
            }
        }

        binding.etUrl.setOnClickListener {
            if (!isSearchMode) {
                enterSearchMode()
            }
        }

        binding.searchBarContainer.setOnClickListener {
            if (!isSearchMode) {
                enterSearchMode()
            }
        }

        binding.etUrl.doAfterTextChanged { text ->
            if (isSearchMode) {
                val query = text?.toString()?.trim() ?: ""
                val hasText = query.isNotEmpty()
                binding.btnClearUrl.visibility = if (hasText) View.VISIBLE else View.GONE
                binding.btnQrScanner.visibility = View.VISIBLE
                binding.btnVoiceSearch.visibility = if (hasText) View.GONE else View.VISIBLE

                val currentTab = tabManager.activeTab.value
                val hasCurrentUrl = !currentTab?.url.isNullOrBlank()

                if (hasText) {
                    binding.cardCurrentPage.visibility = View.GONE
                    fetchSearchSuggestions(query)
                } else {
                    val curUrl = getActivePageUrl()
                    if (curUrl.isNotBlank()) {
                        binding.cardCurrentPage.visibility = View.VISIBLE
                        com.onyx.browser.data.favicon.FaviconManager.loadFavicon(
                            context = this@MainActivity,
                            imageView = binding.ivCurrentPageFavicon,
                            urlOrHost = curUrl,
                            isCircular = true
                        )
                    } else {
                        binding.cardCurrentPage.visibility = View.GONE
                    }
                    suggestionJob?.cancel()
                    val clipboardOpt = getClipboardSuggestion()
                    suggestionsAdapter.submitList(if (clipboardOpt != null) listOf(clipboardOpt) else emptyList())
                }
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
                    enterSearchMode()
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
                    showFindInPage()
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
            tabManager.incognitoTabs.collectLatest {
                updateTabBadgeCount()
            }
        }
    }

    private fun displayTab(tab: TabItem) {
        updateTabBadgeCount()

        val previousTabId = currentDisplayedTabId
        val tabChanged = (previousTabId != tab.id)
        if (tabChanged && previousTabId != null) {
            val outgoingWebView = tabManager.getWebView(previousTabId)
            if (outgoingWebView != null) {
                tabManager.captureTabSnapshot(previousTabId, outgoingWebView)
            }
        }
        currentDisplayedTabId = tab.id

        if (tab.url.isBlank()) {
            showHomeScreen()
        } else {
            showWebView(tab, reloadIfChanged = tabChanged)
        }

        // If we switch to a tab, we are no longer watching the video from the previous tab.
        // Clear the KEEP_SCREEN_ON flag and exit search mode.
        if (tabChanged) {
            exitSearchMode()
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    fun showHomeScreen() {
        binding.homeLayout.root.visibility = View.VISIBLE
        binding.webViewContainer.visibility = View.GONE
        binding.etUrl.setText("")
        binding.ivSslLock.visibility = View.GONE
        binding.progressBar.visibility = View.GONE
        binding.swipeRefreshLayout.isEnabled = false
        hideTranslateBar(restoreOriginal = false)

        val isIncognito = tabManager.activeTab.value?.isIncognito == true
        updateIncognitoUI(isIncognito)
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
        binding.homeLayout.root.visibility = View.GONE
        binding.webViewContainer.visibility = View.VISIBLE
        binding.swipeRefreshLayout.isEnabled = true

        updateIncognitoUI(tab.isIncognito)

        val webView = tabManager.getOrCreateWebView(tab)
        attachWebViewToContainer(webView)

        val targetUrl = forceUrl ?: tab.url
        if (targetUrl.isNotBlank()) {
            if (forceUrl != null || reloadIfChanged || webView.url.isNullOrBlank() || webView.url == "about:blank") {
                try {
                    if (LocalFileLoader.isLocalFile(targetUrl)) {
                        LocalFileLoader.loadLocalFile(this, webView, targetUrl) { title ->
                            tabManager.updateActiveTab(targetUrl, title)
                        }
                    } else {
                        webView.loadUrl(targetUrl)
                    }
                } catch (t: Throwable) {
                    Toast.makeText(this, "Failed to load URL: ${t.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        updateAddressBarDisplay(if (LocalFileLoader.isLocalFile(targetUrl)) targetUrl else (webView.url ?: targetUrl))
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
        webView.onScrollChangedCallback = { _, t, _, oldt ->
            if (preferences.isScrollToTopEnabled && currentDisplayedTabId == tabManager.activeTab.value?.id) {
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
                val cleanUrl = if (newUrl.startsWith("data:") || newUrl.startsWith("file:///android_asset/error_page")) {
                    webView.currentSyntheticState?.failingUrl ?: tabManager.activeTab.value?.url ?: ""
                } else {
                    newUrl
                }
                if (cleanUrl.isNotBlank() && !cleanUrl.startsWith("data:")) {
                    tabManager.updateActiveTab(cleanUrl, webView.title ?: cleanUrl)
                    updateAddressBarDisplay(cleanUrl)
                }
            },
            onPageFinishedCallback = { finishedUrl ->
                val cleanUrl = if (finishedUrl.startsWith("data:") || finishedUrl.startsWith("file:///android_asset/error_page")) {
                    webView.currentSyntheticState?.failingUrl ?: tabManager.activeTab.value?.url ?: ""
                } else {
                    finishedUrl
                }
                if (cleanUrl.isNotBlank() && !cleanUrl.startsWith("data:")) {
                    tabManager.updateActiveTab(cleanUrl, webView.title ?: cleanUrl)
                    updateAddressBarDisplay(cleanUrl)
                }
                binding.progressBar.visibility = View.GONE
                val activeTab = tabManager.activeTab.value
                if (activeTab != null && (webView.url == finishedUrl || webView.currentSyntheticState != null)) {
                    webView.postDelayed({
                        tabManager.captureTabSnapshot(activeTab.id, webView)
                    }, 400)
                }
            },
            onPageCommitVisibleCallback = { view, _ ->
                val activeTab = tabManager.activeTab.value
                if (activeTab != null && view is OnyxWebView) {
                    view.postDelayed({
                        tabManager.captureTabSnapshot(activeTab.id, view)
                    }, 300)
                }
            }
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
                tabManager.updateActiveTab(webView.url ?: "", title)
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
            onCreateWindowCallback = { _, isDialog, isUserGesture, resultMsg ->
                // Only open a new tab when triggered by an explicit user gesture (tap/click).
                // Script-driven window.open() calls (ads, pop-unders, redirect loops) have
                // isUserGesture=false and must be silently blocked.
                if (!isUserGesture || resultMsg == null) {
                    false
                } else {
                    val newTab = tabManager.createNewTab()
                    val newWebView = tabManager.getOrCreateWebView(newTab)

                    // Pre-setup the clients before passing it back, so it instantly has download listeners
                    setupWebViewClients(newWebView)

                    val transport = resultMsg.obj as? WebView.WebViewTransport
                    if (transport != null) {
                        transport.webView = newWebView
                        resultMsg.sendToTarget()

                        // Force show the WebView regardless of the URL being blank
                        currentDisplayedTabId = newTab.id
                        updateTabBadgeCount()
                        showWebView(newTab)

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
        val newTab = tabManager.createNewTab(url = url, isIncognito = false)
        // Explicitly set as active tab and navigate to it, ensuring currentDisplayedTabId
        // is updated before the StateFlow observer fires to avoid a no-op reload.
        tabManager.selectTab(newTab)
        currentDisplayedTabId = newTab.id
        updateTabBadgeCount()
        showWebView(newTab, forceUrl = url, reloadIfChanged = true)
    }

    private fun openUrlInIncognitoTab(url: String) {
        val newTab = tabManager.createNewTab(url = url, isIncognito = true)
        tabManager.selectTab(newTab)
        currentDisplayedTabId = newTab.id
        updateTabBadgeCount()
        showWebView(newTab, forceUrl = url, reloadIfChanged = true)
    }

    private fun startQrScanner() {
        qrScannerLauncher.launch(Intent(this, com.onyx.browser.ui.qr.QrScannerActivity::class.java))
    }

    fun performSearchOrLoad(input: String) {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return

        if (trimmed == "onyx://bookmarks") {
            startActivity(Intent(this, BookmarksActivity::class.java))
            return
        }
        if (trimmed == "onyx://history") {
            historyLauncher.launch(Intent(this, HistoryActivity::class.java))
            return
        }
        if (trimmed == "onyx://downloads") {
            startActivity(Intent(this, DownloadsActivity::class.java))
            return
        }
        if (trimmed == "onyx://qr") {
            qrScannerLauncher.launch(Intent(this, com.onyx.browser.ui.qr.QrScannerActivity::class.java))
            return
        }

        val url = when {
            LocalFileLoader.isLocalFile(trimmed) -> trimmed

            trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("file://", ignoreCase = true) ||
            trimmed.startsWith("about:", ignoreCase = true) ||
            trimmed.startsWith("data:", ignoreCase = true) -> trimmed

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
        if (isSearchMode) {
            exitSearchMode()
        }
    }

    private fun isLikelyUrl(input: String): Boolean {
        if (input.contains(" ")) return false
        if (input.equals("localhost", ignoreCase = true) ||
            input.startsWith("localhost:", ignoreCase = true) ||
            input.matches(Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}(:\\d+)?(/.*)?$"))) {
            return true
        }
        val domainRegex = Regex("^[a-zA-Z0-9][-a-zA-Z0-9]*(\\.[a-zA-Z0-9][-a-zA-Z0-9]*)+(:\\d+)?(/.*)?$")
        return domainRegex.matches(input)
    }

    private fun setupSearchOverlay() {
        suggestionsAdapter = SuggestionsAdapter(
            onSuggestionClicked = { suggestion ->
                performSearchOrLoad(suggestion.queryOrUrl)
                hideSoftKeyboard()
            },
            onInsertClicked = { suggestion ->
                binding.etUrl.setText(suggestion.queryOrUrl)
                binding.etUrl.setSelection(binding.etUrl.text?.length ?: 0)
            }
        )
        binding.rvSearchSuggestions.layoutManager = LinearLayoutManager(this)
        binding.rvSearchSuggestions.adapter = suggestionsAdapter

        // Current Webpage Card Actions: Share, Copy, Edit
        binding.btnCurrentPageShare.setOnClickListener {
            val url = getActivePageUrl()
            if (url.isNotBlank()) {
                val sendIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_TEXT, url)
                    type = "text/plain"
                }
                startActivity(Intent.createChooser(sendIntent, getString(R.string.share)))
            }
        }

        binding.btnCurrentPageCopy.setOnClickListener {
            val url = getActivePageUrl()
            if (url.isNotBlank()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clip = ClipData.newPlainText("URL", url)
                clipboard?.setPrimaryClip(clip)
                Toast.makeText(this, getString(R.string.link_copied), Toast.LENGTH_SHORT).show()
            }
        }

        // Edit button populates the clean search bar with this URL so user can customize it
        binding.btnCurrentPageEdit.setOnClickListener {
            val url = getActivePageUrl()
            if (url.isNotBlank()) {
                binding.etUrl.setText(url)
                binding.etUrl.setSelection(binding.etUrl.text?.length ?: 0)
                binding.etUrl.requestFocus()
                showSoftKeyboard()
            }
        }

        binding.containerPageInfo.setOnClickListener {
            val url = getActivePageUrl()
            if (url.isNotBlank()) {
                binding.etUrl.setText(url)
                binding.etUrl.setSelection(binding.etUrl.text?.length ?: 0)
                binding.etUrl.requestFocus()
                showSoftKeyboard()
            }
        }
    }

    private fun enterSearchMode() {
        if (isSearchMode) return
        isSearchMode = true

        // 1. Transform top toolbar into search mode
        binding.btnHome.visibility = View.GONE
        binding.btnSearchBack.visibility = View.VISIBLE
        binding.btnTabSwitcher.visibility = View.GONE
        binding.btnMenu.visibility = View.GONE
        binding.ivSslLock.visibility = View.GONE

        // 2. Open Search Overlay Page
        binding.searchOverlay.visibility = View.VISIBLE

        // 3. Configure Current Webpage Card under search bar
        val currentTab = tabManager.activeTab.value
        val curUrl = getActivePageUrl()
        val hasCurrentUrl = curUrl.isNotBlank()

        if (hasCurrentUrl) {
            binding.cardCurrentPage.visibility = View.VISIBLE
            val displayTitle = currentTab?.title?.takeIf {
                it.isNotBlank() && !it.startsWith("data:") && !it.startsWith("net::") && it != "Page Not Available"
            } ?: curUrl
            binding.tvCurrentPageTitle.text = displayTitle
            binding.tvCurrentPageUrl.text = curUrl
            com.onyx.browser.data.favicon.FaviconManager.loadFavicon(
                context = this,
                imageView = binding.ivCurrentPageFavicon,
                urlOrHost = curUrl,
                isCircular = true
            )
        } else {
            binding.cardCurrentPage.visibility = View.GONE
        }

        // Clean search bar for fresh input as requested
        binding.etUrl.setText("")
        binding.etUrl.hint = getString(R.string.search_or_type_url)
        binding.btnClearUrl.visibility = View.GONE
        binding.btnQrScanner.visibility = View.VISIBLE
        binding.btnVoiceSearch.visibility = View.VISIBLE

        // 4. Focus search bar & show keyboard
        binding.etUrl.requestFocus()
        showSoftKeyboard()

        // 5. Inject clipboard suggestion if available
        val clipboardOpt = getClipboardSuggestion()
        suggestionsAdapter.submitList(if (clipboardOpt != null) listOf(clipboardOpt) else emptyList())
    }

    private fun exitSearchMode() {
        if (!isSearchMode) return
        isSearchMode = false

        suggestionJob?.cancel()

        // 1. Restore top toolbar
        binding.btnSearchBack.visibility = View.GONE
        binding.btnHome.visibility = View.VISIBLE
        binding.btnTabSwitcher.visibility = View.VISIBLE
        binding.btnMenu.visibility = View.VISIBLE
        binding.btnClearUrl.visibility = View.GONE
        binding.btnQrScanner.visibility = View.GONE
        binding.btnVoiceSearch.visibility = View.VISIBLE

        // 2. Hide search overlay
        binding.searchOverlay.visibility = View.GONE
        binding.cardCurrentPage.visibility = View.GONE
        suggestionsAdapter.submitList(emptyList())

        // 3. Hide keyboard & clear focus
        hideSoftKeyboard()
        binding.etUrl.clearFocus()

        // 4. Restore address bar host display
        updateAddressBarDisplay(getActivePageUrl())
    }

    private fun fetchSearchSuggestions(query: String) {
        suggestionJob?.cancel()
        // Require at least 2 chars — single char gives irrelevant results and
        // wastes network bandwidth. Repository enforces the same guard.
        if (query.length < 2) {
            val clipboardOpt = getClipboardSuggestion()
            suggestionsAdapter.submitList(if (clipboardOpt != null) listOf(clipboardOpt) else emptyList())
            return
        }
        suggestionJob = lifecycleScope.launch {
            // 300ms debounce: waits for user to briefly pause typing
            // before firing the network request and DB query.
            delay(300)
            val suggestions = suggestionRepository.getSuggestions(query, preferences.searchEngine)
            if (isSearchMode) {
                val clipboardOpt = getClipboardSuggestion()
                val finalList = if (clipboardOpt != null) {
                    val list = suggestions.toMutableList()
                    list.add(0, clipboardOpt)
                    list
                } else {
                    suggestions
                }
                suggestionsAdapter.submitList(finalList)
            }
        }
    }

    private fun getClipboardSuggestion(): com.onyx.browser.data.model.SearchSuggestion? {
        try {
            val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            if (clipboard.hasPrimaryClip()) {
                val clipData = clipboard.primaryClip
                if (clipData != null && clipData.itemCount > 0) {
                    val text = clipData.getItemAt(0).text?.toString()?.trim()
                    if (!text.isNullOrBlank() && (android.util.Patterns.WEB_URL.matcher(text).matches() || text.startsWith("http"))) {
                        return com.onyx.browser.data.model.SearchSuggestion(
                            title = "Link from clipboard",
                            queryOrUrl = text,
                            isClipboard = true
                        )
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun getActivePageUrl(): String {
        val activeWv = tabManager.getActiveWebView()
        val failingUrl = activeWv?.currentSyntheticState?.failingUrl
        if (!failingUrl.isNullOrBlank()) {
            return failingUrl
        }
        val tabUrl = tabManager.activeTab.value?.url ?: ""
        if (tabUrl.startsWith("data:") || tabUrl.startsWith("file:///android_asset/error_page")) {
            return ""
        }
        return tabUrl
    }

    private fun updateAddressBarDisplay(url: String) {
        val activeWv = tabManager.getActiveWebView()
        val displayUrl = when {
            url.isBlank() || url.startsWith("data:") || url.startsWith("file:///android_asset/error_page") -> {
                activeWv?.currentSyntheticState?.failingUrl ?: tabManager.activeTab.value?.url ?: ""
            }
            else -> url
        }

        if (displayUrl.isBlank() || displayUrl.startsWith("data:") || displayUrl.startsWith("file:///android_asset/error_page")) {
            binding.etUrl.setText("")
            binding.ivSslLock.visibility = View.GONE
            return
        }

        val isHttps = displayUrl.startsWith("https://")
        binding.ivSslLock.visibility = if (isHttps) View.VISIBLE else View.GONE

        val host = when {
            LocalFileLoader.isLocalFile(displayUrl) -> {
                LocalFileLoader.getDisplayName(this, LocalFileLoader.parseUri(displayUrl))
            }
            else -> try {
                Uri.parse(displayUrl).host ?: displayUrl
            } catch (e: Exception) {
                displayUrl
            }
        }

        if (!isSearchMode) {
            binding.etUrl.setText(host)
        }
    }

    private fun updateSearchEngineIcon() {
        binding.btnSearchEngine.setImageResource(preferences.searchEngine.iconResId)
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

    private fun showCustomFullscreenVideo(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customVideoView != null) {
            callback.onCustomViewHidden()
            return
        }

        customVideoView = view
        customViewCallback = callback

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // Insert at index 0 so fullscreenControlsOverlay stays on top
        binding.fullscreenCustomViewContainer.addView(
            view,
            0,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        binding.fullscreenCustomViewContainer.visibility = View.VISIBLE
        binding.fullscreenControlsOverlay.visibility = View.VISIBLE

        binding.btnFullscreenPip.setOnClickListener {
            enterPipMode()
        }
        binding.btnFullscreenClose.setOnClickListener {
            hideCustomFullscreenVideo()
        }

        updatePipParams(true)

        if (shouldAutoEnterPipOnCustomView) {
            shouldAutoEnterPipOnCustomView = false
            binding.root.post {
                enterPipMode()
            }
        }
    }

    private fun hideCustomFullscreenVideo() {
        if (customVideoView == null) return

        binding.fullscreenControlsOverlay.visibility = View.GONE
        binding.fullscreenCustomViewContainer.removeView(customVideoView)
        binding.fullscreenCustomViewContainer.visibility = View.GONE
        customVideoView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null

        updatePipParams(false)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        binding.topBar.requestLayout()
        binding.root.requestLayout()
    }

    private fun Rational.coerceIn(min: Rational, max: Rational): Rational {
        val currentVal = toFloat()
        val minVal = min.toFloat()
        val maxVal = max.toFloat()
        return when {
            currentVal < minVal -> min
            currentVal > maxVal -> max
            else -> this
        }
    }

    private fun buildPipActions(): List<RemoteAction> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return emptyList()

        val actions = mutableListOf<RemoteAction>()

        // 1. Rewind 10s
        val rewindIntent = PendingIntent.getBroadcast(
            this,
            101,
            Intent(ACTION_PIP_REWIND).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        actions.add(
            RemoteAction(
                Icon.createWithResource(this, R.drawable.ic_fast_rewind),
                "Rewind 10s",
                "Rewind 10 seconds",
                rewindIntent
            )
        )

        // 2. Play / Pause
        val isPlaying = MediaPlaybackBridge.isMediaPlaying
        val playPauseIntent = PendingIntent.getBroadcast(
            this,
            102,
            Intent(ACTION_PIP_PLAY_PAUSE).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        actions.add(
            RemoteAction(
                Icon.createWithResource(this, if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow),
                if (isPlaying) "Pause" else "Play",
                if (isPlaying) "Pause video" else "Play video",
                playPauseIntent
            )
        )

        // 3. Fast Forward 10s
        val forwardIntent = PendingIntent.getBroadcast(
            this,
            103,
            Intent(ACTION_PIP_FORWARD).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        actions.add(
            RemoteAction(
                Icon.createWithResource(this, R.drawable.ic_fast_forward),
                "Forward 10s",
                "Fast forward 10 seconds",
                forwardIntent
            )
        )

        return actions
    }

    fun updatePipParams(
        isVideoPlaying: Boolean = MediaPlaybackBridge.isVideoPlaying,
        width: Int = MediaPlaybackBridge.lastVideoWidth,
        height: Int = MediaPlaybackBridge.lastVideoHeight
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                // Only enable OS autoEnterEnabled when a dedicated custom fullscreen video view is active.
                // For in-page videos, PiP is initiated cleanly on onUserLeaveHint or PiP button tap.
                val shouldEnableAutoPip = preferences.isPipEnabled && (customVideoView != null)
                val rational = Rational(width.coerceAtLeast(1), height.coerceAtLeast(1))
                    .coerceIn(Rational(1, 2), Rational(2, 1))
                val builder = PictureInPictureParams.Builder()
                    .setAspectRatio(rational)
                    .setActions(buildPipActions())

                // Video-only PiP: crop strictly to the video viewport using sourceRectHint
                if (customVideoView != null) {
                    val rect = Rect()
                    customVideoView?.getGlobalVisibleRect(rect)
                    if (!rect.isEmpty) {
                        builder.setSourceRectHint(rect)
                    }
                } else {
                    val bounds = MediaPlaybackBridge.lastVideoBounds
                    val activeWv = tabManager.getActiveWebView()
                    if (bounds != null && activeWv != null && isVideoPlaying) {
                        val location = IntArray(2)
                        activeWv.getLocationInWindow(location)
                        val density = resources.displayMetrics.density
                        val left = (location[0] + bounds.left * density).toInt()
                        val top = (location[1] + bounds.top * density).toInt()
                        val right = (location[0] + bounds.right * density).toInt()
                        val bottom = (location[1] + bounds.bottom * density).toInt()
                        val rect = Rect(
                            left.coerceAtLeast(0),
                            top.coerceAtLeast(0),
                            right.coerceAtMost(resources.displayMetrics.widthPixels),
                            bottom.coerceAtMost(resources.displayMetrics.heightPixels)
                        )
                        if (rect.width() > 20 && rect.height() > 20) {
                            builder.setSourceRectHint(rect)
                        }
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    builder.setAutoEnterEnabled(shouldEnableAutoPip)
                }
                setPictureInPictureParams(builder.build())
            } catch (_: Exception) {}
        }
    }

    fun requestInPageVideoPip() {
        if (!preferences.isPipEnabled) return
        if (customVideoView != null) {
            enterPipMode()
            return
        }
        val activeWv = tabManager.getActiveWebView()
        if (activeWv == null) {
            Toast.makeText(this, "No active webpage to extract video", Toast.LENGTH_SHORT).show()
            return
        }
        shouldAutoEnterPipOnCustomView = true
        // Try driving active video into native fullscreen first for hardware-isolated PiP
        activeWv.evaluateJavascript(MediaPlaybackManager.requestVideoFullscreenScript) { res ->
            if (res?.contains("fullscreen_triggered") == true) {
                // onShowCustomView will handle entering PiP when customVideoView attaches.
                // Add fallback in case onShowCustomView does not fire:
                activeWv.postDelayed({
                    if (customVideoView == null && shouldAutoEnterPipOnCustomView) {
                        shouldAutoEnterPipOnCustomView = false
                        activeWv.evaluateJavascript(MediaPlaybackManager.isolateVideoForPipScript) {
                            enterPipMode()
                        }
                    }
                }, 350)
            } else {
                shouldAutoEnterPipOnCustomView = false
                activeWv.evaluateJavascript(MediaPlaybackManager.isolateVideoForPipScript) {
                    enterPipMode()
                }
            }
        }
    }

    fun enterPipMode() {
        if (!preferences.isPipEnabled) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val appOps = getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                val isPipAllowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    appOps?.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, android.os.Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
                } else {
                    @Suppress("DEPRECATION")
                    appOps?.checkOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, android.os.Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
                }
                if (isPipAllowed == false) {
                    Toast.makeText(this, "Please enable Picture-in-Picture permission in Android Settings", Toast.LENGTH_LONG).show()
                    val intent = Intent("android.settings.PICTURE_IN_PICTURE_SETTINGS", Uri.parse("package:$packageName"))
                    try {
                        startActivity(intent)
                    } catch (_: Exception) {
                        startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                    }
                    return
                }

                val w = MediaPlaybackBridge.lastVideoWidth.coerceAtLeast(1)
                val h = MediaPlaybackBridge.lastVideoHeight.coerceAtLeast(1)
                val rational = Rational(w, h).coerceIn(Rational(1, 2), Rational(2, 1))

                val paramsBuilder = PictureInPictureParams.Builder()
                    .setAspectRatio(rational)
                    .setActions(buildPipActions())

                if (customVideoView != null) {
                    val rect = Rect()
                    customVideoView?.getGlobalVisibleRect(rect)
                    if (!rect.isEmpty) {
                        paramsBuilder.setSourceRectHint(rect)
                    }
                } else {
                    // Hide browser UI before transition so only the isolated video is captured
                    binding.topBar.visibility = View.GONE
                    binding.topBarDivider.visibility = View.GONE
                    binding.fullscreenControlsOverlay.visibility = View.GONE
                    binding.progressBar.visibility = View.GONE
                    binding.findInPageBar.visibility = View.GONE
                    binding.searchOverlay.visibility = View.GONE

                    val bounds = MediaPlaybackBridge.lastVideoBounds
                    val activeWv = tabManager.getActiveWebView()
                    if (bounds != null && activeWv != null) {
                        val location = IntArray(2)
                        activeWv.getLocationInWindow(location)
                        val density = resources.displayMetrics.density
                        val left = (location[0] + bounds.left * density).toInt()
                        val top = (location[1] + bounds.top * density).toInt()
                        val right = (location[0] + bounds.right * density).toInt()
                        val bottom = (location[1] + bounds.bottom * density).toInt()
                        val rect = Rect(
                            left.coerceAtLeast(0),
                            top.coerceAtLeast(0),
                            right.coerceAtMost(resources.displayMetrics.widthPixels),
                            bottom.coerceAtMost(resources.displayMetrics.heightPixels)
                        )
                        if (rect.width() > 20 && rect.height() > 20) {
                            paramsBuilder.setSourceRectHint(rect)
                        }
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    paramsBuilder.setAutoEnterEnabled(customVideoView != null)
                }

                enterPictureInPictureMode(paramsBuilder.build())
            } catch (e: Exception) {
                Toast.makeText(this, "Unable to enter Picture-in-Picture: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Picture-in-Picture requires Android 8.0+", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupMediaPlaybackListener() {
        MediaPlaybackService.mediaActionListener = object : MediaPlaybackService.MediaActionListener {
            override fun onPlayMedia() {
                runOnUiThread {
                    tabManager.getActiveWebView()?.evaluateJavascript(MediaPlaybackManager.playAllMediaScript, null)
                }
            }

            override fun onPauseMedia() {
                runOnUiThread {
                    tabManager.getActiveWebView()?.evaluateJavascript(MediaPlaybackManager.pauseAllMediaScript, null)
                }
            }

            override fun onSeekMedia(deltaSeconds: Int) {
                runOnUiThread {
                    tabManager.getActiveWebView()?.evaluateJavascript(MediaPlaybackManager.getSeekMediaScript(deltaSeconds), null)
                }
            }

            override fun onSeekToMedia(positionMs: Long) {
                runOnUiThread {
                    val posSec = positionMs / 1000.0
                    tabManager.getActiveWebView()?.evaluateJavascript(MediaPlaybackManager.getSeekToPositionScript(posSec), null)
                }
            }

            override fun onStopMedia() {
                runOnUiThread {
                    tabManager.getActiveWebView()?.evaluateJavascript(MediaPlaybackManager.pauseAllMediaScript, null)
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
                if (MediaPlaybackBridge.isVideoPlaying) {
                    updatePipParams()
                }
            }
        }

        MediaPlaybackBridge.onPipRequestedListener = {
            runOnUiThread {
                requestInPageVideoPip()
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

    private var isRegexFindEnabled = false

    private fun setupFindInPage() {
        binding.btnCloseFind.setOnClickListener {
            hideFindInPage()
        }

        binding.btnRegexToggle.setOnClickListener {
            isRegexFindEnabled = !isRegexFindEnabled
            binding.btnRegexToggle.setTextColor(if (isRegexFindEnabled) getColor(R.color.primary) else android.graphics.Color.GRAY)
            val webView = tabManager.getActiveWebView()
            webView?.regexFindBridge?.setRegexMode(isRegexFindEnabled)
            val query = binding.etFindQuery.text?.toString()?.trim() ?: ""
            if (query.isNotEmpty()) {
                webView?.regexFindBridge?.find(query)
            }
        }

        binding.etFindQuery.doAfterTextChanged { text ->
            val query = text?.toString()?.trim() ?: ""
            val webView = tabManager.getActiveWebView()
            if (query.isNotEmpty()) {
                webView?.regexFindBridge?.find(query)
            } else {
                webView?.regexFindBridge?.clearMatches()
                binding.tvFindMatches.text = getString(R.string.no_matches)
            }
        }

        binding.etFindQuery.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                tabManager.getActiveWebView()?.regexFindBridge?.findNext(true)
                true
            } else {
                false
            }
        }

        binding.btnFindPrev.setOnClickListener {
            tabManager.getActiveWebView()?.regexFindBridge?.findNext(false)
        }

        binding.btnFindNext.setOnClickListener {
            tabManager.getActiveWebView()?.regexFindBridge?.findNext(true)
        }
    }

    private fun showFindInPage() {
        val webView = tabManager.getActiveWebView() ?: return
        binding.findInPageBar.visibility = View.VISIBLE
        binding.tvFindMatches.text = getString(R.string.no_matches)

        webView.setFindListener { activeMatchOrdinal, numberOfMatches, _ ->
            if (numberOfMatches > 0) {
                binding.tvFindMatches.text = getString(R.string.matches_count, activeMatchOrdinal + 1, numberOfMatches)
            } else {
                binding.tvFindMatches.text = getString(R.string.no_matches)
            }
        }

        binding.etFindQuery.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(binding.etFindQuery, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideFindInPage() {
        binding.findInPageBar.visibility = View.GONE
        tabManager.getActiveWebView()?.regexFindBridge?.clearMatches()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.etFindQuery.windowToken, 0)
        binding.etFindQuery.setText("")
    }

    private var currentTranslateTargetCode: String = "en"

    private fun setupTranslateBar() {
        binding.toggleTranslateMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val wv = tabManager.getActiveWebView() ?: return@addOnButtonCheckedListener
            when (checkedId) {
                R.id.btnTranslateOriginal -> {
                    wv.evaluateJavascript(PageTranslateManager.restoreOriginalScript, null)
                }
                R.id.btnTranslateTarget -> {
                    binding.pbTranslateLoading.visibility = View.VISIBLE
                    wv.evaluateJavascript(PageTranslateManager.getSwitchLanguageScript(currentTranslateTargetCode)) {
                        binding.pbTranslateLoading.visibility = View.GONE
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
                binding.toggleTranslateMode.check(R.id.btnTranslateTarget)
                val wv = tabManager.getActiveWebView() ?: return@LanguageSelectionDialog
                val activeTab = tabManager.activeTab.value
                if (activeTab != null && activeTab.url.isNotBlank()) {
                    PageTranslateManager.setupCookies(activeTab.url, code)
                }
                binding.pbTranslateLoading.visibility = View.VISIBLE
                wv.evaluateJavascript(PageTranslateManager.getSwitchLanguageScript(code)) {
                    binding.pbTranslateLoading.visibility = View.GONE
                }
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
        binding.toggleTranslateMode.check(R.id.btnTranslateTarget)
        binding.translateBar.visibility = View.VISIBLE
    }

    private fun hideTranslateBar(restoreOriginal: Boolean = false) {
        binding.translateBar.visibility = View.GONE
        binding.pbTranslateLoading.visibility = View.GONE
        if (restoreOriginal) {
            val activeTab = tabManager.activeTab.value
            if (activeTab != null && activeTab.url.isNotBlank()) {
                PageTranslateManager.clearCookies(activeTab.url)
            }
            // Try JS restore first; JS will reload on its own if needed
            tabManager.getActiveWebView()?.evaluateJavascript(PageTranslateManager.restoreOriginalScript, null)
        }
    }

    private fun translateCurrentPage(langCode: String) {
        val activeTab = tabManager.activeTab.value ?: return
        val currentUrl = activeTab.url
        if (currentUrl.isBlank() || currentUrl.startsWith("onyx://") || currentUrl.startsWith("about:") || LocalFileLoader.isLocalFile(currentUrl)) {
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

        PageTranslateManager.setupCookies(currentUrl, targetCode)
        webView.evaluateJavascript(PageTranslateManager.getTranslateScript(targetCode)) { _ ->
            binding.pbTranslateLoading.visibility = View.GONE
        }
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
        if (activeTab == null || activeTab.url.isBlank() || activeTab.url.startsWith("onyx://") || activeTab.url.startsWith("about:")) {
            Toast.makeText(this, "Developer tools can only run on active web pages", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val erudaCode = try {
                assets.open("eruda.min.js").bufferedReader().use { it.readText() }
            } catch (_: Exception) {
                null
            }

            val script = if (!erudaCode.isNullOrBlank()) {
                """
                (function() {
                    if (window.eruda) {
                        window.eruda.show();
                        return;
                    }
                    $erudaCode
                    if (window.eruda) {
                        window.eruda.init();
                        window.eruda.show();
                    }
                })();
                """.trimIndent()
            } else {
                """
                (function() {
                    if (window.eruda) {
                        window.eruda.show();
                        return;
                    }
                    var s = document.createElement('script');
                    s.src = 'https://cdn.jsdelivr.net/npm/eruda';
                    s.onload = function() {
                        if (window.eruda) {
                            window.eruda.init();
                            window.eruda.show();
                        }
                    };
                    document.body.appendChild(s);
                })();
                """.trimIndent()
            }
            webView.evaluateJavascript(script, null)
            Toast.makeText(this@MainActivity, "Eruda Developer Console activated", Toast.LENGTH_SHORT).show()
        }
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

                if (customVideoView != null) {
                    hideCustomFullscreenVideo()
                    return
                }

                if (binding.findInPageBar.visibility == View.VISIBLE) {
                    hideFindInPage()
                    return
                }

                if (binding.translateBar.visibility == View.VISIBLE) {
                    hideTranslateBar(restoreOriginal = true)
                    return
                }

                if (isSearchMode) {
                    exitSearchMode()
                    return
                }

                val activeWebView = tabManager.getActiveWebView()
                if (activeWebView != null && activeWebView.canGoBack()) {
                    activeWebView.goBack()
                    return
                }

                val activeTab = tabManager.activeTab.value
                if (activeTab != null && activeTab.url.isNotBlank()) {
                    // Navigate back to home screen on this tab
                    tabManager.updateActiveTab("", "New Tab")
                    showHomeScreen()
                    return
                }

                // Default activity back behavior
                finish()
            }
        })
    }

    override fun onPause() {
        super.onPause()
        val activeTabId = tabManager.activeTab.value?.id
        val activeWebView = tabManager.getActiveWebView()
        if (activeTabId != null && activeWebView != null) {
            tabManager.captureTabSnapshot(activeTabId, activeWebView)
        }
        if (!preferences.isPipEnabled) {
            updatePipParams(isVideoPlaying = false)
        }
        val isPip = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) isInPictureInPictureMode else false
        if (preferences.isBackgroundPlayEnabled) {
            tabManager.getActiveWebView()?.evaluateJavascript(
                MediaPlaybackManager.getSetBackgroundStateScript(true),
                null
            )
        }
        if (!isPip && !preferences.isBackgroundPlayEnabled) {
            tabManager.getActiveWebView()?.onPause()
        }
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
                        if (url.isNotBlank() && !LocalFileLoader.isLocalFile(url)) {
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
        unregisterNetworkRecoveryCallback()
        tabManager.isIncognitoUnlocked = false
        val isPip = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) isInPictureInPictureMode else false
        if (!isPip && !preferences.isBackgroundPlayEnabled) {
            tabManager.getActiveWebView()?.onPause()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!preferences.isPipEnabled) return
        if (customVideoView != null) {
            enterPipMode()
        } else if (MediaPlaybackBridge.isVideoPlaying) {
            requestInPageVideoPip()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        val activeWv = tabManager.getActiveWebView()
        if (isInPictureInPictureMode) {
            // Video-Only PiP: Strip all browser UI and chrome
            binding.topBar.visibility = View.GONE
            binding.topBarDivider.visibility = View.GONE
            binding.homeLayout.root.visibility = View.GONE
            binding.fullscreenControlsOverlay.visibility = View.GONE
            binding.progressBar.visibility = View.GONE
            binding.findInPageBar.visibility = View.GONE
            binding.searchOverlay.visibility = View.GONE

            if (customVideoView != null) {
                binding.fullscreenCustomViewContainer.visibility = View.VISIBLE
                binding.webViewContainer.visibility = View.GONE
            } else {
                binding.fullscreenCustomViewContainer.visibility = View.GONE
                binding.webViewContainer.visibility = View.VISIBLE
                binding.webViewContainer.setBackgroundColor(android.graphics.Color.BLACK)
                // Isolate video DOM so only the video displays in PiP without any webpage UI
                activeWv?.evaluateJavascript(MediaPlaybackManager.isolateVideoForPipScript, null)
            }
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            // Exiting PiP — restore browser chrome
            if (!MediaPlaybackBridge.isVideoPlaying) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            binding.topBar.visibility = View.VISIBLE
            binding.topBarDivider.visibility = View.VISIBLE
            binding.webViewContainer.setBackgroundColor(android.graphics.Color.TRANSPARENT)

            if (customVideoView != null) {
                binding.fullscreenControlsOverlay.visibility = View.VISIBLE
                binding.fullscreenCustomViewContainer.visibility = View.VISIBLE
                binding.webViewContainer.visibility = View.GONE
            } else {
                binding.fullscreenCustomViewContainer.visibility = View.GONE
                binding.fullscreenControlsOverlay.visibility = View.GONE
                // Restore proper view based on active tab state (not stale visibility flags)
                val activeTab = tabManager.activeTab.value
                if (activeTab != null && activeTab.url.isNotBlank() &&
                    !activeTab.url.startsWith("onyx://") && !activeTab.url.startsWith("about:")) {
                    binding.homeLayout.root.visibility = View.GONE
                    binding.webViewContainer.visibility = View.VISIBLE
                } else {
                    binding.homeLayout.root.visibility = View.VISIBLE
                    binding.webViewContainer.visibility = View.GONE
                }
                activeWv?.evaluateJavascript(MediaPlaybackManager.restoreVideoFromPipScript, null)
            }
            updatePipParams()
        }
    }

    override fun onResume() {
        super.onResume()
        updateSearchEngineIcon()
        updatePipParams()
        val wv = tabManager.getActiveWebView()
        wv?.onResume()
        wv?.applyUserAgentForUrl(wv.url ?: "")
        binding.root.post {
            wv?.resumeTimers()
            wv?.requestFocus()
        }
        wv?.evaluateJavascript(
            MediaPlaybackManager.getSetBackgroundStateScript(false),
            null
        )
        // Restore screen-on flag if video was still playing when we came back to the app
        if (MediaPlaybackBridge.isVideoPlaying) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        
        // Guarantee cleanup of custom view container if we are not in fullscreen
        if (customVideoView == null) {
            binding.fullscreenCustomViewContainer.visibility = View.GONE
            binding.fullscreenControlsOverlay.visibility = View.GONE
            androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            binding.topBar.requestLayout()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            val wv = tabManager.getActiveWebView()
            wv?.resumeTimers()
            wv?.requestFocus()
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
                    enterSearchMode()
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
                    enterSearchMode()
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

        val currentTab = tabManager.activeTab.value
        if (currentTab != null && !currentTab.isIncognito && currentTab.url.isBlank()) {
            // Current tab is an unused blank normal tab: reuse it
            tabManager.updateActiveTab(targetUrl, targetUrl)
            showWebView(currentTab, forceUrl = targetUrl)
        } else {
            // Create a new normal tab for the incoming link so existing tabs remain intact
            val newTab = tabManager.createNewTab(url = targetUrl, isIncognito = false)
            displayTab(newTab)
        }

        if (isSearchMode) {
            exitSearchMode()
        }
    }

    private fun extractUrlFromIntent(intent: Intent?): String? {
        if (intent == null) return null
        val action = intent.action ?: return null

        when (action) {
            Intent.ACTION_VIEW -> {
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
                val extraText = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!extraText.isNullOrBlank()) {
                    return parseUrlOrExtract(extraText)
                }
            }
            Intent.ACTION_SEND -> {
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
        if (LocalFileLoader.isLocalFile(trimmed) ||
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
        val options = arrayOf("Save as Offline Web Archive (.mhtml)", "Save as PDF (.pdf)")
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Save Page")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> saveCurrentPageAsMhtml(activeWebView, currentTab.title)
                    1 -> saveCurrentPageAsPdf(activeWebView, currentTab.title)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveCurrentPageAsMhtml(webView: OnyxWebView, title: String) {
        try {
            val cleanTitle = title.replace(Regex("[^a-zA-Z0-9.-]"), "_").take(50).ifBlank { "page" }
            val fileName = "${cleanTitle}_${System.currentTimeMillis()}.mhtml"
            val tempFile = java.io.File(cacheDir, fileName)

            webView.saveWebArchive(tempFile.absolutePath, false) { savedPath ->
                if (savedPath != null && tempFile.exists() && tempFile.length() > 0) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            val savedUri = copyTempFileToDownloads(tempFile, fileName, "message/rfc822")
                            kotlinx.coroutines.withContext(Dispatchers.Main) {
                                if (savedUri != null) {
                                    Toast.makeText(this@MainActivity, "Saved to Downloads/$fileName", Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(this@MainActivity, "Failed to export to Downloads", Toast.LENGTH_SHORT).show()
                                }
                            }
                        } catch (e: Exception) {
                            kotlinx.coroutines.withContext(Dispatchers.Main) {
                                Toast.makeText(this@MainActivity, "Error saving file: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        } finally {
                            try { tempFile.delete() } catch (_: Exception) {}
                        }
                    }
                } else {
                    Toast.makeText(this, "Failed to generate web archive", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to save page: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyTempFileToDownloads(tempFile: java.io.File, fileName: String, mimeType: String): android.net.Uri? {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { out ->
                    tempFile.inputStream().use { input ->
                        input.copyTo(out)
                    }
                }
            }
            uri
        } else {
            val downloadDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            val destFile = if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                if (!downloadDir.exists()) downloadDir.mkdirs()
                java.io.File(downloadDir, fileName)
            } else {
                val appDownloads = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: cacheDir
                java.io.File(appDownloads, fileName)
            }
            tempFile.copyTo(destFile, overwrite = true)
            android.media.MediaScannerConnection.scanFile(
                this,
                arrayOf(destFile.absolutePath),
                arrayOf(mimeType),
                null
            )
            android.net.Uri.fromFile(destFile)
        }
    }

    private fun saveCurrentPageAsPdf(webView: OnyxWebView, title: String) {
        try {
            val printManager = getSystemService(android.content.Context.PRINT_SERVICE) as? android.print.PrintManager
            if (printManager == null) {
                Toast.makeText(this, "Printing service unavailable on this device", Toast.LENGTH_SHORT).show()
                return
            }
            val cleanTitle = title.replace(Regex("[^a-zA-Z0-9.-]"), "_").take(50).ifBlank { "page" }
            val printAdapter = webView.createPrintDocumentAdapter(cleanTitle)
            val printAttributes = android.print.PrintAttributes.Builder()
                .setMediaSize(android.print.PrintAttributes.MediaSize.ISO_A4)
                .build()
            printManager.print(cleanTitle, printAdapter, printAttributes)
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to export PDF: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(pipReceiver)
        } catch (_: Exception) {}
        MediaPlaybackService.mediaActionListener = null
        tabManager.clearAllWebViews()
    }
}
