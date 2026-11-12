package com.onyx.browser.ui.browser

import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.onyx.browser.MainActivity
import com.onyx.browser.R
import com.onyx.browser.data.local.AppDatabase
import com.onyx.browser.data.model.BookmarkItem
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.databinding.ActivityMainBinding
import com.onyx.browser.ui.menu.ElementPickerManager
import com.onyx.browser.ui.menu.LanguageSelectionDialog
import com.onyx.browser.ui.menu.MenuBottomSheetDialogFragment
import com.onyx.browser.ui.menu.SiteShieldBottomSheetDialog
import com.onyx.browser.web.DevToolsManager
import com.onyx.browser.web.LocalFileLoader
import com.onyx.browser.web.OnyxWebView
import com.onyx.browser.web.translate.PageTranslateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Coordinates the 3-dot browser menu bottom sheet, per-site Shield dialog,
 * in-page translation bar & language selector, bookmark toggling for the active page,
 * developer tools (Eruda) injection, and visual element blocker activation.
 */
class BrowserMenuCoordinator(
    private val activity: MainActivity,
    private val binding: ActivityMainBinding,
    private val preferences: BrowserPreferences
) {
    private var currentTranslateTargetCode: String = ""
    private var isUpdatingTranslateUi: Boolean = false

    fun showMenuBottomSheet() {
        if (activity.isFinishing || activity.isDestroyed) return
        val activeTab = activity.tabManager.activeTab.value
        val activeWebView = activity.tabManager.getActiveWebView()
        val isHome = (binding.homeLayout.root.visibility == View.VISIBLE) || activeTab?.url.isNullOrBlank()

        val sheet = MenuBottomSheetDialogFragment().apply {
            isHomePage = isHome
            currentUrl = activeTab?.url ?: ""
            currentTitle = activeTab?.title ?: ""
            isDesktopSiteEnabled = activeWebView?.isDesktopMode ?: false
            onDesktopSiteToggled = { _ ->
                // Toggle desktop mode scoped to the active tab, with automatic mobile subdomain translation
                activeWebView?.toggleDesktopMode()
            }
            onFindInPageClicked = {
                activity.showFindInPage()
            }
            onTranslateClicked = { langCode ->
                translateCurrentPage(langCode)
            }
            onSavePageClicked = {
                activity.showSavePageDialog()
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
                activeWebView?.onSiteSettingsChanged()
            }
        }
        sheet.show(activity.supportFragmentManager, MenuBottomSheetDialogFragment.TAG)
    }

    fun showSiteShieldDialog() {
        if (activity.isFinishing || activity.isDestroyed) return
        val activeTab = activity.tabManager.activeTab.value ?: return
        val url = activeTab.url
        if (url.isNotBlank() && !url.startsWith("about:blank", ignoreCase = true)) {
            val activeWv = activity.tabManager.getActiveWebView()
            val blockedCount = activeWv?.pageBlockedCount ?: 0
            val cert = activeWv?.certificate
            val dialog = SiteShieldBottomSheetDialog(
                currentUrl = url,
                pageBlockedCount = blockedCount,
                isIncognito = activeTab.isIncognito,
                sslCertificate = cert
            ) {
                activity.tabManager.getActiveWebView()?.onSiteSettingsChanged()
            }
            dialog.show(activity.supportFragmentManager, SiteShieldBottomSheetDialog.TAG)
        }
    }

    fun showLanguageSelectionDialog(webView: OnyxWebView? = activity.getActiveWebView()) {
        if (activity.isFinishing || activity.isDestroyed) return
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
            val targetWv = webView ?: activity.getActiveWebView() ?: return@LanguageSelectionDialog
            binding.pbTranslateLoading.visibility = View.VISIBLE
            targetWv.evaluateJavascript(PageTranslateManager.getTranslateScript(code), null)
        }
        dialog.show(activity.supportFragmentManager, LanguageSelectionDialog.TAG)
    }

    fun toggleBookmarkForCurrentPage(onResult: ((Boolean) -> Unit)? = null) {
        val activeTab = activity.tabManager.activeTab.value
        val currentUrl = activeTab?.url ?: ""
        if (currentUrl.isBlank() || currentUrl.startsWith("about:blank", ignoreCase = true)) {
            return
        }
        val currentTitle = activeTab?.title?.ifBlank { currentUrl } ?: currentUrl
        val db = AppDatabase.getInstance(activity)
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val isBookmarked = db.bookmarkDao().isBookmarked(currentUrl)
            if (isBookmarked) {
                db.bookmarkDao().deleteByUrl(currentUrl)
                withContext(Dispatchers.Main) {
                    Toast.makeText(activity, R.string.bookmark_removed, Toast.LENGTH_SHORT).show()
                    onResult?.invoke(false)
                }
            } else {
                db.bookmarkDao().insertBookmark(BookmarkItem(url = currentUrl, title = currentTitle))
                withContext(Dispatchers.Main) {
                    Toast.makeText(activity, R.string.bookmark_added, Toast.LENGTH_SHORT).show()
                    onResult?.invoke(true)
                }
            }
        }
    }

    fun setTranslateLoading(isLoading: Boolean) {
        activity.runOnUiThread {
            binding.pbTranslateLoading.visibility = if (isLoading) View.VISIBLE else View.GONE
        }
    }

    fun setupTranslateBar() {
        binding.toggleTranslateMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isUpdatingTranslateUi || !isChecked) return@addOnButtonCheckedListener
            val wv = activity.tabManager.getActiveWebView() ?: return@addOnButtonCheckedListener
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
            showLanguageSelectionDialog(activity.getActiveWebView())
        }

        binding.btnCloseTranslate.setOnClickListener {
            hideTranslateBar(restoreOriginal = true)
        }
    }

    fun showTranslateBar(targetCode: String, targetName: String) {
        currentTranslateTargetCode = targetCode
        binding.btnTranslateTarget.text = targetName
        isUpdatingTranslateUi = true
        try {
            binding.toggleTranslateMode.check(R.id.btnTranslateTarget)
        } finally {
            isUpdatingTranslateUi = false
        }
        activity.showToolbar(animated = false)
        binding.translateBar.visibility = View.VISIBLE
    }

    fun hideTranslateBar(restoreOriginal: Boolean = false) {
        binding.translateBar.visibility = View.GONE
        binding.pbTranslateLoading.visibility = View.GONE
        if (restoreOriginal) {
            activity.tabManager.getActiveWebView()?.evaluateJavascript(PageTranslateManager.restoreOriginalScript, null)
        }
    }

    fun translateCurrentPage(langCode: String) {
        val activeTab = activity.tabManager.activeTab.value ?: return
        val currentUrl = activeTab.url
        if (currentUrl.isBlank() || currentUrl.startsWith("onyx://") || currentUrl.startsWith("about:") || LocalFileLoader.isLocalFile(activity, currentUrl)) {
            Toast.makeText(activity, "Cannot translate internal page", Toast.LENGTH_SHORT).show()
            return
        }

        val targetCode = if (langCode.isNotBlank()) langCode else preferences.targetTranslateLanguage
        val targetName = preferences.targetTranslateLanguageName

        val webView = activity.tabManager.getActiveWebView()
        if (webView == null) {
            Toast.makeText(activity, "No active webpage to translate", Toast.LENGTH_SHORT).show()
            return
        }

        showTranslateBar(targetCode, targetName)
        binding.pbTranslateLoading.visibility = View.VISIBLE

        webView.evaluateJavascript(PageTranslateManager.getTranslateScript(targetCode), null)
    }

    fun addCurrentPageToHomeScreen() {
        activity.pinWebpageToHomeScreen()
    }

    fun injectDeveloperTools() {
        val webView = activity.tabManager.getActiveWebView() ?: return
        val activeTab = activity.tabManager.activeTab.value
        if (activeTab == null || activeTab.url.isBlank() ||
            activeTab.url.startsWith("onyx://") || activeTab.url.startsWith("about:")) {
            Toast.makeText(activity, "Developer tools can only run on active web pages", Toast.LENGTH_SHORT).show()
            return
        }
        activity.lifecycleScope.launch {
            val isDark = preferences.isDarkMode()
            DevToolsManager.toggleDevTools(activity, webView, isDark) { status: String ->
                when (status) {
                    "shown" -> {
                        webView.isDevToolsActive = true
                    }
                    "hidden" -> {
                        webView.isDevToolsActive = true
                    }
                    "failed" -> {
                        webView.isDevToolsActive = false
                        Toast.makeText(activity, "Unable to load developer tools", Toast.LENGTH_SHORT).show()
                    }
                    "error" -> {
                        webView.isDevToolsActive = false
                        Toast.makeText(activity, "Developer tools encountered an error", Toast.LENGTH_SHORT).show()
                    }
                    else -> {}
                }
            }
        }
    }

    fun startElementBlocker() {
        val webView = activity.tabManager.getActiveWebView() ?: return
        val activeTab = activity.tabManager.activeTab.value
        if (activeTab == null || activeTab.url.isBlank() ||
            activeTab.url.startsWith("onyx://") || activeTab.url.startsWith("about:")) {
            Toast.makeText(activity, "Element blocker can only run on active web pages", Toast.LENGTH_SHORT).show()
            return
        }
        ElementPickerManager.startPicker(webView)
        Toast.makeText(activity, R.string.block_element_hint, Toast.LENGTH_SHORT).show()
    }
}
