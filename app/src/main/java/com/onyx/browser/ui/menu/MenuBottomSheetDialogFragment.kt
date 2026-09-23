package com.onyx.browser.ui.menu

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.onyx.browser.MainActivity
import com.onyx.browser.R
import com.onyx.browser.data.local.AppDatabase
import com.onyx.browser.data.model.BookmarkItem
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.databinding.BottomSheetMenuBinding
import com.onyx.browser.ui.bookmarks.BookmarksActivity
import com.onyx.browser.ui.downloads.DownloadsActivity
import com.onyx.browser.ui.history.HistoryActivity
import com.onyx.browser.ui.settings.SettingsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MenuBottomSheetDialogFragment : BottomSheetDialogFragment() {

    private var _binding: BottomSheetMenuBinding? = null
    private val binding get() = _binding!!

    var isHomePage: Boolean = true
    var currentUrl: String = ""
    var currentTitle: String = ""
    var isDesktopSiteEnabled: Boolean = false

    var onDesktopSiteToggled: ((Boolean) -> Unit)? = null
    var onFindInPageClicked: (() -> Unit)? = null
    var onTranslateClicked: ((String) -> Unit)? = null
    var onAddToHomeScreenClicked: (() -> Unit)? = null
    var onDeveloperToolsClicked: (() -> Unit)? = null
    var onSavePageClicked: (() -> Unit)? = null
    var onSiteShieldWhitelistChanged: ((Boolean) -> Unit)? = null
    

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetMenuBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (isHomePage) {
            binding.layoutHomeMenu.visibility = View.VISIBLE
            binding.layoutWebpageMenu.visibility = View.GONE
            setupHomeMenu()
        } else {
            binding.layoutHomeMenu.visibility = View.GONE
            binding.layoutWebpageMenu.visibility = View.VISIBLE
            setupWebpageMenu()
        }
    }

    private fun setupHomeMenu() {
        val main = activity as? MainActivity

        // 1. Shortcuts: Bookmarks
        binding.homeQuickBookmarks.setOnClickListener {
            if (main != null) {
                main.openBookmarks()
            } else {
                startActivity(Intent(requireContext(), BookmarksActivity::class.java))
            }
            dismiss()
        }

        // 2. Shortcuts: History
        binding.homeQuickHistory.setOnClickListener {
            if (main != null) {
                main.openHistory()
            } else {
                startActivity(Intent(requireContext(), HistoryActivity::class.java))
            }
            dismiss()
        }

        // 3. Shortcuts: Downloads
        binding.homeQuickDownloads.setOnClickListener {
            if (main != null) {
                main.openDownloads()
            } else {
                startActivity(Intent(requireContext(), DownloadsActivity::class.java))
            }
            dismiss()
        }

        // 4. Shortcuts: Share App
        binding.homeQuickShare.setOnClickListener {
            val sendIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, "Browse the web fast and private with Onyx Browser!")
                type = "text/plain"
            }
            startActivity(Intent.createChooser(sendIntent, getString(R.string.share)))
            dismiss()
        }

        // Default Browser Banner (under shortcuts, shown only if not default)
        setupDefaultBrowserBanner()



        // Settings Button (under default browser banner)
        binding.menuItemHomeSettings.setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
            dismiss()
        }
    }

    private fun setupWebpageMenu() {
        val prefs = BrowserPreferences.getInstance(requireContext())
        val cleanDomain = prefs.cleanDomain(currentUrl)
        val main = activity as? MainActivity
        val db = AppDatabase.getInstance(requireContext())

        // Top Shortcuts Quick Bar (Bookmarks, History, Downloads, Share)
        if (currentUrl.isNotBlank()) {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val isBookmarked = db.bookmarkDao().isBookmarked(currentUrl)
                withContext(Dispatchers.Main) {
                    if (_binding != null) {
                        if (isBookmarked) {
                            binding.ivWebQuickBookmark.setColorFilter(
                                ContextCompat.getColor(requireContext(), R.color.primary)
                            )
                        } else {
                            binding.ivWebQuickBookmark.clearColorFilter()
                        }
                    }
                }
            }
        }

        // 1. Web Shortcuts: Bookmarks
        binding.webQuickBookmarks.setOnClickListener {
            if (main != null) {
                main.openBookmarks()
            } else {
                startActivity(Intent(requireContext(), BookmarksActivity::class.java))
            }
            dismiss()
        }

        binding.webQuickBookmarks.setOnLongClickListener {
            if (currentUrl.isNotBlank()) {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    val isBookmarked = db.bookmarkDao().isBookmarked(currentUrl)
                    if (isBookmarked) {
                        db.bookmarkDao().deleteByUrl(currentUrl)
                        withContext(Dispatchers.Main) {
                            if (_binding != null) {
                                binding.ivWebQuickBookmark.clearColorFilter()
                                Toast.makeText(requireContext(), R.string.bookmark_removed, Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        val title = currentTitle.ifBlank { currentUrl }
                        db.bookmarkDao().insertBookmark(BookmarkItem(url = currentUrl, title = title))
                        withContext(Dispatchers.Main) {
                            if (_binding != null) {
                                binding.ivWebQuickBookmark.setColorFilter(
                                    ContextCompat.getColor(requireContext(), R.color.primary)
                                )
                                Toast.makeText(requireContext(), R.string.bookmark_added, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                true
            } else {
                false
            }
        }

        // 2. Web Shortcuts: History
        binding.webQuickHistory.setOnClickListener {
            if (main != null) {
                main.openHistory()
            } else {
                startActivity(Intent(requireContext(), HistoryActivity::class.java))
            }
            dismiss()
        }

        // 3. Web Shortcuts: Downloads
        binding.webQuickDownloads.setOnClickListener {
            if (main != null) {
                main.openDownloads()
            } else {
                startActivity(Intent(requireContext(), DownloadsActivity::class.java))
            }
            dismiss()
        }

        // 4. Web Shortcuts: Share Webpage
        binding.webQuickShare.setOnClickListener {
            if (currentUrl.isNotBlank()) {
                val sendIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_TEXT, currentUrl)
                    type = "text/plain"
                }
                startActivity(Intent.createChooser(sendIntent, getString(R.string.share)))
            } else {
                Toast.makeText(requireContext(), "No webpage to share", Toast.LENGTH_SHORT).show()
            }
            dismiss()
        }

        // 1st: Box-Type UI Card
        binding.tvWebsiteDomain.text = cleanDomain.ifBlank { "Webpage" }

        // Shield with lock icon button: opens per-site adblock controls (Brave Shields panel)
        binding.btnSiteShield.setOnClickListener {
            val dialog = SiteShieldBottomSheetDialog(currentUrl) {
                onSiteShieldWhitelistChanged?.invoke(true)
            }
            dialog.show(childFragmentManager, SiteShieldBottomSheetDialog.TAG)
        }

        // Share icon button: shares webpage URL
        binding.btnWebpageShare.setOnClickListener {
            if (currentUrl.isNotBlank()) {
                val sendIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_TEXT, currentUrl)
                    type = "text/plain"
                }
                startActivity(Intent.createChooser(sendIntent, getString(R.string.share)))
            } else {
                Toast.makeText(requireContext(), "No webpage to share", Toast.LENGTH_SHORT).show()
            }
            dismiss()
        }

        // 2nd: Translate to [Language]
        fun updateTranslateText() {
            binding.tvTranslateTitle.text = getString(R.string.translate_to, prefs.targetTranslateLanguageName)
        }
        updateTranslateText()

        // Main translate row click
        binding.menuItemTranslate.setOnClickListener {
            onTranslateClicked?.invoke(prefs.targetTranslateLanguage)
            dismiss()
        }

        // Right gear icon click: open language selector
        binding.btnTranslateSettings.setOnClickListener {
            val langDialog = LanguageSelectionDialog { code, name ->
                updateTranslateText()
            }
            langDialog.show(childFragmentManager, LanguageSelectionDialog.TAG)
        }

        // 3rd: Find in Page
        binding.menuItemFindInPage.setOnClickListener {
            onFindInPageClicked?.invoke()
            dismiss()
        }

        // 4th: Request Desktop Site
        binding.switchDesktopSite.isChecked = isDesktopSiteEnabled
        binding.menuItemDesktopSite.setOnClickListener {
            val newState = !binding.switchDesktopSite.isChecked
            binding.switchDesktopSite.isChecked = newState
            onDesktopSiteToggled?.invoke(newState)
            dismiss()
        }



        // Save Page
        binding.menuItemSavePage.setOnClickListener {
            onSavePageClicked?.invoke()
            dismiss()
        }

        // 5th: Add to Home screen
        binding.menuItemAddToHomeScreen.setOnClickListener {
            onAddToHomeScreenClicked?.invoke()
            dismiss()
        }



        // 6th: Developer Tools (Eruda Console)
        binding.menuItemDeveloperTools.setOnClickListener {
            onDeveloperToolsClicked?.invoke()
            dismiss()
        }

        // 7th: Settings
        binding.menuItemWebSettings.setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
            dismiss()
        }
    }

    private fun setupDefaultBrowserBanner() {
        val isDefault = isDefaultBrowser()
        if (!isDefault) {
            binding.bannerDefaultBrowser.visibility = View.VISIBLE
            binding.bannerDefaultBrowser.setOnClickListener {
                promptSetDefaultBrowser()
                dismiss()
            }
        } else {
            binding.bannerDefaultBrowser.visibility = View.GONE
        }
    }

    private fun isDefaultBrowser(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = requireContext().getSystemService(RoleManager::class.java)
            roleManager?.isRoleHeld(RoleManager.ROLE_BROWSER) == true
        } else {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))
            val resolveInfo = requireContext().packageManager.resolveActivity(intent, 0)
            resolveInfo?.activityInfo?.packageName == requireContext().packageName
        }
    }

    private val roleRequestLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) {
        if (isDefaultBrowser()) {
            Toast.makeText(requireContext(), "Onyx set as default browser", Toast.LENGTH_SHORT).show()
            binding.bannerDefaultBrowser.visibility = View.GONE
        }
    }

    private fun promptSetDefaultBrowser() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = requireContext().getSystemService(RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_BROWSER)) {
                try {
                    val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_BROWSER)
                    roleRequestLauncher.launch(intent)
                    return
                } catch (_: Exception) {
                }
            }
        }

        // Fallback for earlier Android versions or customized OEM ROMs
        val fallbackIntents = listOf(
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent("android.settings.MANAGE_DEFAULT_APPS_SETTINGS"),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", requireContext().packageName, null)
            },
            Intent(Settings.ACTION_SETTINGS)
        )

        for (intent in fallbackIntents) {
            try {
                startActivity(intent)
                return
            } catch (_: Exception) {
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "MenuBottomSheet"
    }
}
