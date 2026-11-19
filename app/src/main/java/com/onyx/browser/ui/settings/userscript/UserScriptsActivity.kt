package com.onyx.browser.ui.settings.userscript

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.onyx.browser.data.model.UserScriptItem
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.ui.components.AppAlertDialog
import com.onyx.browser.ui.components.CardContainer
import com.onyx.browser.ui.components.PreferenceItem
import com.onyx.browser.ui.components.AppTopBar
import com.onyx.browser.ui.theme.OnyxTheme
import com.onyx.browser.userscript.UserScriptManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class UserScriptsActivity : ComponentActivity() {

    private lateinit var manager: UserScriptManager
    private lateinit var preferences: BrowserPreferences
    private val httpClient = OkHttpClient()

    private val scripts = mutableStateListOf<UserScriptItem>()
    private var isGlobalScriptBlocked by mutableStateOf(false)
    private var scriptToDelete by mutableStateOf<UserScriptItem?>(null)
    private var showAddOptionsSheet by mutableStateOf(false)
    private var showImportUrlSheet by mutableStateOf(false)

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            importFromFileUri(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        manager = UserScriptManager.getInstance(this)
        preferences = BrowserPreferences.getInstance(this)
        isGlobalScriptBlocked = preferences.isGlobalScriptBlockingEnabled

        loadScripts()

        setContent {
            val isDark = when (preferences.themeMode) {
                BrowserPreferences.THEME_DARK -> true
                BrowserPreferences.THEME_LIGHT -> false
                else -> isSystemInDarkTheme()
            }

            OnyxTheme(darkTheme = isDark, isAmoled = false) {
                UserScriptsScreen(
                    scripts = scripts,
                    isGlobalScriptBlocked = isGlobalScriptBlocked,
                    onBackClick = { finish() },
                    onToggleScript = { script, enabled ->
                        lifecycleScope.launch {
                            manager.toggleScript(script.id, enabled)
                            loadScripts()
                        }
                    },
                    onEditScript = { script ->
                        val intent = Intent(this@UserScriptsActivity, UserScriptEditorActivity::class.java).apply {
                            putExtra(UserScriptEditorActivity.EXTRA_SCRIPT_ID, script.id)
                        }
                        startActivity(intent)
                    },
                    onDeleteScript = { script ->
                        scriptToDelete = script
                    },
                    onAddClick = { showAddOptionsSheet = true },
                    onCheckUpdates = { checkForUpdates() },
                    onExportScripts = { exportAllScripts() }
                )

                if (showAddOptionsSheet) {
                    AddScriptOptionsSheet(
                        onDismiss = { showAddOptionsSheet = false },
                        onCreateNew = {
                            showAddOptionsSheet = false
                            startActivity(Intent(this@UserScriptsActivity, UserScriptEditorActivity::class.java))
                        },
                        onImportFile = {
                            showAddOptionsSheet = false
                            filePickerLauncher.launch("*/*")
                        },
                        onImportUrl = {
                            showAddOptionsSheet = false
                            showImportUrlSheet = true
                        }
                    )
                }

                if (showImportUrlSheet) {
                    ImportUrlSheet(
                        onDismiss = { showImportUrlSheet = false },
                        onImport = { url ->
                            showImportUrlSheet = false
                            fetchAndInstallScriptFromUrl(url)
                        }
                    )
                }

                if (scriptToDelete != null) {
                    val toDelete = scriptToDelete!!
                    AppAlertDialog(
                        title = "Delete userscript?",
                        text = { Text("Are you sure you want to delete '${toDelete.name}'?") },
                        confirmButtonText = "Delete",
                        isDestructive = true,
                        onConfirm = {
                            lifecycleScope.launch {
                                manager.deleteScript(toDelete.id)
                                loadScripts()
                                scriptToDelete = null
                            }
                        },
                        onDismissRequest = { scriptToDelete = null }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isGlobalScriptBlocked = preferences.isGlobalScriptBlockingEnabled
        loadScripts()
    }

    private fun loadScripts() {
        lifecycleScope.launch {
            manager.reload()
            val list = manager.scripts.map { it.script }
            scripts.clear()
            scripts.addAll(list)
        }
    }

    private fun importFromFileUri(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val content = contentResolver.openInputStream(uri)?.use { stream ->
                    stream.bufferedReader().readText()
                }
                if (!content.isNullOrBlank()) {
                    val id = manager.addScript(content)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@UserScriptsActivity, "Installed userscript", Toast.LENGTH_SHORT).show()
                        loadScripts()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@UserScriptsActivity, "Failed to import script: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun fetchAndInstallScriptFromUrl(url: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val req = Request.Builder().url(url).build()
                val resp = httpClient.newCall(req).execute()
                val body = resp.body?.string()
                if (resp.isSuccessful && !body.isNullOrBlank()) {
                    val id = manager.addScript(body, sourceUrl = url)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@UserScriptsActivity, "Installed userscript", Toast.LENGTH_SHORT).show()
                        loadScripts()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@UserScriptsActivity, "Download failed (HTTP ${resp.code})", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@UserScriptsActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun exportAllScripts() {
        lifecycleScope.launch(Dispatchers.IO) {
            val all = manager.scripts
            if (all.isEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@UserScriptsActivity, "No userscripts to export", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            try {
                val array = org.json.JSONArray()
                for (parsed in all) {
                    val obj = org.json.JSONObject().apply {
                        put("id", parsed.script.id)
                        put("name", parsed.metadata.name)
                        put("version", parsed.metadata.version)
                        put("sourceUrl", parsed.script.sourceUrl)
                        put("code", parsed.code)
                        val keys = manager.gmListValues(parsed.script.id)
                        val valuesObj = org.json.JSONObject()
                        for (k in keys) {
                            val v = manager.gmGetValue(parsed.script.id, k)
                            valuesObj.put(k, v)
                        }
                        put("values", valuesObj)
                    }
                    array.put(obj)
                }
                val json = array.toString(2)
                withContext(Dispatchers.Main) {
                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(Intent.EXTRA_TEXT, json)
                        putExtra(Intent.EXTRA_SUBJECT, "Onyx Userscripts Backup")
                    }
                    startActivity(Intent.createChooser(sendIntent, "Export Userscripts"))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@UserScriptsActivity, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun checkForUpdates() {
        lifecycleScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@UserScriptsActivity, "Checking for userscript updates...", Toast.LENGTH_SHORT).show()
            }
            try {
                val results = manager.checkAndUpdateAll()
                val updated = results.count { it.second is com.onyx.browser.userscript.UpdateResult.Updated }
                withContext(Dispatchers.Main) {
                    if (updated > 0) {
                        Toast.makeText(this@UserScriptsActivity, "Updated $updated script(s)", Toast.LENGTH_SHORT).show()
                        loadScripts()
                    } else {
                        Toast.makeText(this@UserScriptsActivity, "All scripts are up to date", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@UserScriptsActivity, "Check failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

@Composable
fun UserScriptsScreen(
    scripts: List<UserScriptItem>,
    isGlobalScriptBlocked: Boolean,
    onBackClick: () -> Unit,
    onToggleScript: (UserScriptItem, Boolean) -> Unit,
    onEditScript: (UserScriptItem) -> Unit,
    onDeleteScript: (UserScriptItem) -> Unit,
    onAddClick: () -> Unit,
    onCheckUpdates: () -> Unit,
    onExportScripts: () -> Unit
) {
    Scaffold(
        topBar = {
            AppTopBar(
                title = "User Scripts",
                onBackClick = onBackClick,
                actions = {
                    if (scripts.isNotEmpty()) {
                        IconButton(onClick = onCheckUpdates) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Check for updates",
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                        IconButton(onClick = onExportScripts) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Export Scripts",
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddClick,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = "Add Script")
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (isGlobalScriptBlocked) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f))
                        .padding(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "JavaScript is blocked globally in Shields. Userscripts will not execute on websites.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            if (scripts.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Code,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No userscripts installed yet",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 80.dp)
                ) {
                    items(scripts, key = { it.id }) { script ->
                        CardContainer {
                            UserScriptItemRow(
                                script = script,
                                onToggle = { enabled -> onToggleScript(script, enabled) },
                                onEdit = { onEditScript(script) },
                                onDelete = { onDeleteScript(script) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UserScriptItemRow(
    script: UserScriptItem,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = script.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (script.version.isNotBlank()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "v${script.version}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (script.description.isNotBlank()) {
                Text(
                    text = script.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                    maxLines = 2
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        IconButton(onClick = onEdit) {
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = "Edit",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }

        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Delete",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp)
            )
        }

        Switch(
            checked = script.enabled,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddScriptOptionsSheet(
    onDismiss: () -> Unit,
    onCreateNew: () -> Unit,
    onImportFile: () -> Unit,
    onImportUrl: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = "Add Userscript",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            PreferenceItem(
                title = "Create New Script",
                subtitle = "Write a Tampermonkey script in the code editor",
                icon = Icons.Default.Code,
                onClick = onCreateNew
            )

            PreferenceItem(
                title = "Import from File (.user.js)",
                subtitle = "Load a script file from device storage",
                icon = Icons.Default.FileOpen,
                onClick = onImportFile
            )

            PreferenceItem(
                title = "Import from URL",
                subtitle = "Download script from GreasyFork, GitHub, or direct link",
                icon = Icons.Default.Language,
                onClick = onImportUrl
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportUrlSheet(
    onDismiss: () -> Unit,
    onImport: (String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var url by remember { mutableStateOf("") }

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
                text = "Import Script from URL",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Script URL (e.g. https://.../script.user.js)") },
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
                            onImport(url.trim())
                        }
                    },
                    enabled = url.isNotBlank()
                ) {
                    Text("Import")
                }
            }
        }
    }
}
