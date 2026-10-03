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
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.onyx.browser.R
import com.onyx.browser.data.local.AppDatabase
import com.onyx.browser.data.model.DownloadItem
import com.onyx.browser.download.DownloadNotificationHelper
import com.onyx.browser.web.OnyxWebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Controller responsible for saving and exporting web pages as MHTML web archives,
 * direct headless PDFs, or through the Android system print service.
 * Handles Scoped Storage / MediaStore resolution and download database integration.
 */
class PageExportManager(
    private val activity: Activity,
    private val coroutineScope: CoroutineScope
) {

    fun showSavePageDialog(webView: OnyxWebView, title: String) {
        val options = arrayOf(
            activity.getString(R.string.save_as_web_archive),
            activity.getString(R.string.save_as_pdf),
            activity.getString(R.string.print_system_option)
        )
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.save_page_dialog_title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> saveCurrentPageAsMhtml(webView, title)
                    1 -> saveCurrentPageAsPdfDirect(webView, title)
                    2 -> printCurrentPageSystem(webView, title)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    fun saveCurrentPageAsMhtml(webView: OnyxWebView, title: String) {
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

    fun saveCurrentPageAsPdfDirect(webView: OnyxWebView, title: String) {
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

    fun printCurrentPageSystem(webView: OnyxWebView, title: String) {
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
}
