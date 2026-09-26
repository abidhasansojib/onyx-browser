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
import android.webkit.URLUtil
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.onyx.browser.R
import com.onyx.browser.databinding.BottomSheetDownloadPromptBinding
import com.onyx.browser.web.DownloadHandler

class DownloadPromptBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetDownloadPromptBinding? = null
    private val binding get() = _binding!!

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

        initialFileName = DownloadHandler.guessResolvedFileName(fileUrl, contentDisposition, mimeType)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetDownloadPromptBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupUI()
        checkNotificationPermission()
    }

    private fun setupUI() {
        // Close Button
        binding.btnClose.setOnClickListener {
            dismiss()
        }

        // File Details
        binding.etFileName.setText(initialFileName)

        val ext = initialFileName.substringAfterLast('.', "").uppercase()
        binding.tvFileExtension.text = if (ext.isNotBlank()) ext else "FILE"

        val sizeText = if (contentLength > 0) {
            Formatter.formatFileSize(requireContext(), contentLength)
        } else {
            getString(R.string.file_size_unknown)
        }
        binding.tvFileSize.text = sizeText

        binding.tvMimeType.text = if (mimeType.isNotBlank()) mimeType else "application/octet-stream"

        binding.btnClearFileName.setOnClickListener {
            binding.etFileName.text?.clear()
            binding.etFileName.requestFocus()
        }

        // Website Details
        val host = try {
            Uri.parse(fileUrl).host ?: fileUrl
        } catch (_: Exception) {
            fileUrl
        }
        binding.tvDomain.text = host
        binding.tvUrl.text = fileUrl

        binding.btnCopyUrl.setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("URL", fileUrl)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(requireContext(), R.string.url_copied, Toast.LENGTH_SHORT).show()
        }

        // Action 1: Default Downloader
        binding.btnDownloadDefault.setOnClickListener {
            val chosenName = binding.etFileName.text?.toString()?.trim() ?: initialFileName
            val finalFileName = if (chosenName.isNotBlank()) chosenName else initialFileName

            checkStorageAndDownload {
                DownloadHandler.startSystemDownload(
                    context = requireContext().applicationContext,
                    coroutineScope = downloadScope,
                    url = fileUrl,
                    userAgent = userAgent,
                    fileName = finalFileName,
                    mimeType = mimeType,
                    contentLength = contentLength,
                    cookies = cookies,
                    referer = referer
                )
                dismiss()
            }
        }

        // Action 2: External Downloader (1DM, 1DM+, ADM, FDM, etc.)
        binding.btnDownloadExternal.setOnClickListener {
            val chosenName = binding.etFileName.text?.toString()?.trim() ?: initialFileName
            val finalFileName = if (chosenName.isNotBlank()) chosenName else initialFileName

            val downloaders = ExternalDownloaderHelper.getInstalledDownloaders(requireContext(), fileUrl, mimeType)
            when {
                downloaders.isEmpty() -> {
                    // No external downloader found -> Show option to install or use default
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
                                    mimeType = mimeType,
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
                    // Exactly ONE is available -> Use it directly without asking!
                    val single = downloaders.first()
                    launchExternalDownloader(single, finalFileName)
                }
                else -> {
                    // Multiple available -> Give option to select between them!
                    val sheet = SelectDownloaderBottomSheet(downloaders) { selected ->
                        launchExternalDownloader(selected, finalFileName)
                    }
                    sheet.show(parentFragmentManager, SelectDownloaderBottomSheet.TAG)
                }
            }
        }

        // Action 3: Cancel
        binding.btnCancel.setOnClickListener {
            dismiss()
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
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
