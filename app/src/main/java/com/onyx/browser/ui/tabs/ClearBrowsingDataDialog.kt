package com.onyx.browser.ui.tabs

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebViewDatabase
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.onyx.browser.R
import com.onyx.browser.data.local.AppDatabase
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.ui.browser.TabCoordinator
import com.onyx.browser.ui.theme.AccentColorManager
import com.onyx.browser.ui.theme.OnyxTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI

class ClearBrowsingDataDialog @JvmOverloads constructor(
    private var tabManager: TabCoordinator? = TabCoordinator.activeInstance,
    private var onDataCleared: () -> Unit = {}
) : DialogFragment() {

    private fun getCutoffTime(selectedIndex: Int): Long {
        val now = System.currentTimeMillis()
        return when (selectedIndex) {
            0 -> now - 15 * 60 * 1000L           // Last 15 minutes
            1 -> now - 60 * 60 * 1000L           // Last hour
            2 -> now - 24 * 60 * 60 * 1000L      // Last 24 hours
            3 -> now - 7 * 24 * 60 * 60 * 1000L  // Last 7 days
            4 -> now - 28L * 24 * 60 * 60 * 1000L // Last 4 weeks
            else -> 0L                           // All time
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, 0)
        if (tabManager == null) {
            tabManager = TabCoordinator.activeInstance
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        dialog?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
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
                    androidx.compose.ui.graphics.Color(AccentColorManager.getAccentColor(prefs))
                }

                OnyxTheme(darkTheme = isDark, isAmoled = false, accentColor = accentColor) {
                    ClearBrowsingDataContent()
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ClearBrowsingDataContent() {
        val context = LocalContext.current
        val timeRanges = listOf(
            stringResource(R.string.time_range_last_15_mins),
            stringResource(R.string.time_range_last_hour),
            stringResource(R.string.time_range_last_24_hours),
            stringResource(R.string.time_range_last_7_days),
            stringResource(R.string.time_range_last_4_weeks),
            stringResource(R.string.time_range_all_time)
        )

        var selectedTimeRangeIndex by remember { mutableIntStateOf(2) } // Default 24 hours
        var timeRangeDropdownExpanded by remember { mutableStateOf(false) }

        var clearHistory by remember { mutableStateOf(true) }
        var clearTabs by remember { mutableStateOf(false) }
        var clearCookies by remember { mutableStateOf(false) }
        var clearCache by remember { mutableStateOf(false) }

        var historyCount by remember { mutableIntStateOf(0) }
        var historyExample by remember { mutableStateOf("") }
        var tabsCount by remember { mutableIntStateOf(0) }
        var tabsExample by remember { mutableStateOf("") }

        var isClearing by remember { mutableStateOf(false) }

        // Update previews when selectedTimeRangeIndex changes
        LaunchedEffect(selectedTimeRangeIndex) {
            val cutoff = getCutoffTime(selectedTimeRangeIndex)
            val database = AppDatabase.getInstance(context)

            val historyItems = withContext(Dispatchers.IO) {
                try {
                    if (cutoff == 0L) {
                        database.historyDao().getHistorySince(0L)
                    } else {
                        database.historyDao().getHistorySince(cutoff)
                    }
                } catch (_: Exception) {
                    emptyList()
                }
            }

            historyCount = historyItems.size
            val distinctHosts = historyItems.mapNotNull { item ->
                try {
                    URI(item.url).host?.removePrefix("www.")
                } catch (_: Exception) {
                    item.title.takeIf { it.isNotBlank() }
                }
            }.distinct().take(3)

            historyExample = if (distinctHosts.isNotEmpty()) {
                "e.g. " + distinctHosts.joinToString(", ")
            } else {
                "None recorded in this period"
            }

            val tm = tabManager ?: TabCoordinator.activeInstance
            val normalTabs = tm?.normalTabs?.value ?: emptyList()
            val archivedTabs = tm?.archivedTabs?.value ?: emptyList()
            val incognitoTabs = tm?.incognitoTabs?.value ?: emptyList()
            val allTabs = normalTabs + archivedTabs + incognitoTabs

            val matchingTabs = if (cutoff == 0L) {
                allTabs
            } else {
                allTabs.filter { it.createdAt >= cutoff }
            }

            tabsCount = matchingTabs.size
            val tabTitles = matchingTabs.map { it.title.ifBlank { "New Tab" } }.take(2)
            tabsExample = if (tabTitles.isNotEmpty()) {
                "e.g. " + tabTitles.joinToString(", ")
            } else {
                "None opened in this period"
            }
        }

        val hasSelection = clearHistory || clearTabs || clearCookies || clearCache

        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            tonalElevation = 0.dp,
            shadowElevation = 12.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Dialog Title
                Text(
                    text = stringResource(R.string.delete_browsing_data),
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 21.sp),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                // Time Range Label
                Text(
                    text = "Time range",
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp)
                )

                // Time Range Spinner Dropdown
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                            .clickable { timeRangeDropdownExpanded = true }
                            .padding(horizontal = 16.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = timeRanges[selectedTimeRangeIndex],
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    DropdownMenu(
                        expanded = timeRangeDropdownExpanded,
                        onDismissRequest = { timeRangeDropdownExpanded = false },
                        modifier = Modifier.widthIn(min = 260.dp),
                        shape = RoundedCornerShape(16.dp),
                        containerColor = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        tonalElevation = 0.dp,
                        shadowElevation = 8.dp
                    ) {
                        timeRanges.forEachIndexed { index, text ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = text,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                                        fontWeight = if (index == selectedTimeRangeIndex) FontWeight.Bold else FontWeight.Normal,
                                        color = if (index == selectedTimeRangeIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                },
                                onClick = {
                                    selectedTimeRangeIndex = index
                                    timeRangeDropdownExpanded = false
                                },
                                modifier = Modifier.height(44.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)
                            )
                        }
                    }
                }

                // Options Card with Granular Checkboxes
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    elevation = CardDefaults.cardElevation(0.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // 1. Browsing History Option
                        ClearDataItemRow(
                            iconRes = R.drawable.ic_history,
                            title = stringResource(R.string.browsing_history_preview, historyCount, ""),
                            subtitle = historyExample,
                            checked = clearHistory,
                            onCheckedChange = { clearHistory = it }
                        )

                        HorizontalDivider(
                            thickness = 1.dp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                        )

                        // 2. Open Tabs Option
                        ClearDataItemRow(
                            iconRes = R.drawable.ic_tabs,
                            title = stringResource(R.string.open_tabs_preview, tabsCount, ""),
                            subtitle = tabsExample,
                            checked = clearTabs,
                            onCheckedChange = { clearTabs = it }
                        )

                        HorizontalDivider(
                            thickness = 1.dp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                        )

                        // 3. Cookies and Site Data Option
                        ClearDataItemRow(
                            iconRes = R.drawable.ic_shield_lock,
                            title = stringResource(R.string.clear_data_cookies),
                            subtitle = stringResource(R.string.clear_data_cookies_desc),
                            checked = clearCookies,
                            onCheckedChange = { clearCookies = it }
                        )

                        HorizontalDivider(
                            thickness = 1.dp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                        )

                        // 4. Cached Images and Files Option
                        ClearDataItemRow(
                            iconRes = R.drawable.ic_broom,
                            title = stringResource(R.string.clear_data_cache),
                            subtitle = stringResource(R.string.clear_data_cache_desc),
                            checked = clearCache,
                            onCheckedChange = { clearCache = it }
                        )
                    }
                }

                // Dialog Buttons: Cancel & Clear Data
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 22.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Cancel Button
                    Button(
                        onClick = { dismissAllowingStateLoss() },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        enabled = !isClearing,
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.cancel),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    // Clear Data Button
                    Button(
                        onClick = {
                            isClearing = true
                            performClearData(
                                selectedIndex = selectedTimeRangeIndex,
                                clearHistory = clearHistory,
                                clearTabs = clearTabs,
                                clearCookies = clearCookies,
                                clearCache = clearCache
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        enabled = hasSelection && !isClearing,
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f),
                            disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.38f)
                        )
                    ) {
                        if (isClearing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.clear_data),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ClearDataItemRow(
        @DrawableRes iconRes: Int,
        title: String,
        subtitle: String,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onCheckedChange(!checked) }
                .padding(start = 14.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(end = 12.dp)
                    .size(22.dp)
            )

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Checkbox(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = CheckboxDefaults.colors(
                    checkedColor = MaterialTheme.colorScheme.primary,
                    uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            )
        }
    }

    private fun performClearData(
        selectedIndex: Int,
        clearHistory: Boolean = true,
        clearTabs: Boolean = false,
        clearCookies: Boolean = false,
        clearCache: Boolean = false
    ) {
        val cutoff = getCutoffTime(selectedIndex)
        val appContext = context?.applicationContext ?: return
        val database = AppDatabase.getInstance(appContext)
        val tm = tabManager ?: TabCoordinator.activeInstance

        viewLifecycleOwner.lifecycleScope.launch {
            // 1. Delete history (if selected)
            if (clearHistory) {
                withContext(Dispatchers.IO) {
                    try {
                        if (cutoff == 0L) {
                            database.historyDao().clearAllHistory()
                        } else {
                            database.historyDao().deleteHistorySince(cutoff)
                        }
                    } catch (_: Exception) {}
                }
            }

            // 2. Close tabs created/active in this timeframe (if selected)
            if (clearTabs) {
                try {
                    if (cutoff == 0L) {
                        tm?.closeAllTabs(incognitoOnly = true, canUndo = false)
                        tm?.closeAllTabs(incognitoOnly = false, canUndo = false)
                    } else {
                        tm?.closeTabsCreatedSince(cutoff)
                    }
                } catch (_: Exception) {}
            }

            // 3. Clear WebView cookies and web storage across all profiles (if selected)
            if (clearCookies) {
                try {
                    CookieManager.getInstance().removeAllCookies {
                        CookieManager.getInstance().flush()
                    }
                    CookieManager.getInstance().removeSessionCookies {
                        CookieManager.getInstance().flush()
                    }
                    CookieManager.getInstance().flush()
                    WebStorage.getInstance().deleteAllData()

                    if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.MULTI_PROFILE)) {
                        try {
                            val store = androidx.webkit.ProfileStore.getInstance()
                            val incognitoProfile = store.getProfile(com.onyx.browser.web.BrowserWebView.INCOGNITO_PROFILE_NAME)
                            if (incognitoProfile != null) {
                                incognitoProfile.cookieManager.removeAllCookies(null)
                                incognitoProfile.cookieManager.flush()
                                incognitoProfile.webStorage.deleteAllData()
                            }
                        } catch (_: Throwable) {}
                    }
                } catch (_: Exception) {}

                try {
                    val db = WebViewDatabase.getInstance(appContext)
                    db.clearHttpAuthUsernamePassword()
                    db.clearFormData()
                } catch (_: Exception) {}

                try {
                    val prefs = com.onyx.browser.data.preferences.BrowserPreferences.getInstance(appContext)
                    prefs.clearAllCustomBlockedRules()
                } catch (_: Exception) {}
            }

            // 4. Clear disk cache & preview files (if selected)
            if (clearCache) {
                withContext(Dispatchers.IO) {
                    try {
                        val archivesDir = File(appContext.cacheDir, "web_archives")
                        if (archivesDir.exists()) {
                            if (cutoff == 0L) {
                                archivesDir.deleteRecursively()
                                com.onyx.browser.web.LocalFileLoader.cleanupAllPreviews(appContext)
                            } else {
                                archivesDir.listFiles()?.forEach { f ->
                                    if (f.lastModified() >= cutoff) {
                                        f.delete()
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {}

                    if (cutoff == 0L) {
                        try {
                            com.onyx.browser.data.favicon.FaviconManager.clearCache(appContext)
                        } catch (_: Exception) {}
                        try {
                            tm?.clearAllThumbnailsAndCache()
                        } catch (_: Exception) {}
                        try {
                            val apk = File(appContext.cacheDir, "install_pending.apk")
                            if (apk.exists() && System.currentTimeMillis() - apk.lastModified() > 60_000L) {
                                apk.delete()
                            }
                        } catch (_: Exception) {}
                    }
                }
            }

            // 5. Clear cache on active WebViews safely on Main thread
            withContext(Dispatchers.Main) {
                if (clearCache) {
                    try {
                        tm?.getAllWebViews()?.forEach { wv ->
                            wv.clearCache(true)
                            wv.clearFormData()
                            wv.clearSslPreferences()
                            wv.lastOAuthInteractionTimestamp = 0L
                        }
                    } catch (_: Exception) {}
                }

                if (clearCookies) {
                    try {
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().flush()
                    } catch (_: Exception) {}
                }

                try {
                    Toast.makeText(appContext, getString(R.string.browsing_data_cleared), Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {}

                try {
                    onDataCleared()
                } catch (_: Exception) {}

                try {
                    dismissAllowingStateLoss()
                } catch (_: Exception) {}
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.let { window ->
            val displayMetrics = resources.displayMetrics
            val maxAllowedWidth = (440 * displayMetrics.density).toInt()
            val targetWidth = (displayMetrics.widthPixels * 0.92).toInt().coerceAtMost(maxAllowedWidth)
            window.setLayout(targetWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    companion object {
        const val TAG = "ClearBrowsingDataDialog"
    }
}
