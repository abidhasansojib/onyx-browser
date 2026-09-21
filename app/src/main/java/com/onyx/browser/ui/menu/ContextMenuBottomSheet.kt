package com.onyx.browser.ui.menu

import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Base64
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.onyx.browser.R
import com.onyx.browser.databinding.BottomSheetContextMenuBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Context menu bottom sheet inspired by Brave / Chromium (Screenshot /storage/emulated/0/1.png).
 *
 * Header Card:
 *  - Website icon on left (favicon / letter badge)
 *  - Website title on top, under it the URL
 *  - Inline action buttons on right: Share · Copy · Edit (pencil icon)
 *
 * Image Context Features:
 *  - Preview thumbnail card (tap to open high-res preview)
 *  - "Preview image" action row
 *  - "Open image in new tab"
 *  - "Save image"
 *  - "Search by image" (Reverse search: Google Lens, TinEye, Yandex, Bing)
 *  - "Copy image URL"
 *  - "Share image"
 */
class ContextMenuBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetContextMenuBinding? = null
    private val binding get() = _binding!!

    private var onOpenInNewTab: ((String) -> Unit)? = null
    private var onEditUrl: ((String) -> Unit)? = null

    fun setOnOpenInNewTab(cb: (String) -> Unit) {
        onOpenInNewTab = cb
    }

    fun setOnEditUrl(cb: (String) -> Unit) {
        onEditUrl = cb
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.Theme_OnyxBrowser_BottomSheetDialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetContextMenuBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val url = arguments?.getString(ARG_URL)
        val imageUrl = arguments?.getString(ARG_IMAGE_URL)
        val linkText = arguments?.getString(ARG_LINK_TEXT)
        val isImage = !imageUrl.isNullOrBlank()
        val isLink = !url.isNullOrBlank()

        val displayUrl = url ?: imageUrl ?: ""

        // ── 1. Compact Header Card (Favicon + Title + URL + Share/Copy/Edit) ──
        setupHeaderCard(displayUrl, linkText, isImage)

        // Action buttons inside the compact card
        val targetForActions = url ?: imageUrl ?: ""
        binding.btnShare.setOnClickListener {
            if (targetForActions.isNotBlank()) {
                shareUrl(targetForActions)
            }
            dismiss()
        }

        binding.btnCopyUrl.setOnClickListener {
            if (targetForActions.isNotBlank()) {
                copyToClipboard(if (isImage) "Image URL" else "Link", targetForActions)
            }
            dismiss()
        }

        binding.btnEditUrl.setOnClickListener {
            if (targetForActions.isNotBlank()) {
                onEditUrl?.invoke(targetForActions)
            }
            dismiss()
        }

        // ── 2. Link Actions ──────────────────────────────────────────────────
        if (isLink) {
            binding.itemOpenNewTab.visibility = View.VISIBLE
            binding.itemOpenNewTab.setOnClickListener {
                onOpenInNewTab?.invoke(url!!)
                dismiss()
            }
        }

        if (!linkText.isNullOrBlank()) {
            binding.itemCopyLinkText.visibility = View.VISIBLE
            binding.itemCopyLinkText.setOnClickListener {
                copyToClipboard("Link text", linkText)
                dismiss()
            }
        }

        // ── 3. Image Actions & Preview ───────────────────────────────────────
        if (isImage) {
            val img = imageUrl!!

            // Show preview card and tap-to-expand
            binding.cardImagePreview.visibility = View.VISIBLE
            binding.cardImagePreview.setOnClickListener {
                openFullPreview(img)
            }
            loadThumbnail(img)

            // "Preview image" row
            binding.itemPreviewImage.visibility = View.VISIBLE
            binding.itemPreviewImage.setOnClickListener {
                openFullPreview(img)
            }

            // "Open image in new tab"
            binding.itemOpenImageNewTab.visibility = View.VISIBLE
            binding.itemOpenImageNewTab.setOnClickListener {
                onOpenInNewTab?.invoke(img)
                dismiss()
            }

            // "Save image"
            binding.itemSaveImage.visibility = View.VISIBLE
            binding.itemSaveImage.setOnClickListener {
                downloadMedia(img, "image")
                dismiss()
            }

            // "Search by image" (Google Lens, TinEye, Yandex, Bing)
            binding.itemSearchByImage.visibility = View.VISIBLE
            binding.itemSearchByImage.setOnClickListener {
                showImageSearchPicker(img)
            }

            // "Copy image URL"
            binding.itemCopyImageUrl.visibility = View.VISIBLE
            binding.itemCopyImageUrl.setOnClickListener {
                copyToClipboard("Image URL", img)
                dismiss()
            }

            // "Share image"
            binding.itemShareImage.visibility = View.VISIBLE
            binding.itemShareImage.setOnClickListener {
                shareUrl(img)
                dismiss()
            }
        }

        // ── 4. Video Save ────────────────────────────────────────────────────
        val isVideoUrl = url?.let { u ->
            u.contains(".mp4", ignoreCase = true) ||
            u.contains(".webm", ignoreCase = true) ||
            u.contains(".m3u8", ignoreCase = true) ||
            u.contains("video", ignoreCase = true)
        } == true

        if (isVideoUrl) {
            binding.itemSaveVideo.visibility = View.VISIBLE
            binding.itemSaveVideo.setOnClickListener {
                downloadMedia(url!!, "video")
                dismiss()
            }
        }
    }

    private fun setupHeaderCard(rawUrl: String, linkText: String?, isImage: Boolean) {
        val host = try {
            Uri.parse(rawUrl).host?.removePrefix("www.") ?: rawUrl
        } catch (_: Exception) {
            rawUrl
        }

        val displayTitle = when {
            !linkText.isNullOrBlank() -> linkText
            isImage -> "Image"
            host.isNotBlank() -> host.replaceFirstChar { it.uppercase() }
            else -> rawUrl
        }

        binding.tvContextTitle.text = displayTitle
        binding.tvContextUrl.text = rawUrl

        val faviconHost = try {
            Uri.parse(rawUrl).host ?: ""
        } catch (_: Exception) {
            ""
        }

        if (faviconHost.isNotBlank()) {
            val faviconUrl = "https://www.google.com/s2/favicons?domain=$faviconHost&sz=64"
            loadImageAsync(faviconUrl, maxWidth = 128, maxHeight = 128) { bmp ->
                if (_binding != null) {
                    if (bmp != null) {
                        val circular = getCircularBitmap(bmp)
                        binding.ivFavicon.setImageBitmap(circular)
                        binding.ivFavicon.visibility = View.VISIBLE
                        binding.flFaviconFallback.visibility = View.GONE
                    } else {
                        showLetterFallback(displayTitle)
                    }
                }
            }
        } else {
            showLetterFallback(displayTitle)
        }
    }

    private fun showLetterFallback(title: String) {
        val letter = title.trim().firstOrNull()?.uppercase()
        if (letter != null && letter.matches(Regex("[A-Za-z0-9]"))) {
            binding.tvFaviconLetter.text = letter
            binding.tvFaviconLetter.visibility = View.VISIBLE
            binding.ivFaviconFallback.visibility = View.GONE
        }
    }

    private fun loadThumbnail(imageUrl: String) {
        binding.pbImageLoading.visibility = View.VISIBLE
        loadImageAsync(imageUrl, maxWidth = 800, maxHeight = 800) { bmp ->
            if (_binding != null) {
                binding.pbImageLoading.visibility = View.GONE
                if (bmp != null) {
                    binding.ivImagePreview.setImageBitmap(bmp)
                }
            }
        }
    }

    private fun openFullPreview(imageUrl: String) {
        val previewDialog = ImagePreviewDialog.newInstance(imageUrl)
        previewDialog.setOnOpenInNewTab { u ->
            onOpenInNewTab?.invoke(u)
        }
        previewDialog.show(parentFragmentManager, ImagePreviewDialog.TAG)
        dismiss()
    }

    private fun showImageSearchPicker(imageUrl: String) {
        val picker = ImageSearchPickerSheet(imageUrl) { searchUrl, engineName ->
            onOpenInNewTab?.invoke(searchUrl)
            Toast.makeText(requireContext(), "Searching with $engineName…", Toast.LENGTH_SHORT).show()
            dismiss()
        }
        picker.show(parentFragmentManager, ImageSearchPickerSheet.TAG)
    }

    private fun copyToClipboard(label: String, text: String) {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(requireContext(), "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun shareUrl(url: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        startActivity(Intent.createChooser(intent, "Share link"))
    }

    private fun downloadMedia(url: String, type: String) {
        try {
            val ctx = requireContext()
            val filename = Uri.parse(url).lastPathSegment
                ?: "${type}_${System.currentTimeMillis()}"
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
            Toast.makeText(requireContext(), "Failed to save: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadImageAsync(
        url: String,
        maxWidth: Int = 1024,
        maxHeight: Int = 1024,
        onLoaded: (Bitmap?) -> Unit
    ) {
        if (url.startsWith("data:image")) {
            try {
                val base64Index = url.indexOf("base64,")
                if (base64Index != -1) {
                    val base64Str = url.substring(base64Index + 7)
                    val bytes = Base64.decode(base64Str, Base64.DEFAULT)
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    onLoaded(bmp)
                    return
                }
            } catch (e: Exception) {
                Log.w(TAG, "Data URI decode error: ${e.message}")
            }
        }

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val bitmap = try {
                val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    instanceFollowRedirects = true
                    setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
                    )
                    doInput = true
                    connect()
                }
                if (conn.responseCode == java.net.HttpURLConnection.HTTP_OK) {
                    conn.inputStream.use { stream ->
                        val bytes = stream.readBytes()
                        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)

                        var inSampleSize = 1
                        while (opts.outWidth / (inSampleSize * 2) >= maxWidth &&
                            opts.outHeight / (inSampleSize * 2) >= maxHeight
                        ) {
                            inSampleSize *= 2
                        }
                        val decodeOpts = BitmapFactory.Options().apply {
                            this.inSampleSize = inSampleSize
                        }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
                    }
                } else null
            } catch (e: Exception) {
                null
            }

            withContext(Dispatchers.Main) {
                if (_binding != null && isAdded && !isDetached) {
                    onLoaded(bitmap)
                }
            }
        }
    }

    private fun getCircularBitmap(bitmap: Bitmap): Bitmap {
        val size = Math.min(bitmap.width, bitmap.height)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint().apply { isAntiAlias = true }
        val rect = Rect(
            (bitmap.width - size) / 2,
            (bitmap.height - size) / 2,
            (bitmap.width + size) / 2,
            (bitmap.height + size) / 2
        )
        val rectF = RectF(0f, 0f, size.toFloat(), size.toFloat())
        canvas.drawOval(rectF, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(bitmap, rect, rectF, paint)
        return output
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

        fun forLink(url: String, linkText: String? = null) =
            ContextMenuBottomSheet().apply {
                arguments = bundleOf(ARG_URL to url, ARG_LINK_TEXT to linkText)
            }

        fun forImage(imageUrl: String) =
            ContextMenuBottomSheet().apply {
                arguments = bundleOf(ARG_IMAGE_URL to imageUrl)
            }

        fun forImageLink(url: String, imageUrl: String, linkText: String? = null) =
            ContextMenuBottomSheet().apply {
                arguments = bundleOf(
                    ARG_URL to url,
                    ARG_IMAGE_URL to imageUrl,
                    ARG_LINK_TEXT to linkText
                )
            }
    }
}
