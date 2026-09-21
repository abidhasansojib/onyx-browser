package com.onyx.browser

import android.Manifest
import android.app.Activity
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
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
import com.onyx.browser.ui.search.SuggestionsAdapter
import com.onyx.browser.ui.tabs.TabSwitcherBottomSheet
import com.onyx.browser.web.DownloadHandler
import com.onyx.browser.web.OnyxWebChromeClient
import com.onyx.browser.web.OnyxWebView
import com.onyx.browser.web.OnyxWebViewClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var tabManager: TabManager
    private lateinit var preferences: BrowserPreferences
    private lateinit var suggestionRepository: SearchSuggestionRepository
    private lateinit var suggestionsAdapter: SuggestionsAdapter
    private lateinit var shortcutsAdapter: ShortcutsAdapter
    private var suggestionJob: Job? = null

    private var isSearchMode = false
    private var customVideoView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var currentDisplayedTabId: String? = null
    private var isTabsRestored = false
    private var pendingIntent: Intent? = null

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = BrowserPreferences.getInstance(this)
        preferences.applyTheme()
        if (preferences.themeMode == BrowserPreferences.THEME_SYSTEM) {
            com.google.android.material.color.DynamicColors.applyIfAvailable(this)
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Handle edge-to-edge system bars (Status Bar & Gesture Nav Bar)
        ViewCompat.setOnApplyWindowInsetsListener(binding.mainRoot) { _, windowInsets ->
            val statusBarInsets = windowInsets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val navBarInsets = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())

            binding.topBar.setPadding(
                binding.topBar.paddingLeft,
                statusBarInsets.top,
                binding.topBar.paddingRight,
                binding.topBar.paddingBottom
            )

            binding.contentContainer.setPadding(
                binding.contentContainer.paddingLeft,
                binding.contentContainer.paddingTop,
                binding.contentContainer.paddingRight,
                navBarInsets.bottom
            )
            windowInsets
        }

        tabManager = TabManager(this, lifecycleScope)
        val database = AppDatabase.getInstance(this)
        suggestionRepository = SearchSuggestionRepository(database.historyDao())

        setupTopToolbar()
        setupSearchOverlay()
        setupFindInPage()
        setupHomepageInteractions()
        setupBackNavigation()

        checkNotificationPermissionForDownloads()

        lifecycleScope.launch {
            tabManager.restoreTabs()
            isTabsRestored = true
            observeTabs()
            val intentToHandle = pendingIntent ?: intent
            pendingIntent = null
            if (savedInstanceState == null || intentToHandle != intent) {
                handleIncomingIntent(intentToHandle)
            }
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
                binding.btnVoiceSearch.visibility = if (hasText) View.GONE else View.VISIBLE

                val currentTab = tabManager.activeTab.value
                val hasCurrentUrl = !currentTab?.url.isNullOrBlank()

                if (hasText) {
                    binding.cardCurrentPage.visibility = View.GONE
                    fetchSearchSuggestions(query)
                } else {
                    if (hasCurrentUrl) {
                        binding.cardCurrentPage.visibility = View.VISIBLE
                    }
                    suggestionJob?.cancel()
                    suggestionsAdapter.submitList(emptyList())
                }
            }
        }

        binding.btnClearUrl.setOnClickListener {
            binding.etUrl.setText("")
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
                isDesktopSiteEnabled = activeWebView?.isDesktopModeEnabled() ?: false
                onDesktopSiteToggled = { enabled ->
                    activeWebView?.setDesktopMode(enabled)
                }
                onFindInPageClicked = {
                    showFindInPage()
                }
                onTranslateClicked = { langCode ->
                    translateCurrentPage(langCode)
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

        // Quick Action 1: Bookmarks Button
        home.actionBookmarks.setOnClickListener {
            startActivity(Intent(this, BookmarksActivity::class.java))
        }

        // Quick Action 2: Add / Manage Shortcuts Button (+)
        home.actionAddShortcut.setOnClickListener {
            val sheet = ManageShortcutsBottomSheet()
            sheet.show(supportFragmentManager, ManageShortcutsBottomSheet.TAG)
        }

        // Quick Action 3: History Button
        home.actionHistory.setOnClickListener {
            historyLauncher.launch(Intent(this, HistoryActivity::class.java))
        }

        // Quick Action 4: Downloads Button
        home.actionDownloads.setOnClickListener {
            startActivity(Intent(this, DownloadsActivity::class.java))
        }

        // Dynamic Shortcuts RecyclerView
        shortcutsAdapter = ShortcutsAdapter(
            onShortcutClick = { shortcut ->
                performSearchOrLoad(shortcut.url)
            },
            onShortcutLongClick = { shortcut ->
                showShortcutOptionsMenu(shortcut)
            }
        )

        val shortcutSpanCount = if (resources.configuration.screenWidthDp >= 600) 6 else 4
        home.rvShortcuts.layoutManager = GridLayoutManager(this, shortcutSpanCount)
        home.rvShortcuts.adapter = shortcutsAdapter

        // Drag-and-drop reordering with ItemTouchHelper
        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.START or ItemTouchHelper.END,
            0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPos = viewHolder.bindingAdapterPosition
                val toPos = target.bindingAdapterPosition
                return shortcutsAdapter.onItemMove(fromPos, toPos)
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    viewHolder?.itemView?.animate()?.scaleX(1.08f)?.scaleY(1.08f)?.setDuration(150)?.start()
                }
            }

            override fun clearView(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                preferences.saveShortcuts(shortcutsAdapter.getItems())
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

    private fun showShortcutOptionsMenu(shortcut: ShortcutItem) {
        val options = arrayOf(
            getString(R.string.open_in_new_tab),
            getString(R.string.edit_shortcut),
            getString(R.string.delete_shortcut),
            getString(R.string.share)
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(shortcut.title.ifBlank { shortcut.url })
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        // Open in new tab
                        val newTab = tabManager.createNewTab(url = shortcut.url)
                        displayTab(newTab)
                    }
                    1 -> {
                        // Edit shortcut
                        val dialog = EditShortcutDialog(shortcut) { updated ->
                            preferences.updateShortcut(updated)
                            Toast.makeText(this, R.string.shortcut_updated, Toast.LENGTH_SHORT).show()
                        }
                        dialog.show(supportFragmentManager, EditShortcutDialog.TAG)
                    }
                    2 -> {
                        // Delete shortcut
                        preferences.deleteShortcut(shortcut.id)
                        Toast.makeText(this, R.string.shortcut_deleted, Toast.LENGTH_SHORT).show()
                    }
                    3 -> {
                        // Share
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, shortcut.url)
                        }
                        startActivity(Intent.createChooser(shareIntent, getString(R.string.share)))
                    }
                }
            }
            .show()
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

        val tabChanged = (currentDisplayedTabId != tab.id)
        currentDisplayedTabId = tab.id

        if (tab.url.isBlank()) {
            showHomeScreen()
        } else {
            showWebView(tab, reloadIfChanged = tabChanged)
        }
    }

    private fun showHomeScreen() {
        binding.homeLayout.root.visibility = View.VISIBLE
        binding.webViewContainer.visibility = View.GONE
        binding.etUrl.setText("")
        binding.ivSslLock.visibility = View.GONE
        binding.progressBar.visibility = View.GONE
    }

    private fun showWebView(tab: TabItem, forceUrl: String? = null, reloadIfChanged: Boolean = false) {
        binding.homeLayout.root.visibility = View.GONE
        binding.webViewContainer.visibility = View.VISIBLE

        val webView = tabManager.getOrCreateWebView(tab)
        attachWebViewToContainer(webView)

        val targetUrl = forceUrl ?: tab.url
        if (targetUrl.isNotBlank()) {
            if (forceUrl != null || reloadIfChanged || webView.url.isNullOrBlank() || webView.url == "about:blank") {
                try {
                    webView.loadUrl(targetUrl)
                } catch (t: Throwable) {
                    Toast.makeText(this, "Failed to load URL: ${t.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        updateAddressBarDisplay(webView.url ?: targetUrl)
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
        webView.webViewClient = OnyxWebViewClient(
            context = this,
            coroutineScope = lifecycleScope,
            onUrlChanged = { newUrl ->
                tabManager.updateActiveTab(newUrl, webView.title ?: newUrl)
                updateAddressBarDisplay(newUrl)
            },
            onPageFinishedCallback = { finishedUrl ->
                tabManager.updateActiveTab(finishedUrl, webView.title ?: finishedUrl)
                updateAddressBarDisplay(finishedUrl)
                binding.progressBar.visibility = View.GONE
            }
        )

        webView.webChromeClient = OnyxWebChromeClient(
            onProgressChangedCallback = { progress ->
                if (progress < 100) {
                    binding.progressBar.visibility = View.VISIBLE
                    binding.progressBar.progress = progress
                } else {
                    binding.progressBar.visibility = View.GONE
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
            onCreateWindowCallback = { _, _, _, resultMsg ->
                if (resultMsg != null) {
                    val newTab = tabManager.createNewTab()
                    val newWebView = tabManager.getOrCreateWebView(newTab)
                    val transport = resultMsg.obj as? WebView.WebViewTransport
                    if (transport != null) {
                        transport.webView = newWebView
                        resultMsg.sendToTarget()
                        displayTab(newTab)
                        true
                    } else {
                        false
                    }
                } else {
                    false
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
            DownloadHandler.handleDownload(
                activity = this,
                coroutineScope = lifecycleScope,
                url = url,
                userAgent = userAgent,
                contentDisposition = contentDisposition,
                mimeType = mimetype,
                contentLength = contentLength
            )
        }
    }

    private fun performSearchOrLoad(input: String) {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return

        val url = when {
            trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("file://", ignoreCase = true) ||
            trimmed.startsWith("about:", ignoreCase = true) ||
            trimmed.startsWith("data:", ignoreCase = true) -> trimmed

            isLikelyUrl(trimmed) -> "https://$trimmed"

            else -> preferences.searchEngine.buildSearchUrl(trimmed)
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
            val currentTab = tabManager.activeTab.value
            val url = currentTab?.url ?: ""
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
            val currentTab = tabManager.activeTab.value
            val url = currentTab?.url ?: ""
            if (url.isNotBlank()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clip = ClipData.newPlainText("URL", url)
                clipboard?.setPrimaryClip(clip)
                Toast.makeText(this, getString(R.string.link_copied), Toast.LENGTH_SHORT).show()
            }
        }

        // Edit button populates the clean search bar with this URL so user can customize it
        binding.btnCurrentPageEdit.setOnClickListener {
            val currentTab = tabManager.activeTab.value
            val url = currentTab?.url ?: ""
            if (url.isNotBlank()) {
                binding.etUrl.setText(url)
                binding.etUrl.setSelection(binding.etUrl.text?.length ?: 0)
                binding.etUrl.requestFocus()
                showSoftKeyboard()
            }
        }

        binding.containerPageInfo.setOnClickListener {
            val currentTab = tabManager.activeTab.value
            val url = currentTab?.url ?: ""
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
        val hasCurrentUrl = !currentTab?.url.isNullOrBlank()

        if (hasCurrentUrl) {
            binding.cardCurrentPage.visibility = View.VISIBLE
            binding.tvCurrentPageTitle.text = currentTab?.title?.ifBlank { currentTab.url } ?: ""
            binding.tvCurrentPageUrl.text = currentTab?.url ?: ""
        } else {
            binding.cardCurrentPage.visibility = View.GONE
        }

        // Clean search bar for fresh input as requested
        binding.etUrl.setText("")
        binding.etUrl.hint = getString(R.string.search_or_type_url)
        binding.btnClearUrl.visibility = View.GONE
        binding.btnVoiceSearch.visibility = View.VISIBLE

        // 4. Focus search bar & show keyboard
        binding.etUrl.requestFocus()
        showSoftKeyboard()

        // 5. Reset suggestions list
        suggestionsAdapter.submitList(emptyList())
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
        binding.btnVoiceSearch.visibility = View.VISIBLE

        // 2. Hide search overlay
        binding.searchOverlay.visibility = View.GONE
        binding.cardCurrentPage.visibility = View.GONE
        suggestionsAdapter.submitList(emptyList())

        // 3. Hide keyboard & clear focus
        hideSoftKeyboard()
        binding.etUrl.clearFocus()

        // 4. Restore address bar host display
        val currentTab = tabManager.activeTab.value
        updateAddressBarDisplay(currentTab?.url ?: "")
    }

    private fun fetchSearchSuggestions(query: String) {
        suggestionJob?.cancel()
        suggestionJob = lifecycleScope.launch {
            delay(150)
            val suggestions = suggestionRepository.getSuggestions(query, preferences.searchEngine)
            if (isSearchMode) {
                suggestionsAdapter.submitList(suggestions)
            }
        }
    }

    private fun updateAddressBarDisplay(url: String) {
        if (url.isBlank()) {
            binding.etUrl.setText("")
            binding.ivSslLock.visibility = View.GONE
            return
        }

        val isHttps = url.startsWith("https://")
        binding.ivSslLock.visibility = if (isHttps) View.VISIBLE else View.GONE

        val host = try {
            Uri.parse(url).host ?: url
        } catch (e: Exception) {
            url
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

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        binding.fullscreenCustomViewContainer.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        binding.fullscreenCustomViewContainer.visibility = View.VISIBLE
    }

    private fun hideCustomFullscreenVideo() {
        if (customVideoView == null) return

        binding.fullscreenCustomViewContainer.removeView(customVideoView)
        binding.fullscreenCustomViewContainer.visibility = View.GONE
        customVideoView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
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

    private fun setupFindInPage() {
        binding.btnCloseFind.setOnClickListener {
            hideFindInPage()
        }

        binding.etFindQuery.doAfterTextChanged { text ->
            val query = text?.toString()?.trim() ?: ""
            val webView = tabManager.getActiveWebView()
            if (query.isNotEmpty()) {
                webView?.findAllAsync(query)
            } else {
                webView?.clearMatches()
                binding.tvFindMatches.text = getString(R.string.no_matches)
            }
        }

        binding.etFindQuery.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                tabManager.getActiveWebView()?.findNext(true)
                true
            } else {
                false
            }
        }

        binding.btnFindPrev.setOnClickListener {
            tabManager.getActiveWebView()?.findNext(false)
        }

        binding.btnFindNext.setOnClickListener {
            tabManager.getActiveWebView()?.findNext(true)
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
        tabManager.getActiveWebView()?.clearMatches()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.etFindQuery.windowToken, 0)
        binding.etFindQuery.setText("")
    }

    private fun translateCurrentPage(langCode: String) {
        val activeTab = tabManager.activeTab.value ?: return
        val currentUrl = activeTab.url
        if (currentUrl.isBlank() || currentUrl.startsWith("onyx://") || currentUrl.startsWith("about:")) {
            Toast.makeText(this, "Cannot translate internal page", Toast.LENGTH_SHORT).show()
            return
        }
        val encodedUrl = try {
            java.net.URLEncoder.encode(currentUrl, "UTF-8")
        } catch (_: Exception) {
            currentUrl
        }
        val targetCode = if (langCode.startsWith("zh", ignoreCase = true)) langCode else langCode.take(2)
        val translateUrl = "https://translate.google.com/translate?sl=auto&tl=$targetCode&u=$encodedUrl"
        performSearchOrLoad(translateUrl)
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
                if (customVideoView != null) {
                    hideCustomFullscreenVideo()
                    return
                }

                if (binding.findInPageBar.visibility == View.VISIBLE) {
                    hideFindInPage()
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
        tabManager.getActiveWebView()?.onPause()
    }

    override fun onResume() {
        super.onResume()
        tabManager.getActiveWebView()?.onResume()
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

    private fun handleIncomingIntent(intent: Intent?) {
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
                val dataUri = intent.data
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
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("file://", ignoreCase = true) ||
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

    override fun onDestroy() {
        super.onDestroy()
        tabManager.clearAllWebViews()
    }
}
