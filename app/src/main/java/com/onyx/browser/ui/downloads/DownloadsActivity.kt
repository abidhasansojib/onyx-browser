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
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import com.onyx.browser.download.DownloadHandler
import com.onyx.browser.download.FileUtils
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.onyx.browser.MainActivity
import com.onyx.browser.R
import com.onyx.browser.data.local.AppDatabase
import com.onyx.browser.data.model.DownloadItem
import com.onyx.browser.databinding.ActivityDownloadsBinding
import com.onyx.browser.databinding.BottomSheetDownloadDetailsBinding
import com.onyx.browser.databinding.BottomSheetDownloadItemMenuBinding
import com.onyx.browser.ui.viewmodel.DownloadsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.WebSettings
import com.onyx.browser.ui.theme.AccentColorManager
import com.onyx.browser.web.LocalFileLoader
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DownloadsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDownloadsBinding
    private lateinit var adapter: DownloadsAdapter
    private lateinit var database: AppDatabase
    private lateinit var viewModel: DownloadsViewModel
    private var pendingApkInstallPath: String? = null

    companion object {
        const val EXTRA_INSTALL_APK_PATH = "com.onyx.browser.extra.INSTALL_APK_PATH"
        private const val FILTER_ALL = DownloadsViewModel.FILTER_ALL
        private const val FILTER_APKS = DownloadsViewModel.FILTER_APKS
        private const val FILTER_IMAGES = DownloadsViewModel.FILTER_IMAGES
        private const val FILTER_VIDEOS = DownloadsViewModel.FILTER_VIDEOS
        private const val FILTER_DOCUMENTS = DownloadsViewModel.FILTER_DOCUMENTS
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AccentColorManager.applyThemeOverlay(this)
        super.onCreate(savedInstanceState)
        binding = ActivityDownloadsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        database = AppDatabase.getInstance(this)
        viewModel = ViewModelProvider(this)[DownloadsViewModel::class.java]

        binding.toolbarDownloads.setNavigationOnClickListener { finish() }

        binding.btnOpenSystemDownloads.setOnClickListener {
            openSystemDownloadsFolder()
        }

        setupRecyclerView()
        setupSearchAndFilters()
        observeDownloads()
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        adapter.notifyDataSetChanged()
        val pendingPath = pendingApkInstallPath
        if (pendingPath != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && packageManager.canRequestPackageInstalls()) {
                pendingApkInstallPath = null
                ApkInstallerHelper.launchPackageInstaller(this, pendingPath)
            }
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val apkPath = intent?.getStringExtra(EXTRA_INSTALL_APK_PATH) ?: return
        intent.removeExtra(EXTRA_INSTALL_APK_PATH)
        if (apkPath.isNotBlank()) {
            ApkInstallerHelper.installApk(
                activity = this,
                filePath = apkPath,
                onPermissionNeeded = { pendingPath ->
                    pendingApkInstallPath = pendingPath
                }
            )
        }
    }

    private fun openSystemDownloadsFolder() {
        openFileManagerFolder()
    }

    private fun setupRecyclerView() {
        adapter = DownloadsAdapter(
            onItemClicked = { item ->
                val snapshot = com.onyx.browser.download.OnyxDownloadManager.snapshotsFlow.value[item.id]
                if (snapshot != null && (snapshot.status == DownloadItem.STATUS_RUNNING || snapshot.status == DownloadItem.STATUS_PAUSED || snapshot.status == DownloadItem.STATUS_PENDING)) {
                    toggleDownloadAction(item)
                } else if (item.status == DownloadItem.STATUS_COMPLETED) {
                    openFile(item)
                } else {
                    toggleDownloadAction(item)
                }
            },
            onCancelOngoingClicked = { item -> confirmCancelDownload(item) },
            onMoreOptionsClicked = { item -> showDownloadItemOptionsMenu(item) },
            onActionClicked = { item -> toggleDownloadAction(item) },
            onItemLongClicked = { item -> showDownloadItemOptionsMenu(item) }
        )

        binding.rvDownloads.layoutManager = LinearLayoutManager(this)
        binding.rvDownloads.adapter = adapter
    }

    private fun toggleDownloadAction(item: DownloadItem) {
        val snapshot = com.onyx.browser.download.OnyxDownloadManager.snapshotsFlow.value[item.id]
        if (snapshot != null) {
            if (snapshot.status == DownloadItem.STATUS_RUNNING) {
                com.onyx.browser.download.OnyxDownloadManager.pauseDownload(item.id)
            } else if (snapshot.status == DownloadItem.STATUS_PAUSED) {
                com.onyx.browser.download.OnyxDownloadManager.resumeDownload(item.id)
            } else if (snapshot.status == DownloadItem.STATUS_FAILED) {
                com.onyx.browser.download.OnyxDownloadManager.retryDownload(item.id)
            }
        } else {
            // Task not currently in memory; check DB status
            if (item.status == DownloadItem.STATUS_PAUSED || item.status == DownloadItem.STATUS_FAILED || item.status == DownloadItem.STATUS_RUNNING) {
                com.onyx.browser.download.OnyxDownloadManager.resumeExistingDownload(this, item)
            }
        }
    }

    private fun setupSearchAndFilters() {
        val existingQuery = viewModel.searchQuery.value
        if (existingQuery.isNotEmpty() && binding.etSearchDownloads.text?.toString() != existingQuery) {
            binding.etSearchDownloads.setText(existingQuery)
            binding.etSearchDownloads.setSelection(existingQuery.length)
        }
        binding.btnClearSearch.visibility = if (existingQuery.isNotEmpty()) View.VISIBLE else View.GONE

        binding.etSearchDownloads.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString().orEmpty()
                binding.btnClearSearch.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
                viewModel.setSearchQuery(query)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnClearSearch.setOnClickListener {
            binding.etSearchDownloads.text?.clear()
        }

        binding.chipGroupFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            val category = when {
                checkedIds.contains(R.id.chipApks) -> FILTER_APKS
                checkedIds.contains(R.id.chipImages) -> FILTER_IMAGES
                checkedIds.contains(R.id.chipVideos) -> FILTER_VIDEOS
                checkedIds.contains(R.id.chipDocuments) -> FILTER_DOCUMENTS
                else -> FILTER_ALL
            }
            viewModel.setCategoryFilter(category)
        }
    }

    private fun isImageFile(fileName: String, mimeType: String): Boolean =
        DownloadsViewModel.isImageFile(fileName, mimeType)

    private fun isVideoFile(fileName: String, mimeType: String): Boolean =
        DownloadsViewModel.isVideoFile(fileName, mimeType)

    private fun isDocumentFile(fileName: String, mimeType: String): Boolean =
        DownloadsViewModel.isDocumentFile(fileName, mimeType)

    private fun updateFilterChipCounts(list: List<DownloadItem>) {
        val totalCount = list.size
        var apkCount = 0
        var imgCount = 0
        var vidCount = 0
        var docCount = 0

        for (item in list) {
            when {
                ApkInstallerHelper.isApkFile(item.fileName, item.mimeType) -> apkCount++
                isImageFile(item.fileName, item.mimeType) -> imgCount++
                isVideoFile(item.fileName, item.mimeType) -> vidCount++
                isDocumentFile(item.fileName, item.mimeType) -> docCount++
            }
        }

        binding.chipAll.text = if (totalCount > 0) "All ($totalCount)" else "All"
        binding.chipApks.text = if (apkCount > 0) "APKs ($apkCount)" else "APKs"
        binding.chipImages.text = if (imgCount > 0) "Images ($imgCount)" else "Images"
        binding.chipVideos.text = if (vidCount > 0) "Videos ($vidCount)" else "Videos"
        binding.chipDocuments.text = if (docCount > 0) "Documents ($docCount)" else "Documents"
    }

    private fun observeDownloads() {
        lifecycleScope.launch {
            viewModel.allDownloads.collectLatest { list ->
                updateFilterChipCounts(list)
            }
        }

        lifecycleScope.launch {
            viewModel.filteredDownloads.collectLatest { filtered ->
                adapter.submitList(filtered)
                binding.emptyDownloadsView.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
                binding.rvDownloads.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
            }
        }

        lifecycleScope.launch {
            com.onyx.browser.download.OnyxDownloadManager.snapshotsFlow.collectLatest { snapshots ->
                adapter.updateActiveSnapshots(snapshots)
            }
        }
    }

    private fun isLocalWebDocument(item: DownloadItem, resolvedPath: String = item.filePath): Boolean {
        return LocalFileLoader.isWebDocument(item.fileName, item.mimeType) ||
                (resolvedPath.isNotBlank() && LocalFileLoader.isWebDocument(resolvedPath, item.mimeType))
    }

    private fun openFile(item: DownloadItem) {
        val resolvedPath = FileUtils.resolveExistingPath(item.filePath, this)
        if (resolvedPath == null) {
            Toast.makeText(this, "File not found or deleted", Toast.LENGTH_SHORT).show()
            adapter.notifyDataSetChanged()
            return
        }

        // Reconcile path or filename in DB if MediaStore renamed or appended extension
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

        if (isLocalWebDocument(targetItem, resolvedPath)) {
            val target = targetItem.filePath
            val uri = if (target.startsWith("content://", ignoreCase = true) || target.startsWith("file://", ignoreCase = true)) {
                Uri.parse(target)
            } else {
                Uri.fromFile(File(target))
            }
            val intent = Intent(this, com.onyx.browser.MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = uri
                putExtra(com.onyx.browser.MainActivity.EXTRA_FROM_DOWNLOADS, true)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
            return
        }

        // For content URIs
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
            val uri: Uri = FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                file
            )
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

    private fun confirmCancelDownload(item: DownloadItem) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Cancel Download")
            .setMessage("Are you sure you want to cancel downloading \"${item.fileName}\"? The unfinished download file will be deleted.")
            .setPositiveButton("Cancel Download") { _, _ ->
                com.onyx.browser.download.OnyxDownloadManager.cancelDownload(item.id)
                lifecycleScope.launch(Dispatchers.IO) {
                    database.downloadDao().deleteById(item.id)
                    database.downloadDao().deleteDownload(item)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@DownloadsActivity, "Download cancelled", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Keep Downloading", null)
            .show()
    }

    private fun showDownloadItemOptionsMenu(item: DownloadItem) {
        val bottomSheet = BottomSheetDialog(this)
        val menuBinding = BottomSheetDownloadItemMenuBinding.inflate(layoutInflater)
        bottomSheet.setContentView(menuBinding.root)

        val lowerName = item.fileName.lowercase()
        val isApk = ApkInstallerHelper.isApkFile(item.fileName, item.mimeType)
        when {
            isApk -> {
                menuBinding.ivMenuFileIcon.setImageResource(R.drawable.ic_android)
                menuBinding.ivMenuOpenIcon.setImageResource(R.drawable.ic_android)
                menuBinding.tvMenuOpenText.text = "Install"
            }
            lowerName.endsWith(".mht") || lowerName.endsWith(".mhtml") ||
            lowerName.endsWith(".html") || lowerName.endsWith(".htm") ||
            item.mimeType.contains("html") || item.mimeType.contains("multipart") -> {
                menuBinding.ivMenuFileIcon.setImageResource(R.drawable.ic_web)
                menuBinding.ivMenuOpenIcon.setImageResource(R.drawable.ic_open_in_new)
                menuBinding.tvMenuOpenText.text = "Open in browser"
            }
            lowerName.endsWith(".png") || lowerName.endsWith(".jpg") ||
            lowerName.endsWith(".jpeg") || lowerName.endsWith(".webp") ||
            lowerName.endsWith(".gif") || item.mimeType.startsWith("image/") -> {
                menuBinding.ivMenuFileIcon.setImageResource(R.drawable.ic_image)
                menuBinding.ivMenuOpenIcon.setImageResource(R.drawable.ic_open_in_new)
                menuBinding.tvMenuOpenText.text = "Open file"
            }
            lowerName.endsWith(".mp4") || lowerName.endsWith(".mkv") ||
            lowerName.endsWith(".webm") || item.mimeType.startsWith("video/") -> {
                menuBinding.ivMenuFileIcon.setImageResource(R.drawable.ic_play_circle)
                menuBinding.ivMenuOpenIcon.setImageResource(R.drawable.ic_open_in_new)
                menuBinding.tvMenuOpenText.text = "Play video"
            }
            lowerName.endsWith(".md") || lowerName.endsWith(".markdown") ||
            lowerName.endsWith(".txt") -> {
                menuBinding.ivMenuFileIcon.setImageResource(R.drawable.ic_file)
                menuBinding.ivMenuOpenIcon.setImageResource(R.drawable.ic_open_in_new)
                menuBinding.tvMenuOpenText.text = "Open file"
            }
            else -> {
                menuBinding.ivMenuFileIcon.setImageResource(R.drawable.ic_download)
                menuBinding.ivMenuOpenIcon.setImageResource(R.drawable.ic_open_in_new)
                menuBinding.tvMenuOpenText.text = "Open file"
            }
        }

        menuBinding.tvMenuFileName.text = item.fileName
        val sizeFormatted = if (item.fileSize > 0) Formatter.formatFileSize(this, item.fileSize) else ""
        val timeFormatted = DateUtils.getRelativeTimeSpanString(item.downloadTime, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        menuBinding.tvMenuFileDetails.text = listOfNotNull(sizeFormatted.ifEmpty { null }, timeFormatted).joinToString(" • ")

        val isLocalData = item.url.startsWith("data:", ignoreCase = true) || item.url.startsWith("blob:", ignoreCase = true)
        val displaySiteUrl = when {
            item.url.isNotBlank() && !isLocalData -> item.url
            else -> "Locally generated / page data"
        }
        menuBinding.tvMenuSiteUrl.text = displaySiteUrl

        val fileExists = FileUtils.doesFileExist(item.filePath, this)
        if (!fileExists) {
            menuBinding.tvMenuStatusBadge.visibility = View.VISIBLE
            menuBinding.tvMenuStatusBadge.text = "File deleted from device"

            menuBinding.menuOpenOrInstall.visibility = View.GONE
            menuBinding.menuOpenInFolder.visibility = View.GONE
            menuBinding.menuShareFile.visibility = View.GONE
            menuBinding.menuRenameFile.visibility = View.GONE

            menuBinding.menuDownloadAgain.visibility = if (!isLocalData && item.url.isNotBlank()) View.VISIBLE else View.GONE
            menuBinding.tvMenuDeleteText.text = "Remove from list"
        } else {
            menuBinding.tvMenuStatusBadge.visibility = View.GONE

            menuBinding.menuOpenOrInstall.visibility = View.VISIBLE
            menuBinding.menuOpenInFolder.visibility = View.VISIBLE
            menuBinding.menuShareFile.visibility = View.VISIBLE
            menuBinding.menuRenameFile.visibility = View.VISIBLE

            menuBinding.menuDownloadAgain.visibility = if (!isLocalData && item.url.isNotBlank()) View.VISIBLE else View.GONE
            menuBinding.tvMenuDeleteText.text = "Delete"
        }

        menuBinding.menuOpenOrInstall.setOnClickListener {
            bottomSheet.dismiss()
            if (!FileUtils.doesFileExist(item.filePath, this)) {
                Toast.makeText(this, "File not found or deleted", Toast.LENGTH_SHORT).show()
                adapter.notifyDataSetChanged()
                return@setOnClickListener
            }
            openFile(item)
        }

        menuBinding.menuOpenInFolder.setOnClickListener {
            bottomSheet.dismiss()
            if (!FileUtils.doesFileExist(item.filePath, this)) {
                Toast.makeText(this, "File not found or deleted", Toast.LENGTH_SHORT).show()
                adapter.notifyDataSetChanged()
                return@setOnClickListener
            }
            openInFileManager(item)
        }

        menuBinding.menuShareFile.setOnClickListener {
            bottomSheet.dismiss()
            if (!FileUtils.doesFileExist(item.filePath, this)) {
                Toast.makeText(this, "File not found or deleted", Toast.LENGTH_SHORT).show()
                adapter.notifyDataSetChanged()
                return@setOnClickListener
            }
            shareDownloadedFile(item)
        }

        menuBinding.menuDownloadAgain.setOnClickListener {
            bottomSheet.dismiss()
            redownloadItem(item)
        }

        menuBinding.menuCopyLink.setOnClickListener {
            bottomSheet.dismiss()
            if (item.url.isBlank()) {
                Toast.makeText(this, "Download link not available", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            copyToClipboard("Download Link", item.url)
        }

        menuBinding.menuOpenSite.setOnClickListener {
            bottomSheet.dismiss()
            if (isLocalData || item.url.isBlank()) {
                Toast.makeText(this, "Original website not available for locally generated file", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            openOriginalSite(item)
        }

        menuBinding.menuRenameFile.setOnClickListener {
            bottomSheet.dismiss()
            if (!FileUtils.doesFileExist(item.filePath, this)) {
                Toast.makeText(this, "File not found or deleted", Toast.LENGTH_SHORT).show()
                adapter.notifyDataSetChanged()
                return@setOnClickListener
            }
            showRenameFileDialog(item)
        }

        menuBinding.menuViewDetails.setOnClickListener {
            bottomSheet.dismiss()
            showDownloadDetailsBottomSheet(item)
        }

        menuBinding.menuDeleteFile.setOnClickListener {
            bottomSheet.dismiss()
            if (!fileExists) {
                deleteDownloadItem(item)
            } else {
                confirmDeleteDownload(item)
            }
        }

        bottomSheet.show()
    }

    private fun openInFileManager(item: DownloadItem) {
        val resolvedPath = FileUtils.resolveExistingPath(item.filePath, this)
        if (resolvedPath == null) {
            Toast.makeText(this, "File not found or deleted", Toast.LENGTH_SHORT).show()
            adapter.notifyDataSetChanged()
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

        // 1. Try DownloadManager.ACTION_VIEW_DOWNLOADS
        try {
            val downloadsIntent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (downloadsIntent.resolveActivity(packageManager) != null) {
                startActivity(downloadsIntent)
                return
            }
        } catch (_: Exception) {}

        // 2. Try DocumentsUI / SAF with Downloads folder URI
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

        // 3. Try DocumentsUI / SAF root URI
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

        // 4. Try ACTION_VIEW with target directory FileProvider / file Uri and folder mime types
        try {
            val folderUri = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    FileProvider.getUriForFile(this, "${applicationContext.packageName}.fileprovider", targetDir)
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

            val resourceFolderIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(folderUri, "resource/folder")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (resourceFolderIntent.resolveActivity(packageManager) != null) {
                startActivity(resourceFolderIntent)
                return
            }
        } catch (_: Exception) {}

        // 5. Try launching known OEM / third-party File Manager packages directly
        val fileManagerPackages = listOf(
            "com.google.android.apps.nbu.files",       // Google Files
            "com.sec.android.app.myfiles",             // Samsung My Files
            "com.mi.android.globalFileexplorer",       // Xiaomi / Poco / Redmi
            "com.coloros.filemanager",                 // OPPO / Realme
            "com.oneplus.filemanager",                 // OnePlus
            "com.huawei.hidisk",                       // Huawei
            "com.lenovo.filebrowser",                  // Motorola / Lenovo
            "pl.solidexplorer2",                       // Solid Explorer
            "com.mixplorer",                           // MiXplorer
            "nextapp.fx",                              // FX File Explorer
            "ru.zdevs.zarchiver",                      // ZArchiver
            "com.ghisler.android.TotalCommander",      // Total Commander
            "com.google.android.documentsui",          // Google DocumentsUI
            "com.android.documentsui"                  // AOSP DocumentsUI
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

        // 6. Fallback: Intent.ACTION_GET_CONTENT chooser (opens system document / file picker)
        try {
            val pickerIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(Intent.createChooser(pickerIntent, "Open in file manager"))
            return
        } catch (_: Exception) {}

        // 7. Last resort: Open Storage Settings
        try {
            startActivity(Intent(android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot open file manager: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareDownloadedFile(item: DownloadItem) {
        val resolvedPath = FileUtils.resolveExistingPath(item.filePath, this)
        if (resolvedPath == null) {
            Toast.makeText(this, "File not found or deleted", Toast.LENGTH_SHORT).show()
            adapter.notifyDataSetChanged()
            return
        }

        try {
            val uri: Uri = if (resolvedPath.startsWith("content://")) {
                Uri.parse(resolvedPath)
            } else {
                val file = File(resolvedPath)
                if (file.exists()) {
                    FileProvider.getUriForFile(this, "${applicationContext.packageName}.fileprovider", file)
                } else {
                    Toast.makeText(this, "File not found or deleted", Toast.LENGTH_SHORT).show()
                    adapter.notifyDataSetChanged()
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

    private fun showRenameFileDialog(item: DownloadItem) {
        if (!FileUtils.doesFileExist(item.filePath, this)) {
            Toast.makeText(this, "File not found or deleted", Toast.LENGTH_SHORT).show()
            adapter.notifyDataSetChanged()
            return
        }

        val input = EditText(this).apply {
            setText(item.fileName)
            val dotIdx = item.fileName.lastIndexOf('.')
            if (dotIdx > 0) {
                setSelection(0, dotIdx)
            } else {
                selectAll()
            }
            setSingleLine(true)
        }

        val container = FrameLayout(this).apply {
            val padH = (24 * resources.displayMetrics.density).toInt()
            val padV = (8 * resources.displayMetrics.density).toInt()
            setPadding(padH, padV, padH, padV)
            addView(input)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Rename file")
            .setView(container)
            .setPositiveButton("Rename") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isBlank() || newName == item.fileName) return@setPositiveButton

                val illegalChars = Regex("[\\\\/:*?\"<>|]")
                if (illegalChars.containsMatchIn(newName)) {
                    Toast.makeText(this, "Invalid characters in file name", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
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
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteDownload(item: DownloadItem) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Download")
            .setMessage("Are you sure you want to delete \"${item.fileName}\"? The file will be permanently deleted from device storage and download history.")
            .setPositiveButton("Delete") { _, _ ->
                deleteDownloadItem(item)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteDownloadItem(item: DownloadItem) {
        com.onyx.browser.download.OnyxDownloadManager.cancelDownload(item.id)
        lifecycleScope.launch(Dispatchers.IO) {
            // 1. Delete physical file from storage
            try {
                val resolvedPath = FileUtils.resolveExistingPath(item.filePath, this@DownloadsActivity) ?: item.filePath
                if (resolvedPath.startsWith("content://")) {
                    contentResolver.delete(Uri.parse(resolvedPath), null, null)
                } else {
                    val file = File(resolvedPath)
                    if (file.exists()) {
                        file.delete()
                    }
                }
            } catch (_: Exception) {}

            // 2. Delete cache temp files if any
            try {
                val tempDir = File(cacheDir, "onyx_downloads")
                File(tempDir, "task_${item.id}.part").delete()
                File(tempDir, "task_${item.id}.part.chunks").delete()
            } catch (_: Exception) {}

            // 3. Delete from database
            database.downloadDao().deleteDownload(item)
            database.downloadDao().deleteById(item.id)

            withContext(Dispatchers.Main) {
                Toast.makeText(this@DownloadsActivity, "Deleted ${item.fileName}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        if (text.isBlank()) return
        val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clip.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "$label copied", Toast.LENGTH_SHORT).show()
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

    private fun showDownloadDetailsBottomSheet(item: DownloadItem) {
        val bottomSheet = BottomSheetDialog(this)
        val detailsBinding = BottomSheetDownloadDetailsBinding.inflate(layoutInflater)
        bottomSheet.setContentView(detailsBinding.root)

        val isApk = ApkInstallerHelper.isApkFile(item.fileName, item.mimeType)
        val lowerName = item.fileName.lowercase()
        when {
            isApk -> detailsBinding.ivDetailsFileIcon.setImageResource(R.drawable.ic_android)
            lowerName.endsWith(".mht") || lowerName.endsWith(".mhtml") ||
            lowerName.endsWith(".html") || lowerName.endsWith(".htm") ||
            item.mimeType.contains("html") || item.mimeType.contains("multipart") -> {
                detailsBinding.ivDetailsFileIcon.setImageResource(R.drawable.ic_web)
            }
            lowerName.endsWith(".png") || lowerName.endsWith(".jpg") ||
            lowerName.endsWith(".jpeg") || lowerName.endsWith(".webp") ||
            lowerName.endsWith(".gif") || item.mimeType.startsWith("image/") -> {
                detailsBinding.ivDetailsFileIcon.setImageResource(R.drawable.ic_image)
            }
            lowerName.endsWith(".mp4") || lowerName.endsWith(".mkv") ||
            lowerName.endsWith(".webm") || item.mimeType.startsWith("video/") -> {
                detailsBinding.ivDetailsFileIcon.setImageResource(R.drawable.ic_play_circle)
            }
            else -> detailsBinding.ivDetailsFileIcon.setImageResource(R.drawable.ic_file)
        }

        detailsBinding.tvDetailsHeaderTitle.text = "File Details"
        detailsBinding.tvDetailsHeaderSubtitle.text = item.fileName
        detailsBinding.btnDetailsClose.setOnClickListener { bottomSheet.dismiss() }

        detailsBinding.tvDetailFileName.text = item.fileName
        detailsBinding.btnCopyFileName.setOnClickListener {
            copyToClipboard("File Name", item.fileName)
        }

        val sizeFormatted = if (item.fileSize > 0) {
            "${Formatter.formatFileSize(this, item.fileSize)} (${item.fileSize} bytes)"
        } else {
            "Unknown size"
        }
        detailsBinding.tvDetailFileSize.text = sizeFormatted

        val fileExists = FileUtils.doesFileExist(item.filePath, this)
        if (fileExists) {
            detailsBinding.tvDetailFileStatus.text = "Available on storage"
            detailsBinding.tvDetailFileStatus.setTextColor(ContextCompat.getColor(this, R.color.green_success))
        } else {
            detailsBinding.tvDetailFileStatus.text = "File deleted or moved"
            detailsBinding.tvDetailFileStatus.setTextColor(ContextCompat.getColor(this, R.color.red_danger))
        }

        val dateFormatted = try {
            val sdf = SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.getDefault())
            sdf.format(Date(item.downloadTime))
        } catch (_: Exception) {
            DateUtils.getRelativeTimeSpanString(item.downloadTime, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
        }
        detailsBinding.tvDetailFileDate.text = dateFormatted

        detailsBinding.tvDetailFilePath.text = item.filePath
        detailsBinding.btnCopyFilePath.setOnClickListener {
            copyToClipboard("File Path", item.filePath)
        }

        val isLocalData = item.url.startsWith("data:", ignoreCase = true) || item.url.startsWith("blob:", ignoreCase = true)
        val displayUrl = when {
            item.url.isNotBlank() && !isLocalData -> item.url
            item.url.isNotBlank() && isLocalData -> "Locally generated data"
            else -> "Not available"
        }
        detailsBinding.tvDetailFileUrl.text = displayUrl
        detailsBinding.btnCopyFileUrl.visibility = if (!isLocalData && item.url.isNotBlank()) View.VISIBLE else View.GONE
        detailsBinding.btnCopyFileUrl.setOnClickListener {
            copyToClipboard("Download URL", item.url)
        }

        var currentSha256 = item.sha256
        var currentMd5 = item.md5

        fun updateChecksumViews() {
            if (currentSha256.isNotBlank()) {
                detailsBinding.containerSha256.visibility = View.VISIBLE
                detailsBinding.tvDetailSha256.text = currentSha256
                detailsBinding.btnCopySha256.setOnClickListener {
                    copyToClipboard("SHA-256", currentSha256)
                }
            } else {
                detailsBinding.containerSha256.visibility = View.GONE
            }

            if (currentMd5.isNotBlank()) {
                detailsBinding.containerMd5.visibility = View.VISIBLE
                detailsBinding.tvDetailMd5.text = currentMd5
                detailsBinding.btnCopyMd5.setOnClickListener {
                    copyToClipboard("MD5", currentMd5)
                }
            } else {
                detailsBinding.containerMd5.visibility = View.GONE
            }

            if (currentSha256.isBlank() && currentMd5.isBlank()) {
                if (!fileExists) {
                    detailsBinding.tvChecksumStatus.text = "Checksums unavailable (file not present on device)"
                    detailsBinding.containerVerifyInput.visibility = View.GONE
                    detailsBinding.tvVerifyHeader.visibility = View.GONE
                    detailsBinding.dividerVerify.visibility = View.GONE
                } else {
                    detailsBinding.tvChecksumStatus.text = "Calculating cryptographic checksums..."
                }
            } else {
                detailsBinding.tvChecksumStatus.text = "Cryptographic hashes for verifying file authenticity"
                detailsBinding.containerVerifyInput.visibility = View.VISIBLE
                detailsBinding.tvVerifyHeader.visibility = View.VISIBLE
                detailsBinding.dividerVerify.visibility = View.VISIBLE
            }
        }

        updateChecksumViews()

        if (fileExists && (currentSha256.isBlank() || currentMd5.isBlank())) {
            lifecycleScope.launch {
                val computed = computeFileChecksums(item.filePath)
                if (computed != null && bottomSheet.isShowing) {
                    currentSha256 = computed.first
                    currentMd5 = computed.second
                    updateChecksumViews()

                    withContext(Dispatchers.IO) {
                        try {
                            val updated = item.copy(sha256 = currentSha256, md5 = currentMd5)
                            database.downloadDao().updateDownload(updated)
                        } catch (_: Exception) {}
                    }
                }
            }
        }

        detailsBinding.btnClearVerify.setOnClickListener {
            detailsBinding.etVerifyChecksum.text?.clear()
        }

        detailsBinding.etVerifyChecksum.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val input = s?.toString()?.trim() ?: ""
                detailsBinding.btnClearVerify.visibility = if (input.isNotEmpty()) View.VISIBLE else View.GONE
                if (input.isBlank()) {
                    detailsBinding.tvVerifyResult.visibility = View.GONE
                } else if (input.equals(currentSha256, ignoreCase = true) || input.equals(currentMd5, ignoreCase = true)) {
                    detailsBinding.tvVerifyResult.visibility = View.VISIBLE
                    detailsBinding.tvVerifyResult.text = "✓ Checksum MATCHES successfully"
                    detailsBinding.tvVerifyResult.setTextColor(ContextCompat.getColor(this@DownloadsActivity, R.color.green_success))
                    detailsBinding.tvVerifyResult.setBackgroundResource(R.drawable.bg_pill_badge)
                } else {
                    detailsBinding.tvVerifyResult.visibility = View.VISIBLE
                    detailsBinding.tvVerifyResult.text = "✗ Checksum MISMATCH — hash does not match"
                    detailsBinding.tvVerifyResult.setTextColor(ContextCompat.getColor(this@DownloadsActivity, R.color.red_danger))
                    detailsBinding.tvVerifyResult.setBackgroundResource(R.drawable.bg_pill_badge)
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        if (!isLocalData && item.url.isNotBlank()) {
            detailsBinding.btnDetailsShareLink.visibility = View.VISIBLE
            detailsBinding.btnDetailsShareLink.setOnClickListener {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, item.url)
                }
                startActivity(Intent.createChooser(shareIntent, "Share Download Link"))
            }
        } else {
            detailsBinding.btnDetailsShareLink.visibility = View.GONE
        }

        detailsBinding.btnDetailsDone.setOnClickListener {
            bottomSheet.dismiss()
        }

        bottomSheet.show()
    }

    private fun showDownloadDetailsDialog(item: DownloadItem) {
        showDownloadDetailsBottomSheet(item)
    }
}
