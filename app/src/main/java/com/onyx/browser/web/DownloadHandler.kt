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

object DownloadHandler {

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
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
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
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
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
                setDescription("Downloading $fileName")
                setTitle(fileName)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }

            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadManager.enqueue(request)

            Toast.makeText(context, "Download started: $fileName", Toast.LENGTH_SHORT).show()

            coroutineScope.launch(Dispatchers.IO) {
                val database = AppDatabase.getInstance(context)
                val path = "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)}/$fileName"
                database.downloadDao().insertDownload(
                    DownloadItem(
                        url = url,
                        fileName = fileName,
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
