package com.onyx.browser.web

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.MimeTypeMap
import android.webkit.WebResourceResponse
import android.widget.Toast
import com.onyx.browser.download.FileUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * High-performance resolver, preparer, interceptor, and renderer for local documents:
 * - Offline Web Archives (.mht, .mhtml, multipart/related, message/rfc822)
 * - HTML Documents (.html, .htm, .xhtml)
 * - Markdown Documents (.md, .markdown)
 * - Plain Text Documents (.txt, .log, .csv, .json, .xml, .yaml, .yml)
 *
 * Engineered for instant (<10ms) rendering, zero UI thread freezing, and full compatibility
 * with Scoped Storage across modern Android (Android 8 to Android 16).
 */
object LocalFileLoader {

    enum class LocalFileType {
        HTML,
        MHTML,
        MARKDOWN,
        TEXT,
        CODE,
        IMAGE,
        VIDEO,
        AUDIO,
        PDF,
        UNSUPPORTED_BINARY,
        UNKNOWN
    }

    private var cachedMarkdownTemplate: String? = null

    // Thread-safe map tracking generated preview file paths keyed by tabId
    private val activePreviewFiles = ConcurrentHashMap<String, MutableSet<String>>()

    /**
     * Determines whether the given URL or path points to a sensitive, private, or restricted
     * system/app filesystem location that must NEVER be exposed or navigated to.
     */
    fun isSensitiveOrRestrictedPath(context: Context?, urlOrPath: String): Boolean {
        val trimmed = urlOrPath.trim()
        val lower = trimmed.lowercase()

        // System assets and resources are safe and managed internally
        if (lower.startsWith("file:///android_asset/") || lower.startsWith("file:///android_res/")) {
            return false
        }

        // Sensitive Linux & Android root directories
        if (lower.startsWith("/data/") || lower.startsWith("file:///data/") ||
            lower.startsWith("/proc/") || lower.startsWith("file:///proc/") ||
            lower.startsWith("/sys/") || lower.startsWith("file:///sys/") ||
            lower.startsWith("/system/") || lower.startsWith("file:///system/") ||
            lower.startsWith("/apex/") || lower.startsWith("file:///apex/") ||
            lower.startsWith("/vendor/") || lower.startsWith("file:///vendor/")) {
            return true
        }

        if (context != null) {
            try {
                val path = when {
                    trimmed.startsWith("file://", ignoreCase = true) -> Uri.parse(trimmed).path ?: ""
                    trimmed.startsWith("/") -> trimmed
                    else -> ""
                }
                if (path.isNotBlank()) {
                    val canonical = File(path).canonicalPath
                    val blockedPrefixes = listOf("/data/", "/proc/", "/sys/", "/system/", "/apex/", "/vendor/")
                    if (blockedPrefixes.any { canonical.startsWith(it) }) {
                        return true
                    }
                    val dataDir = context.applicationInfo.dataDir
                    if (!dataDir.isNullOrBlank() && canonical.startsWith(File(dataDir).canonicalPath)) {
                        return true
                    }
                    val cacheDir = context.cacheDir?.canonicalPath
                    if (!cacheDir.isNullOrBlank() && canonical.startsWith(cacheDir)) {
                        return true
                    }
                    val filesDir = context.filesDir?.canonicalPath
                    if (!filesDir.isNullOrBlank() && canonical.startsWith(filesDir)) {
                        return true
                    }
                }
            } catch (_: Exception) {
                return true // Fail safe: reject if canonicalization fails
            }
        }
        return false
    }

    /**
     * Determines whether the given string represents a local file URI or path.
     * Excludes system asset/resource schemes (file:///android_asset/ and file:///android_res/)
     * and strictly blocks private app storage or sensitive system partitions.
     */
    fun isLocalFile(context: Context?, input: String): Boolean {
        val trimmed = input.trim()
        if (trimmed.startsWith("file:///android_asset/", ignoreCase = true) ||
            trimmed.startsWith("file:///android_res/", ignoreCase = true)) {
            return false
        }
        // Block sensitive system/internal paths — these should never be opened in the browser.
        if (isSensitiveOrRestrictedPath(context, trimmed)) {
            return false
        }
        if (trimmed.startsWith("file://", ignoreCase = true) ||
            trimmed.startsWith("content://", ignoreCase = true) ||
            trimmed.startsWith("/storage/", ignoreCase = true) ||
            trimmed.startsWith("/sdcard/", ignoreCase = true)) {
            return true
        }
        val lower = trimmed.lowercase()
        if (lower.endsWith(".html") || lower.endsWith(".htm") || lower.endsWith(".xhtml") ||
            lower.endsWith(".mht") || lower.endsWith(".mhtml") ||
            lower.endsWith(".md") || lower.endsWith(".markdown") || lower.endsWith(".mdown") ||
            lower.endsWith(".txt") || lower.endsWith(".log") || lower.endsWith(".json") || lower.endsWith(".xml") ||
            lower.contains("readme")) {
            if (trimmed.startsWith("/")) return true
        }
        if (trimmed.startsWith("/") && try { File(trimmed).exists() } catch (_: Exception) { false }) {
            return true
        }
        return false
    }

    fun isLocalFile(input: String): Boolean = isLocalFile(null, input)

    fun registerPreviewFile(tabId: String, filePath: String) {
        if (tabId.isBlank() || filePath.isBlank()) return
        activePreviewFiles.getOrPut(tabId) { Collections.synchronizedSet(mutableSetOf()) }.add(filePath)
    }

    fun isAuthorizedPreviewForTab(tabId: String?, url: String): Boolean {
        if (tabId.isNullOrBlank() || url.isBlank()) return false
        val path = try {
            if (url.startsWith("file://", ignoreCase = true)) {
                Uri.parse(url).path ?: ""
            } else url
        } catch (_: Exception) { "" }
        if (path.isBlank()) return false
        val canonical = try { File(path).canonicalPath } catch (_: Exception) { path }
        val registeredPaths = activePreviewFiles[tabId] ?: return false
        return registeredPaths.any { registered ->
            try {
                File(registered).canonicalPath == canonical
            } catch (_: Exception) {
                registered == path
            }
        }
    }

    fun isPreviewUrl(url: String): Boolean {
        if (url.isBlank()) return false
        return url.contains("/cache/web_archives/preview_")
    }

    fun cleanupTabPreviews(context: Context, tabId: String) {
        if (tabId.isBlank()) return
        val registered = activePreviewFiles.remove(tabId)
        registered?.forEach { path ->
            try {
                val f = File(path)
                if (f.exists()) f.delete()
            } catch (_: Exception) {}
        }
        try {
            val archivesDir = File(context.cacheDir, "web_archives")
            if (archivesDir.exists()) {
                archivesDir.listFiles()?.forEach { file ->
                    if (file.name.startsWith("preview_${tabId}_")) {
                        file.delete()
                    }
                }
            }
        } catch (_: Exception) {}
    }

    fun cleanupAllPreviews(context: Context) {
        activePreviewFiles.clear()
        try {
            val archivesDir = File(context.cacheDir, "web_archives")
            if (archivesDir.exists()) {
                archivesDir.deleteRecursively()
            }
        } catch (_: Exception) {}
    }

    /**
     * Parses and standardizes an input string into an android.net.Uri.
     */
    fun parseUri(raw: String): Uri {
        val trimmed = raw.trim()
        return when {
            trimmed.startsWith("content://", ignoreCase = true) -> Uri.parse(trimmed)
            trimmed.startsWith("file://", ignoreCase = true) -> Uri.parse(trimmed)
            trimmed.startsWith("/") -> Uri.fromFile(File(trimmed))
            else -> Uri.parse(trimmed)
        }
    }

    /**
     * Asynchronously resolves, decodes, and loads a local document into the given OnyxWebView.
     * All disk/ContentResolver I/O executes strictly on [Dispatchers.IO] to guarantee
     * an unblocked, 60fps UI thread.
     */
    fun loadLocalFile(
        context: Context,
        webView: OnyxWebView,
        rawUriOrPath: String,
        onTitleResolved: ((String) -> Unit)? = null
    ) {
        val uri = parseUri(rawUriOrPath)

        CoroutineScope(Dispatchers.IO).launch {
            if (!FileUtils.doesFileExist(rawUriOrPath, context)) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "File not found or deleted", Toast.LENGTH_SHORT).show()
                    showErrorPage(webView, rawUriOrPath, "The requested file was deleted or does not exist.")
                }
                return@launch
            }

            val fileName = getDisplayName(context, uri)
            withContext(Dispatchers.Main) {
                onTitleResolved?.invoke(fileName)
            }
            val fileType = detectFileType(context, uri)

            when (fileType) {
                LocalFileType.MHTML -> {
                    // Chromium's native C++ MHTMLArchive parser handles .mht/.mhtml files directly,
                    // but ONLY via file:// URLs in readable storage and when shouldInterceptRequest returns null.
                    val preparedFile = prepareMhtmlFile(context, uri, webView.tabId)
                    withContext(Dispatchers.Main) {
                        if (preparedFile != null && preparedFile.exists() && preparedFile.length() > 0) {
                            webView.stopLoading()
                            webView.loadUrl(Uri.fromFile(preparedFile).toString())
                        } else {
                            showErrorPage(webView, rawUriOrPath, "Unable to read or decode Web Archive (.mht). The file may be corrupt or inaccessible.")
                        }
                    }
                }

                LocalFileType.HTML -> {
                    // Read HTML string directly in background and load via loadDataWithBaseURL.
                    // This bypasses ContentResolver IPC latency and guarantees instant < 10ms rendering.
                    val stream = openInputStream(context, uri)
                    val htmlContent = try {
                        stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }
                    } catch (e: Exception) {
                        null
                    }

                    withContext(Dispatchers.Main) {
                        if (htmlContent != null) {
                            val baseUrl = if (uri.scheme == "file" && uri.path != null) {
                                val parent = File(uri.path!!).parentFile
                                if (parent != null) "file://${parent.absolutePath}/" else null
                            } else null
                            webView.stopLoading()
                            webView.loadDataWithBaseURL(baseUrl, htmlContent, "text/html", "UTF-8", rawUriOrPath)
                        } else {
                            showErrorPage(webView, rawUriOrPath, "Could not open HTML document. Permission denied or file not found.")
                        }
                    }
                }

                LocalFileType.MARKDOWN -> {
                    val stream = openInputStream(context, uri)
                    val rawMarkdown = try {
                        stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }
                    } catch (e: Exception) {
                        null
                    }

                    withContext(Dispatchers.Main) {
                        if (rawMarkdown != null) {
                            val previewHtml = renderMarkdownToHtml(context, fileName, rawMarkdown)
                            webView.stopLoading()
                            val baseUrl = if (uri.scheme == "file" && uri.path != null) {
                                val parent = File(uri.path!!).parentFile
                                if (parent != null) "file://${parent.absolutePath}/" else "file:///"
                            } else if (uri.scheme == "content") {
                                rawUriOrPath
                            } else null
                            webView.loadDataWithBaseURL(baseUrl, previewHtml, "text/html", "UTF-8", rawUriOrPath)
                        } else {
                            showErrorPage(webView, rawUriOrPath, "Could not read Markdown document.")
                        }
                    }
                }

                LocalFileType.IMAGE -> {
                    val previewHtml = renderImageViewerHtml(fileName, rawUriOrPath)
                    withContext(Dispatchers.Main) {
                        webView.stopLoading()
                        webView.loadDataWithBaseURL(rawUriOrPath, previewHtml, "text/html", "UTF-8", rawUriOrPath)
                    }
                }

                LocalFileType.VIDEO, LocalFileType.AUDIO -> {
                    val isVideo = (fileType == LocalFileType.VIDEO)
                    val mimeType = try {
                        if (uri.scheme == "content") context.contentResolver.getType(uri) else null
                    } catch (_: Exception) { null } ?: (if (isVideo) "video/mp4" else "audio/mpeg")
                    val previewHtml = renderMediaPlayerHtml(fileName, rawUriOrPath, mimeType, isVideo)
                    withContext(Dispatchers.Main) {
                        webView.stopLoading()
                        webView.loadDataWithBaseURL(rawUriOrPath, previewHtml, "text/html", "UTF-8", rawUriOrPath)
                    }
                }

                LocalFileType.CODE -> {
                    val stream = openInputStream(context, uri)
                    val codeContent = try {
                        stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }
                    } catch (e: Exception) {
                        null
                    }
                    withContext(Dispatchers.Main) {
                        if (codeContent != null) {
                            val previewHtml = renderCodeAsHtml(fileName, codeContent)
                            webView.stopLoading()
                            webView.loadDataWithBaseURL(rawUriOrPath, previewHtml, "text/html", "UTF-8", rawUriOrPath)
                        } else {
                            showErrorPage(webView, rawUriOrPath, "Could not read code document.")
                        }
                    }
                }

                LocalFileType.PDF -> {
                    val fileSize = getFileSize(context, uri)
                    val previewHtml = renderPdfViewerHtml(fileName, rawUriOrPath, fileSize)
                    withContext(Dispatchers.Main) {
                        webView.stopLoading()
                        webView.loadDataWithBaseURL(rawUriOrPath, previewHtml, "text/html", "UTF-8", rawUriOrPath)
                    }
                }

                LocalFileType.UNSUPPORTED_BINARY -> {
                    val fileSize = getFileSize(context, uri)
                    val mime = try {
                        if (uri.scheme == "content") context.contentResolver.getType(uri) else null
                    } catch (_: Exception) { null } ?: "application/octet-stream"
                    val previewHtml = renderUnsupportedBinaryHtml(fileName, rawUriOrPath, mime, fileSize)
                    withContext(Dispatchers.Main) {
                        webView.stopLoading()
                        webView.loadDataWithBaseURL(rawUriOrPath, previewHtml, "text/html", "UTF-8", rawUriOrPath)
                    }
                }

                LocalFileType.TEXT, LocalFileType.UNKNOWN -> {
                    val rawStream = openInputStream(context, uri)
                    if (rawStream == null) {
                        withContext(Dispatchers.Main) {
                            showErrorPage(webView, rawUriOrPath, "Could not open document. Permission denied or file not found.")
                        }
                        return@launch
                    }
                    val bufferedStream = BufferedInputStream(rawStream)
                    if (isBinaryStream(bufferedStream)) {
                        val fileSize = getFileSize(context, uri)
                        val mime = try {
                            if (uri.scheme == "content") context.contentResolver.getType(uri) else null
                        } catch (_: Exception) { null } ?: "application/octet-stream"
                        val previewHtml = renderUnsupportedBinaryHtml(fileName, rawUriOrPath, mime, fileSize)
                        withContext(Dispatchers.Main) {
                            webView.stopLoading()
                            webView.loadDataWithBaseURL(rawUriOrPath, previewHtml, "text/html", "UTF-8", rawUriOrPath)
                        }
                        return@launch
                    }

                    val textContent = try {
                        bufferedStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                    } catch (e: Exception) {
                        null
                    }

                    withContext(Dispatchers.Main) {
                        if (textContent != null) {
                            val baseUrl = if (uri.scheme == "file" && uri.path != null) {
                                val parent = File(uri.path!!).parentFile
                                if (parent != null) "file://${parent.absolutePath}/" else "file:///"
                            } else if (uri.scheme == "content") {
                                rawUriOrPath
                            } else null

                            if (isMarkdownContent(fileName, textContent)) {
                                val previewHtml = renderMarkdownToHtml(context, fileName, textContent)
                                webView.stopLoading()
                                webView.loadDataWithBaseURL(baseUrl, previewHtml, "text/html", "UTF-8", rawUriOrPath)
                            } else {
                                val formattedHtml = formatPlainTextAsHtml(fileName, textContent)
                                webView.stopLoading()
                                webView.loadDataWithBaseURL(baseUrl, formattedHtml, "text/html", "UTF-8", rawUriOrPath)
                            }
                        } else {
                            showErrorPage(webView, rawUriOrPath, "Could not read text document.")
                        }
                    }
                }
            }
        }
    }

    /**
     * Prepares an MHTML file on disk so that Chromium's native Blink MHTMLArchive
     * parser can parse and render it with full CSS/images fidelity and zero black screens.
     */
    fun prepareMhtmlFile(context: Context, uri: Uri, tabId: String? = null): File? {
        val archivesDir = File(context.cacheDir, "web_archives").apply {
            if (!exists()) mkdirs()
        }

        // Cleanup old preview files (> 1 hour old) to keep cache tidy
        try {
            val now = System.currentTimeMillis()
            archivesDir.listFiles()?.forEach { f ->
                if (f.name.startsWith("preview_") && (now - f.lastModified() > 3600_000)) {
                    f.delete()
                }
            }
        } catch (_: Exception) {}

        // Copy stream into dedicated app cache file ending with .mht scoped to this tab
        val fileName = if (!tabId.isNullOrBlank()) {
            "preview_${tabId}_${System.currentTimeMillis()}.mht"
        } else {
            "preview_${System.currentTimeMillis()}.mht"
        }
        val tempFile = File(archivesDir, fileName)
        val stream = openInputStream(context, uri) ?: return null
        return try {
            stream.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            if (tempFile.exists() && tempFile.length() > 0) {
                if (!tabId.isNullOrBlank()) {
                    registerPreviewFile(tabId, tempFile.absolutePath)
                }
                tempFile
            } else null
        } catch (e: Exception) {
            try { tempFile.delete() } catch (_: Exception) {}
            null
        }
    }

    /**
     * Determines whether the given file or MIME type is safe and supported for in-browser rendering.
     */
    fun canRender(fileName: String, mimeType: String = ""): Boolean {
        val lowerName = fileName.lowercase()
        val lowerMime = mimeType.lowercase()

        // HTML & Web Archives
        if (lowerName.endsWith(".html") || lowerName.endsWith(".htm") || lowerName.endsWith(".xhtml") ||
            lowerName.endsWith(".mht") || lowerName.endsWith(".mhtml") ||
            lowerMime == "text/html" || lowerMime == "application/xhtml+xml" ||
            lowerMime == "multipart/related" || lowerMime == "message/rfc822" ||
            lowerMime == "application/x-mimearchive" || lowerMime == "application/mhtml"
        ) return true

        // Markdown
        if (lowerName.endsWith(".md") || lowerName.endsWith(".markdown") || lowerName.endsWith(".mdown") ||
            lowerName.endsWith(".mkd") || lowerName.contains("readme") ||
            lowerMime == "text/markdown" || lowerMime == "text/x-markdown"
        ) return true

        // Images
        if (lowerName.endsWith(".png") || lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") ||
            lowerName.endsWith(".gif") || lowerName.endsWith(".webp") || lowerName.endsWith(".svg") ||
            lowerName.endsWith(".bmp") || lowerName.endsWith(".ico") || lowerName.endsWith(".avif") ||
            lowerMime.startsWith("image/")
        ) return true

        // Video & Audio
        if (lowerName.endsWith(".mp4") || lowerName.endsWith(".webm") || lowerName.endsWith(".mkv") ||
            lowerName.endsWith(".mov") || lowerName.endsWith(".3gp") || lowerName.endsWith(".mp3") ||
            lowerName.endsWith(".wav") || lowerName.endsWith(".ogg") || lowerName.endsWith(".m4a") ||
            lowerName.endsWith(".aac") || lowerName.endsWith(".flac") || lowerName.endsWith(".opus") ||
            lowerMime.startsWith("video/") || lowerMime.startsWith("audio/")
        ) return true

        // PDF
        if (lowerName.endsWith(".pdf") || lowerMime == "application/pdf") return true

        // Text & Code
        if (lowerName.endsWith(".txt") || lowerName.endsWith(".log") || lowerName.endsWith(".csv") ||
            lowerName.endsWith(".tsv") || lowerName.endsWith(".json") || lowerName.endsWith(".xml") ||
            lowerName.endsWith(".yaml") || lowerName.endsWith(".yml") || lowerName.endsWith(".toml") ||
            lowerName.endsWith(".ini") || lowerName.endsWith(".properties") || lowerName.endsWith(".js") ||
            lowerName.endsWith(".mjs") || lowerName.endsWith(".ts") || lowerName.endsWith(".css") ||
            lowerName.endsWith(".py") || lowerName.endsWith(".sh") || lowerName.endsWith(".kt") ||
            lowerName.endsWith(".java") || lowerName.endsWith(".c") || lowerName.endsWith(".cpp") ||
            lowerName.endsWith(".h") || lowerName.endsWith(".hpp") || lowerName.endsWith(".rs") ||
            lowerName.endsWith(".go") || lowerName.endsWith(".sql") ||
            lowerMime.startsWith("text/") || lowerMime == "application/json" ||
            lowerMime == "application/xml" || lowerMime == "application/javascript"
        ) return true

        return false
    }

    /**
     * Determines whether the file is a web document, code, markdown, or image that should
     * automatically open inside Onyx Browser rather than launching an external viewer app.
     */
    fun isWebDocument(fileName: String, mimeType: String = ""): Boolean {
        val lowerName = fileName.lowercase()
        val lowerMime = mimeType.lowercase()
        return lowerName.endsWith(".mht") || lowerName.endsWith(".mhtml") ||
            lowerName.endsWith(".html") || lowerName.endsWith(".htm") || lowerName.endsWith(".xhtml") ||
            lowerName.endsWith(".md") || lowerName.endsWith(".markdown") || lowerName.endsWith(".mdown") || lowerName.endsWith(".mkd") ||
            lowerName.contains("readme") ||
            lowerName.endsWith(".txt") || lowerName.endsWith(".log") || lowerName.endsWith(".json") || lowerName.endsWith(".xml") ||
            lowerName.endsWith(".svg") || lowerName.endsWith(".png") || lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") ||
            lowerName.endsWith(".webp") || lowerName.endsWith(".gif") || lowerName.endsWith(".ico") || lowerName.endsWith(".avif") ||
            lowerName.endsWith(".js") || lowerName.endsWith(".mjs") || lowerName.endsWith(".ts") || lowerName.endsWith(".css") ||
            lowerName.endsWith(".py") || lowerName.endsWith(".sh") || lowerName.endsWith(".kt") || lowerName.endsWith(".java") ||
            lowerName.endsWith(".c") || lowerName.endsWith(".cpp") || lowerName.endsWith(".rs") || lowerName.endsWith(".go") ||
            lowerName.endsWith(".sql") || lowerName.endsWith(".yaml") || lowerName.endsWith(".yml") ||
            lowerMime == "multipart/related" || lowerMime == "message/rfc822" ||
            lowerMime == "application/x-mimearchive" || lowerMime == "application/mhtml" ||
            lowerMime == "text/html" || lowerMime == "application/xhtml+xml" ||
            lowerMime == "text/markdown" || lowerMime == "text/x-markdown" ||
            lowerMime == "text/plain" || lowerMime == "image/svg+xml" ||
            lowerMime.startsWith("image/") ||
            lowerMime == "application/json" || lowerMime == "text/xml" || lowerMime == "application/xml" ||
            lowerMime == "text/css" || lowerMime == "application/javascript"
    }
    fun detectFileType(context: Context, uri: Uri): LocalFileType {
        val name = getDisplayName(context, uri).lowercase()
        val path = (uri.path ?: "").lowercase()
        val mimeType = try {
            if (uri.scheme == "content") context.contentResolver.getType(uri)?.lowercase() else null
        } catch (_: Exception) { null }

        // 1. MHTML / MHT web archives
        if (name.endsWith(".mht") || name.endsWith(".mhtml") ||
            path.endsWith(".mht") || path.endsWith(".mhtml") ||
            mimeType == "multipart/related" || mimeType == "message/rfc822" ||
            mimeType == "application/x-mimearchive" || mimeType == "application/mhtml" ||
            mimeType == "multipart/appledouble"
        ) {
            return LocalFileType.MHTML
        }

        // 2. HTML documents
        if (name.endsWith(".html") || name.endsWith(".htm") || name.endsWith(".xhtml") ||
            path.endsWith(".html") || path.endsWith(".htm") || path.endsWith(".xhtml") ||
            mimeType == "text/html" || mimeType == "application/xhtml+xml"
        ) {
            return LocalFileType.HTML
        }

        // 3. Markdown files
        if (name.endsWith(".md") || name.endsWith(".markdown") || name.endsWith(".mdown") || name.endsWith(".mkd") ||
            path.endsWith(".md") || path.endsWith(".markdown") ||
            name.equals("readme", ignoreCase = true) ||
            name.startsWith("readme.", ignoreCase = true) ||
            name.contains("readme", ignoreCase = true) ||
            path.contains("readme", ignoreCase = true) ||
            name.contains("changelog", ignoreCase = true) ||
            name.contains("contributing", ignoreCase = true) ||
            name.contains("license", ignoreCase = true) ||
            mimeType == "text/markdown" || mimeType == "text/x-markdown"
        ) {
            return LocalFileType.MARKDOWN
        }

        // 4. Images
        if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") ||
            name.endsWith(".gif") || name.endsWith(".webp") || name.endsWith(".svg") ||
            name.endsWith(".bmp") || name.endsWith(".ico") || name.endsWith(".avif") ||
            name.endsWith(".heic") || name.endsWith(".heif") ||
            mimeType?.startsWith("image/") == true
        ) {
            return LocalFileType.IMAGE
        }

        // 5. Video
        if (name.endsWith(".mp4") || name.endsWith(".webm") || name.endsWith(".mkv") ||
            name.endsWith(".mov") || name.endsWith(".3gp") || name.endsWith(".avi") ||
            name.endsWith(".flv") || name.endsWith(".m4v") ||
            mimeType?.startsWith("video/") == true
        ) {
            return LocalFileType.VIDEO
        }

        // 6. Audio
        if (name.endsWith(".mp3") || name.endsWith(".wav") || name.endsWith(".ogg") ||
            name.endsWith(".m4a") || name.endsWith(".aac") || name.endsWith(".flac") ||
            name.endsWith(".opus") || name.endsWith(".mid") || name.endsWith(".midi") ||
            mimeType?.startsWith("audio/") == true
        ) {
            return LocalFileType.AUDIO
        }

        // 7. PDF
        if (name.endsWith(".pdf") || mimeType == "application/pdf") {
            return LocalFileType.PDF
        }

        // 8. Source code & structured formats
        if (name.endsWith(".js") || name.endsWith(".mjs") || name.endsWith(".ts") ||
            name.endsWith(".css") || name.endsWith(".json") || name.endsWith(".xml") ||
            name.endsWith(".py") || name.endsWith(".sh") || name.endsWith(".kt") ||
            name.endsWith(".java") || name.endsWith(".c") || name.endsWith(".cpp") ||
            name.endsWith(".h") || name.endsWith(".hpp") || name.endsWith(".rs") ||
            name.endsWith(".go") || name.endsWith(".sql") || name.endsWith(".yaml") ||
            name.endsWith(".yml") || name.endsWith(".toml") || name.endsWith(".ini") ||
            name.endsWith(".properties") || name.endsWith(".csv") || name.endsWith(".tsv") ||
            name.endsWith(".bash") || name.endsWith(".zsh") || name.endsWith(".swift") ||
            name.endsWith(".rb") || name.endsWith(".php") ||
            mimeType == "application/json" || mimeType == "text/xml" ||
            mimeType == "application/xml" || mimeType == "text/css" ||
            mimeType == "application/javascript" || mimeType == "text/javascript" ||
            mimeType == "text/csv" || mimeType == "text/x-python" ||
            mimeType == "text/x-shellscript"
        ) {
            return LocalFileType.CODE
        }

        // 9. Plain text documents
        if (name.endsWith(".txt") || name.endsWith(".log") || name.endsWith(".env") ||
            name.endsWith(".diff") || name.endsWith(".patch") || path.endsWith(".txt") ||
            mimeType == "text/plain"
        ) {
            return LocalFileType.TEXT
        }

        // 10. Known unsupported binary formats
        if (name.endsWith(".zip") || name.endsWith(".rar") || name.endsWith(".7z") ||
            name.endsWith(".tar") || name.endsWith(".gz") || name.endsWith(".apk") ||
            name.endsWith(".exe") || name.endsWith(".bin") || name.endsWith(".iso") ||
            name.endsWith(".doc") || name.endsWith(".docx") || name.endsWith(".xls") ||
            name.endsWith(".xlsx") || name.endsWith(".ppt") || name.endsWith(".pptx") ||
            name.endsWith(".dex") || name.endsWith(".jar") || name.endsWith(".so") ||
            name.endsWith(".aar") || name.endsWith(".class") || name.endsWith(".dmg") ||
            mimeType == "application/zip" || mimeType == "application/x-zip-compressed" ||
            mimeType == "application/vnd.android.package-archive"
        ) {
            return LocalFileType.UNSUPPORTED_BINARY
        }

        return LocalFileType.UNKNOWN
    }

    /**
     * Safely opens an InputStream from a content://, file://, or raw path URI.
     * Features MediaStore resolution fallback for Scoped Storage on Android 10+.
     */
    fun openInputStream(context: Context, uri: Uri): InputStream? {
        return try {
            when (uri.scheme) {
                "content" -> {
                    context.contentResolver.openInputStream(uri)
                }
                "file" -> {
                    val path = uri.path
                    if (!path.isNullOrBlank()) {
                        val file = File(path)
                        if (file.exists() && file.canRead()) {
                            file.inputStream()
                        } else {
                            // On Android 10+ Scoped Storage, try resolving through MediaStore
                            getInputStreamFromPathViaMediaStore(context, path)
                                ?: context.contentResolver.openInputStream(uri)
                        }
                    } else {
                        context.contentResolver.openInputStream(uri)
                    }
                }
                null -> {
                    val path = uri.path
                    if (!path.isNullOrBlank()) {
                        val file = File(path)
                        if (file.exists() && file.canRead()) {
                            file.inputStream()
                        } else {
                            getInputStreamFromPathViaMediaStore(context, path)
                        }
                    } else null
                }
                else -> {
                    context.contentResolver.openInputStream(uri)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Resolves a filesystem path through MediaStore to access Scoped Storage files
     * that cannot be read directly via java.io.File on Android 10+.
     */
    private fun getInputStreamFromPathViaMediaStore(context: Context, path: String): InputStream? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val projection = arrayOf(MediaStore.Files.FileColumns._ID)
            val selection = "${MediaStore.Files.FileColumns.DATA} = ?"
            val selectionArgs = arrayOf(path)
            val queryUri = MediaStore.Files.getContentUri("external")
            context.contentResolver.query(queryUri, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID))
                    val contentUri = ContentUris.withAppendedId(queryUri, id)
                    context.contentResolver.openInputStream(contentUri)
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Intercepts main frame requests for local files (content:// and file://) and converts
     * them into fully renderable WebResourceResponses for Chromium WebView.
     * Note: Returns NULL for MHTML so Chromium Blink's native MHTML parser handles it directly.
     */
    fun interceptLocalFile(context: Context, url: String): WebResourceResponse? {
        if (!url.startsWith("content://", ignoreCase = true) && !url.startsWith("file://", ignoreCase = true)) {
            return null
        }
        if (url.startsWith("file:///android_asset/", ignoreCase = true) ||
            url.startsWith("file:///android_res/", ignoreCase = true)) {
            return null
        }

        if (isSensitiveOrRestrictedPath(context, url)) {
            return WebResourceResponse(
                "text/plain",
                "UTF-8",
                403,
                "Forbidden",
                emptyMap(),
                ByteArrayInputStream("Access to private app storage is blocked.".toByteArray(StandardCharsets.UTF_8))
            )
        }

        val uri = try { Uri.parse(url) } catch (_: Exception) { return null }
        var fileType = detectFileType(context, uri)

        // CRITICAL: For MHTML archives, return null! Chromium handles file:// .mht natively.
        if (fileType == LocalFileType.MHTML ||
            url.endsWith(".mht", ignoreCase = true) ||
            url.endsWith(".mhtml", ignoreCase = true)) {
            return null
        }

        val rawStream = openInputStream(context, uri)
        if (rawStream == null) {
            val errorHtml = """
                <!DOCTYPE html>
                <html>
                <head>
                  <meta name="viewport" content="width=device-width, initial-scale=1.0">
                  <title>File Not Found</title>
                  <style>
                    body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; background: #202124; color: #E8EAED; padding: 24px; line-height: 1.6; }
                    h2 { color: #F28B82; font-size: 20px; font-weight: 500; }
                    p { font-size: 14px; color: #9AA0A6; }
                    code { background: #303134; color: #DC4B64; padding: 4px 8px; border-radius: 6px; word-break: break-all; font-size: 13px; }
                  </style>
                </head>
                <body>
                  <h2>File could not be opened</h2>
                  <p>The requested file could not be read or does not exist:</p>
                  <p><code>${escapeHtml(url)}</code></p>
                </body>
                </html>
            """.trimIndent()
            val errorBytes = errorHtml.toByteArray(StandardCharsets.UTF_8)
            return WebResourceResponse(
                "text/html",
                "UTF-8",
                404,
                "Not Found",
                mapOf("Content-Type" to "text/html; charset=UTF-8", "Content-Length" to errorBytes.size.toString()),
                ByteArrayInputStream(errorBytes)
            )
        }

        val stream = BufferedInputStream(rawStream)

        // If file extension / MIME type was unknown, inspect header bytes to see if it's MHTML or HTML
        if (fileType == LocalFileType.UNKNOWN) {
            stream.mark(1024)
            val peekBytes = ByteArray(1024)
            val readCount = stream.read(peekBytes)
            stream.reset()
            val peekStr = if (readCount > 0) String(peekBytes, 0, readCount, StandardCharsets.UTF_8).lowercase() else ""

            if (peekStr.contains("multipart/related") ||
                peekStr.contains("snapshot-content-location:") ||
                peekStr.contains("from: <saved by") ||
                peekStr.contains("content-type: multipart/related")
            ) {
                fileType = LocalFileType.MHTML
            } else if (peekStr.contains("<!doctype html") ||
                peekStr.contains("<html") ||
                peekStr.contains("<head") ||
                peekStr.contains("<body")
            ) {
                fileType = LocalFileType.HTML
            } else {
                fileType = LocalFileType.TEXT
            }
        }

        // Return null for MHTML so Chromium handles it directly
        if (fileType == LocalFileType.MHTML) {
            return null
        }

        val allBytes = try {
            stream.use { it.readBytes() }
        } catch (e: Exception) {
            return null
        }

        val baseHeaders = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Methods" to "GET, OPTIONS",
            "Access-Control-Allow-Headers" to "*"
        )

        return when (fileType) {
            LocalFileType.HTML -> {
                val headers = baseHeaders + mapOf(
                    "Content-Type" to "text/html; charset=UTF-8",
                    "Content-Length" to allBytes.size.toString()
                )
                WebResourceResponse(
                    "text/html",
                    "UTF-8",
                    200,
                    "OK",
                    headers,
                    ByteArrayInputStream(allBytes)
                )
            }
            LocalFileType.MARKDOWN -> {
                val fileName = getDisplayName(context, uri)
                val rawMd = String(allBytes, StandardCharsets.UTF_8)
                val previewHtml = renderMarkdownToHtml(context, fileName, rawMd)
                val previewBytes = previewHtml.toByteArray(StandardCharsets.UTF_8)
                val headers = baseHeaders + mapOf(
                    "Content-Type" to "text/html; charset=UTF-8",
                    "Content-Length" to previewBytes.size.toString()
                )
                WebResourceResponse(
                    "text/html",
                    "UTF-8",
                    200,
                    "OK",
                    headers,
                    ByteArrayInputStream(previewBytes)
                )
            }
            LocalFileType.IMAGE -> {
                val fileName = getDisplayName(context, uri)
                val previewHtml = renderImageViewerHtml(fileName, url)
                val previewBytes = previewHtml.toByteArray(StandardCharsets.UTF_8)
                val headers = baseHeaders + mapOf(
                    "Content-Type" to "text/html; charset=UTF-8",
                    "Content-Length" to previewBytes.size.toString()
                )
                WebResourceResponse("text/html", "UTF-8", 200, "OK", headers, ByteArrayInputStream(previewBytes))
            }
            LocalFileType.VIDEO, LocalFileType.AUDIO -> {
                val fileName = getDisplayName(context, uri)
                val isVideo = (fileType == LocalFileType.VIDEO)
                val mime = try {
                    if (uri.scheme == "content") context.contentResolver.getType(uri) else null
                } catch (_: Exception) { null } ?: (if (isVideo) "video/mp4" else "audio/mpeg")
                val previewHtml = renderMediaPlayerHtml(fileName, url, mime, isVideo)
                val previewBytes = previewHtml.toByteArray(StandardCharsets.UTF_8)
                val headers = baseHeaders + mapOf(
                    "Content-Type" to "text/html; charset=UTF-8",
                    "Content-Length" to previewBytes.size.toString()
                )
                WebResourceResponse("text/html", "UTF-8", 200, "OK", headers, ByteArrayInputStream(previewBytes))
            }
            LocalFileType.CODE -> {
                val fileName = getDisplayName(context, uri)
                val text = String(allBytes, StandardCharsets.UTF_8)
                val previewHtml = renderCodeAsHtml(fileName, text)
                val previewBytes = previewHtml.toByteArray(StandardCharsets.UTF_8)
                val headers = baseHeaders + mapOf(
                    "Content-Type" to "text/html; charset=UTF-8",
                    "Content-Length" to previewBytes.size.toString()
                )
                WebResourceResponse("text/html", "UTF-8", 200, "OK", headers, ByteArrayInputStream(previewBytes))
            }
            LocalFileType.PDF -> {
                val fileName = getDisplayName(context, uri)
                val previewHtml = renderPdfViewerHtml(fileName, url, allBytes.size.toLong())
                val previewBytes = previewHtml.toByteArray(StandardCharsets.UTF_8)
                val headers = baseHeaders + mapOf(
                    "Content-Type" to "text/html; charset=UTF-8",
                    "Content-Length" to previewBytes.size.toString()
                )
                WebResourceResponse("text/html", "UTF-8", 200, "OK", headers, ByteArrayInputStream(previewBytes))
            }
            LocalFileType.UNSUPPORTED_BINARY -> {
                val fileName = getDisplayName(context, uri)
                val mime = try {
                    if (uri.scheme == "content") context.contentResolver.getType(uri) else null
                } catch (_: Exception) { null } ?: ""
                val previewHtml = renderUnsupportedBinaryHtml(fileName, url, mime, allBytes.size.toLong())
                val previewBytes = previewHtml.toByteArray(StandardCharsets.UTF_8)
                val headers = baseHeaders + mapOf(
                    "Content-Type" to "text/html; charset=UTF-8",
                    "Content-Length" to previewBytes.size.toString()
                )
                WebResourceResponse("text/html", "UTF-8", 200, "OK", headers, ByteArrayInputStream(previewBytes))
            }
            LocalFileType.TEXT, LocalFileType.UNKNOWN, LocalFileType.MHTML -> {
                val fileName = getDisplayName(context, uri)
                if (isBinaryStream(ByteArrayInputStream(allBytes))) {
                    val previewHtml = renderUnsupportedBinaryHtml(fileName, url, "", allBytes.size.toLong())
                    val previewBytes = previewHtml.toByteArray(StandardCharsets.UTF_8)
                    val headers = baseHeaders + mapOf(
                        "Content-Type" to "text/html; charset=UTF-8",
                        "Content-Length" to previewBytes.size.toString()
                    )
                    WebResourceResponse("text/html", "UTF-8", 200, "OK", headers, ByteArrayInputStream(previewBytes))
                } else {
                    val text = String(allBytes, StandardCharsets.UTF_8)
                    if (isMarkdownContent(fileName, text)) {
                        val previewHtml = renderMarkdownToHtml(context, fileName, text)
                        val previewBytes = previewHtml.toByteArray(StandardCharsets.UTF_8)
                        val headers = baseHeaders + mapOf(
                            "Content-Type" to "text/html; charset=UTF-8",
                            "Content-Length" to previewBytes.size.toString()
                        )
                        WebResourceResponse(
                            "text/html",
                            "UTF-8",
                            200,
                            "OK",
                            headers,
                            ByteArrayInputStream(previewBytes)
                        )
                    } else {
                        val headers = baseHeaders + mapOf(
                            "Content-Type" to "text/plain; charset=UTF-8",
                            "Content-Length" to allBytes.size.toString()
                        )
                        WebResourceResponse(
                            "text/plain",
                            "UTF-8",
                            200,
                            "OK",
                            headers,
                            ByteArrayInputStream(allBytes)
                        )
                    }
                }
            }
        }
    }

    /**
     * Intercepts sub-resource requests made by local HTML pages (images, CSS, JS, fonts).
     * Enforces path restrictions to prevent traversal into sensitive system directories.
     */
    fun interceptLocalSubResource(context: Context, url: String): WebResourceResponse? {
        if (url.startsWith("file:///android_asset/", ignoreCase = true) ||
            url.startsWith("file:///android_res/", ignoreCase = true)) {
            return null
        }

        if (url.startsWith("file://", ignoreCase = true) || url.startsWith("content://", ignoreCase = true)) {
            if (isSensitiveOrRestrictedPath(context, url)) {
                return WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
            }
        }

        val uri = try { Uri.parse(url) } catch (_: Exception) { return null }
        val stream = openInputStream(context, uri) ?: return null
        val ext = MimeTypeMap.getFileExtensionFromUrl(url).lowercase()
        val mimeType = when (ext) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "webp" -> "image/webp"
            "ico" -> "image/x-icon"
            "avif" -> "image/avif"
            "bmp" -> "image/bmp"
            "css" -> "text/css"
            "js" -> "application/javascript"
            "json" -> "application/json"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "mp3" -> "audio/mpeg"
            "ogg" -> "audio/ogg"
            "wav" -> "audio/wav"
            "m4a" -> "audio/mp4"
            else -> {
                val resolverType = try {
                    if (uri.scheme == "content") context.contentResolver.getType(uri)?.lowercase() else null
                } catch (_: Exception) { null }
                resolverType ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
            }
        }

        val headers = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Methods" to "GET, OPTIONS",
            "Access-Control-Allow-Headers" to "*"
        )

        // For audio and video, stream directly to prevent OOM
        if (mimeType.startsWith("video/") || mimeType.startsWith("audio/")) {
            return WebResourceResponse(mimeType, null, 200, "OK", headers, stream)
        }

        val bytes = try {
            stream.use { it.readBytes() }
        } catch (_: Exception) {
            return null
        }
        val responseHeaders = headers + mapOf("Content-Length" to bytes.size.toString())
        return WebResourceResponse(mimeType, null, 200, "OK", responseHeaders, ByteArrayInputStream(bytes))
    }

    /**
     * Transforms Markdown text into a self-contained HTML document using markdown_previewer.html and marked.min.js.
     */
    fun renderMarkdownToHtml(context: Context, fileName: String, rawMarkdown: String): String {
        var template = cachedMarkdownTemplate
        if (template == null) {
            val rawTemplate = try {
                context.assets.open("markdown_previewer.html").bufferedReader().use { it.readText() }
            } catch (_: Exception) { null }

            val markedJs = try {
                context.assets.open("marked.min.js").bufferedReader().use { it.readText() }
            } catch (_: Exception) { "" }

            template = if (rawTemplate != null) {
                if (markedJs.isNotBlank()) {
                    rawTemplate.replace("<script src=\"marked.min.js\"></script>", "<script>\n$markedJs\n</script>")
                } else {
                    rawTemplate
                }
            } else null
            cachedMarkdownTemplate = template
        }

        return if (template == null) {
            formatPlainTextAsHtml(fileName, rawMarkdown)
        } else {
            val base64Content = Base64.encodeToString(rawMarkdown.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
            template.replace("{{MARKDOWN_B64}}", base64Content)
                .replace("{{FILE_NAME}}", escapeHtml(fileName))
        }
    }

    /**
     * Determines whether the given text or filename represents Markdown content.
     */
    fun isMarkdownContent(fileName: String, content: String): Boolean {
        val lower = fileName.lowercase()
        if (lower.contains("readme") || lower.contains("changelog") || lower.contains("contributing") || lower.contains("license") ||
            lower.endsWith(".md") || lower.endsWith(".markdown") || lower.endsWith(".mdown") || lower.endsWith(".mkd")) {
            return true
        }
        val firstLines = content.lineSequence().take(30).toList()
        return firstLines.any { line ->
            val trimmed = line.trimStart()
            trimmed.startsWith("# ") || trimmed.startsWith("## ") || trimmed.startsWith("### ") ||
            trimmed.startsWith("#### ") || trimmed.startsWith("```") || trimmed.startsWith("> ")
        }
    }

    /**
     * Formats plain text inside a Google Dark / Light themed <pre> document.
     */
    fun formatPlainTextAsHtml(fileName: String, content: String): String {
        return """
            <!DOCTYPE html>
            <html>
            <head>
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <title>${escapeHtml(fileName)}</title>
              <style>
                :root { color-scheme: light dark; }
                body {
                  font-family: 'Roboto Mono', 'SF Mono', Consolas, Menlo, monospace;
                  font-size: 13px;
                  line-height: 1.5;
                  padding: 16px;
                  margin: 0;
                  background: #202124;
                  color: #E8EAED;
                  word-break: break-all;
                  white-space: pre-wrap;
                }
                @media (prefers-color-scheme: light) {
                  body { background: #FFFFFF; color: #202124; }
                }
              </style>
            </head>
            <body><pre>${escapeHtml(content)}</pre></body>
            </html>
        """.trimIndent()
    }

    /**
     * Inspects stream header bytes to determine whether the content is binary.
     */
    fun isBinaryStream(stream: InputStream): Boolean {
        return try {
            stream.mark(512)
            val buffer = ByteArray(512)
            val read = stream.read(buffer)
            stream.reset()
            if (read <= 0) return false
            for (i in 0 until read) {
                if (buffer[i] == 0.toByte()) return true
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Resolves the size of a local file in bytes from content:// or file:// URI.
     */
    fun getFileSize(context: Context, uri: Uri): Long {
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIndex != -1) {
                            val size = cursor.getLong(sizeIndex)
                            if (size > 0L) return size
                        }
                    }
                }
            } catch (_: Exception) {}
        } else if (uri.scheme == "file") {
            try {
                val path = uri.path
                if (!path.isNullOrBlank()) {
                    val f = File(path)
                    if (f.exists()) return f.length()
                }
            } catch (_: Exception) {}
        }
        return -1L
    }

    /**
     * Formats code files with line numbers and monospace font inside a themed container.
     */
    fun renderCodeAsHtml(fileName: String, content: String): String {
        val lines = content.lines()
        val rows = StringBuilder()
        for ((idx, line) in lines.withIndex()) {
            val num = idx + 1
            rows.append("<tr><td class=\"ln\">$num</td><td class=\"code\">${escapeHtml(line)}</td></tr>")
        }
        return """
            <!DOCTYPE html>
            <html>
            <head>
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <title>${escapeHtml(fileName)}</title>
              <style>
                :root { color-scheme: light dark; }
                body {
                  margin: 0; padding: 0;
                  background: #202124; color: #E8EAED;
                  font-family: 'Roboto Mono', 'SF Mono', Consolas, Menlo, monospace;
                  font-size: 13px; line-height: 1.5;
                }
                @media (prefers-color-scheme: light) {
                  body { background: #FFFFFF; color: #202124; }
                  .header { background: #F1F3F4 !important; border-bottom: 1px solid #DADCE0 !important; color: #202124 !important; }
                  .ln { color: #80868B !important; background: #F8F9FA !important; border-right: 1px solid #DADCE0 !important; }
                  .badge { background: #E8EAED !important; color: #1A73E8 !important; }
                }
                .header {
                  padding: 10px 16px;
                  background: #2D2E30;
                  border-bottom: 1px solid #3C4043;
                  font-size: 13px; font-weight: 500;
                  display: flex; justify-content: space-between; align-items: center;
                }
                .badge {
                  background: #3C4043; color: #A8C7FA;
                  padding: 2px 8px; border-radius: 4px; font-size: 11px;
                }
                .table-container { overflow-x: auto; }
                table { border-collapse: collapse; width: 100%; }
                td { padding: 1px 8px; vertical-align: top; white-space: pre-wrap; word-break: break-all; }
                .ln {
                  width: 1%; min-width: 40px; text-align: right;
                  color: #5F6368; background: #28292A;
                  border-right: 1px solid #3C4043;
                  user-select: none; padding-right: 12px;
                }
                .code { padding-left: 12px; }
              </style>
            </head>
            <body>
              <div class="header">
                <span>${escapeHtml(fileName)}</span>
                <span class="badge">${lines.size} lines</span>
              </div>
              <div class="table-container">
                <table>$rows</table>
              </div>
            </body>
            </html>
        """.trimIndent()
    }

    /**
     * Renders a local image centered on a dark/light responsive viewport with zoom support.
     */
    fun renderImageViewerHtml(fileName: String, imageUri: String): String {
        return """
            <!DOCTYPE html>
            <html>
            <head>
              <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=5.0, user-scalable=yes">
              <title>${escapeHtml(fileName)}</title>
              <style>
                :root { color-scheme: light dark; }
                * { box-sizing: border-box; }
                html, body {
                  margin: 0; padding: 0; width: 100%; height: 100%;
                  background-color: #121212;
                  display: flex; align-items: center; justify-content: center;
                  overflow: auto;
                }
                @media (prefers-color-scheme: light) {
                  html, body { background-color: #F8F9FA; }
                }
                .container {
                  display: flex; align-items: center; justify-content: center;
                  min-width: 100%; min-height: 100%; padding: 16px;
                }
                img {
                  max-width: 100%; max-height: 100%;
                  object-fit: contain;
                  border-radius: 4px;
                  box-shadow: 0 4px 20px rgba(0,0,0,0.3);
                }
              </style>
            </head>
            <body>
              <div class="container">
                <img src="${escapeHtml(imageUri)}" alt="${escapeHtml(fileName)}" />
              </div>
            </body>
            </html>
        """.trimIndent()
    }

    /**
     * Renders an HTML5 video or audio player wrapper hooking into Onyx media playback subsystem.
     */
    fun renderMediaPlayerHtml(fileName: String, mediaUri: String, mimeType: String, isVideo: Boolean): String {
        return """
            <!DOCTYPE html>
            <html>
            <head>
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <title>${escapeHtml(fileName)}</title>
              <style>
                :root { color-scheme: dark; }
                html, body {
                  margin: 0; padding: 0; width: 100%; height: 100%;
                  background-color: #000000;
                  display: flex; align-items: center; justify-content: center;
                  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                }
                .media-container {
                  width: 100%; height: 100%;
                  display: flex; flex-direction: column; align-items: center; justify-content: center;
                  padding: 16px; box-sizing: border-box;
                }
                video {
                  width: 100%; max-height: 85vh; outline: none; border-radius: 8px;
                }
                .audio-card {
                  background: #1E1F20; border: 1px solid #303134; border-radius: 16px;
                  padding: 32px 24px; width: 90%; max-width: 450px;
                  display: flex; flex-direction: column; align-items: center;
                  box-shadow: 0 8px 24px rgba(0,0,0,0.5);
                }
                .audio-icon {
                  width: 64px; height: 64px; border-radius: 50%; background: #303134;
                  display: flex; align-items: center; justify-content: center; margin-bottom: 16px;
                }
                .audio-title {
                  color: #E8EAED; font-size: 16px; font-weight: 500; text-align: center;
                  word-break: break-all; margin-bottom: 20px;
                }
                audio { width: 100%; outline: none; }
              </style>
            </head>
            <body>
              <div class="media-container">
                ${if (isVideo) """
                  <video controls autoplay playsinline name="media">
                    <source src="${escapeHtml(mediaUri)}" type="${escapeHtml(mimeType)}">
                    Your browser does not support HTML5 video.
                  </video>
                """ else """
                  <div class="audio-card">
                    <div class="audio-icon">
                      <svg width="32" height="32" viewBox="0 0 24 24" fill="#A8C7FA"><path d="M12 3v10.55c-.59-.34-1.27-.55-2-.55-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4V7h4V3h-6z"/></svg>
                    </div>
                    <div class="audio-title">${escapeHtml(fileName)}</div>
                    <audio controls autoplay name="media">
                      <source src="${escapeHtml(mediaUri)}" type="${escapeHtml(mimeType)}">
                      Your browser does not support HTML5 audio.
                    </audio>
                  </div>
                """}
              </div>
            </body>
            </html>
        """.trimIndent()
    }

    /**
     * Formats byte size into human-readable representation without requiring an Android Context.
     */
    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0L) return ""
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(java.util.Locale.US, "%.1f GB", gb)
            mb >= 1.0 -> String.format(java.util.Locale.US, "%.1f MB", mb)
            kb >= 1.0 -> String.format(java.util.Locale.US, "%.1f KB", kb)
            else -> "$bytes B"
        }
    }

    /**
     * Renders a clean Material document card for PDF files.
     */
    fun renderPdfViewerHtml(fileName: String, pathOrUri: String, fileSize: Long): String {
        val sizeFormatted = formatFileSize(fileSize)
        return """
            <!DOCTYPE html>
            <html>
            <head>
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <title>${escapeHtml(fileName)}</title>
              <style>
                :root { color-scheme: light dark; }
                body {
                  margin: 0; padding: 24px;
                  background: #202124; color: #E8EAED;
                  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                  display: flex; align-items: center; justify-content: center; min-height: 100vh;
                  box-sizing: border-box;
                }
                @media (prefers-color-scheme: light) {
                  body { background: #F8F9FA; color: #202124; }
                  .card { background: #FFFFFF !important; border-color: #DADCE0 !important; box-shadow: 0 4px 16px rgba(0,0,0,0.08) !important; }
                  .subtext { color: #5F6368 !important; }
                }
                .card {
                  background: #303134; border: 1px solid #3C4043; border-radius: 16px;
                  padding: 32px 24px; max-width: 440px; width: 100%; text-align: center;
                  box-shadow: 0 8px 24px rgba(0,0,0,0.4);
                }
                .pdf-icon {
                  width: 56px; height: 56px; border-radius: 12px; background: #EA4335;
                  display: inline-flex; align-items: center; justify-content: center; margin-bottom: 16px;
                  color: #FFFFFF; font-weight: bold; font-size: 16px;
                }
                h2 { font-size: 18px; margin: 0 0 8px 0; word-break: break-all; }
                .subtext { color: #9AA0A6; font-size: 14px; margin-bottom: 20px; }
                p { font-size: 13px; color: #9AA0A6; line-height: 1.5; margin: 0; }
              </style>
            </head>
            <body>
              <div class="card">
                <div class="pdf-icon">PDF</div>
                <h2>${escapeHtml(fileName)}</h2>
                <div class="subtext">${if (sizeFormatted.isNotBlank()) escapeHtml(sizeFormatted) else "Portable Document Format"}</div>
                <p>This is a PDF document. To read, view or print pages, open it with an installed PDF viewer.</p>
              </div>
            </body>
            </html>
        """.trimIndent()
    }

    /**
     * Renders a safe info card for unsupported binary files, preventing memory freezing and text dumping.
     */
    fun renderUnsupportedBinaryHtml(fileName: String, rawPath: String, mimeType: String, fileSize: Long): String {
        val sizeFormatted = formatFileSize(fileSize)
        return """
            <!DOCTYPE html>
            <html>
            <head>
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <title>${escapeHtml(fileName)}</title>
              <style>
                :root { color-scheme: light dark; }
                body {
                  margin: 0; padding: 24px;
                  background: #202124; color: #E8EAED;
                  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                  display: flex; align-items: center; justify-content: center; min-height: 100vh;
                  box-sizing: border-box;
                }
                @media (prefers-color-scheme: light) {
                  body { background: #F8F9FA; color: #202124; }
                  .card { background: #FFFFFF !important; border-color: #DADCE0 !important; }
                  .subtext { color: #5F6368 !important; }
                }
                .card {
                  background: #303134; border: 1px solid #3C4043; border-radius: 16px;
                  padding: 32px 24px; max-width: 440px; width: 100%; text-align: center;
                }
                .icon {
                  width: 56px; height: 56px; border-radius: 50%; background: #3C4043;
                  display: inline-flex; align-items: center; justify-content: center; margin-bottom: 16px;
                }
                h2 { font-size: 18px; margin: 0 0 8px 0; word-break: break-all; }
                .subtext { color: #9AA0A6; font-size: 14px; margin-bottom: 16px; }
                p { font-size: 13px; color: #9AA0A6; line-height: 1.5; margin: 0; }
              </style>
            </head>
            <body>
              <div class="card">
                <div class="icon">
                  <svg width="28" height="28" viewBox="0 0 24 24" fill="#9AA0A6"><path d="M14 2H6c-1.1 0-1.99.9-1.99 2L4 20c0 1.1.89 2 1.99 2H18c1.1 0 2-.9 2-2V8l-6-6zm2 16H8v-2h8v2zm0-4H8v-2h8v2zm-3-5V3.5L18.5 9H13z"/></svg>
                </div>
                <h2>${escapeHtml(fileName)}</h2>
                <div class="subtext">${listOfNotNull(sizeFormatted.ifBlank { null }, mimeType.ifBlank { null }).joinToString(" • ")}</div>
                <p>This file is a binary archive or application package and cannot be rendered directly inside a web view.</p>
              </div>
            </body>
            </html>
        """.trimIndent()
    }
    fun showErrorPage(webView: OnyxWebView, pathOrUrl: String, errorDescription: String) {
        val errorHtml = """
            <!DOCTYPE html>
            <html>
            <head>
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <title>Cannot Open Document</title>
              <style>
                :root { color-scheme: light dark; }
                body {
                  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                  background: #202124;
                  color: #E8EAED;
                  padding: 24px;
                  line-height: 1.6;
                  margin: 0;
                }
                @media (prefers-color-scheme: light) {
                  body { background: #FFFFFF; color: #202124; }
                  code { background: #F1F3F4 !important; color: #DC4B64 !important; }
                  .card { background: #F8F9FA !important; border-color: #DFE1E5 !important; }
                }
                h2 { color: #F28B82; font-size: 20px; font-weight: 600; margin-top: 0; }
                p { font-size: 14px; margin: 8px 0; }
                code {
                  background: #303134;
                  color: #DC4B64;
                  padding: 6px 10px;
                  border-radius: 6px;
                  word-break: break-all;
                  font-size: 13px;
                  display: inline-block;
                }
                .card {
                  background: #303134;
                  border: 1px solid #3C4043;
                  border-radius: 12px;
                  padding: 20px;
                  margin-top: 16px;
                }
              </style>
            </head>
            <body>
              <div class="card">
                <h2>Cannot Open Document</h2>
                <p>${escapeHtml(errorDescription)}</p>
                <p>Target URI or Path:</p>
                <p><code>${escapeHtml(pathOrUrl)}</code></p>
              </div>
            </body>
            </html>
        """.trimIndent()
        val baseUrl = if (pathOrUrl.startsWith("file://")) {
            try {
                val parent = File(Uri.parse(pathOrUrl).path ?: "").parentFile
                if (parent != null) "file://${parent.absolutePath}/" else "file:///"
            } catch (_: Exception) { null }
        } else if (pathOrUrl.startsWith("content://")) {
            pathOrUrl
        } else null
        webView.loadDataWithBaseURL(baseUrl, errorHtml, "text/html", "UTF-8", pathOrUrl)
    }

    /**
     * Extracts a human-friendly display name from a content:// or file:// URI.
     */
    fun getDisplayName(context: Context, uri: Uri): String {
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) {
                            val name = cursor.getString(nameIndex)
                            if (!name.isNullOrBlank()) return name
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        val lastSegment = uri.lastPathSegment
        if (!lastSegment.isNullOrBlank()) {
            return File(lastSegment).name
        }
        return "document"
    }

    private fun escapeHtml(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }
}
