package com.onyx.browser.ui.downloads

import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.Formatter
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.onyx.browser.MainActivity
import com.onyx.browser.data.local.AppDatabase
import com.onyx.browser.data.model.DownloadItem
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.download.FileUtils
import com.onyx.browser.ui.components.AppAlertDialog
import com.onyx.browser.ui.components.CardContainer
import com.onyx.browser.ui.theme.OnyxTheme
import com.onyx.browser.ui.viewmodel.DownloadsViewModel
import com.onyx.browser.web.LocalFileLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class DownloadsActivity : ComponentActivity() {

    companion object {
        const val EXTRA_INSTALL_APK_PATH = "com.onyx.browser.extra.INSTALL_APK_PATH"
    }

    private lateinit var viewModel: DownloadsViewModel
    private lateinit var preferences: BrowserPreferences
    private lateinit var database: AppDatabase

    private var itemToDelete by mutableStateOf<DownloadItem?>(null)
    private var itemDetailsToShow by mutableStateOf<DownloadItem?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        viewModel = ViewModelProvider(this)[DownloadsViewModel::class.java]
        preferences = BrowserPreferences.getInstance(this)
        database = AppDatabase.getInstance(this)

        setContent {
            val isDark = when (preferences.themeMode) {
                BrowserPreferences.THEME_DARK -> true
                BrowserPreferences.THEME_LIGHT -> false
                else -> isSystemInDarkTheme()
            }

            val allDownloads by viewModel.allDownloads.collectAsState()
            val filteredDownloads by viewModel.filteredDownloads.collectAsState()
            val categoryFilter by viewModel.categoryFilter.collectAsState()
            val searchQuery by viewModel.searchQuery.collectAsState()

            OnyxTheme(darkTheme = isDark, isAmoled = false) {
                DownloadsScreen(
                    downloads = filteredDownloads,
                    allDownloads = allDownloads,
                    categoryFilter = categoryFilter,
                    onCategorySelect = { viewModel.setCategoryFilter(it) },
                    searchQuery = searchQuery,
                    onSearchQueryChange = { viewModel.setSearchQuery(it) },
                    onBackClick = { finish() },
                    onOpenSystemDownloads = { openSystemDownloadsFolder() },
                    onItemClick = { item -> openFile(item) },
                    onItemShare = { item -> shareFile(item) },
                    onItemCopyUrl = { item -> copyUrl(item.url) },
                    onItemDetails = { item -> itemDetailsToShow = item },
                    onItemDelete = { item -> itemToDelete = item }
                )

                if (itemToDelete != null) {
                    val toDelete = itemToDelete!!
                    AppAlertDialog(
                        title = "Delete download?",
                        text = { Text("Are you sure you want to delete '${toDelete.fileName}'? This will remove the file from storage and history.") },
                        confirmButtonText = "Delete",
                        isDestructive = true,
                        onConfirm = {
                            deleteDownload(toDelete)
                            itemToDelete = null
                        },
                        onDismissRequest = { itemToDelete = null }
                    )
                }

                if (itemDetailsToShow != null) {
                    val details = itemDetailsToShow!!
                    val sizeFormatted = Formatter.formatFileSize(this, details.fileSize)
                    AppAlertDialog(
                        title = "File Details",
                        text = {
                            Column {
                                Text("Name: ${details.fileName}", fontWeight = FontWeight.SemiBold)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("Size: $sizeFormatted")
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("Path: ${details.filePath}")
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("URL: ${details.url}")
                            }
                        },
                        confirmButtonText = "Close",
                        dismissButtonText = null,
                        onConfirm = { itemDetailsToShow = null },
                        onDismissRequest = { itemDetailsToShow = null }
                    )
                }
            }
        }
    }

    private fun openSystemDownloadsFolder() {
        try {
            val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "Could not open system downloads", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openFile(item: DownloadItem) {
        val resolvedPath = FileUtils.resolveExistingPath(item.filePath, this)
        if (resolvedPath == null) {
            Toast.makeText(this, "File not found or deleted", Toast.LENGTH_SHORT).show()
            return
        }

        if (ApkInstallerHelper.isApkFile(item.fileName, item.mimeType)) {
            ApkInstallerHelper.installApk(this, resolvedPath, item.fileName)
            return
        }

        if (LocalFileLoader.isWebDocument(item.fileName, item.mimeType)) {
            val intent = Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.fromFile(File(resolvedPath))
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
            finish()
            return
        }

        try {
            val file = File(resolvedPath)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, item.mimeType.ifBlank { "*/*" })
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "No app available to open this file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareFile(item: DownloadItem) {
        val resolvedPath = FileUtils.resolveExistingPath(item.filePath, this) ?: return
        try {
            val file = File(resolvedPath)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = item.mimeType.ifBlank { "*/*" }
                putExtra(Intent.EXTRA_STREAM, uri)
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            startActivity(Intent.createChooser(intent, "Share ${item.fileName}"))
        } catch (_: Exception) {
            Toast.makeText(this, "Could not share file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyUrl(url: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("URL", url))
        Toast.makeText(this, "Copied URL to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun deleteDownload(item: DownloadItem) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val file = File(item.filePath)
                if (file.exists()) {
                    file.delete()
                }
                database.downloadDao().deleteById(item.id)
            } catch (_: Exception) {}
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    downloads: List<DownloadItem>,
    allDownloads: List<DownloadItem>,
    categoryFilter: Int,
    onCategorySelect: (Int) -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onBackClick: () -> Unit,
    onOpenSystemDownloads: () -> Unit,
    onItemClick: (DownloadItem) -> Unit,
    onItemShare: (DownloadItem) -> Unit,
    onItemCopyUrl: (DownloadItem) -> Unit,
    onItemDetails: (DownloadItem) -> Unit,
    onItemDelete: (DownloadItem) -> Unit
) {
    var isSearchActive by remember { mutableStateOf(false) }

    val totalCount = allDownloads.size
    val apkCount = allDownloads.count { ApkInstallerHelper.isApkFile(it.fileName, it.mimeType) }
    val imgCount = allDownloads.count { DownloadsViewModel.isImageFile(it.fileName, it.mimeType) }
    val vidCount = allDownloads.count { DownloadsViewModel.isVideoFile(it.fileName, it.mimeType) }
    val docCount = allDownloads.count { DownloadsViewModel.isDocumentFile(it.fileName, it.mimeType) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isSearchActive) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = onSearchQueryChange,
                            placeholder = { Text("Search downloads...") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text(
                            text = "Downloads",
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
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                actions = {
                    if (!isSearchActive) {
                        IconButton(onClick = { isSearchActive = true }) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search",
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }
                    IconButton(onClick = onOpenSystemDownloads) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = "System Downloads",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Category Chips Row
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(
                        selected = categoryFilter == DownloadsViewModel.FILTER_ALL,
                        onClick = { onCategorySelect(DownloadsViewModel.FILTER_ALL) },
                        label = { Text("All ($totalCount)") }
                    )
                }
                item {
                    FilterChip(
                        selected = categoryFilter == DownloadsViewModel.FILTER_APKS,
                        onClick = { onCategorySelect(DownloadsViewModel.FILTER_APKS) },
                        label = { Text("APKs ($apkCount)") }
                    )
                }
                item {
                    FilterChip(
                        selected = categoryFilter == DownloadsViewModel.FILTER_IMAGES,
                        onClick = { onCategorySelect(DownloadsViewModel.FILTER_IMAGES) },
                        label = { Text("Images ($imgCount)") }
                    )
                }
                item {
                    FilterChip(
                        selected = categoryFilter == DownloadsViewModel.FILTER_VIDEOS,
                        onClick = { onCategorySelect(DownloadsViewModel.FILTER_VIDEOS) },
                        label = { Text("Videos ($vidCount)") }
                    )
                }
                item {
                    FilterChip(
                        selected = categoryFilter == DownloadsViewModel.FILTER_DOCUMENTS,
                        onClick = { onCategorySelect(DownloadsViewModel.FILTER_DOCUMENTS) },
                        label = { Text("Documents ($docCount)") }
                    )
                }
            }

            if (downloads.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.FileDownload,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (searchQuery.isBlank()) "No downloads found" else "No results for '$searchQuery'",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 32.dp)
                ) {
                    items(downloads, key = { it.id }) { item ->
                        CardContainer {
                            DownloadItemRow(
                                item = item,
                                onClick = { onItemClick(item) },
                                onShare = { onItemShare(item) },
                                onCopyUrl = { onItemCopyUrl(item) },
                                onDetails = { onItemDetails(item) },
                                onDelete = { onItemDelete(item) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadItemRow(
    item: DownloadItem,
    onClick: () -> Unit,
    onShare: () -> Unit,
    onCopyUrl: () -> Unit,
    onDetails: () -> Unit,
    onDelete: () -> Unit
) {
    var isMenuOpen by remember { mutableStateOf(false) }

    val icon = when {
        ApkInstallerHelper.isApkFile(item.fileName, item.mimeType) -> Icons.Default.Android
        DownloadsViewModel.isImageFile(item.fileName, item.mimeType) -> Icons.Default.Image
        DownloadsViewModel.isVideoFile(item.fileName, item.mimeType) -> Icons.Default.Videocam
        DownloadsViewModel.isDocumentFile(item.fileName, item.mimeType) -> Icons.Default.Description
        else -> Icons.Default.FileDownload
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = item.fileName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                color = MaterialTheme.colorScheme.onSurface
            )

            val sizeStr = if (item.fileSize > 0) {
                "${item.downloadedBytes * 100 / item.fileSize}% • ${item.fileSize / 1024} KB"
            } else {
                "${item.downloadedBytes / 1024} KB"
            }

            Text(
                text = sizeStr,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if ((item.status == DownloadItem.STATUS_RUNNING || item.status == DownloadItem.STATUS_PENDING) && item.fileSize > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { item.downloadedBytes.toFloat() / item.fileSize },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }

        Box {
            IconButton(onClick = { isMenuOpen = true }) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Options",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }

            DropdownMenu(
                expanded = isMenuOpen,
                onDismissRequest = { isMenuOpen = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Share") },
                    onClick = { isMenuOpen = false; onShare() }
                )
                DropdownMenuItem(
                    text = { Text("Copy link") },
                    onClick = { isMenuOpen = false; onCopyUrl() }
                )
                DropdownMenuItem(
                    text = { Text("Details") },
                    onClick = { isMenuOpen = false; onDetails() }
                )
                DropdownMenuItem(
                    text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                    onClick = { isMenuOpen = false; onDelete() }
                )
            }
        }
    }
}
