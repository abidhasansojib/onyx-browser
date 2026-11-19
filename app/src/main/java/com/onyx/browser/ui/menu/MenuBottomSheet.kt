package com.onyx.browser.ui.menu

import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 16.dp)
            .verticalScroll(scrollState)
    ) {
        // Quick Action Row (Bookmarks, History, Downloads, Share)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            QuickActionButton(
                icon = if (isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                label = "Bookmark",
                tint = if (isBookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = onToggleBookmark
            )

            QuickActionButton(
                icon = Icons.Default.History,
                label = "History",
                onClick = onHistoryClick
            )

            QuickActionButton(
                icon = Icons.Default.Download,
                label = "Downloads",
                onClick = onDownloadsClick
            )

            QuickActionButton(
                icon = Icons.Default.Share,
                label = "Share",
                onClick = onShareClick
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (!isHomePage && currentUrl.isNotBlank()) {
            val domain = Uri.parse(currentUrl).host?.removePrefix("www.") ?: "Webpage"
            // Page Header Card
            CardContainer {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
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
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )

                    IconButton(onClick = onSiteShieldClick) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = "Shields",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        // Standard Menu Actions
        CardContainer {
            PreferenceItem(
                title = "New Tab",
                icon = Icons.Default.Add,
                onClick = onNewTabClick
            )

            PreferenceItem(
                title = "New Incognito Tab",
                icon = Icons.Default.VisibilityOff,
                onClick = onNewIncognitoTabClick
            )

            if (!isHomePage && currentUrl.isNotBlank()) {
                PreferenceItem(
                    title = "Close Tab",
                    icon = Icons.Default.Close,
                    onClick = onCloseTabClick
                )
            }

            if (!isHomePage) {
                PreferenceItem(
                    title = "Find in Page",
                    icon = Icons.Default.FindInPage,
                    onClick = onFindInPageClick
                )

                SwitchPreference(
                    title = "Desktop Site",
                    subtitle = "Request desktop version of this website",
                    icon = Icons.Default.Computer,
                    checked = isDesktop,
                    onCheckedChange = onDesktopToggle
                )

                PreferenceItem(
                    title = "Translate Webpage",
                    icon = Icons.Default.Translate,
                    onClick = onTranslateClick
                )

                PreferenceItem(
                    title = "Save Page as MHTML",
                    icon = Icons.Default.Save,
                    onClick = onSavePageClick
                )

                if (isAddToHomeScreenVisible) {
                    PreferenceItem(
                        title = "Add to Home Screen",
                        icon = Icons.Default.AddCircleOutline,
                        onClick = onAddToHomeScreenClick
                    )
                }

                if (isBlockElementVisible) {
                    PreferenceItem(
                        title = "Block Element on Page",
                        icon = Icons.Default.Build,
                        onClick = onBlockElementClick
                    )
                }

                PreferenceItem(
                    title = "Developer Tools",
                    subtitle = "Open in-page Eruda debugging console",
                    icon = Icons.Default.Code,
                    onClick = onDevToolsClick
                )
            }

            PreferenceItem(
                title = "Settings",
                icon = Icons.Default.Settings,
                onClick = onSettingsClick
            )
        }
    }
}

@Composable
private fun QuickActionButton(
    icon: ImageVector,
    label: String,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
