package com.onyx.browser.web

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.widget.Toast
import com.onyx.browser.MainActivity
import androidx.fragment.app.FragmentActivity
import com.onyx.browser.data.local.AppDatabase
import com.onyx.browser.data.model.DownloadItem
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.ui.downloads.DownloadPromptActivity
import com.onyx.browser.ui.downloads.DownloadPromptBottomSheet
import com.onyx.browser.ui.downloads.ExternalDownloaderHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

import android.util.Base64
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder

object DownloadHandler {

    fun sanitizeFileName(name: String): String {
        val clean = name.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim()
        return if (clean.isBlank()) "download_${System.currentTimeMillis()}" else clean
    }

    fun handleDownload(
        activity: Activity,
        coroutineScope: CoroutineScope,
        url: String,
        userAgent: String,
        contentDisposition: String,
        mimeType: String,
        contentLength: Long,
        cookies: String = "",
        referer: String = ""
    ) {
        if (url.startsWith("data:", ignoreCase = true)) {
            handleDataUriDownload(activity, coroutineScope, url, contentDisposition, mimeType)
            return
        }

        if (url.startsWith("blob:", ignoreCase = true)) {
            handleBlobUriDownload(activity, coroutineScope, url, contentDisposition, mimeType)
            return
        }

        val resolvedCookies = if (cookies.isNotBlank()) cookies else {
            try {
                CookieManager.getInstance().getCookie(url) ?: ""
            } catch (_: Exception) {
                ""
            }
        }

        val preferences = BrowserPreferences.getInstance(activity)
        val behavior = preferences.downloadManagerBehavior

        if (behavior == 0) {
            // Ask before download
            if (activity is FragmentActivity) {
                val sheet = DownloadPromptBottomSheet.newInstance(
                    url = url,
                    userAgent = userAgent,
                    contentDisposition = contentDisposition,
                    mimeType = mimeType,
                    contentLength = contentLength,
                    cookies = resolvedCookies,
                    referer = referer
                )
                sheet.show(activity.supportFragmentManager, DownloadPromptBottomSheet.TAG)
            } else {
                val intent = Intent(activity, DownloadPromptActivity::class.java).apply {
                    putExtra(DownloadPromptActivity.EXTRA_URL, url)
                    putExtra(DownloadPromptActivity.EXTRA_USER_AGENT, userAgent)
                    putExtra(DownloadPromptActivity.EXTRA_CONTENT_DISPOSITION, contentDisposition)
                    putExtra(DownloadPromptActivity.EXTRA_MIME_TYPE, mimeType)
                    putExtra(DownloadPromptActivity.EXTRA_CONTENT_LENGTH, contentLength)
                    putExtra(DownloadPromptActivity.EXTRA_COOKIES, resolvedCookies)
                    putExtra(DownloadPromptActivity.EXTRA_REFERER, referer)
                }
                activity.startActivity(intent)
            }
        } else if (behavior == 1) {
            // Internal download
            val rawFileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val fileName = sanitizeFileName(rawFileName)
            startSystemDownload(
                context = activity,
                coroutineScope = coroutineScope,
                url = url,
                userAgent = userAgent,
                fileName = fileName,
                mimeType = mimeType,
                contentLength = contentLength,
                cookies = resolvedCookies,
                referer = referer
            )
        } else {
            // External download manager
            val rawFileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val fileName = sanitizeFileName(rawFileName)
            dispatchToExternalDownloader(
                context = activity,
                url = url,
                mimeType = mimeType,
                userAgent = userAgent,
                fileName = fileName,
                cookies = resolvedCookies,
                referer = referer
            )
        }
    }

    fun handleDataUriDownload(
        context: Context,
        coroutineScope: CoroutineScope,
        dataUri: String,
        contentDisposition: String = "",
        mimeType: String = "",
        suggestedFileName: String? = null
    ) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val commaIndex = dataUri.indexOf(',')
                if (commaIndex == -1) throw IllegalArgumentException("Invalid data URI")

                val header = dataUri.substring(5, commaIndex)
                val isBase64 = header.contains(";base64", ignoreCase = true)
                val detectedMime = header.substringBefore(';').ifBlank { mimeType.ifBlank { "application/octet-stream" } }

                val rawData = dataUri.substring(commaIndex + 1)
                val bytes = if (isBase64) {
                    Base64.decode(rawData, Base64.DEFAULT)
                } else {
                    URLDecoder.decode(rawData, "UTF-8").toByteArray()
                }

                val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(detectedMime) ?: "bin"
                val resolvedName = if (!suggestedFileName.isNullOrBlank()) {
                    suggestedFileName
                } else {
                    val guessed = URLUtil.guessFileName(dataUri, contentDisposition, detectedMime)
                    if (guessed.isNotBlank() && !guessed.endsWith(".bin")) guessed else "download_${System.currentTimeMillis()}.$ext"
                }
                val fileName = sanitizeFileName(resolvedName)

                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                val targetFile = File(dir, fileName)
                FileOutputStream(targetFile).use { it.write(bytes) }

                val database = AppDatabase.getInstance(context)
                database.downloadDao().insertDownload(
                    DownloadItem(
                        url = "data:$detectedMime;base64,...",
                        fileName = fileName,
                        filePath = targetFile.absolutePath,
                        mimeType = detectedMime,
                        fileSize = bytes.size.toLong(),
                        status = DownloadItem.STATUS_COMPLETED
                    )
                )

                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Download complete: $fileName", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Data download failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun handleBlobUriDownload(
        activity: Activity,
        coroutineScope: CoroutineScope,
        blobUrl: String,
        contentDisposition: String,
        mimeType: String
    ) {
        val mainAct = activity as? MainActivity
        val webView = mainAct?.getActiveWebView()
        if (webView == null) {
            Toast.makeText(activity, "Cannot download blob without active webpage", Toast.LENGTH_SHORT).show()
            return
        }

        val rawFileName = URLUtil.guessFileName(blobUrl, contentDisposition, mimeType)
        val fileName = sanitizeFileName(rawFileName)

        val script = """
            (function() {
                try {
                    fetch('$blobUrl').then(function(r) { return r.blob(); }).then(function(blob) {
                        var reader = new FileReader();
                        reader.onloadend = function() {
                            if (window.OnyxBlobBridge) {
                                window.OnyxBlobBridge.onBlobDownloaded(reader.result, '$fileName', '$mimeType');
                            }
                        };
                        reader.readAsDataURL(blob);
                    }).catch(function(err) {
                        if (window.OnyxBlobBridge) window.OnyxBlobBridge.onBlobFailed(err.toString());
                    });
                } catch(e) {
                    if (window.OnyxBlobBridge) window.OnyxBlobBridge.onBlobFailed(e.toString());
                }
            })();
        """.trimIndent()

        webView.evaluateJavascript(script, null)
    }

    fun startSystemDownload(
        context: Context,
        coroutineScope: CoroutineScope,
        url: String,
        userAgent: String,
        fileName: String,
        mimeType: String,
        contentLength: Long,
        cookies: String = "",
        referer: String = ""
    ) {
        try {
            val cleanFileName = sanitizeFileName(fileName)
            val resolvedCookies = if (cookies.isNotBlank()) cookies else {
                try {
                    CookieManager.getInstance().getCookie(url) ?: ""
                } catch (_: Exception) {
                    ""
                }
            }

            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType.ifBlank { "*/*" })
                if (resolvedCookies.isNotBlank()) {
                    addRequestHeader("Cookie", resolvedCookies)
                }
                if (userAgent.isNotBlank()) {
                    addRequestHeader("User-Agent", userAgent)
                }
                if (referer.isNotBlank()) {
                    addRequestHeader("Referer", referer)
                }
                setDescription("Downloading $cleanFileName")
                setTitle(cleanFileName)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, cleanFileName)
            }

            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val downloadId = downloadManager.enqueue(request)

            Toast.makeText(context, "Download started: $cleanFileName", Toast.LENGTH_SHORT).show()

            coroutineScope.launch(Dispatchers.IO) {
                val database = AppDatabase.getInstance(context)
                val path = "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)}/$cleanFileName"
                database.downloadDao().insertDownload(
                    DownloadItem(
                        downloadId = downloadId,
                        url = url,
                        fileName = cleanFileName,
                        filePath = path,
                        mimeType = mimeType,
                        fileSize = contentLength,
                        status = DownloadItem.STATUS_RUNNING
                    )
                )
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun dispatchToExternalDownloader(
        context: Context,
        url: String,
        mimeType: String,
        userAgent: String,
        fileName: String = "",
        cookies: String = "",
        referer: String = ""
    ) {
        val downloaders = ExternalDownloaderHelper.getInstalledDownloaders(context, url, mimeType)
        val resolvedCookies = if (cookies.isNotBlank()) cookies else {
            try {
                CookieManager.getInstance().getCookie(url) ?: ""
            } catch (_: Exception) {
                ""
            }
        }
        val resolvedFileName = if (fileName.isNotBlank()) fileName else URLUtil.guessFileName(url, null, mimeType)

        if (downloaders.size == 1) {
            val single = downloaders.first()
            val intent = ExternalDownloaderHelper.buildDownloadIntent(
                downloader = single,
                url = url,
                fileName = resolvedFileName,
                userAgent = userAgent,
                cookies = resolvedCookies,
                referer = referer,
                mimeType = mimeType
            )
            try {
                context.startActivity(intent)
                Toast.makeText(context, "Opening in ${single.name}", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to launch ${single.name}", Toast.LENGTH_SHORT).show()
            }
        } else {
            // Fallback generic intent
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse(url), mimeType.ifEmpty { "*/*" })
                    if (resolvedCookies.isNotBlank()) {
                        putExtra("Cookie", resolvedCookies)
                        putExtra("cookies", resolvedCookies)
                    }
                    if (userAgent.isNotBlank()) {
                        putExtra("User-Agent", userAgent)
                        putExtra("user_agent", userAgent)
                    }
                    if (referer.isNotBlank()) {
                        putExtra("Referer", referer)
                        putExtra("referer", referer)
                    }
                    putExtra("extra_filename", resolvedFileName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val chooser = Intent.createChooser(intent, "Open with Downloader")
                context.startActivity(chooser)
            } catch (e: Exception) {
                Toast.makeText(context, "No external downloader found", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
