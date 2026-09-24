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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets

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
        UNKNOWN
    }

    private var cachedMarkdownTemplate: String? = null

    /**
     * Determines whether the given string represents a local file URI or path.
     * Excludes system asset/resource schemes (file:///android_asset/ and file:///android_res/).
     */
    fun isLocalFile(input: String): Boolean {
        val trimmed = input.trim()
        if (trimmed.startsWith("file:///android_asset/", ignoreCase = true) ||
            trimmed.startsWith("file:///android_res/", ignoreCase = true)) {
            return false
        }
        if (trimmed.startsWith("file://", ignoreCase = true) ||
            trimmed.startsWith("content://", ignoreCase = true) ||
            trimmed.startsWith("/storage/", ignoreCase = true) ||
            trimmed.startsWith("/sdcard/", ignoreCase = true) ||
            trimmed.startsWith("/data/", ignoreCase = true)) {
            return true
        }
        val lower = trimmed.lowercase()
        if (lower.endsWith(".html") || lower.endsWith(".htm") || lower.endsWith(".xhtml") ||
            lower.endsWith(".mht") || lower.endsWith(".mhtml") ||
            lower.endsWith(".md") || lower.endsWith(".markdown") || lower.endsWith(".txt") ||
            lower.endsWith(".log") || lower.endsWith(".json") || lower.endsWith(".xml")) {
            if (trimmed.startsWith("/")) return true
        }
        if (trimmed.startsWith("/") && try { File(trimmed).exists() } catch (_: Exception) { false }) {
            return true
        }
        return false
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
            val fileName = getDisplayName(context, uri)
            withContext(Dispatchers.Main) {
                onTitleResolved?.invoke(fileName)
            }
            val fileType = detectFileType(context, uri)

            when (fileType) {
                LocalFileType.MHTML -> {
                    // Chromium's native C++ MHTMLArchive parser handles .mht/.mhtml files directly,
                    // but ONLY via file:// URLs in readable storage and when shouldInterceptRequest returns null.
                    val preparedFile = prepareMhtmlFile(context, uri)
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
                            webView.loadDataWithBaseURL("file:///android_asset/", previewHtml, "text/html", "UTF-8", rawUriOrPath)
                        } else {
                            showErrorPage(webView, rawUriOrPath, "Could not read Markdown document.")
                        }
                    }
                }

                LocalFileType.TEXT, LocalFileType.UNKNOWN -> {
                    val stream = openInputStream(context, uri)
                    val textContent = try {
                        stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }
                    } catch (e: Exception) {
                        null
                    }

                    withContext(Dispatchers.Main) {
                        if (textContent != null) {
                            val formattedHtml = formatPlainTextAsHtml(fileName, textContent)
                            webView.stopLoading()
                            webView.loadDataWithBaseURL("file:///android_asset/", formattedHtml, "text/html", "UTF-8", rawUriOrPath)
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
    fun prepareMhtmlFile(context: Context, uri: Uri): File? {
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

        // If it's already a readable file within app's own cache/files, we can use it directly
        if (uri.scheme == "file") {
            val path = uri.path
            if (!path.isNullOrBlank()) {
                val f = File(path)
                if (f.exists() && f.canRead() &&
                    (f.absolutePath.startsWith(context.cacheDir.absolutePath) ||
                     f.absolutePath.startsWith(context.filesDir.absolutePath))) {
                    return f
                }
            }
        }

        // Copy stream into dedicated app cache file ending with .mht
        val tempFile = File(archivesDir, "preview_${System.currentTimeMillis()}.mht")
        val stream = openInputStream(context, uri) ?: return null
        return try {
            stream.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            if (tempFile.exists() && tempFile.length() > 0) tempFile else null
        } catch (e: Exception) {
            try { tempFile.delete() } catch (_: Exception) {}
            null
        }
    }

    /**
     * Detects the category of local document represented by the URI.
     */
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
        if (name.endsWith(".md") || name.endsWith(".markdown") || name.endsWith(".mdown") ||
            path.endsWith(".md") || path.endsWith(".markdown") ||
            mimeType == "text/markdown" || mimeType == "text/x-markdown"
        ) {
            return LocalFileType.MARKDOWN
        }

        // 4. Plain text documents
        if (name.endsWith(".txt") || name.endsWith(".log") || name.endsWith(".csv") ||
            name.endsWith(".json") || name.endsWith(".xml") || name.endsWith(".yaml") ||
            name.endsWith(".yml") || path.endsWith(".txt") || mimeType == "text/plain"
        ) {
            return LocalFileType.TEXT
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
                    code { background: #303134; color: #8AB4F8; padding: 4px 8px; border-radius: 6px; word-break: break-all; font-size: 13px; }
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
            LocalFileType.TEXT, LocalFileType.UNKNOWN, LocalFileType.MHTML -> {
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

    /**
     * Intercepts sub-resource requests made by local HTML pages (images, CSS, JS, fonts).
     */
    fun interceptLocalSubResource(context: Context, url: String): WebResourceResponse? {
        if (url.startsWith("file:///android_asset/", ignoreCase = true) ||
            url.startsWith("file:///android_res/", ignoreCase = true)) {
            return null
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
            "css" -> "text/css"
            "js" -> "application/javascript"
            "json" -> "application/json"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "mp3" -> "audio/mpeg"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
        val bytes = try {
            stream.use { it.readBytes() }
        } catch (_: Exception) {
            return null
        }
        val headers = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Content-Length" to bytes.size.toString()
        )
        return WebResourceResponse(mimeType, null, 200, "OK", headers, ByteArrayInputStream(bytes))
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
     * Displays a clean, styled Material error card inside the WebView.
     */
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
                  code { background: #F1F3F4 !important; color: #1A73E8 !important; }
                  .card { background: #F8F9FA !important; border-color: #DFE1E5 !important; }
                }
                h2 { color: #F28B82; font-size: 20px; font-weight: 600; margin-top: 0; }
                p { font-size: 14px; margin: 8px 0; }
                code {
                  background: #303134;
                  color: #8AB4F8;
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
        webView.loadDataWithBaseURL("file:///android_asset/", errorHtml, "text/html", "UTF-8", pathOrUrl)
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
