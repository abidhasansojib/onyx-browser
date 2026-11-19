package com.onyx.browser.ui.settings

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.onyx.browser.data.model.SearchEngine
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.ui.components.OnyxAlertDialog
import com.onyx.browser.ui.components.OnyxCardContainer
import com.onyx.browser.ui.components.OnyxTopAppBar
import com.onyx.browser.ui.theme.OnyxTheme
import com.onyx.browser.ui.widget.SearchWidgetProvider
import java.util.UUID

class SearchEngineSettingsActivity : ComponentActivity() {

    private lateinit var preferences: BrowserPreferences
    private var activeEngineId by mutableStateOf("")
    private val customEngines = mutableStateListOf<SearchEngine>()

    private var editingEngine by mutableStateOf<SearchEngine?>(null)
    private var isBottomSheetOpen by mutableStateOf(false)
    private var engineToDelete by mutableStateOf<SearchEngine?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        preferences = BrowserPreferences.getInstance(this)
        loadEngines()

        val isAmoled = preferences.themeMode == BrowserPreferences.THEME_DARK

        setContent {
            val isDark = when (preferences.themeMode) {
                BrowserPreferences.THEME_DARK -> true
                BrowserPreferences.THEME_LIGHT -> false
                else -> isSystemInDarkTheme()
            }

            OnyxTheme(darkTheme = isDark, isAmoled = isAmoled) {
                SearchEngineSettingsScreen(
                    onBackClick = { finish() },
                    activeEngineId = activeEngineId,
                    onEngineSelected = { engine ->
                        preferences.searchEngine = engine
                        activeEngineId = engine.id
                        SearchWidgetProvider.updateAllWidgets(this)
                        Toast.makeText(this, "Default search engine set to ${engine.displayName}", Toast.LENGTH_SHORT).show()
                    },
                    customEngines = customEngines,
                    onAddEngineClick = {
                        editingEngine = null
                        isBottomSheetOpen = true
                    },
                    onEditEngineClick = { engine ->
                        editingEngine = engine
                        isBottomSheetOpen = true
                    },
                    onDeleteEngineClick = { engine ->
                        engineToDelete = engine
                    }
                )

                if (isBottomSheetOpen) {
                    AddEditSearchEngineSheet(
                        existingEngine = editingEngine,
                        onDismiss = { isBottomSheetOpen = false },
                        onSave = { newEngine ->
                            if (editingEngine != null) {
                                preferences.updateCustomSearchEngine(newEngine)
                                if (activeEngineId == newEngine.id) {
                                    preferences.searchEngine = newEngine
                                }
                            } else {
                                preferences.addCustomSearchEngine(newEngine)
                            }
                            loadEngines()
                            isBottomSheetOpen = false
                        }
                    )
                }

                if (engineToDelete != null) {
                    val toDelete = engineToDelete!!
                    OnyxAlertDialog(
                        title = "Delete search engine?",
                        text = {
                            Text("Are you sure you want to delete '${toDelete.displayName}'?")
                        },
                        confirmButtonText = "Delete",
                        isDestructive = true,
                        onConfirm = {
                            preferences.deleteCustomSearchEngine(toDelete.id)
                            loadEngines()
                            engineToDelete = null
                        },
                        onDismissRequest = { engineToDelete = null }
                    )
                }
            }
        }
    }

    private fun loadEngines() {
        activeEngineId = preferences.searchEngine.id
        customEngines.clear()
        customEngines.addAll(preferences.getCustomSearchEngines())
    }
}

@Composable
fun SearchEngineSettingsScreen(
    onBackClick: () -> Unit,
    activeEngineId: String,
    onEngineSelected: (SearchEngine) -> Unit,
    customEngines: List<SearchEngine>,
    onAddEngineClick: () -> Unit,
    onEditEngineClick: (SearchEngine) -> Unit,
    onDeleteEngineClick: (SearchEngine) -> Unit
) {
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            OnyxTopAppBar(
                title = "Search Engines",
                onBackClick = onBackClick,
                actions = {
                    IconButton(onClick = onAddEngineClick) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add Search Engine",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(bottom = 32.dp)
        ) {
            CategoryHeader("Standard Search Engines")
            OnyxCardContainer {
                SearchEngine.BUILT_IN.forEach { engine ->
                    SearchEngineRow(
                        engine = engine,
                        isSelected = engine.id == activeEngineId,
                        onClick = { onEngineSelected(engine) }
                    )
                }
            }

            CategoryHeader("Custom Search Engines")
            OnyxCardContainer {
                if (customEngines.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No custom search engines added yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    customEngines.forEach { engine ->
                        SearchEngineRow(
                            engine = engine,
                            isSelected = engine.id == activeEngineId,
                            onClick = { onEngineSelected(engine) },
                            onEdit = { onEditEngineClick(engine) },
                            onDelete = { onDeleteEngineClick(engine) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchEngineRow(
    engine: SearchEngine,
    isSelected: Boolean,
    onClick: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = isSelected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(
                selectedColor = MaterialTheme.colorScheme.primary,
                unselectedColor = MaterialTheme.colorScheme.outline
            )
        )

        Spacer(modifier = Modifier.width(12.dp))

        // Avatar Initial
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            val initial = engine.displayName.trim().firstOrNull()?.uppercase() ?: "S"
            Text(
                text = initial,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(
            modifier = Modifier.weight(1f)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = engine.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (engine.keyword.isNotBlank()) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = ":${engine.keyword}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Text(
                text = engine.homeUrl.ifBlank { engine.queryUrl },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }

        if (onEdit != null) {
            IconButton(onClick = onEdit) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Edit",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        if (onDelete != null) {
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddEditSearchEngineSheet(
    existingEngine: SearchEngine?,
    onDismiss: () -> Unit,
    onSave: (SearchEngine) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var name by remember { mutableStateOf(existingEngine?.displayName ?: "") }
    var keyword by remember { mutableStateOf(existingEngine?.keyword ?: "") }
    var queryUrl by remember { mutableStateOf(existingEngine?.queryUrl ?: "") }

    val suggestions = listOf(
        Triple("Ecosia", "eco", "https://www.ecosia.org/search?q=%s"),
        Triple("Kagi", "k", "https://kagi.com/search?q=%s"),
        Triple("Perplexity", "px", "https://www.perplexity.ai/search?q=%s"),
        Triple("Startpage", "sp", "https://www.startpage.com/do/dsearch?query=%s"),
        Triple("Searx", "sx", "https://searx.be/search?q=%s"),
        Triple("Qwant", "qw", "https://www.qwant.com/?q=%s")
    )

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
                text = if (existingEngine != null) "Edit Search Engine" else "Add Custom Search Engine",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            if (existingEngine == null) {
                Text(
                    text = "Quick Suggestions",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 6.dp)
                )

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    items(suggestions) { (sName, sKey, sUrl) ->
                        AssistChip(
                            onClick = {
                                name = sName
                                keyword = sKey
                                queryUrl = sUrl
                            },
                            label = { Text(sName) }
                        )
                    }
                }
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Engine Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = keyword,
                onValueChange = { keyword = it },
                label = { Text("Shortcut Keyword (e.g. g, d, k)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = queryUrl,
                onValueChange = { queryUrl = it },
                label = { Text("Search URL with %s (e.g. https://example.com?q=%s)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = {
                        if (name.isNotBlank() && queryUrl.isNotBlank()) {
                            val id = existingEngine?.id ?: UUID.randomUUID().toString()
                            onSave(
                                SearchEngine(
                                    id = id,
                                    displayName = name.trim(),
                                    queryUrl = queryUrl.trim(),
                                    homeUrl = "",
                                    keyword = keyword.trim(),
                                    isCustom = true
                                )
                            )
                        }
                    },
                    enabled = name.isNotBlank() && queryUrl.isNotBlank()
                ) {
                    Text(if (existingEngine != null) "Save" else "Add")
                }
            }
        }
    }
}

@Composable
private fun CategoryHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 6.dp)
    )
}
