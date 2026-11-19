package com.onyx.browser.ui.settings.shields

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.onyx.browser.R
import com.onyx.browser.data.filter.CustomFilterItem
import com.onyx.browser.data.filter.FilterListEntry
import com.onyx.browser.data.filter.FilterListManager
import com.onyx.browser.data.filter.FilterUpdateWorker
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.ui.components.AppAlertDialog
import com.onyx.browser.ui.components.CardContainer
import com.onyx.browser.ui.components.RadioPreference
import com.onyx.browser.ui.components.AppTopBar
import com.onyx.browser.ui.theme.OnyxTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

data class FilterListUiItem(
    val entry: FilterListEntry,
    val isEnabled: Boolean
)

class ContentFiltersActivity : ComponentActivity() {

    private lateinit var preferences: BrowserPreferences
    private val allLists = mutableStateListOf<FilterListUiItem>()
    private var searchQuery by mutableStateOf("")
    private var isUpdating by mutableStateOf(false)
    private var updateStatusText by mutableStateOf("")
    private var subtitleText by mutableStateOf("")

    private var showIntervalDialog by mutableStateOf(false)
    private var showAddUrlSheet by mutableStateOf(false)
    private var showCreateCustomSheet by mutableStateOf(false)
    private var entryToDelete by mutableStateOf<FilterListUiItem?>(null)

    private var recompileJob: Job? = null
    private var hasPendingRecompile = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        preferences = BrowserPreferences.getInstance(this)
        preferences.isFilterAutoUpdateEnabled = true

        reloadLists()

        val isAmoled = preferences.themeMode == BrowserPreferences.THEME_DARK

        setContent {
            val isDark = when (preferences.themeMode) {
                BrowserPreferences.THEME_DARK -> true
                BrowserPreferences.THEME_LIGHT -> false
                else -> isSystemInDarkTheme()
            }

            OnyxTheme(darkTheme = isDark, isAmoled = isAmoled) {
                val filtered = if (searchQuery.isBlank()) {
                    allLists
                } else {
                    allLists.filter {
                        it.entry.title.contains(searchQuery, ignoreCase = true) ||
                        it.entry.subtitle.contains(searchQuery, ignoreCase = true)
                    }
                }

                ContentFiltersScreen(
                    onBackClick = { finish() },
                    subtitle = subtitleText,
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    isUpdating = isUpdating,
                    updateStatus = updateStatusText,
                    onUpdateClick = { performUpdateFilters() },
                    onIntervalClick = { showIntervalDialog = true },
                    onAddViaUrlClick = { showAddUrlSheet = true },
                    onCreateCustomClick = { showCreateCustomSheet = true },
                    filterLists = filtered,
                    onToggleFilter = { item, enabled ->
                        FilterListManager.setListEnabled(this@ContentFiltersActivity, item.entry.id, enabled)
                        val idx = allLists.indexOfFirst { it.entry.id == item.entry.id }
                        if (idx >= 0) {
                            allLists[idx] = allLists[idx].copy(isEnabled = enabled)
                        }
                        scheduleRecompile()
                    },
                    onDeleteFilter = { item ->
                        entryToDelete = item
                    }
                )

                if (showIntervalDialog) {
                    UpdateIntervalDialog(
                        currentHours = if (preferences.isFilterAutoUpdateEnabled) preferences.filterAutoUpdateIntervalHours else 0,
                        onSelectHours = { hours ->
                            if (hours == 0) {
                                preferences.filterAutoUpdateIntervalHours = 0
                                preferences.isFilterAutoUpdateEnabled = false
                            } else {
                                preferences.filterAutoUpdateIntervalHours = hours
                                preferences.isFilterAutoUpdateEnabled = true
                            }
                            FilterUpdateWorker.schedule(this@ContentFiltersActivity, forceReschedule = true)
                            updateSubtitleDisplay()
                            showIntervalDialog = false
                        },
                        onDismiss = { showIntervalDialog = false }
                    )
                }

                if (showAddUrlSheet) {
                    AddFilterUrlSheet(
                        onDismiss = { showAddUrlSheet = false },
                        onAdd = { url, name ->
                            val newItem = CustomFilterItem(
                                id = "custom_${UUID.randomUUID()}",
                                name = name,
                                url = url,
                                isUrlType = true,
                                isEnabled = true
                            )
                            preferences.addCustomFilter(newItem)
                            reloadLists()
                            scheduleRecompile()
                            showAddUrlSheet = false
                        }
                    )
                }

                if (showCreateCustomSheet) {
                    CreateCustomRuleSheet(
                        onDismiss = { showCreateCustomSheet = false },
                        onSave = { ruleText, ruleName ->
                            val newItem = CustomFilterItem(
                                id = "custom_${UUID.randomUUID()}",
                                name = ruleName,
                                rules = ruleText,
                                isUrlType = false,
                                isEnabled = true
                            )
                            preferences.addCustomFilter(newItem)
                            reloadLists()
                            scheduleRecompile()
                            showCreateCustomSheet = false
                        }
                    )
                }

                if (entryToDelete != null) {
                    val item = entryToDelete!!
                    AppAlertDialog(
                        title = "Remove filter list?",
                        text = { Text("Are you sure you want to remove '${item.entry.title}'?") },
                        confirmButtonText = "Remove",
                        isDestructive = true,
                        onConfirm = {
                            preferences.deleteCustomFilter(item.entry.id)
                            reloadLists()
                            scheduleRecompile()
                            entryToDelete = null
                        },
                        onDismissRequest = { entryToDelete = null }
                    )
                }
            }
        }
    }

    private fun reloadLists() {
        allLists.clear()
        val lists = FilterListManager.getAllLists(this)
        allLists.addAll(lists.map { FilterListUiItem(it, FilterListManager.isListEnabled(this, it.id)) })
        updateSubtitleDisplay()
    }

    private fun updateSubtitleDisplay() {
        val hours = preferences.filterAutoUpdateIntervalHours
        val isEnabled = preferences.isFilterAutoUpdateEnabled && hours > 0
        val intervalLabel = when {
            !isEnabled -> "Disabled"
            hours == 6 -> "Every 6h"
            hours == 12 -> "Every 12h"
            hours == 24 -> "Every 24h"
            hours == 72 -> "Every 3 days"
            hours == 168 -> "Every 7 days"
            else -> "Every ${hours}h"
        }
        val lastUpdated = FilterListManager.getLastUpdatedFormatted(this)
        subtitleText = "Auto-update: $intervalLabel • Updated: $lastUpdated"
    }

    private fun performUpdateFilters() {
        if (isUpdating) return
        isUpdating = true
        updateStatusText = getString(R.string.updating_filters)

        lifecycleScope.launch {
            val result = FilterListManager.updateAllFilters(this@ContentFiltersActivity) { current, total, name ->
                updateStatusText = "Updating $current of $total: $name"
            }

            isUpdating = false
            updateStatusText = ""
            updateSubtitleDisplay()
            reloadLists()

            if (result.isSuccess) {
                val rulesCount = result.getOrNull() ?: 0
                Toast.makeText(this@ContentFiltersActivity, getString(R.string.filters_updated, rulesCount), Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this@ContentFiltersActivity, getString(R.string.filters_update_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun scheduleRecompile() {
        recompileJob?.cancel()
        hasPendingRecompile = true
        recompileJob = lifecycleScope.launch {
            delay(500)
            hasPendingRecompile = false
            isUpdating = true
            updateStatusText = "Recompiling filters..."
            val rulesCount = FilterListManager.recompileFilters(this@ContentFiltersActivity) { current, total, name ->
                updateStatusText = "Downloading $current of $total: $name"
            }
            isUpdating = false
            updateStatusText = ""
            updateSubtitleDisplay()
            reloadLists()
            if (rulesCount > 0) {
                Toast.makeText(this@ContentFiltersActivity, getString(R.string.filters_updated, rulesCount), Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (hasPendingRecompile) {
            hasPendingRecompile = false
            CoroutineScope(Dispatchers.IO + NonCancellable).launch {
                FilterListManager.recompileFilters(applicationContext)
            }
        }
    }
}

@Composable
fun ContentFiltersScreen(
    onBackClick: () -> Unit,
    subtitle: String,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    isUpdating: Boolean,
    updateStatus: String,
    onUpdateClick: () -> Unit,
    onIntervalClick: () -> Unit,
    onAddViaUrlClick: () -> Unit,
    onCreateCustomClick: () -> Unit,
    filterLists: List<FilterListUiItem>,
    onToggleFilter: (FilterListUiItem, Boolean) -> Unit,
    onDeleteFilter: (FilterListUiItem) -> Unit
) {
    Scaffold(
        topBar = {
            AppTopBar(
                title = "Content Filters",
                onBackClick = onBackClick,
                actions = {
                    if (isUpdating) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(24.dp)
                                .padding(end = 8.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        IconButton(onClick = onUpdateClick) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Update Filters",
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(bottom = 32.dp)
        ) {
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clickable(onClick = onIntervalClick)
                            .padding(vertical = 4.dp)
                    )

                    if (isUpdating && updateStatus.isNotBlank()) {
                        Text(
                            text = updateStatus,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        placeholder = { Text("Search filter lists...") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onAddViaUrlClick,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AddLink,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Add via URL", maxLines = 1)
                        }

                        OutlinedButton(
                            onClick = onCreateCustomClick,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Custom Rule", maxLines = 1)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            items(filterLists, key = { it.entry.id }) { item ->
                FilterListCardItem(
                    item = item,
                    onToggle = { enabled -> onToggleFilter(item, enabled) },
                    onDelete = if (item.entry.isCustom) { { onDeleteFilter(item) } } else null
                )
            }
        }
    }
}

@Composable
private fun FilterListCardItem(
    item: FilterListUiItem,
    onToggle: (Boolean) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    val entry = item.entry
    CardContainer {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (entry.subtitle.isNotBlank()) {
                    Text(
                        text = entry.subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (onDelete != null) {
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Switch(
                checked = item.isEnabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    }
}

@Composable
private fun UpdateIntervalDialog(
    currentHours: Int,
    onSelectHours: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val intervals = listOf(
        0 to "Manual updates only (Disabled)",
        6 to "Every 6 hours",
        12 to "Every 12 hours",
        24 to "Every 1 day (24 hours)",
        72 to "Every 3 days",
        168 to "Every 7 days (1 week)"
    )

    AppAlertDialog(
        title = "Auto-Update Interval",
        confirmButtonText = "Close",
        dismissButtonText = null,
        onConfirm = onDismiss,
        onDismissRequest = onDismiss,
        text = {
            Column {
                intervals.forEach { (hours, label) ->
                    RadioPreference(
                        title = label,
                        selected = hours == currentHours,
                        onClick = { onSelectHours(hours) }
                    )
                }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddFilterUrlSheet(
    onDismiss: () -> Unit,
    onAdd: (url: String, name: String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = "Add Filter via URL",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Filter List Name (Optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Filter List URL (e.g. https://.../rules.txt)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (url.isNotBlank()) {
                            onAdd(url.trim(), name.trim())
                        }
                    },
                    enabled = url.isNotBlank()
                ) {
                    Text("Add")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateCustomRuleSheet(
    onDismiss: () -> Unit,
    onSave: (ruleText: String, ruleName: String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember { mutableStateOf("") }
    var ruleText by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = "Create Custom Rule",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Rule Name (e.g. My Custom AdBlock)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = ruleText,
                onValueChange = { ruleText = it },
                label = { Text("Filter Rule syntax (e.g. ||ads.example.com^)") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (ruleText.isNotBlank()) {
                            onSave(ruleText.trim(), name.trim())
                        }
                    },
                    enabled = ruleText.isNotBlank()
                ) {
                    Text("Save Rule")
                }
            }
        }
    }
}
