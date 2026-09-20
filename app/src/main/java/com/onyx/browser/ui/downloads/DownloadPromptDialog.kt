package com.onyx.browser.ui.downloads

import android.net.Uri
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import com.onyx.browser.R
import com.onyx.browser.databinding.DialogDownloadPromptBinding

class DownloadPromptDialog : DialogFragment() {

    private var _binding: DialogDownloadPromptBinding? = null
    private val binding get() = _binding!!

    var onDownloadConfirmed: ((String) -> Unit)? = null
    var onExternalDownloadRequested: (() -> Unit)? = null

    private var fileUrl: String = ""
    private var initialFileName: String = ""
    private var fileSize: Long = 0L
    private var mimeType: String = ""
    private var userAgent: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, R.style.Theme_OnyxBrowser_BottomSheetDialog)

        arguments?.let {
            fileUrl = it.getString(ARG_URL, "")
            initialFileName = it.getString(ARG_FILE_NAME, "")
            fileSize = it.getLong(ARG_FILE_SIZE, 0L)
            mimeType = it.getString(ARG_MIME_TYPE, "")
            userAgent = it.getString(ARG_USER_AGENT, "")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogDownloadPromptBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.etFileName.setText(initialFileName)

        val sizeFormatted = if (fileSize > 0) {
            Formatter.formatFileSize(requireContext(), fileSize)
        } else {
            getString(R.string.file_size_unknown)
        }
        binding.tvFileSize.text = sizeFormatted

        val domain = try {
            Uri.parse(fileUrl).host ?: fileUrl
        } catch (e: Exception) {
            fileUrl
        }
        binding.tvFileDomain.text = domain

        // Button 1: Download with internal downloader
        binding.btnDownloadInternal.setOnClickListener {
            val fileName = binding.etFileName.text?.toString()?.trim() ?: initialFileName
            onDownloadConfirmed?.invoke(fileName.ifEmpty { initialFileName })
            dismiss()
        }

        // Button 2: External Downloader
        binding.btnDownloadExternal.setOnClickListener {
            onExternalDownloadRequested?.invoke()
            dismiss()
        }

        // Cancel
        binding.btnCancelDownload.setOnClickListener {
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_URL = "url"
        private const val ARG_FILE_NAME = "file_name"
        private const val ARG_FILE_SIZE = "file_size"
        private const val ARG_MIME_TYPE = "mime_type"
        private const val ARG_USER_AGENT = "user_agent"

        fun newInstance(
            url: String,
            fileName: String,
            fileSize: Long,
            mimeType: String,
            userAgent: String
        ): DownloadPromptDialog {
            return DownloadPromptDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_URL, url)
                    putString(ARG_FILE_NAME, fileName)
                    putLong(ARG_FILE_SIZE, fileSize)
                    putString(ARG_MIME_TYPE, mimeType)
                    putString(ARG_USER_AGENT, userAgent)
                }
            }
        }
    }
}
