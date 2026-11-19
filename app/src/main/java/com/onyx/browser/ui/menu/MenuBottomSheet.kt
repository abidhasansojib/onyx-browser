package com.onyx.browser.ui.menu

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val prefs = BrowserPreferences.getInstance(requireContext())
                val isDark = when (prefs.themeMode) {
                    BrowserPreferences.THEME_DARK -> true
                    BrowserPreferences.THEME_LIGHT -> false
                    else -> isSystemInDarkTheme()
                }
                val isAmoled = prefs.themeMode == BrowserPreferences.THEME_DARK

                var isBookmarked by remember { mutableStateOf(false) }
                val db = AppDatabase.getInstance(requireContext())
                val cachedUpdate = remember { AppUpdateManager.getCachedUpdate(requireContext()) }

                if (currentUrl.isNotBlank()) {
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        val exists = db.bookmarkDao().isBookmarked(currentUrl)
                        withContext(Dispatchers.Main) {
                            isBookmarked = exists
                        }
                    }
                }

                OnyxTheme(darkTheme = isDark, isAmoled = isAmoled) {
                    MenuSheetLayout(
                        isHomePage = isHomePage,
                        currentUrl = currentUrl,
                        currentTitle = currentTitle,
                        isBookmarked = isBookmarked,
                        cachedUpdate = cachedUpdate,
                        onUpdateClick = {
                            val act = activity as? AppCompatActivity
                            dismiss()
                            if (act != null && cachedUpdate != null) {
                                AppUpdatePromptHelper.showUpdateAvailableDialog(act, cachedUpdate)
                            }
                        },
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
    cachedUpdate: UpdateInfo? = null,
    onUpdateClick: (() -> Unit)? = null,
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
        color = MaterialTheme.colorScheme.surface,
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
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onUpdateClick?.invoke() }
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_sync),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.update_available_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = stringResource(R.string.update_available_subtitle, cachedUpdate.tagName),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            // Webpage Site Header Card (only shown when visiting a website)
            if (!isHomePage && currentUrl.isNotBlank()) {
                val domain = remember(currentUrl) {
                    try {
                        Uri.parse(currentUrl).host?.removePrefix("www.") ?: currentUrl
                    } catch (_: Exception) {
                        currentUrl
                    }
                }
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = colorResource(R.color.shortcut_tile_bg),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
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
                                imageVector = Icons.Default.Security,
                                contentDescription = "Shields",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            // Quick Action Row (Bookmarks, History, Downloads, Share)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                QuickActionButton(
                    iconRes = R.drawable.ic_bookmark,
                    label = if (!isHomePage && isBookmarked) "Bookmarked" else stringResource(R.string.bookmarks),
                    tint = if (!isHomePage && isBookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    onClick = if (!isHomePage) onToggleBookmark else onBookmarksClick
                )

                QuickActionButton(
                    iconRes = R.drawable.ic_history,
                    label = stringResource(R.string.history),
                    tint = MaterialTheme.colorScheme.onSurface,
                    onClick = onHistoryClick
                )

                QuickActionButton(
                    iconRes = R.drawable.ic_download,
                    label = stringResource(R.string.downloads),
                    tint = MaterialTheme.colorScheme.onSurface,
                    onClick = onDownloadsClick
                )

                QuickActionButton(
                    iconRes = R.drawable.ic_share,
                    label = stringResource(R.string.share),
                    tint = MaterialTheme.colorScheme.onSurface,
                    onClick = onShareClick
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            // Primary Navigation Actions
            MenuItemRow(
                title = stringResource(R.string.new_tab),
                icon = Icons.Default.Add,
                onClick = onNewTabClick
            )

            MenuItemRow(
                title = stringResource(R.string.new_incognito_tab),
                iconResId = R.drawable.ic_incognito,
                onClick = onNewIncognitoTabClick
            )

            if (!isHomePage && currentUrl.isNotBlank()) {
                MenuItemRow(
                    title = "Close Tab",
                    icon = Icons.Default.Close,
                    onClick = onCloseTabClick
                )
            }

            // Webpage-specific Actions
            if (!isHomePage) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                )

                MenuItemRow(
                    title = "Find in Page",
                    icon = Icons.Default.FindInPage,
                    onClick = onFindInPageClick
                )

                MenuItemRow(
                    title = "Desktop Site",
                    subtitle = "Request desktop version",
                    icon = Icons.Default.Computer,
                    trailing = {
                        Switch(
                            checked = isDesktop,
                            onCheckedChange = onDesktopToggle,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                checkedTrackColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    },
                    onClick = { onDesktopToggle(!isDesktop) }
                )

                MenuItemRow(
                    title = "Translate Webpage",
                    icon = Icons.Default.Translate,
                    onClick = onTranslateClick
                )

                MenuItemRow(
                    title = "Save this page",
                    icon = Icons.Default.Save,
                    onClick = onSavePageClick
                )

                if (isAddToHomeScreenVisible) {
                    MenuItemRow(
                        title = "Add to Home Screen",
                        icon = Icons.Default.AddCircleOutline,
                        onClick = onAddToHomeScreenClick
                    )
                }

                if (isBlockElementVisible) {
                    MenuItemRow(
                        title = "Block Element on Page",
                        icon = Icons.Default.Build,
                        onClick = onBlockElementClick
                    )
                }

                MenuItemRow(
                    title = "Developer Tools",
                    subtitle = "In-page Eruda console",
                    icon = Icons.Default.Code,
                    onClick = onDevToolsClick
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            )

            MenuItemRow(
                title = stringResource(R.string.settings),
                icon = Icons.Default.Settings,
                onClick = onSettingsClick
            )

            Spacer(modifier = Modifier.height(10.dp))
        }
    }
}

@Composable
private fun QuickActionButton(
    iconRes: Int,
    label: String,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(50.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(colorResource(R.color.shortcut_tile_bg)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
    }
}

@Composable
private fun MenuItemRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconResId: Int? = null,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (iconResId != null) {
            Icon(
                painter = painterResource(iconResId),
                contentDescription = title,
                tint = iconTint,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
        } else if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = iconTint,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (trailing != null) {
            trailing()
        }
    }
}
