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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.onyx.browser.R
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.download.DownloadHandler
import com.onyx.browser.ui.theme.AccentColorManager
import com.onyx.browser.ui.theme.OnyxTheme
import com.onyx.browser.ui.theme.SuccessGreenDark
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class DownloadPromptBottomSheet : BottomSheetDialogFragment() {

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
            if (isAdded) {
                Toast.makeText(requireContext(), "Storage permission required to download files", Toast.LENGTH_SHORT).show()
            }
        }
        pendingDownloadAction = null
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.Theme_OnyxBrowser_BottomSheetDialog)
        arguments?.let {
            fileUrl = it.getString(ARG_URL, "")
            userAgent = it.getString(ARG_USER_AGENT, "")
            contentDisposition = it.getString(ARG_CONTENT_DISPOSITION, "")
            mimeType = it.getString(ARG_MIME_TYPE, "")
            contentLength = it.getLong(ARG_CONTENT_LENGTH, 0L)
            cookies = it.getString(ARG_COOKIES, "")
            referer = it.getString(ARG_REFERER, "")
        }

        initialFileName = DownloadHandler.guessResolvedFileName(fileUrl, contentDisposition, mimeType, referer)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
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
                    DownloadPromptContent()
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        checkNotificationPermission()
    }

    @Composable
    private fun DownloadPromptContent() {
        val context = LocalContext.current
        var fileName by remember { mutableStateOf(initialFileName) }

        val ext = remember(fileName) {
            fileName.substringAfterLast('.', "").uppercase().ifBlank { "FILE" }
        }

        val sizeText = remember(contentLength) {
            if (contentLength > 0) {
                Formatter.formatFileSize(context, contentLength)
            } else {
                context.getString(R.string.file_size_unknown)
            }
        }

        val resolvedMime = remember(fileName, mimeType) {
            val m = com.onyx.browser.download.FileUtils.resolveMimeTypeForDownload(fileName, mimeType)
            if (m.isNotBlank()) m else "application/octet-stream"
        }

        val isBlobOrData = fileUrl.startsWith("data:", ignoreCase = true) || fileUrl.startsWith("blob:", ignoreCase = true)
        val displayUrl = if (isBlobOrData) referer.ifBlank { "Locally generated data" } else fileUrl
        val displayHost = remember(displayUrl) {
            try {
                if (displayUrl.isNotBlank()) Uri.parse(displayUrl).host ?: displayUrl else "Locally generated"
            } catch (_: Exception) {
                displayUrl
            }
        }

        Surface(
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 24.dp)
                    .navigationBarsPadding()
            ) {
                // Drag handle indicator
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 14.dp)
                        .size(width = 36.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )

                // Header: Title, Circular Icon & Close Button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_download),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.onyx_downloader_title),
                            style = MaterialTheme.typography.titleMedium.copy(fontSize = 19.sp),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.download_prompt_title),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(
                        onClick = { dismiss() },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = stringResource(R.string.close),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Card 1: File Details
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    elevation = CardDefaults.cardElevation(0.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp)
                    ) {
                        // File Extension Badge and Name Input Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Extension Badge
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surface)
                                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = ext.take(4),
                                    style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp),
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            // Editable File Name Field with Clear Button
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surface)
                                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                                    .padding(start = 12.dp, end = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                BasicTextField(
                                    value = fileName,
                                    onValueChange = { fileName = it },
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                                        fontSize = 14.5.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    ),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    modifier = Modifier.weight(1f),
                                    decorationBox = { innerTextField ->
                                        Box(contentAlignment = Alignment.CenterStart) {
                                            if (fileName.isEmpty()) {
                                                Text(
                                                    text = "File name",
                                                    style = MaterialTheme.typography.bodyMedium.copy(
                                                        fontSize = 14.5.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                                    )
                                                )
                                            }
                                            innerTextField()
                                        }
                                    }
                                )

                                if (fileName.isNotEmpty()) {
                                    IconButton(
                                        onClick = { fileName = "" },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_close),
                                            contentDescription = stringResource(R.string.clear_search),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Metadata Row: Size & MIME type
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // File Size Pill
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surface)
                                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = sizeText,
                                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            Text(
                                text = resolvedMime,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .padding(start = 10.dp)
                                    .weight(1f)
                            )
                        }
                    }
                }

                // Card 2: Website & Security Details
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    elevation = CardDefaults.cardElevation(0.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp)
                    ) {
                        // Domain Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_web),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )

                            Text(
                                text = displayHost,
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .padding(start = 8.dp)
                                    .weight(1f)
                            )

                            IconButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("URL", displayUrl)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(context, R.string.url_copied, Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_copy),
                                    contentDescription = stringResource(R.string.copy_url),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        // URL Preview
                        Text(
                            text = displayUrl,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp)
                        )

                        // Forwarded Cookies & Headers Status Badge
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_shield_lock),
                                contentDescription = null,
                                tint = SuccessGreenDark,
                                modifier = Modifier.size(16.dp)
                            )

                            Text(
                                text = stringResource(R.string.forwarding_cookies_headers),
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }

                // Action Buttons: Download & External Downloader
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 18.dp)
                ) {
                    // 1. Download Button (Default Downloader)
                    Button(
                        onClick = {
                            val finalFileName = fileName.trim().ifBlank { initialFileName }
                            startDownload(finalFileName)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_download),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.download_button),
                                style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // 2. External Downloader Button (1DM, 1DM+, ADM, FDM)
                    if (!isBlobOrData) {
                        OutlinedButton(
                            onClick = {
                                val finalFileName = fileName.trim().ifBlank { initialFileName }
                                handleExternalDownload(finalFileName)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .padding(top = 10.dp),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = androidx.compose.ui.graphics.Color.Transparent,
                                contentColor = MaterialTheme.colorScheme.onSurface
                            )
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_external),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.external_downloader_button),
                                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    // 3. Cancel Button
                    TextButton(
                        onClick = { dismiss() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .padding(top = 6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.cancel),
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.5.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    private fun startDownload(finalFileName: String) {
        if (fileUrl.startsWith("data:", ignoreCase = true)) {
            DownloadHandler.handleDataUriDownload(
                context = requireContext().applicationContext,
                coroutineScope = downloadScope,
                dataUri = fileUrl,
                contentDisposition = contentDisposition,
                mimeType = mimeType,
                suggestedFileName = finalFileName,
                originalPageUrl = referer
            )
            dismiss()
            return
        }

        if (fileUrl.startsWith("blob:", ignoreCase = true)) {
            val mainAct = (activity as? com.onyx.browser.MainActivity) ?: com.onyx.browser.MainActivity.currentInstance?.get()
            if (mainAct != null) {
                DownloadHandler.handleBlobUriDownload(
                    activity = mainAct,
                    coroutineScope = downloadScope,
                    blobUrl = fileUrl,
                    contentDisposition = contentDisposition,
                    mimeType = mimeType,
                    referer = referer,
                    suggestedFileName = finalFileName
                )
            } else {
                Toast.makeText(requireContext(), "Cannot download blob without active webpage", Toast.LENGTH_SHORT).show()
            }
            dismiss()
            return
        }

        checkStorageAndDownload {
            val targetMime = com.onyx.browser.download.FileUtils.resolveMimeTypeForDownload(finalFileName, mimeType)
            DownloadHandler.startSystemDownload(
                context = requireContext().applicationContext,
                coroutineScope = downloadScope,
                url = fileUrl,
                userAgent = userAgent,
                fileName = finalFileName,
                mimeType = targetMime,
                contentLength = contentLength,
                cookies = cookies,
                referer = referer
            )
            dismiss()
        }
    }

    private fun handleExternalDownload(finalFileName: String) {
        val targetMime = com.onyx.browser.download.FileUtils.resolveMimeTypeForDownload(finalFileName, mimeType)
        val downloaders = ExternalDownloaderHelper.getInstalledDownloaders(requireContext(), fileUrl, targetMime)
        when {
            downloaders.isEmpty() -> {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.no_external_downloader_found)
                    .setMessage(R.string.no_external_downloader_message)
                    .setPositiveButton(R.string.get_from_play_store) { _, _ ->
                        ExternalDownloaderHelper.openPlayStoreForDownloaders(requireContext())
                    }
                    .setNeutralButton(R.string.use_default_downloader) { _, _ ->
                        checkStorageAndDownload {
                            DownloadHandler.startSystemDownload(
                                context = requireContext().applicationContext,
                                coroutineScope = downloadScope,
                                url = fileUrl,
                                userAgent = userAgent,
                                fileName = finalFileName,
                                mimeType = targetMime,
                                contentLength = contentLength,
                                cookies = cookies,
                                referer = referer
                            )
                            dismiss()
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            downloaders.size == 1 -> {
                val single = downloaders.first()
                launchExternalDownloader(single, finalFileName)
            }
            else -> {
                val sheet = SelectDownloaderBottomSheet(downloaders) { selected ->
                    launchExternalDownloader(selected, finalFileName)
                }
                sheet.show(parentFragmentManager, SelectDownloaderBottomSheet.TAG)
            }
        }
    }

    private fun launchExternalDownloader(downloader: ExternalDownloaderInfo, fileName: String) {
        val targetMime = com.onyx.browser.download.FileUtils.resolveMimeTypeForDownload(fileName, mimeType)
        val intent = ExternalDownloaderHelper.buildDownloadIntent(
            downloader = downloader,
            url = fileUrl,
            fileName = fileName,
            userAgent = userAgent,
            cookies = cookies,
            referer = referer,
            mimeType = targetMime
        )
        try {
            startActivity(intent)
            Toast.makeText(requireContext(), "Opening in ${downloader.name}", Toast.LENGTH_SHORT).show()
            dismiss()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Failed to launch ${downloader.name}: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun checkStorageAndDownload(action: () -> Unit) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                pendingDownloadAction = action
                storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                return
            }
        }
        action()
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    companion object {
        private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        const val TAG = "DownloadPromptBottomSheet"

        private const val ARG_URL = "arg_url"
        private const val ARG_USER_AGENT = "arg_user_agent"
        private const val ARG_CONTENT_DISPOSITION = "arg_content_disposition"
        private const val ARG_MIME_TYPE = "arg_mime_type"
        private const val ARG_CONTENT_LENGTH = "arg_content_length"
        private const val ARG_COOKIES = "arg_cookies"
        private const val ARG_REFERER = "arg_referer"

        fun newInstance(
            url: String,
            userAgent: String,
            contentDisposition: String,
            mimeType: String,
            contentLength: Long,
            cookies: String,
            referer: String
        ): DownloadPromptBottomSheet {
            val fragment = DownloadPromptBottomSheet()
            val args = Bundle().apply {
                putString(ARG_URL, url)
                putString(ARG_USER_AGENT, userAgent)
                putString(ARG_CONTENT_DISPOSITION, contentDisposition)
                putString(ARG_MIME_TYPE, mimeType)
                putLong(ARG_CONTENT_LENGTH, contentLength)
                putString(ARG_COOKIES, cookies)
                putString(ARG_REFERER, referer)
            }
            fragment.arguments = args
            return fragment
        }
    }
}
