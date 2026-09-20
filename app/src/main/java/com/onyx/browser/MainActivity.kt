package com.onyx.browser

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.onyx.browser.data.model.SearchEngine
import com.onyx.browser.data.model.TabItem
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.databinding.ActivityMainBinding
import com.onyx.browser.ui.browser.TabManager
import com.onyx.browser.ui.common.SearchEnginePickerDialog
import com.onyx.browser.ui.downloads.DownloadsActivity
import com.onyx.browser.ui.history.HistoryActivity
import com.onyx.browser.ui.menu.MenuBottomSheetDialogFragment
import com.onyx.browser.ui.tabs.TabSwitcherBottomSheet
import com.onyx.browser.web.DownloadHandler
import com.onyx.browser.web.OnyxWebChromeClient
import com.onyx.browser.web.OnyxWebView
import com.onyx.browser.web.OnyxWebViewClient
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var tabManager: TabManager
    private lateinit var preferences: BrowserPreferences

    private var isSearchMode = false
    private var customVideoView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

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

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        tabManager = TabManager(this, lifecycleScope)

        setupTopToolbar()
        setupHomepageInteractions()
        setupBackNavigation()

        lifecycleScope.launch {
            tabManager.restoreTabs()
            observeTabs()
        }
    }

    private fun setupTopToolbar() {
        // Search Engine Icon
        updateSearchEngineIcon()
        binding.btnSearchEngine.setOnClickListener {
            val dialog = SearchEnginePickerDialog().apply {
                setSelectedEngine(preferences.searchEngine)
                onSearchEngineSelected = { engine ->
                    preferences.searchEngine = engine
                    updateSearchEngineIcon()
                }
            }
            dialog.show(supportFragmentManager, SearchEnginePickerDialog.TAG)
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
            if (hasFocus) {
                enterSearchMode()
            } else {
                exitSearchMode()
            }
        }

        binding.etUrl.setOnClickListener {
            if (!isSearchMode) {
                enterSearchMode()
            }
        }

        binding.etUrl.doAfterTextChanged { text ->
            if (isSearchMode) {
                val hasText = !text.isNullOrEmpty()
                binding.btnClearUrl.visibility = if (hasText) View.VISIBLE else View.GONE
                binding.btnVoiceSearch.visibility = if (hasText) View.GONE else View.VISIBLE
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
                }
            )
            sheet.show(supportFragmentManager, TabSwitcherBottomSheet.TAG)
        }

        // Three-Dot Menu Button
        binding.btnMenu.setOnClickListener {
            val activeTab = tabManager.activeTab.value
            val activeWebView = tabManager.getActiveWebView()
            val sheet = MenuBottomSheetDialogFragment().apply {
                currentUrl = activeTab?.url ?: ""
                currentTitle = activeTab?.title ?: ""
                isDesktopSiteEnabled = activeWebView?.isDesktopModeEnabled() ?: false
                onNewTabClicked = {
                    val newTab = tabManager.createNewTab(isIncognito = false)
                    displayTab(newTab)
                }
                onNewIncognitoTabClicked = {
                    val newTab = tabManager.createNewTab(isIncognito = true)
                    displayTab(newTab)
                }
                onDesktopSiteToggled = { enabled ->
                    activeWebView?.setDesktopMode(enabled)
                }
                onFindInPageClicked = {
                    Toast.makeText(this@MainActivity, "Find in page activated", Toast.LENGTH_SHORT).show()
                }
            }
            sheet.show(supportFragmentManager, MenuBottomSheetDialogFragment.TAG)
        }
    }

    private fun setupHomepageInteractions() {
        val home = binding.homeLayout

        // Quick Action 1: Shortcuts
        home.actionShortcuts.setOnClickListener {
            Toast.makeText(this, "Top Shortcuts", Toast.LENGTH_SHORT).show()
        }

        // Quick Action 2: Incognito Shortcut
        home.actionIncognito.setOnClickListener {
            val newTab = tabManager.createNewTab(isIncognito = true)
            displayTab(newTab)
        }

        // Quick Action 3: History Button
        home.actionHistory.setOnClickListener {
            historyLauncher.launch(Intent(this, HistoryActivity::class.java))
        }

        // Quick Action 4: Downloads Button
        home.actionDownloads.setOnClickListener {
            startActivity(Intent(this, DownloadsActivity::class.java))
        }

        // Predefined Shortcuts
        home.shortcutGoogle.setOnClickListener { performSearchOrLoad("https://www.google.com") }
        home.shortcutBrave.setOnClickListener { performSearchOrLoad("https://search.brave.com") }
        home.shortcutGitHub.setOnClickListener { performSearchOrLoad("https://github.com") }
        home.shortcutWikipedia.setOnClickListener { performSearchOrLoad("https://www.wikipedia.org") }
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

        if (tab.url.isBlank()) {
            showHomeScreen()
        } else {
            showWebView(tab)
        }
    }

    private fun showHomeScreen() {
        binding.homeLayout.root.visibility = View.VISIBLE
        binding.webViewContainer.visibility = View.GONE
        binding.etUrl.setText("")
        binding.ivSslLock.visibility = View.GONE
        binding.progressBar.visibility = View.GONE
    }

    private fun showWebView(tab: TabItem) {
        binding.homeLayout.root.visibility = View.GONE
        binding.webViewContainer.visibility = View.VISIBLE

        val webView = tabManager.getOrCreateWebView(tab)
        attachWebViewToContainer(webView)

        if (webView.url.isNullOrBlank() && tab.url.isNotBlank()) {
            webView.loadUrl(tab.url)
        }

        updateAddressBarDisplay(webView.url ?: tab.url)
    }

    private fun attachWebViewToContainer(webView: OnyxWebView) {
        if (webView.parent != binding.webViewContainer) {
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
        val url = if (input.startsWith("http://") || input.startsWith("https://")) {
            input
        } else if (input.contains(".") && !input.contains(" ")) {
            "https://$input"
        } else {
            preferences.searchEngine.buildSearchUrl(input)
        }

        val activeTab = tabManager.activeTab.value
        if (activeTab != null) {
            tabManager.updateActiveTab(url, url)
            val webView = tabManager.getOrCreateWebView(activeTab)
            attachWebViewToContainer(webView)
            webView.loadUrl(url)
            showWebView(activeTab)
        }
    }

    private fun enterSearchMode() {
        isSearchMode = true
        binding.ivSslLock.visibility = View.GONE
        binding.btnVoiceSearch.visibility = View.VISIBLE

        val currentTab = tabManager.activeTab.value
        if (!currentTab?.url.isNullOrBlank()) {
            binding.etUrl.setText(currentTab?.url)
            binding.etUrl.selectAll()
        }

        showSoftKeyboard()
    }

    private fun exitSearchMode() {
        isSearchMode = false
        binding.btnClearUrl.visibility = View.GONE
        binding.btnVoiceSearch.visibility = View.VISIBLE

        val currentTab = tabManager.activeTab.value
        updateAddressBarDisplay(currentTab?.url ?: "")
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

    private fun launchVoiceSearch() {
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

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (customVideoView != null) {
                    hideCustomFullscreenVideo()
                    return
                }

                if (isSearchMode) {
                    binding.etUrl.clearFocus()
                    hideSoftKeyboard()
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

    override fun onDestroy() {
        super.onDestroy()
        tabManager.clearAllWebViews()
    }
}
