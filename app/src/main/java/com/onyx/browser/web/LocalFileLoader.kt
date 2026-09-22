package com.onyx.browser.web

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.WebViewClient
import com.onyx.browser.web.error.WebErrorHandler
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * High-performance resolver and loader for local documents (HTML, Markdown, plain text)
 * accessed via file://, content://, or absolute filesystem paths.
 */
object LocalFileLoader {

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
     * Supports HTML (.html, .htm, .xhtml), Markdown (.md, .markdown), and plain text.
     */
    fun loadLocalFile(
        context: Context,
        webView: OnyxWebView,
        rawUriOrPath: String,
        onTitleResolved: ((String) -> Unit)? = null
    ): Boolean {
        val uri = parseUri(rawUriOrPath)
        val fileName = getDisplayName(context, uri)
        val lowerName = fileName.lowercase()

        val isMarkdown = lowerName.endsWith(".md") || lowerName.endsWith(".markdown")
        val isHtml = lowerName.endsWith(".html") || lowerName.endsWith(".htm") || lowerName.endsWith(".xhtml")

        val inputStream: InputStream? = try {
            if (uri.scheme == "file") {
                val path = uri.path
                if (path != null) {
                    val file = File(path)
                    if (!file.exists() || !file.canRead()) {
                        showFileNotFoundError(context, webView, rawUriOrPath)
                        return true
                    }
                    file.inputStream()
                } else {
                    context.contentResolver.openInputStream(uri)
                }
            } else {
                context.contentResolver.openInputStream(uri)
            }
        } catch (_: Exception) {
            showFileNotFoundError(context, webView, rawUriOrPath)
            return true
        }

        if (inputStream == null) {
            showFileNotFoundError(context, webView, rawUriOrPath)
            return true
        }

        try {
            val contentBytes = inputStream.use { it.readBytes() }
            val contentString = String(contentBytes, StandardCharsets.UTF_8)

            onTitleResolved?.invoke(fileName)

            if (isMarkdown) {
                renderMarkdown(context, webView, contentBytes, fileName, rawUriOrPath)
            } else {
                val baseUrl = if (uri.scheme == "file" && uri.path != null) {
                    val parent = File(uri.path!!).parentFile
                    if (parent != null) "file://${parent.absolutePath}/" else "file:///"
                } else {
                    uri.toString()
                }
                webView.post {
                    webView.loadDataWithBaseURL(
                        baseUrl,
                        contentString,
                        if (isHtml) "text/html" else "text/plain",
                        "UTF-8",
                        rawUriOrPath
                    )
                }
            }
            return true
        } catch (_: Exception) {
            showFileNotFoundError(context, webView, rawUriOrPath)
            return true
        }
    }

    private fun renderMarkdown(
        context: Context,
        webView: OnyxWebView,
        markdownBytes: ByteArray,
        fileName: String,
        historyUrl: String
    ) {
        val template = try {
            context.assets.open("markdown_previewer.html").bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        }

        if (template == null) {
            val text = String(markdownBytes, StandardCharsets.UTF_8)
            webView.post {
                webView.loadDataWithBaseURL(
                    null,
                    "<pre style=\"padding:16px;white-space:pre-wrap;word-break:break-word;\">${escapeHtml(text)}</pre>",
                    "text/html",
                    "UTF-8",
                    historyUrl
                )
            }
            return
        }

        val base64Content = Base64.encodeToString(markdownBytes, Base64.NO_WRAP)
        val populatedHtml = template
            .replace("{{MARKDOWN_B64}}", base64Content)
            .replace("{{FILE_NAME}}", escapeHtml(fileName))

        webView.post {
            webView.loadDataWithBaseURL(
                "file:///android_asset/",
                populatedHtml,
                "text/html",
                "UTF-8",
                historyUrl
            )
        }
    }

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

    private fun showFileNotFoundError(context: Context, webView: OnyxWebView, url: String) {
        val onyxError = WebErrorHandler.resolveNetworkError(
            failingUrl = url,
            errorCode = WebViewClient.ERROR_FILE_NOT_FOUND,
            description = "net::ERR_FILE_NOT_FOUND",
            isNetworkConnected = true
        )
        webView.post {
            try {
                val template = context.assets.open("error_page.html").bufferedReader().use { it.readText() }
                val populated = template.replace("{{ERROR_JSON}}", onyxError.toJson())
                webView.loadDataWithBaseURL(url, populated, "text/html", "UTF-8", url)
            } catch (_: Exception) {
                webView.loadUrl(url)
            }
        }
    }

    private fun escapeHtml(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }
}
