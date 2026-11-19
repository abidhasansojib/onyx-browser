package com.onyx.browser.ui.history

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import com.onyx.browser.R
import com.onyx.browser.data.model.HistoryItem
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.ui.browser.TabCoordinator
import com.onyx.browser.ui.home.rememberFavicon
import com.onyx.browser.ui.tabs.ClearBrowsingDataDialog
import com.onyx.browser.ui.theme.AccentColorManager
import com.onyx.browser.ui.theme.OnyxTheme
import com.onyx.browser.ui.viewmodel.HistoryViewModel
import com.onyx.browser.web.LocalFileLoader
import java.util.Calendar

class HistoryActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "com.onyx.browser.extra.URL"
    }

    private lateinit var viewModel: HistoryViewModel
    private lateinit var preferences: BrowserPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        viewModel = ViewModelProvider(this)[HistoryViewModel::class.java]
        preferences = BrowserPreferences.getInstance(this)

        setContent {
            val isDark = when (preferences.themeMode) {
                BrowserPreferences.THEME_DARK -> true
                BrowserPreferences.THEME_LIGHT -> false
                else -> isSystemInDarkTheme()
            }
            val isAmoled = preferences.themeMode == BrowserPreferences.THEME_DARK
            val accentColor = remember(preferences.accentColorHex, preferences.accentColorPreset) {
                androidx.compose.ui.graphics.Color(AccentColorManager.getAccentColor(preferences))
            }

            val rawHistory by viewModel.rawHistory.collectAsState()
            val searchQuery by viewModel.searchQuery.collectAsState()
            val collapsedDayKeys by viewModel.collapsedDayKeys.collectAsState()

            OnyxTheme(darkTheme = isDark, isAmoled = isAmoled, accentColor = accentColor) {
                HistoryScreen(
                    historyItems = rawHistory,
                    searchQuery = searchQuery,
                    collapsedDayKeys = collapsedDayKeys,
                    isInitialCollapseConfigured = viewModel.isInitialCollapseConfigured,
                    onConfigureInitialCollapse = { viewModel.configureInitialCollapsedDays(it) },
                    onToggleDayHeader = { viewModel.toggleDayHeader(it) },
                    onSearchQueryChange = { viewModel.setSearchQuery(it) },
                    onBackClick = { finish() },
                    onItemClick = { item ->
                        val resultIntent = Intent().apply { putExtra(EXTRA_URL, item.url) }
                        setResult(Activity.RESULT_OK, resultIntent)
                        finish()
                    },
                    onItemDelete = { item ->
                        viewModel.deleteHistoryItem(item)
                    },
                    onClearBrowsingDataClick = {
                        openClearBrowsingDataDialog()
                    }
                )
            }
        }
    }

    private fun openClearBrowsingDataDialog() {
        if (isFinishing || isDestroyed || supportFragmentManager.isStateSaved || supportFragmentManager.isDestroyed) {
            return
        }
        val tm = TabCoordinator.activeInstance
        val dialog = ClearBrowsingDataDialog(
            tabManager = tm,
            onDataCleared = {}
        )
        dialog.show(supportFragmentManager, ClearBrowsingDataDialog.TAG)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    historyItems: List<HistoryItem>,
    searchQuery: String,
    collapsedDayKeys: Set<String>,
    isInitialCollapseConfigured: Boolean,
    onConfigureInitialCollapse: (Set<String>) -> Unit,
    onToggleDayHeader: (String) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onBackClick: () -> Unit,
    onItemClick: (HistoryItem) -> Unit,
    onItemDelete: (HistoryItem) -> Unit,
    onClearBrowsingDataClick: () -> Unit
) {
    val context = LocalContext.current
    var isSearchActive by remember { mutableStateOf(false) }
    val isSearch = searchQuery.isNotBlank()

    // Group items into LinkedHashMap preserving reverse-chronological order
    val grouped = remember(historyItems) {
        val map = LinkedHashMap<String, MutableList<HistoryItem>>()
        for (item in historyItems) {
            val key = HistoryDateUtils.getDayKey(item.visitTime)
            map.getOrPut(key) { mutableListOf() }.add(item)
        }
        map
    }

    // Configure initial collapse: expand Today and Yesterday, collapse older
    LaunchedEffect(historyItems, isSearch) {
        if (!isInitialCollapseConfigured && historyItems.isNotEmpty() && !isSearch) {
            val nowCal = Calendar.getInstance()
            val todayKey = HistoryDateUtils.getDayKey(nowCal.timeInMillis)
            val yesterdayCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
            val yesterdayKey = HistoryDateUtils.getDayKey(yesterdayCal.timeInMillis)

            val initialKeys = mutableSetOf<String>()
            val groupedKeys = historyItems.map { HistoryDateUtils.getDayKey(it.visitTime) }.distinct()
            groupedKeys.forEachIndexed { index, key ->
                if (index > 0 && key != todayKey && key != yesterdayKey) {
                    initialKeys.add(key)
                }
            }
            onConfigureInitialCollapse(initialKeys)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isSearchActive) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = onSearchQueryChange,
                            placeholder = { Text(stringResource(R.string.search_history)) },
                            singleLine = true,
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { onSearchQueryChange("") }) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = stringResource(R.string.clear),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.history),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isSearchActive) {
                            isSearchActive = false
                            onSearchQueryChange("")
                        } else {
                            onBackClick()
                        }
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                actions = {
                    if (!isSearchActive) {
                        IconButton(onClick = { isSearchActive = true }) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = stringResource(R.string.search),
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                        IconButton(onClick = onClearBrowsingDataClick) {
                            Icon(
                                painter = painterResource(R.drawable.ic_delete),
                                contentDescription = stringResource(R.string.clear_browsing_data),
                                tint = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        if (historyItems.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = if (searchQuery.isBlank()) {
                            stringResource(R.string.no_history)
                        } else {
                            "No results found for '$searchQuery'"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(bottom = 32.dp)
            ) {
                grouped.forEach { (dayKey, items) ->
                    val title = HistoryDateUtils.formatDayGroupTitle(context, items.first().visitTime)
                    val isExpanded = if (isSearch) true else !collapsedDayKeys.contains(dayKey)

                    item(key = "group_$dayKey") {
                        HistoryDayCard(
                            title = title,
                            itemCount = items.size,
                            items = items,
                            isExpanded = isExpanded,
                            onToggleExpand = { onToggleDayHeader(dayKey) },
                            onItemClick = onItemClick,
                            onItemDelete = onItemDelete
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryDayCard(
    title: String,
    itemCount: Int,
    items: List<HistoryItem>,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onItemClick: (HistoryItem) -> Unit,
    onItemDelete: (HistoryItem) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(if (isExpanded) RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp) else RoundedCornerShape(16.dp))
                    .clickable(onClick = onToggleExpand)
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )

                // Count badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = itemCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Icon(
                    painter = painterResource(
                        if (isExpanded) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right
                    ),
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )

                    items.forEachIndexed { index, item ->
                        HistoryItemRow(
                            item = item,
                            onClick = { onItemClick(item) },
                            onDelete = { onItemDelete(item) }
                        )

                        if (index < items.size - 1) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 66.dp, end = 16.dp),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryItemRow(
    item: HistoryItem,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val isLocal = remember(item.url) {
        LocalFileLoader.isLocalFile(context, item.url) || LocalFileLoader.isPreviewUrl(item.url)
    }
    val favicon = if (!isLocal) rememberFavicon(item.url) else null

    val displayTitle = remember(item.title, item.url, isLocal) {
        if (isLocal) {
            item.title.ifBlank { "Local Document" }
        } else {
            item.title.ifBlank { item.url }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, top = 10.dp, end = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Circular Favicon / Website Logo Container
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (favicon != null) {
                Image(
                    bitmap = favicon,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                )
            } else if (isLocal) {
                Icon(
                    painter = painterResource(R.drawable.ic_file),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_web),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = displayTitle,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = item.url,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        // Delete Button
        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.delete),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
