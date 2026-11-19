package com.onyx.browser.ui.browser

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.print.PdfPrintHelper
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.MediaStore
import android.util.Log
import android.webkit.CookieManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.onyx.browser.R
import com.onyx.browser.data.local.AppDatabase
import com.onyx.browser.data.model.DownloadItem
import com.onyx.browser.download.DownloadHandler
import com.onyx.browser.download.DownloadNotificationHelper
import com.onyx.browser.download.FileUtils
import com.onyx.browser.web.BrowserWebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Controller responsible for saving and exporting web pages as MHTML web archives,
 * direct headless PDFs, or through the Android system print service.
 * Handles Scoped Storage / MediaStore resolution and download database integration.
 */
class PageExportCoordinator(
    private val activity: Activity,
    private val coroutineScope: CoroutineScope
) {

    fun showSavePageDialog(webView: BrowserWebView, title: String) {
        val options = arrayOf(
            activity.getString(R.string.save_as_original),
            activity.getString(R.string.save_as_web_archive),
            activity.getString(R.string.save_as_pdf),
            activity.getString(R.string.print_system_option)
        )
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.save_this_page)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> saveCurrentPageAsOriginal(webView, title)
                    1 -> saveCurrentPageAsMhtml(webView, title)
                    2 -> saveCurrentPageAsPdfDirect(webView, title)
                    3 -> printCurrentPageSystem(webView, title)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    fun saveCurrentPageAsOriginal(webView: BrowserWebView, title: String) {
        val pageUrl = webView.url ?: "about:blank"
        if (pageUrl.isBlank() || pageUrl.equals("about:blank", ignoreCase = true)) {
            Toast.makeText(activity, "No page to save", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(activity, R.string.saving_original_page, Toast.LENGTH_SHORT).show()
        val cleanTitle = title.trim().replace(Regex("[^a-zA-Z0-9.-]"), "_").trim('_', '.').take(50).ifBlank { "page" }

        coroutineScope.launch(Dispatchers.IO) {
            try {
                if (pageUrl.startsWith("file://", ignoreCase = true) || pageUrl.startsWith("content://", ignoreCase = true)) {
                    saveLocalPageAsOriginal(pageUrl, cleanTitle)
                    return@launch
                }
                if (pageUrl.startsWith("data:", ignoreCase = true)) {
                    saveDataUriAsOriginal(pageUrl, cleanTitle)
                    return@launch
                }

                // Attempt network download of original resource
                val savedViaNetwork = fetchAndSaveOriginalResource(webView, pageUrl, cleanTitle)
                if (!savedViaNetwork) {
                    withContext(Dispatchers.Main) {
                        saveDomAsHtmlFallback(webView, cleanTitle, pageUrl)
                    }
                }
            } catch (e: Exception) {
                Log.w("PageExportCoordinator", "Original page save error: ${e.message}, falling back to DOM")
                withContext(Dispatchers.Main) {
                    saveDomAsHtmlFallback(webView, cleanTitle, pageUrl)
                }
            }
        }
    }

    private suspend fun fetchAndSaveOriginalResource(
        webView: BrowserWebView,
        pageUrl: String,
        cleanTitle: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val userAgent = withContext(Dispatchers.Main) { webView.settings.userAgentString }
            val cookies = try {
                CookieManager.getInstance().getCookie(pageUrl) ?: ""
            } catch (_: Exception) { "" }

            val requestBuilder = Request.Builder()
                .url(pageUrl)
                .header("User-Agent", userAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8")
                .header("Referer", pageUrl)
            if (cookies.isNotBlank()) {
                requestBuilder.header("Cookie", cookies)
            }

            val response = HTTP_CLIENT.newCall(requestBuilder.build()).execute()
            if (!response.isSuccessful) {
                response.close()
                return@withContext false
            }

            val rawContentType = response.header("Content-Type") ?: "text/html"
            val mimeType = rawContentType.substringBefore(';').trim().lowercase().ifBlank { "text/html" }
            val contentDisposition = response.header("Content-Disposition")

            val candidate = DownloadHandler.guessResolvedFileName(pageUrl, contentDisposition, mimeType, pageUrl)
            val extension = FileUtils.resolveExtensionFromMimeType(mimeType)
                ?: candidate.substringAfterLast('.', "").ifBlank { "html" }

            val finalFileName = if (candidate.isNotBlank() &&
                !candidate.equals("downloadfile.bin", ignoreCase = true) &&
                !candidate.equals("downloadfile", ignoreCase = true) &&
                !candidate.startsWith("downloadfile.", ignoreCase = true) &&
                candidate.contains('.')
            ) {
                candidate
            } else {
                "${cleanTitle}_${System.currentTimeMillis()}.$extension"
            }

            val tempFile = File(activity.cacheDir, "orig_${System.currentTimeMillis()}_$finalFileName")
            val body = response.body
            if (body == null) {
                response.close()
                return@withContext false
            }

            body.byteStream().use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            response.close()

            if (tempFile.exists() && tempFile.length() > 0) {
                val resolvedMime = FileUtils.resolveMimeTypeForDownload(finalFileName, mimeType)
                publishSavedPageToDownloads(tempFile, finalFileName, resolvedMime, pageUrl)
                true
            } else {
                try { tempFile.delete() } catch (_: Exception) {}
                false
            }
        } catch (e: Exception) {
            Log.w("PageExportCoordinator", "Original resource fetch exception: ${e.message}")
            false
        }
    }

    private fun saveDomAsHtmlFallback(webView: BrowserWebView, cleanTitle: String, pageUrl: String) {
        webView.evaluateJavascript("(function() { return document.documentElement.outerHTML; })();") { htmlJson ->
            if (htmlJson.isNullOrBlank() || htmlJson == "null") {
                Toast.makeText(activity, "Failed to save page", Toast.LENGTH_SHORT).show()
                return@evaluateJavascript
            }

            val decodedHtml = try {
                org.json.JSONTokener(htmlJson).nextValue() as? String ?: htmlJson
            } catch (_: Exception) {
                if (htmlJson.startsWith("\"") && htmlJson.endsWith("\"") && htmlJson.length >= 2) {
                    htmlJson.substring(1, htmlJson.length - 1)
                        .replace("\\\"", "\"")
                        .replace("\\n", "\n")
                        .replace("\\r", "\r")
                        .replace("\\t", "\t")
                        .replace("\\\\", "\\")
                } else {
                    htmlJson
                }
            }

            val fullHtml = if (!decodedHtml.startsWith("<!DOCTYPE", ignoreCase = true)) {
                "<!DOCTYPE html>\n$decodedHtml"
            } else {
                decodedHtml
            }

            val fileName = "${cleanTitle}_${System.currentTimeMillis()}.html"
            val tempFile = File(activity.cacheDir, fileName)

            coroutineScope.launch(Dispatchers.IO) {
                try {
                    tempFile.writeText(fullHtml, Charsets.UTF_8)
                    if (tempFile.exists() && tempFile.length() > 0) {
                        publishSavedPageToDownloads(tempFile, fileName, "text/html", pageUrl)
                    } else {
                        try { tempFile.delete() } catch (_: Exception) {}
                        withContext(Dispatchers.Main) {
                            Toast.makeText(activity, "Failed to save page", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(activity, "Failed to save page: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private suspend fun saveLocalPageAsOriginal(pageUrl: String, cleanTitle: String) = withContext(Dispatchers.IO) {
        try {
            val uri = Uri.parse(pageUrl)
            val stream = if (pageUrl.startsWith("content://", ignoreCase = true)) {
                activity.contentResolver.openInputStream(uri)
            } else {
                File(uri.path ?: "").inputStream()
            } ?: run {
                withContext(Dispatchers.Main) {
                    Toast.makeText(activity, "Cannot open local file", Toast.LENGTH_SHORT).show()
                }
                return@withContext
            }

            val path = uri.path ?: ""
            val originalName = File(path).name.ifBlank { "${cleanTitle}.html" }
            val mimeType = activity.contentResolver.getType(uri)
                ?: FileUtils.resolveMimeTypeForDownload(originalName, null)

            val tempFile = File(activity.cacheDir, "orig_${System.currentTimeMillis()}_$originalName")
            stream.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            if (tempFile.exists() && tempFile.length() > 0) {
                publishSavedPageToDownloads(tempFile, originalName, mimeType, pageUrl)
            } else {
                try { tempFile.delete() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(activity, "Failed to save local file: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun saveDataUriAsOriginal(pageUrl: String, cleanTitle: String) = withContext(Dispatchers.IO) {
        try {
            val commaIndex = pageUrl.indexOf(',')
            if (commaIndex == -1) return@withContext
            val meta = pageUrl.substring(5, commaIndex)
            val dataPart = pageUrl.substring(commaIndex + 1)
            val isBase64 = meta.contains(";base64", ignoreCase = true)
            val mimeType = meta.substringBefore(';').trim().ifBlank { "text/html" }
            val ext = FileUtils.resolveExtensionFromMimeType(mimeType) ?: "html"
            val fileName = "${cleanTitle}_${System.currentTimeMillis()}.$ext"
            val tempFile = File(activity.cacheDir, fileName)

            val bytes = if (isBase64) {
                android.util.Base64.decode(dataPart, android.util.Base64.DEFAULT)
            } else {
                Uri.decode(dataPart).toByteArray(Charsets.UTF_8)
            }
            tempFile.writeBytes(bytes)
            if (tempFile.exists() && tempFile.length() > 0) {
                publishSavedPageToDownloads(tempFile, fileName, mimeType, pageUrl)
            } else {
                try { tempFile.delete() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(activity, "Failed to save data URL: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun saveCurrentPageAsMhtml(webView: BrowserWebView, title: String) {
        try {
            Toast.makeText(activity, R.string.saving_web_archive, Toast.LENGTH_SHORT).show()
            val cleanTitle = title.replace(Regex("[^a-zA-Z0-9.-]"), "_").take(50).ifBlank { "page" }
            val fileName = "${cleanTitle}_${System.currentTimeMillis()}.mht"
            val tempFile = File(activity.cacheDir, fileName)

            webView.saveWebArchive(tempFile.absolutePath, false) { savedPath ->
                if (savedPath != null && tempFile.exists() && tempFile.length() > 0) {
                    val pageUrl = webView.url ?: "about:blank"
                    publishSavedPageToDownloads(tempFile, fileName, "multipart/related", pageUrl)
                } else {
                    try { tempFile.delete() } catch (_: Exception) {}
                    Toast.makeText(activity, "Failed to generate web archive", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(activity, "Failed to save page: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun saveCurrentPageAsPdfDirect(webView: BrowserWebView, title: String) {
        try {
            Toast.makeText(activity, R.string.generating_pdf, Toast.LENGTH_SHORT).show()
            val cleanTitle = title.replace(Regex("[^a-zA-Z0-9.-]"), "_").take(50).ifBlank { "page" }
            val fileName = "${cleanTitle}_${System.currentTimeMillis()}.pdf"
            val tempFile = File(activity.cacheDir, fileName)
            val printAdapter = webView.createPrintDocumentAdapter(cleanTitle)
            val pageUrl = webView.url ?: "about:blank"

            PdfPrintHelper.printToPdf(
                printAdapter,
                tempFile,
                object : PdfPrintHelper.Callback {
                    override fun onSuccess() {
                        if (tempFile.exists() && tempFile.length() > 0) {
                            publishSavedPageToDownloads(tempFile, fileName, "application/pdf", pageUrl)
                        } else {
                            try { tempFile.delete() } catch (_: Exception) {}
                            Toast.makeText(activity, "Failed to generate PDF", Toast.LENGTH_SHORT).show()
                        }
                    }

                    override fun onError(error: String?) {
                        try { tempFile.delete() } catch (_: Exception) {}
                        Toast.makeText(activity, "Failed to export PDF: ${error ?: "Unknown error"}", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        } catch (e: Exception) {
            Toast.makeText(activity, "Failed to start PDF export: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun printCurrentPageSystem(webView: BrowserWebView, title: String) {
        try {
            val printManager = activity.getSystemService(Context.PRINT_SERVICE) as? PrintManager
            if (printManager == null) {
                Toast.makeText(activity, "Printing service unavailable on this device", Toast.LENGTH_SHORT).show()
                return
            }
            val cleanTitle = title.replace(Regex("[^a-zA-Z0-9.-]"), "_").take(50).ifBlank { "page" }
            val printAdapter = webView.createPrintDocumentAdapter(cleanTitle)
            val printAttributes = PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                .build()
            printManager.print(cleanTitle, printAdapter, printAttributes)
        } catch (e: Exception) {
            Toast.makeText(activity, "Failed to export PDF: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private data class SavedFileResult(
        val uri: Uri,
        val fileName: String,
        val filePath: String
    )

    private fun copyTempFileToDownloads(tempFile: File, fileName: String, mimeType: String): SavedFileResult? {
        val resolver = activity.contentResolver
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { out ->
                    tempFile.inputStream().use { input ->
                        input.copyTo(out)
                    }
                }
                var actualName = fileName
                var actualPath = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    fileName
                ).absolutePath

                try {
                    resolver.query(
                        uri,
                        arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATA),
                        null,
                        null,
                        null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                            if (nameIndex != -1) {
                                val name = cursor.getString(nameIndex)
                                if (!name.isNullOrBlank()) {
                                    actualName = name
                                }
                            }
                            val dataIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                            if (dataIndex != -1) {
                                val data = cursor.getString(dataIndex)
                                if (!data.isNullOrBlank()) {
                                    actualPath = data
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}

                val diskFile = File(actualPath)
                val finalPath = if (diskFile.exists() && diskFile.canRead()) {
                    diskFile.absolutePath
                } else {
                    val publicFile = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        actualName
                    )
                    if (publicFile.exists() && publicFile.canRead()) {
                        publicFile.absolutePath
                    } else {
                        uri.toString()
                    }
                }
                SavedFileResult(uri = uri, fileName = actualName, filePath = finalPath)
            } else {
                null
            }
        } else {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val destFile = if (ContextCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                if (!downloadDir.exists()) downloadDir.mkdirs()
                File(downloadDir, fileName)
            } else {
                val appDownloads = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: activity.cacheDir
                File(appDownloads, fileName)
            }
            tempFile.copyTo(destFile, overwrite = true)
            MediaScannerConnection.scanFile(
                activity,
                arrayOf(destFile.absolutePath),
                arrayOf(mimeType),
                null
            )
            SavedFileResult(
                uri = Uri.fromFile(destFile),
                fileName = destFile.name,
                filePath = destFile.absolutePath
            )
        }
    }

    private fun publishSavedPageToDownloads(
        tempFile: File,
        fileName: String,
        mimeType: String,
        pageUrl: String
    ) {
        val fileSize = tempFile.length()
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val result = copyTempFileToDownloads(tempFile, fileName, mimeType)
                if (result != null) {
                    val database = AppDatabase.getInstance(activity)
                    val downloadId = database.downloadDao().insertDownload(
                        DownloadItem(
                            url = pageUrl,
                            fileName = result.fileName,
                            filePath = result.filePath,
                            mimeType = mimeType,
                            fileSize = fileSize,
                            downloadedBytes = fileSize,
                            status = DownloadItem.STATUS_COMPLETED,
                            downloadTime = System.currentTimeMillis()
                        )
                    )

                    try {
                        DownloadNotificationHelper.postDownloadCompletedNotification(
                            context = activity.applicationContext,
                            id = if (downloadId > 0) downloadId else System.currentTimeMillis(),
                            fileName = result.fileName,
                            filePath = result.filePath,
                            mimeType = mimeType,
                            fileSize = fileSize
                        )
                    } catch (e: Exception) {
                        Log.e("PageExportManager", "Failed to post download notification", e)
                    }

                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            activity,
                            "Saved to Downloads/${result.fileName}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(activity, "Failed to export to Downloads", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(activity, "Error saving file: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                try { tempFile.delete() } catch (_: Exception) {}
            }
        }
    }

    companion object {
        private val HTTP_CLIENT: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }
    }
}


typealias PageExportManager = PageExportCoordinator
