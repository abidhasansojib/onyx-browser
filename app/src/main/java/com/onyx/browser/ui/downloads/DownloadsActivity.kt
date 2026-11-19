package com.onyx.browser.ui.downloads

import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.format.DateUtils
import android.text.format.Formatter
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.WebSettings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.onyx.browser.MainActivity
import com.onyx.browser.data.local.AppDatabase
import com.onyx.browser.data.model.DownloadItem
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.download.DownloadEngineManager
import com.onyx.browser.download.DownloadHandler
import com.onyx.browser.download.DownloadTaskSnapshot
import com.onyx.browser.download.FileUtils
import com.onyx.browser.ui.components.AppAlertDialog
import com.onyx.browser.ui.theme.OnyxTheme
import com.onyx.browser.ui.viewmodel.DownloadsViewModel
import com.onyx.browser.web.LocalFileLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DownloadsActivity : ComponentActivity() {

    companion object {
        const val EXTRA_INSTALL_APK_PATH = "com.onyx.browser.extra.INSTALL_APK_PATH"
    }

    private lateinit var viewModel: DownloadsViewModel
    private lateinit var preferences: BrowserPreferences
    private lateinit var database: AppDatabase
    private var pendingApkInstallPath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        viewModel = ViewModelProvider(this)[DownloadsViewModel::class.java]
        preferences = BrowserPreferences.getInstance(this)
        database = AppDatabase.getInstance(this)

        handleIncomingIntent(intent)

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
            val activeSnapshots by DownloadEngineManager.snapshotsFlow.collectAsState()

            var selectedIds by remember { mutableStateOf(setOf<Long>()) }
            var itemForOptions by remember { mutableStateOf<DownloadItem?>(null) }
            var itemDetailsToShow by remember { mutableStateOf<DownloadItem?>(null) }
            var itemToRename by remember { mutableStateOf<DownloadItem?>(null) }
            var itemToDelete by remember { mutableStateOf<DownloadItem?>(null) }
            var showBatchDeleteDialog by remember { mutableStateOf(false) }

            OnyxTheme(darkTheme = isDark, isAmoled = false) {
                DownloadsScreen(
                    downloads = filteredDownloads,
                    allDownloads = allDownloads,
                    activeSnapshots = activeSnapshots,
                    categoryFilter = categoryFilter,
                    onCategorySelect = { viewModel.setCategoryFilter(it) },
                    searchQuery = searchQuery,
                    onSearchQueryChange = { viewModel.setSearchQuery(it) },
                    selectedIds = selectedIds,
                    onToggleSelect = { item ->
                        selectedIds = if (selectedIds.contains(item.id)) {
                            selectedIds - item.id
                        } else {
                            selectedIds + item.id
                        }
                    },
                    onSelectAll = {
                        selectedIds = if (selectedIds.size == filteredDownloads.size && filteredDownloads.isNotEmpty()) {
                            emptySet()
                        } else {
                            filteredDownloads.map { it.id }.toSet()
                        }
                    },
                    onClearSelection = { selectedIds = emptySet() },
                    onShareSelected = { ids ->
                        val itemsToShare = allDownloads.filter { ids.contains(it.id) }
                        shareMultipleFiles(itemsToShare)
                    },
                    onDeleteSelected = { showBatchDeleteDialog = true },
                    onBackClick = { finish() },
                    onOpenSystemDownloads = { openSystemDownloadsFolder() },
                    onItemClick = { item -> openFile(item) },
                    onItemLongClick = { item -> selectedIds = setOf(item.id) },
                    onOptionsClick = { item -> itemForOptions = item },
                    onActionClick = { item -> toggleDownloadAction(item) },
                    onCancelClick = { item -> cancelOngoingDownload(item) }
                )

                // ── 3-Dot Options Bottom Sheet ─────────────────────────────────
                if (itemForOptions != null) {
                    val currentItem = itemForOptions!!
                    DownloadItemOptionsMenuSheet(
                        item = currentItem,
                        onDismiss = { itemForOptions = null },
                        onOpenOrInstall = {
                            itemForOptions = null
                            openFile(currentItem)
                        },
                        onOpenInFolder = {
                            itemForOptions = null
                            openInFileManager(currentItem)
                        },
                        onShare = {
                            itemForOptions = null
                            shareFile(currentItem)
                        },
                        onDownloadAgain = {
                            itemForOptions = null
                            redownloadItem(currentItem)
                        },
                        onCopyLink = {
                            itemForOptions = null
                            copyUrl(currentItem.url)
                        },
                        onOpenWebsite = {
                            itemForOptions = null
                            openOriginalSite(currentItem)
                        },
                        onRename = {
                            itemForOptions = null
                            itemToRename = currentItem
                        },
                        onViewDetails = {
                            itemForOptions = null
                            itemDetailsToShow = currentItem
                        },
                        onDelete = {
                            itemForOptions = null
                            val fileExists = FileUtils.doesFileExist(currentItem.filePath, this@DownloadsActivity)
                            if (fileExists) {
                                itemToDelete = currentItem
                            } else {
                                deleteDownload(currentItem)
                            }
                        }
                    )
                }

                // ── File Details Bottom Sheet ──────────────────────────────────
                if (itemDetailsToShow != null) {
                    val currentDetails = itemDetailsToShow!!
                    DownloadDetailsSheet(
                        item = currentDetails,
                        onDismiss = { itemDetailsToShow = null },
                        onCopyToClipboard = { label, text -> copyToClipboard(label, text) },
                        onShareLink = { url ->
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, url)
                            }
                            startActivity(Intent.createChooser(shareIntent, "Share Download Link"))
                        },
                        onChecksumsComputed = { sha256, md5 ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                try {
                                    val updated = currentDetails.copy(sha256 = sha256, md5 = md5)
                                    database.downloadDao().updateDownload(updated)
                                } catch (_: Exception) {}
                            }
                        },
                        computeChecksums = { path -> computeFileChecksums(path) }
                    )
                }

                // ── Rename File Dialog ─────────────────────────────────────────
                if (itemToRename != null) {
                    val toRename = itemToRename!!
                    var newNameInput by remember(toRename) { mutableStateOf(toRename.fileName) }
                    AppAlertDialog(
                        title = "Rename File",
                        text = {
                            Column {
                                Text(
                                    text = "Enter a new file name:",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                OutlinedTextField(
                                    value = newNameInput,
                                    onValueChange = { newNameInput = it },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        },
                        confirmButtonText = "Rename",
                        onConfirm = {
                            val trimmed = newNameInput.trim()
                            if (trimmed.isNotBlank() && trimmed != toRename.fileName) {
                                renameFile(toRename, trimmed)
                            }
                            itemToRename = null
                        },
                        onDismissRequest = { itemToRename = null }
                    )
                }

                // ── Single Item Delete Dialog ──────────────────────────────────
                if (itemToDelete != null) {
                    val toDelete = itemToDelete!!
                    AppAlertDialog(
                        title = "Delete download?",
                        text = {
                            Text("Are you sure you want to delete '${toDelete.fileName}'? This will permanently remove the file from storage and download history.")
                        },
                        confirmButtonText = "Delete",
                        isDestructive = true,
                        onConfirm = {
                            deleteDownload(toDelete)
                            itemToDelete = null
                        },
                        onDismissRequest = { itemToDelete = null }
                    )
                }

                // ── Batch Delete Dialog ────────────────────────────────────────
                if (showBatchDeleteDialog) {
                    val count = selectedIds.size
                    AppAlertDialog(
                        title = "Delete $count downloads?",
                        text = {
                            Text("Are you sure you want to delete $count selected items? Physical files will be removed from storage and history.")
                        },
                        confirmButtonText = "Delete All",
                        isDestructive = true,
                        onConfirm = {
                            val itemsToDelete = allDownloads.filter { selectedIds.contains(it.id) }
                            deleteMultipleDownloads(itemsToDelete)
                            selectedIds = emptySet()
                            showBatchDeleteDialog = false
                        },
                        onDismissRequest = { showBatchDeleteDialog = false }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val pendingPath = pendingApkInstallPath
        if (pendingPath != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && packageManager.canRequestPackageInstalls()) {
                pendingApkInstallPath = null
                ApkInstallerHelper.launchPackageInstaller(this, pendingPath)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val apkPath = intent?.getStringExtra(EXTRA_INSTALL_APK_PATH) ?: return
        intent.removeExtra(EXTRA_INSTALL_APK_PATH)
        if (apkPath.isNotBlank()) {
            ApkInstallerHelper.installApk(
                activity = this,
                filePath = apkPath,
                fileName = File(apkPath).name,
                onPermissionNeeded = { pendingPath ->
                    pendingApkInstallPath = pendingPath
                }
            )
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

        val targetItem = if (resolvedPath != item.filePath) {
            val realFile = File(resolvedPath)
            val realName = realFile.name
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    database.downloadDao().markCompleted(
                        id = item.id,
                        status = item.status,
                        fileName = realName,
                        filePath = resolvedPath,
                        fileSize = item.fileSize,
                        downloadedBytes = item.downloadedBytes,
                        sha256 = item.sha256,
                        md5 = item.md5
                    )
                } catch (_: Exception) {}
            }
            item.copy(fileName = realName, filePath = resolvedPath)
        } else {
            item
        }

        if (ApkInstallerHelper.isApkFile(targetItem.fileName, targetItem.mimeType)) {
            ApkInstallerHelper.installApk(
                activity = this,
                filePath = targetItem.filePath,
                fileName = targetItem.fileName,
                onPermissionNeeded = { pendingPath ->
                    pendingApkInstallPath = pendingPath
                }
            )
            return
        }

        if (LocalFileLoader.isWebDocument(targetItem.fileName, targetItem.mimeType)) {
            val target = targetItem.filePath
            val uri = if (target.startsWith("content://", ignoreCase = true) || target.startsWith("file://", ignoreCase = true)) {
                Uri.parse(target)
            } else {
                Uri.fromFile(File(target))
            }
            val intent = Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = uri
                putExtra(MainActivity.EXTRA_FROM_DOWNLOADS, true)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
            finish()
            return
        }

        if (targetItem.filePath.startsWith("content://", ignoreCase = true)) {
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse(targetItem.filePath), targetItem.mimeType.ifEmpty { "*/*" })
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                return
            } catch (e: Exception) {
                Toast.makeText(this, "Cannot open file: ${e.message}", Toast.LENGTH_SHORT).show()
                return
            }
        }

        val file = File(targetItem.filePath)
        if (!file.exists()) {
            Toast.makeText(this, "File does not exist: ${targetItem.fileName}", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                val resolvedMime = targetItem.mimeType.ifBlank {
                    val ext = MimeTypeMap.getFileExtensionFromUrl(uri.toString())
                    MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
                }
                setDataAndType(uri, resolvedMime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot open file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openInFileManager(item: DownloadItem) {
        val resolvedPath = FileUtils.resolveExistingPath(item.filePath, this)
        if (resolvedPath == null) {
            Toast.makeText(this, "File not found or deleted", Toast.LENGTH_SHORT).show()
            return
        }
        openFileManagerFolder(resolvedPath)
    }

    private fun openFileManagerFolder(targetFilePath: String? = null) {
        val parentFolder: File? = try {
            if (!targetFilePath.isNullOrBlank() && !targetFilePath.startsWith("content://", ignoreCase = true)) {
                val clean = if (targetFilePath.startsWith("file://", ignoreCase = true)) {
                    Uri.parse(targetFilePath).path ?: targetFilePath.removePrefix("file://")
                } else {
                    targetFilePath
                }
                File(clean).parentFile?.takeIf { it.exists() }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }

        val downloadsDir: File = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val targetDir = parentFolder ?: downloadsDir

        try {
            val downloadsIntent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (downloadsIntent.resolveActivity(packageManager) != null) {
                startActivity(downloadsIntent)
                return
            }
        } catch (_: Exception) {}

        try {
            val downloadsDocUri = Uri.parse("content://com.android.externalstorage.documents/document/primary:Download")
            val docIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(downloadsDocUri, "vnd.android.document/directory")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (docIntent.resolveActivity(packageManager) != null) {
                startActivity(docIntent)
                return
            }
        } catch (_: Exception) {}

        try {
            val rootUri = Uri.parse("content://com.android.externalstorage.documents/root/primary")
            val rootIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(rootUri, "vnd.android.document/root")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (rootIntent.resolveActivity(packageManager) != null) {
                startActivity(rootIntent)
                return
            }
        } catch (_: Exception) {}

        try {
            val folderUri = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    FileProvider.getUriForFile(this, "$packageName.fileprovider", targetDir)
                } else {
                    Uri.fromFile(targetDir)
                }
            } catch (_: Exception) {
                Uri.fromFile(targetDir)
            }

            val folderIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(folderUri, "vnd.android.document/directory")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (folderIntent.resolveActivity(packageManager) != null) {
                startActivity(folderIntent)
                return
            }
        } catch (_: Exception) {}

        val fileManagerPackages = listOf(
            "com.google.android.apps.nbu.files",
            "com.sec.android.app.myfiles",
            "com.mi.android.globalFileexplorer",
            "com.coloros.filemanager",
            "com.oneplus.filemanager",
            "com.huawei.hidisk",
            "com.lenovo.filebrowser",
            "pl.solidexplorer2",
            "com.mixplorer",
            "nextapp.fx",
            "ru.zdevs.zarchiver",
            "com.ghisler.android.TotalCommander",
            "com.google.android.documentsui",
            "com.android.documentsui"
        )

        for (pkg in fileManagerPackages) {
            try {
                val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launchIntent)
                    return
                }
            } catch (_: Exception) {}
        }

        try {
            val pickerIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(Intent.createChooser(pickerIntent, "Open in file manager"))
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot open file manager: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openOriginalSite(item: DownloadItem) {
        val rawUrl = item.url.trim()
        if (rawUrl.isBlank() || rawUrl.startsWith("data:", ignoreCase = true) || rawUrl.startsWith("blob:", ignoreCase = true)) {
            Toast.makeText(this, "Original website not available for locally generated file", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(rawUrl)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
        finish()
    }

    private fun shareFile(item: DownloadItem) {
        val resolvedPath = FileUtils.resolveExistingPath(item.filePath, this)
        if (resolvedPath == null) {
            Toast.makeText(this, "File not found or deleted", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val uri: Uri = if (resolvedPath.startsWith("content://")) {
                Uri.parse(resolvedPath)
            } else {
                val file = File(resolvedPath)
                if (file.exists()) {
                    FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                } else {
                    Toast.makeText(this, "File not found or deleted", Toast.LENGTH_SHORT).show()
                    return
                }
            }

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                val ext = MimeTypeMap.getFileExtensionFromUrl(resolvedPath)
                val resolvedMime = item.mimeType.ifBlank {
                    MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
                }
                type = resolvedMime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Share ${item.fileName}"))
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot share file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareMultipleFiles(items: List<DownloadItem>) {
        val uris = ArrayList<Uri>()
        for (item in items) {
            val resolvedPath = FileUtils.resolveExistingPath(item.filePath, this) ?: continue
            val uri = if (resolvedPath.startsWith("content://")) {
                Uri.parse(resolvedPath)
            } else {
                val file = File(resolvedPath)
                if (file.exists()) {
                    FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                } else null
            }
            if (uri != null) uris.add(uri)
        }
        if (uris.isEmpty()) {
            Toast.makeText(this, "Selected files are deleted or missing from storage", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = "*/*"
                    putExtra(Intent.EXTRA_STREAM, uris[0])
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "*/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            startActivity(Intent.createChooser(intent, "Share ${uris.size} files"))
        } catch (e: Exception) {
            Toast.makeText(this, "Could not share files: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun redownloadItem(item: DownloadItem) {
        val rawUrl = item.url.trim()
        if (rawUrl.isBlank()) {
            Toast.makeText(this, "Cannot re-download: missing URL", Toast.LENGTH_SHORT).show()
            return
        }
        val isLocalData = rawUrl.startsWith("data:", ignoreCase = true) || rawUrl.startsWith("blob:", ignoreCase = true)
        if (isLocalData) {
            Toast.makeText(this, "Cannot re-download locally generated file directly", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Starting download: ${item.fileName}", Toast.LENGTH_SHORT).show()
        DownloadHandler.handleDownload(
            activity = this,
            coroutineScope = lifecycleScope,
            url = rawUrl,
            userAgent = WebSettings.getDefaultUserAgent(this),
            contentDisposition = "",
            mimeType = item.mimeType,
            contentLength = item.fileSize,
            cookies = CookieManager.getInstance().getCookie(rawUrl).orEmpty(),
            referer = rawUrl
        )
    }

    private fun copyUrl(url: String) {
        if (url.isBlank()) {
            Toast.makeText(this, "Download link not available", Toast.LENGTH_SHORT).show()
            return
        }
        copyToClipboard("Download Link", url)
    }

    private fun copyToClipboard(label: String, text: String) {
        if (text.isBlank()) return
        val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clip.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "$label copied", Toast.LENGTH_SHORT).show()
    }

    private fun renameFile(item: DownloadItem, newName: String) {
        val illegalChars = Regex("[\\\\/:*?\"<>|]")
        if (illegalChars.containsMatchIn(newName)) {
            Toast.makeText(this, "Invalid characters in file name", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val resolvedOldPath = FileUtils.resolveExistingPath(item.filePath, this@DownloadsActivity) ?: item.filePath
            var newPath = resolvedOldPath

            if (resolvedOldPath.startsWith("content://")) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val values = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, newName)
                        }
                        contentResolver.update(Uri.parse(resolvedOldPath), values, null, null)
                    }
                } catch (_: Exception) {}
            } else {
                try {
                    val oldFile = File(resolvedOldPath)
                    if (oldFile.exists()) {
                        val newFile = File(oldFile.parentFile, newName)
                        if (newFile.exists()) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@DownloadsActivity, "A file with this name already exists", Toast.LENGTH_SHORT).show()
                            }
                            return@launch
                        }
                        if (oldFile.renameTo(newFile)) {
                            newPath = newFile.absolutePath
                            MediaScannerConnection.scanFile(this@DownloadsActivity, arrayOf(newPath), null, null)
                        }
                    }
                } catch (_: Exception) {}
            }

            val updatedItem = item.copy(
                fileName = newName,
                filePath = newPath
            )
            database.downloadDao().updateDownload(updatedItem)

            withContext(Dispatchers.Main) {
                Toast.makeText(this@DownloadsActivity, "Renamed to $newName", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun deleteDownload(item: DownloadItem) {
        DownloadEngineManager.cancelDownload(item.id)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val resolvedPath = FileUtils.resolveExistingPath(item.filePath, this@DownloadsActivity) ?: item.filePath
                if (resolvedPath.startsWith("content://")) {
                    contentResolver.delete(Uri.parse(resolvedPath), null, null)
                } else {
                    val file = File(resolvedPath)
                    if (file.exists()) file.delete()
                }
            } catch (_: Exception) {}

            try {
                val tempDir = File(cacheDir, "onyx_downloads")
                File(tempDir, "task_${item.id}.part").delete()
                File(tempDir, "task_${item.id}.part.chunks").delete()
            } catch (_: Exception) {}

            try {
                database.downloadDao().deleteDownload(item)
                database.downloadDao().deleteById(item.id)
            } catch (_: Exception) {}

            withContext(Dispatchers.Main) {
                Toast.makeText(this@DownloadsActivity, "Deleted ${item.fileName}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun deleteMultipleDownloads(items: List<DownloadItem>) {
        lifecycleScope.launch(Dispatchers.IO) {
            var count = 0
            for (item in items) {
                DownloadEngineManager.cancelDownload(item.id)
                try {
                    val resolvedPath = FileUtils.resolveExistingPath(item.filePath, this@DownloadsActivity) ?: item.filePath
                    if (resolvedPath.startsWith("content://")) {
                        contentResolver.delete(Uri.parse(resolvedPath), null, null)
                    } else {
                        val file = File(resolvedPath)
                        if (file.exists()) file.delete()
                    }
                } catch (_: Exception) {}

                try {
                    val tempDir = File(cacheDir, "onyx_downloads")
                    File(tempDir, "task_${item.id}.part").delete()
                    File(tempDir, "task_${item.id}.part.chunks").delete()
                } catch (_: Exception) {}

                try {
                    database.downloadDao().deleteDownload(item)
                    database.downloadDao().deleteById(item.id)
                    count++
                } catch (_: Exception) {}
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(this@DownloadsActivity, "Deleted $count downloads", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun toggleDownloadAction(item: DownloadItem) {
        val snapshot = DownloadEngineManager.snapshotsFlow.value[item.id]
        if (snapshot != null) {
            if (snapshot.status == DownloadItem.STATUS_RUNNING) {
                DownloadEngineManager.pauseDownload(item.id)
            } else if (snapshot.status == DownloadItem.STATUS_PAUSED) {
                DownloadEngineManager.resumeDownload(item.id)
            } else if (snapshot.status == DownloadItem.STATUS_FAILED) {
                DownloadEngineManager.retryDownload(item.id)
            }
        } else {
            if (item.status == DownloadItem.STATUS_PAUSED || item.status == DownloadItem.STATUS_FAILED || item.status == DownloadItem.STATUS_RUNNING) {
                DownloadEngineManager.resumeExistingDownload(this, item)
            }
        }
    }

    private fun cancelOngoingDownload(item: DownloadItem) {
        DownloadEngineManager.cancelDownload(item.id)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                database.downloadDao().deleteById(item.id)
            } catch (_: Exception) {}
            withContext(Dispatchers.Main) {
                Toast.makeText(this@DownloadsActivity, "Download cancelled", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun computeFileChecksums(filePath: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        try {
            val resolvedPath = FileUtils.resolveExistingPath(filePath, this@DownloadsActivity) ?: filePath
            val inputStream = if (resolvedPath.startsWith("content://")) {
                contentResolver.openInputStream(Uri.parse(resolvedPath))
            } else {
                val file = File(resolvedPath)
                if (file.exists() && file.canRead()) FileInputStream(file) else null
            } ?: return@withContext null

            val sha256Digest = MessageDigest.getInstance("SHA-256")
            val md5Digest = MessageDigest.getInstance("MD5")
            val buffer = ByteArray(64 * 1024)
            inputStream.use { stream ->
                var bytesRead: Int
                while (stream.read(buffer).also { bytesRead = it } != -1) {
                    sha256Digest.update(buffer, 0, bytesRead)
                    md5Digest.update(buffer, 0, bytesRead)
                }
            }
            val sha256 = sha256Digest.digest().joinToString("") { "%02x".format(it) }
            val md5 = md5Digest.digest().joinToString("") { "%02x".format(it) }
            Pair(sha256, md5)
        } catch (_: Exception) {
            null
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    downloads: List<DownloadItem>,
    allDownloads: List<DownloadItem>,
    activeSnapshots: Map<Long, DownloadTaskSnapshot>,
    categoryFilter: Int,
    onCategorySelect: (Int) -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    selectedIds: Set<Long>,
    onToggleSelect: (DownloadItem) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onShareSelected: (Set<Long>) -> Unit,
    onDeleteSelected: () -> Unit,
    onBackClick: () -> Unit,
    onOpenSystemDownloads: () -> Unit,
    onItemClick: (DownloadItem) -> Unit,
    onItemLongClick: (DownloadItem) -> Unit,
    onOptionsClick: (DownloadItem) -> Unit,
    onActionClick: (DownloadItem) -> Unit,
    onCancelClick: (DownloadItem) -> Unit
) {
    var isSearchActive by remember { mutableStateOf(false) }
    val isSelectionMode = selectedIds.isNotEmpty()

    BackHandler(enabled = isSelectionMode || isSearchActive) {
        if (isSelectionMode) {
            onClearSelection()
        } else {
            isSearchActive = false
            onSearchQueryChange("")
        }
    }

    val totalCount = allDownloads.size
    val apkCount = allDownloads.count { ApkInstallerHelper.isApkFile(it.fileName, it.mimeType) }
    val imgCount = allDownloads.count { DownloadsViewModel.isImageFile(it.fileName, it.mimeType) }
    val vidCount = allDownloads.count { DownloadsViewModel.isVideoFile(it.fileName, it.mimeType) }
    val docCount = allDownloads.count { DownloadsViewModel.isDocumentFile(it.fileName, it.mimeType) }

    Scaffold(
        topBar = {
            if (isSelectionMode) {
                TopAppBar(
                    title = {
                        Text(
                            text = "${selectedIds.size} selected",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onClearSelection) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close selection mode",
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = onSelectAll) {
                            Icon(
                                imageVector = Icons.Default.SelectAll,
                                contentDescription = if (selectedIds.size == downloads.size && downloads.isNotEmpty()) "Deselect all" else "Select all",
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                        IconButton(onClick = { onShareSelected(selectedIds) }) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Share selected",
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                        IconButton(onClick = onDeleteSelected) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete selected",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            } else {
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
            }
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
                        .padding(bottom = 16.dp)
                ) {
                    items(downloads, key = { it.id }) { item ->
                        val snapshot = activeSnapshots[item.id]
                        val isSelected = selectedIds.contains(item.id)
                        DownloadItemRow(
                            item = item,
                            snapshot = snapshot,
                            isSelectionMode = isSelectionMode,
                            isSelected = isSelected,
                            onClick = {
                                if (isSelectionMode) {
                                    onToggleSelect(item)
                                } else {
                                    onItemClick(item)
                                }
                            },
                            onLongClick = {
                                if (isSelectionMode) {
                                    onToggleSelect(item)
                                } else {
                                    onItemLongClick(item)
                                }
                            },
                            onToggleSelect = { onToggleSelect(item) },
                            onOptionsClick = { onOptionsClick(item) },
                            onActionClick = { onActionClick(item) },
                            onCancelClick = { onCancelClick(item) }
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = if (isSelectionMode) 100.dp else 68.dp, end = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                            thickness = 0.5.dp
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadItemRow(
    item: DownloadItem,
    snapshot: DownloadTaskSnapshot?,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleSelect: () -> Unit,
    onOptionsClick: () -> Unit,
    onActionClick: () -> Unit,
    onCancelClick: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val icon = getFileIcon(item.fileName, item.mimeType)

    val isOngoing = snapshot != null && (snapshot.status == DownloadItem.STATUS_RUNNING ||
            snapshot.status == DownloadItem.STATUS_PAUSED ||
            snapshot.status == DownloadItem.STATUS_PENDING)

    val isDbPausedOrRunning = snapshot == null && (item.status == DownloadItem.STATUS_PAUSED ||
            item.status == DownloadItem.STATUS_RUNNING)

    val fileExists = remember(item.filePath) { FileUtils.doesFileExist(item.filePath, context) }
    val isDeleted = !fileExists && item.status == DownloadItem.STATUS_COMPLETED

    val contentAlpha = if (isDeleted) 0.55f else 1.0f
    val detailsAlpha = if (isDeleted) 0.75f else 1.0f

    val rowBackground = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
    } else {
        Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBackground)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isSelectionMode) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggleSelect() },
                modifier = Modifier.padding(end = 8.dp)
            )
        }

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
                tint = MaterialTheme.colorScheme.primary.copy(alpha = contentAlpha),
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
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha)
            )

            if (isOngoing && snapshot != null) {
                val downloadedStr = Formatter.formatFileSize(context, snapshot.downloadedBytes)
                val totalStr = if (snapshot.totalBytes > 0) Formatter.formatFileSize(context, snapshot.totalBytes) else ""
                val speedStr = Formatter.formatFileSize(context, snapshot.speedBytesPerSec) + "/s"
                val progressText = if (snapshot.totalBytes > 0) {
                    val pct = ((snapshot.downloadedBytes * 100L) / snapshot.totalBytes).toInt().coerceIn(0, 100)
                    if (snapshot.status == DownloadItem.STATUS_PAUSED) {
                        "Paused • $downloadedStr / $totalStr ($pct%)"
                    } else {
                        "$downloadedStr / $totalStr ($pct%) • $speedStr"
                    }
                } else {
                    if (snapshot.status == DownloadItem.STATUS_PAUSED) "Paused • $downloadedStr" else "$downloadedStr • $speedStr"
                }

                Text(
                    text = progressText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(4.dp))
                if (snapshot.totalBytes > 0) {
                    LinearProgressIndicator(
                        progress = { (snapshot.downloadedBytes.toFloat() / snapshot.totalBytes).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            } else if (isDbPausedOrRunning) {
                val downloadedStr = Formatter.formatFileSize(context, item.downloadedBytes)
                val totalStr = if (item.fileSize > 0) Formatter.formatFileSize(context, item.fileSize) else ""
                val pausedText = if (item.fileSize > 0 && item.downloadedBytes > 0) {
                    val pct = ((item.downloadedBytes * 100L) / item.fileSize).toInt().coerceIn(0, 100)
                    "Paused • $downloadedStr / $totalStr ($pct%)"
                } else {
                    "Paused • $downloadedStr"
                }

                Text(
                    text = pausedText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (item.fileSize > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { (item.downloadedBytes.toFloat() / item.fileSize).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            } else {
                val sizeFormatted = if (item.fileSize > 0) {
                    Formatter.formatFileSize(context, item.fileSize)
                } else ""

                val timeFormatted = DateUtils.getRelativeTimeSpanString(
                    item.downloadTime,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS
                ).toString()

                val statusPrefix = when {
                    isDeleted -> "File deleted • "
                    item.status == DownloadItem.STATUS_FAILED -> "Failed • "
                    item.status == DownloadItem.STATUS_CANCELLED -> "Cancelled • "
                    item.status == DownloadItem.STATUS_PAUSED -> "Paused • "
                    else -> ""
                }

                val details = listOfNotNull(
                    sizeFormatted.ifEmpty { null },
                    timeFormatted
                ).joinToString(" • ")

                Text(
                    text = "$statusPrefix$details",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = detailsAlpha)
                )
            }
        }

        if (!isSelectionMode) {
            if (isOngoing || isDbPausedOrRunning) {
                val isPaused = snapshot?.status == DownloadItem.STATUS_PAUSED || isDbPausedOrRunning
                IconButton(onClick = onActionClick) {
                    Icon(
                        imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = if (isPaused) "Resume" else "Pause",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = onCancelClick) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cancel download",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                }
            } else {
                IconButton(onClick = onOptionsClick) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More options",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadItemOptionsMenuSheet(
    item: DownloadItem,
    onDismiss: () -> Unit,
    onOpenOrInstall: () -> Unit,
    onOpenInFolder: () -> Unit,
    onShare: () -> Unit,
    onDownloadAgain: () -> Unit,
    onCopyLink: () -> Unit,
    onOpenWebsite: () -> Unit,
    onRename: () -> Unit,
    onViewDetails: () -> Unit,
    onDelete: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val fileExists = remember(item.filePath) { FileUtils.doesFileExist(item.filePath, context) }
    val isLocalData = item.url.startsWith("data:", ignoreCase = true) || item.url.startsWith("blob:", ignoreCase = true)
    val displayUrl = when {
        item.url.isNotBlank() && !isLocalData -> item.url
        else -> "Locally generated / page data"
    }

    val (openLabel, openIcon) = getFileTypeOpenLabel(item.fileName, item.mimeType)
    val fileIcon = getFileIcon(item.fileName, item.mimeType)

    val sizeFormatted = if (item.fileSize > 0) Formatter.formatFileSize(context, item.fileSize) else ""
    val timeFormatted = DateUtils.getRelativeTimeSpanString(item.downloadTime, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    val headerSubtitle = listOfNotNull(sizeFormatted.ifEmpty { null }, timeFormatted).joinToString(" • ")

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = fileIcon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.fileName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = headerSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = displayUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!fileExists) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            Text(
                                text = "File deleted from device",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 10.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            // Action Items
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                if (fileExists) {
                    MenuActionRow(
                        icon = openIcon,
                        title = openLabel,
                        onClick = onOpenOrInstall
                    )
                    MenuActionRow(
                        icon = Icons.Default.Folder,
                        title = "Open in file manager",
                        onClick = onOpenInFolder
                    )
                    MenuActionRow(
                        icon = Icons.Default.Share,
                        title = "Share file",
                        onClick = onShare
                    )
                }

                if (!isLocalData && item.url.isNotBlank()) {
                    MenuActionRow(
                        icon = Icons.Default.Refresh,
                        title = "Download again",
                        onClick = onDownloadAgain
                    )
                }

                if (item.url.isNotBlank()) {
                    MenuActionRow(
                        icon = Icons.Default.ContentCopy,
                        title = "Copy link",
                        onClick = onCopyLink
                    )
                }

                if (!isLocalData && item.url.isNotBlank()) {
                    MenuActionRow(
                        icon = Icons.Default.Language,
                        title = "Open website",
                        onClick = onOpenWebsite
                    )
                }

                if (fileExists) {
                    MenuActionRow(
                        icon = Icons.Default.Edit,
                        title = "Rename file",
                        onClick = onRename
                    )
                }

                MenuActionRow(
                    icon = Icons.Default.Info,
                    title = "View details",
                    onClick = onViewDetails
                )

                MenuActionRow(
                    icon = Icons.Default.Delete,
                    title = if (fileExists) "Delete" else "Remove from list",
                    tint = MaterialTheme.colorScheme.error,
                    onClick = onDelete
                )
            }
        }
    }
}

@Composable
private fun MenuActionRow(
    icon: ImageVector,
    title: String,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = tint
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadDetailsSheet(
    item: DownloadItem,
    onDismiss: () -> Unit,
    onCopyToClipboard: (label: String, text: String) -> Unit,
    onShareLink: (url: String) -> Unit,
    onChecksumsComputed: (sha256: String, md5: String) -> Unit,
    computeChecksums: suspend (filePath: String) -> Pair<String, String>?
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val fileExists = remember(item.filePath) { FileUtils.doesFileExist(item.filePath, context) }

    var currentSha256 by remember(item.sha256) { mutableStateOf(item.sha256) }
    var currentMd5 by remember(item.md5) { mutableStateOf(item.md5) }
    var isComputingChecksums by remember { mutableStateOf(false) }

    LaunchedEffect(item.id) {
        if (fileExists && (currentSha256.isBlank() || currentMd5.isBlank())) {
            isComputingChecksums = true
            val computed = computeChecksums(item.filePath)
            if (computed != null) {
                currentSha256 = computed.first
                currentMd5 = computed.second
                onChecksumsComputed(computed.first, computed.second)
            }
            isComputingChecksums = false
        }
    }

    val isLocalData = item.url.startsWith("data:", ignoreCase = true) || item.url.startsWith("blob:", ignoreCase = true)
    val displayUrl = when {
        item.url.isNotBlank() && !isLocalData -> item.url
        isLocalData -> "Locally generated data"
        else -> "Not available"
    }

    val sizeFormatted = if (item.fileSize > 0) {
        "${Formatter.formatFileSize(context, item.fileSize)} (${item.fileSize} bytes)"
    } else {
        "Unknown size"
    }

    val dateFormatted = try {
        val sdf = SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.getDefault())
        sdf.format(Date(item.downloadTime))
    } catch (_: Exception) {
        DateUtils.getRelativeTimeSpanString(item.downloadTime, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    }

    var verifyInput by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "File Details",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = item.fileName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
            ) {
                DetailPropertyRow(
                    label = "File Name",
                    value = item.fileName,
                    onCopy = { onCopyToClipboard("File Name", item.fileName) }
                )

                DetailPropertyRow(
                    label = "File Size",
                    value = sizeFormatted
                )

                DetailPropertyRow(
                    label = "File Status",
                    value = if (fileExists) "Available on storage" else "File deleted or moved",
                    valueColor = if (fileExists) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
                )

                DetailPropertyRow(
                    label = "Download Date",
                    value = dateFormatted
                )

                DetailPropertyRow(
                    label = "File Path",
                    value = item.filePath,
                    onCopy = { onCopyToClipboard("File Path", item.filePath) }
                )

                DetailPropertyRow(
                    label = "Download URL",
                    value = displayUrl,
                    onCopy = if (!isLocalData && item.url.isNotBlank()) { { onCopyToClipboard("Download URL", item.url) } } else null
                )

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 12.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )

                // Checksums Section
                Text(
                    text = "Cryptographic Hashes",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))

                if (currentSha256.isNotBlank() || currentMd5.isNotBlank()) {
                    if (currentSha256.isNotBlank()) {
                        DetailPropertyRow(
                            label = "SHA-256",
                            value = currentSha256,
                            onCopy = { onCopyToClipboard("SHA-256", currentSha256) }
                        )
                    }
                    if (currentMd5.isNotBlank()) {
                        DetailPropertyRow(
                            label = "MD5",
                            value = currentMd5,
                            onCopy = { onCopyToClipboard("MD5", currentMd5) }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = verifyInput,
                        onValueChange = { verifyInput = it.trim() },
                        label = { Text("Verify checksum (paste hash)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            if (verifyInput.isNotEmpty()) {
                                IconButton(onClick = { verifyInput = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear")
                                }
                            }
                        }
                    )

                    if (verifyInput.isNotEmpty()) {
                        val isMatch = verifyInput.equals(currentSha256, ignoreCase = true) ||
                                verifyInput.equals(currentMd5, ignoreCase = true)
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = if (isMatch) Color(0xFFE8F5E9) else MaterialTheme.colorScheme.errorContainer
                        ) {
                            Text(
                                text = if (isMatch) "✓ Checksum MATCHES successfully" else "✗ Checksum MISMATCH — hash does not match",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isMatch) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                } else if (isComputingChecksums) {
                    Row(
                        modifier = Modifier.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Calculating cryptographic checksums...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Text(
                        text = if (!fileExists) "Checksums unavailable (file not present on device)" else "Checksums not computed",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (!isLocalData && item.url.isNotBlank()) {
                    Button(
                        onClick = { onShareLink(item.url) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Share Download Link")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Close")
                }
            }
        }
    }
}

@Composable
private fun DetailPropertyRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    onCopy: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = valueColor,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (onCopy != null) {
            IconButton(onClick = onCopy, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Copy $label",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

private fun getFileIcon(fileName: String, mimeType: String): ImageVector {
    val lowerName = fileName.lowercase()
    val lowerMime = mimeType.lowercase()
    return when {
        ApkInstallerHelper.isApkFile(fileName, mimeType) -> Icons.Default.Android
        lowerName.endsWith(".mht") || lowerName.endsWith(".mhtml") ||
                lowerName.endsWith(".html") || lowerName.endsWith(".htm") ||
                lowerMime.contains("html") || lowerMime.contains("multipart") -> Icons.Default.Language
        lowerMime.startsWith("image/") || lowerName.endsWith(".png") || lowerName.endsWith(".jpg") ||
                lowerName.endsWith(".jpeg") || lowerName.endsWith(".webp") || lowerName.endsWith(".gif") ||
                lowerName.endsWith(".svg") || lowerName.endsWith(".bmp") -> Icons.Default.Image
        lowerMime.startsWith("video/") || lowerName.endsWith(".mp4") || lowerName.endsWith(".mkv") ||
                lowerName.endsWith(".webm") || lowerName.endsWith(".mov") || lowerName.endsWith(".avi") ||
                lowerName.endsWith(".flv") -> Icons.Default.PlayCircle
        lowerMime.startsWith("text/") || lowerMime.contains("pdf") || lowerMime.contains("document") ||
                lowerMime.contains("sheet") || lowerName.endsWith(".pdf") || lowerName.endsWith(".doc") ||
                lowerName.endsWith(".docx") || lowerName.endsWith(".xls") || lowerName.endsWith(".xlsx") ||
                lowerName.endsWith(".ppt") || lowerName.endsWith(".pptx") || lowerName.endsWith(".txt") ||
                lowerName.endsWith(".md") || lowerName.endsWith(".markdown") || lowerName.endsWith(".epub") ||
                lowerName.endsWith(".zip") || lowerName.endsWith(".tar") || lowerName.endsWith(".gz") -> Icons.Default.Description
        else -> Icons.Default.FileDownload
    }
}

private fun getFileTypeOpenLabel(fileName: String, mimeType: String): Pair<String, ImageVector> {
    val lowerName = fileName.lowercase()
    val lowerMime = mimeType.lowercase()
    return when {
        ApkInstallerHelper.isApkFile(fileName, mimeType) -> Pair("Install", Icons.Default.Android)
        lowerName.endsWith(".mht") || lowerName.endsWith(".mhtml") ||
                lowerName.endsWith(".html") || lowerName.endsWith(".htm") ||
                lowerMime.contains("html") || lowerMime.contains("multipart") -> Pair("Open in browser", Icons.Default.Language)
        lowerMime.startsWith("video/") || lowerName.endsWith(".mp4") || lowerName.endsWith(".mkv") ||
                lowerName.endsWith(".webm") || lowerName.endsWith(".mov") || lowerName.endsWith(".avi") ||
                lowerName.endsWith(".flv") -> Pair("Play video", Icons.Default.PlayCircle)
        else -> Pair("Open file", Icons.Default.OpenInNew)
    }
}
