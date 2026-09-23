package com.onyx.browser.web

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.MimeTypeMap
import android.webkit.WebResourceResponse
import android.webkit.WebViewClient
import com.onyx.browser.web.error.WebErrorHandler
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * High-performance resolver, interceptor, and loader for local documents:
 * - Offline Web Archives (.mht, .mhtml, multipart/related, message/rfc822)
 * - HTML Documents (.html, .htm, .xhtml)
 * - Markdown Documents (.md, .markdown)
 * - Plain Text Documents (.txt, .log)
 *
 * Supports file://, content://, and raw filesystem paths across modern scoped-storage Android.
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
     */
    fun isLocalFile(input: String): Boolean {
        val trimmed = input.trim()
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
            lower.endsWith(".md") || lower.endsWith(".markdown") || lower.endsWith(".txt")) {
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
     * Reads and loads a local document into the given OnyxWebView.
     */
    fun loadLocalFile(
        context: Context,
        webView: OnyxWebView,
        rawUriOrPath: String,
        onTitleResolved: ((String) -> Unit)? = null
    ): Boolean {
        val uri = parseUri(rawUriOrPath)
        val fileName = getDisplayName(context, uri)
        onTitleResolved?.invoke(fileName)
        webView.post {
            webView.loadUrl(uri.toString())
        }
        return true
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
     */
    fun openInputStream(context: Context, uri: Uri): InputStream? {
        return try {
            if (uri.scheme == "content") {
                context.contentResolver.openInputStream(uri)
            } else if (uri.scheme == "file") {
                val path = uri.path
                if (!path.isNullOrBlank()) {
                    val file = File(path)
                    if (file.exists() && file.canRead()) {
                        file.inputStream()
                    } else {
                        context.contentResolver.openInputStream(uri)
                    }
                } else {
                    context.contentResolver.openInputStream(uri)
                }
            } else if (uri.scheme == null && uri.path != null) {
                val file = File(uri.path!!)
                if (file.exists() && file.canRead()) {
                    file.inputStream()
                } else null
            } else {
                context.contentResolver.openInputStream(uri)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Intercepts main frame requests for local files (content:// and file://) and converts
     * them into fully renderable WebResourceResponses for Chromium WebView.
     */
    fun interceptLocalFile(context: Context, url: String): WebResourceResponse? {
        if (!url.startsWith("content://", ignoreCase = true) && !url.startsWith("file://", ignoreCase = true)) {
            return null
        }
        val uri = try { Uri.parse(url) } catch (_: Exception) { return null }
        var fileType = detectFileType(context, uri)

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
            return WebResourceResponse(
                "text/html",
                "UTF-8",
                404,
                "Not Found",
                mapOf("Content-Type" to "text/html; charset=UTF-8"),
                ByteArrayInputStream(errorHtml.toByteArray(StandardCharsets.UTF_8))
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

        val headers = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Methods" to "GET, OPTIONS",
            "Access-Control-Allow-Headers" to "*"
        )

        return when (fileType) {
            LocalFileType.MHTML -> {
                // Chromium's internal Blink MHTMLArchive parser handles multipart/related seamlessly
                WebResourceResponse(
                    "multipart/related",
                    null,
                    200,
                    "OK",
                    headers + ("Content-Type" to "multipart/related"),
                    stream
                )
            }
            LocalFileType.HTML -> {
                WebResourceResponse(
                    "text/html",
                    "UTF-8",
                    200,
                    "OK",
                    headers + ("Content-Type" to "text/html; charset=UTF-8"),
                    stream
                )
            }
            LocalFileType.MARKDOWN -> {
                val renderedStream = renderMarkdownToStream(context, uri, stream)
                if (renderedStream != null) {
                    WebResourceResponse(
                        "text/html",
                        "UTF-8",
                        200,
                        "OK",
                        headers + ("Content-Type" to "text/html; charset=UTF-8"),
                        renderedStream
                    )
                } else {
                    WebResourceResponse(
                        "text/plain",
                        "UTF-8",
                        200,
                        "OK",
                        headers + ("Content-Type" to "text/plain; charset=UTF-8"),
                        stream
                    )
                }
            }
            LocalFileType.TEXT, LocalFileType.UNKNOWN -> {
                WebResourceResponse(
                    "text/plain",
                    "UTF-8",
                    200,
                    "OK",
                    headers + ("Content-Type" to "text/plain; charset=UTF-8"),
                    stream
                )
            }
        }
    }

    /**
     * Intercepts sub-resource requests made by local HTML pages (images, CSS, JS, fonts).
     */
    fun interceptLocalSubResource(context: Context, url: String): WebResourceResponse? {
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
        val headers = mapOf("Access-Control-Allow-Origin" to "*")
        return WebResourceResponse(mimeType, null, 200, "OK", headers, stream)
    }

    /**
     * Transforms a Markdown stream into a self-contained HTML preview using marked.js.
     */
    fun renderMarkdownToStream(context: Context, uri: Uri, existingStream: InputStream? = null): InputStream? {
        val fileName = getDisplayName(context, uri)
        val inputStream = existingStream ?: openInputStream(context, uri) ?: return null

        val bytes = inputStream.use { it.readBytes() }

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

        val htmlString = if (template == null) {
            val text = String(bytes, StandardCharsets.UTF_8)
            "<pre style=\"padding:16px;white-space:pre-wrap;word-break:break-word;\">${escapeHtml(text)}</pre>"
        } else {
            val base64Content = Base64.encodeToString(bytes, Base64.NO_WRAP)
            template.replace("{{MARKDOWN_B64}}", base64Content).replace("{{FILE_NAME}}", escapeHtml(fileName))
        }

        return ByteArrayInputStream(htmlString.toByteArray(StandardCharsets.UTF_8))
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
