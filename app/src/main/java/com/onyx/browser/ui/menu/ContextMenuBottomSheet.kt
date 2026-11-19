package com.onyx.browser.ui.menu

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.onyx.browser.MainActivity
import com.onyx.browser.R
import com.onyx.browser.download.DownloadHandler
import com.onyx.browser.ui.theme.OnyxTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Context menu bottom sheet rewritten in Jetpack Compose & Material 3.
 */
class ContextMenuBottomSheet : BottomSheetDialogFragment() {

    private val isIncognito: Boolean
        get() = arguments?.getBoolean(ARG_IS_INCOGNITO, false) ?: false

    private var onOpenInNewTab: ((String) -> Unit)? = null
    private var onOpenInIncognitoTab: ((String) -> Unit)? = null
    private var onEditUrl: ((String) -> Unit)? = null
    private var onDownloadUrl: ((String, String) -> Unit)? = null

    fun setOnOpenInNewTab(cb: (String) -> Unit) {
        onOpenInNewTab = cb
    }

    fun setOnOpenInIncognitoTab(cb: (String) -> Unit) {
        onOpenInIncognitoTab = cb
    }

    fun setOnEditUrl(cb: (String) -> Unit) {
        onEditUrl = cb
    }

    fun setOnDownloadUrl(cb: (String, String) -> Unit) {
        onDownloadUrl = cb
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
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                OnyxTheme {
                    ContextMenuSheetContent()
                }
            }
        }
    }

    @Composable
    private fun ContextMenuSheetContent() {
        val url = arguments?.getString(ARG_URL)
        val imageUrl = arguments?.getString(ARG_IMAGE_URL)
        val videoUrl = arguments?.getString(ARG_VIDEO_URL)
        val linkText = arguments?.getString(ARG_LINK_TEXT)
        val incognito = arguments?.getBoolean(ARG_IS_INCOGNITO, false) ?: false

        val isImage = !imageUrl.isNullOrBlank()
        val isVideo = !videoUrl.isNullOrBlank()
        val isLink = !url.isNullOrBlank() && !isVideo
        val effectiveVideoUrl = videoUrl ?: if (url?.let { isLikelyVideoUrl(it) } == true) url else null

        val displayUrl = url ?: imageUrl ?: videoUrl ?: ""

        var imageBitmap by remember { mutableStateOf<Bitmap?>(null) }
        var isImageLoading by remember { mutableStateOf(isImage) }

        LaunchedEffect(imageUrl) {
            if (!imageUrl.isNullOrBlank()) {
                isImageLoading = true
                loadImageAsync(imageUrl) { bmp ->
                    imageBitmap = bmp
                    isImageLoading = false
                }
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
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .navigationBarsPadding()
            ) {
                // Drag handle
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 12.dp)
                        .size(width = 36.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )

                // Header Card
                ContextMenuHeaderCard(
                    rawUrl = displayUrl,
                    linkText = linkText,
                    isImage = isImage,
                    isVideo = isVideo,
                    onShareClick = {
                        if (displayUrl.isNotBlank()) shareUrl(displayUrl)
                        dismiss()
                    },
                    onCopyClick = {
                        if (displayUrl.isNotBlank()) {
                            val label = when {
                                isImage -> "Image URL"
                                isVideo -> "Video URL"
                                else -> "Link"
                            }
                            copyToClipboard(label, displayUrl)
                        }
                        dismiss()
                    },
                    onEditClick = {
                        if (displayUrl.isNotBlank()) onEditUrl?.invoke(displayUrl)
                        dismiss()
                    }
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Image Preview Card (if image)
                if (isImage && imageUrl != null) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { openFullPreview(imageUrl) }
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            if (isImageLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                            } else if (imageBitmap != null) {
                                Image(
                                    bitmap = imageBitmap!!.asImageBitmap(),
                                    contentDescription = "Image preview",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Icon(
                                    painter = painterResource(R.drawable.ic_image),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                    modifier = Modifier.size(48.dp)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Link Actions
                if (isLink && url != null) {
                    ContextMenuItem(
                        icon = Icons.Default.OpenInNew,
                        text = "Open in new tab",
                        onClick = {
                            onOpenInNewTab?.invoke(url)
                            dismiss()
                        }
                    )

                    if (!incognito) {
                        ContextMenuItem(
                            iconRes = R.drawable.ic_incognito,
                            text = "Open in incognito tab",
                            onClick = {
                                onOpenInIncognitoTab?.invoke(url)
                                dismiss()
                            }
                        )
                    }

                    ContextMenuItem(
                        icon = Icons.Default.ContentCopy,
                        text = "Copy link address",
                        onClick = {
                            copyToClipboard("Link address", url)
                            dismiss()
                        }
                    )

                    if (!linkText.isNullOrBlank()) {
                        ContextMenuItem(
                            icon = Icons.Default.ContentCopy,
                            text = "Copy link text",
                            onClick = {
                                copyToClipboard("Link text", linkText)
                                dismiss()
                            }
                        )
                    }

                    ContextMenuItem(
                        icon = Icons.Default.Download,
                        text = "Download link",
                        onClick = {
                            if (onDownloadUrl != null) {
                                onDownloadUrl?.invoke(url, "")
                            } else {
                                downloadMedia(url, "download")
                            }
                            dismiss()
                        }
                    )

                    ContextMenuItem(
                        icon = Icons.Default.Share,
                        text = "Share link",
                        onClick = {
                            shareUrl(url)
                            dismiss()
                        }
                    )
                }

                // Image Actions
                if (isImage && imageUrl != null) {
                    ContextMenuItem(
                        icon = Icons.Default.Fullscreen,
                        text = "Preview image",
                        onClick = { openFullPreview(imageUrl) }
                    )

                    ContextMenuItem(
                        icon = Icons.Default.OpenInNew,
                        text = "Open image in new tab",
                        onClick = {
                            onOpenInNewTab?.invoke(imageUrl)
                            dismiss()
                        }
                    )

                    ContextMenuItem(
                        icon = Icons.Default.Download,
                        text = "Save image",
                        onClick = {
                            val mime = resolveImageMimeType(imageUrl)
                            if (onDownloadUrl != null) {
                                onDownloadUrl?.invoke(imageUrl, mime)
                            } else {
                                downloadMedia(imageUrl, "image")
                            }
                            dismiss()
                        }
                    )

                    ContextMenuItem(
                        icon = Icons.Default.Search,
                        text = "Search by image",
                        onClick = { showImageSearchPicker(imageUrl) }
                    )

                    ContextMenuItem(
                        icon = Icons.Default.ContentCopy,
                        text = "Copy image URL",
                        onClick = {
                            copyToClipboard("Image URL", imageUrl)
                            dismiss()
                        }
                    )

                    ContextMenuItem(
                        icon = Icons.Default.Share,
                        text = "Share image",
                        onClick = {
                            shareUrl(imageUrl)
                            dismiss()
                        }
                    )
                }

                // Video Actions
                if (!effectiveVideoUrl.isNullOrBlank()) {
                    val vUrl = effectiveVideoUrl

                    ContextMenuItem(
                        icon = Icons.Default.OpenInNew,
                        text = "Open video in new tab",
                        onClick = {
                            onOpenInNewTab?.invoke(vUrl)
                            dismiss()
                        }
                    )

                    ContextMenuItem(
                        icon = Icons.Default.Download,
                        text = "Save video",
                        onClick = {
                            val mime = resolveVideoMimeType(vUrl)
                            if (onDownloadUrl != null) {
                                onDownloadUrl?.invoke(vUrl, mime)
                            } else {
                                downloadMedia(vUrl, "video")
                            }
                            dismiss()
                        }
                    )

                    ContextMenuItem(
                        icon = Icons.Default.ContentCopy,
                        text = "Copy video link",
                        onClick = {
                            copyToClipboard("Video link", vUrl)
                            dismiss()
                        }
                    )

                    ContextMenuItem(
                        icon = Icons.Default.Share,
                        text = "Share video",
                        onClick = {
                            shareUrl(vUrl)
                            dismiss()
                        }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }

    @Composable
    private fun ContextMenuHeaderCard(
        rawUrl: String,
        linkText: String?,
        isImage: Boolean,
        isVideo: Boolean,
        onShareClick: () -> Unit,
        onCopyClick: () -> Unit,
        onEditClick: () -> Unit
    ) {
        val host = remember(rawUrl) {
            try {
                Uri.parse(rawUrl).host?.removePrefix("www.") ?: rawUrl
            } catch (_: Exception) {
                rawUrl
            }
        }

        val displayTitle = remember(rawUrl, linkText, isImage, isVideo) {
            when {
                !linkText.isNullOrBlank() -> linkText
                isImage -> "Image"
                isVideo -> "Video"
                host.isNotBlank() -> host.replaceFirstChar { it.uppercase() }
                else -> rawUrl
            }
        }

        val firstLetter = remember(displayTitle) {
            displayTitle.trim().firstOrNull()?.uppercase()
        }

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Favicon letter badge
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    if (firstLetter != null && firstLetter.matches(Regex("[A-Za-z0-9]"))) {
                        Text(
                            text = firstLetter,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.ic_web),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Title & URL
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = displayTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = rawUrl,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Inline Actions
                IconButton(onClick = onShareClick, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Share",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(onClick = onCopyClick, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(onClick = onEditClick, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }

    @Composable
    private fun ContextMenuItem(
        text: String,
        onClick: () -> Unit,
        icon: ImageVector? = null,
        iconRes: Int? = null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { onClick() }
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            } else if (iconRes != null) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
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
        val clip = ClipData.newPlainText(label, text)
        cm.setPrimaryClip(clip)
        Toast.makeText(requireContext(), "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun shareUrl(url: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        startActivity(Intent.createChooser(intent, "Share link"))
    }

    private fun resolveVideoMimeType(url: String): String {
        val clean = url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            clean.endsWith(".webm") -> "video/webm"
            clean.endsWith(".mp4") -> "video/mp4"
            clean.endsWith(".m3u8") -> "application/x-mpegURL"
            clean.endsWith(".mkv") -> "video/x-matroska"
            clean.endsWith(".mov") -> "video/quicktime"
            clean.endsWith(".avi") -> "video/x-msvideo"
            clean.endsWith(".flv") -> "video/x-flv"
            clean.endsWith(".3gp") -> "video/3gpp"
            clean.endsWith(".ts") -> "video/mp2t"
            clean.endsWith(".mpd") -> "application/dash+xml"
            else -> "video/mp4"
        }
    }

    private fun resolveImageMimeType(url: String): String {
        val clean = url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            clean.endsWith(".png") -> "image/png"
            clean.endsWith(".webp") -> "image/webp"
            clean.endsWith(".gif") -> "image/gif"
            clean.endsWith(".svg") -> "image/svg+xml"
            clean.endsWith(".bmp") -> "image/bmp"
            clean.endsWith(".ico") -> "image/x-icon"
            clean.endsWith(".jpg") || clean.endsWith(".jpeg") -> "image/jpeg"
            else -> "image/jpeg"
        }
    }

    private fun isLikelyVideoUrl(url: String): Boolean {
        val clean = url.substringBefore('?').substringBefore('#').lowercase()
        return clean.endsWith(".mp4") ||
               clean.endsWith(".webm") ||
               clean.endsWith(".m3u8") ||
               clean.endsWith(".mkv") ||
               clean.endsWith(".mov") ||
               clean.endsWith(".avi") ||
               clean.endsWith(".flv") ||
               clean.endsWith(".3gp") ||
               clean.endsWith(".ts") ||
               clean.endsWith(".mpd")
    }

    private fun downloadMedia(url: String, type: String) {
        val act = activity ?: MainActivity.currentInstance?.get()
        val mime = when (type) {
            "video" -> resolveVideoMimeType(url)
            "image" -> resolveImageMimeType(url)
            else -> ""
        }

        if (act != null) {
            val cookies = try {
                android.webkit.CookieManager.getInstance().getCookie(url) ?: ""
            } catch (_: Exception) { "" }
            val ua = (act as? MainActivity)?.getActiveWebView()?.settings?.userAgentString ?: ""
            val pageUrl = (act as? MainActivity)?.getActiveWebView()?.url ?: ""
            val scope = (act as? androidx.lifecycle.LifecycleOwner)?.lifecycleScope ?: kotlinx.coroutines.MainScope()
            DownloadHandler.handleDownload(
                activity = act,
                coroutineScope = scope,
                url = url,
                userAgent = ua,
                contentDisposition = "",
                mimeType = mime,
                contentLength = -1L,
                cookies = cookies,
                referer = pageUrl
            )
            return
        }

        try {
            val ctx = context?.applicationContext ?: return
            val fileName = DownloadHandler.guessResolvedFileName(url, null, mime)
            DownloadHandler.startInternalDownload(
                context = ctx,
                coroutineScope = kotlinx.coroutines.MainScope(),
                url = url,
                userAgent = "",
                fileName = fileName,
                mimeType = mime,
                contentLength = -1L,
                cookies = "",
                referer = ""
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download media: ${e.message}", e)
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
                if (isAdded && !isDetached) {
                    onLoaded(bitmap)
                }
            }
        }
    }

    companion object {
        const val TAG = "ContextMenuBottomSheet"
        private const val ARG_URL = "url"
        private const val ARG_IMAGE_URL = "image_url"
        private const val ARG_VIDEO_URL = "video_url"
        private const val ARG_LINK_TEXT = "link_text"
        private const val ARG_IS_INCOGNITO = "is_incognito"

        fun forLink(url: String, linkText: String? = null, isIncognito: Boolean = false) =
            ContextMenuBottomSheet().apply {
                arguments = bundleOf(
                    ARG_URL to url,
                    ARG_LINK_TEXT to linkText,
                    ARG_IS_INCOGNITO to isIncognito
                )
            }

        fun forImage(imageUrl: String, isIncognito: Boolean = false) =
            ContextMenuBottomSheet().apply {
                arguments = bundleOf(
                    ARG_IMAGE_URL to imageUrl,
                    ARG_IS_INCOGNITO to isIncognito
                )
            }

        fun forImageLink(url: String, imageUrl: String, linkText: String? = null, isIncognito: Boolean = false) =
            ContextMenuBottomSheet().apply {
                arguments = bundleOf(
                    ARG_URL to url,
                    ARG_IMAGE_URL to imageUrl,
                    ARG_LINK_TEXT to linkText,
                    ARG_IS_INCOGNITO to isIncognito
                )
            }

        fun forVideo(
            videoUrl: String,
            linkUrl: String? = null,
            linkText: String? = null,
            isIncognito: Boolean = false
        ) =
            ContextMenuBottomSheet().apply {
                arguments = bundleOf(
                    ARG_URL to (linkUrl ?: videoUrl),
                    ARG_VIDEO_URL to videoUrl,
                    ARG_LINK_TEXT to linkText,
                    ARG_IS_INCOGNITO to isIncognito
                )
            }
    }
}
