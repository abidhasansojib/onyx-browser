package com.onyx.browser.ui.menu

import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.onyx.browser.MainActivity
import com.onyx.browser.R
import com.onyx.browser.data.local.AppDatabase
import com.onyx.browser.data.model.BookmarkItem
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.ui.bookmarks.BookmarksActivity
import com.onyx.browser.ui.components.CardContainer
import com.onyx.browser.ui.components.PreferenceItem
import com.onyx.browser.ui.components.SwitchPreference
import com.onyx.browser.ui.downloads.DownloadsActivity
import com.onyx.browser.ui.history.HistoryActivity
import com.onyx.browser.ui.settings.SettingsActivity
import com.onyx.browser.ui.theme.OnyxTheme
import com.onyx.browser.update.AppUpdateManager
import com.onyx.browser.update.AppUpdatePromptHelper
import com.onyx.browser.update.UpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MenuBottomSheet : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "MenuBottomSheet"
    }

    var isHomePage: Boolean = true
    var currentUrl: String = ""
    var currentTitle: String = ""
    var isDesktopSiteEnabled: Boolean = false

    var onCloseTabClicked: (() -> Unit)? = null
    var onDesktopSiteToggled: ((Boolean) -> Unit)? = null
    var onFindInPageClicked: (() -> Unit)? = null
    var onTranslateClicked: ((String) -> Unit)? = null
    var onAddToHomeScreenClicked: (() -> Unit)? = null
    var onDeveloperToolsClicked: (() -> Unit)? = null
    var onBlockElementClicked: (() -> Unit)? = null
    var onSavePageClicked: (() -> Unit)? = null
    var onSiteShieldWhitelistChanged: ((Boolean) -> Unit)? = null

    private val roleRequestLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) {
        if (isDefaultBrowser()) {
            Toast.makeText(requireContext(), "Onyx set as default browser", Toast.LENGTH_SHORT).show()
        }
        dismiss()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val prefs = remember { BrowserPreferences.getInstance(requireContext()) }
                val isDark = when (prefs.themeMode) {
                    BrowserPreferences.THEME_DARK -> true
                    BrowserPreferences.THEME_LIGHT -> false
                    else -> isSystemInDarkTheme()
                }

                var isBookmarked by remember { mutableStateOf(false) }
                val db = AppDatabase.getInstance(requireContext())
                val cachedUpdate = remember { AppUpdateManager.getCachedUpdate(requireContext()) }
                val isDefault = remember { isDefaultBrowser() }

                if (currentUrl.isNotBlank()) {
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        val exists = db.bookmarkDao().isBookmarked(currentUrl)
                        withContext(Dispatchers.Main) {
                            isBookmarked = exists
                        }
                    }
                }

                OnyxTheme(darkTheme = isDark, isAmoled = false) {
                    MenuSheetLayout(
                        isHomePage = isHomePage,
                        currentUrl = currentUrl,
                        currentTitle = currentTitle,
                        isBookmarked = isBookmarked,
                        isDefaultBrowser = isDefault,
                        cachedUpdate = cachedUpdate,
                        onUpdateClick = {
                            val act = activity as? AppCompatActivity
                            dismiss()
                            if (act != null && cachedUpdate != null) {
                                AppUpdatePromptHelper.showUpdateAvailableDialog(act, cachedUpdate)
                            }
                        },
                        onSetDefaultBrowserClick = { promptSetDefaultBrowser() },
                        onToggleBookmark = {
                            if (currentUrl.isNotBlank()) {
                                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                                    val exists = db.bookmarkDao().isBookmarked(currentUrl)
                                    if (exists) {
                                        db.bookmarkDao().deleteByUrl(currentUrl)
                                    } else {
                                        val title = currentTitle.ifBlank { currentUrl }
                                        db.bookmarkDao().insertBookmark(BookmarkItem(url = currentUrl, title = title))
                                    }
                                    withContext(Dispatchers.Main) {
                                        isBookmarked = !exists
                                        Toast.makeText(
                                            requireContext(),
                                            if (!exists) R.string.bookmark_added else R.string.bookmark_removed,
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            } else {
                                openBookmarks()
                            }
                        },
                        onBookmarksClick = { openBookmarks() },
                        onHistoryClick = { openHistory() },
                        onDownloadsClick = { openDownloads() },
                        onShareClick = { sharePage() },
                        onNewTabClick = {
                            (activity as? MainActivity)?.openNewTab(isIncognito = false)
                            dismiss()
                        },
                        onNewIncognitoTabClick = {
                            (activity as? MainActivity)?.openNewTab(isIncognito = true)
                            dismiss()
                        },
                        onCloseTabClick = {
                            onCloseTabClicked?.invoke()
                            dismiss()
                        },
                        onSiteShieldClick = {
                            openSiteShield()
                        },
                        onFindInPageClick = {
                            onFindInPageClicked?.invoke()
                            dismiss()
                        },
                        isDesktop = isDesktopSiteEnabled,
                        onDesktopToggle = {
                            isDesktopSiteEnabled = it
                            onDesktopSiteToggled?.invoke(it)
                            dismiss()
                        },
                        onTranslateClick = {
                            onTranslateClicked?.invoke(prefs.targetTranslateLanguage)
                            dismiss()
                        },
                        onSavePageClick = {
                            onSavePageClicked?.invoke()
                            dismiss()
                        },
                        isAddToHomeScreenVisible = prefs.isAddToHomeScreenEnabled,
                        onAddToHomeScreenClick = {
                            onAddToHomeScreenClicked?.invoke()
                            dismiss()
                        },
                        isBlockElementVisible = prefs.isBlockElementEnabled,
                        onBlockElementClick = {
                            onBlockElementClicked?.invoke()
                            dismiss()
                        },
                        onDevToolsClick = {
                            onDeveloperToolsClicked?.invoke()
                            dismiss()
                        },
                        onSettingsClick = {
                            startActivity(Intent(requireContext(), SettingsActivity::class.java))
                            dismiss()
                        }
                    )
                }
            }
        }
    }

    private fun isDefaultBrowser(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = requireContext().getSystemService(RoleManager::class.java)
                roleManager?.isRoleHeld(RoleManager.ROLE_BROWSER) == true
            } else {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))
                val resolveInfo = requireContext().packageManager.resolveActivity(intent, 0)
                resolveInfo?.activityInfo?.packageName == requireContext().packageName
            }
        } catch (_: Exception) {
            false
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
                dismiss()
                return
            } catch (_: Exception) {
            }
        }
        dismiss()
    }

    private fun openBookmarks() {
        (activity as? MainActivity)?.openBookmarks() ?: startActivity(Intent(requireContext(), BookmarksActivity::class.java))
        dismiss()
    }

    private fun openHistory() {
        (activity as? MainActivity)?.openHistory() ?: startActivity(Intent(requireContext(), HistoryActivity::class.java))
        dismiss()
    }

    private fun openDownloads() {
        (activity as? MainActivity)?.openDownloads() ?: startActivity(Intent(requireContext(), DownloadsActivity::class.java))
        dismiss()
    }

    private fun sharePage() {
        val url = currentUrl.ifBlank { "https://github.com/abidhasansojib/onyx-browser" }
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, url)
            type = "text/plain"
        }
        startActivity(Intent.createChooser(sendIntent, getString(R.string.share)))
        dismiss()
    }

    private fun openSiteShield() {
        val main = activity as? MainActivity
        val activeWv = main?.getActiveWebView()
        val blockedCount = activeWv?.pageBlockedCount ?: 0
        val isIncognito = activeWv?.isIncognito ?: false
        val cert = activeWv?.certificate
        val dialog = SiteShieldBottomSheet(
            currentUrl = currentUrl,
            pageBlockedCount = blockedCount,
            isIncognito = isIncognito,
            sslCertificate = cert
        ) {
            onSiteShieldWhitelistChanged?.invoke(true)
        }
        dialog.show(childFragmentManager, SiteShieldBottomSheet.TAG)
    }
}

@Composable
fun MenuSheetLayout(
    isHomePage: Boolean,
    currentUrl: String,
    currentTitle: String,
    isBookmarked: Boolean,
    isDefaultBrowser: Boolean = true,
    cachedUpdate: UpdateInfo? = null,
    onUpdateClick: (() -> Unit)? = null,
    onSetDefaultBrowserClick: (() -> Unit)? = null,
    onToggleBookmark: () -> Unit,
    onBookmarksClick: () -> Unit,
    onHistoryClick: () -> Unit,
    onDownloadsClick: () -> Unit,
    onShareClick: () -> Unit,
    onNewTabClick: () -> Unit,
    onNewIncognitoTabClick: () -> Unit,
    onCloseTabClick: () -> Unit,
    onSiteShieldClick: () -> Unit,
    onFindInPageClick: () -> Unit,
    isDesktop: Boolean,
    onDesktopToggle: (Boolean) -> Unit,
    onTranslateClick: () -> Unit,
    onSavePageClick: () -> Unit,
    isAddToHomeScreenVisible: Boolean,
    onAddToHomeScreenClick: () -> Unit,
    isBlockElementVisible: Boolean,
    onBlockElementClick: () -> Unit,
    onDevToolsClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    val scrollState = rememberScrollState()

    Surface(
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = MaterialTheme.colorScheme.background,
        tonalElevation = 4.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(scrollState)
                .padding(vertical = 12.dp)
        ) {
            // Drag handle
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 4.dp, bottom = 12.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )

            // Update Available Banner
            if (cachedUpdate != null) {
                CardContainer {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onUpdateClick?.invoke() }
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_refresh),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(26.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.update_available_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.update_available_subtitle, cachedUpdate.tagName),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.primary)
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.update),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Webpage Site Header Card (only shown when visiting an active website)
            if (!isHomePage && currentUrl.isNotBlank()) {
                val domain = remember(currentUrl) {
                    try {
                        Uri.parse(currentUrl).host?.removePrefix("www.") ?: currentUrl
                    } catch (_: Exception) {
                        currentUrl
                    }
                }
                CardContainer {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_web),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = domain,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        IconButton(
                            onClick = onSiteShieldClick,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_shield_lock),
                                contentDescription = "Shields",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        IconButton(
                            onClick = onShareClick,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_share),
                                contentDescription = "Share",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            // Top Shortcuts Quick Bar (Bookmarks, History, Downloads, Share)
            CardContainer {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp, horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    QuickActionButton(
                        iconRes = if (!isHomePage && isBookmarked) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border,
                        label = if (!isHomePage && isBookmarked) "Bookmarked" else stringResource(R.string.bookmarks),
                        isActive = !isHomePage && isBookmarked,
                        onClick = if (!isHomePage) onToggleBookmark else onBookmarksClick
                    )

                    QuickActionButton(
                        iconRes = R.drawable.ic_history,
                        label = stringResource(R.string.history),
                        onClick = onHistoryClick
                    )

                    QuickActionButton(
                        iconRes = R.drawable.ic_download,
                        label = stringResource(R.string.downloads),
                        onClick = onDownloadsClick
                    )

                    QuickActionButton(
                        iconRes = R.drawable.ic_share,
                        label = stringResource(R.string.share),
                        onClick = onShareClick
                    )
                }
            }

            // Set as Default Browser Banner (Shown on Homepage when not default browser)
            if (isHomePage && !isDefaultBrowser) {
                CardContainer {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSetDefaultBrowserClick?.invoke() }
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_shield_lock),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.set_as_default_browser),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.set_default_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            painter = painterResource(R.drawable.ic_external),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Tabs & Navigation Section
            CategoryHeader("Navigation")
            CardContainer {
                PreferenceItem(
                    title = stringResource(R.string.new_tab),
                    iconResId = R.drawable.ic_add,
                    showChevron = false,
                    onClick = onNewTabClick
                )

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                )

                PreferenceItem(
                    title = stringResource(R.string.new_incognito_tab),
                    iconResId = R.drawable.ic_incognito,
                    showChevron = false,
                    onClick = onNewIncognitoTabClick
                )

                if (!isHomePage && currentUrl.isNotBlank()) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                    )

                    PreferenceItem(
                        title = stringResource(R.string.close_tab),
                        iconResId = R.drawable.ic_close,
                        showChevron = false,
                        onClick = onCloseTabClick
                    )
                }
            }

            // Webpage-specific Actions (Page Actions & Tools)
            if (!isHomePage) {
                CategoryHeader("Page Actions")
                CardContainer {
                    SwitchPreference(
                        title = stringResource(R.string.desktop_site),
                        subtitle = "Request desktop version",
                        iconResId = R.drawable.ic_desktop,
                        checked = isDesktop,
                        onCheckedChange = onDesktopToggle
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                    )

                    PreferenceItem(
                        title = stringResource(R.string.find_in_page),
                        iconResId = R.drawable.ic_search,
                        showChevron = false,
                        onClick = onFindInPageClick
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                    )

                    PreferenceItem(
                        title = "Translate Webpage",
                        iconResId = R.drawable.ic_translate,
                        showChevron = false,
                        onClick = onTranslateClick
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                    )

                    PreferenceItem(
                        title = stringResource(R.string.save_this_page),
                        subtitle = "Save as original, MHTML, or PDF",
                        iconResId = R.drawable.ic_download,
                        showChevron = false,
                        onClick = onSavePageClick
                    )

                    if (isAddToHomeScreenVisible) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                        )

                        PreferenceItem(
                            title = stringResource(R.string.add_to_home_screen),
                            iconResId = R.drawable.ic_add_to_home_screen,
                            showChevron = false,
                            onClick = onAddToHomeScreenClick
                        )
                    }
                }

                CategoryHeader("Developer & Tools")
                CardContainer {
                    if (isBlockElementVisible) {
                        PreferenceItem(
                            title = stringResource(R.string.block_element),
                            iconResId = R.drawable.ic_block_element,
                            showChevron = false,
                            onClick = onBlockElementClick
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                        )
                    }

                    PreferenceItem(
                        title = stringResource(R.string.developer_tools),
                        subtitle = stringResource(R.string.developer_tools_subtitle),
                        iconResId = R.drawable.ic_terminal_outline,
                        showChevron = false,
                        onClick = onDevToolsClick
                    )
                }
            }

            // Settings Section
            CategoryHeader(stringResource(R.string.settings))
            CardContainer {
                PreferenceItem(
                    title = stringResource(R.string.settings),
                    subtitle = "Preferences, privacy, shields & appearance",
                    iconResId = R.drawable.ic_settings,
                    showChevron = true,
                    onClick = onSettingsClick
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun CategoryHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, top = 14.dp, bottom = 4.dp)
    )
}

@Composable
private fun QuickActionButton(
    iconRes: Int,
    label: String,
    isActive: Boolean = false,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(50.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    else colorResource(R.color.shortcut_tile_bg)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = label,
                tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
    }
}
