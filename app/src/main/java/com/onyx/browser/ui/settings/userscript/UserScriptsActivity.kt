package com.onyx.browser.ui.settings.userscript

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.File
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import com.onyx.browser.ui.components.UpdateActionButton
import com.onyx.browser.ui.components.updateIntervalLabel
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.ui.components.AppAlertDialog
import com.onyx.browser.ui.components.CardContainer
import com.onyx.browser.ui.components.PreferenceItem
import com.onyx.browser.ui.components.AppTopBar
import com.onyx.browser.ui.home.rememberFavicon
import com.onyx.browser.ui.theme.OnyxTheme
import com.onyx.browser.userscript.ParsedUserScript
import com.onyx.browser.userscript.UpdateResult
import com.onyx.browser.userscript.UserScriptManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URL

class UserScriptsActivity : ComponentActivity() {

    private lateinit var manager: UserScriptManager
    private lateinit var preferences: BrowserPreferences
    private val httpClient = OkHttpClient()

    private val scripts = mutableStateListOf<ParsedUserScript>()
    private var isGlobalScriptBlocked by mutableStateOf(false)
    private var updateIntervalHours by mutableStateOf(12)
    private var scriptToDelete by mutableStateOf<ParsedUserScript?>(null)
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
        updateIntervalHours = if (preferences.isUserScriptAutoUpdateEnabled) preferences.userScriptAutoUpdateIntervalHours else 0

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
                    onToggleScript = { item, enabled ->
                        lifecycleScope.launch {
                            manager.toggleScript(item.script.id, enabled)
                            val idx = scripts.indexOfFirst { it.script.id == item.script.id }
                            if (idx >= 0) {
                                val current = scripts[idx]
                                scripts[idx] = current.copy(script = current.script.copy(enabled = enabled))
                            }
                        }
                    },
                    onEditScript = { item ->
                        val intent = Intent(this@UserScriptsActivity, UserScriptEditorActivity::class.java).apply {
                            putExtra(UserScriptEditorActivity.EXTRA_SCRIPT_ID, item.script.id)
                        }
                        startActivity(intent)
                    },
                    onDeleteScript = { item ->
                        scriptToDelete = item
                    },
                    onShareScript = { item ->
                        shareSingleScript(item)
                    },
                    onCheckScriptUpdate = { item ->
                        checkSingleScriptUpdate(item)
                    },
                    onCopyScriptName = { item ->
                        copyToClipboard("Script Name", item.metadata.name)
                    },
                    onCopyScriptLink = { item ->
                        copyToClipboard("Script Link", item.script.sourceUrl ?: "")
                    },
                    onAddClick = { showAddOptionsSheet = true },
                    onCheckUpdates = { checkForUpdates() },
                    updateIntervalHours = updateIntervalHours,
                    onSelectUpdateInterval = { applyUpdateInterval(it) }
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
                    DeleteUserScriptDialog(
                        scriptName = toDelete.metadata.name.ifBlank { toDelete.script.name },
                        scriptVersion = toDelete.metadata.version,
                        scriptAuthor = toDelete.metadata.author,
                        scriptMatches = toDelete.script.matchSummary,
                        onConfirm = {
                            lifecycleScope.launch {
                                manager.deleteScript(toDelete.script.id)
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
            val fresh = manager.scripts
            if (scripts.size == fresh.size && scripts.indices.all { scripts[it].script.id == fresh[it].script.id }) {
                for (i in fresh.indices) {
                    if (scripts[i] != fresh[i]) {
                        scripts[i] = fresh[i]
                    }
                }
            } else {
                scripts.clear()
                scripts.addAll(fresh)
            }
        }
    }

    private fun shareSingleScript(item: ParsedUserScript) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val code = item.code.ifBlank { manager.readCodeFile(item.script.id) }
                if (code.isBlank()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@UserScriptsActivity, "Script content is empty", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val rawName = item.metadata.name.ifBlank { item.script.name }.ifBlank { "script" }
                val sanitized = rawName.replace(Regex("""[\\/:*?"<>|\r\n\t]"""), "_").trim()
                val fileName = when {
                    sanitized.endsWith(".user.js", ignoreCase = true) -> sanitized
                    sanitized.endsWith(".js", ignoreCase = true) -> sanitized.removeSuffix(".js") + ".user.js"
                    else -> "$sanitized.user.js"
                }

                val shareDir = File(cacheDir, "shared_scripts").apply { mkdirs() }
                val shareFile = File(shareDir, fileName)
                shareFile.writeText(code)

                val fileUri: Uri = FileProvider.getUriForFile(
                    this@UserScriptsActivity,
                    "$packageName.fileprovider",
                    shareFile
                )

                withContext(Dispatchers.Main) {
                    val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension("js") ?: "application/javascript"
                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        type = mimeType
                        putExtra(Intent.EXTRA_STREAM, fileUri)
                        putExtra(Intent.EXTRA_SUBJECT, fileName)
                        clipData = ClipData.newRawUri(fileName, fileUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    val chooser = Intent.createChooser(sendIntent, "Share Userscript").apply {
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(chooser)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@UserScriptsActivity, "Failed to share script file: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun checkSingleScriptUpdate(item: ParsedUserScript) {
        lifecycleScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@UserScriptsActivity, "Checking for update...", Toast.LENGTH_SHORT).show()
            }
            try {
                val res = manager.checkAndUpdate(item.script.id)
                withContext(Dispatchers.Main) {
                    when (res) {
                        is UpdateResult.Updated -> {
                            Toast.makeText(this@UserScriptsActivity, "Updated to v${res.to}", Toast.LENGTH_SHORT).show()
                            loadScripts()
                        }
                        is UpdateResult.UpToDate -> {
                            Toast.makeText(this@UserScriptsActivity, "Script is up to date", Toast.LENGTH_SHORT).show()
                        }
                        is UpdateResult.NoSource -> {
                            Toast.makeText(this@UserScriptsActivity, "No update URL available", Toast.LENGTH_SHORT).show()
                        }
                        is UpdateResult.Failed -> {
                            Toast.makeText(this@UserScriptsActivity, "Update failed: ${res.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@UserScriptsActivity, "Check failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        if (text.isBlank()) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard != null) {
            val clip = ClipData.newPlainText(label, text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Copied $label to clipboard", Toast.LENGTH_SHORT).show()
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
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .build()
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
                val shareDir = File(cacheDir, "shared_scripts").apply { mkdirs() }
                val backupFile = File(shareDir, "userscripts_backup.json")
                backupFile.writeText(json)
                val fileUri: Uri = FileProvider.getUriForFile(
                    this@UserScriptsActivity,
                    "$packageName.fileprovider",
                    backupFile
                )
                withContext(Dispatchers.Main) {
                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(Intent.EXTRA_STREAM, fileUri)
                        putExtra(Intent.EXTRA_SUBJECT, "Userscripts Backup")
                        clipData = ClipData.newRawUri("userscripts_backup.json", fileUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    val chooser = Intent.createChooser(sendIntent, "Export Userscripts").apply {
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(chooser)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@UserScriptsActivity, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun applyUpdateInterval(hours: Int) {
        if (hours <= 0) {
            preferences.isUserScriptAutoUpdateEnabled = false
            updateIntervalHours = 0
            Toast.makeText(this, "Userscript auto-update disabled", Toast.LENGTH_SHORT).show()
        } else {
            preferences.userScriptAutoUpdateIntervalHours = hours
            preferences.isUserScriptAutoUpdateEnabled = true
            updateIntervalHours = hours
            Toast.makeText(this, "Auto-update: ${updateIntervalLabel(hours)}", Toast.LENGTH_SHORT).show()
        }
        com.onyx.browser.userscript.UserScriptUpdateWorker.schedule(this, forceReschedule = true)
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
    scripts: List<ParsedUserScript>,
    isGlobalScriptBlocked: Boolean,
    onBackClick: () -> Unit,
    onToggleScript: (ParsedUserScript, Boolean) -> Unit,
    onEditScript: (ParsedUserScript) -> Unit,
    onDeleteScript: (ParsedUserScript) -> Unit,
    onShareScript: (ParsedUserScript) -> Unit,
    onCheckScriptUpdate: (ParsedUserScript) -> Unit,
    onCopyScriptName: (ParsedUserScript) -> Unit,
    onCopyScriptLink: (ParsedUserScript) -> Unit,
    onAddClick: () -> Unit,
    onCheckUpdates: () -> Unit,
    updateIntervalHours: Int,
    onSelectUpdateInterval: (Int) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val filteredScripts = remember(scripts, searchQuery) {
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) scripts
        else scripts.filter {
            it.metadata.name.lowercase().contains(q) ||
                    it.metadata.author.lowercase().contains(q) ||
                    it.metadata.description.lowercase().contains(q) ||
                    it.metadata.matches.any { m -> m.lowercase().contains(q) } ||
                    it.metadata.includes.any { inc -> inc.lowercase().contains(q) }
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = "Userscripts",
                onBackClick = onBackClick,
                actions = {
                    if (scripts.isNotEmpty()) {
                        UpdateActionButton(
                            currentHours = updateIntervalHours,
                            contentDescription = "Check for updates (long-press for auto-update options)",
                            onClick = onCheckUpdates,
                            onSelectHours = onSelectUpdateInterval
                        )
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
                if (scripts.size >= 2 || searchQuery.isNotEmpty()) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        placeholder = { Text("Search by name, author, domain...") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Clear",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                }

                if (filteredScripts.isEmpty() && searchQuery.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No userscripts match '$searchQuery'",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = 80.dp)
                    ) {
                        items(filteredScripts, key = { it.script.id }) { item ->
                            CardContainer {
                                UserScriptItemRow(
                                    item = item,
                                    onToggle = { enabled -> onToggleScript(item, enabled) },
                                    onEdit = { onEditScript(item) },
                                    onCheckUpdate = { onCheckScriptUpdate(item) },
                                    onShare = { onShareScript(item) },
                                    onCopyName = { onCopyScriptName(item) },
                                    onCopyLink = { onCopyScriptLink(item) },
                                    onDelete = { onDeleteScript(item) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private val SCRIPT_BADGE_COLORS = listOf(
    Color(0xFF5B50E6), // Purple / Indigo (matches reference A.png)
    Color(0xFF2563EB), // Blue
    Color(0xFF059669), // Emerald
    Color(0xFFD97706), // Amber
    Color(0xFFDC2626), // Red
    Color(0xFF7C3AED), // Violet
    Color(0xFF0891B2), // Cyan
    Color(0xFFDB2777)  // Pink
)

private fun resolveUserScriptIconUrl(icon: String, sourceUrl: String?): String {
    val value = icon.trim()
    if (value.isEmpty()) return ""
    return when {
        value.startsWith("data:image/", ignoreCase = true) -> value
        value.startsWith("https://", ignoreCase = true) || value.startsWith("http://", ignoreCase = true) -> value
        value.startsWith("//") -> "https:$value"
        sourceUrl.isNullOrBlank() -> ""
        else -> runCatching { URL(URL(sourceUrl), value).toString() }.getOrDefault("")
    }
}

@Composable
private fun UserScriptItemRow(
    item: ParsedUserScript,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onCheckUpdate: () -> Unit,
    onShare: () -> Unit,
    onCopyName: () -> Unit,
    onCopyLink: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    val iconUrl = remember(item.metadata.icon, item.script.sourceUrl) {
        resolveUserScriptIconUrl(item.metadata.icon, item.script.sourceUrl)
    }
    val effectiveFaviconUrl = iconUrl.ifBlank { item.script.sourceUrl ?: "" }
    val favicon = rememberFavicon(effectiveFaviconUrl)

    val patternCount = item.metadata.matches.size + item.metadata.includes.size
    val subtitle = item.metadata.description.ifBlank {
        if (patternCount == 0) "Runs on all sites"
        else "Runs on $patternCount pattern(s)"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .alpha(if (item.script.enabled) 1f else 0.6f)
            .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left Icon Badge (Favicon or Deterministic Letter Badge)
        if (favicon != null) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = favicon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    contentScale = ContentScale.Fit
                )
            }
        } else {
            val initial = item.metadata.name.trim().firstOrNull()?.uppercaseChar()?.toString()
            if (!initial.isNullOrBlank() && initial.all { it.isLetterOrDigit() }) {
                val colorIndex = Math.abs(item.metadata.name.hashCode()) % SCRIPT_BADGE_COLORS.size
                val badgeColor = SCRIPT_BADGE_COLORS[colorIndex]
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(badgeColor),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = initial,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Code,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // Center Title & Subtitle Column
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp, end = 4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.metadata.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (item.metadata.version.isNotBlank()) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "v${item.metadata.version}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        // Switch with Checkmark Thumb
        Switch(
            checked = item.script.enabled,
            onCheckedChange = onToggle,
            thumbContent = if (item.script.enabled) {
                {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(SwitchDefaults.IconSize)
                    )
                }
            } else null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                checkedIconColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )

        // 3-dot More Options Menu
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Script options",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                shape = RoundedCornerShape(12.dp)
            ) {
                DropdownMenuItem(
                    text = { Text("Edit script") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onEdit()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Check for update") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onCheckUpdate()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Share script") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onShare()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Copy name") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onCopyName()
                    }
                )
                if (!item.script.sourceUrl.isNullOrBlank()) {
                    DropdownMenuItem(
                        text = { Text("Copy link") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Link,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onCopyLink()
                        }
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = {
                        Text(
                            text = "Delete",
                            color = MaterialTheme.colorScheme.error
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onDelete()
                    }
                )
            }
        }
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
