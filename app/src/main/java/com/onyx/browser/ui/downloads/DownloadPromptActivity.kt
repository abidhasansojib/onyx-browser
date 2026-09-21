package com.onyx.browser.ui.downloads

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import android.webkit.URLUtil
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.onyx.browser.R
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.databinding.ActivityDownloadPromptBinding
import com.onyx.browser.web.DownloadHandler

class DownloadPromptActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDownloadPromptBinding
    private lateinit var preferences: BrowserPreferences

    private var fileUrl: String = ""
    private var userAgent: String = ""
    private var contentDisposition: String = ""
    private var mimeType: String = ""
    private var contentLength: Long = 0L
    private var cookies: String = ""
    private var referer: String = ""
    private var initialFileName: String = ""

    private var pendingDownloadAction: (() -> Unit)? = null
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            pendingDownloadAction?.invoke()
        } else {
            Toast.makeText(this, "Storage permission required to download files", Toast.LENGTH_SHORT).show()
        }
        pendingDownloadAction = null
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = BrowserPreferences.getInstance(this)
        preferences.applyTheme()

        binding = ActivityDownloadPromptBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Edge-to-edge system insets handling
        ViewCompat.setOnApplyWindowInsetsListener(binding.downloadRoot) { _, windowInsets ->
            val statusBarInsets = windowInsets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val navBarInsets = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())

            binding.topBar.setPadding(
                binding.topBar.paddingLeft,
                statusBarInsets.top,
                binding.topBar.paddingRight,
                binding.topBar.paddingBottom
            )

            binding.scrollView.setPadding(
                binding.scrollView.paddingLeft,
                binding.scrollView.paddingTop,
                binding.scrollView.paddingRight,
                navBarInsets.bottom
            )
            windowInsets
        }

        extractIntentData()
        setupUI()
        checkNotificationPermission()
    }

    private fun extractIntentData() {
        intent?.let {
            fileUrl = it.getStringExtra(EXTRA_URL) ?: ""
            userAgent = it.getStringExtra(EXTRA_USER_AGENT) ?: ""
            contentDisposition = it.getStringExtra(EXTRA_CONTENT_DISPOSITION) ?: ""
            mimeType = it.getStringExtra(EXTRA_MIME_TYPE) ?: ""
            contentLength = it.getLongExtra(EXTRA_CONTENT_LENGTH, 0L)
            cookies = it.getStringExtra(EXTRA_COOKIES) ?: ""
            referer = it.getStringExtra(EXTRA_REFERER) ?: ""
        }

        initialFileName = URLUtil.guessFileName(fileUrl, contentDisposition, mimeType)
    }

    private fun setupUI() {
        // Back Button
        binding.btnBack.setOnClickListener {
            finish()
        }

        // File Details
        binding.etFileName.setText(initialFileName)

        val ext = initialFileName.substringAfterLast('.', "").uppercase()
        binding.tvFileExtension.text = if (ext.isNotBlank()) ext else "FILE"

        val sizeText = if (contentLength > 0) {
            Formatter.formatFileSize(this, contentLength)
        } else {
            getString(R.string.file_size_unknown)
        }
        binding.tvFileSize.text = sizeText

        binding.tvMimeType.text = if (mimeType.isNotBlank()) mimeType else "application/octet-stream"

        // Website Details
        val host = try {
            Uri.parse(fileUrl).host ?: fileUrl
        } catch (_: Exception) {
            fileUrl
        }
        binding.tvDomain.text = host
        binding.tvUrl.text = fileUrl

        binding.btnCopyUrl.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("URL", fileUrl)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, R.string.url_copied, Toast.LENGTH_SHORT).show()
        }

        // Action 1: Default Downloader
        binding.btnDownloadDefault.setOnClickListener {
            val chosenName = binding.etFileName.text?.toString()?.trim() ?: initialFileName
            val finalFileName = if (chosenName.isNotBlank()) chosenName else initialFileName

            checkStorageAndDownload {
                DownloadHandler.startSystemDownload(
                    context = this,
                    coroutineScope = lifecycleScope,
                    url = fileUrl,
                    userAgent = userAgent,
                    fileName = finalFileName,
                    mimeType = mimeType,
                    contentLength = contentLength,
                    cookies = cookies,
                    referer = referer
                )
                finish()
            }
        }

        // Action 2: External Downloader (1DM, 1DM+, ADM, FDM, etc.)
        binding.btnDownloadExternal.setOnClickListener {
            val chosenName = binding.etFileName.text?.toString()?.trim() ?: initialFileName
            val finalFileName = if (chosenName.isNotBlank()) chosenName else initialFileName

            val downloaders = ExternalDownloaderHelper.getInstalledDownloaders(this, fileUrl, mimeType)
            when {
                downloaders.isEmpty() -> {
                    // No external downloader found -> Show option to install or use default
                    MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.no_external_downloader_found)
                        .setMessage(R.string.no_external_downloader_message)
                        .setPositiveButton(R.string.get_from_play_store) { _, _ ->
                            ExternalDownloaderHelper.openPlayStoreForDownloaders(this)
                        }
                        .setNeutralButton(R.string.use_default_downloader) { _, _ ->
                            checkStorageAndDownload {
                                DownloadHandler.startSystemDownload(
                                    context = this,
                                    coroutineScope = lifecycleScope,
                                    url = fileUrl,
                                    userAgent = userAgent,
                                    fileName = finalFileName,
                                    mimeType = mimeType,
                                    contentLength = contentLength,
                                    cookies = cookies,
                                    referer = referer
                                )
                                finish()
                            }
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
                downloaders.size == 1 -> {
                    // Exactly ONE is available -> Use it directly without asking!
                    val single = downloaders.first()
                    launchExternalDownloader(single, finalFileName)
                }
                else -> {
                    // Multiple available -> Give option to select between them!
                    val sheet = SelectDownloaderBottomSheet(downloaders) { selected ->
                        launchExternalDownloader(selected, finalFileName)
                    }
                    sheet.show(supportFragmentManager, SelectDownloaderBottomSheet.TAG)
                }
            }
        }

        // Action 3: Cancel
        binding.btnCancel.setOnClickListener {
            finish()
        }
    }

    private fun launchExternalDownloader(downloader: ExternalDownloaderInfo, fileName: String) {
        val intent = ExternalDownloaderHelper.buildDownloadIntent(
            downloader = downloader,
            url = fileUrl,
            fileName = fileName,
            userAgent = userAgent,
            cookies = cookies,
            referer = referer,
            mimeType = mimeType
        )
        try {
            startActivity(intent)
            Toast.makeText(this, "Opening in ${downloader.name}", Toast.LENGTH_SHORT).show()
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to launch ${downloader.name}: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun checkStorageAndDownload(action: () -> Unit) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                pendingDownloadAction = action
                storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                return
            }
        }
        action()
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_USER_AGENT = "extra_user_agent"
        const val EXTRA_CONTENT_DISPOSITION = "extra_content_disposition"
        const val EXTRA_MIME_TYPE = "extra_mime_type"
        const val EXTRA_CONTENT_LENGTH = "extra_content_length"
        const val EXTRA_COOKIES = "extra_cookies"
        const val EXTRA_REFERER = "extra_referer"
    }
}
