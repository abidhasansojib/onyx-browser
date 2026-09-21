package com.onyx.browser.ui.menu

import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.os.bundleOf
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.onyx.browser.R
import com.onyx.browser.databinding.BottomSheetContextMenuBinding

class ContextMenuBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetContextMenuBinding? = null
    private val binding get() = _binding!!

    private var onOpenInNewTab: ((String) -> Unit)? = null

    fun setOnOpenInNewTab(cb: (String) -> Unit) { onOpenInNewTab = cb }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _binding = BottomSheetContextMenuBinding.inflate(i, c, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val url = arguments?.getString(ARG_URL)
        val imageUrl = arguments?.getString(ARG_IMAGE_URL)
        val linkText = arguments?.getString(ARG_LINK_TEXT)
        val isImage = !imageUrl.isNullOrBlank()
        val isLink = !url.isNullOrBlank()

        // Header: show URL or image URL truncated
        binding.tvContextUrl.text = (url ?: imageUrl ?: "").let {
            if (it.length > 60) it.take(57) + "…" else it
        }

        // Open in new tab
        val targetUrl = url ?: imageUrl
        if (!targetUrl.isNullOrBlank()) {
            binding.itemOpenNewTab.visibility = View.VISIBLE
            binding.itemOpenNewTab.setOnClickListener {
                onOpenInNewTab?.invoke(targetUrl)
                dismiss()
            }
        } else {
            binding.itemOpenNewTab.visibility = View.GONE
        }

        // Copy link
        if (isLink) {
            binding.itemCopyLink.visibility = View.VISIBLE
            binding.itemCopyLink.setOnClickListener {
                copyToClipboard(requireContext(), "Link", url!!)
                dismiss()
            }
        } else {
            binding.itemCopyLink.visibility = View.GONE
        }

        // Copy link text
        if (!linkText.isNullOrBlank()) {
            binding.itemCopyLinkText.visibility = View.VISIBLE
            binding.itemCopyLinkText.setOnClickListener {
                copyToClipboard(requireContext(), "Link text", linkText)
                dismiss()
            }
        } else {
            binding.itemCopyLinkText.visibility = View.GONE
        }

        // Save image
        if (isImage) {
            binding.itemSaveImage.visibility = View.VISIBLE
            binding.itemSaveImage.setOnClickListener {
                downloadMedia(imageUrl!!, "image")
                dismiss()
            }
        } else {
            binding.itemSaveImage.visibility = View.GONE
        }

        // Save video (show for video URLs)
        val isVideoUrl = url?.let {
            it.contains(".mp4") || it.contains(".webm") || it.contains(".m3u8") || it.contains("video")
        } == true
        if (isVideoUrl) {
            binding.itemSaveVideo.visibility = View.VISIBLE
            binding.itemSaveVideo.setOnClickListener {
                downloadMedia(url!!, "video")
                dismiss()
            }
        } else {
            binding.itemSaveVideo.visibility = View.GONE
        }

        binding.btnContextClose.setOnClickListener { dismiss() }
    }

    private fun copyToClipboard(ctx: Context, label: String, text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(ctx, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun downloadMedia(url: String, type: String) {
        try {
            val ctx = requireContext()
            val filename = Uri.parse(url).lastPathSegment ?: "${type}_${System.currentTimeMillis()}"
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(filename)
                .setDescription("Downloading $type")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(
                    if (type == "image") Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_MOVIES,
                    filename
                )
                .setAllowedOverMetered(true)
            val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(ctx, "Saving $type…", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Failed to save", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "ContextMenuBottomSheet"
        private const val ARG_URL = "url"
        private const val ARG_IMAGE_URL = "image_url"
        private const val ARG_LINK_TEXT = "link_text"

        fun forLink(url: String, linkText: String? = null) = ContextMenuBottomSheet().apply {
            arguments = bundleOf(ARG_URL to url, ARG_LINK_TEXT to linkText)
        }

        fun forImage(imageUrl: String) = ContextMenuBottomSheet().apply {
            arguments = bundleOf(ARG_IMAGE_URL to imageUrl)
        }

        fun forImageLink(url: String, imageUrl: String, linkText: String? = null) = ContextMenuBottomSheet().apply {
            arguments = bundleOf(ARG_URL to url, ARG_IMAGE_URL to imageUrl, ARG_LINK_TEXT to linkText)
        }
    }
}
