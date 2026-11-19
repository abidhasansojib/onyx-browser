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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.onyx.browser.MainActivity
import com.onyx.browser.R
import com.onyx.browser.data.local.AppDatabase
import com.onyx.browser.data.model.BookmarkItem
import com.onyx.browser.ui.theme.AccentColorManager
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.ui.bookmarks.BookmarksActivity
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
        val ctx = context
        if (ctx != null && isDefaultBrowser()) {
            Toast.makeText(ctx, "Onyx set as default browser", Toast.LENGTH_SHORT).show()
        }
        dismissAllowingStateLoss()
    }

    override fun getTheme(): Int = R.style.Theme_OnyxBrowser_BottomSheetDialog

    override fun onStart() {
        super.onStart()
        dialog?.let { d ->
            val bottomSheet = d.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let { sheet ->
                sheet.setBackgroundResource(android.R.color.transparent)
                val behavior = BottomSheetBehavior.from(sheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }
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
                val accentColor = remember(prefs.accentColorHex, prefs.accentColorPreset) {
                    try {
                        Color(AccentColorManager.getAccentColor(prefs))
                    } catch (_: Exception) {
                        null
                    }
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

                OnyxTheme(darkTheme = isDark, isAmoled = false, accentColor = accentColor) {
                    MenuSheetLayout(
                        isHomePage = isHomePage,
                        currentUrl = currentUrl,
                        currentTitle = currentTitle,
                        isBookmarked = isBookmarked,
                        isDefaultBrowser = isDefault,
                        cachedUpdate = cachedUpdate,
                        translateTargetLanguageName = prefs.targetTranslateLanguageName.ifBlank { "English" },
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
                        onTranslateSettingsClick = {
                            (activity as? MainActivity)?.browserMenuCoordinator?.showLanguageSelectionDialog()
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
            val ctx = context ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = ctx.getSystemService(RoleManager::class.java)
                if (roleManager?.isRoleHeld(RoleManager.ROLE_BROWSER) == true) {
                    return true
                }
            }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))
            val resolveInfo = ctx.packageManager.resolveActivity(intent, 0)
            resolveInfo?.activityInfo?.packageName == ctx.packageName
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
                dismissAllowingStateLoss()
                return
            } catch (_: Exception) {
            }
        }
        dismissAllowingStateLoss()
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
    translateTargetLanguageName: String = "English",
    onUpdateClick: (() -> Unit)? = null,
    onSetDefaultBrowserClick: (() -> Unit)? = null,
    onToggleBookmark: () -> Unit,
    onBookmarksClick: () -> Unit,
    onHistoryClick: () -> Unit,
    onDownloadsClick: () -> Unit,
    onShareClick: () -> Unit,
    onNewTabClick: () -> Unit,
    onNewIncognitoTabClick: () -> Unit,
    onCloseTabClick: (() -> Unit)? = null,
    onSiteShieldClick: () -> Unit,
    onFindInPageClick: () -> Unit,
    isDesktop: Boolean,
    onDesktopToggle: (Boolean) -> Unit,
    onTranslateClick: () -> Unit,
    onTranslateSettingsClick: () -> Unit,
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
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(scrollState)
                .padding(top = 8.dp, bottom = 16.dp)
        ) {
            // Drag handle
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 12.dp)
                    .size(width = 32.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            )

            // Update Available Banner (if update available)
            if (cachedUpdate != null) {
                UpdateBannerCard(
                    cachedUpdate = cachedUpdate,
                    onClick = { onUpdateClick?.invoke() }
                )
            }

            // Top Shortcuts Quick Bar (Bookmarks, History, Downloads, Share)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                QuickActionItem(
                    iconRes = if (!isHomePage && isBookmarked) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border,
                    label = stringResource(R.string.bookmarks),
                    isActive = !isHomePage && isBookmarked,
                    onClick = if (!isHomePage) onToggleBookmark else onBookmarksClick,
                    modifier = Modifier.weight(1f)
                )

                QuickActionItem(
                    iconRes = R.drawable.ic_history,
                    label = stringResource(R.string.history),
                    onClick = onHistoryClick,
                    modifier = Modifier.weight(1f)
                )

                QuickActionItem(
                    iconRes = R.drawable.ic_download,
                    label = stringResource(R.string.downloads),
                    onClick = onDownloadsClick,
                    modifier = Modifier.weight(1f)
                )

                QuickActionItem(
                    iconRes = R.drawable.ic_share,
                    label = stringResource(R.string.share),
                    onClick = onShareClick,
                    modifier = Modifier.weight(1f)
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
            )

            if (isHomePage) {
                // ── HOMEPAGE MENU (Reference: a.jpg) ──
                if (!isDefaultBrowser) {
                    DefaultBrowserBannerCard(
                        onClick = { onSetDefaultBrowserClick?.invoke() }
                    )
                }

                MenuRowItem(
                    iconRes = R.drawable.ic_add,
                    title = stringResource(R.string.new_tab),
                    onClick = onNewTabClick
                )

                MenuRowItem(
                    iconRes = R.drawable.ic_incognito,
                    title = stringResource(R.string.new_incognito_tab),
                    onClick = onNewIncognitoTabClick
                )

                MenuRowItem(
                    iconRes = R.drawable.ic_settings,
                    title = stringResource(R.string.settings),
                    onClick = onSettingsClick
                )
            } else {
                // ── WEBPAGE MENU (Reference: b.jpg) ──
                val domain = remember(currentUrl) {
                    try {
                        Uri.parse(currentUrl).host?.removePrefix("www.") ?: currentUrl
                    } catch (_: Exception) {
                        currentUrl
                    }
                }

                WebsiteHeaderCard(
                    domain = domain,
                    onShieldClick = onSiteShieldClick,
                    onShareClick = onShareClick
                )

                Spacer(modifier = Modifier.height(4.dp))

                MenuRowItem(
                    iconRes = R.drawable.ic_add,
                    title = stringResource(R.string.new_tab),
                    onClick = onNewTabClick
                )

                MenuRowItem(
                    iconRes = R.drawable.ic_incognito,
                    title = stringResource(R.string.new_incognito_tab),
                    onClick = onNewIncognitoTabClick
                )

                if (onCloseTabClick != null) {
                    MenuRowItem(
                        iconRes = R.drawable.ic_close,
                        title = stringResource(R.string.close_tab),
                        onClick = onCloseTabClick
                    )
                }

                MenuRowItem(
                    iconRes = R.drawable.ic_translate,
                    title = "Translate to $translateTargetLanguageName",
                    trailing = {
                        IconButton(
                            onClick = onTranslateSettingsClick,
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_settings),
                                contentDescription = stringResource(R.string.settings),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    },
                    onClick = onTranslateClick
                )

                MenuRowItem(
                    iconRes = R.drawable.ic_search,
                    title = stringResource(R.string.find_in_page),
                    onClick = onFindInPageClick
                )

                MenuRowItem(
                    iconRes = R.drawable.ic_desktop,
                    title = stringResource(R.string.desktop_site),
                    trailing = {
                        Switch(
                            checked = isDesktop,
                            onCheckedChange = { onDesktopToggle(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        )
                    },
                    onClick = { onDesktopToggle(!isDesktop) }
                )

                MenuRowItem(
                    iconRes = R.drawable.ic_download,
                    title = stringResource(R.string.save_this_page),
                    onClick = onSavePageClick
                )

                MenuRowItem(
                    iconRes = R.drawable.ic_terminal_outline,
                    title = stringResource(R.string.developer_tools),
                    subtitle = stringResource(R.string.developer_tools_subtitle),
                    onClick = onDevToolsClick
                )

                if (isBlockElementVisible) {
                    MenuRowItem(
                        iconRes = R.drawable.ic_block_element,
                        title = stringResource(R.string.block_element),
                        onClick = onBlockElementClick
                    )
                }

                if (isAddToHomeScreenVisible) {
                    MenuRowItem(
                        iconRes = R.drawable.ic_add_to_home_screen,
                        title = stringResource(R.string.add_to_home_screen),
                        onClick = onAddToHomeScreenClick
                    )
                }

                HorizontalDivider(
                    modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                )

                MenuRowItem(
                    iconRes = R.drawable.ic_settings,
                    title = stringResource(R.string.settings),
                    onClick = onSettingsClick
                )
            }
        }
    }
}

@Composable
private fun QuickActionItem(
    iconRes: Int,
    label: String,
    isActive: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp)
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = label,
            tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(26.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
            fontWeight = FontWeight.Medium,
            color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun WebsiteHeaderCard(
    domain: String,
    onShieldClick: () -> Unit,
    onShareClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onShieldClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_web),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp)
            )

            Spacer(modifier = Modifier.width(10.dp))

            Text(
                text = domain,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                    .clickable(onClick = onShieldClick),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_shield_lock),
                    contentDescription = "Shields",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                    .clickable(onClick = onShareClick),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_share),
                    contentDescription = "Share",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun MenuRowItem(
    iconRes: Int,
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (subtitle != null) 54.dp else 48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(24.dp)
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (trailing != null) {
            trailing()
        }
    }
}

@Composable
private fun DefaultBrowserBannerCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
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
                    style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.set_default_desc),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
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

@Composable
private fun UpdateBannerCard(
    cachedUpdate: UpdateInfo,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
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
                    style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.update_available_subtitle, cachedUpdate.tagName),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
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
