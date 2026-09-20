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
import androidx.fragment.app.FragmentActivity
import com.onyx.browser.MainActivity
import com.onyx.browser.data.local.AppDatabase
import com.onyx.browser.data.model.DownloadItem
import com.onyx.browser.data.preferences.BrowserPreferences
import com.onyx.browser.ui.downloads.DownloadPromptDialog
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
        contentLength: Long
    ) {
        if (activity is MainActivity) {
            // Request notification permission on Android 13+ for download notifications
            activity.checkNotificationPermissionForDownloads()
            // Check storage permission on Android 8-9
            activity.checkAndRequestStoragePermission {
                processDownloadWithPrompt(
                    activity = activity,
                    coroutineScope = coroutineScope,
                    url = url,
                    userAgent = userAgent,
                    contentDisposition = contentDisposition,
                    mimeType = mimeType,
                    contentLength = contentLength
                )
            }
        } else {
            processDownloadWithPrompt(
                activity = activity,
                coroutineScope = coroutineScope,
                url = url,
                userAgent = userAgent,
                contentDisposition = contentDisposition,
                mimeType = mimeType,
                contentLength = contentLength
            )
        }
    }

    private fun processDownloadWithPrompt(
        activity: Activity,
        coroutineScope: CoroutineScope,
        url: String,
        userAgent: String,
        contentDisposition: String,
        mimeType: String,
        contentLength: Long
    ) {
        val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val preferences = BrowserPreferences.getInstance(activity)

        if (preferences.askBeforeDownload) {
            val dialog = DownloadPromptDialog.newInstance(
                url = url,
                fileName = fileName,
                fileSize = contentLength,
                mimeType = mimeType,
                userAgent = userAgent
            )
            dialog.onDownloadConfirmed = { customFileName ->
                startSystemDownload(
                    context = activity,
                    coroutineScope = coroutineScope,
                    url = url,
                    userAgent = userAgent,
                    fileName = customFileName,
                    mimeType = mimeType,
                    contentLength = contentLength
                )
            }
            dialog.onExternalDownloadRequested = {
                dispatchToExternalDownloader(
                    context = activity,
                    url = url,
                    mimeType = mimeType,
                    userAgent = userAgent
                )
            }
            dialog.show((activity as FragmentActivity).supportFragmentManager, "DownloadPrompt")
        } else {
            startSystemDownload(
                context = activity,
                coroutineScope = coroutineScope,
                url = url,
                userAgent = userAgent,
                fileName = fileName,
                mimeType = mimeType,
                contentLength = contentLength
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
        contentLength: Long
    ) {
        try {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType)
                val cookies = CookieManager.getInstance().getCookie(url)
                if (!cookies.isNullOrEmpty()) {
                    addRequestHeader("Cookie", cookies)
                }
                addRequestHeader("User-Agent", userAgent)
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
        userAgent: String
    ) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(url), mimeType.ifEmpty { "*/*" })
                val cookies = CookieManager.getInstance().getCookie(url)
                if (!cookies.isNullOrEmpty()) {
                    putExtra("Cookie", cookies)
                    putExtra("cookies", cookies)
                }
                putExtra("User-Agent", userAgent)
                putExtra("user_agent", userAgent)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooser = Intent.createChooser(intent, "Open with Downloader")
            context.startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(context, "No external downloader found", Toast.LENGTH_SHORT).show()
        }
    }
}
