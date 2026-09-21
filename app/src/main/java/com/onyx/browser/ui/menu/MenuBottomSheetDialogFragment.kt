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
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.onyx.browser.R
import com.onyx.browser.data.local.AppDatabase
import com.onyx.browser.data.model.BookmarkItem
import com.onyx.browser.databinding.BottomSheetMenuBinding
import com.onyx.browser.ui.bookmarks.BookmarksActivity
import com.onyx.browser.ui.downloads.DownloadsActivity
import com.onyx.browser.ui.history.HistoryActivity
import com.onyx.browser.ui.settings.SettingsActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MenuBottomSheetDialogFragment : BottomSheetDialogFragment() {

    private var _binding: BottomSheetMenuBinding? = null
    private val binding get() = _binding!!

    var currentUrl: String = ""
    var currentTitle: String = ""
    var isDesktopSiteEnabled: Boolean = false

    var onNewTabClicked: (() -> Unit)? = null
    var onNewIncognitoTabClicked: (() -> Unit)? = null
    var onDesktopSiteToggled: ((Boolean) -> Unit)? = null
    var onFindInPageClicked: (() -> Unit)? = null

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

        setupQuickBar()
        setupDefaultBrowserBanner()
        setupMenuItems()
        checkBookmarkStatus()
    }

    private fun setupQuickBar() {
        // Bookmarks
        binding.menuQuickBookmarks.setOnClickListener {
            startActivity(Intent(requireContext(), BookmarksActivity::class.java))
            dismiss()
        }

        // History
        binding.menuQuickHistory.setOnClickListener {
            startActivity(Intent(requireContext(), HistoryActivity::class.java))
            dismiss()
        }

        // Downloads
        binding.menuQuickDownloads.setOnClickListener {
            startActivity(Intent(requireContext(), DownloadsActivity::class.java))
            dismiss()
        }

        // Share Page
        binding.menuQuickShare.setOnClickListener {
            if (currentUrl.isNotBlank()) {
                val sendIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_TEXT, currentUrl)
                    type = "text/plain"
                }
                val shareIntent = Intent.createChooser(sendIntent, "Share Link")
                startActivity(shareIntent)
            } else {
                Toast.makeText(requireContext(), "No webpage to share", Toast.LENGTH_SHORT).show()
            }
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

    private fun setupMenuItems() {
        // New Tab
        binding.menuItemNewTab.setOnClickListener {
            onNewTabClicked?.invoke()
            dismiss()
        }

        // New Incognito Tab
        binding.menuItemNewIncognitoTab.setOnClickListener {
            onNewIncognitoTabClicked?.invoke()
            dismiss()
        }

        // Desktop Site
        binding.switchDesktopSite.isChecked = isDesktopSiteEnabled
        binding.menuItemDesktopSite.setOnClickListener {
            val newState = !binding.switchDesktopSite.isChecked
            binding.switchDesktopSite.isChecked = newState
            onDesktopSiteToggled?.invoke(newState)
            dismiss()
        }

        // Find In Page
        binding.menuItemFindInPage.setOnClickListener {
            onFindInPageClicked?.invoke()
            dismiss()
        }

        // Add Bookmark
        binding.menuItemAddBookmark.setOnClickListener {
            toggleBookmark()
        }

        // Settings
        binding.menuItemSettings.setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
            dismiss()
        }
    }

    private fun checkBookmarkStatus() {
        if (currentUrl.isBlank()) return
        CoroutineScope(Dispatchers.IO).launch {
            val isBookmarked = AppDatabase.getInstance(requireContext())
                .bookmarkDao()
                .isBookmarked(currentUrl)
            withContext(Dispatchers.Main) {
                if (_binding != null) {
                    if (isBookmarked) {
                        binding.ivMenuBookmarkIcon.setImageResource(R.drawable.ic_bookmark)
                        binding.tvMenuBookmarkTitle.text = "Remove Bookmark"
                    } else {
                        binding.ivMenuBookmarkIcon.setImageResource(R.drawable.ic_bookmark_border)
                        binding.tvMenuBookmarkTitle.text = getString(R.string.add_bookmark)
                    }
                }
            }
        }
    }

    private fun toggleBookmark() {
        if (currentUrl.isBlank()) {
            Toast.makeText(requireContext(), "Cannot bookmark empty page", Toast.LENGTH_SHORT).show()
            dismiss()
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            val db = AppDatabase.getInstance(requireContext())
            val existing = db.bookmarkDao().getBookmarkByUrl(currentUrl)
            if (existing != null) {
                db.bookmarkDao().deleteBookmark(existing)
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Bookmark removed", Toast.LENGTH_SHORT).show()
                }
            } else {
                db.bookmarkDao().insertBookmark(
                    BookmarkItem(
                        url = currentUrl,
                        title = currentTitle.ifBlank { currentUrl }
                    )
                )
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Bookmark added", Toast.LENGTH_SHORT).show()
                }
            }
            withContext(Dispatchers.Main) {
                dismiss()
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
